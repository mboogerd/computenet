package civictech.query.lower

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.expr.Expr
import civictech.query.expr.ExprPredicate
import civictech.query.expr.ExprTyping
import civictech.query.expr.RowCombine
import civictech.query.expr.RowKey
import civictech.query.expr.RowProjection
import civictech.query.expr.TypingResult
import civictech.query.plan.AntiJoin
import civictech.query.plan.Difference
import civictech.query.plan.GroupAggregate
import civictech.query.plan.Intersect
import civictech.query.plan.Join
import civictech.query.plan.JoinKey
import civictech.query.plan.LogicalPlan
import civictech.query.plan.OuterJoin
import civictech.query.plan.PlanNode
import civictech.query.plan.PlanOrder
import civictech.query.plan.Project
import civictech.query.plan.Scan
import civictech.query.plan.Select
import civictech.query.plan.SemiJoin
import civictech.query.plan.Union
import civictech.query.schema.AttrType
import civictech.query.schema.Catalog

/**
 * [LogicalPlan] -> [GraphSpec] lowering (epic computenet-cab §4.3). A pure function over data:
 * it builds a step list and never touches a host, so nothing it does creates a live cell
 * (`[QRY1-LOWER-01]`). Applying the spec is the caller's business.
 *
 * **Total dispatch (cab.4-D5).** Every [PlanNode] kind is handled by an exhaustive `when`. A
 * node with no rule yields a [LoweringRefusal] naming its kind; the walk continues so every
 * refusal in the plan is collected, and any refusal makes the whole result
 * [LoweringResult.Refused]. Shapes the lowering cannot honour (an undeclared relation, a
 * column no input produces, an ill-typed comparison) are refusals too, never exceptions.
 *
 * **Handles and order (cab.4-D2).** One `src:<relation>` `SetCell` per relation any `Scan`
 * references, emitted first in sorted relation order; a relation scanned several times has one
 * source whose outlet fans out (`[QRY1-LOWER-11]`). Then per root, in sorted root-name order,
 * nodes are numbered in [PlanOrder.allNodes]' pre-order and named `<root>/<n>:<kind>`; steps
 * are emitted in post-order (children first), each node's connects right after its spawn, so
 * every `ConnectStep` follows both of its spawns as `GraphSpec.applyTo` requires. A `Scan`
 * takes a number but emits nothing: its handle is the shared source. Every spawn is
 * [IdentityBinding.FreshLogical], so one spec applies to one host more than once. Nothing on
 * this path iterates a hash-ordered collection, which with data-class factories is what makes
 * two lowerings of one plan `==` and byte-identical (`[QRY1-LOWER-05]`).
 *
 * **Rules owned here:** Scan, Select, Project, Join, SemiJoin, AntiJoin, Union. Intersect,
 * Difference, OuterJoin and a root GroupAggregate refuse as "not yet lowered" until the
 * completion task replaces them. A non-root GroupAggregate refuses permanently: its outlet is
 * a `MapDelta` stream and no kernel operator consumes one as a relation.
 */
object Lowering {

    const val NOT_YET_LOWERED = "not yet lowered"

    const val AGGREGATE_NOT_A_RELATION =
        "aggregate output is a MapDelta stream; no kernel operator consumes it as a relation"

    fun lower(plan: LogicalPlan, catalog: Catalog): LoweringResult {
        val refusals = mutableListOf<LoweringRefusal>()
        val diagnostics = mutableListOf<LoweringDiagnostic>()

        val relations = plan.roots.values
            .flatMap { PlanOrder.allNodes(it) }
            .filterIsInstance<Scan>()
            .map { it.relation }
            .distinct()
            .sorted()
        val sourceHandles = LinkedHashMap<String, String>()
        val steps = mutableListOf<GraphStep>()
        for (relation in relations) {
            // An undeclared relation is refused at its Scan node below; it gets no source.
            if (relation !in catalog.relations) continue
            val handle = sourceHandle(relation)
            sourceHandles[relation] = handle
            steps += SpawnStep(handle, SetSourceFactory(relation), IdentityBinding.FreshLogical)
        }

        val outputHandles = LinkedHashMap<String, String>()
        for (rootName in plan.roots.keys.sorted()) {
            val walk = RootWalk(rootName, catalog, steps, refusals, diagnostics)
            walk.lower(plan.roots.getValue(rootName), isRoot = true)?.let { outputHandles[rootName] = it.handle }
        }

        return if (refusals.isNotEmpty()) {
            LoweringResult.Refused(refusals.toList())
        } else {
            LoweringResult.Lowered(GraphSpec(steps.toList()), sourceHandles, outputHandles, diagnostics.toList())
        }
    }

    fun sourceHandle(relation: String): String = "src:$relation"

