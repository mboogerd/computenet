package civictech.cell.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Bare-[Aggregator] level tests for `Aggregators.countDistinct` (KAGG-R-16,
 * KAGG-R-17, the countDistinct half of KAGG-R-18). [GroupByCellTest] covers
 * the same aggregator hosted in a [civictech.cell.data.op.GroupByCell]
 * (emission gating, group death, snapshot/restore).
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
}
