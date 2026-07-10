package civictech.cell.data

import java.io.Serializable
import java.util.TreeMap

/**
 * A grouped aggregate (M11.3): a deterministic function of group membership.
 * [value] may depend only on WHICH elements are live — never on insertion or
 * retraction order (22: convergence, not simultaneity). That is what makes
 * "incremental equals batch recompute" testable and per-peer recompute over
 * replicated inputs converge. Arrival-order aggregates (first/last/scan) are
 * excluded from the family by this rule. [value] must not return a view that
 * aliases the accumulator — effective-only gating compares before/after.
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

    /**
     * Smallest selected value (M11.4). Non-invertible: the accumulator is the
     * full support multiset (value → multiplicity) — needed even under set
     * semantics because distinct elements can share an extracted value, and
     * retraction of the current extremum must reshuffle without a re-scan.
     */
    fun <E, V> minOf(selector: (E) -> V): Aggregator<E, V, TreeMap<V, Int>>
            where V : Comparable<V>, V : Serializable = Extremum(selector, min = true)

    /** Largest selected value (M11.4); see [minOf] for the support-multiset rationale. */
    fun <E, V> maxOf(selector: (E) -> V): Aggregator<E, V, TreeMap<V, Int>>
            where V : Comparable<V>, V : Serializable = Extremum(selector, min = false)

    /**
     * The k largest selected values, descending, duplicates included (M11.4).
     * Keeps the full support — bounded-memory top-k is unsound under
     * retractions (an evicted value can become top again).
     */
    fun <E, V> topK(k: Int, selector: (E) -> V): Aggregator<E, List<V>, TreeMap<V, Int>>
            where V : Comparable<V>, V : Serializable = TopK(k, selector)

    /** Live group members as a set (M11.4); E must be Serializable. */
    fun <E : Serializable> collectToSet(): Aggregator<E, Set<E>, HashSet<E>> = Collect()

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

    /** Shared support-multiset fold; mutates in place, functional signature kept. */
    private abstract class Support<E, V, A>(private val selector: (E) -> V) :
        Aggregator<E, A, TreeMap<V, Int>> where V : Comparable<V>, V : Serializable {
        override fun empty(): TreeMap<V, Int> = TreeMap()

        override fun insert(acc: TreeMap<V, Int>, element: E): TreeMap<V, Int> =
            acc.also { it.merge(selector(element), 1, Int::plus) }

        override fun retract(acc: TreeMap<V, Int>, element: E): TreeMap<V, Int> = acc.also {
            val v = selector(element)
            val n = checkNotNull(it[v]) { "retract of untracked value $v" }
            if (n <= 1) it.remove(v) else it[v] = n - 1
        }
    }

    private class Extremum<E, V>(selector: (E) -> V, private val min: Boolean) :
        Support<E, V, V>(selector) where V : Comparable<V>, V : Serializable {
        override fun value(acc: TreeMap<V, Int>): V = if (min) acc.firstKey() else acc.lastKey()
    }

    private class TopK<E, V>(private val k: Int, selector: (E) -> V) :
        Support<E, V, List<V>>(selector) where V : Comparable<V>, V : Serializable {
        override fun value(acc: TreeMap<V, Int>): List<V> {
            val out = ArrayList<V>(k)
            for ((v, n) in acc.descendingMap()) {
                repeat(n.coerceAtMost(k - out.size)) { out += v }
                if (out.size >= k) break
            }
            return out
        }
    }

    private class Collect<E : Serializable> : Aggregator<E, Set<E>, HashSet<E>> {
        override fun empty(): HashSet<E> = HashSet()
        override fun insert(acc: HashSet<E>, element: E): HashSet<E> = acc.also { it += element }
        override fun retract(acc: HashSet<E>, element: E): HashSet<E> = acc.also { it -= element }
        override fun value(acc: HashSet<E>): Set<E> = acc.toSet() // no aliasing: gating compares before/after
    }
}
