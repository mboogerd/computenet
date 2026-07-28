package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import java.util.concurrent.ConcurrentHashMap

/**
 * M2 — the error lane feed (`doc/spec/90-roadmap/97-inspector-plan/tickets/M2-BE.md`):
 * `GET /api/inspect/errors` plus the `error.deadLetter` / `error.parked` /
 * `error.restart` SSE events, built entirely from seams that already exist —
 * no kernel changes.
 *
 * Three independent sources, none of them a per-message hook (P2):
 *
 * - **Dead letters** arrive push-style, one per [ManagedHost.deadLetterOutlet]
 *   emission, via an Observe-role [civictech.cell.port.FanOutlet.tap] — never
 *   [civictech.cell.port.FanOutlet.subscribe] ("consuming"), because a dead
 *   letter can carry a sanitized-but-still-live payload reference and the
 *   inspector must not become a second consumer of it (ownership invariant).
 *   Retained in a bounded [RingBuffer] (cap 200, oldest evicted) — the
 *   contract's "current dead letters".
 * - **Parked traffic** ([LocationRegistry.parkedFor]) and **restarts**
 *   ([ManagedHost.generationOf]) have no push signal at all — the kernel
 *   exposes them as plain reads — so [poll] samples both on a timer (contract
 *   §Implement: "poll parked counts on a 2 s timer — cheap registry reads, no
 *   subscriptions"). Parked rows are a live gauge, recomputed fresh on every
 *   read/poll, never retained history. Restarts, like dead letters, are
 *   retained in a bounded [RingBuffer] — a generation increase is a discrete
 *   event the kernel has no log of, so observing it is the only way to build
 *   the contract's `restarts[]` history at all.
 *
 * "Known refs" for both timer-driven sources is [LocationRegistry.localRefs]
 * — the same set [InspectorModel] treats as known (`InspectorModel.knows`) —
 * so error data never reaches further than the topology the client can
 * already see.
 */
