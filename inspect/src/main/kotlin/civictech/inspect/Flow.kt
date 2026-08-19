package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.host.LocationRegistry
import civictech.cell.host.TopologyLink
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The topology-facing half of the flow feed, so [InspectorModel] can record and
 * retract edges without knowing how they are observed. [FlowCollector] is the
 * only real implementation; [None] is what a model constructed without a
 * collector (M0/M1/M2 tests) uses, and it reproduces M0's behaviour exactly —
 * every edge's `fused` stays the contract's `null`.
 */
internal interface FlowBinding {

    /**
     * Start observing [link]'s traffic, and answer the contract's `Edge.fused`
     * for it: `false` when the producing endpoint is a tappable outlet, `true`
     * when it is a locally hosted port with no emission point of its own, and
     * `null` when this inspector cannot tell (a non-local or free-standing
     * producer). Idempotent per `link.id`.
     */
    fun bind(link: TopologyLink): Boolean?

    /** Stop observing the edge [id] — an unlink. Idempotent. */
    fun unbind(id: UUID)

    /** Stop observing every edge produced by [ref] — a despawn. */
    fun dropCell(ref: CellRef)

    companion object {
        val None: FlowBinding = object : FlowBinding {
            override fun bind(link: TopologyLink): Boolean? = null
            override fun unbind(id: UUID) = Unit
            override fun dropCell(ref: CellRef) = Unit
        }
    }
}

/**
 * M3 — the flow feed (`doc/spec/90-roadmap/97-inspector-plan/tickets/M3-BE.md`):
 * per-edge message rates, sampled at the one seam built for exactly this,
 * [FanOutlet.observe] (spec 20/23 §Taps, G-47).
 *
 * ### Why a tap, and why at the outlet
 *
 * [FanOutlet.observe] is the Observe-role attachment in its payload-agnostic
 * shape: uncounted by the SPSC funnel, never an expected sibling of a
 * wave-completeness set ([civictech.cell.consistency.WaveFrontier] filters
 * [civictech.cell.link.LinkRole.Consume]), always admitted, and fired *before*
 * the outlet's consumers — the same storage and the same firing position as a
 * contract-typed [FanOutlet.tap]. Attaching a `Propagate` **consumer** instead
 * — the only other way to see traffic from outside the graph — would be a
 * semantic intrusion on three counts at once: it would be refused outright on
 * an `Owned`/`Leased` contract (SPSC), it would enter every downstream join's
 * completeness condition, and it would take delivery of payloads the inspector
 * has no business touching. The observe attachment does none of that, and it
 * is not merely disciplined about the payload but structurally incapable of
 * reaching it: the handler below is handed a [MessageContext] and nothing
 * else, so payloads stay `Borrowed` in the strictest sense — unread, uncopied,
 * unretained (10-target-v3 §Constraints 3).
 *
 * The outlet is also the only point that sees *all* of an edge's traffic. Spec
 * 20/21 §Fusion: co-hosted chains run as nested direct calls, so a same-host
 * edge never passes through `ManagedHost.enqueueHostedInvocation` at all —
 * measured, not assumed: a two-cell co-hosted `SetCell → SetCell` graph driven
 * with five `add`s produces five host-queue entries, all of them the *ingress*
 * to the first cell's inlet, and zero for the edge between the two. The tap
 * fires on the emission itself, upstream of the decision to call directly or to
 * enqueue, so it sees fused and cross-host edges identically (P2's promise runs
 * the other way: observation costs the same either way and adds no hop).
 *
 * ### Attribution
 *
 * An Observe-role attachment fires once per *emission*, not once per consumer,
 * and a [FanOutlet] broadcasts: every one of an outlet's consume links carries
 * every emission. So an outlet's emission count is each of its outgoing edges'
 * message count — duplicated across them, never divided. Rates are therefore
 * exact per edge for the broadcast fan-out the kernel implements, and the only
 * traffic they omit is what genuinely never reaches a link's consumer:
 * [FanOutlet.at] deliveries (the `onLinked` catch-up baseline and pull replies,
 * which bypass taps by construction) and emissions a `disclosureFilter`
 * suppresses — the kernel gates a payload-agnostic observer on exactly the same
 * filter verdict it gates a delivery on, so that omission is unchanged.
 *
 * ### Cost on the graph thread
 *
 * Per message per tapped outlet, the handler does one
 * `AtomicLong.incrementAndGet` and one volatile reference store. No
 * allocation, no map lookup, no lock, no reflective dispatch, not even a
 * thread-local read — the outlet hands the [MessageContext] straight to the
 * handler — and nothing touching the payload. The recorded [MessageContext] is
 * the wave-plane object the outlet had already built — carrying only a
 * timestamp, a port ref and a hop count — so keeping the last one retains
 * nothing of the message.
 *
 * The aggregation half never runs on a graph thread: [sample] is driven by the
 * inspector's own single scheduler thread, and the batch it produces is handed
 * to the SSE broadcaster's per-client bounded queues, which drop frames rather
 * than wait (10-target-v3 §Constraints 6 — viz never blocks the graph).
 */
