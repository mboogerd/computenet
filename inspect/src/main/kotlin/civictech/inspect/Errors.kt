package civictech.inspect

import civictech.cell.Borrowed
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Redacted
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
    /**
     * V3 — the currently open wave-health rows ([WaveHealth.openRows]), folded
     * into this snapshot rather than served on a route of their own: they are an
     * error class, and `ErrorSnapshot` is where the client already looks.
     */
    private val waveHealthRows: () -> List<WaveHealthRow> = ::emptyList,
    /**
     * V3 — when a re-baseline beat was last observed on one of [CellRef]'s
     * tapped outlets ([FlowCollector.reBaselineAtMsOf]), or null. Only ever
     * consulted at the instant a generation bump is observed.
     */
    private val reBaselineAtMsOf: (CellRef) -> Long? = { null },
    private val ringCapacity: Int = RING_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val deadLetterRing = RingBuffer<DeadLetterRow>(ringCapacity)
    private val restartRing = RingBuffer<RestartRow>(ringCapacity)

    /** Poll-thread-only bookkeeping (the single scheduled task in [InspectorServer.start] drives [poll]). */
    private val lastGeneration = HashMap<CellRef, Long>()
    private val lastParkedCounts = HashMap<Pair<CellRef, String>, Int>()

    /**
     * V3 — the most recent *thrown* dead letter captured per ref, for
     * [pollRestarts]'s cause correlation. Written from whichever host thread the
     * dead-letter tap fires on and read from the poll thread, so it is
     * concurrent; pruned to [RESTART_CAUSE_WINDOW_MS] on every poll, so it is
     * bounded by the refs that failed in the last five seconds rather than by
     * the graph.
     *
     * Only dead letters carrying a throwable are candidates. That is not a
     * convenience: a `SupervisionPolicy.RESTART` is reached exclusively from
     * `ManagedHost`'s catch block, which always dead-letters with the throwable
     * it caught, so a causeless drop can never be the cause of a restart.
     */
    private val lastFailure = ConcurrentHashMap<CellRef, Failure>()

    /**
     * The re-baseline beat already attributed to a restart of this ref, so one
     * beat is never reported twice across two restarts.
     */
    private val attributedReBaseline = HashMap<CellRef, Long>()

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
        // one read, used for both the gauge and the rows, so a row opening
        // between the two could not make them disagree
        val waveHealth = waveHealthRows()
        return ErrorSnapshot(
            counters = ErrorCounters(
                deadLetters = accounting.sumOf { it.deadLetters },
                parked = parkedRows.sumOf { it.count.toLong() },
                restarts = accounting.sumOf { it.restarts },
                drainedOnTeardown = accounting.sumOf { it.parkedDrainedOnTeardown },
                waveHealth = waveHealth.size.toLong(),
            ),
            deadLetters = deadLetterRing.snapshot(),
            parked = parkedRows,
            restarts = restartRing.snapshot(),
            waveHealth = waveHealth,
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
     *
     * **V3 widens what is extracted, not how long anything is held.** The
     * invocation summary is port name / invocation type / method name /
     * *declared* parameter type names / argument count / hop, and the
     * disposition list is one ownership *class name* per argument plus, for a
     * [civictech.cell.Redacted] stand-in, the kernel's own reason string
     * truncated at [REDACTION_REASON_MAX]. No argument value is read, copied,
     * `toString()`-ed, encoded or referenced — [dispositionOf] receives each
     * argument only to ask what class it is.
     *
     * computenet-usd.7 — [letter]'s `denial` (non-null only for a
     * `BoundaryPolicy` refusal, never a fault) is extracted the same way, to
     * [BoundaryDenialSummary]: this is the structural discriminator that lets
     * a client tell a refusal apart from a plain host-level drop, both of
     * which are `cause == null` and previously shared one string-prefixed
     * [description].
     */
    private fun onDeadLetterReceived(letter: DeadLetter) {
        val hosted = letter.invocation
        val ref = hosted?.cellRef ?: letter.hostRef
        val wave = hosted?.invocation?.context?.timestamp
        val args = hosted?.invocation?.args.orEmpty()
        val row = DeadLetterRow(
            ref = InspectorServer.encodeRef(ref),
            cause = letter.cause?.javaClass?.simpleName,
            description = letter.description,
            wave = wave?.let { WaveStamp(it.sourceId.toString(), it.counter) },
            atMs = clock(),
            invocation = hosted?.let {
                InvocationSummary(
                    port = it.portName,
                    type = it.type.name,
                    method = it.invocation.methodName,
                    parameterTypes = it.invocation.parameterTypes.toList(),
                    argCount = args.size,
                    hop = it.invocation.context?.hop,
                )
            },
            disposition = args.mapIndexed { index, arg -> dispositionOf(index, arg) },
            denial = letter.denial?.let {
                BoundaryDenialSummary(
                    seam = it.seam.name,
                    reason = it.reason.name,
                    exposure = it.exposure,
                    principal = it.principal?.name,
                    subject = it.subject,
                    detail = it.detail,
                )
            },
        )
        // V3 — the restart-cause candidate, recorded from the same primitives
        // the row already carries and keyed by ref. See [lastFailure].
        val cause = row.cause
        if (cause != null) lastFailure[ref] = Failure(cause, row.atMs)
        deadLetterRing.add(row)
        onDeadLetter(row)
    }

    /**
     * One argument's ownership disposition, classified by its **runtime class
     * only** (`civictech.cell.Ownership`).
     *
     * `DeadLetters.sanitizeForDeadLetter` has already run before the outlet
     * fanned this letter (spec 23 R8, G-46): an `Owned` arrives as `Frozen`, or
     * as `Redacted` when it had been consumed before capture; a `Leased`
     * arrives released and replaced by `Redacted`. Reading that outcome back is
     * exactly the thing an operator needs — "the exclusive payload on this
     * failed call was frozen / was released / was already gone".
     *
     * [ArgDisposition.OWNED] and [ArgDisposition.LEASED] are the honesty case: a
     * live exclusive handle must never reach a fan-out outlet, and if one ever
     * does the row says so rather than mislabelling it `plain`.
     */
    private fun dispositionOf(index: Int, arg: Any?): ArgDisposition = when (arg) {
        is Frozen<*> -> ArgDisposition(index, ArgDisposition.FROZEN)
        is Redacted -> ArgDisposition(index, ArgDisposition.REDACTED, arg.reason.take(REDACTION_REASON_MAX))
        is Borrowed<*> -> ArgDisposition(index, ArgDisposition.BORROWED)
        is Owned<*> -> ArgDisposition(index, ArgDisposition.OWNED)
        is Leased<*> -> ArgDisposition(index, ArgDisposition.LEASED)
        else -> ArgDisposition(index, ArgDisposition.PLAIN)
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
                val cause = causeFor(ref, now)
                val row = RestartRow(
                    ref = InspectorServer.encodeRef(ref),
                    generation = generation,
                    atMs = now,
                    cause = cause?.cause,
                    causeAtMs = cause?.atMs,
                    reBaselineAtMs = reBaselineFor(ref, now),
                )
                restartRing.add(row)
                onRestart(row)
            }
        }
        lastGeneration.keys.retainAll(known)
        lastFailure.entries.removeIf { now - it.value.atMs > RESTART_CAUSE_WINDOW_MS }
        attributedReBaseline.keys.retainAll(known)
    }

    /**
     * **A correlation, not a kernel-reported restart cause.** The kernel
     * dead-letters the failure *before* it bumps the generation (`ManagedHost`'s
     * RESTART branch: `deadLetter(e, …)` then `generations[cellRef] = … + 1`),
     * and this class already captures both — but no seam reports the two as one
     * event, so all that can honestly be done is pair a generation bump with the
     * most recent *thrown* dead letter for the same ref inside
     * [RESTART_CAUSE_WINDOW_MS] preceding it.
     *
     * A coincidental dead letter for that ref inside the window **would** be
     * attributed here. The window is deliberately only just wider than the 2 s
     * poll period that bounds how late a bump can be noticed, which is the only
     * lever available to keep that coincidence narrow. Null when no candidate
     * exists — never a guess.
     */
    private fun causeFor(ref: CellRef, now: Long): Failure? =
        lastFailure[ref]?.takeIf { now - it.atMs <= RESTART_CAUSE_WINDOW_MS }

    /**
     * The re-baseline beat this restart completed with, when one was
     * **observed** on a tapped outlet of [ref] — see [RestartRow.reBaselineAtMs]
     * for why null is "not observed" and never "did not happen".
     *
     * Two conditions, both necessary: the beat must be recent enough to belong
     * to *this* restart (the same window the cause correlation uses), and it
     * must not already have been attributed to an earlier one — a cell that
     * re-baselined once and then restarted again without emitting would
     * otherwise report the stale beat a second time.
     */
    private fun reBaselineFor(ref: CellRef, now: Long): Long? {
        val beat = reBaselineAtMsOf(ref) ?: return null
        if (now - beat > RESTART_CAUSE_WINDOW_MS) return null
        if (beat <= (attributedReBaseline[ref] ?: 0L)) return null
        attributedReBaseline[ref] = beat
        return beat
    }

    /** A captured thrown dead letter, as a restart-cause candidate (see [lastFailure]). */
    private class Failure(val cause: String, val atMs: Long)

    internal companion object {
        const val RING_CAPACITY = 200

        /**
         * How far back a generation bump may look for the dead letter that
         * explains it. Comfortably above the 2 s error-poll period
         * ([InspectorServer.ERROR_POLL_SECONDS]) so a restart noticed one whole
         * poll late still finds its cause, and no wider than that — every extra
         * second widens the coincidence window [causeFor] describes.
         */
        const val RESTART_CAUSE_WINDOW_MS = 5_000L

        /**
         * Cap on the kernel-authored `Redacted.reason` copied onto an
         * [ArgDisposition]. The kernel's own reasons are two short sentences;
         * the cap exists so a future (or application-authored) reason cannot
         * turn a bounded diagnostic row into an unbounded one.
         */
        const val REDACTION_REASON_MAX = 200
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