internal class Errors(
    private val registry: LocationRegistry,
    private val hosts: Map<String, ManagedHost>,
    private val onDeadLetter: (DeadLetterRow) -> Unit,
    private val onParked: (ParkedRow) -> Unit,
    private val onRestart: (RestartRow) -> Unit,
    private val ringCapacity: Int = RING_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val deadLetterRing = RingBuffer<DeadLetterRow>(ringCapacity)
    private val restartRing = RingBuffer<RestartRow>(ringCapacity)

    /** Poll-thread-only bookkeeping (the single scheduled task in [InspectorServer.start] drives [poll]). */
    private val lastGeneration = HashMap<CellRef, Long>()
    private val lastParkedCounts = HashMap<Pair<CellRef, String>, Int>()

    /**
     * When a `(ref, port)` parked pair was first observed, for [ParkedRow.oldestMs].
     * Read from [snapshot] (an HTTP thread) as well as written from [poll] (the
     * scheduled thread) — a plain map would race, so this one is concurrent.
     */
    private val parkFirstSeenMs = ConcurrentHashMap<Pair<CellRef, String>, Long>()

    /**
     * One Observe-role tap per host, installed at construction — symmetric
     * with [InspectorModel]'s topology hooks, which also attach in `init`
     * rather than in [InspectorServer.start]. A plain [Use.fixed] target is
     * never [civictech.cell.link.Linked], so [civictech.cell.port.FanOutlet.tap]
     * always takes its unnegotiated fallback and returns
     * [LinkResult.Connected] — there is no rejection branch to handle.
     */
    private val deadLetterTaps: List<Link> = hosts.values.map { host ->
        val result = host.deadLetterOutlet.tap(
            Use.fixed(Propagate<DeadLetter> { onDeadLetterReceived(it) }, PortRef.generate()),
        )
        (result as LinkResult.Connected).link
    }

    /**
     * `GET /api/inspect/errors`. Counters are true totals
     * ([ManagedHost.supervisionAccounting] summed across hosts, plus the live
     * parked sum); [deadLetters] and [restarts] are the ring buffers'
     * contents; [parked] is recomputed fresh, never cached.
     */
    fun snapshot(): ErrorSnapshot {
        val parkedRows = parkedRows(parkedCounts(), clock())
        val accounting = hosts.values.map { it.supervisionAccounting() }
        return ErrorSnapshot(
            counters = ErrorCounters(
                deadLetters = accounting.sumOf { it.deadLetters },
                parked = parkedRows.sumOf { it.count.toLong() },
                restarts = accounting.sumOf { it.restarts },
                drainedOnTeardown = accounting.sumOf { it.parkedDrainedOnTeardown },
            ),
            deadLetters = deadLetterRing.snapshot(),
            parked = parkedRows,
            restarts = restartRing.snapshot(),
        )
    }

    /**
     * The 2 s timer sample (contract §Implement 2). Cheap registry reads only
     * — no subscriptions, nothing on the data path (P2/P6).
     */
    fun poll() {
        val now = clock()
        pollParked(now)
        pollRestarts(now)
    }

    override fun close() {
        deadLetterTaps.forEach { runCatching { it.unlink() } }
    }

    // --------------------------------------------------------- dead letters

    /**
     * Extracts strings/primitives from [letter] immediately and drops the
     * reference right here — the ticket's "never retain the payload object
     * itself beyond serialization". Neither the [DeadLetter] nor its
     * [civictech.cell.proxy.HostedPortInvocation] survives this call.
     */
    private fun onDeadLetterReceived(letter: DeadLetter) {
        val ref = letter.invocation?.cellRef ?: letter.hostRef
        val wave = letter.invocation?.invocation?.context?.timestamp
        val row = DeadLetterRow(
            ref = InspectorServer.encodeRef(ref),
            cause = letter.cause?.javaClass?.simpleName,
            description = letter.description,
            wave = wave?.let { WaveStamp(it.sourceId.toString(), it.counter) },
            atMs = clock(),
        )
        deadLetterRing.add(row)
        onDeadLetter(row)
    }

    // ------------------------------------------------------------- parked

    /** Every currently parked `(ref, port)` pair and its live count, across known refs. */
    private fun parkedCounts(): Map<Pair<CellRef, String>, Int> {
        val counts = LinkedHashMap<Pair<CellRef, String>, Int>()
        registry.localRefs().forEach { ref ->
            registry.parkedFor(ref).groupingBy { it.portName }.eachCount()
                .forEach { (port, count) -> counts[ref to port] = count }
        }
        return counts
    }

    private fun parkedRows(counts: Map<Pair<CellRef, String>, Int>, now: Long): List<ParkedRow> =
        counts.map { (key, count) ->
            val firstSeen = parkFirstSeenMs.getOrPut(key) { now }
            ParkedRow(InspectorServer.encodeRef(key.first), key.second, count, (now - firstSeen).coerceAtLeast(0))
        }

    /**
     * Diffs this tick's [parkedCounts] against [lastParkedCounts]: any changed
     * or newly-parked pair emits its fresh row, any pair that fully drained
     * (present before, absent now) emits the contract's `count: 0` clear and
     * drops its [parkFirstSeenMs] entry — the next park of that pair starts a
     * fresh age rather than inheriting the old one.
     */
    private fun pollParked(now: Long) {
        val counts = parkedCounts()
        counts.forEach { (key, count) ->
            if (lastParkedCounts[key] != count) {
                lastParkedCounts[key] = count
                val firstSeen = parkFirstSeenMs.getOrPut(key) { now }
                onParked(ParkedRow(InspectorServer.encodeRef(key.first), key.second, count, (now - firstSeen).coerceAtLeast(0)))
            }
        }
        (lastParkedCounts.keys - counts.keys).forEach { key ->
            lastParkedCounts.remove(key)
            parkFirstSeenMs.remove(key)
            onParked(ParkedRow(InspectorServer.encodeRef(key.first), key.second, 0, 0))
        }
    }

    // ------------------------------------------------------------ restarts

    /**
     * Compares each known ref's current [ManagedHost.generationOf] against
     * the last-observed value. A ref seen for the first time only seeds the
     * baseline — a restart that happened before this poller started watching
     * is not fabricated as one that just happened. Refs no longer known are
     * dropped from the map so a despawned cell's generation is not tracked
     * forever.
     */
    private fun pollRestarts(now: Long) {
        val known = registry.localRefs()
        known.forEach { ref ->
            val host = registry.locate(ref) ?: return@forEach
            val generation = host.generationOf(ref)
            val prior = lastGeneration.put(ref, generation)
            if (prior != null && generation > prior) {
                val row = RestartRow(InspectorServer.encodeRef(ref), generation, now)
                restartRing.add(row)
                onRestart(row)
            }
        }
        lastGeneration.keys.retainAll(known)
    }

    private companion object {
        const val RING_CAPACITY = 200
    }
}

/**
 * A bounded FIFO retaining at most [capacity] items, oldest evicted first —
 * the contract's "current dead letters retained in a bounded ring buffer".
 * Internally synchronized: [Errors.onDeadLetterReceived] can run concurrently
 * on more than one host's scheduler thread, and [Errors.pollRestarts] runs on
 * the shared heartbeat thread while [Errors.snapshot] reads from an HTTP
 * thread.
 */
internal class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()

    @Synchronized
    fun add(item: T) {
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<T> = items.toList()
}
