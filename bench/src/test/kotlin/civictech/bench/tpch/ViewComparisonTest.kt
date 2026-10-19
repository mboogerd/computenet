package civictech.bench.tpch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewComparisonTest {

    @Test
    fun `one unit difference is reported with its key and column`() {
        val expected = ViewResult(
            columns = listOf("sum_charge"),
            rows = mapOf(listOf<Any>("R", "F") to longArrayOf(100L)),
        )
        val actual = ViewResult(
            columns = listOf("sum_charge"),
            rows = mapOf(listOf<Any>("R", "F") to longArrayOf(101L)),
        )

        val comparison = ViewComparison.compare(expected, actual)
        assertTrue(comparison is Comparison.Differs)
        assertEquals(
            RowDiff(listOf<Any>("R", "F"), "sum_charge", 100L, 101L),
            (comparison as Comparison.Differs).rows.single(),
        )
    }

    @Test
    fun `equal values compare exactly and no tolerance is accepted`() {
        val view = ViewResult(listOf("value"), mapOf(emptyList<Any>() to longArrayOf(1L)))
        assertEquals(Comparison.Equal, ViewComparison.compare(view, view.copy(rows = mapOf(emptyList<Any>() to longArrayOf(1L)))))
    }
}
