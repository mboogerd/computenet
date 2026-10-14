package civictech.bench.graphalytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidatorTest {

    @Test
    fun `rule mapping is exhaustive and follows the specification`() {
        assertEquals(ComparisonRule.ExactLong, ruleFor(Algorithm.BFS))
        assertEquals(ComparisonRule.ExactLong, ruleFor(Algorithm.CDLP))
        assertEquals(ComparisonRule.Equivalence, ruleFor(Algorithm.WCC))
        assertEquals(ComparisonRule.Epsilon, ruleFor(Algorithm.PR))
        assertEquals(ComparisonRule.Epsilon, ruleFor(Algorithm.LCC))
        assertEquals(ComparisonRule.Epsilon, ruleFor(Algorithm.SSSP))
    }

    @Test
    fun `epsilon accepts equality at the boundary and records the fixed epsilon`() {
        val expected = mapOf(1L to "0.15")
        val boundary = 0.15 + EPSILON * 0.15

        val result = validate(Algorithm.LCC, expected, mapOf(1L to boundary))

        val passed = assertInstanceOf(Validation.Passed::class.java, result)
        assertEquals(ComparisonRule.Epsilon, passed.rule)
        assertEquals(EPSILON, passed.epsilonUsed)
    }

    @Test
    fun `zero reference requires exact zero and infinity matches only infinity`() {
        assertTrue(
            validate(Algorithm.PR, mapOf(1L to "0.0"), mapOf(1L to 0.0)) is Validation.Passed,
        )
        assertTrue(
            validate(Algorithm.PR, mapOf(1L to "Infinity"), mapOf(1L to Double.POSITIVE_INFINITY))
                is Validation.Passed,
        )
        assertTrue(
            validate(Algorithm.PR, mapOf(1L to "Infinity"), mapOf(1L to Double.MAX_VALUE))
                is Validation.Failed,
        )
        assertTrue(
            validate(Algorithm.PR, mapOf(1L to "0.0"), mapOf(1L to 1.0e-12)) is Validation.Failed,
        )
    }

    @Test
    fun `a wider epsilon would accept a case that the fixed epsilon rejects`() {
        val expected = mapOf(1L to "0.15")
        val actual = mapOf(1L to 0.15 * (1.0 + 5.0e-4))

        val result = assertInstanceOf(
            Validation.Failed::class.java,
            validate(Algorithm.LCC, expected, actual),
        )

        assertEquals(EPSILON, result.epsilonUsed)
        assertEquals(listOf(VertexDiff(1L, 0.15, actual[1L])), result.differing)
    }

    @Test
    fun `failed validation reports every difference missing vertex and extra vertex`() {
        val result = assertInstanceOf(
            Validation.Failed::class.java,
            validate(
                Algorithm.BFS,
                expected = mapOf(1L to "1", 2L to "2", 3L to "3"),
                actual = mapOf(1L to 9L, 2L to 2L, 4L to 4L),
            ),
        )

        assertEquals(ComparisonRule.ExactLong, result.rule)
        assertEquals(listOf(VertexDiff(1L, 1L, 9L)), result.differing)
        assertEquals(setOf(3L), result.missing)
        assertEquals(setOf(4L), result.extra)
        assertEquals(null, result.epsilonUsed)
    }

    @Test
    fun `equivalence compares label partitions rather than label spellings`() {
        val expected = mapOf(1L to "10", 2L to "10", 3L to "20", 4L to "30")
        val renamed = mapOf(1L to 99L, 2L to 99L, 3L to 7L, 4L to 8L)
        val split = mapOf(1L to 99L, 2L to 7L, 3L to 8L, 4L to 9L)

        assertTrue(validate(Algorithm.WCC, expected, renamed) is Validation.Passed)
        val failed = assertInstanceOf(
            Validation.Failed::class.java,
            validate(Algorithm.WCC, expected, split),
        )
        assertEquals(setOf(1L, 2L), failed.differing.map { it.vertex }.toSet())
    }

    @Test
    fun `exact integer validation passes only identical long values`() {
        val passed = validate(Algorithm.BFS, mapOf(1L to "2"), mapOf(1L to 2L))
        assertTrue(passed is Validation.Passed)
        assertFalse(validate(Algorithm.BFS, mapOf(1L to "2"), mapOf(1L to 2.0)) is Validation.Passed)
    }
}
