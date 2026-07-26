package civictech.cell.consistency

import civictech.cell.Timestamp
import civictech.cell.attention.Progress
import civictech.cell.attention.StallNotice
import civictech.cell.port.EdgeClose
import civictech.cell.port.EdgeEvent
import civictech.cell.port.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.InletFrontier
import civictech.cell.link.Link
import civictech.cell.link.LinkRole
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
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
    /**
     * Has the covering subset for origin wave `(source, counter)` touching [key]
     * delivered it? (PN-7, plan §3 Rule of settlement.) [key] `null` ⇒ unfiltered
     * (every member) ⇒ pre-PN-7 behavior verbatim; a non-null [key] scopes the
     * quorum to members whose interest admits it.
     */
    fun completeAt(source: UUID, counter: Long, key: Any?): Boolean
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
    private val onDropped: (Invocation) -> Unit = {},
    private val onViolation: (GlitchViolation) -> Unit = {},
) : InletFrontier {

    // PN-9: the wave-completeness fold is the ALIGN tier (reorders/buffers).
    override val tier get() = civictech.cell.port.PolicyTier.ALIGN

    private lateinit var release: (Invocation) -> Unit

    /**
     * PN-0a (plan §2 F1): count of invocations [offer] could not match to any
     * open edge and routed to the dead-letter/diagnostic path instead of
     * discarding silently. A non-zero value is the tripwire for the F1 defect —
     * replayed journal frames, `streamTo`/`tap` producers, or duplicate edges
     * arriving before/without their `EdgeOpen`. Observability only; delivery is
     * unchanged (PN-1/PN-2 remove the cause).
     */
    var unmatchedDrops: Long = 0L
        private set

    /**
     * Opt-in replica-fed settlement (E3.4), declared **per edge** — not per cell.
     *
     * An edge is *replica-fed* when its deliveries arrive from a *replica* of one
     * logical source. Such a source cannot be aligned by the ordinary cross-inlink
     * per-source predicate ([expectedLocalEdges]): each replica re-originates a
     * delivered wave under its own outlet epoch, so the source appears on exactly
     * the one edge whose replica minted it, and every sibling edge would be a
     * *phantom* expected-edge that never settles (its floor defaults to
     * `MIN_VALUE`). So a replica-fed edge gates on the replica SET instead: a wave
     * it delivered releases once every ORIGIN tag its payload carries is
     * [ReplicaFrontier.completeAt] — "the replica set delivered it", not merely
     * "my replica delivered it".
     *
     * A *local* edge keeps the ordinary cross-inlink frontier ([isSettled]) so a
     * local fan-in diamond on the very same cell stays glitch-free. Settlement is
     * therefore composed **per edge**: each expected edge is ready under its own
     * rule (see [flushReady]) — never globally replaced.
     *
     * [replicaFedBy] carries per-outlet declarations; [blanketGate] is the
     * whole-cell convenience (mark every edge replica-fed) kept for the
     * all-replica join. [ReplicaGate.originTags] extracts origin tags from the
     * payload, keeping this fold payload-agnostic.
     */
    private val replicaFedBy = mutableMapOf<PortRef, ReplicaGate>()
    private var blanketGate: ReplicaGate? = null

    /**
     * Configuration for a replica-fed edge: the cross-replica read plus payload
     * extractors.
     *
     * [originTags] lists the origin waves a payload carries (the unfiltered read,
     * pre-PN-7). [originKeys] (PN-7, plan §3 Rule of settlement) is the
     * *interest-scoped* extractor: it maps each key the payload touches to the
     * origin waves attached to it, so the settlement quorum for each wave is the
     * covering subset of members whose interest admits that key. The default is
     * empty ⇒ the gate falls back to [originTags] with a `null` key ⇒ the quorum
     * is every member, byte-identical to pre-PN-7 behavior (an
     * `originKeys`-unaware graph is unfiltered).
     */
    class ReplicaGate(
        val frontier: ReplicaFrontier,
        val originTags: (Invocation) -> Collection<Timestamp>,
        val originKeys: (Invocation) -> Map<Any?, Collection<Timestamp>> = { emptyMap() },
    )

    /** The gate governing [edge], if it is replica-fed (per-outlet declaration, else the blanket). */
    private fun gateFor(edge: EdgeState): ReplicaGate? = replicaFedBy[edge.link.from] ?: blanketGate

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
        // PN-9: register through the inlet's edge-event fan-out (not directly on
        // ProtocolSupport) so a sibling PullOnOpen policy can also observe EdgeOpen.
        inlet.onEdgeEvent { link, event ->
            when (event) {
                EdgeOpen -> {
                    edges[link.id] = EdgeState(link, flushedHighWater.toMap())
                    // PN-9: on-demand pull-on-open is no longer welded here — it is
                    // the separately installable [civictech.cell.port.PullOnOpen]
                    // policy (GlitchFreeCell installs both). The frontier now only
                    // tracks the edge; a plain WaveFrontier no longer auto-pulls.
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
        val edge = edges.values.singleOrNull { it.open && it.link.from == ctx.sourcePort } ?: run {
            // PN-0a (plan §2 F1): no open edge matches this invocation's source
            // (a replayed journal frame, a streamTo/tap producer that never fired
            // EdgeOpen, a duplicate edge). The frontier is a per-inlet policy with
            // no handle on the host's dead-letter outlet, so route the drop to a
            // counted diagnostic — still not delivered downstream (observable
            // behavior unchanged), but no longer silent. This is the tripwire;
            // PN-1/PN-2 remove the cause.
            unmatchedDrops++
            onDropped(invocation)
            return
        }
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
     * Whole-cell convenience (E3.4): mark **every** edge replica-fed under one
     * gate — the all-replica join, where no inlink is local. Prefer
     * [markReplicaFed] to declare a single arm replica-fed while sibling arms stay
     * local (the mixed-arm case). Immediately re-checks: a gate installed after
     * waves already buffered must not leave them stuck.
     */
    fun installReplicaGate(gate: ReplicaGate) {
        blanketGate = gate
        flushReady()
    }

    /**
     * Declare a **single edge** replica-fed (E3.4) — the inlinks from [fromOutlet]
     * gate on the replica frontier while every other inlink keeps the ordinary
     * cross-inlink predicate. Marking is by source outlet ref so it survives the
     * edge's open/close lifecycle and needs no live [Link] at declaration time.
     */
    fun markReplicaFed(fromOutlet: PortRef, gate: ReplicaGate) {
        replicaFedBy[fromOutlet] = gate
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

    /**
     * The **local** expected edges of a wave (the cross-inlink frontier). Every
     * open, non-suspended *local* edge whose floor for this source is below the
     * wave's counter must settle before release — this is what keeps a local
     * fan-in diamond glitch-free. Replica-fed edges are excluded: a local source
     * never flows through a replica inlink, so counting one would be a phantom
     * sibling that never settles.
     *
     * PN-10: **Observe**-role edges (negotiated `tap`/`streamTo` announcements)
     * are excluded too. A tap announces its edge — so it negotiates and appears
     * in the topology — but it never carries the source's consume waves; counting
     * it would be a phantom sibling that never settles, wedging the join. Only
     * [LinkRole.Consume] edges gate a wave.
     */
    private fun expectedLocalEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.link.role == LinkRole.Consume }
        .filter { it.open && it.link.id !in suspendedEdges }
        .filter { gateFor(it) == null }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    /**
     * Per-edge settlement (E3.4): a buffered wave is ready only when **every**
     * expected edge is ready under its own rule.
     *
     * A wave delivered on a replica-fed edge carries a replica-minted source that
     * lives on that one edge only; it is ready once the replica SET has delivered
     * every origin tag its payload carries ([ReplicaFrontier.completeAt]) — its
     * local sibling arms are NOT phantom expected-edges for it. A wave delivered
     * on local edges is ready under the ordinary cross-inlink frontier over the
     * local expected edges. The two classes never share a timestamp (a replica
     * re-originates under its own outlet epoch), so classifying by the wave's
     * delivering edges is unambiguous.
     */
    private fun ready(timestamp: Timestamp): Boolean {
        val wave = pending[timestamp] ?: return false
        val replicaFed = wave.entries.mapNotNull { (edgeId, invocation) ->
            edges[edgeId]?.let { edge -> gateFor(edge)?.let { gate -> gate to invocation } }
        }
        if (replicaFed.isNotEmpty()) {
            // Replica-fed wave: gate purely on the merged watermark, no phantom siblings.
            return replicaFed.all { (gate, invocation) -> gateReady(gate, invocation) }
        }
        // Local wave: the ordinary cross-inlink frontier over local expected edges.
        return expectedLocalEdges(timestamp).all { isSettled(it, timestamp) }
    }

    /**
     * Is a replica-fed wave complete under [gate]? (PN-7, plan §3 Rule of
     * settlement.) When the gate extracts origin keys, each origin wave is read
     * *interest-scoped* to the key it is attached to — the quorum is the covering
     * subset of members whose interest admits that key. With no keys extracted
     * (the default) the read is unfiltered (`null` key ⇒ every member), so an
     * `originKeys`-unaware graph settles exactly as it did pre-PN-7.
     */
    private fun gateReady(gate: ReplicaGate, invocation: Invocation): Boolean {
        val keyed = gate.originKeys(invocation)
        if (keyed.isEmpty()) {
            return gate.originTags(invocation).all { tag -> gate.frontier.completeAt(tag.sourceId, tag.counter, null) }
        }
        return keyed.all { (key, tags) ->
            tags.all { tag -> gate.frontier.completeAt(tag.sourceId, tag.counter, key) }
        }
    }

    private fun flushReady() {
        val ready = pending.keys
            .filter { timestamp -> ready(timestamp) }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            wave.values.forEach { release(it) } // each under its own context
        }
    }
}
