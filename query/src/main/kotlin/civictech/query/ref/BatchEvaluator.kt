package civictech.query.ref

import civictech.query.ast.AggregateKind
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Term
import civictech.query.plan.AntiJoin
import civictech.query.plan.Difference
import civictech.query.plan.GroupAggregate
import civictech.query.plan.Intersect
import civictech.query.plan.Join
import civictech.query.plan.JoinKey
import civictech.query.plan.LogicalPlan
import civictech.query.plan.OuterJoin
import civictech.query.plan.OuterJoinSide
import civictech.query.plan.PlanNode
import civictech.query.plan.Project
import civictech.query.plan.Scan
import civictech.query.plan.Select
import civictech.query.plan.SemiJoin
import civictech.query.plan.Union
import civictech.query.schema.Row

/**
 * The reference evaluator for a [LogicalPlan] (`[QRY1-ORA-02]`): naive, non-incremental,
 * total recomputation of every root from the FINAL contents of the base relations. It keeps no
 * state between calls and never sees a delta, a wave or an interleaving — it answers what the
 * query means over one database snapshot, which is what an incrementally maintained compiled
 * query must equal once it quiesces.
 *
 * **What this evaluator's own correctness rests on — NOT proven by this epic
 * (`[QRY1-HONEST-01]`).** Nothing in computenet-cab proves `BatchEvaluator` right. It is a
 * second, independently written reading of the plan semantics, and it is defended — not
 * proven — by four things only:
 * 1. `[QRY1-ORA-03]` independence: this package imports only `civictech.query.plan`,
 *    `civictech.query.ast` and `civictech.query.schema` — no `civictech.cell.data` /
 *    `civictech.cell.data.op` type (`RefImportBoundaryTest`) and, by cab.6-D6, none of the
 *    lowering's own `civictech.query.expr` interpreters (`ExprPredicate`, `RowProjection`,
 *    `RowKey`, `RowCombine`, `RowPad`), so the reference cannot agree with the lowering about a
 *    bug they share code for;
 * 2. the divergence control `[QRY1-ORA-07]`: a deliberately wrong compiled cell must make the
 *    differential suite report a mismatch against this evaluator;
 * 3. the mutation check `[QRY1-ORA-08]`;
 * 4. agreement with `RelationalGraphsTest`'s hand-rolled left-join fold
 *    (`LeftJoinFoldAgreementTest`, cab.6-D10).
 * A semantic error made identically here and in the planner (which both read the same
 * [LogicalPlan]) is outside all four defences.
 *
 * Result type and independence decisions: cab.6-D5 ([RelationValue]) and cab.6-D6.
 *
 * **Semantics**, each written against the plan vocabulary rather than against the lowering's
 * code:
 * - [Scan]: `db[relation]` verbatim; a relation absent from `db` is the empty set. A row whose
 *   arity differs from the scan's columns is a plan/db mismatch and throws.
 * - [Select]: `EQ`/`NE` by `==`; `LT`/`LE`/`GT`/`GE` by `Comparable` over non-null operands of
 *   one runtime class. Operands of two different runtime classes throw for every operator.
 * - [Project]: by column name, the result a set (duplicates collapse, `[QRY1-SEM-01]`).
 * - [Join]: nested loop on `equiKeys` (empty = cross product); an output column is read from
 *   the left row when the left side carries it, else from the right row.
 * - [SemiJoin]/[AntiJoin]: existence / absence of a witness row with equal key values.
 * - [Union]/[Intersect]/[Difference]: set operations over operands whose columns equal the
 *   node's.
 * - [GroupAggregate] (roots only): the population is the DISTINCT ROWS of the input — the full
 *   body plan, per computenet-cab.4.7 — per group. COUNT and SUM `Long`, AVG `Double`
 *   (`sum.toDouble() / n`), MIN/MAX the column value, TOP_K the k largest values descending
 *   with each value repeated by the number of rows carrying it, COLLECT_TO_SET the set of whole
 *   input rows. Grouped → [RelationValue.Groups] keyed by `Row(group values)`; scalar COUNT →
 *   [RelationValue.Count]; scalar other → [RelationValue.Groups] under `"global"`, absent when
 *   the input is empty.
 * - [OuterJoin]: matched rows as [Join]; each unmatched row of a preserved side (LEFT: left,
 *   RIGHT: right, FULL: both) padded by reading every output column from that row by name and
 *   `null` for the rest — so a right-only row carries its own key value.
 *
 * **Refusal.** A plan shape this evaluator cannot honour — a column a node names that its
 * input does not produce, comparison operands of different runtime classes, SUM/AVG over a
 * value that is not `Int`/`Long`, MIN/MAX/TOP_K over a null or mixed-class column, an aggregate
 * below the root — throws [IllegalStateException] naming the root and the node. It never
 * returns a partial answer; the differential runner reports the throw as a model evaluation
 * failure (`[QRY1-ORA-11]`), distinct from a mismatch.
 */
