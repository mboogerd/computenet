package civictech.agora

import civictech.agora.cell.*
import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.Link
import civictech.cell.port.streamTo
import civictech.cell.host.inlet

/**
 * Graph management shared by the HTTP layer and the tests. Cells stay
 * topology-blind; the service owns the index — which is also what lets it
 * designate cycle heads at edge-creation time (the app-side form of the
 * decided cycle model, spec 21 §Cycles / 93 I-5+I-6: every elementary cycle
 * contains at least one head, because any new cycle runs through the edge
 * that closed it).
 *
 * All wiring is **routed** through the host queue (`streamTo` + registry
 * inlet handles, the demo idiom) rather than DSL-linked: co-hosted DSL links fuse
 * into synchronous calls that bypass the scheduler, and magnitude-based
 * prioritization needs every hop staged.
 */
class AgoraService(
    private val host: ManagedHost,
    private val registry: LocationRegistry,
    private val semantics: GradualSemantics = DfQuad,
    /** Cycle-head absorb threshold (per feedback edge; heads only). */
    private val quiescence: Double = 1e-3,
    /**
     * Durable graph structure (the demo `users.txt` idiom): claim/edge/remove
     * ops append here; on construction an existing log replays, rebuilding
     * every cell under its recorded CellRef — replay-stable identity, so the
     * host journal's data frames land on the same cells afterwards
     * (rebuild → `host.recoverFrom(journal)` → `host.checkpoint(journal)`).
     */
    private val structureLog: java.io.File? = null,
    onCredence: (CellRef, Double) -> Unit = { _, _ -> },
) {
    enum class Kind { CLAIM, EDGE }

    data class NodeInfo(
        val kind: Kind,
        val text: String? = null,
        val polarity: Polarity? = null,
        val source: CellRef? = null,
        val target: CellRef? = null,
        val head: Boolean = false,
    )

    data class Node(val ref: CellRef, val info: NodeInfo, val credence: Double)

    private val manage = host.managementInlet.call

    // deterministic ref: journaled hub frames re-deliver after a restart
    val hub = civictech.cell.host.ObserveCell(
        CredenceView(onCredence),
        ref = CellRef(java.util.UUID.nameUUIDFromBytes("agora:hub".toByteArray())),
    )

    private val cells = mutableMapOf<CellRef, ClaimCell>()
    private val nodes = LinkedHashMap<CellRef, NodeInfo>()

    /** Per edge: the link feeding it from its source's credence outlet. */
    private val sourceLinks = mutableMapOf<CellRef, Link>()

    @kotlinx.serialization.Serializable
    private data class StructureOp(
        val op: String,
        val ref: String,
        val text: String? = null,
        val polarity: Polarity? = null,
        val source: String? = null,
        val target: String? = null,
    )

    private var replaying = false

    init {
        manage.spawn(hub)
        structureLog?.takeIf { it.exists() }?.let { log ->
            replaying = true
            try {
                log.readLines().filter { it.isNotBlank() }.forEach { line ->
                    val op = kotlinx.serialization.json.Json.decodeFromString<StructureOp>(line)
                    val ref = CellRef(java.util.UUID.fromString(op.ref))
                    when (op.op) {
                        "claim" -> createClaim(op.text ?: "", ref)
                        "edge" -> createEdge(
                            CellRef(java.util.UUID.fromString(op.source!!)),
                            CellRef(java.util.UUID.fromString(op.target!!)),
                            op.polarity!!,
                            ref,
                        )
                        "remove" -> remove(ref)
                        else -> error("unknown structure op ${op.op}")
                    }
                }
            } finally {
                replaying = false
                // rebuild done: links created from now on emit baselines again
                cells.values.forEach { it.catchUp = true }
            }
        }
    }

    private fun log(op: StructureOp) {
        if (!replaying) structureLog?.appendText(
            kotlinx.serialization.json.Json.encodeToString(StructureOp.serializer(), op) + "\n"
        )
    }

    fun createClaim(text: String, ref: CellRef = CellRef(java.util.UUID.randomUUID())): CellRef {
        val cell = ClaimCell(ref, semantics).also { it.catchUp = !replaying }
        manage.spawn(cell)
        cells[ref] = cell
        nodes[ref] = NodeInfo(Kind.CLAIM, text = text)
        cell.credenceOutlet.streamTo(routedHub())
        log(StructureOp("claim", ref.id.toString(), text = text))
        return ref
    }

    fun createEdge(
        source: CellRef,
        target: CellRef,
        polarity: Polarity,
        ref: CellRef = CellRef(java.util.UUID.randomUUID()),
    ): CellRef {
        require(source in nodes) { "unknown source ${source.id}" }
        require(target in nodes) { "unknown target ${target.id}" }
        val head = reaches(from = target, to = source)
        val edge = EdgeCell(polarity, ref, semantics, quiescence = if (head) quiescence else 0.0)
            .also { it.catchUp = !replaying }
        manage.spawn(edge)
        cells[ref] = edge
        nodes[ref] = NodeInfo(Kind.EDGE, polarity = polarity, source = source, target = target, head = head)
        edge.credenceOutlet.streamTo(routedHub())
        edge.influenceOutlet.streamTo(routedInfluence(target))
        sourceLinks[ref] = cells.getValue(source).credenceOutlet.streamTo(routedSource(ref))
        log(StructureOp("edge", ref.id.toString(), polarity = polarity, source = source.id.toString(), target = target.id.toString()))
        return ref
    }

    fun setStance(id: CellRef, user: String, value: Double?) {
        require(id in nodes) { "unknown node ${id.id}" }
        value?.let { require(it in 0.0..1.0) { "stance must be between 0 and 1 (was $it)" } }
        routedStance(id).propagate(StanceDelta(user, value))
    }

    /**
     * Remove a claim or edge, cascading over every edge that becomes dangling
     * (sources/targets it, recursively — edges targeting a removed edge go
     * too). Each removed edge first stops its inbound feed, then retracts its
     * influence at any surviving target, then despawns.
     */
    fun remove(id: CellRef) {
        require(id in nodes) { "unknown node ${id.id}" }
        val doomed = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = doomed.addAll(nodes.filter { (ref, info) ->
                ref !in doomed && info.kind == Kind.EDGE && (info.source in doomed || info.target in doomed)
            }.keys)
        }
        doomed.forEach { ref ->
            val info = nodes.getValue(ref)
            if (info.kind == Kind.EDGE) {
                sourceLinks.remove(ref)?.unlink()
                // during structure replay the journal already holds the
                // original retraction — re-sending would double-journal it
                if (info.target !in doomed && !replaying) {
                    // retraction urgency: the edge's credence bounds its influence
                    val size = hub.credenceOf(ref) ?: 1.0
                    routedInfluence(info.target!!).propagate(InfluenceDelta(ref, info.polarity!!, null, size))
                }
            }
        }
        doomed.forEach { ref ->
            manage.despawn(ref)
            cells.remove(ref)
            nodes.remove(ref)
        }
        log(StructureOp("remove", id.id.toString())) // cascade re-derives on replay
    }

    fun graph(): List<Node> = nodes.map { (ref, info) ->
        Node(ref, info, hub.credenceOf(ref) ?: 0.5)
    }

    fun nodeInfo(id: CellRef): NodeInfo? = nodes[id]

    /**
     * The existing edge with exactly this `(source, target, polarity)`, if any.
     * The engine deliberately permits parallel edges and self-loops (both are
     * valid constructs the cycle/DF-QuAD tests exercise directly), so relation
     * uniqueness is a *product-surface* policy the HTTP layer applies via this
     * lookup — not an invariant enforced in [createEdge].
     */
    fun findEdge(source: CellRef, target: CellRef, polarity: Polarity): CellRef? =
        nodes.entries.firstOrNull { (_, info) ->
            info.kind == Kind.EDGE &&
                info.source == source && info.target == target && info.polarity == polarity
        }?.key

    /** DFS along the influence-flow direction: claim → edges sourced at it → their targets. */
    private fun reaches(from: CellRef, to: CellRef): Boolean {
        val seen = mutableSetOf<CellRef>()
        val stack = ArrayDeque<CellRef>().apply { add(from) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n == to) return true
            if (!seen.add(n)) continue
            nodes[n]?.target?.let { stack.add(it) }
            nodes.forEach { (ref, info) -> if (info.source == n) stack.add(ref) }
        }
        return false
    }

    private fun routedHub(): Propagate<CredenceUpdate> =
        registry.inlet(hub.ref, "inlet")

    private fun routedSource(edge: CellRef): Propagate<CredenceUpdate> =
        registry.inlet(edge, "sourceInlet")

    private fun routedInfluence(target: CellRef): Propagate<InfluenceDelta> =
        registry.inlet(target, "influenceInlet")

    private fun routedStance(id: CellRef): Propagate<StanceDelta> =
        registry.inlet(id, "stanceInlet")

    companion object {
        /**
         * Magnitude → band mapping for agora hosts: sizes are credence deltas
         * in [0,1], so the attention quantizer's 0.4/0.75 knees would leave
         * almost everything unboosted. Dramatic shifts outrank the neutral
         * band, noticeable ones match it, micro-adjustments yield to it.
         */
        val MAGNITUDE_BANDS: (Double) -> civictech.cell.attention.AttentionBand = {
            when {
                it >= 0.2 -> civictech.cell.attention.AttentionBand.HIGH
                it >= 0.05 -> civictech.cell.attention.AttentionBand.NORMAL
                else -> civictech.cell.attention.AttentionBand.LOW
            }
        }
    }
}
