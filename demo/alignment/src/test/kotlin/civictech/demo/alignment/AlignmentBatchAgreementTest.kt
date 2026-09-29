package civictech.demo.alignment

import civictech.cell.Propagate
import civictech.cell.data.delta.MapDelta
import civictech.cell.graph.lookup
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Incremental == batch (computenet-sigl0.1, feature rule 4): the exact pipeline
 * the app wires ([AlignmentPipeline.build]) is driven through a seeded churn of
 * rate / unrate / re-weight / direction-change ops — the direction-change op
 * cycles a dimension uniformly among the OTHER two of VALUE, COST and FACTOR
 * (design contract 2026-09-22) — and after EVERY step the folded
 * `WeightedFusionCell` outlet must equal [Alignment.rankBatch] recomputed from
 * the write-side maps — null-score rows and has-cost/has-factor flips included
 * (computenet-k1d4g-D2..D4, generalised to FACTOR). This is the only place the
 * two implementations meet (computenet-sigl0-D7). A failing seed stays
 * failing — never swap it out.
 *
 * A second test pins the all-value case (k1d4g rule 1) against the v1 formula
 * itself, computed inline from the raw ratings, so a shared mistake in the
 * cell and [Alignment.rankBatch] cannot pass unseen.
 *
 * Measured cost (darwin/arm64, 2026-09-21): the whole 50-seed × 100-step run
 * takes about 0.4 s of test time (JUnit `time` on the one test case), so there
 * is no reason to thin the seeds.
 */
class AlignmentBatchAgreementTest {

    private val topics = (0 until 3).map { TopicId("t$it") }
    private val ideas = (0 until 4).map { "i$it" }
    private val dims = (0 until 3).map { "d$it" }
    private val participants = (0 until 4).map { "p$it" }

    @Test
    fun `the folded fusion outlet equals the batch reference after every step, seeds 0 until 50`() {
        var nullScoreRows = 0L
        var hasCostFlips = 0
        var hasFactorFlips = 0
        for (seed in 0 until 50) {
            val controller = SimulationController(seed.toLong())
            val host = ManagedHost(scheduler = controller.scheduler())
            val refs = AlignmentPipeline.build(host)

            val folded = LinkedHashMap<IdeaKey, Scored>()
            host.lookup(refs.fusion, WeightedFusionApi::class.java)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<IdeaKey, Scored>> {
                    override fun propagate(value: MapDelta<IdeaKey, Scored>) {
                        folded.putAll(value.puts)
                        value.removals.forEach { folded.remove(it) }
                    }
                }, PortRef.generate())
            )
            val ratingOps = host.lookup(refs.ratings)!!.inlet.call
            val weightOps = host.lookup(refs.weights)!!.inlet.call

            val ratings = mutableMapOf<RatingKey, Int>()
            val configs = mutableMapOf<DimKey, DimConfig>()
            // every dimension starts at the default weight and VALUE, as the app creates it
            for (topic in topics) for (dim in dims) {
                val d = DimKey(topic, dim)
                val c = DimConfig(1.0, Direction.VALUE)
                weightOps.put(d, c); configs[d] = c
            }
            controller.runToIdle()

