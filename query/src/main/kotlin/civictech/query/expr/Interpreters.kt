package civictech.query.expr

import civictech.query.ast.ComparisonOp
import civictech.query.schema.Row
import java.io.Serializable

/**
 * The kernel function-value interpreters for the `expr` algebra (cab.4-D1): each type below
 * is a `data class` whose fields are only `List<String>`/[Expr] (structural `equals`, and
 * `Serializable` since every field is), and each IMPLEMENTS one of the Kotlin function types
 * the kernel data-op cells take — `FilterCell`'s `(E) -> Boolean`, `FlatMapSetCell`'s
 * `(A) -> Iterable<B>`, `JoinSetCell`/`GroupByCell`'s key functions,
 * `RelationalGraphs`' `(A, B) -> C` / `(A, B?) -> C` combines, and the `Aggregators` selector
 * shapes — rather than STORING a lambda in a field, which would defeat the structural
 * equality `CellFactory` construction (cab.4-D1, [QRY1-LOWER-05]/[QRY1-LOWER-06]) depends on.
 * See `kernel/.../graph/GraphDsl.kt`'s `InstanceCellFactory` for the same discipline.
 *
 * Every interpreter resolves column names to `Row` indexes once and caches the resolution in
 * a non-constructor `val` — excluded from the generated `equals`/`hashCode`/`toString` (those
 * cover only primary-constructor properties) and restored, not recomputed, by Java
 * deserialization (an ordinary field is part of the serialized object graph). Index
 * resolution happens eagerly at construction so an impossible index (an unknown column name)
 * fails at construction time, never inside a running cell.
 */

/**
 * `(Row) -> Boolean`: the `FilterCell` predicate for a `Select` plan node. [columns] names
 * the incoming row's columns positionally; [expr] is evaluated against them. `Cmp` compares
 * two values of the same runtime class via `Comparable` for `LT`/`LE`/`GT`/`GE`, and by `==`
 * for `EQ`/`NE`.
 */
data class ExprPredicate(val columns: List<String>, val expr: Expr) : (Row) -> Boolean, Serializable {

    private val columnIndex: Map<String, Int> = columns.withIndex().associate { it.value to it.index }

    init {
        val unknown = unknownAttrNames(expr).filterNot { it in columnIndex }
        require(unknown.isEmpty()) {
            "ExprPredicate: expr references unknown columns $unknown (columns=$columns)"
        }
    }

    override fun invoke(row: Row): Boolean {
        require(row.values.size == columns.size) {
            "ExprPredicate: row arity ${row.values.size} != columns arity ${columns.size}"
        }
        return eval(expr, row) as Boolean
    }

    private fun eval(expr: Expr, row: Row): Any? = when (expr) {
        is Expr.Attr -> row.values[columnIndex.getValue(expr.name)]
        is Expr.Const -> expr.value
        is Expr.Cmp -> compare(expr.op, eval(expr.left, row), eval(expr.right, row))
        is Expr.And -> (eval(expr.left, row) as Boolean) && (eval(expr.right, row) as Boolean)
        is Expr.Or -> (eval(expr.left, row) as Boolean) || (eval(expr.right, row) as Boolean)
        is Expr.Not -> !(eval(expr.expr, row) as Boolean)
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(op: ComparisonOp, left: Any?, right: Any?): Boolean = when (op) {
        ComparisonOp.EQ -> left == right
        ComparisonOp.NE -> left != right
        else -> {
            requireNotNull(left) { "ExprPredicate: comparison operand is null" }
            requireNotNull(right) { "ExprPredicate: comparison operand is null" }
            require(left::class == right::class) {
                "ExprPredicate: comparison operands of different runtime types " +
                    "(${left::class}, ${right::class})"
            }
            val c = (left as Comparable<Any>).compareTo(right)
            when (op) {
                ComparisonOp.LT -> c < 0
                ComparisonOp.LE -> c <= 0
                ComparisonOp.GT -> c > 0
                ComparisonOp.GE -> c >= 0
                else -> error("unreachable: EQ/NE handled above")
            }
        }
    }

    private fun unknownAttrNames(expr: Expr): List<String> = when (expr) {
        is Expr.Attr -> listOf(expr.name)
        is Expr.Const -> emptyList()
        is Expr.Cmp -> unknownAttrNames(expr.left) + unknownAttrNames(expr.right)
        is Expr.And -> unknownAttrNames(expr.left) + unknownAttrNames(expr.right)
        is Expr.Or -> unknownAttrNames(expr.left) + unknownAttrNames(expr.right)
        is Expr.Not -> unknownAttrNames(expr.expr)
    }
}

/**
 * `(Row) -> Iterable<Row>`: the `FlatMapSetCell` transform for `Project` and head
 * construction ([QRY1-LOWER-07]) — a pure, total index permutation. [to] must be a subset of
 * [from]; validated at construction, so an impossible projection fails at lowering, never
 * inside a cell. Always returns exactly one narrowed row.
 */
data class RowProjection(val from: List<String>, val to: List<String>) : (Row) -> Iterable<Row>, Serializable {

    private val fromIndex: Map<String, Int> = from.withIndex().associate { it.value to it.index }

    init {
        val missing = to.filterNot { it in fromIndex }
        require(missing.isEmpty()) {
            "RowProjection: to names columns not present in from: $missing (from=$from)"
        }
    }

    override fun invoke(row: Row): Iterable<Row> {
        require(row.values.size == from.size) {
            "RowProjection: row arity ${row.values.size} != from arity ${from.size}"
        }
        return listOf(Row(to.map { row.values[fromIndex.getValue(it)] }))
    }
}

/**
 * `(Row) -> Row`: the join/semijoin/group-by key extractor. [keyColumns] is a (possibly
 * empty) subset of [columns], in the order the key row carries them. An empty [keyColumns]
 * yields `Row(emptyList())`, the unit key for the cross product ([24-OP-JOINSET-02]).
 */
data class RowKey(val columns: List<String>, val keyColumns: List<String>) : (Row) -> Row, Serializable {

