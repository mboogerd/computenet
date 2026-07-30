package civictech.cell.data.op

import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.CounterDelta
import civictech.cell.link.Link
import civictech.cell.link.LinkRole
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface CoalescingCombineApi {
    /**
     * Fan-in: one Consume link per arm, every arm carrying `CounterDelta` under
     * the *origin* wave's [Timestamp] (the arms of one fork share it — a
     * reactive re-emission keeps the incoming timestamp, spec 20/22).
     */
    val inlet: Serve<Propagate<CounterDelta>>
    val outlet: Subscribe<Propagate<CounterDelta>>
}

/**
 * The wave-coalescing **scalar** combine: a version-buffered sum over a
 * `CounterDelta` fan-in that emits **exactly one** delta per completed input
 * wave, so no torn intermediate sum is ever observable
 * (`24-OP-COMBINE-01`, spec 20/22 `[22-GF-01]`).
 *
 * The set shape already coalesces at the operator ([QuorumSetCell] only
 * `propagate()`s once quorum is met); the scalar shape did not. A combine that
 * folds each arm's arrival straight into the running sum emits one delta *per
 * arm*, and the two arms of one source wave ride distinct invocations — so an
 * observer folds `2k + 1` between them, mixing arm `l`'s post-wave input with
 * arm `r`'s pre-wave input, exactly what `[22-GF-01]` forbids. Wrapping such a
 * combine in a downstream [civictech.cell.consistency.GlitchFreeCell] cannot
 * rescue it: the per-arm emissions are two *complete* one-edge waves, which the
 * wrapper faithfully replays, torn intermediate and all. Coalescing has to
 * happen here, before emission — the version-buffering clause of `[22-GF-01]`
 * applied to the operator itself.
 *
 * So: buffer each wave's per-arm contributions, and when the wave's input set
 * is complete emit their **net** as one [CounterDelta] under that wave's own
 * identity — the one-delta-per-input-wave discipline [GroupByCell] and
 * [CombineLatestCell] already keep for keyed streams (22), which is what makes
 * a downstream glitch-free wrap compose (`[22-GF-02]`). Emission is
 * effective-only (21): a completed wave whose net change is zero absorb-acks
 * instead (CP-A3, [emitOrAbsorb]) so no downstream frontier stalls on a wave
 * this cell swallowed.
 *
 * No per-arm running totals are kept, and none are needed: the sum of the arms
 * is the sum of every delta, so a wave's change in the sum is just the sum of
 * that wave's deltas. [total] — the sum of everything emitted — exists only for
 * late-join catch-up (G-22) and [Stateful] snapshot/restore.
 *
 * ### Completeness (the internal frontier fold)
 *
 * The completeness condition is [WaveFrontier][civictech.cell.consistency.WaveFrontier]'s,
 * folded internally rather than installed as an inlet policy: every OPEN
 * `Consume` inlink whose floor for the wave's source is below the wave's
 * counter must have a watermark at or past it, the watermark advancing on a
 * real delta, on a metadata-plane [Progress] absorb-ack, or on a later wave
 * (monotone `max`) — so an arm that silently absorbs a wave is retired by
 * whatever it next produces and never wedges the combine.
 *
 * The frontier policy could not be *composed* here (CP-A4 route 1) for a
 * structural reason worth recording: a released wave's invocations reach the
 * served handler individually (`WaveFrontier.flushReady`), so a coalescing
 * operator must still learn where the released batch ends — which takes the
 * expected-edge set and the [Protocols.Progress] lane, and
 * [ProtocolSupport] keeps exactly one handler per protocol id, already held by
 * the installed frontier. Mirroring the fold is therefore the composition that
 * is actually available; [WaveFrontier][civictech.cell.consistency.WaveFrontier]
 * itself is untouched.
 *
 * Deliberately *not* mirrored (this cell is the WAIT-mode shape): suspension /
 * `WaveMode.DEGRADE` frontier shrinking and terminal-stall RE-SCOPE, replica-fed
 * settlement (E3.4), and pull-on-open. A stalled arm's waves stay buffered until
 * it resumes, produces a later wave, or its edge closes — `EdgeClose` shrinks the
 * condition exactly as it does for the frontier. Like that frontier, an inlink
 * that structurally never carries a given source is a phantom expected edge for
 * that source's waves (G-13: static link-set frontier, no upstream traversal).
 *
 * Single writer of its output stream, like [GroupByCell]/[CombineLatestCell]:
 * the sum is a deterministic function of convergent inputs, so peers recompute
 * from their replicated inputs and converge with no cell-level gossip (42).
 *
 * ponytail: the cell is glitch-free by construction but does **not** carry the
 * PN-12 `civictech.cell.consistency.GlitchFree` structural marker (which would
 * stamp `Manifest.GLITCH_FREE` + `WaveParticipation.WAVED` on the outlet), for
 * the same reason [QuorumSetCell] — the set-shaped operator that likewise
 * coalesces at the operator — does not: the marker lives in `.consistency`, and
 * importing it would mint a `data -> consistency` package edge that closes a
 * two-cycle with the existing `consistency -> data` one (`ArchitectureRatchetTest`,
 * G-63). Marking the coalescing operators is a conscious ratchet decision, not a
 * side effect of adding one.
 */
