package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.inlet
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.observe.ObserveCell
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
 * `AgoraService`: all wiring is admitted through the host's staged link
 * primitive, so cycle admission, topology bookkeeping, EdgeClose ordering,
 * and magnitude scheduling apply uniformly to every hop.
 *
 * Durability (SPEC DUR-01): the only durable parts are the inputs.
 * [structureLog] records claims and edges (append-only, one line each,
 * written *before* wiring as agora does, computenet-t3sp); replaying it on
 * construction rebuilds every cell under its recorded ref. Stances are the
 * engine's to persist (they are its Jev judgments) and to re-apply. The cells
 * themselves are volatile: a restart recomputes every credence from those
 * inputs, with late-join catch-up baselines enabled throughout, so nothing
 * derived ever needs to be journaled.
 *
 * Model C: beside the credence cells runs the sensitivity layer
 * ([SensitivityNode], one per claim and per edge, and a [SensitivityHubView]
 * fold): d root / d node, computed top-down. It reads what the credence cells
 * emit and the stances routed to them; nothing it emits is wired into a
 * credence cell ([wiring] records every link, so a test can check that). Off
 * ([sensitivity] = false), the credence cells are wired exactly the same.
 */
class CredenceGraph(
    host: ManagedHost,
    private val registry: LocationRegistry,
    val layers: LayerSet,
    private val structureLog: File? = null,
    /** Cycle-head absorb threshold (per feedback edge; heads only), agora's default. */
    private val quiescence: Double = 1e-3,
    onCredence: () -> Unit = {},
    /** Model C: run the sensitivity layer. */
    private val sensitivity: Boolean = true,
) {
    enum class Kind { CLAIM, EDGE }

    /**
     * Model A: how a question root is framed. READINGS are alternative
     * meanings of the question, each judged on its own; POSITIONS are
     * competing answers whose credences an [IssueNode] folds into [Shares].
     */
    enum class IssueMode { READINGS, POSITIONS }

    /** Model A: a framed root's issue — its [mode] and its [positions] (readings or positions), in order. */
    data class IssueInfo(val mode: IssueMode, val positions: List<CellRef>)

    data class NodeInfo(
        val kind: Kind,
        val text: String? = null,
        /** True only for the root claim of a question tree. */
        val question: Boolean = false,
        val polarity: Polarity? = null,
        val source: CellRef? = null,
        val target: CellRef? = null,
        val head: Boolean = false,
        /** Model A: set on a framed question root. */
        val issue: IssueInfo? = null,
        /** Model A: set on each reading/position, the root it frames. */
        val positionOf: CellRef? = null,
    )

    /**
     * A node, its latest credence (null until its first emission reached the hub)
     * and its sensitivity d headline(root) / d node ([sensitivityOf]; null until known).
     */
    data class Node(
        val ref: CellRef,
        val info: NodeInfo,
        val credence: Credence?,
        val sensitivity: Double? = null,
        /** Model A: a POSITIONS root's shares, once the shares fold has them. */
        val shares: Shares? = null,
    )

    /** One link the graph installed: [outlet] of cell [from] streams to [inlet] of cell [to]. */
    data class Wire(val from: CellRef, val outlet: String, val to: CellRef, val inlet: String)

    private val manage = host.managementInlet.call

    /** Volatile: its content is recomputed after every restart. */
    val hub = ObserveCell(CredenceHubView(onCredence))

    /** Model C: the sensitivity fold. Volatile, like [hub]. */
    val sensitivityHub = ObserveCell(SensitivityHubView(onCredence))

    /** Model A: the shares fold of every POSITIONS issue. Volatile, like [hub]. */
    val sharesHub = ObserveCell(SharesHubView(onCredence))

    private val cells = HashMap<CellRef, ClaimNode>()

    /** Model A: each POSITIONS root's [IssueNode], by the root's ref. */
    private val issueCells = HashMap<CellRef, IssueNode>()

    /** Model C: each node's sensitivity cell, by the node's ref. */
    private val sensCells = HashMap<CellRef, SensitivityNode>()

    private val wires = java.util.concurrent.CopyOnWriteArrayList<Wire>()

    /** Every link the graph installed, in order. */
    val wiring: List<Wire> get() = wires.toList()

    /** The cells of the sensitivity layer (the hub included). */
    val sensitivityCells: Set<CellRef> get() = synchronized(mutationLock) { sensCells.values.map { it.ref }.toSet() + sensitivityHub.ref }

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
        /** Model A, an "issue" op: the framing mode and the position refs, in order. */
        val mode: IssueMode? = null,
        val positions: List<String>? = null,
    )

    private var replaying = false

    init {
        manage.spawn(hub)
        if (sensitivity) manage.spawn(sensitivityHub)
        manage.spawn(sharesHub)
        structureLog?.takeIf { it.exists() }?.let { log ->
            replaying = true
            try {
                replay(readStructure(log))
            } finally {
                replaying = false
            }
        }
    }

    /**
     * Replays the structure log. Model A (feature D3): an "issue" op is valid
     * only when every position it names has a "claim" op after it; a valid one
     * replays as [frame], which recreates those position claims itself (so
     * their own claim ops are skipped). A torn framing — an issue op whose
     * positions are not all present — is dropped with its listed positions,
     * and any edge touching a dropped position is skipped too.
     */
    private fun replay(ops: List<StructureOp>) {
        val claimAt = HashMap<String, Int>()
        ops.forEachIndexed { i, op -> if (op.op == "claim") claimAt.putIfAbsent(op.ref, i) }
        val skipped = HashSet<String>()
        val framed = HashSet<String>()
        val validIssues = HashSet<Int>()
        ops.forEachIndexed { i, op ->
            if (op.op != "issue") return@forEachIndexed
            val positions = op.positions.orEmpty()
            if (positions.all { (claimAt[it] ?: -1) > i }) {
                validIssues += i
                framed += positions
            } else {
                skipped += positions
                System.err.println("deliberate: dropping torn framing of root ${op.ref} (positions not all logged: $positions)")
            }
        }
        fun ref(id: String) = CellRef(UUID.fromString(id))
        ops.forEachIndexed { i, op ->
            when (op.op) {
                "claim" -> if (op.ref !in skipped && op.ref !in framed) createClaim(op.text ?: "", ref(op.ref), op.question)
                "issue" -> if (i in validIssues) {
                    val positions = op.positions!!
                    frame(ref(op.ref), op.mode!!, positions.map { ops[claimAt.getValue(it)].text ?: "" }, positions.map(::ref))
                }
                "edge" -> if (op.source !in skipped && op.target !in skipped && op.ref !in skipped) createEdge(
                    ref(op.source!!),
                    ref(op.target!!),
                    op.polarity!!,
                    ref(op.ref),
                )
                else -> error("unknown structure op ${op.op}")
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
        val cell = ClaimNode(ref, layers, neutralPrior = question)
        // Persist before any hosted operation can block or fail. A logged but
        // incompletely wired node is rebuilt in full on the next replay.
        log(StructureOp("claim", ref.id.toString(), text = text, question = question))
        manage.spawn(cell)
        cells[ref] = cell
        wire(ref, "credenceOutlet", hub.ref, "inlet")
        if (sensitivity) {
            spawnSensitivity(SensitivityNode(CellRef(UUID.randomUUID()), ref, layers, question = question, quiescence = quiescence))
        }
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
        wire(ref, "credenceOutlet", hub.ref, "inlet")
        wire(ref, "influenceOutlet", target, "influenceInlet")
        wire(source, "credenceOutlet", ref, if (head) "feedbackInlet" else "sourceInlet")
        if (sensitivity) {
            // Model C: the edge's sensitivity cell hears its target's frame and hands its source its share;
            // the target's sensitivity cell folds the same influence the target's credence cell does.
            val s = spawnSensitivity(
                SensitivityNode(CellRef(UUID.randomUUID()), ref, layers, isEdge = true, quiescence = quiescence),
            )
            val onTarget = sensCells.getValue(target)
            val ofSource = sensCells.getValue(source)
            wire(s.ref, "sourceOutlet", ofSource.ref, "shareInlet")
            wire(ref, "influenceOutlet", onTarget.ref, "influenceInlet")
            wire(onTarget.ref, "frameOutlet", s.ref, if (head) "feedbackFrameInlet" else "frameInlet")
        }
        synchronized(nodesLock) {
            nodes[ref] = NodeInfo(Kind.EDGE, polarity = polarity, source = source, target = target, head = head)
        }
        ref
    }

    /**
     * Model A: frames question root [root] as an issue over [texts] — each a
     * new question-flagged claim (so each has model D's neutral verdict and is
     * a model C sensitivity root). Logs ONE "issue" op naming every position
     * ref *before* the position claims (feature D3: replay drops a torn
     * framing). POSITIONS also spawn an [IssueNode] fed by the positions'
     * credences, emitting [Shares] to [sharesHub]; nothing flows back from it
     * (CRED-03). Returns the position refs, in order.
     */
    fun frame(
        root: CellRef,
        mode: IssueMode,
        texts: List<String>,
        refs: List<CellRef> = texts.map { CellRef(UUID.randomUUID()) },
    ): List<CellRef> = synchronized(mutationLock) {
        synchronized(nodesLock) {
            val info = requireNotNull(nodes[root]) { "unknown root ${root.id}" }
            require(info.kind == Kind.CLAIM && info.question) { "only a question root can be framed: ${root.id}" }
            require(info.issue == null) { "root ${root.id} is already framed" }
            require(nodes.values.none { it.target == root }) { "root ${root.id} already has arguments" }
            require(texts.size >= 2) { "an issue needs at least 2 positions (was ${texts.size})" }
            require(refs.size == texts.size) { "one ref per position" }
            require(refs.distinct().size == refs.size && refs.none { it in nodes }) { "position refs must be new and distinct" }
        }
        log(StructureOp("issue", root.id.toString(), mode = mode, positions = refs.map { it.id.toString() }))
        texts.zip(refs).forEach { (text, ref) -> createClaim(text, ref, question = true) }
        synchronized(nodesLock) {
            refs.forEach { nodes[it] = nodes.getValue(it).copy(positionOf = root) }
            nodes[root] = nodes.getValue(root).copy(issue = IssueInfo(mode, refs))
        }
        if (mode == IssueMode.POSITIONS) {
            val issue = IssueNode(CellRef(UUID.randomUUID()), root, refs, layers)
            manage.spawn(issue)
            issueCells[root] = issue
            refs.forEach { p ->
                wire(p, "credenceOutlet", issue.ref, "positionInlet")
            }
            wire(issue.ref, "sharesOutlet", sharesHub.ref, "inlet")
        }
        refs
    }

    /** Model A: the [IssueNode] of POSITIONS root [root]; null for any other node. */
    fun issueCellOf(root: CellRef): CellRef? = synchronized(mutationLock) { issueCells[root]?.ref }

    /** Model A: POSITIONS root [root]'s latest shares; null until the fold has them, and for any other node. */
    fun sharesOf(root: CellRef): Shares? = sharesHub.current()[root]

    /** Routes [user]'s stance to node [id]; a stance the node already holds is not sent again. */
    fun setStance(id: CellRef, user: String, value: Double?) = synchronized(mutationLock) mutation@{
        synchronized(nodesLock) {
            require(id in nodes) { "unknown node ${id.id}" }
            value?.let { require(it in 0.0..1.0) { "stance must be between 0 and 1 (was $it)" } }
            val mine = held.getOrPut(id) { HashMap() }
            if (mine[user] == value) return@mutation
            if (value == null) mine.remove(user) else mine[user] = value
        }
        registry.inlet(id, ClaimNodePorts.stanceInlet).propagate(Stance(user, value))
        sensCells[id]?.let { registry.inlet(it.ref, SensitivityNodePorts.stanceInlet).propagate(Stance(user, value)) }
    }

    fun graph(): List<Node> {
        val snapshot = synchronized(nodesLock) { nodes.entries.map { it.key to it.value } }
        val credences = hub.current()
        val sensitivities = sensitivityHub.current()
        val shares = sharesHub.current()
        return snapshot.map { (ref, info) -> Node(ref, info, credences[ref], scalar(sensitivities[ref], credences), shares[ref]) }
    }

    fun nodeInfo(id: CellRef): NodeInfo? = synchronized(nodesLock) { nodes[id] }

    fun credenceOf(id: CellRef): Credence? = hub.current()[id]

    /** Model C: node [id]'s sensitivity vector, d root\[l] / d node\[l] per layer; null until the layer reached it. */
    fun sensitivityVectorOf(id: CellRef): List<Double>? = sensitivityHub.current()[id]?.values

    /**
     * Model C: d headline(root) / d node [id] — how far the root's headline
     * credence moves per unit move of every layer of the node's credence
     * ([LayerSet.headlineGradient] at the root's current credences, dotted with
     * the node's vector). Null until the sensitivity layer reached the node.
     */
    fun sensitivityOf(id: CellRef): Double? = scalar(sensitivityHub.current()[id], hub.current())

    private fun scalar(s: Sensitivity?, credences: Map<CellRef, Credence>): Double? {
        val values = s?.values ?: return null
        val root = s.root?.let { credences[it]?.values } ?: List(layers.ids.size) { 0.5 }
        val g = layers.headlineGradient(root)
        return values.indices.sumOf { g[it] * values[it] }
    }

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

    private fun wire(from: CellRef, outlet: String, to: CellRef, inlet: String) {
        val result = manage.connect(from, outlet, to, inlet, STAGED)
        check(result is LinkResult.Connected) {
            val reason = (result as? LinkResult.Rejected)?.reason ?: result.toString()
            "CredenceGraph: admitted link $from.$outlet -> $to.$inlet rejected: $reason"
        }
        wires += Wire(from, outlet, to, inlet)
    }

    /** Caller holds [mutationLock]. Spawns a sensitivity cell and wires it to the sensitivity hub. */
    private fun spawnSensitivity(s: SensitivityNode): SensitivityNode {
        manage.spawn(s)
        sensCells[s.subject] = s
        wire(s.ref, "hubOutlet", sensitivityHub.ref, "inlet")
        return s
    }

    private companion object {
        val JSON = Json { explicitNulls = false }
        val STAGED = LinkOptions(staged = true)
    }
}
