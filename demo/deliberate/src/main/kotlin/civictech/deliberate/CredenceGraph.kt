package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TopologyFold
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.inlet
import civictech.cell.link.LinkOptions
import civictech.cell.observe.ObserveCell
import java.util.UUID

/** The durable construction record for one claim. */
data class ClaimNodeFactory(
    val text: String,
    val question: Boolean,
    val layers: LayerSet,
) : CellFactory {
    @Transient
    internal var created: ClaimNode? = null

    override fun create(ref: CellRef): ClaimNode =
        ClaimNode(ref, layers, neutralPrior = question).also { created = it }
}

/** The durable construction record for one argument edge. */
data class EdgeNodeFactory(
    val polarity: Polarity,
    val head: Boolean,
    val layers: LayerSet,
    val quiescence: Double,
) : CellFactory {
    @Transient
    internal var created: EdgeNode? = null

    override fun create(ref: CellRef): EdgeNode =
        EdgeNode(polarity, ref, layers, quiescence = if (head) quiescence else 0.0).also { created = it }
}

/** The durable construction record for one claim or edge's sensitivity cell. */
data class SensitivityFactory(
    val subject: CellRef,
    val isEdge: Boolean,
    val question: Boolean,
    val layers: LayerSet,
    val quiescence: Double,
) : CellFactory {
    @Transient
    internal var created: SensitivityNode? = null

    override fun create(ref: CellRef): SensitivityNode = SensitivityNode(
        ref = ref,
        subject = subject,
        layers = layers,
        question = question,
        isEdge = isEdge,
        quiescence = quiescence,
    ).also { created = it }
}

/** The durable framing record; READINGS keep the cell unlinked as metadata, POSITIONS wire its shares fold. */
data class IssueFactory(
    val root: CellRef,
    val positions: List<CellRef>,
    val mode: CredenceGraph.IssueMode,
    val layers: LayerSet,
) : CellFactory {
    @Transient
    internal var created: IssueNode? = null

    override fun create(ref: CellRef): IssueNode = IssueNode(ref, root, positions, layers).also { created = it }
}