object BatchEvaluator {

    /** Every root of [plan], recomputed from [db] (relation name to its live rows). */
    fun evaluate(plan: LogicalPlan, db: Map<String, Set<Row>>): Map<String, RelationValue> =
        plan.roots.mapValues { (root, node) -> RootEvaluation(root, db).root(node) }

    private class RootEvaluation(private val root: String, private val db: Map<String, Set<Row>>) {

        fun root(node: PlanNode): RelationValue = when (node) {
            is GroupAggregate -> aggregate(node)
            else -> RelationValue.Rows(rows(node))
        }

        /** [node]'s distinct rows, positional against `node.outputColumns`. Exhaustive over [PlanNode]. */
        fun rows(node: PlanNode): Set<Row> = when (node) {
            is Scan -> scan(node)
            is Select -> select(node)
            is Project -> project(node)
            is Join -> join(node)
            is SemiJoin -> semi(node, node.input, node.witness, node.keys, negated = false)
            is AntiJoin -> semi(node, node.input, node.witness, node.keys, negated = true)
            is Union -> union(node)
            is Intersect -> intersect(node)
            is Difference -> difference(node)
            is GroupAggregate -> fail(node, "an aggregate is not a relation; it may appear only as a root")
            is OuterJoin -> outerJoin(node)
        }

        private fun scan(node: Scan): Set<Row> {
            val rows = db[node.relation] ?: emptySet()
            rows.firstOrNull { it.values.size != node.outputColumns.size }?.let {
                fail(node, "row $it of relation '${node.relation}' has arity ${it.values.size}, the scan names ${node.outputColumns}")
            }
            return rows.toSet()
        }

        private fun select(node: Select): Set<Row> {
            val input = node.input
            requireSameColumns(node, input.outputColumns)
            val condition = node.condition
            listOf(condition.left, condition.right).filterIsInstance<Term.Var>().forEach {
                if (it.name !in input.outputColumns) fail(node, "condition references column '${it.name}' not in ${input.outputColumns}")
            }
            return rows(input).filterTo(LinkedHashSet()) { row -> holds(node, condition, input.outputColumns, row) }
        }

        private fun holds(node: PlanNode, condition: Literal.Comparison, columns: List<String>, row: Row): Boolean {
            fun operand(term: Term): Any? = when (term) {
                is Term.Var -> row.values[columns.indexOf(term.name)]
                is Term.Const -> term.value
            }
            val left = operand(condition.left)
            val right = operand(condition.right)
            if (left != null && right != null && left::class != right::class) {
                fail(node, "comparison $condition over operands of different runtime classes (${left::class}, ${right::class})")
            }
            return when (condition.op) {
                ComparisonOp.EQ -> left == right
                ComparisonOp.NE -> left != right
                else -> {
                    if (left == null || right == null) fail(node, "ordered comparison $condition over a null operand")
                    val c = compareSameClass(node, left, right)
                    when (condition.op) {
                        ComparisonOp.LT -> c < 0
                        ComparisonOp.LE -> c <= 0
                        ComparisonOp.GT -> c > 0
                        else -> c >= 0
                    }
                }
            }
        }

        private fun project(node: Project): Set<Row> {
            val from = node.input.outputColumns
            return rows(node.input).mapTo(LinkedHashSet()) { byName(node, it, from, node.outputColumns) }
        }