class CoalescingCombineCell(
    ref: CellRef = CellRef(UUID.randomUUID()),
) : CoalescingCombineCellBase(ref), Stateful {

    /** Sum of every contribution this cell has folded — the value a late joiner catches up to. */
    private var total = 0L

    /**
     * One tracked inlink. [floors] snapshots [flushedHighWater] at `EdgeOpen`:
     * an edge that opened after a wave already flushed is not an expected
     * sibling for it (otherwise a fresh arm would gate waves it can never
     * deliver).
     */
    private class EdgeState(val link: Link, val floors: Map<UUID, Long>, var open: Boolean = true)

    private val edges = LinkedHashMap<UUID, EdgeState>()

    /** Per-edge, per-source watermark: the highest counter known settled on that edge. */
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()

    /** Highest flushed wave per source: a straggler emits late, it never re-buffers. */
    private val flushedHighWater = mutableMapOf<UUID, Long>()

    /**
     * The transient version buffer: the net contribution of an in-flight wave
     * plus the [MessageContext] its coalesced emission rides. [context] is the
     * wave's first buffered arrival — every arm of one source wave carries the
     * same [Timestamp], so which arm's context is kept only decides the `hop`
     * count; the wave identity is identical either way. It stays null for a
     * wave known only from a [Progress] ack (every arm absorbed it).
     */
    private class Wave {
        var net = 0L
        var context: MessageContext? = null
    }

    private val pending = LinkedHashMap<Timestamp, Wave>()

    /**
     * Contributions that matched no open `Consume` edge and were therefore
     * folded and forwarded unaligned instead of buffered (PN-0a's F1 tripwire
     * shape: a replayed frame, a `streamTo`/`tap` producer that announced no
     * `EdgeOpen`, a duplicate edge). Observability only — the sum stays exact.
     */
    var unmatchedContributions: Long = 0L
        private set

    init {
        // PN-9: through the inlet's edge-event fan-out, so this fold composes
        // with any other policy that also observes EdgeOpen/EdgeClose.
        inlet.onEdgeEvent { link, event ->
            when (event) {
                EdgeOpen -> edges[link.id] = EdgeState(link, flushedHighWater.toMap())
                EdgeClose -> edges[link.id]?.open = false
            }
            flushReady() // the frontier just grew or shrank; waves may now be complete
        }
        ProtocolSupport.of(inlet).handle(Protocols.Progress) { link, message ->
            val progress = message as Progress
            advanceWatermark(link.id, progress.sourceId, progress.thru)
            noteAbsorbed(link, progress)
            flushReady()
        }
        // late-join catch-up (G-22): the current sum as a delta-from-zero
        outlet.catchUpOnLinked { if (total != 0L) CounterDelta(total) else null }
    }

    override fun onInlet(value: CounterDelta) {
        val ctx = CurrentContext.get()
        // Unwaved traffic (push catch-up, context-free sends) and pull-catch-up
        // baselines (93 I-24) are never wave positions: folded and forwarded
        // immediately, admitted to no completeness set.
        if (ctx == null || ctx.baseline != null) return passThrough(value)

        val edge = edges.values.singleOrNull { it.open && it.link.from == ctx.sourcePort }
        if (edge == null) {
            // WaveFrontier dead-letters an unmatched invocation; a scalar
            // contribution cannot be dropped without corrupting every later
            // sum, so it is folded and forwarded unaligned, and counted.
            unmatchedContributions++
            return passThrough(value)
        }
        val timestamp = ctx.timestamp
        val floor = edge.floors[timestamp.sourceId] ?: Long.MIN_VALUE
        val flushed = flushedHighWater[timestamp.sourceId] ?: Long.MIN_VALUE
        if (timestamp.counter <= floor || timestamp.counter <= flushed) {
            // The wave already completed without this edge (a resume replay, a
            // late-opened arm): emit late rather than buffer forever — catch-up,
            // spec 21 — and never lose the amount.
            return passThrough(value)
        }
        advanceWatermark(edge.link.id, timestamp.sourceId, timestamp.counter)
        val wave = pending.getOrPut(timestamp) { Wave() }
        wave.net += value.amount
        if (wave.context == null) wave.context = ctx
        flushReady()
    }

    /** Fold and forward a contribution that belongs to no wave set, under whatever context it arrived on. */
    private fun passThrough(value: CounterDelta) {
        total += value.amount
        emitOrAbsorb(
            value.amount == 0L,
            emit = { outlet.call.propagate(value) },
            absorbAck = { outlet.absorbAck() },
        )
    }

    /**
     * A wave known only from an absorb-ack (CP-A3): the arm consumed it and
     * emitted nothing. Buffering the empty wave is what lets a wave *every* arm
     * absorbs still retire downstream — it completes with a zero net and
     * absorb-acks onward, instead of being invisible here and stranding a
     * downstream frontier on the final wave.
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
     * The expected edges of a wave: every open `Consume` inlink whose floor for
     * this source is below the wave's counter. Observe-role edges (negotiated
     * `tap`/`streamTo` announcements, PN-10) never carry consume waves and so
     * never gate one.
     */
    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.link.role == LinkRole.Consume }
        .filter { it.open }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    private fun ready(timestamp: Timestamp): Boolean = expectedEdges(timestamp).all { isSettled(it, timestamp) }

    /** Release every complete wave, in per-source counter order (per-link FIFO makes completion monotone per source). */
    private fun flushReady() {
        val ready = pending.keys
            .filter { timestamp -> ready(timestamp) }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            emit(timestamp, wave)
        }
    }

    /**
     * One delta for the whole wave, under the wave's own identity. The
     * emission runs inside the buffered context so the outlet's reactive
     * stamping keys it to the completed input wave (the [GroupByCell] pattern)
     * however completeness was reached — the last arm's delta, a [Progress]
     * ack, or an `EdgeClose` that shrank the frontier. A wave known only from
     * acks carries no context of its own; its ack is minted from the wave
     * position directly.
     */
    private fun emit(timestamp: Timestamp, wave: Wave) {
        total += wave.net
        CurrentContext.with(wave.context ?: MessageContext(timestamp, outlet.ref)) {
            emitOrAbsorb(
                wave.net == 0L,
                emit = { outlet.call.propagate(CounterDelta(wave.net)) },
                absorbAck = { outlet.absorbAck() },
            )
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the transient
     * version buffer is dropped — a partially collected wave was never observed
     * downstream. Floors, watermarks and flushed high-water record what
     * genuinely happened and stay valid.
     */
    override fun onDeactivate(ctx: CellContext) {
        pending.clear()
    }

    override fun snapshot(): Serializable = total

    override fun restore(state: Serializable) {
        total = state as Long
    }

    companion object {
        fun create(): CoalescingCombineApi = CoalescingCombineCell()
    }
}
