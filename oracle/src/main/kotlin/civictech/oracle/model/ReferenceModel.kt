package civictech.oracle.model

/**
 * A model-level description of a dataflow graph, and the batch evaluator over it —
 * `ORA1 §MODEL-01`'s carrier: *"compute, from a complete input script alone, the observable
 * state of every terminal in a graph, without executing any kernel cell."*
 *
 * ## What it is not
 *
 * It is not a `GraphSpec`. A kernel graph spec names cells, ports, links, dispatch classes
 * and topology order; none of that is observable at quiescence, and reproducing it here
 * would make the oracle a second implementation rather than an independent check (epic
 * computenet-4ru design D2). A [ReferenceModel] names only what the answer depends on: which
 * sources read which script slice, which operator consumes which node in which port order,
 * and which nodes are terminals.
 *
 * ## Why nodes hold [ReferenceOp]s directly
 *
 * A node carries its model instance, not a catalog id. Resolving ids to
 * [civictech.oracle.bind.OperatorCatalog] entries is the runner feature's
 * (computenet-4ru.8), and `civictech.oracle.model` importing `civictech.oracle.bind` would
 * be a package cycle — `bind` already depends on `model` for [ReferenceOp] and [ElementShape].
 *
 * ## Purity
 *
 * [eval] is a pure function of ([ReferenceModel], [Script]): it reads the script, never
 * writes it, and holds no state between calls (`ORA1 §MODEL-11`). Every intermediate lives
 * in a local map that dies with the call, so two evaluations of one script are two
 * independent folds that happen to agree.
 */
data class ReferenceModel(
    /** Every node, in any order — [eval] topologically sorts them itself. */
    val nodes: List<ModelNode>,
    /** The named observation points a differential run compares: terminal name → node id. */
    val terminals: Map<String, NodeId>,
) {

    private val byId: Map<NodeId, ModelNode> = nodes.associateBy { it.id }

    init {
        val duplicated = nodes.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "Duplicate node ids in the reference model: ${duplicated.map { it.id }.sorted()}"
        }
        terminals.forEach { (name, node) ->
            require(node in byId) { "Terminal '$name' names node '${node.id}', which the model does not declare" }
        }
        nodes.filterIsInstance<ModelNode.Operator>().forEach { operator ->
            operator.inputs.forEach { input ->
                require(input in byId) {
                    "Operator node '${operator.id.id}' names input '${input.id}', which the model does not declare"
                }
            }
        }
    }

    /**
     * Every terminal's state, computed from [script] alone.
     *
     * The fold is a topological sweep: a source node folds its own slice through its
     * [SourceModel]; an operator node applies its [OperatorModel] to its inputs' already-computed
     * states, **in declaration order**, which is [civictech.oracle.bind.ShapeRule.inputs]'
     * port order. A node the terminals do not reach is still evaluated — an oracle that
     * skipped unobserved nodes would hide a model that throws only on the paths nobody looks
     * at.
     *
     * @throws IllegalStateException if the node graph has a cycle, naming the nodes involved.
     *   A dataflow graph with a feedback loop has no batch denotation, and silently folding
     *   one arbitrary iteration would produce an expected value nothing justifies.
     */
    fun eval(script: Script): Map<String, ModelState> {
        val computed = LinkedHashMap<NodeId, ModelState>()
        order().forEach { node ->
            computed[node.id] = when (node) {
                is ModelNode.Source -> node.model.evaluate(script.slice(node.source))
                is ModelNode.Operator -> node.model.evaluate(node.inputs.map { computed.getValue(it) })
            }
        }
        return terminals.mapValues { (_, node) -> computed.getValue(node) }
    }

    /** [nodes] in dependency order — every operator after all of its inputs. */
    private fun order(): List<ModelNode> {
        val ordered = ArrayList<ModelNode>(nodes.size)
        val settled = HashSet<NodeId>()
        var remaining = nodes
        while (remaining.isNotEmpty()) {
            val (ready, blocked) = remaining.partition { node ->
                node !is ModelNode.Operator || node.inputs.all { it in settled }
            }
            check(ready.isNotEmpty()) {
                "The reference model has a dependency cycle among nodes " +
                    "${blocked.map { it.id.id }.sorted()}; a graph with a feedback loop has no " +
                    "batch denotation, so no expected value can honestly be computed for it."
            }
            ready.forEach { settled += it.id }
            ordered += ready
            remaining = blocked
        }
        return ordered
    }

    companion object {
        /** A single-node model whose one terminal is that node — the shape most operator unit tests want. */
        fun terminal(name: String, node: ModelNode, vararg upstream: ModelNode): ReferenceModel =
            ReferenceModel(listOf(*upstream, node), mapOf(name to node.id))
    }
}

/** A node's name inside a [ReferenceModel]. Distinct from [SourceId]: several nodes may read one source. */
data class NodeId(val id: String) {
    init {
        require(id.isNotBlank()) { "NodeId must not be blank" }
    }
}

/** One node of a [ReferenceModel]. */
sealed interface ModelNode {

    val id: NodeId

    /** A source cell: folds [source]'s slice of the script through [model]. */
    data class Source(override val id: NodeId, val source: SourceId, val model: SourceModel) : ModelNode

    /**
     * A derived cell: applies [model] to [inputs]' states.
     *
     * [inputs] is ordered, and that order is the operator's **port order** — the same order
     * [civictech.oracle.bind.ShapeRule.inputs] declares. For a fan-in operator the list is
     * the arm order; the models that care ([QuorumSetModel]) read only its length.
     */
    data class Operator(override val id: NodeId, val model: OperatorModel, val inputs: List<NodeId>) : ModelNode {
        constructor(id: NodeId, model: OperatorModel, vararg inputs: NodeId) : this(id, model, inputs.toList())
    }
}