        private fun join(node: Join): Set<Row> {
            val left = node.left
            val right = node.right
            requireKeys(node, node.equiKeys, left.outputColumns, right.outputColumns)
            requireProduced(node, node.outputColumns, left.outputColumns + right.outputColumns)
            val rightRows = rows(right)
            val out = LinkedHashSet<Row>()
            for (l in rows(left)) {
                for (r in rightRows) {
                    if (keysMatch(node.equiKeys, left.outputColumns, l, right.outputColumns, r)) {
                        out += combine(node.outputColumns, left.outputColumns, l, right.outputColumns, r)
                    }
                }
            }
            return out
        }

        private fun semi(node: PlanNode, input: PlanNode, witness: PlanNode, keys: List<JoinKey>, negated: Boolean): Set<Row> {
            requireSameColumns(node, input.outputColumns)
            requireKeys(node, keys, input.outputColumns, witness.outputColumns)
            val witnesses = rows(witness)
            return rows(input).filterTo(LinkedHashSet()) { row ->
                val found = witnesses.any { w -> keysMatch(keys, input.outputColumns, row, witness.outputColumns, w) }
                found != negated
            }
        }

        private fun union(node: Union): Set<Row> {
            node.inputs.forEach { requireOperandColumns(node, it) }
            return node.inputs.flatMapTo(LinkedHashSet()) { rows(it) }
        }

        private fun intersect(node: Intersect): Set<Row> {
            requireOperandColumns(node, node.left)
            requireOperandColumns(node, node.right)
            val right = rows(node.right)
            return rows(node.left).filterTo(LinkedHashSet()) { it in right }
        }

        private fun difference(node: Difference): Set<Row> {
            requireOperandColumns(node, node.left)
            requireOperandColumns(node, node.right)
            val right = rows(node.right)
            return rows(node.left).filterTo(LinkedHashSet()) { it !in right }
        }

        private fun outerJoin(node: OuterJoin): Set<Row> {
            val leftCols = node.left.outputColumns
            val rightCols = node.right.outputColumns
            requireKeys(node, node.keys, leftCols, rightCols)
            requireProduced(node, node.outputColumns, leftCols + rightCols)
            val leftRows = rows(node.left)
            val rightRows = rows(node.right)
            val out = LinkedHashSet<Row>()
            for (l in leftRows) {
                for (r in rightRows) {
                    if (keysMatch(node.keys, leftCols, l, rightCols, r)) out += combine(node.outputColumns, leftCols, l, rightCols, r)
                }
            }
            if (node.side == OuterJoinSide.LEFT || node.side == OuterJoinSide.FULL) {
                for (l in leftRows) {
                    if (rightRows.none { r -> keysMatch(node.keys, leftCols, l, rightCols, r) }) out += pad(node.outputColumns, leftCols, l)
                }
            }
            if (node.side == OuterJoinSide.RIGHT || node.side == OuterJoinSide.FULL) {
                for (r in rightRows) {
                    if (leftRows.none { l -> keysMatch(node.keys, leftCols, l, rightCols, r) }) out += pad(node.outputColumns, rightCols, r)
                }
            }
            return out
        }

        private fun aggregate(node: GroupAggregate): RelationValue {
            val columns = node.input.outputColumns
            requireProduced(node, node.groupByColumns, columns)
            val column = node.aggregatedColumn
            if (column != null) requireProduced(node, listOf(column), columns)
            val kind = node.aggregate.kind
            val population = rows(node.input)
            if (node.groupByColumns.isEmpty() && kind == AggregateKind.COUNT) {
                return RelationValue.Count(population.size.toLong())
            }
            val groups: Map<Any?, List<Row>> = if (node.groupByColumns.isEmpty()) {
                if (population.isEmpty()) emptyMap() else mapOf(GLOBAL_KEY to population.toList())
            } else {
                population.groupBy { row -> byName(node, row, columns, node.groupByColumns) }
            }
            return RelationValue.Groups(groups.mapValues { (_, members) -> aggregateValue(node, kind, column, columns, members) })
        }

