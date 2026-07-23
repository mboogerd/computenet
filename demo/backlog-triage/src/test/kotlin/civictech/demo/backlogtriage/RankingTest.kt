package civictech.demo.backlogtriage

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankingTest {

    private fun assertClose(expected: Double, actual: Double, eps: Double = 1e-6, msg: String = "") =
        assertTrue(abs(expected - actual) < eps, "$msg expected $expected, got $actual")

    // ── MeanOfSigns ──────────────────────────────────────────────────────

    @Test
    fun `mean of signs scores are (wins-losses) over comparisons`() {
        val m = MeanOfSigns()
        m.add("a", "b"); m.add("a", "b"); m.add("b", "a")
        assertClose(1.0 / 3, m.ratings().getValue("a"))
        assertClose(-1.0 / 3, m.ratings().getValue("b"))
        m.add("c", "a")   // a: 2w/2l → 0
        assertClose(0.0, m.ratings().getValue("a"))
        assertClose(1.0, m.ratings().getValue("c"))
    }

    @Test
    fun `mean of signs retraction is exact and empties cleanly`() {
        val m = MeanOfSigns()
        m.add("a", "b"); m.add("b", "c"); m.add("a", "c")
        m.retract("a", "c"); m.retract("b", "c")
        assertClose(1.0, m.ratings().getValue("a"))
        assertClose(-1.0, m.ratings().getValue("b"))
        assertTrue("c" !in m.ratings(), "fully retracted item must vanish")
        m.retract("a", "b")
        assertTrue(m.ratings().isEmpty())
    }

    // ── BradleyTerry ─────────────────────────────────────────────────────

    @Test
    fun `bradley-terry recovers the odds ratio of a two-item record`() {
        val bt = BradleyTerry(prior = 1e-9)   // negligible prior → pure ML fit
        repeat(3) { bt.add("a", "b") }
        bt.add("b", "a")
        val r = bt.ratings()
        // P(a beats b) = 3/4  →  s_a / s_b = 3
        assertClose(3.0, r.getValue("a") / r.getValue("b"), eps = 1e-3)
    }

    @Test
    fun `bradley-terry infers transitive strength through chains`() {
        val bt = BradleyTerry()
        repeat(2) { bt.add("a", "b") }
        repeat(2) { bt.add("b", "c") }
        val r = bt.ratings()   // a and c never met
        assertTrue(r.getValue("a") > r.getValue("b"), "$r")
        assertTrue(r.getValue("b") > r.getValue("c"), "$r")
    }

    @Test
    fun `bradley-terry weighs evidence where mean of signs cannot`() {
        // a: 1 win in 1 game; c: 3 wins in 3 games — mean scores both 1.0,
        // but under the phantom-prior the better-evidenced item is stronger
        val bt = BradleyTerry()
        bt.add("a", "b")
        bt.add("c", "d"); bt.add("c", "e"); bt.add("c", "f")
        val mean = MeanOfSigns().apply {
            add("a", "b"); add("c", "d"); add("c", "e"); add("c", "f")
        }
        assertClose(1.0, mean.ratings().getValue("a"))
        assertClose(1.0, mean.ratings().getValue("c"))
        val r = bt.ratings()
        assertTrue(r.getValue("c") > r.getValue("a"), "more evidence must outrank: $r")
    }

    @Test
    fun `bradley-terry retraction is exact`() {
        val reference = BradleyTerry().apply { add("a", "b") }
        val roundTrip = BradleyTerry().apply {
            add("a", "b"); add("c", "d"); add("c", "a")
            retract("c", "a"); retract("c", "d")
        }
        val want = reference.ratings()
        val got = roundTrip.ratings()
        assertEquals(want.keys, got.keys)
        want.forEach { (item, s) -> assertClose(s, got.getValue(item), eps = 1e-6, msg = item) }
    }

    @Test
    fun `bradley-terry keeps undefeated items finite and converges warm`() {
        val bt = BradleyTerry()
        repeat(5) { bt.add("a", "b") }
        val first = bt.ratings()
        assertTrue(first.getValue("a").isFinite() && first.getValue("a") > first.getValue("b"), "$first")
        bt.add("b", "a")   // warm-started refit after one delta
        val second = bt.ratings()
        assertTrue(second.getValue("a") > second.getValue("b"), "$second")
        assertTrue(second.getValue("a") / second.getValue("b")
                < first.getValue("a") / first.getValue("b"), "an upset must narrow the gap")
    }

    // ── Elo ──────────────────────────────────────────────────────────────

    @Test
    fun `elo transfers points from loser to winner, zero-sum`() {
        val elo = Elo(k = 32.0, base = 1000.0)
        elo.add("a", "b")
        val r = elo.ratings()
        assertClose(1016.0, r.getValue("a"))   // even game: k/2 changes hands
        assertClose(984.0, r.getValue("b"))
        elo.add("a", "c"); elo.add("b", "c")
        assertClose(3000.0, elo.ratings().values.sum(), msg = "zero-sum")
    }

    @Test
    fun `elo pays more for an upset than for an expected win`() {
        val elo = Elo()
        repeat(5) { elo.add("a", "b") }        // a is now clearly stronger
        val before = elo.ratings().getValue("b")
        elo.add("b", "a")                      // upset
        val upsetGain = elo.ratings().getValue("b") - before
        assertTrue(upsetGain > 16.0, "upset must move more than an even game (k/2): $upsetGain")
    }

    @Test
    fun `elo retraction is bounded and hides fully-retracted items`() {
        val elo = Elo(k = 32.0, base = 1000.0)
        elo.add("a", "b"); elo.add("a", "c")
        elo.retract("a", "b")
        val r = elo.ratings()
        assertTrue("b" !in r, "no live games → not rated")
        assertTrue("a" in r && "c" in r)
        // approximate inverse: residual per retraction is under k
        assertTrue(abs(r.getValue("a") - 1016.0) < 32.0, "$r")
    }

    @Test
    fun `elo is deterministic for a given arrival order`() {
        fun run() = Elo().apply {
            add("a", "b"); add("b", "c"); add("c", "a"); retract("b", "c"); add("a", "c")
        }.ratings()
        assertEquals(run(), run())
    }

    // ── TrueSkill ────────────────────────────────────────────────────────

    @Test
    fun `trueskill winner rises above the conservative baseline, loser sinks below`() {
        val ts = TrueSkill()
        ts.add("a", "b")
        val r = ts.ratings()   // baseline conservative rating is μ0 − 3σ0 = 0
        assertTrue(r.getValue("a") > 0, "$r")
        assertTrue(r.getValue("b") < 0, "$r")
        assertTrue(r.getValue("a") > r.getValue("b"), "$r")
    }

    @Test
    fun `trueskill weighs evidence - a proven record outranks an equal-looking newcomer`() {
        val ts = TrueSkill()
        repeat(3) { ts.add("a", "b") }   // 3 wins, σ shrunk three times
        ts.add("c", "d")                 // 1 win, still uncertain
        val r = ts.ratings()
        assertTrue(r.getValue("a") > r.getValue("c"), "more evidence must outrank: $r")
    }

    @Test
    fun `trueskill pays more for an upset than for an even first game`() {
        val fresh = TrueSkill()
        fresh.add("x", "y")
        val evenGain = fresh.ratings().getValue("x")   // gain from conservative 0

        val ts = TrueSkill()
        repeat(5) { ts.add("a", "b") }                 // a is clearly stronger
        val before = ts.ratings().getValue("b")
        ts.add("b", "a")                               // upset
        val upsetGain = ts.ratings().getValue("b") - before
        assertTrue(upsetGain > evenGain, "upset $upsetGain must beat even-game gain $evenGain")
    }

    @Test
    fun `trueskill retraction hides fully-retracted items and stays deterministic`() {
        val ts = TrueSkill()
        ts.add("a", "b"); ts.add("a", "c")
        ts.retract("a", "b")
        val r = ts.ratings()
        assertTrue("b" !in r, "no live games → not rated")
        assertTrue("a" in r && "c" in r)

        fun run() = TrueSkill().apply {
            add("a", "b"); add("b", "c"); add("c", "a"); retract("c", "a")
        }.ratings()
        assertEquals(run(), run())
    }

    // ── Glicko ───────────────────────────────────────────────────────────

    @Test
    fun `glicko orders winner above loser and rewards evidence`() {
        val g = Glicko()
        repeat(3) { g.add("a", "b") }
        g.add("c", "d")
        val r = g.ratings()
        assertTrue(r.getValue("a") > r.getValue("b"), "$r")
        // 3-0 with a shrunken deviation must outrank an equal-looking 1-0
        assertTrue(r.getValue("a") > r.getValue("c"), "more evidence must outrank: $r")
    }

    @Test
    fun `glicko retraction hides fully-retracted items`() {
        val g = Glicko()
        g.add("a", "b"); g.add("a", "c")
        g.retract("a", "b")
        val r = g.ratings()
        assertTrue("b" !in r && "a" in r && "c" in r, "$r")
    }

    // ── WengLin (online Bradley–Terry) ───────────────────────────────────

    @Test
    fun `weng-lin agrees with the global bradley-terry ordering on a chain`() {
        val online = WengLin()
        val global = BradleyTerry()
        repeat(2) { online.add("a", "b"); global.add("a", "b") }
        repeat(2) { online.add("b", "c"); global.add("b", "c") }
        fun order(r: Map<String, Double>) = r.entries.sortedByDescending { it.value }.map { it.key }
        assertEquals(order(global.ratings()), order(online.ratings()),
            "the online estimator should reproduce the global fit's ordering")
    }

    @Test
    fun `weng-lin rewards evidence and hides fully-retracted items`() {
        val wl = WengLin()
        repeat(3) { wl.add("a", "b") }
        wl.add("c", "d")
        assertTrue(wl.ratings().getValue("a") > wl.ratings().getValue("c"), "${wl.ratings()}")
        wl.retract("c", "d")
        assertTrue("c" !in wl.ratings() && "d" !in wl.ratings())
    }

    // ── Wilson ───────────────────────────────────────────────────────────

    @Test
    fun `wilson bound rewards evidence where raw win rate ties`() {
        val w = WilsonAggregator()
        fun value(wins: Int, losses: Int): Double {
            var acc = w.empty()
            repeat(wins) { acc = w.insert(acc, Contribution("i", "x", "y", +1)) }
            repeat(losses) { acc = w.insert(acc, Contribution("i", "x", "y", -1)) }
            return w.value(acc)
        }
        assertTrue(value(3, 0) > value(1, 0), "3-0 must outrank 1-0")
        assertTrue(value(1, 0) > value(1, 1))
        assertTrue(value(0, 2) < value(1, 1))
        assertClose(0.0, w.value(w.empty()))
    }

    @Test
    fun `wilson retraction is exact`() {
        val w = WilsonAggregator()
        val win = Contribution("i", "x", "y", +1)
        val loss = Contribution("i", "x", "y", -1)
        var acc = w.empty()
        acc = w.insert(acc, win)
        val after = w.value(acc)
        acc = w.insert(acc, loss)
        acc = w.retract(acc, loss)
        assertClose(after, w.value(acc))
    }

    // ── MetaRank ─────────────────────────────────────────────────────────

    private fun allEngines() = listOf(MeanOfSigns(), Elo(), BradleyTerry(), TrueSkill(), Glicko(), WengLin())

    @Test
    fun `meta scores unanimity as 1 and 0 at the extremes`() {
        val meta = MetaRank(allEngines())
        repeat(2) { meta.add("a", "b") }
        repeat(2) { meta.add("b", "c") }   // every delegate ranks a > b > c
        val r = meta.ratings()
        assertClose(1.0, r.getValue("a"))
        assertClose(0.5, r.getValue("b"))
        assertClose(0.0, r.getValue("c"))
    }

    @Test
    fun `meta averages conflicting delegate rankings`() {
        // two synthetic delegates with exactly opposite orderings → dead heat
        fun fixed(vararg scores: Pair<String, Double>) = object : RatingEngine {
            override fun add(winner: String, loser: String) {}
            override fun retract(winner: String, loser: String) {}
            override fun ratings() = scores.toMap()
        }
        val meta = MetaRank(listOf(fixed("a" to 2.0, "b" to 1.0), fixed("a" to 1.0, "b" to 2.0)))
        val r = meta.ratings()
        assertClose(0.5, r.getValue("a"))
        assertClose(0.5, r.getValue("b"))
    }

    @Test
    fun `meta gives tied delegate scores fractional ranks instead of breaking them`() {
        val meta = MetaRank(listOf(MeanOfSigns()))
        meta.add("a", "b"); meta.add("c", "d")   // mean: a == c (1.0), b == d (−1.0)
        val r = meta.ratings()
        assertClose(r.getValue("a"), r.getValue("c"))
        assertClose(r.getValue("b"), r.getValue("d"))
        assertTrue(r.getValue("a") > r.getValue("b"), "$r")
    }

    @Test
    fun `meta forwards retraction to its delegates`() {
        val meta = MetaRank(allEngines())
        meta.add("a", "b")
        meta.retract("a", "b")
        assertTrue(meta.ratings().isEmpty())
    }
}
