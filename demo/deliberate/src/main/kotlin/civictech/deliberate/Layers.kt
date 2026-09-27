package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.agora.cell.credenceOf
import civictech.cell.CellRef
import java.util.UUID

/**
 * One agora graph per semantics, all on one host (SPEC §2 "Semantics
 * layers"). The **primary** layer's refs are the deliberation's identity (the
 * refs in `GraphDto`) and its credence is `NodeDto.credence`; every other
 * layer is a **mirror**: its own `ClaimCell`/`EdgeCell` graph under refs
 * derived from the primary's ([refIn]), fed the same structure and the same
 * `jev` stances, propagating them under its own [civictech.agora.semantics.GradualSemantics].
 *
 * Every mutation goes to the primary first and then to each mirror in order,
 * under one lock (agora's single-writer mutation model, per layer). A process
 * killed between those writes leaves a mirror missing a tail of the primary's
 * structure; [reconcile] repairs that on the next boot.
 */
class AgoraLayers(
    val primaryId: String,
    val primary: AgoraService,
    val mirrors: Map<String, AgoraService> = emptyMap(),
    /** The layers [Consensus] averages over (absent ones are skipped). */
    val consensusMembers: List<String> = Consensus.DEFAULT_MEMBERS,
    /** The layer whose credence is `NodeDto.credence` (`--semantics`); the primary unless chosen otherwise. */
    val headlineId: String = primaryId,
) {
    init {
        require(primaryId !in mirrors) { "the primary layer '$primaryId' cannot also be a mirror" }
        require(headlineId == primaryId || headlineId in mirrors) { "unknown headline layer '$headlineId'" }
    }

    /** Every layer id, primary first. */
    val ids: List<String> = listOf(primaryId) + mirrors.keys

    private val lock = Any()

    fun createClaim(text: String): CellRef = synchronized(lock) {
        val ref = primary.createClaim(text)
        mirrors.forEach { (id, s) -> s.createClaim(text, refIn(id, ref)) }
        ref
    }

    fun createEdge(source: CellRef, target: CellRef, polarity: Polarity): CellRef = synchronized(lock) {
        val ref = primary.createEdge(source, target, polarity)
        mirrors.forEach { (id, s) -> s.createEdge(refIn(id, source), refIn(id, target), polarity, refIn(id, ref)) }
        ref
    }

    fun setStance(ref: CellRef, user: String, value: Double?) = synchronized(lock) {
        primary.setStance(ref, user, value)
        mirrors.forEach { (id, s) -> s.setStance(refIn(id, ref), user, value) }
    }

    /** The primary layer's graph: structure and primary credence. */
    fun graph(): List<AgoraService.Node> = primary.graph()

    /** Credence of the primary [ref] in every layer, by layer id (0.5 before a layer has propagated). */
    fun credences(ref: CellRef): Map<String, Double> =
        ids.associateWith { id -> layer(id).hub.credenceOf(refIn(id, ref)) ?: NEUTRAL }

    private fun layer(id: String): AgoraService = if (id == primaryId) primary else mirrors.getValue(id)

    /**
     * Boot repair after a restart: creates in every mirror, in the primary's
     * creation order, whatever the primary holds and the mirror lacks, then
     * re-applies [stances] (the engine's `jev` judgments by primary ref) to
     * every layer — a stance is last-writer-wins per user, so re-applying one
     * a layer already holds changes nothing.
     */
    fun reconcile(stances: Map<CellRef, Double>) = synchronized(lock) {
        val nodes = primary.graph()
        for ((id, s) in mirrors) {
            for (n in nodes) {
                val ref = refIn(id, n.ref)
                if (s.nodeInfo(ref) != null) continue
                when (n.info.kind) {
                    AgoraService.Kind.CLAIM -> s.createClaim(n.info.text ?: "", ref)
                    AgoraService.Kind.EDGE -> {
                        val source = refIn(id, n.info.source!!)
                        val target = refIn(id, n.info.target!!)
                        if (s.nodeInfo(source) != null && s.nodeInfo(target) != null) {
                            s.createEdge(source, target, n.info.polarity!!, ref)
                        }
                    }
                }
            }
        }
        val present = nodes.map { it.ref }.toSet()
        stances.filterKeys { it in present }.forEach { (ref, v) ->
            primary.setStance(ref, JEV, v)
            mirrors.forEach { (id, s) -> s.setStance(refIn(id, ref), JEV, v) }
        }
    }

    companion object {
        const val JEV = "jev"
        private const val NEUTRAL = 0.5

        /** The ref of primary node [ref] in mirror layer [layer]: deterministic, so a restart finds the same cells. */
        fun mirrorRef(layer: String, ref: CellRef): CellRef =
            CellRef(UUID.nameUUIDFromBytes("deliberate:$layer:${ref.id}".toByteArray()))

        /** The hub ref of a mirror layer's `AgoraService` (the primary keeps agora's default). */
        fun hubRef(layer: String): CellRef = CellRef(UUID.nameUUIDFromBytes("deliberate:hub:$layer".toByteArray()))
    }

    /** [ref] (a primary ref) as it is named in layer [layer]. */
    fun refIn(layer: String, ref: CellRef): CellRef = if (layer == primaryId) ref else mirrorRef(layer, ref)
}
