package civictech.demo.alignment

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [PairwiseFit]'s properties (feature computenet-k6rrk, design k6rrk-D1): a from-scratch
 * Bradley–Terry fit with prior 0.5, EQUAL as half a win each way, sorted sweep order, and
 * geometric-mean normalization before the `v = 1 + 8·s/(1+s)` mapping to [1, 9] thousandths.
 */
class PairwiseFitTest {

    @Test
    fun `empty input yields an empty map`() {
        assertEquals(emptyMap(), PairwiseFit.ratings(emptyList()))
    }

    @Test
    fun `one judgement splits the pair around 5000, winner above`() {
        val ratings = PairwiseFit.ratings(listOf(Judgement("a", "b", Outcome.A)))
        val vA = ratings.getValue("a")
        val vB = ratings.getValue("b")
        assertTrue(vA > 5000, "winner should rate above 5000, was $vA")
        assertTrue(abs(vA + vB - 10000) <= 1, "v_a + v_b should be 10000 +/- 1, was ${vA + vB}")
        // Pinned observed value (the design's estimate was "~6.3 for the winner", unverified; the
        // actual PairwiseFit.ratings output for a single decisive judgement, computed 2026-09-22
        // both by an independent Python reimplementation and by this test, is 6444 milli).
        assertEquals(6444, vA, "winner's rating drifted from the pinned observed value")
    }

    @Test
    fun `a chain a beats b beats c and a beats c orders a over b over c, b at the middle`() {
        val ratings = PairwiseFit.ratings(
            listOf(
                Judgement("a", "b", Outcome.A),
                Judgement("b", "c", Outcome.A),
                Judgement("a", "c", Outcome.A),
            ),
        )
        val vA = ratings.getValue("a")
        val vB = ratings.getValue("b")
        val vC = ratings.getValue("c")
        assertTrue(vA > vB, "a should outrate b, was $vA vs $vB")
        assertTrue(vB > vC, "b should outrate c, was $vB vs $vC")
        assertTrue(abs(vB - 5000) <= 1, "b should sit at the chain's middle, was $vB")
    }

    @Test
    fun `every idea rates exactly 5000 when every judgement is EQUAL`() {
        val ratings = PairwiseFit.ratings(
            listOf(
                Judgement("a", "b", Outcome.EQUAL),
                Judgement("b", "c", Outcome.EQUAL),
                Judgement("a", "c", Outcome.EQUAL),
            ),
        )
        assertEquals(mapOf("a" to 5000, "b" to 5000, "c" to 5000), ratings)
    }

    @Test
    fun `repeating the same judgement pushes the winner further than a single judgement`() {
        val once = PairwiseFit.ratings(listOf(Judgement("a", "b", Outcome.A))).getValue("a")
        val five = PairwiseFit.ratings(
            List(5) { Judgement("a", "b", Outcome.A) },
        ).getValue("a")
        assertTrue(five > once, "five judgements should rate a higher than one, was $five vs $once")
    }

    @Test
    fun `a contradicted judgement (a beats b, then b beats a) leaves both at 5000`() {
        val ratings = PairwiseFit.ratings(
            listOf(
                Judgement("a", "b", Outcome.A),
                Judgement("a", "b", Outcome.B),
            ),
        )
        assertEquals(mapOf("a" to 5000, "b" to 5000), ratings)
    }

    @Test
    fun `judgement order does not affect the result`() {
        val judgements = listOf(
            Judgement("a", "b", Outcome.A),
            Judgement("b", "c", Outcome.A),
            Judgement("a", "c", Outcome.A),
            Judgement("c", "d", Outcome.EQUAL),
            Judgement("d", "a", Outcome.B),
        )
        val inOrder = PairwiseFit.ratings(judgements)
        val shuffled = PairwiseFit.ratings(judgements.shuffled(Random(42)))
        assertEquals(inOrder, shuffled)
    }

    @Test
    fun `an idea mentioned in no judgement is absent from the result`() {
        val ratings = PairwiseFit.ratings(listOf(Judgement("a", "b", Outcome.A)))
        assertTrue("c" !in ratings, "unmentioned idea should not appear, map was $ratings")
    }

    @Test
    fun `symmetric judgements (a equal b, both beat c) rate a and b exactly equal, above c`() {
        val ratings = PairwiseFit.ratings(
            listOf(
                Judgement("a", "b", Outcome.EQUAL),
                Judgement("a", "c", Outcome.A),
                Judgement("b", "c", Outcome.A),
            ),
        )
        val vA = ratings.getValue("a")
        val vB = ratings.getValue("b")
        val vC = ratings.getValue("c")
        assertEquals(vA, vB, "a and b are symmetric under the EQUAL judgement, should rate identically")
        assertTrue(vA > vC, "a/b should outrate c, was $vA vs $vC")
    }
}

private fun abs(v: Int): Int = if (v < 0) -v else v
