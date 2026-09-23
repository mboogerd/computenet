package civictech.cell.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Bare-[Aggregator] level tests for `Aggregators.countDistinct` (KAGG-R-16,
 * KAGG-R-17, the countDistinct half of KAGG-R-18) and `Aggregators.topKBy`
 * (KAGG-R-10..KAGG-R-15, the topKBy half of KAGG-R-18). [GroupByCellTest]
 * covers the same aggregators hosted in a
 * [civictech.cell.data.op.GroupByCell] (emission gating, group death,
 * snapshot/restore).
 */
class AggregatorTest {

    // elements "a3x" -> group 'a', value 3, suffix distinguishes elements sharing a value
    private fun midVal(e: String) = e[1].toString().toLong()

    @Test
    fun `countDistinct tracks cardinality across shared-value retraction`() {
        val agg = Aggregators.countDistinct(::midVal)
        var acc = agg.empty()
        assertEquals(0L, agg.value(acc))

        acc = agg.insert(acc, "a3x") // value 3
        acc = agg.insert(acc, "a3y") // value 3, shares with a3x
        acc = agg.insert(acc, "a7z") // value 7
        assertEquals(2L, agg.value(acc)) // {3, 7}

        // one of two elements sharing a value retracts: cardinality unchanged
        acc = agg.retract(acc, "a3x")
        assertEquals(2L, agg.value(acc))

        // the last element for that value retracts: cardinality drops by one
        acc = agg.retract(acc, "a3y")
        assertEquals(1L, agg.value(acc))

        acc = agg.retract(acc, "a7z")
        assertEquals(0L, agg.value(acc)) // empty accumulator reads 0, not a thrown/undefined state
    }

    @Test
    fun `countDistinct throws on retract of an untracked value`() {
        val agg = Aggregators.countDistinct(::midVal)
        val acc = agg.empty()
        val ex = assertThrows(IllegalStateException::class.java) {
            agg.retract(acc, "a3x")
        }
        assertTrue(ex.message.orEmpty().contains("untracked"), "expected an untracked-value message, got: ${ex.message}")
    }

    @Test
    fun `countDistinct value equals distinct live projections at every quiescent point`() {
        val domain = listOf("a1x", "a1y", "a2z", "b1x", "b3y", "b3z", "c1x")
        for (seed in 0L until 200L) {
            val rnd = Random(seed)
            val agg = Aggregators.countDistinct(::midVal)
            var acc = agg.empty()
            val held = mutableListOf<String>()

            repeat(60) {
                val addBias = held.isEmpty() || rnd.nextInt(10) < 6
                if (addBias) {
                    val e = domain[rnd.nextInt(domain.size)]
                    acc = agg.insert(acc, e)
                    held += e
                } else {
                    val idx = rnd.nextInt(held.size)
                    val e = held.removeAt(idx)
                    acc = agg.retract(acc, e)
                }
                val expected = held.map(::midVal).toSet().size
                assertEquals(expected, agg.value(acc).toInt(), "diverged from batch on seed $seed")
            }
        }
    }
    // ---- topKBy (KAGG-R-10..15, 18) -------------------------------------------------

    private val staff = listOf(
        Emp("d", 100, "bob", 3),
        Emp("d", 120, "ann", 1),
        Emp("d", 100, "amy", 5),
        Emp("d", 100, "amy", 2),
        Emp("d", 90, "cid", 4),
        Emp("d", 120, "zed", 6),
        Emp("d", 80, "eve", 7),
    )