internal class FlowCollector(
    private val registry: LocationRegistry,
    /** Non-blocking sink for one aggregated window. */
    private val onBatch: (FlowBatch) -> Unit,
    /** The contract's `flow.rates.window`, in milliseconds. */
    private val windowMs: Long = WINDOW_MS,
    /**
     * Wall clock, read **on a graph thread** by the re-baseline branch in
     * [TapSite]'s handler and nowhere else (V3-BE part 2). Injectable so a test
     * can pin the timeline without sleeping; the default is the same
     * `System::currentTimeMillis` every other collaborator here uses.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) : FlowBinding, AutoCloseable {

    private val lock = Any()

    /** Live edges, by id — the mapping from a contract `Edge.id` to its producing outlet. */
    private val edges = LinkedHashMap<UUID, PortRef>()

    /**
     * The *consuming* cell of each live edge, by id (V3-BE). [edges] answers
     * "which outlet produces this edge"; the wave-health evaluator additionally
     * needs "and which cell does it feed", so it can pair a tapped outlet's
     * last wave with the frontier of an observed cell downstream of it. A
     * free-standing consumer with no cell (a `Use.fixed` target) is simply not
     * recorded — it can never be an observed subject.
     */
    private val edgeTargets = LinkedHashMap<UUID, CellRef>()

    /** One tap per producing outlet, shared by every edge that outlet feeds. */
    private val sites = LinkedHashMap<PortRef, TapSite>()

    /** Did the last published window have anything tapped? Drives the trailing all-quiet batch. */
    private var everSampled = false

    override fun bind(link: TopologyLink): Boolean? {
        val producer = link.from
        val cell = producer.cell ?: return null
        // A producer this registry does not host locally cannot be reached as
        // an object at all (a mirrored/announced edge, spec 42) — the contract's
        // "null when unknown", never a guess.
        val host = registry.locate(cell) ?: return null
        val outlet = host.outletAt(producer)
            // Locally hosted, but the endpoint is not an emission point: the
            // delivery is a delegating pass-through (spec 20/21 §Fusion, 10/14
            // flattening), so this edge carries no observable message. Fused,
            // and deliberately never a zero rate — the two say different things.
            ?: return true

        synchronized(lock) {
            if (link.id in edges) return false
            edges[link.id] = producer
            link.to.cell?.let { edgeTargets[link.id] = it }
            sites.getOrPut(producer) { TapSite(outlet, clock) }.edges += link.id
        }
        return false
    }

    override fun unbind(id: UUID) {
        val orphaned = synchronized(lock) {
            edgeTargets.remove(id)
            val producer = edges.remove(id) ?: return
            val site = sites[producer] ?: return
            site.edges -= id
            if (site.edges.isNotEmpty()) return
            sites.remove(producer)
            site
        }
        orphaned.detach()
    }

    override fun dropCell(ref: CellRef) {
        // Despawn does not unlink (`ManagedHost.despawn` unpublishes and
        // deactivates; only an explicit `Link.unlink` retracts the edge), so the
        // untap has to be driven from the unpublish hook as well.
        val ids = synchronized(lock) { edges.filterValues { it.cell == ref }.keys.toList() }
        ids.forEach(::unbind)
    }

    /**
     * One window: snapshot and reset every site's counter, and publish a
     * [FlowBatch] of the edges that carried traffic (the contract omits rate-0
     * edges). Runs on the inspector's scheduler thread.
     *
     * Cadence: a window publishes whenever anything is tapped, even when
     * nothing moved — the client's decay rule keys on *received* windows, so a
     * quiet second must read as "quiet", not as "the feed stopped". Once the
     * last tap is gone one trailing empty window says so, and the feed then
     * falls silent rather than emitting an empty batch every second forever.
     */
    fun sample() {
        val batch = synchronized(lock) {
            if (sites.isEmpty() && !everSampled) return
            everSampled = sites.isNotEmpty()
            val perSecond = 1000.0 / windowMs
            val rows = ArrayList<FlowEdgeRate>()
            sites.values.forEach { site ->
                val count = site.drain()
                if (count == 0L) return@forEach
                val context = site.lastContext
                val rate = count * perSecond
                site.edges.forEach { id ->
                    rows += FlowEdgeRate(
                        id = id.toString(),
                        rate = rate,
                        lastWave = context?.timestamp?.let { WaveStamp(it.sourceId.toString(), it.counter) },
                        hop = context?.hop,
                    )
                }
            }
            FlowBatch(window = windowMs, edges = rows)
        }
        onBatch(batch)
    }

    /** Untaps everything. A stopped inspector leaves no handler attached to a live graph. */
    override fun close() {
        val all = synchronized(lock) {
            val snapshot = sites.values.toList()
            sites.clear()
            edges.clear()
            edgeTargets.clear()
            // a closed collector is silent, not one trailing batch short of it
            everSampled = false
            snapshot
        }
        all.forEach { it.detach() }
    }

    /** Tapped outlets — diagnostics and tests. */
    internal val tappedOutlets: Set<PortRef> get() = synchronized(lock) { sites.keys.toSet() }

    /**
     * V3-BE — what the wave-health evaluator ([WaveHealth]) reads, once per
     * evaluation tick, on the inspector's own scheduler thread.
     *
     * Everything here is *already recorded*: [TapSite.lastContext] is the
     * volatile the tap handler has always written, and [edgeTargets] comes off
     * the same `bind` the topology feed already drives. No tap is installed,
     * no outlet is touched and nothing is subscribed to answer this (P6/P2) —
     * which is the whole reason the diagnostic is admissible at all.
     */
    internal fun tapReadings(): List<TapReading> = synchronized(lock) {
        sites.map { (producer, site) ->
            TapReading(
                producer = producer,
                edges = site.edges.mapNotNull { id -> edgeTargets[id]?.let { id to it } },
                lastWave = liveWaveOf(site.lastContext),
            )
        }
    }

    /**
     * The wave position [tapReadings] would currently report for one tapped
     * outlet, or null when [producer] is not tapped or its last observed
     * emission carries no live wave ([liveWaveOf]).
     *
     * A **test seam**, beside [reBaselineAtMsOf] and for the same class of
     * race. `FanOutlet.call` mints the emission's `MessageContext` — bumping
     * `waveCounter`, and with it `waveState().highWater` — *before* it fans out
     * to its Observe-role attachments, so an outlet's watermark reaches wave
     * *W* strictly before [TapSite.lastContext] does. A test that drives a
     * graph and then calls `tickAll()` needs a barrier on what the *evaluator*
     * can see, not on what the outlet has counted; waiting on the watermark
     * alone lets an evaluation observe wave *W-1* and reach a different verdict
     * (computenet-e0zv). Reading the same field [tapReadings] reads, through the
     * same filter and the same monitor, is that barrier.
     *
     * Nothing on the production path calls this, and it subscribes to nothing:
     * like [tapReadings] it only re-reads state the tap handler has already
     * written.
     */
    internal fun observedWaveOf(producer: PortRef): Timestamp? = synchronized(lock) {
        sites[producer]?.let { liveWaveOf(it.lastContext) }
    }

    /**
     * V3-BE part 2 — when a **re-baseline beat** was last observed on any
     * tapped outlet of [cell], or null when none ever was.
     *
     * A `SupervisionPolicy.RESTART` completes by re-baselining over the
     * ordinary catch-up path (`ManagedHost`'s RESTART branch), and
     * `FanOutlet.reBaseline` stages a `ReBaselineNotice` that the minted
     * [MessageContext] then carries — so the payload-agnostic observer this
     * class already installs sees the beat with one null check (see [TapSite]).
     *
     * Null therefore means **not observed**, never "did not happen": only a
     * `ReBaselineEmitting` cell re-baselines at all, and only a cell with a
     * tapped outgoing edge is visible here at all.
     */
    internal fun reBaselineAtMsOf(cell: CellRef): Long? = synchronized(lock) {
        sites.entries
            .filter { (producer, _) -> producer.cell == cell }
            .mapNotNull { (_, site) -> site.reBaselineAtMs.takeIf { it > 0L } }
            .maxOrNull()
    }

    /**
     * One outlet's tap and its window counter. The counter and [lastContext]
     * are the only state a graph thread touches; [edges] is written under
     * [FlowCollector.lock] and read from the sampling thread inside that same
     * monitor, never from the tap handler.
     */
    private class TapSite(source: FanOutlet<*>, private val clock: () -> Long) {
        val edges = LinkedHashSet<UUID>()

        private val count = AtomicLong()

        /**
         * Wall clock of the last observed re-baseline beat on this outlet, or
         * `0` when none has been seen (V3-BE part 2). Written only inside the
         * handler's taken branch below; read from the scheduler thread.
         *
         * A plain volatile long for the same reason [lastContext] is a plain
         * volatile reference: a reader that loses the race merely reports the
         * previous beat, and nothing sums these.
         */
        @Volatile
        var reBaselineAtMs: Long = 0L
            private set

        /**
         * The wave the last observed emission carried. A plain volatile
         * reference: one store per message, no compare-and-set, and a reader
         * that loses a race merely reports the previous window's wave — a
         * label on a rate, never a number anyone sums.
         */
        @Volatile
        var lastContext: MessageContext? = null
            private set

        private val tapRef = PortRef.generate()

        /**
         * The observed outlet, kept so [detach] can undo the attachment.
         *
         * [FanOutlet.observe] is the kernel's payload-agnostic Observe-role
         * attachment: same `taps` storage, same always-admitted SPSC
         * exemption, same taps-fire-first position as a contract-typed
         * [FanOutlet.tap] — but the handler is told only *that* an emission
         * happened and which [MessageContext] it carried. No proxy of the
         * outlet's contract is synthesized here (the outlet's `Api` is never
         * named, hence no cast and no erasure), so there is no payload to
         * decode and no `Object`-method dispatch to screen off the counting
         * path. The whole class of "answering `null` to `hashCode()` throws on
         * unboxing, on a graph thread" hazard is gone with it.
         *
         * A lambda is not [civictech.cell.link.Linked], so `observe` takes the
         * unnegotiated path and always answers
         * [civictech.cell.link.LinkResult.Connected] — the same shape
         * [Errors]'s dead-letter tap relies on. It also means installing this
         * attachment fires no `onLinked` hook, so it triggers no catch-up push
         * and records nothing in the topology index: the inspector's own
         * attachments never appear as edges it then reports.
         */
        private val observed: FanOutlet<*> = source.also { outlet ->
            outlet.observe(tapRef) { context ->
                // The whole per-message cost: one atomic add, one volatile
                // store, and (V3-BE) one reference-null comparison. The context
                // is the wave-plane object the outlet had already built, handed
                // straight over.
                count.incrementAndGet()
                lastContext = context
                // V3-BE part 2, the one thing this ticket adds to the data
                // path, and its cost is pinned: **one reference-null
                // comparison per message**, plus — only inside the taken branch
                // — one clock read and one volatile long store. No allocation,
                // no lock, no map lookup, no payload access, on either side of
                // the branch. `reBaseline` is a plain nullable field of the
                // context object already in a register here.
                //
                // Sampling `lastContext` from the scheduler instead would be
                // free, and wrong: a re-baseline beat is a single emission and
                // live traffic resumes immediately after a restart, so the
                // scheduler would find the beat overwritten in exactly the case
                // this exists to report.
                if (context.reBaseline != null) reBaselineAtMs = clock()
            }
        }

        fun drain(): Long = count.getAndSet(0)

        fun detach() = observed.untap(tapRef)
    }

    internal companion object {
        /** Contract §SSE `flow.rates`: `"window": 1000` — one batch per second. */
        const val WINDOW_MS = 1000L

        /**
         * A site's **wave position**, or null when the last emission it
         * observed does not carry one.
         *
         * Two contexts are deliberately refused (V3-BE guard 3):
         *
         * - `baseline != null` — a catch-up baseline is a topology-versioned
         *   state-as-delta reply carrying a merge-tag frontier, and is
         *   explicitly *not* a wave position (spec 20/21 §Pull, 20/22
         *   §Interaction, 93 I-24; a glitch-free consumer must not admit it to
         *   any wave-completeness set). Reading its timestamp as "the wave this
         *   edge just carried" would be the exact lie the frontier exists to
         *   prevent.
         * - `reBaseline != null` — a RESTART re-baseline *is* a fresh
         *   origination and so does carry a real wave position, of a
         *   **brand-new epoch**. Refusing it is stricter than the letter of the
         *   guard, and deliberately so: the first wave of a fresh epoch is the
         *   worst possible reference point for a lag comparison against a
         *   frontier still stamped with the dead epoch, and skipping it costs
         *   nothing but one tick of latency.
         */
        fun liveWaveOf(context: MessageContext?): Timestamp? =
            context?.takeIf { it.baseline == null && it.reBaseline == null }?.timestamp
    }
}

/**
 * One tapped producing outlet as [WaveHealth] sees it — a value snapshot taken
 * under [FlowCollector]'s own monitor, so the evaluator never reads live
 * collector state (V3-BE).
 */
internal data class TapReading(
    /** The producing outlet this tap is installed on. */
    val producer: PortRef,
    /** `(contract Edge.id, the consuming cell)` for every edge this outlet feeds. */
    val edges: List<Pair<UUID, CellRef>>,
    /** The last *live* wave position observed here — see [FlowCollector.liveWaveOf]. */
    val lastWave: Timestamp?,
)
