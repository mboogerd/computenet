package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.inlet
import civictech.cell.observe.ObserveCell
import civictech.cell.port.streamTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * One cell graph for every credence layer (SPEC CRED-04): a [ClaimNode] per
 * claim, an [EdgeNode] per edge, every credence a vector over [layers], and a
 * hub fold ([CredenceHubView]) the snapshot reads. Graph management — the
 * index, cycle-head designation, wiring — is modelled on agora's
 * `AgoraService`: all wiring is **routed** through the host queue
 * (`streamTo` + registry inlets), so every hop is staged and magnitude
 * scheduling (`size`) orders it.
 *
 * Durability (SPEC DUR-01): the only durable parts are the inputs.
 * [structureLog] records claims and edges (append-only, one line each,
 * written *before* wiring as agora does, computenet-t3sp); replaying it on
 * construction rebuilds every cell under its recorded ref. Stances are the
 * engine's to persist (they are its Jev judgments) and to re-apply. The cells
 * themselves are volatile: a restart recomputes every credence from those
 * inputs, with late-join catch-up baselines enabled throughout, so nothing
 * derived ever needs to be journaled.
 */
class CredenceGraph(
    host: ManagedHost,
    private val registry: LocationRegistry,
    val layers: LayerSet,
    private val structureLog: File? = null,
    /** Cycle-head absorb threshold (per feedback edge; heads only), agora's default. */
    private val quiescence: Double = 1e-3,
    onCredence: () -> Unit = {},
) {
    enum class Kind { CLAIM, EDGE }

    data class NodeInfo(
        val kind: Kind,
        val text: String? = null,
        /** True only for the root claim of a question tree. */
        val question: Boolean = false,
        val polarity: Polarity? = null,
        val source: CellRef? = null,
        val target: CellRef? = null,
        val head: Boolean = false,
    )

    /** A node and its latest credence (null until its first emission reached the hub). */
    data class Node(val ref: CellRef, val info: NodeInfo, val credence: Credence?)

    private val manage = host.managementInlet.call

    /** Volatile: its content is recomputed after every restart. */
    val hub = ObserveCell(CredenceHubView(onCredence))

    private val cells = HashMap<CellRef, ClaimNode>()

    /** Serializes structure-log appends and graph mutations, including direct callers outside the engine. */
    private val mutationLock = Any()

    /** Readers ([graph], [nodeInfo]) run off the mutation thread; see `AgoraService.nodesLock`. */
    private val nodesLock = Any()
    private val nodes = LinkedHashMap<CellRef, NodeInfo>()

    /** The stance each node was last sent, by user: lets [setStance] skip one already held. */
    private val held = HashMap<CellRef, HashMap<String, Double>>()

    @Serializable
    private data class StructureOp(
        val op: String,
        val ref: String,
        val text: String? = null,
        val question: Boolean = false,
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
                readStructure(log).forEach { op ->
                    val ref = CellRef(UUID.fromString(op.ref))
                    when (op.op) {
                        "claim" -> createClaim(op.text ?: "", ref, op.question)
                        "edge" -> createEdge(
                            CellRef(UUID.fromString(op.source!!)),
                            CellRef(UUID.fromString(op.target!!)),
                            op.polarity!!,
                            ref,
                        )
                        else -> error("unknown structure op ${op.op}")
                    }
                }
            } finally {
                replaying = false
            }
        }
    }

    /**
     * The log's operations. A `kill -9` mid-append can leave a torn last line:
     * it is cut off (the file truncated to the last complete line) so the next
     * append starts on a line of its own. A bad line anywhere else is an error.
     */
    private fun readStructure(log: File): List<StructureOp> {
        val bytes = log.readBytes()
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete < bytes.size) RandomAccessFile(log, "rw").use { it.setLength(complete.toLong()) }
        return String(bytes, 0, complete, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
            .map { JSON.decodeFromString(StructureOp.serializer(), it) }.toList()
    }

    /** Durable record first, then wiring: see `AgoraService.log` (computenet-t3sp). */
    private fun log(op: StructureOp) {
        if (!replaying) structureLog?.appendText(JSON.encodeToString(StructureOp.serializer(), op) + "\n")
    }

    fun createClaim(
        text: String,
        ref: CellRef = CellRef(UUID.randomUUID()),
        question: Boolean = false,
    ): CellRef = synchronized(mutationLock) {
        val cell = ClaimNode(ref, layers)
        // Persist before any hosted operation can block or fail. A logged but
        // incompletely wired node is rebuilt in full on the next replay.
        log(StructureOp("claim", ref.id.toString(), text = text, question = question))
        manage.spawn(cell)
        cells[ref] = cell
        cell.credenceOutlet.streamTo(routedHub())
        synchronized(nodesLock) { nodes[ref] = NodeInfo(Kind.CLAIM, text = text, question = question) }
        ref
    }

    fun createEdge(
        source: CellRef,
        target: CellRef,
        polarity: Polarity,
        ref: CellRef = CellRef(UUID.randomUUID()),
    ): CellRef = synchronized(mutationLock) {
        val head = synchronized(nodesLock) {
            require(source in nodes) { "unknown source ${source.id}" }
            require(target in nodes) { "unknown target ${target.id}" }
            // Every elementary cycle runs through the edge that closed it (agora's cycle model).
            reaches(from = target, to = source)
        }
        log(StructureOp("edge", ref.id.toString(), polarity = polarity, source = source.id.toString(), target = target.id.toString()))
        val edge = EdgeNode(polarity, ref, layers, quiescence = if (head) quiescence else 0.0)
        manage.spawn(edge)
        cells[ref] = edge
        edge.credenceOutlet.streamTo(routedHub())
        edge.influenceOutlet.streamTo(registry.inlet<Influence>(target, "influenceInlet"))
        cells.getValue(source).credenceOutlet.streamTo(registry.inlet<Credence>(ref, "sourceInlet"))
        synchronized(nodesLock) {
            nodes[ref] = NodeInfo(Kind.EDGE, polarity = polarity, source = source, target = target, head = head)
        }
        ref
    }

    /** Routes [user]'s stance to node [id]; a stance the node already holds is not sent again. */
    fun setStance(id: CellRef, user: String, value: Double?) = synchronized(mutationLock) mutation@{
        synchronized(nodesLock) {
            require(id in nodes) { "unknown node ${id.id}" }
            value?.let { require(it in 0.0..1.0) { "stance must be between 0 and 1 (was $it)" } }
            val mine = held.getOrPut(id) { HashMap() }
            if (mine[user] == value) return@mutation
            if (value == null) mine.remove(user) else mine[user] = value
        }
        registry.inlet<Stance>(id, "stanceInlet").propagate(Stance(user, value))
    }

    fun graph(): List<Node> {
        val snapshot = synchronized(nodesLock) { nodes.entries.map { it.key to it.value } }
        val credences = hub.current()
        return snapshot.map { (ref, info) -> Node(ref, info, credences[ref]) }
    }

    fun nodeInfo(id: CellRef): NodeInfo? = synchronized(nodesLock) { nodes[id] }

    fun credenceOf(id: CellRef): Credence? = hub.current()[id]

    /** Caller holds [nodesLock]. DFS along the influence flow: node → edges sourced at it → their targets. */
    private fun reaches(from: CellRef, to: CellRef): Boolean {
        val seen = HashSet<CellRef>()
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

    private fun routedHub(): Propagate<Credence> = registry.inlet(hub.ref, "inlet")

    private companion object {
        val JSON = Json { explicitNulls = false }
    }
}