    private fun batchTopK(live: List<Emp>, k: Int) = live.sortedWith(salaryDescNameAsc()).take(k)

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs)
        else xs.indices.flatMap { i -> permutations(xs.take(i) + xs.drop(i + 1)).map { listOf(xs[i]) + it } }

    @Test
    fun `topKBy value is independent of insertion order (BS-10)`() {
        val five = staff.take(5) // two rows at salary=100 sharing name "amy"
        val expected = batchTopK(five, 3)
        assertEquals(listOf(1, 2, 5), expected.map { it.id })
        for (order in permutations(five)) {
            val agg = Aggregators.topKBy(3, salaryDescNameAsc())
            var acc = agg.empty()
            order.forEach { acc = agg.insert(acc, it) }
            assertEquals(expected, agg.value(acc), "insertion order ${order.map { it.id }}")
        }
    }

    @Test
    fun `topKBy incremental equals batch recompute at every quiescent point (BS-10)`() {
        for (seed in 0L until 200L) {
            val rnd = Random(seed)
            val k = 1 + rnd.nextInt(4)
            val agg = Aggregators.topKBy(k, salaryDescNameAsc())
            var acc = agg.empty()
            val held = mutableListOf<Emp>() // multiset: the same row may be live twice

            repeat(60) {
                if (held.isEmpty() || rnd.nextInt(10) < 6) {
                    val e = staff[rnd.nextInt(staff.size)]
                    acc = agg.insert(acc, e)
                    held += e
                } else {
                    acc = agg.retract(acc, held.removeAt(rnd.nextInt(held.size)))
                }
                assertEquals(batchTopK(held, k), agg.value(acc), "diverged from batch on seed $seed")
            }
        }
    }

    @Test
    fun `topKBy keeps the survivor of two column-identical rows and refills the slot (BS-12)`() {
        val agg = Aggregators.topKBy(2, salaryDescNameAsc())
        val amy2 = Emp("d", 100, "amy", 2)
        val amy5 = Emp("d", 100, "amy", 5)
        val bob = Emp("d", 100, "bob", 3)
        var acc = agg.empty()
        listOf(bob, amy5, amy2).forEach { acc = agg.insert(acc, it) }
        assertEquals(listOf(amy2, amy5), agg.value(acc))

        acc = agg.retract(acc, amy2)
        assertEquals(listOf(amy5, bob), agg.value(acc)) // survivor at its position, freed slot refilled
    }

    @Test
    fun `topKBy reports exactly the live rows below k, unpadded (BS-13)`() {
        val agg = Aggregators.topKBy(5, salaryDescNameAsc())
        var acc = agg.empty()
        val ann = staff[1]; val cid = staff[4]
        acc = agg.insert(acc, cid)
        acc = agg.insert(acc, ann)
        assertEquals(listOf(ann, cid), agg.value(acc))

        val extra = staff.filter { it != ann && it != cid }
        extra.forEach { acc = agg.insert(acc, it) } // 7 live, past k
        assertEquals(batchTopK(staff, 5), agg.value(acc))

        extra.forEach { acc = agg.retract(acc, it) } // back below k
        assertEquals(listOf(ann, cid), agg.value(acc))

        acc = agg.retract(acc, ann)
        acc = agg.retract(acc, cid)
        assertEquals(emptyList<Emp>(), agg.value(acc))
    }

    @Test
    fun `topKBy refuses a declared tie-break that merges distinct rows (BS-11)`() {
        val constantTie = SortSpec.by(SortSpec.SortKey.desc(Emp::salary), tieBreak = Emp::dept) // not unique
        val agg = Aggregators.topKBy(3, constantTie)
        var acc = agg.empty()
        acc = agg.insert(acc, Emp("d", 100, "amy", 2))
        acc = agg.insert(acc, Emp("d", 100, "amy", 2)) // the same row again is a multiplicity, not a clash
        val ex = assertThrows(IllegalStateException::class.java) {
            agg.insert(acc, Emp("d", 100, "bob", 3))
        }
        assertTrue(ex.message.orEmpty().contains("not total"), "got: ${ex.message}")
        // nor may a retraction of the other row decrement the held one
        assertThrows(IllegalStateException::class.java) { agg.retract(acc, Emp("d", 100, "bob", 3)) }
        assertEquals(listOf(Emp("d", 100, "amy", 2), Emp("d", 100, "amy", 2)), agg.value(acc))
    }

    @Test
    fun `topKBy throws on retract of a never-inserted row and never goes negative (BS-15)`() {
        val agg = Aggregators.topKBy(3, salaryDescNameAsc())
        var acc = agg.empty()
        val ex = assertThrows(IllegalStateException::class.java) { agg.retract(acc, staff[0]) }
        assertTrue(ex.message.orEmpty().contains("untracked"), "got: ${ex.message}")

        acc = agg.insert(acc, staff[1])
        assertThrows(IllegalStateException::class.java) { agg.retract(acc, staff[0]) }
        acc = agg.retract(acc, staff[1])
        assertThrows(IllegalStateException::class.java) { agg.retract(acc, staff[1]) }
        assertTrue(acc.isEmpty())
    }

    @Test
    fun `topKBy with a row selector projects elements before ordering`() {
        // elements are (row, tag) pairs; the aggregate orders the projected rows
        val agg = Aggregators.topKBy(2, salaryDescNameAsc(), Pair<Emp, String>::first)
        var acc = agg.empty()
        acc = agg.insert(acc, staff[0] to "x")
        acc = agg.insert(acc, staff[1] to "y")
        acc = agg.insert(acc, staff[4] to "z")
        assertEquals(listOf(staff[1], staff[0]), agg.value(acc))
    }

    @Test
    fun `topKBy accumulator round-trips with its spec and multiplicities (BS-14)`() {
        val agg = Aggregators.topKBy(2, salaryDescNameAsc())
        val amy2 = Emp("d", 100, "amy", 2)
        val bob = Emp("d", 100, "bob", 3)
        var acc = agg.empty()
        listOf(amy2, amy2, bob).forEach { acc = agg.insert(acc, it) }
        assertEquals(listOf(amy2, amy2), agg.value(acc))

        var restored = serialRoundTrip(acc)
        assertEquals(agg.value(acc), agg.value(restored))
        restored = agg.retract(restored, amy2) // multiplicity 2 survived: one copy remains
        assertEquals(listOf(amy2, bob), agg.value(restored))
        restored = agg.insert(restored, Emp("d", 130, "top", 9)) // the restored comparator still orders by spec
        assertEquals(listOf(Emp("d", 130, "top", 9), amy2), agg.value(restored))
    }
}