            val rnd = Random(seed.toLong())
            repeat(100) { step ->
                val roll = rnd.nextInt(10)
                val op = when {
                    roll < 6 -> {
                        val k = RatingKey(
                            topics[rnd.nextInt(topics.size)], ideas[rnd.nextInt(ideas.size)],
                            dims[rnd.nextInt(dims.size)], participants[rnd.nextInt(participants.size)],
                        )
                        // continuous [1, 9] in thousandths, endpoints included
                        val v = RatingScale.MIN_MILLI + rnd.nextInt(RatingScale.MAX_MILLI - RatingScale.MIN_MILLI + 1)
                        ratingOps.put(k, Rating(k, v)); ratings[k] = v
                        "rate $k=$v‰"
                    }
                    roll < 8 -> {
                        // unrate a live rating when there is one; an unknown key is a no-op on both sides
                        val k = if (ratings.isEmpty()) RatingKey(topics[0], ideas[0], dims[0], participants[0])
                        else ratings.keys.sortedBy { it.toString() }[rnd.nextInt(ratings.size)]
                        ratingOps.remove(k); ratings.remove(k)
                        "unrate $k"
                    }
                    roll < 9 -> {
                        val d = DimKey(topics[rnd.nextInt(topics.size)], dims[rnd.nextInt(dims.size)])
                        val c = DimConfig(0.5 + rnd.nextDouble() * 3.5, configs.getValue(d).direction)
                        weightOps.put(d, c); configs[d] = c
                        "weight $d=$c"
                    }
                    else -> {
                        // flip one dimension's direction, picked uniformly from every OTHER
                        // direction (design contract 2026-09-22): all three of VALUE, COST and
                        // FACTOR stay reachable, and a topic's first/last COST or FACTOR flips
                        // its respective has-bit (k1d4g-D4, generalised to FACTOR)
                        val d = DimKey(topics[rnd.nextInt(topics.size)], dims[rnd.nextInt(dims.size)])
                        val old = configs.getValue(d)
                        val others = Direction.entries.filter { it != old.direction }
                        val c = old.copy(direction = others[rnd.nextInt(others.size)])
                        fun hasDir(dir: Direction) = configs.any { (k, v) -> k.topic == d.topic && v.direction == dir }
                        val hadCost = hasDir(Direction.COST)
                        val hadFactor = hasDir(Direction.FACTOR)
                        weightOps.put(d, c); configs[d] = c
                        if (hadCost != hasDir(Direction.COST)) hasCostFlips++
                        if (hadFactor != hasDir(Direction.FACTOR)) hasFactorFlips++
                        "direction $d=$c"
                    }
                }
                controller.runToIdle()
                val want = Alignment.rankBatch(ratings, configs)
                assertAgrees(want, folded, "seed=$seed step=$step ($op)")
                nullScoreRows += want.values.count { it.score == null }
            }
        }
        // the churn must actually reach the cases it exists to check
        assertTrue(nullScoreRows > 0, "no step ever produced a null-score row")
        assertTrue(hasCostFlips > 0, "no step ever flipped a topic's has-cost bit")
        assertTrue(hasFactorFlips > 0, "no step ever flipped a topic's has-factor bit")
        println("agreement coverage: nullScoreRows=$nullScoreRows hasCostFlips=$hasCostFlips hasFactorFlips=$hasFactorFlips")
    }

    /**
     * Rule 1 of computenet-k1d4g: with no COST dimension ever configured,
     * [Alignment.rankBatch] equals the v1 formula Σ w_d·mean_d / Σ w_d over the
     * idea's rated dims, computed here inline from the raw ratings map — a third
     * computation, sharing no code with `rankBatch` or the cell — after every
     * step of a seeded rate / unrate / reweight run.
     */
    @Test
    fun `with no cost dimension rankBatch equals the v1 weighted mean, seeds 0 until 50`() {
        for (seed in 0 until 50) {
            val rnd = Random(seed.toLong())
            val ratings = mutableMapOf<RatingKey, Int>()
            val weights = mutableMapOf<DimKey, Double>()
            for (topic in topics) for (dim in dims) weights[DimKey(topic, dim)] = 1.0
            repeat(100) { step ->
                val roll = rnd.nextInt(10)
                when {
                    roll < 6 -> {
                        val k = RatingKey(
                            topics[rnd.nextInt(topics.size)], ideas[rnd.nextInt(ideas.size)],
                            dims[rnd.nextInt(dims.size)], participants[rnd.nextInt(participants.size)],
                        )
                        ratings[k] = RatingScale.MIN_MILLI + rnd.nextInt(RatingScale.MAX_MILLI - RatingScale.MIN_MILLI + 1)
                    }
                    roll < 8 -> if (ratings.isNotEmpty()) {
                        ratings.remove(ratings.keys.sortedBy { it.toString() }[rnd.nextInt(ratings.size)])
                    }
                    else -> weights[DimKey(topics[rnd.nextInt(topics.size)], dims[rnd.nextInt(dims.size)])] =
                        0.5 + rnd.nextDouble() * 3.5
                }
                val where = "seed=$seed step=$step"

                // v1, inline: per idea, per rated dim, the plain mean (thousandths → scale); then the weighted mean
                val sums = HashMap<IdeaKey, HashMap<String, IntArray>>() // dim → [Σx, n]
                for ((k, v) in ratings) {
                    val acc = sums.getOrPut(IdeaKey(k.topic, k.idea)) { HashMap() }.getOrPut(k.dim) { IntArray(2) }
                    acc[0] += v; acc[1] += 1
                }
                val batch = Alignment.rankBatch(ratings, weights.mapValues { DimConfig(it.value, Direction.VALUE) })
                assertEquals(sums.keys, batch.keys, "$where: scored idea set")
                for ((idea, perDim) in sums) {
                    var num = 0.0
                    var den = 0.0
                    val weighted = HashMap<String, Double>()
                    for ((dim, acc) in perDim) {
                        val w = weights.getValue(DimKey(idea.topic, dim))
                        val wm = w * (acc[0] / 1000.0) / acc[1]
                        weighted[dim] = wm; num += wm; den += w
                    }
                    val v1 = num / den
                    val got = batch.getValue(idea)
                    fun near(want: Double, g: Double?, what: String) {
                        if (g == null || abs(want - g) >= 1e-9) fail("$where $idea $what: v1 $want, rankBatch $g")
                    }
                    near(v1, got.score, "score")
                    near(v1, got.value, "value")
                    assertEquals(null, got.cost, "$where $idea: no cost dim, cost null")
                    assertEquals(null, got.factor, "$where $idea: no factor dim, factor null")
                    assertEquals(weighted.keys, got.contributions.keys, "$where $idea contribution dims")
                    weighted.forEach { (d, wm) -> near(wm / den, got.contributions[d], "contribution[$d]") }
                }
            }
        }
    }

    /**
     * EXACT equality on every `Double?` the two implementations compute (score, value, cost,
     * factor, each contribution, each byDim mean/stdev) — the mirrored formula (design contract
     * 2026-09-22, "Bit-identical batch/incremental agreement is mandatory") is meant to produce
     * bit-identical results, and a 1e-9 tolerance here would let a mutation that reordered, say,
     * the factor product's terms pass unseen. `Double.NaN` cannot occur (the formulas never divide
     * by a possibly-zero sum without the `side.isEmpty()`/zero-weight guards), so `assertEquals` on
     * the boxed `Double?` is safe: two nulls compare equal, and two non-null values compare equal
     * only when bit-identical. The lone exception is "contributions sum to score", which is an
     * arithmetic-sum identity of already-exact-equal contributions, not a batch/incremental
     * agreement check, so it keeps its `1e-9` tolerance.
     */
    private fun assertAgrees(want: Map<IdeaKey, Scored>, got: Map<IdeaKey, Scored>, where: String) {
        assertEquals(want.keys, got.keys, "$where: scored idea set")
        for ((idea, w) in want) {
            val g = got.getValue(idea)
            assertEquals(w.score, g.score, "$where $idea score")
            assertEquals(w.value, g.value, "$where $idea value")
            assertEquals(w.cost, g.cost, "$where $idea cost")
            assertEquals(w.factor, g.factor, "$where $idea factor")
            assertEquals(w.contributions.keys, g.contributions.keys, "$where $idea contribution dims")
            w.contributions.forEach { (d, c) -> assertEquals(c, g.contributions.getValue(d), "$where $idea contribution[$d]") }
            assertEquals(w.byDim.keys, g.byDim.keys, "$where $idea byDim dims")
            w.byDim.forEach { (d, s) ->
                val gs = g.byDim.getValue(d)
                assertEquals(s.n, gs.n, "$where $idea n[$d]")
                assertEquals(s.mean, gs.mean, "$where $idea mean[$d]")
                assertEquals(s.stdev, gs.stdev, "$where $idea stdev[$d]")
            }
            assertEquals(w.split, g.split, "$where $idea split")
            g.score?.let { score ->
                assertTrue(abs(g.contributions.values.sum() - score) < 1e-9, "$where $idea contributions sum to score")
            }
        }
    }
}
