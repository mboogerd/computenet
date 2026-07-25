package civictech.cell.consistency

import civictech.cell.Timestamp
import civictech.cell.attention.Progress
import civictech.cell.attention.StallNotice
import civictech.cell.port.EdgeClose
import civictech.cell.port.EdgeEvent
import civictech.cell.port.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.InletFrontier
import civictech.cell.port.Link
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.StateRequest
import civictech.cell.proxy.Invocation
import java.util.*

/**
 * Cross-replica settlement read (spec 20/22 §Completeness — cross-replica
 * extension, E3.4): "has *every* replica-set member delivered origin wave
 * `(source, counter)`", answered off the merged delivered-watermark lattice
 * ([civictech.cell.data.WatermarkCell]) — the JoinBarrier read. A
 * [civictech.cell.replication.Replication] builds the concrete read-view over
 * its local companion; [WaveFrontier] consults it without new coordination
 * traffic (the lattice is the only wire).
 */
fun interface ReplicaFrontier {
    fun completeAt(source: UUID, counter: Long): Boolean
}

/**
 * The wave-completeness fold (spec 20/22), extracted from [GlitchFreeCell] as a
 * reusable per-inlet policy (CP-A4). Buffers the reactive data waves arriving on
 * an inlet until each wave's edge frontier is complete, then releases them in
 * per-source counter order to the inlet's served handler.
 *
 * The completeness frontier is folded from in-band `EdgeOpen`/`EdgeClose`
 * markers; wave order is per-source counter order, and per-link FIFO (spec 31)
 * makes wave completion monotone per source. Completeness of wave (s, t): every
 * OPEN, non-suspended inlink whose floor(s) < t has watermark(s) >= t — the
 * watermark advancing on a real delta, a metadata-plane [Progress] absorb-ack,
 * or a later wave (monotone `max`).
 *
 * As an [InletFrontier] it registers its own generic-protocol handlers on
 * [attach], so edge/watermark/stall markers reach it through the ordinary
 * [ProtocolSupport] delivery path — identical whether the arm is in-process or
 * bridged (spec 40/41 point 4).
 *
 * ponytail: static link-set frontier; real upstream traversal ("describe your
 * frontier") needs multiplex ports (G-13). Unwaved traffic passes through.
 */
