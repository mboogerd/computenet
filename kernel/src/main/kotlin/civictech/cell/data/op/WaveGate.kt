package civictech.cell.data.op

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.link.Link
import civictech.cell.link.LinkRole
import civictech.cell.port.FanInlet
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import java.util.UUID

/**
 * One buffered input delta, **deferred**: folding it into the operator's state
 * is postponed to wave completeness, and doing so returns the output keys/rows
 * the wave must then reconcile **once**, against both sides' settled state.
 *
 * That "apply now, reconcile later" split is the whole of `emitOnFrontier`'s
 * mechanism: the flicker a non-monotone binary operator shows within a wave is
 * an artifact of reconciling after *each* arm's delta instead of after the
 * wave's, and a fold that returns its touched set rather than emitting lets the
 * cell run exactly one reconciliation per completed wave (96 §E2.4).
 *
 * The union of the per-fold touched sets is order-independent, which is why the
 * gate may apply a wave's folds in arrival order: a row that a later fold would
 * have collected differently is, by construction, a row the *other* fold
 * touched, so it is in the union either way (see `SemiJoinCell.applyRight`).
 */
internal fun interface GatedFold<T> {
    fun applyAndTouch(): Set<T>
}

/**
 * The **binary operator's** wave-completeness gate (spec 20/24
 * `[24-OP-SEMIJOIN-04]` and the mirrored `CombineLatestCell` clause; 96 §E2.4):
 * buffers the input deltas arriving on a two-inlet operator's `left`/`right`
 * inlets until each wave's edge frontier is complete, then hands the whole
 * wave's folds to [onWave] under the wave's own identity, so the operator
 * reconciles and emits **once** per completed input wave.
 *
 * ### Mirrored, not composed
 *
 * The completeness condition is [civictech.cell.consistency.WaveFrontier]'s,
 * *folded at cell scope* rather than installed as an inlet policy — the
 * structural argument [CoalescingCombineCell] records (D-COMBINE) and
 * [civictech.cell.observe.AlignedCompositeCell] repeats: an installed frontier
 * releases a completed wave's invocations **individually** (so an operator that
 * must coalesce still has to learn where the released batch ends), it holds the
 * inlet's single [Protocols.Progress] handler, and it is **per-inlet**, so two
 * per-inlet installs cannot express *one* frontier spanning `left` and `right`.
 * Mirroring is the composition actually available; `WaveFrontier` itself is
 * untouched.
 *
 * What is mirrored: edges + `EdgeOpen` floors, per-edge per-source watermarks
 * advanced by a real delta / a metadata-plane [Progress] absorb-ack / a later
 * wave (monotone `max`), [noteAbsorbed]'s buffer-the-acked-wave move (so a wave
 * *every* arm swallows still retires here), the flushed-high-water straggler
 * rule, and per-source-ordered release.
 *
 * ### Deliberately not mirrored: this is the WAIT-mode shape
 *
 * Like [CoalescingCombineCell] and [civictech.cell.observe.AlignedCompositeCell],
 * this gate does not mirror [civictech.cell.consistency.GlitchFreeCell.WaveMode.DEGRADE]
 * frontier shrinking, terminal-stall RE-SCOPE, replica-fed settlement (E3.4), or
 * pull-on-open. A stalled arm's waves stay buffered until it resumes, produces a
 * later wave, absorb-acks, or its edge closes — `EdgeClose` shrinks the condition
 * exactly as it does for the frontier ([bufferedWaves] is the observability hook).
 *
 * ### The phantom expected edge (G-13) — read this before enabling the gate
 *
 * The frontier is a **static link set** with no upstream traversal, so an arm
 * that structurally *never* carries a given source's waves is nonetheless an
 * expected edge for them and holds them. For a *binary* operator that caveat is
 * sharper than it is for `WaveFrontier`'s fan-in or for the aligned sink, because
 * a join's two inlets are so often fed by two **independent roots**: gate such a
 * cell and each side holds the other's waves until an ack, a later wave carrying
 * that same source, or an unlink shrinks the condition. Enable `emitOnFrontier`
 * for the **shared-source diamond** — both inlets descending from one root, which
 * is precisely the topology in which the within-wave flicker exists at all — and
 * leave it off otherwise. This is also why the gate is opt-in and the ungated
 * default is the shipped behavior: `[22-LIVE-01]` forbids over-alignment across
 * independent sources, and the static-frontier phase cannot yet tell a silent
 * source from an absent one (the G-40/G-13 residual, identical here, in
 * `WaveFrontier`, in `CoalescingCombineCell`, and in `AlignedCompositeCell`).
 *
 * Not thread-safe by itself: it runs under the owning cell's ordinary
 * single-threaded handler discipline, exactly as [CoalescingCombineCell]'s fold
 * does (protocol deliveries are synchronous on the sender's thread, which for an
 * in-process edge is the host thread already running the handler).
 */