    /** The `<kind>` segment of a node handle: the node's class name, lower-cased. */
    fun kindName(node: PlanNode): String = nodeKind(node).lowercase()

    /** The plan node's kind as a [LoweringRefusal] names it. */
    fun nodeKind(node: PlanNode): String = when (node) {
        is Scan -> "Scan"
        is Select -> "Select"
        is Project -> "Project"
        is Join -> "Join"
        is SemiJoin -> "SemiJoin"
        is AntiJoin -> "AntiJoin"
        is Union -> "Union"
        is Intersect -> "Intersect"
        is Difference -> "Difference"
        is GroupAggregate -> "GroupAggregate"
        is OuterJoin -> "OuterJoin"
    }
}

/** A lowered node: the [handle] whose `outlet` carries its rows, positional against [columns]. */
private data class LoweredNode(val handle: String, val columns: List<String>, val types: List<AttrType>)

/** One root's walk; the counter numbers nodes in [PlanOrder.allNodes]' pre-order. */
private class RootWalk(
    private val rootName: String,
    private val catalog: Catalog,
    private val steps: MutableList<GraphStep>,
    private val refusals: MutableList<LoweringRefusal>,
    private val diagnostics: MutableList<LoweringDiagnostic>,
) {
    private var counter = 0

    /** Lowers [node] and its subtree; `null` when this node or anything below it refused. */
    fun lower(node: PlanNode, isRoot: Boolean): LoweredNode? {
        val handle = "$rootName/${counter++}:${Lowering.kindName(node)}"
        val locus = Locus.PlanNode(handle)
        fun refuse(reason: String): LoweredNode? {
            refusals += LoweringRefusal(locus, Lowering.nodeKind(node), reason)
            return null
        }

        return when (node) {
            is Scan -> when (val derived = ColumnTypes.scan(node.relation, node.outputColumns, catalog)) {
                is ColumnTypes.Derived.Failure -> refuse(derived.reason)
                is ColumnTypes.Derived.Types ->
                    LoweredNode(Lowering.sourceHandle(node.relation), node.outputColumns, derived.types)
            }

            is Select -> {
                val input = lower(node.input, isRoot = false) ?: return null
                if (node.outputColumns != input.columns) {
                    return refuse("select output columns ${node.outputColumns} differ from its input's ${input.columns}")
                }
                val expr = Expr.Cmp(node.condition.op, toExpr(node.condition.left), toExpr(node.condition.right))
                when (val typed = ExprTyping.typeOf(expr, ColumnTypes.asMap(input.columns, input.types))) {
                    is TypingResult.Typed -> if (typed.type != AttrType.BOOL) {
                        return refuse("[QRY1-HONEST-02] selection condition types to ${typed.type}, not BOOL")
                    }
                    is TypingResult.TypeMismatch ->
                        return refuse("[QRY1-HONEST-02] comparison ${node.condition} compares ${typed.left} with ${typed.right}")
                    is TypingResult.UnknownAttribute ->
                        return refuse("[QRY1-HONEST-02] comparison references unknown column '${typed.name}'")
                }
                addSpawn(handle, FilterFactory(ExprPredicate(input.columns, expr)))
                addConnect(input, handle, "inlet")
                LoweredNode(handle, node.outputColumns, input.types)
            }

            is Project -> {
                val input = lower(node.input, isRoot = false) ?: return null
                when (val derived = ColumnTypes.byName(node.outputColumns, listOf(input.columns to input.types))) {
                    is ColumnTypes.Derived.Failure -> refuse("projection: ${derived.reason}")
                    is ColumnTypes.Derived.Types -> {
                        addSpawn(handle, FlatMapFactory(RowProjection(input.columns, node.outputColumns)))
                        addConnect(input, handle, "inlet")
                        LoweredNode(handle, node.outputColumns, derived.types)
                    }
                }
            }

            is Join -> {
                val left = lower(node.left, isRoot = false)
                val right = lower(node.right, isRoot = false)
                if (left == null || right == null) return null
                keyProblem(node.equiKeys, left, right)?.let { return refuse(it) }
                val derived = ColumnTypes.byName(
                    node.outputColumns,
                    listOf(left.columns to left.types, right.columns to right.types),
                )
                when (derived) {
                    is ColumnTypes.Derived.Failure -> refuse("join: ${derived.reason}")
                    is ColumnTypes.Derived.Types -> {
                        addSpawn(
                            handle,
                            JoinFactory(
                                leftKey = RowKey(left.columns, node.equiKeys.map { it.left }),
                                rightKey = RowKey(right.columns, node.equiKeys.map { it.right }),
                                combine = RowCombine(left.columns, right.columns, node.outputColumns),
                            ),
                        )
                        addConnect(left, handle, "left")
                        addConnect(right, handle, "right")
                        LoweredNode(handle, node.outputColumns, derived.types)
                    }
                }
            }

            is SemiJoin -> lowerSemiJoin(
                node, node.input, node.witness, node.keys, negated = false, handle, locus, ::refuse,
            )

            is AntiJoin -> lowerSemiJoin(
                node, node.input, node.witness, node.keys, negated = true, handle, locus, ::refuse,
            )

            is Union -> {
                val branches = node.inputs.map { lower(it, isRoot = false) }
                if (branches.any { it == null }) return null
                val lowered = branches.filterNotNull()
                val first = lowered.first()
                lowered.firstOrNull { it.columns != node.outputColumns }?.let {
                    return refuse("union branch columns ${it.columns} differ from ${node.outputColumns}")
                }
                lowered.firstOrNull { it.types != first.types }?.let {
                    return refuse("union branch column types ${it.types} differ from the first branch's ${first.types}")
                }
                addSpawn(handle, UnionFactory(node.outputColumns))
                // Every branch fans into the one inlet ([QRY1-LOWER-10]); a branch handle
                // appearing twice (two scans of one relation) needs one link, not two.
                lowered.map { it.handle }.distinct().forEach { branch ->
                    steps += ConnectStep(branch, "outlet", handle, "inlet")
                }
                LoweredNode(handle, node.outputColumns, first.types)
            }

            is GroupAggregate -> {
                lower(node.input, isRoot = false)
                refuse(if (isRoot) Lowering.NOT_YET_LOWERED else Lowering.AGGREGATE_NOT_A_RELATION)
            }

            is Intersect -> {
                lower(node.left, isRoot = false)
                lower(node.right, isRoot = false)
                refuse(Lowering.NOT_YET_LOWERED)
            }

            is Difference -> {
                lower(node.left, isRoot = false)
                lower(node.right, isRoot = false)
                refuse(Lowering.NOT_YET_LOWERED)
            }

            is OuterJoin -> {
                lower(node.left, isRoot = false)
                lower(node.right, isRoot = false)
                refuse(Lowering.NOT_YET_LOWERED)
            }
        }
    }

    private fun lowerSemiJoin(
        node: PlanNode,
        inputNode: PlanNode,
        witnessNode: PlanNode,
        keys: List<JoinKey>,
        negated: Boolean,
        handle: String,
        locus: Locus.PlanNode,
        refuse: (String) -> LoweredNode?,
    ): LoweredNode? {
        val input = lower(inputNode, isRoot = false)
        val witness = lower(witnessNode, isRoot = false)
        if (input == null || witness == null) return null
        if (node.outputColumns != input.columns) {
            return refuse("output columns ${node.outputColumns} differ from its input's ${input.columns}")
        }
        keyProblem(keys, input, witness)?.let { return refuse(it) }
        val emitOnFrontier = if (negated) {
            val decision = Gating.decide(inputNode, witnessNode, locus, handle)
            decision.diagnostic?.let { diagnostics += it }
            decision.emitOnFrontier
        } else {
            false
        }
        addSpawn(
            handle,
            SemiJoinFactory(
                leftKey = RowKey(input.columns, keys.map { it.left }),
                rightKey = RowKey(witness.columns, keys.map { it.right }),
                negated = negated,
                emitOnFrontier = emitOnFrontier,
            ),
        )
        addConnect(input, handle, "left")
        addConnect(witness, handle, "right")
        return LoweredNode(handle, node.outputColumns, input.types)
    }

    /** Why [keys] cannot key [left] against [right], or `null` when they can. */
    private fun keyProblem(keys: List<JoinKey>, left: LoweredNode, right: LoweredNode): String? {
        val missingLeft = keys.map { it.left }.filterNot { it in left.columns }
        val missingRight = keys.map { it.right }.filterNot { it in right.columns }
        if (missingLeft.isNotEmpty() || missingRight.isNotEmpty()) {
            return "join keys name columns no input produces: left $missingLeft, right $missingRight"
        }
        val mismatched = keys.filter { key ->
            left.types[left.columns.indexOf(key.left)] != right.types[right.columns.indexOf(key.right)]
        }
        if (mismatched.isNotEmpty()) {
            return "[QRY1-HONEST-02] join keys $mismatched equate columns of different types"
        }
        return null
    }

    private fun addSpawn(handle: String, factory: civictech.cell.graph.CellFactory) {
        steps += SpawnStep(handle, factory, IdentityBinding.FreshLogical)
    }

    private fun addConnect(from: LoweredNode, to: String, inlet: String) {
        steps += ConnectStep(from.handle, "outlet", to, inlet)
    }

    private fun toExpr(term: Term): Expr = when (term) {
        is Term.Var -> Expr.Attr(term.name)
        is Term.Const -> Expr.Const(term.value, term.type)
    }
}