        private fun aggregateValue(node: GroupAggregate, kind: AggregateKind, column: String?, columns: List<String>, members: List<Row>): Any? {
            fun values(): List<Any?> {
                if (column == null) fail(node, "$kind needs an aggregated column")
                val index = columns.indexOf(column)
                return members.map { it.values[index] }
            }
            fun longs(): List<Long> = values().map { v ->
                when (v) {
                    is Long -> v
                    is Int -> v.toLong()
                    else -> fail(node, "$kind over column '$column' value $v (${v?.let { it::class }}) that is neither Int nor Long")
                }
            }
            fun comparables(): List<Any> {
                val vs = values()
                val nonNull = vs.map { it ?: fail(node, "$kind over column '$column' meets a null value") }
                val classes = nonNull.map { it::class }.toSet()
                if (classes.size > 1) fail(node, "$kind over column '$column' mixes runtime classes $classes")
                if (nonNull.any { it !is Comparable<*> }) fail(node, "$kind over column '$column' meets a non-Comparable value")
                return nonNull
            }
            val comparator = Comparator<Any> { a, b -> compareSameClass(node, a, b) }
            return when (kind) {
                AggregateKind.COUNT -> members.size.toLong()
                AggregateKind.SUM -> longs().sum()
                AggregateKind.AVG -> longs().let { it.sum().toDouble() / it.size }
                AggregateKind.MIN -> comparables().minWithOrNull(comparator)
                AggregateKind.MAX -> comparables().maxWithOrNull(comparator)
                AggregateKind.TOP_K -> {
                    val k = node.aggregate.k ?: fail(node, "TOP_K without k")
                    comparables().sortedWith(comparator.reversed()).take(k)
                }
                AggregateKind.COLLECT_TO_SET -> members.toSet()
            }
        }

        // ------------------------------------------------------------------ row helpers

        private fun byName(node: PlanNode, row: Row, from: List<String>, to: List<String>): Row {
            requireProduced(node, to, from)
            return Row(to.map { row.values[from.indexOf(it)] })
        }

        private fun keysMatch(keys: List<JoinKey>, leftCols: List<String>, l: Row, rightCols: List<String>, r: Row): Boolean =
            keys.all { key -> l.values[leftCols.indexOf(key.left)] == r.values[rightCols.indexOf(key.right)] }

        private fun combine(out: List<String>, leftCols: List<String>, l: Row, rightCols: List<String>, r: Row): Row =
            Row(out.map { name -> if (name in leftCols) l.values[leftCols.indexOf(name)] else r.values[rightCols.indexOf(name)] })

        private fun pad(out: List<String>, cols: List<String>, row: Row): Row =
            Row(out.map { name -> if (name in cols) row.values[cols.indexOf(name)] else null })

        @Suppress("UNCHECKED_CAST")
        private fun compareSameClass(node: PlanNode, a: Any, b: Any): Int {
            if (a::class != b::class) fail(node, "cannot order values of different runtime classes (${a::class}, ${b::class})")
            val comparable = a as? Comparable<Any> ?: fail(node, "cannot order non-Comparable value $a")
            return comparable.compareTo(b)
        }

        // ------------------------------------------------------------------ shape checks

        private fun requireSameColumns(node: PlanNode, inputColumns: List<String>) {
            if (node.outputColumns != inputColumns) fail(node, "output columns ${node.outputColumns} differ from its input's $inputColumns")
        }

        private fun requireOperandColumns(node: PlanNode, operand: PlanNode) {
            if (operand.outputColumns != node.outputColumns) {
                fail(node, "operand columns ${operand.outputColumns} differ from the output's ${node.outputColumns}")
            }
        }

        private fun requireProduced(node: PlanNode, named: List<String>, produced: List<String>) {
            val missing = named.filterNot { it in produced }
            if (missing.isNotEmpty()) fail(node, "names columns $missing that no input produces (inputs produce $produced)")
        }

        private fun requireKeys(node: PlanNode, keys: List<JoinKey>, leftCols: List<String>, rightCols: List<String>) {
            requireProduced(node, keys.map { it.left }, leftCols)
            requireProduced(node, keys.map { it.right }, rightCols)
        }

        private fun fail(node: PlanNode, reason: String): Nothing =
            throw IllegalStateException(
                "BatchEvaluator cannot honour root '$root' at ${node::class.simpleName}${node.outputColumns}: $reason",
            )
    }

    /** The single group key of a scalar non-COUNT aggregate — `GroupByCell.global`'s constant. */
    private const val GLOBAL_KEY = "global"
}