internal class WaveGate<T>(
    left: FanInlet<*>,
    right: FanInlet<*>,
    private val onWave: (Timestamp, MessageContext?, List<GatedFold<T>>) -> Unit,
) {

    /** Which inlet an edge belongs to — see [offer]'s per-arm edge match. */
    private enum class Arm { LEFT, RIGHT }

    /**
     * One tracked inlink, remembering which [arm] it feeds. [floors] snapshots
     * [flushedHighWater] at `EdgeOpen`: an edge that opened after a wave already
     * flushed is not an expected sibling for it (otherwise a freshly linked arm
     * would gate waves it can never deliver).
     */
    private class EdgeState(val arm: Arm, val link: Link, val floors: Map<UUID, Long>, var open: Boolean = true)

    /** Both arms' edges in ONE map: the union is the shared completeness condition. */
    private val edges = LinkedHashMap<UUID, EdgeState>()

    /** Per-edge, per-source watermark: the highest counter known settled on that edge. */
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()

    /** Highest flushed wave per source: a straggler applies late, it never re-buffers. */
    private val flushedHighWater = mutableMapOf<UUID, Long>()

    /**
     * The transient version buffer: an in-flight wave's deferred folds plus the
     * [MessageContext] its coalesced emission rides. [context] is the wave's
     * first buffered arrival — every arm of one source wave carries the same
     * [Timestamp], so which arm's context is kept only decides the `hop` count;
     * the wave identity is identical either way. It stays null for a wave known
     * only from a [Progress] ack (every arm absorbed it).
     */
    private class Wave<T> {
        val folds = mutableListOf<GatedFold<T>>()
        var context: MessageContext? = null
    }

    private val pending = LinkedHashMap<Timestamp, Wave<T>>()

    /**
     * Deltas that matched no open `Consume` edge and were therefore applied
     * ungated instead of buffered (PN-0a's F1 tripwire shape: a replayed frame,
     * a `streamTo`/`tap` producer that announced no `EdgeOpen`, a duplicate
     * edge). `WaveFrontier` dead-letters such an invocation; an operator cannot
     * drop an input delta without corrupting every later output, so it is
     * applied and counted — the trade [CoalescingCombineCell] makes for its sum
     * and [civictech.cell.observe.AlignedCompositeCell] for its views.
     */
    var unmatchedDeltas: Long = 0L
        private set

    /**
     * Waves currently held awaiting the shared frontier — the WAIT-shape
     * observability hook (a stalled or phantom expected edge shows up here, and
     * a healthy graph returns to 0 at quiescence). Diagnostic only.
     */
    val bufferedWaves: Int get() = pending.size

    init {
        track(Arm.LEFT, left)
        track(Arm.RIGHT, right)
    }

    private fun track(arm: Arm, inlet: FanInlet<*>) {
        // PN-9: through the inlet's edge-event fan-out, so this fold composes
        // with any other policy that also observes EdgeOpen/EdgeClose.
        inlet.onEdgeEvent { link, event ->
            when (event) {
                EdgeOpen -> edges[link.id] = EdgeState(arm, link, flushedHighWater.toMap())
                EdgeClose -> edges[link.id]?.open = false
            }
            flushReady() // the shared condition just grew or shrank
        }
        ProtocolSupport.of(inlet).handle(Protocols.Progress) { link, message ->
            val progress = message as Progress
            advanceWatermark(link.id, progress.sourceId, progress.thru)
            noteAbsorbed(link, progress)
            flushReady()
        }
    }

    /** Buffer a delta arriving on `left`; see [offer] for the false (apply-now) cases. */
    fun offerLeft(fold: GatedFold<T>): Boolean = offer(Arm.LEFT, fold)

    /** Buffer a delta arriving on `right`; see [offer] for the false (apply-now) cases. */
    fun offerRight(fold: GatedFold<T>): Boolean = offer(Arm.RIGHT, fold)

    /**
     * Buffer [fold] for its wave, or return `false` when the delta belongs to no
     * completeness set and the caller must apply it **immediately, ungated**:
     *
     *  - unwaved traffic (push catch-up's `onLinked` state-as-delta, context-free
     *    sends) and pull-catch-up baselines (`ctx.baseline != null`, 93 I-24) —
     *    `WaveFrontier.offer`'s two catch-up arms, never wave positions;
     *  - a delta matching no open `Consume` edge (counted in [unmatchedDeltas]);
     *  - a straggler for a wave already flushed or below its edge's floor —
     *    applied late rather than buffered forever (catch-up, spec 21).
     */
    private fun offer(arm: Arm, fold: GatedFold<T>): Boolean {
        val ctx = CurrentContext.get()
        if (ctx == null || ctx.baseline != null) return false

        // Scoped to THIS arm's edges: one upstream outlet may legitimately feed
        // both inlets (a self-join over one stream), so a gate-wide `singleOrNull`
        // on the source port would find two edges and give up on both.
        val edge = edges.values.singleOrNull { it.open && it.arm == arm && it.link.from == ctx.sourcePort }
        if (edge == null) {
            unmatchedDeltas++
            return false
        }
        val timestamp = ctx.timestamp
        val floor = edge.floors[timestamp.sourceId] ?: Long.MIN_VALUE
        val flushed = flushedHighWater[timestamp.sourceId] ?: Long.MIN_VALUE
        if (timestamp.counter <= floor || timestamp.counter <= flushed) return false

        advanceWatermark(edge.link.id, timestamp.sourceId, timestamp.counter)
        val wave = pending.getOrPut(timestamp) { Wave() }
        wave.folds += fold
        if (wave.context == null) wave.context = ctx
        flushReady()
        return true
    }

    /**
     * A wave known only from an absorb-ack (CP-A3): the arm consumed it and
     * emitted nothing. Buffering the empty wave is what lets a wave *every* arm
     * absorbs still retire here — it completes with no folds, reconciles nothing,
     * absorb-acks onward (the caller's own emit-or-absorb), and advances
     * [flushedHighWater] — instead of being invisible and stranding a downstream
     * frontier on the final wave.
     */
    private fun noteAbsorbed(link: Link, progress: Progress) {
        val edge = edges[link.id] ?: return
        if (!edge.open || edge.link.role != LinkRole.Consume) return
        if (progress.thru <= (flushedHighWater[progress.sourceId] ?: Long.MIN_VALUE)) return
        if ((edge.floors[progress.sourceId] ?: Long.MIN_VALUE) >= progress.thru) return
        pending.getOrPut(Timestamp(progress.sourceId, progress.thru)) { Wave() }
    }

    private fun advanceWatermark(edgeId: UUID, sourceId: UUID, counter: Long) {
        watermark.getOrPut(edgeId) { mutableMapOf() }.merge(sourceId, counter, ::maxOf)
    }

    private fun isSettled(edgeId: UUID, timestamp: Timestamp): Boolean =
        (watermark[edgeId]?.get(timestamp.sourceId) ?: Long.MIN_VALUE) >= timestamp.counter

    /**
     * The expected edges of a wave — **the union across both inlets**, which is
     * the one line that generalizes `WaveFrontier`'s per-inlet condition to a
     * binary operator. Every open `Consume` edge whose floor for this source is
     * below the wave's counter must settle before the wave is reconciled.
     * Observe-role edges (negotiated `tap`/`streamTo` announcements, PN-10) never
     * carry consume waves and so never gate one.
     */
    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.link.role == LinkRole.Consume }
        .filter { it.open }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    private fun ready(timestamp: Timestamp): Boolean = expectedEdges(timestamp).all { isSettled(it, timestamp) }

    /**
     * Release every complete wave in per-source counter order (per-link FIFO
     * makes completion monotone per source), handing each wave's folds to
     * [onWave] exactly once.
     */
    private fun flushReady() {
        val ready = pending.keys
            .filter { timestamp -> ready(timestamp) }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            onWave(timestamp, wave.context, wave.folds)
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the transient version
     * buffer is dropped — a partially collected wave was never applied to the
     * operator's state and never observed downstream. Floors, watermarks and
     * flushed high-water record what genuinely happened and stay valid.
     */
    fun clear() {
        pending.clear()
    }
}
