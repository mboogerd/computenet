package civictech.query.lower

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.Aggregator
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.TypedCellFactory
import civictech.query.expr.ExprPredicate
import civictech.query.expr.RowCombine
import civictech.query.expr.RowKey
import civictech.query.expr.RowPad
import civictech.query.expr.RowProjection
import civictech.query.schema.Row
import java.io.Serializable

/*
 * The cell factories lowering puts into `SpawnStep`s (`[QRY1-LOWER-06]`, cab.4-D1). Each is a
 * `data class` whose fields are plain data or `civictech.query.expr` interpreters — never a
 * lambda, never a captured plan node — so two lowerings of one plan produce `==` factories
 * and identical serialized bytes (`[QRY1-LOWER-05]`), and a spec survives a Java
 * serialization round trip `==` to itself. `create` only constructs the kernel cell with the
 * ref it is handed; it performs no host call.
 */

/**
 * The shared EDB source for [relation] (`[24-SET-*]`): one `SetCell` per relation the plan
 * scans, however many scans reference it. [relation] configures nothing in the cell; it makes
 * two sources of different relations unequal, as they are.
 */
data class SetSourceFactory(val relation: String) : TypedCellFactory<SetCell<Row>> {
    override fun create(ref: CellRef): SetCell<Row> = SetCell(ref = ref)
}

/** A `Select` node (`[24-OP-FILTER-01]`). */
data class FilterFactory(val predicate: ExprPredicate) : TypedCellFactory<FilterCell<Row>> {
    override fun create(ref: CellRef): FilterCell<Row> = FilterCell(ref = ref, predicate = predicate)
}

/** A `Project` node (`[24-OP-FLATMAP-01..02]`). */
data class FlatMapFactory(val transform: RowProjection) : TypedCellFactory<FlatMapSetCell<Row, Row>> {
    override fun create(ref: CellRef): FlatMapSetCell<Row, Row> = FlatMapSetCell(ref = ref, f = transform)
}

/** A `Join` node; empty keys join at the unit key, the cross product (`[24-OP-JOINSET-01..02]`). */
data class JoinFactory(
    val leftKey: RowKey,
    val rightKey: RowKey,
    val combine: RowCombine,
) : TypedCellFactory<JoinSetCell<Row, Row, Row, Row>> {
    override fun create(ref: CellRef): JoinSetCell<Row, Row, Row, Row> =
        JoinSetCell(ref = ref, leftKey = leftKey, rightKey = rightKey, combine = combine)
}

/**
 * A `SemiJoin` ([negated] `false`) or `AntiJoin` ([negated] `true`) node
 * (`[24-OP-SEMIJOIN-01..02]`); [emitOnFrontier] is decided by [Gating.decide].
 */
data class SemiJoinFactory(
    val leftKey: RowKey,
    val rightKey: RowKey,
    val negated: Boolean,
    val emitOnFrontier: Boolean,
) : TypedCellFactory<SemiJoinCell<Row, Row, Row>> {
    override fun create(ref: CellRef): SemiJoinCell<Row, Row, Row> = SemiJoinCell(
        ref = ref,
        leftKey = leftKey,
        rightKey = rightKey,
        negated = negated,
        emitOnFrontier = emitOnFrontier,
    )
}

/**
 * A `Union` node (`[24-OP-UNION-01]`, `[QRY1-LOWER-10]`). [columns] configures nothing in the
 * cell; it records the row shape every branch agrees on, and keeps this a data class.
 */
data class UnionFactory(val columns: List<String>) : TypedCellFactory<UnionSetCell<Row>> {
    override fun create(ref: CellRef): UnionSetCell<Row> = UnionSetCell(ref = ref)
}

/**
 * An `Intersect` node (`[24-OP-INTERSECT-01]`). [columns] configures nothing in the cell; it
 * records the row shape both sides agree on, and keeps this a data class.
 */
data class IntersectFactory(val columns: List<String>) : TypedCellFactory<IntersectSetCell<Row>> {
    override fun create(ref: CellRef): IntersectSetCell<Row> = IntersectSetCell(ref = ref)
}

/**
 * The null-padding `FlatMapSetCell` inside an outer-join composition — `RelationalGraphs`'
 * `$name-null` / `$name-left-null` / `$name-right-null` cells (`[24-OP-OUTERJOIN-01]`).
 */
data class PadFactory(val pad: RowPad) : TypedCellFactory<FlatMapSetCell<Row, Row>> {
    override fun create(ref: CellRef): FlatMapSetCell<Row, Row> = FlatMapSetCell(ref = ref, f = pad)
}

/**
 * A root `GroupAggregate` (`[24-OP-GROUPBY-01..05]`, `[24-AGG-01]`): keyed by [key] when the
 * node groups, or `GroupByCell.global` (the constant `"global"` key) when [key] is `null` — the
 * scalar aggregate. The kernel `Aggregator` is built from [spec] at `create` time; equality
 * lives in [spec], never in an `Aggregator` instance.
 */
data class GroupByFactory(val key: RowKey?, val spec: AggregateSpec) : TypedCellFactory<GroupByCell<Row, *, *, *>> {
    @Suppress("UNCHECKED_CAST")
    override fun create(ref: CellRef): GroupByCell<Row, *, *, *> {
        val aggregator = spec.aggregator() as Aggregator<Row, Any?, Serializable>
        return if (key == null) {
            GroupByCell.global(aggregator, ref)
        } else {
            GroupByCell(ref = ref, keyFn = key, aggregator = aggregator)
        }
    }
}

/**
 * A root scalar `COUNT` — §3.2's "COUNT over the whole relation" row (`[24-OP-COUNT-01]`): a
 * `CountCell` over the input rows, emitting `CounterDelta`. [columns] records the counted rows'
 * shape and keeps this a data class.
 */
data class CountFactory(val columns: List<String>) : TypedCellFactory<CountCell<Row>> {
    override fun create(ref: CellRef): CountCell<Row> = CountCell(ref = ref)
}