/**
 * One cell graph for every credence layer (SPEC CRED-04): a [ClaimNode] per
 * claim, an [EdgeNode] per edge, every credence a vector over [layers], and a
 * hub fold ([CredenceHubView]) the snapshot reads. Graph management — the
 * index, cycle-head designation, wiring — is modelled on agora's
 * `AgoraService`: all wiring is admitted through the host's staged link
 * primitive, so cycle admission, topology bookkeeping, EdgeClose ordering,
 * and magnitude scheduling apply uniformly to every hop.
 *
 * Durability (SPEC DUR-01): the kernel topology journal records each complete
 * mutation as one write-ahead [GraphSpec]. Stances are the engine's to persist
 * and re-apply. The cells themselves are volatile: recovery rebuilds them under
 * their recorded refs and recomputes every credence from those inputs, with
 * late-join catch-up baselines enabled throughout.
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
    context: ApplyContext? = null,
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
    private val context = context ?: ApplyContext(host)

    /** Volatile: its content is recomputed after every restart. */
    val hub = ObserveCell(
        CredenceHubView(onCredence),
        ref = CellRef(UUID.nameUUIDFromBytes("deliberate:hub".toByteArray())),
    )

    /** Model C: the sensitivity fold. Volatile, like [hub]. */
    val sensitivityHub = ObserveCell(
        SensitivityHubView(onCredence),
        ref = CellRef(UUID.nameUUIDFromBytes("deliberate:sensitivity-hub".toByteArray())),
    )

    /** Model A: the shares fold of every POSITIONS issue. Volatile, like [hub]. */
    val sharesHub = ObserveCell(
        SharesHubView(onCredence),
        ref = CellRef(UUID.nameUUIDFromBytes("deliberate:shares-hub".toByteArray())),
    )

    private val cells = HashMap<CellRef, ClaimNode>()

    /** Model A: each POSITIONS root's [IssueNode], by the root's ref. */
    private val issueCells = HashMap<CellRef, CellRef>()

    /** Model C: each node's sensitivity cell, by the node's ref. */
    private val sensCells = HashMap<CellRef, CellRef>()

    private val wires = java.util.concurrent.CopyOnWriteArrayList<Wire>()

    /** Every link the graph installed, in order. */
    val wiring: List<Wire> get() = wires.toList()

    /** The cells of the sensitivity layer (the hub included). */
    val sensitivityCells: Set<CellRef> get() = synchronized(mutationLock) { sensCells.values.toSet() + sensitivityHub.ref }

    /** Serializes graph mutations, including direct callers outside the engine. */
    private val mutationLock = Any()

    /** Readers ([graph], [nodeInfo]) run off the mutation thread; see `AgoraService.nodesLock`. */
    private val nodesLock = Any()
    private val nodes = LinkedHashMap<CellRef, NodeInfo>()

    /** The stance each node was last sent, by user: lets [setStance] skip one already held. */
    private val held = HashMap<CellRef, HashMap<String, Double>>()

    init {
        require(this.context.host === host) { "CredenceGraph context belongs to a different host" }
        manage.spawn(hub)
        manage.spawn(sensitivityHub)
        manage.spawn(sharesHub)
        this.context.adopt(HUB_HANDLE, hub.ref)
        this.context.adopt(SENSITIVITY_HUB_HANDLE, sensitivityHub.ref)
        this.context.adopt(SHARES_HUB_HANDLE, sharesHub.ref)
    }

    fun createClaim(
        text: String,
        ref: CellRef = CellRef(UUID.randomUUID()),
        question: Boolean = false,
    ): CellRef = synchronized(mutationLock) {
        val handle = claimHandle(ref)
        val steps = mutableListOf<GraphStep>(
            SpawnStep(handle, ClaimNodeFactory(text, question, layers), IdentityBinding.Exact(ref)),
            ConnectStep(handle, "credenceOutlet", HUB_HANDLE, "inlet", STAGED),
        )
        if (sensitivity) {
            val sensitivityRef = CellRef(UUID.randomUUID())
            val sensitivityHandle = sensitivityHandle(ref)
            steps += SpawnStep(
                sensitivityHandle,
                SensitivityFactory(ref, isEdge = false, question = question, layers = layers, quiescence = quiescence),
                IdentityBinding.Exact(sensitivityRef),
            )
            steps += ConnectStep(sensitivityHandle, "hubOutlet", SENSITIVITY_HUB_HANDLE, "inlet", STAGED)
        }
        GraphSpec(steps).apply(context)
        rebuildIndex()
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
        val topology = context.live()
        val handle = edgeHandle(ref)
        val steps = mutableListOf<GraphStep>(
            SpawnStep(
                handle,
                EdgeNodeFactory(polarity, head, layers, quiescence),
                IdentityBinding.Exact(ref),
            ),
            ConnectStep(handle, "credenceOutlet", HUB_HANDLE, "inlet", STAGED),
            ConnectStep(handle, "influenceOutlet", topology.handleFor(target), "influenceInlet", STAGED),
            ConnectStep(
                topology.handleFor(source),
                "credenceOutlet",
                handle,
                if (head) "feedbackInlet" else "sourceInlet",
                STAGED,
            ),
        )
        if (sensitivity) {
            // Model C: the edge's sensitivity cell hears its target's frame and hands its source its share;
            // the target's sensitivity cell folds the same influence the target's credence cell does.
            val sensitivityHandle = sensitivityHandle(ref)
            steps += SpawnStep(
                sensitivityHandle,
                SensitivityFactory(ref, isEdge = true, question = false, layers = layers, quiescence = quiescence),
                IdentityBinding.Exact(CellRef(UUID.randomUUID())),
            )
            steps += ConnectStep(sensitivityHandle, "hubOutlet", SENSITIVITY_HUB_HANDLE, "inlet", STAGED)
            steps += ConnectStep(sensitivityHandle, "sourceOutlet", sensitivityHandle(source), "shareInlet", STAGED)
            steps += ConnectStep(handle, "influenceOutlet", sensitivityHandle(target), "influenceInlet", STAGED)
            steps += ConnectStep(
                sensitivityHandle(target),
                "frameOutlet",
                sensitivityHandle,
                if (head) "feedbackFrameInlet" else "frameInlet",
                STAGED,
            )
        }
        GraphSpec(steps).apply(context)
        rebuildIndex()
        ref
    }

    /**
     * Rebuild the application-owned indexes from the kernel's folded live topology.
     * Factory data is the node record; edge endpoints are recovered from admitted
     * connects, and framing metadata from [IssueFactory].
     */
    fun rebuildIndex() = synchronized(mutationLock) {
        val topology = context.live()
        val rebuiltCells = linkedMapOf<CellRef, ClaimNode>()
        val rebuiltNodes = linkedMapOf<CellRef, NodeInfo>()
        val rebuiltSensitivity = linkedMapOf<CellRef, CellRef>()
        val rebuiltIssues = linkedMapOf<CellRef, CellRef>()
        val framing = mutableListOf<Pair<CellRef, IssueFactory>>()

        topology.spawns.values.forEach { spawn ->
            when (val factory = spawn.factory) {
                is ClaimNodeFactory -> {
                    rebuiltCells[spawn.ref] = checkNotNull(factory.created) {
                        "claim ${spawn.ref} is live in the topology fold but its factory has no cell"
                    }
                    rebuiltNodes[spawn.ref] = NodeInfo(
                        kind = Kind.CLAIM,
                        text = factory.text,
                        question = factory.question,
                    )
                }

                is EdgeNodeFactory -> {
                    rebuiltCells[spawn.ref] = checkNotNull(factory.created) {
                        "edge ${spawn.ref} is live in the topology fold but its factory has no cell"
                    }
                    val source = topology.links.values.singleOrNull { link ->
                        link.to == spawn.ref &&
                            link.outlet == "credenceOutlet" &&
                            link.inlet in SOURCE_INLETS
                    }?.from ?: error("edge ${spawn.ref} has no unique source link in recovered topology")
                    val target = topology.links.values.singleOrNull { link ->
                        link.from == spawn.ref &&
                            link.outlet == "influenceOutlet" &&
                            link.inlet == "influenceInlet" &&
                            topology.spawns[link.to]?.factory !is SensitivityFactory
                    }?.to ?: error("edge ${spawn.ref} has no unique target link in recovered topology")
                    rebuiltNodes[spawn.ref] = NodeInfo(
                        kind = Kind.EDGE,
                        polarity = factory.polarity,
                        source = source,
                        target = target,
                        head = factory.head,
                    )
                }

                is SensitivityFactory -> {
                    checkNotNull(factory.created) {
                        "sensitivity ${spawn.ref} is live in the topology fold but its factory has no cell"
                    }
                    rebuiltSensitivity[factory.subject] = spawn.ref
                }

                is IssueFactory -> {
                    checkNotNull(factory.created) {
                        "issue ${spawn.ref} is live in the topology fold but its factory has no cell"
                    }
                    framing += spawn.ref to factory
                }
            }
        }

        framing.forEach { (issueRef, factory) ->
            val root = checkNotNull(rebuiltNodes[factory.root]) {
                "issue ${factory.root} has no recovered root claim"
            }
            factory.positions.forEach { position ->
                val info = checkNotNull(rebuiltNodes[position]) {
                    "issue ${factory.root} has no recovered position $position"
                }
                rebuiltNodes[position] = info.copy(positionOf = factory.root)
            }
            rebuiltNodes[factory.root] = root.copy(issue = IssueInfo(factory.mode, factory.positions))
            if (factory.mode == IssueMode.POSITIONS) rebuiltIssues[factory.root] = issueRef
        }

        val rebuiltWires = topology.links.values.map { Wire(it.from, it.outlet, it.to, it.inlet) }
        synchronized(nodesLock) {
            cells.clear()
            cells.putAll(rebuiltCells)
            nodes.clear()
            nodes.putAll(rebuiltNodes)
        }
        issueCells.clear()
        issueCells.putAll(rebuiltIssues)
        sensCells.clear()
        sensCells.putAll(rebuiltSensitivity)
        wires.clear()
        wires.addAll(rebuiltWires)
    }

    /**
     * Model A: frames question root [root] as an issue over [texts] — each a
     * new question-flagged claim (so each has model D's neutral verdict and is
     * a model C sensitivity root). The positions, framing metadata, sensitivity
     * cells and every wire are one write-ahead topology delta, so recovery sees
     * the whole framing or none of it. POSITIONS wire the [IssueNode] into the
     * shares fold; READINGS keep it unlinked as the durable framing record.
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
        val steps = mutableListOf<GraphStep>()
        texts.zip(refs).forEach { (text, ref) ->
            val claimHandle = claimHandle(ref)
            steps += SpawnStep(
                claimHandle,
                ClaimNodeFactory(text, question = true, layers = layers),
                IdentityBinding.Exact(ref),
            )
            steps += ConnectStep(claimHandle, "credenceOutlet", HUB_HANDLE, "inlet", STAGED)
            if (sensitivity) {
                val sensitivityHandle = sensitivityHandle(ref)
                steps += SpawnStep(
                    sensitivityHandle,
                    SensitivityFactory(ref, isEdge = false, question = true, layers = layers, quiescence = quiescence),
                    IdentityBinding.Exact(CellRef(UUID.randomUUID())),
                )
                steps += ConnectStep(sensitivityHandle, "hubOutlet", SENSITIVITY_HUB_HANDLE, "inlet", STAGED)
            }
        }
        val issueHandle = issueHandle(root)
        steps += SpawnStep(
            issueHandle,
            IssueFactory(root, refs, mode, layers),
            IdentityBinding.Exact(CellRef(UUID.randomUUID())),
        )
        if (mode == IssueMode.POSITIONS) {
            refs.forEach { position ->
                steps += ConnectStep(claimHandle(position), "credenceOutlet", issueHandle, "positionInlet", STAGED)
            }
            steps += ConnectStep(issueHandle, "sharesOutlet", SHARES_HUB_HANDLE, "inlet", STAGED)
        }
        GraphSpec(steps).apply(context)
        rebuildIndex()
        refs
    }

    /** Model A: the [IssueNode] of POSITIONS root [root]; null for any other node. */
    fun issueCellOf(root: CellRef): CellRef? = synchronized(mutationLock) { issueCells[root] }

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
        sensCells[id]?.let { registry.inlet(it, SensitivityNodePorts.stanceInlet).propagate(Stance(user, value)) }
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

    private fun TopologyFold.handleFor(ref: CellRef): String =
        spawns[ref]?.handle
            ?: handles.entries.singleOrNull { it.value == ref }?.key
            ?: error("no live topology handle for $ref")

    private companion object {
        const val HUB_HANDLE = "hub"
        const val SENSITIVITY_HUB_HANDLE = "sensitivityHub"
        const val SHARES_HUB_HANDLE = "sharesHub"
        val SOURCE_INLETS = setOf("sourceInlet", "feedbackInlet")
        val STAGED = LinkOptions(staged = true)

        fun claimHandle(ref: CellRef) = "claim:${ref.id}"
        fun edgeHandle(ref: CellRef) = "edge:${ref.id}"
        fun sensitivityHandle(ref: CellRef) = "sens:${ref.id}"
        fun issueHandle(root: CellRef) = "issue:${root.id}"
    }
}
