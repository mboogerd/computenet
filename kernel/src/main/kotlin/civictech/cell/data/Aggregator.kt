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

    /**
     * The first k rows under a declared multi-column [SortSpec] (KAGG-R-10):
     * `value` is the live rows in spec order (the spec's own directions encode
     * DESC — there is no implicit reversal as in [topK]), duplicates expanded,
     * truncated at k, and exactly the live rows when fewer than k are live (no
     * padding). Keeps the full support multiset like [topK], on the same
     * [Support] fold, with [spec] as the `TreeMap` comparator — so the
     * accumulator carries the spec through snapshot/restore (KAGG-R-15).
     *
     * [spec] must be total over the rows [selector] produces; a declared
     * tie-break that lets two distinct rows compare equal makes `insert` (and
     * `retract`) throw `IllegalStateException` instead of silently merging
     * their multiplicities (KAGG-R-12).
     */
    fun <E, R : Serializable> topKBy(k: Int, spec: SortSpec<R>, selector: (E) -> R): Aggregator<E, List<R>, TreeMap<R, Int>> =
        TopKBy(k, spec, selector)

    /** [topKBy] over the elements themselves (the element is the row). */
    fun <R : Serializable> topKBy(k: Int, spec: SortSpec<R>): Aggregator<R, List<R>, TreeMap<R, Int>> =
        TopKBy(k, spec, Identity.cast())

    /** Live group members as a set (M11.4); E must be Serializable. */
    fun <E : Serializable> collectToSet(): Aggregator<E, Set<E>, HashSet<E>> = Collect()

    /**
     * Count of distinct projected values among live elements (KAGG-R-16), on
     * the same support multiset as [minOf]/[maxOf]/[topK]: multiplicity per
     * value survives retraction of one of several elements sharing a
     * projection, so the reported count only drops when the last element for
     * a value is retracted. The `Comparable` bound is deliberate, not a
     * shortcut — a hash-based multiset would duplicate [Support]'s drop-at-zero
     * mechanic; a caller with a non-comparable projection maps it to a
     * comparable key first.
     */
    fun <E, V> countDistinct(selector: (E) -> V): Aggregator<E, Long, TreeMap<V, Int>>
            where V : Comparable<V>, V : Serializable = CountDistinct(selector)

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

    /**
     * Shared support-multiset fold — the one drop-at-zero multiset (KAGG-R-02);
     * mutates in place, functional signature kept. [comparator] `null` is the
     * natural order (`TreeMap()`, as before, for [minOf]/[maxOf]/[topK]/
     * [countDistinct]); a supplied comparator ([topKBy]'s [SortSpec]) orders the
     * map and is checked for totality on every insert/retract: a key that
     * compares 0 to the selected value but is not equal to it means the
     * comparator merged two distinct values, which is refused loudly.
     */
    private abstract class Support<E, V : Serializable, A>(
        private val selector: (E) -> V,
        private val comparator: Comparator<in V>? = null,
    ) : Aggregator<E, A, TreeMap<V, Int>> {
        override fun empty(): TreeMap<V, Int> = TreeMap(comparator)

        override fun insert(acc: TreeMap<V, Int>, element: E): TreeMap<V, Int> = acc.also {
            val v = selector(element)
            checkTotal(it, v)
            it.merge(v, 1, Int::plus)
        }

        override fun retract(acc: TreeMap<V, Int>, element: E): TreeMap<V, Int> = acc.also {
            val v = selector(element)
            checkTotal(it, v)
            val n = checkNotNull(it[v]) { "retract of untracked value $v" }
            if (n <= 1) it.remove(v) else it[v] = n - 1
        }

        private fun checkTotal(acc: TreeMap<V, Int>, v: V) {
            val cmp = comparator ?: return
            val held = acc.floorKey(v) ?: return
            check(cmp.compare(held, v) != 0 || held == v) {
                "sort spec is not total: distinct rows $held and $v compare equal (the tie-break must be unique per row)"
            }
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

    private class TopKBy<E, R : Serializable>(private val k: Int, spec: SortSpec<R>, selector: (E) -> R) :
        Support<E, R, List<R>>(selector, spec) {
        init {
            require(k >= 0) { "topKBy k must be non-negative, was $k" }
        }

        // ascending in the spec's order: its columns' directions already encode DESC
        override fun value(acc: TreeMap<R, Int>): List<R> {
            val out = ArrayList<R>(minOf(k, acc.size))
            for ((row, n) in acc) {
                if (out.size >= k) break
                repeat(n.coerceAtMost(k - out.size)) { out += row }
            }
            return out
        }
    }

    /** Serializable identity selector for [topKBy] over the elements themselves. */
    private object Identity : (Any?) -> Any?, Serializable {
        private fun readResolve(): Any = Identity
        override fun invoke(e: Any?): Any? = e

        @Suppress("UNCHECKED_CAST")
        fun <R> cast(): (R) -> R = this as (R) -> R
    }

    private class CountDistinct<E, V>(selector: (E) -> V) :
        Support<E, V, Long>(selector) where V : Comparable<V>, V : Serializable {
        override fun value(acc: TreeMap<V, Int>): Long = acc.size.toLong()
    }

    private class Collect<E : Serializable> : Aggregator<E, Set<E>, HashSet<E>> {
        override fun empty(): HashSet<E> = HashSet()
        override fun insert(acc: HashSet<E>, element: E): HashSet<E> = acc.also { it += element }
        override fun retract(acc: HashSet<E>, element: E): HashSet<E> = acc.also { it -= element }
        override fun value(acc: HashSet<E>): Set<E> = acc.toSet() // no aliasing: gating compares before/after
    }
}
