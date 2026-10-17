package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NexmarkResultTest {
    private val columns = listOf("auction", "price")

    @Test
    fun `equal sets compare equal`() {
        val result = NexmarkResult(columns, setOf(listOf(1L, 20L), listOf(2L, 30L)))
        assertEquals(Comparison.Equal, ResultComparison.compare(result, result.copy()))
    }

    @Test
    fun `one-sided rows are reported exactly`() {
        val expected = NexmarkResult(columns, setOf(listOf(1L, 20L), listOf(2L, 30L)))
        val actual = NexmarkResult(columns, setOf(listOf(2L, 30L), listOf(3L, 40L)))

        assertEquals(
            Comparison.Differs(
                listOf(
                    RowDiff(listOf(1L, 20L), Side.EXPECTED_ONLY),
                    RowDiff(listOf(3L, 40L), Side.ACTUAL_ONLY),
                ),
            ),
            ResultComparison.compare(expected, actual),
        )
    }

    @Test
    fun `column mismatch is rejected`() {
        val expected = NexmarkResult(columns, emptySet())
        val actual = NexmarkResult(listOf("auction"), emptySet())
        assertThrows(IllegalArgumentException::class.java) { ResultComparison.compare(expected, actual) }
    }
}