class WaveFrontier(
    private val mode: GlitchFreeCell.WaveMode,
    private val onViolation: (GlitchViolation) -> Unit = {},
) : InletFrontier {

    private lateinit var release: (Invocation) -> Unit

    /**
     * Opt-in replica-fed settlement (E3.4). A glitch-free consumer drawing from
     * *replicas* of one logical source cannot align its inlinks by wave source —
     * each replica re-originates a delivered wave under its own outlet epoch, so
     * the ordinary cross-inlink per-source predicate ([expectedEdges]) would
     * stall forever (a source only ever appears on the one edge whose replica
     * minted it). When set, settlement is instead read from the replica set: a
     * buffered wave releases once every ORIGIN tag its payload carries is
     * [ReplicaFrontier.completeAt] — "the replica set delivered it", not merely
     * "my replica delivered it". [ReplicaGate.originTags] extracts those origin
     * tags from the payload, keeping this fold payload-agnostic.
     */
    private var replicaGate: ReplicaGate? = null

    /** Configuration for [replicaGate]: the cross-replica read plus a payload origin-tag extractor. */
    class ReplicaGate(
        val frontier: ReplicaFrontier,
        val originTags: (Invocation) -> Collection<Timestamp>,
    )

    private val pending = LinkedHashMap<Timestamp, LinkedHashMap<UUID, Invocation>>()

    private data class EdgeState(
        val link: Link,
        val floors: Map<UUID, Long>,
        var open: Boolean = true,
    )

    private val edges = LinkedHashMap<UUID, EdgeState>()

    /** Edges announced recoverably stalled by their host (DEGRADE only). */
    private val suspendedEdges = mutableSetOf<UUID>()

    /**
     * Per-edge, per-source watermark (spec 20/22 "Completeness over silent or
     * stuck edges"): the highest counter known settled on that edge. Advances
     * on a real data delta, on a metadata-plane [Progress] absorb-ack, or on a
     * later wave (monotone `max`) — so an edge that silently absorbs a wave is
     * retired by whatever it next produces, never stuck.
     */
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()

    /** Highest flushed wave per source: replayed stragglers pass through, never re-buffer. */
    private val flushedHighWater = mutableMapOf<UUID, Long>()

    override fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit) {
        this.release = release
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event as EdgeEvent) {
                EdgeOpen -> {
                    edges[link.id] = EdgeState(link, flushedHighWater.toMap())
                    // On-demand pull (spec 20/21 §Pull, G-18 residual, decided in 93
                    // I-16): a fresh link issues a StateRequest so a subscriber-side
                    // subscription is caught up regardless of which side observes the
                    // install; idempotence (observed-remove tags, 24) makes racing the
                    // producer's own onLinked push harmless.
                    Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(link.to, since = null))
                }
                EdgeClose -> edges[link.id]?.open = false
            }
            flushReady()
        }
        ProtocolSupport.of(inlet).handle(Protocols.Suspension) { link, message ->
            when (val notice = message as StallNotice) {
                is StallNotice.Stall -> when {
                    !notice.recoverable -> reScope(link, notice)
                    mode == GlitchFreeCell.WaveMode.DEGRADE -> {
                        suspendedEdges += link.id
                        flushReady() // shrinking the frontier may complete waves
                    }
                    // WAIT: a recoverable stall needs no action here — the join
                    // resolves it by the eventual Resume + real replay, or by a
                    // later wave's monotone watermark advance.
                }

                StallNotice.Resume -> {
                    suspendedEdges -= link.id
                    flushReady()
                }
            }
        }
        ProtocolSupport.of(inlet).handle(Protocols.Progress) { link, message ->
            val progress = message as Progress
            advanceWatermark(link.id, progress.sourceId, progress.thru)
            flushReady()
        }
    }

    override fun offer(invocation: Invocation) {
        val ctx = invocation.context
        if (ctx == null) {
            // unwaved traffic: pass straight through
            release(invocation)
            return
        }
        if (ctx.baseline != null) {
            // Catch-up baseline (spec 20/21 §Pull, 20/22 §Interaction, decided in 93
            // I-24): a topology-versioned state-as-delta, never a wave position —
            // released immediately, never buffered, excluded from every wave set.
            release(invocation)
            return
        }
        val edge = edges.values.singleOrNull { it.open && it.link.from == ctx.sourcePort } ?: return
        val floor = edge.floors[ctx.timestamp.sourceId] ?: Long.MIN_VALUE
        if (ctx.timestamp.counter <= floor) return
        if (ctx.timestamp.counter <= (flushedHighWater[ctx.timestamp.sourceId] ?: Long.MIN_VALUE)) {
            // a wave that already completed without this edge (DEGRADE + resume
            // replay): emit late rather than buffer forever — catch-up, spec 21
            release(invocation)
        } else {
            advanceWatermark(edge.link.id, ctx.timestamp.sourceId, ctx.timestamp.counter)
            pending.getOrPut(ctx.timestamp) { LinkedHashMap() }[edge.link.id] = invocation
            flushReady()
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (spec 20/22, 30/31 rule 5,
     * decided in 93 I-18): the transient version buffer is dropped — any
     * partially-collected wave was never observed downstream. Floors, watermark,
     * and flushed high-water stay valid (they record what genuinely happened),
     * so accounting for waves arriving after restart is unaffected.
     */
    override fun reset() {
        pending.clear()
    }

    /**
     * Install the cross-replica settlement read (E3.4). Immediately re-checks:
     * a gate installed after waves already buffered must not leave them stuck.
     */
    fun installReplicaGate(gate: ReplicaGate) {
        replicaGate = gate
        flushReady()
    }

    /**
     * Re-run settlement (E3.4). The replica frontier advances asynchronously as
     * peer watermarks gossip in — a change no inlet event of this frontier
     * observes — so its owner pokes this hook when the merged watermark moves.
     */
    fun recheck() = flushReady()

    private fun advanceWatermark(edgeId: UUID, sourceId: UUID, counter: Long) {
        watermark.getOrPut(edgeId) { mutableMapOf() }.merge(sourceId, counter, ::maxOf)
    }

    private fun isSettled(edgeId: UUID, timestamp: Timestamp): Boolean =
        (watermark[edgeId]?.get(timestamp.sourceId) ?: Long.MIN_VALUE) >= timestamp.counter

    /**
     * RE-SCOPE (spec 20/22, 30/31 rule 5, decided in 93 I-18): the only
     * admissible disposition for a terminal stall. With a known [Stall.timestamp]
     * only that wave's watermark advances — rescuing exactly the poisoned wave,
     * leaving the edge open. Without one, the edge closes outright (the unlink
     * frontier-shrink), unblocking everything pending on it. Either way a
     * [GlitchViolation] surfaces via [onViolation].
     */
    private fun reScope(link: Link, stall: StallNotice.Stall) {
        val timestamp = stall.timestamp
        if (timestamp != null) {
            advanceWatermark(link.id, timestamp.sourceId, timestamp.counter)
        } else {
            edges[link.id]?.open = false
        }
        val detail = if (timestamp != null) " wave $timestamp" else ""
        onViolation(GlitchViolation("edge ${link.id} ${stall.reason}$detail — advanced past the poisoned wave"))
        flushReady()
    }

    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.open && it.link.id !in suspendedEdges }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    /**
     * Replica-fed readiness (E3.4): the wave arrived on its delivering edge (it
     * is in [pending]), so "my replica delivered it" already holds; the only
     * remaining question is whether the replica SET has — every origin tag the
     * buffered invocations carry must be [ReplicaFrontier.completeAt].
     */
    private fun replicaReady(timestamp: Timestamp, gate: ReplicaGate): Boolean {
        val wave = pending[timestamp] ?: return false
        return wave.values.all { invocation ->
            gate.originTags(invocation).all { tag -> gate.frontier.completeAt(tag.sourceId, tag.counter) }
        }
    }

    private fun flushReady() {
        val gate = replicaGate
        val ready = pending.keys
            .filter { timestamp ->
                if (gate != null) replicaReady(timestamp, gate)
                else expectedEdges(timestamp).all { isSettled(it, timestamp) }
            }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            wave.values.forEach { release(it) } // each under its own context
        }
    }
}