    private val columnIndex: Map<String, Int> = columns.withIndex().associate { it.value to it.index }

    init {
        val missing = keyColumns.filterNot { it in columnIndex }
        require(missing.isEmpty()) {
            "RowKey: keyColumns names columns not present in columns: $missing (columns=$columns)"
        }
    }

    override fun invoke(row: Row): Row {
        require(row.values.size == columns.size) {
            "RowKey: row arity ${row.values.size} != columns arity ${columns.size}"
        }
        return Row(keyColumns.map { row.values[columnIndex.getValue(it)] })
    }
}

/**
 * `(Row, Row) -> Row`: the `JoinSetCell` combine for an inner join. Builds [outputColumns] by
 * name, reading from [leftColumns] first and [rightColumns] otherwise — a column shared by
 * both sides (the join key, typically) is read from the left row.
 */
data class RowCombine(
    val leftColumns: List<String>,
    val rightColumns: List<String>,
    val outputColumns: List<String>,
) : (Row, Row) -> Row, Serializable {

    private val leftIndex: Map<String, Int> = leftColumns.withIndex().associate { it.value to it.index }
    private val rightIndex: Map<String, Int> = rightColumns.withIndex().associate { it.value to it.index }

    init {
        val missing = outputColumns.filterNot { it in leftIndex || it in rightIndex }
        require(missing.isEmpty()) {
            "RowCombine: outputColumns names columns not present in either side: $missing " +
                "(leftColumns=$leftColumns, rightColumns=$rightColumns)"
        }
    }

    override fun invoke(left: Row, right: Row): Row {
        require(left.values.size == leftColumns.size) {
            "RowCombine: left row arity ${left.values.size} != leftColumns arity ${leftColumns.size}"
        }
        require(right.values.size == rightColumns.size) {
            "RowCombine: right row arity ${right.values.size} != rightColumns arity ${rightColumns.size}"
        }
        return Row(
            outputColumns.map { name ->
                leftIndex[name]?.let { left.values[it] } ?: right.values[rightIndex.getValue(name)]
            },
        )
    }
}

/**
 * `(Row?, Row?) -> Row`: the outer-join variant of [RowCombine] ([RelationalGraphs]'
 * `(A, B?) -> C` / `(A?, B?) -> C` combine shapes). A missing side (`null`) contributes `null`
 * for every column [outputColumns] would otherwise read from it, rather than throwing.
 */
data class RowCombinePadded(
    val leftColumns: List<String>,
    val rightColumns: List<String>,
    val outputColumns: List<String>,
) : (Row?, Row?) -> Row, Serializable {

    private val leftIndex: Map<String, Int> = leftColumns.withIndex().associate { it.value to it.index }
    private val rightIndex: Map<String, Int> = rightColumns.withIndex().associate { it.value to it.index }

    init {
        val missing = outputColumns.filterNot { it in leftIndex || it in rightIndex }
        require(missing.isEmpty()) {
            "RowCombinePadded: outputColumns names columns not present in either side: $missing " +
                "(leftColumns=$leftColumns, rightColumns=$rightColumns)"
        }
    }

    override fun invoke(left: Row?, right: Row?): Row {
        require(left == null || left.values.size == leftColumns.size) {
            "RowCombinePadded: left row arity ${left?.values?.size} != leftColumns arity ${leftColumns.size}"
        }
        require(right == null || right.values.size == rightColumns.size) {
            "RowCombinePadded: right row arity ${right?.values?.size} != rightColumns arity ${rightColumns.size}"
        }
        return Row(
            outputColumns.map { name ->
                val li = leftIndex[name]
                if (li != null) left?.values?.get(li) else right?.values?.get(rightIndex.getValue(name))
            },
        )
    }
}

/**
 * `(Row) -> Any?`: the `Aggregators.minOf`/`maxOf`/`topK` selector — reads [column] out of a
 * [columns]-shaped row without widening it.
 */
data class RowSelector(val columns: List<String>, val column: String) : (Row) -> Any?, Serializable {

    private val index: Int = columns.indexOf(column).also {
        require(it >= 0) { "RowSelector: column $column not present in columns=$columns" }
    }

    override fun invoke(row: Row): Any? {
        require(row.values.size == columns.size) {
            "RowSelector: row arity ${row.values.size} != columns arity ${columns.size}"
        }
        return row.values[index]
    }
}

/**
 * `(Row) -> Long`: the `Aggregators.sumOf`/`avgOf` selector. Widens an `INT`-typed slot (a
 * boxed `Int`) to `Long`; constructed ONLY for `INT`/`LONG` columns — that is the caller's
 * (the lowering task's) obligation, not something this type re-derives, since it carries no
 * `AttrType` of its own (`Aggregator.kt:31` documents why sums are `Long`, never `Double`).
 */
data class RowLongSelector(val columns: List<String>, val column: String) : (Row) -> Long, Serializable {

    private val index: Int = columns.indexOf(column).also {
        require(it >= 0) { "RowLongSelector: column $column not present in columns=$columns" }
    }

    override fun invoke(row: Row): Long {
        require(row.values.size == columns.size) {
            "RowLongSelector: row arity ${row.values.size} != columns arity ${columns.size}"
        }
        return when (val v = row.values[index]) {
            is Long -> v
            is Int -> v.toLong()
            else -> error(
                "RowLongSelector: column $column value $v (${v?.let { it::class }}) is neither Int nor Long",
            )
        }
    }
}
