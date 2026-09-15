package civictech.query.plan

import civictech.query.ast.AggregateKind
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.SetOpKind
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode
import civictech.query.lower.Lowering
import civictech.query.parse.SpanTable

/**
 * The set/bag boundary (epic computenet-cab §4.4, cab.5-D3, cab.5-D8): the two places a query's
 * answer could differ between bag and set semantics, refused as
 * [RejectionCode.BAG_SEMANTICS_REQUIRED] instead of compiled as a distinct-semantics
 * approximation (`[QRY1-SEM-02]`, `[QRY1-SEM-04]`). Both functions are total and pure; the
 * front door (`civictech.query.QueryCompiler`) runs [refuseAllSetOps] before planning and
 * [refuseLossyAggregates] over the plan.
 *
 * **The decision procedure is the planner's annotation, nothing else (cab.5-D3).** Whether an
 * aggregate's input is lossy is read from [PlanNode.keyPreserving], which [PlanAnalyses]
 * computes at every construction site (`[QRY1-PLAN-06]`). No second key analysis exists here:
 * the lost-key wording in a rejection's `specId` is read from the descendants' own
 * annotations. `[QRY1-HONEST-02]`'s "annotation missing → reject as undecidable" has no
 * reachable site: [PlanNode.keyPreserving] is a non-null `Boolean` on every node, so a node
 * without an annotation cannot be constructed.
 *
 * **Only an aggregate observes multiplicity.** `[QRY1-SEM-02]` lists "a further join" among the
 * multiplicity-sensitive consumers; that is realized transitively, not as a rule of its own —
 * [PlanAnalyses.joinKey] propagates non-preservation through a join, so a lossy projection
 * under a join under a `COUNT`/`SUM`/`AVG` is caught at the aggregate. A join whose output
 * feeds only a set-valued root is never refused: the set answer of a join does not depend on
 * its inputs' multiplicity. `MIN`, `MAX`, `TOP_K` and `COLLECT_TO_SET` are not
 * multiplicity-sensitive (a duplicate cannot change an extremum, a top-k of distinct values, or
 * a set) and are never refused on this ground.
 *
 * **cab.4.7's deferred question (cab.5-D8): not a rejection.** A rule whose aggregated
 * head-variable set is a strict subset of its body variables (`@count c(X) :- e(X, Y).`) has no
 * projection before its aggregate: the planner builds the [GroupAggregate] over the full body
 * plan, whose population is the body's distinct rows by definition. Over a relation with a
 * declared row key that input is key-preserving and the rule compiles. The same aggregate over
 * an intermediate predicate that projects the key away (`@count c(X) :- p(X). p(X) :- e(X, Y).`)
 * is refused.
 *
 * **Limit of the annotation, in the conservative direction.** [PlanNode.keyPreserving] `false`
 * means "not established", not "lossy" ([PlanAnalyses]' KDoc). A relation with **no declared row
 * key** therefore makes every `COUNT`/`SUM`/`AVG` over it refused, including one with no
 * projection at all (`@count c(X) :- e(X, Y).` over a keyless `e`), whose set and bag answers do
 * not in fact differ. That over-refusal is the acceptance of computenet-cab.5.2 (reject iff the
 * input's `keyPreserving` is `false`, naming "no declared row key"), chosen over a structural
 * exception that would be a second key analysis; it narrows the language (`[QRY1-API-05]`) and
 * never admits an approximation. A caller who needs such an aggregate declares the row key.
 */
object BagSemantics {

    /** Where weighted/bag semantics is owned — named by every rejection here (`[QRY1-SEM-02]`). */
    const val OWNERS = "owners: 96 §E6 / 95 R17"

    /** The aggregate kinds whose answer depends on input multiplicity. */
    val MULTIPLICITY_SENSITIVE: Set<AggregateKind> = setOf(AggregateKind.COUNT, AggregateKind.SUM, AggregateKind.AVG)

    /**
     * One [RejectionCode.BAG_SEMANTICS_REQUIRED] per `ALL` set operation in [query]'s
     * definitions (`[QRY1-SEM-04]`), in definition order and pre-order within a definition,
     * located at the definition's [SpanTable.definitions] span when [spans] has one for it and at
     * [Locus.RuleStatement] (the definition's index and head) otherwise. Rejected definitions are
     * excluded from planning by the front door, which is what keeps the planner's own `ALL`
     * guard unreachable.
     */
    fun refuseAllSetOps(query: Query, spans: SpanTable?): List<Rejection> =
        query.definitions.flatMapIndexed { index, definition ->
            val locus: Locus = spans?.definitions?.getOrNull(index)
                ?: Locus.RuleStatement(index, definition.head.predicate)
            allSetOps(definition.expr).map { setOp ->
                Rejection(
                    code = RejectionCode.BAG_SEMANTICS_REQUIRED,
                    locus = locus,
                    specId = "[QRY1-SEM-04] ${surfaceName(setOp.kind)} ALL is bag semantics; " +
                        "the operator algebra is set-semantic ([24-OP-SEMIJOIN-01]); $OWNERS",
                )
            }
        }

