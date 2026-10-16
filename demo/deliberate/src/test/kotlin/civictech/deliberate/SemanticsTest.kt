package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.agora.semantics.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deliberate-specific tests for judge knowledge and model A. The catalog's
 * reference tables, properties, consensus and layer-set tests live in Agora's
 * `civictech.agora.semantics.SemanticsCatalogTest`.
 */
class SemanticsTest {

    @Test
    fun `a claim outside the judge's knowledge enters every layer and the consensus at one half`() {
        val all = LayerSet.of(SemanticsCatalog.IDS, headline = LayerSet.CONSENSUS)
        val values = all.evaluate(listOf(Judge.OUTSIDE_KNOWLEDGE), emptyList(), emptyList())
        values.forEach { assertEquals(0.5, it, 1e-12) }
        assertEquals(0.5, all.consensus(values), 1e-12)
    }

    private fun legacyTemperatureShares(credences: List<Double>, temperature: Double): List<Double> {
        val scores = credences.map { ln(it / (1 - it)) / temperature }
        val top = scores.max()
        val weights = scores.map { exp(it - top) }
        return weights.map { it / weights.sum() }
    }

    @Test
    fun `model A - complementary positions reproduce the temperature defect and now match the binary verdict`() {
        val probabilities = (1..99).map { it / 100.0 }
        val t1MaxDiff = probabilities.maxOf { p -> abs(legacyTemperatureShares(listOf(p, 1 - p), 1.0)[0] - p) }
        val t2MaxDiff = probabilities.maxOf { p -> abs(legacyTemperatureShares(listOf(p, 1 - p), 2.0)[0] - p) }
        assertEquals(0.150, t1MaxDiff, 1e-3)
        assertEquals(0.0, t2MaxDiff, 1e-12)

        probabilities.forEach { p ->
            val shares = Softmax.shares(listOf(p, 1 - p))
            assertEquals(p, shares[0], 1e-12, "yes share at p=$p")
            assertEquals(1 - p, shares[1], 1e-12, "no share at p=$p")
        }
    }

    @Test
    fun `model A - implausible positions leave a none-of-the-listed residual`() {
        val shares = Softmax.shares(listOf(0.1, 0.1, 0.1))
        assertEquals(listOf(0.1, 0.1, 0.1), shares)
        assertEquals(0.7, 1 - shares.sum(), 1e-12)
    }

    @Test
    fun `model A - first impressions are simply normalised instead of sharpened`() {
        val credences = listOf(0.15, 0.2, 0.7)
        assertEquals(
            listOf(0.064, 0.091, 0.845),
            legacyTemperatureShares(credences, 1.0).map { Math.round(it * 1_000) / 1_000.0 },
        )

        val shares = Softmax.shares(credences)
        assertEquals(credences.map { it / credences.sum() }, shares)
        assertTrue(shares.last() < credences.last(), "$shares")
    }

    @Test
    fun `model A - a rising credence raises its share and lowers every other`() {
        val before = Softmax.shares(listOf(0.8, 0.6, 0.2))
        val after = Softmax.shares(listOf(0.8, 0.7, 0.2))
        assertTrue(after[1] > before[1])
        assertTrue(after[0] < before[0] && after[2] < before[2])
    }

    @Test
    fun `model A - shares admit certain credences and map empty to empty`() {
        val shares = Softmax.shares(listOf(0.0, 1.0, 1.0, 0.5))
        assertTrue(shares.all { it.isFinite() && it >= 0 }, "$shares")
        assertEquals(1.0, shares.sum(), 1e-12)
        assertEquals(emptyList(), Softmax.shares(emptyList()))
        assertEquals(listOf(0.5, 0.5), Softmax.shares(listOf(0.5, 0.5)))
    }
}
