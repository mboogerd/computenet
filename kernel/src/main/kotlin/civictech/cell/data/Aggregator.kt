package civictech.cell.data

import java.io.Serializable

/**
 * A grouped aggregate (M11.3): a deterministic function of group membership.
 * [value] may depend only on WHICH elements are live — never on insertion or
 * retraction order (22: convergence, not simultaneity). That is what makes
 * "incremental equals batch recompute" testable and per-peer recompute over
 * replicated inputs converge. Arrival-order aggregates (first/last/scan) are
 * excluded from the family by this rule.
 *
 * Implementations are named serializable classes (selector captures included)
 * so accumulators survive snapshot/restore and cells survive graph-spec
 * construction (51).
 */
interface Aggregator<E, A, ACC : Serializable> : Serializable {
    fun empty(): ACC
    fun insert(acc: ACC, element: E): ACC
    fun retract(acc: ACC, element: E): ACC
    fun value(acc: ACC): A
}

object Aggregators {
    /** Live-element count. */
    fun <E> count(): Aggregator<E, Long, Long> = Count()

    /** Sum of a Long selector — Long, not Double: float sums are order-sensitive. */
    fun <E> sumOf(selector: (E) -> Long): Aggregator<E, Long, Long> = Sum(selector)

    /** Mean of a Long selector; one deterministic division at read time. */
    fun <E> avgOf(selector: (E) -> Long): Aggregator<E, Double, SumCount> = Avg(selector)

    data class SumCount(val sum: Long, val n: Long) : Serializable

    private class Count<E> : Aggregator<E, Long, Long> {
        override fun empty(): Long = 0L
        override fun insert(acc: Long, element: E): Long = acc + 1
        override fun retract(acc: Long, element: E): Long = acc - 1
        override fun value(acc: Long): Long = acc
    }

    private class Sum<E>(private val selector: (E) -> Long) : Aggregator<E, Long, Long> {
        override fun empty(): Long = 0L
        override fun insert(acc: Long, element: E): Long = acc + selector(element)
        override fun retract(acc: Long, element: E): Long = acc - selector(element)
        override fun value(acc: Long): Long = acc
    }

    private class Avg<E>(private val selector: (E) -> Long) : Aggregator<E, Double, SumCount> {
        override fun empty(): SumCount = SumCount(0, 0)
        override fun insert(acc: SumCount, element: E): SumCount =
            SumCount(acc.sum + selector(element), acc.n + 1)

        override fun retract(acc: SumCount, element: E): SumCount =
            SumCount(acc.sum - selector(element), acc.n - 1)

        override fun value(acc: SumCount): Double = acc.sum.toDouble() / acc.n
    }
}