    /**
     * One [RejectionCode.BAG_SEMANTICS_REQUIRED] per `COUNT`/`SUM`/`AVG` [GroupAggregate] in
     * [plan] whose input is not key-preserving (`[QRY1-SEM-02]`), located at
     * `Locus.PlanNode("<root>/<n>:<kind>")` — the handle `civictech.query.lower.Lowering` gives
     * the same node: roots in sorted name order, nodes numbered in [PlanOrder.allNodes]'
     * pre-order. An aggregate inlined into several roots is refused at each occurrence.
     */
    fun refuseLossyAggregates(plan: LogicalPlan): List<Rejection> =
        plan.roots.keys.sorted().flatMap { rootName ->
            PlanOrder.allNodes(plan.roots.getValue(rootName)).withIndex().mapNotNull { (index, node) ->
                if (node !is GroupAggregate || node.aggregate.kind !in MULTIPLICITY_SENSITIVE || node.input.keyPreserving) {
                    null
                } else {
                    Rejection(
                        code = RejectionCode.BAG_SEMANTICS_REQUIRED,
                        locus = Locus.PlanNode("$rootName/$index:${Lowering.kindName(node)}"),
                        specId = "[QRY1-SEM-02] ${node.aggregate.kind} over a non-key-preserving input " +
                            "(${lossOf(node.input)}); $OWNERS",
                    )
                }
            }
        }

    /**
     * Why [input] is not key-preserving, read from its subtree's annotations: the witnesses of
     * the highest key-preserving descendants (the keys lost on the way up) and the relations
     * scanned without a declared row key.
     */
    private fun lossOf(input: PlanNode): String {
        val lost = highestKeyed(input).map { key -> key.joinToString(", ", "{", "}") }.distinct()
        val keyless = PlanOrder.allNodes(input).filterIsInstance<Scan>()
            .filter { !it.keyPreserving }.map { it.relation }.distinct().sorted()
        return listOfNotNull(
            lost.takeIf { it.isNotEmpty() }?.let { "lost row key ${it.joinToString(", ")}" },
            keyless.takeIf { it.isNotEmpty() }?.let { "no declared row key on ${it.joinToString(", ")}" },
        ).joinToString("; ")
    }

    /** The [PlanNode.preservedKey] of every key-preserving node in [node]'s subtree with no key-preserving ancestor there. */
    private fun highestKeyed(node: PlanNode): List<Set<String>> {
        node.preservedKey?.let { return listOf(it) }
        return when (node) {
            is Scan -> emptyList()
            is Select -> highestKeyed(node.input)
            is Project -> highestKeyed(node.input)
            is Join -> highestKeyed(node.left) + highestKeyed(node.right)
            is SemiJoin -> highestKeyed(node.input)
            is AntiJoin -> highestKeyed(node.input)
            is Union -> node.inputs.flatMap { highestKeyed(it) }
            is Intersect -> highestKeyed(node.left) + highestKeyed(node.right)
            is Difference -> highestKeyed(node.left)
            is GroupAggregate -> highestKeyed(node.input)
            is OuterJoin -> highestKeyed(node.left) + highestKeyed(node.right)
        }
    }

    private fun allSetOps(expr: RelationalExpr): List<RelationalExpr.SetOp> = when (expr) {
        is RelationalExpr.Relation -> emptyList()
        is RelationalExpr.SetOp -> listOfNotNull(expr.takeIf { it.all }) + allSetOps(expr.left) + allSetOps(expr.right)
        is RelationalExpr.OuterJoin -> allSetOps(expr.left) + allSetOps(expr.right)
    }

    /** The surface keyword of [kind] (`union` / `intersect` / `except`), upper-cased. */
    private fun surfaceName(kind: SetOpKind): String = when (kind) {
        SetOpKind.UNION -> "UNION"
        SetOpKind.INTERSECTION -> "INTERSECT"
        SetOpKind.DIFFERENCE -> "EXCEPT"
    }
}
