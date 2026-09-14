package civictech.query.lower

import civictech.cell.data.Aggregator
import civictech.cell.data.Aggregators
import civictech.query.expr.RowLongSelector
import civictech.query.expr.RowSelector
import civictech.query.schema.AttrType
import civictech.query.schema.Row
import java.io.Serializable

/**
 * The aggregate a [GroupByFactory] builds, as data (cab.4-D8, `[24-AGG-01]`). The kernel's
 * `Aggregator` implementations are private, non-data classes, so structural equality between
 * two lowerings of one plan (`[QRY1-LOWER-05]`) lives HERE, in the spec; [aggregator] builds a
 * fresh kernel `Aggregator` only when the factory creates its cell.
 *
 * Every selector is a `civictech.query.expr` interpreter, never a lambda. The typed variants
 * ([Min], [Max], [TopK]) carry the aggregated column's [AttrType] so the `Comparable`
 * extremum support is instantiated at the column's runtime type; all five runtime types
 * (`Int`, `Long`, `Double`, `String`, `Boolean`) are `Comparable` and `Serializable`.
 */
sealed interface AggregateSpec : Serializable {

    fun aggregator(): Aggregator<Row, *, *>

    /** `Aggregators.count()` — live rows per group. */
    data object Count : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = Aggregators.count<Row>()
    }

    /**
     * `Aggregators.sumOf` over an INT/LONG column only: the kernel sums `Long`, never `Double`,
     * because float sums are order-sensitive (`Aggregator.kt:30`); the lowering refuses the rest.
     */
    data class Sum(val selector: RowLongSelector) : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = Aggregators.sumOf(selector)
    }

    /** `Aggregators.avgOf` over an INT/LONG column only; see [Sum]. */
    data class Avg(val selector: RowLongSelector) : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = Aggregators.avgOf(selector)
    }

    /** `Aggregators.minOf` over the aggregated column, typed by [type]. */
    data class Min(val selector: RowSelector, val type: AttrType) : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = extremum(type, selector, min = true)
    }

    /** `Aggregators.maxOf` over the aggregated column, typed by [type]. */
    data class Max(val selector: RowSelector, val type: AttrType) : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = extremum(type, selector, min = false)
    }

    /** `Aggregators.topK(k, …)` over the aggregated column, typed by [type]. */
    data class TopK(val k: Int, val selector: RowSelector, val type: AttrType) : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = when (type) {
            AttrType.INT -> Aggregators.topK(k, typed<Int>(selector))
            AttrType.LONG -> Aggregators.topK(k, typed<Long>(selector))
            AttrType.DOUBLE -> Aggregators.topK(k, typed<Double>(selector))
            AttrType.STRING -> Aggregators.topK(k, typed<String>(selector))
            AttrType.BOOL -> Aggregators.topK(k, typed<Boolean>(selector))
        }
    }

    /**
     * `Aggregators.collectToSet()` — collects WHOLE input ROWS per group, not the values of a
     * single column: the aggregated value is the entire `Row`. A single-column collect would need
     * a projection to that column ahead of the group-by, which the planner does not emit.
     */
    data object CollectToSet : AggregateSpec {
        override fun aggregator(): Aggregator<Row, *, *> = Aggregators.collectToSet<Row>()
    }
}

private fun extremum(type: AttrType, selector: RowSelector, min: Boolean): Aggregator<Row, *, *> = when (type) {
    AttrType.INT -> if (min) Aggregators.minOf(typed<Int>(selector)) else Aggregators.maxOf(typed<Int>(selector))
    AttrType.LONG -> if (min) Aggregators.minOf(typed<Long>(selector)) else Aggregators.maxOf(typed<Long>(selector))
    AttrType.DOUBLE -> if (min) Aggregators.minOf(typed<Double>(selector)) else Aggregators.maxOf(typed<Double>(selector))
    AttrType.STRING -> if (min) Aggregators.minOf(typed<String>(selector)) else Aggregators.maxOf(typed<String>(selector))
    AttrType.BOOL -> if (min) Aggregators.minOf(typed<Boolean>(selector)) else Aggregators.maxOf(typed<Boolean>(selector))
}

/**
 * [RowSelector] is `(Row) -> Any?`; the column's [AttrType] fixes its runtime value class, so
 * the view as `(Row) -> V` is sound for the column it was built over. The cast keeps the
 * serializable interpreter itself inside the kernel aggregator rather than wrapping it in a lambda.
 */
@Suppress("UNCHECKED_CAST")
private fun <V> typed(selector: RowSelector): (Row) -> V = selector as (Row) -> V
