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
 * rate / unrate / re-weight ops, and after EVERY step the folded
 * `WeightedFusionCell` outlet must equal [Alignment.rankBatch] recomputed from
 * the write-side maps. This is the only place the two implementations meet
 * (computenet-sigl0-D7). A failing seed stays failing — never swap it out.
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
            val weights = mutableMapOf<DimKey, Double>()
            // every dimension starts at the default weight, as the app creates it
            for (topic in topics) for (dim in dims) {
                val d = DimKey(topic, dim)
                weightOps.put(d, 1.0); weights[d] = 1.0
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
                        val v = 1 + rnd.nextInt(9)
                        ratingOps.put(k, Rating(k, v)); ratings[k] = v
                        "rate $k=$v"
                    }
                    roll < 8 -> {
                        // unrate a live rating when there is one; an unknown key is a no-op on both sides
                        val k = if (ratings.isEmpty()) RatingKey(topics[0], ideas[0], dims[0], participants[0])
                        else ratings.keys.sortedBy { it.toString() }[rnd.nextInt(ratings.size)]
                        ratingOps.remove(k); ratings.remove(k)
                        "unrate $k"
                    }
                    else -> {
                        val d = DimKey(topics[rnd.nextInt(topics.size)], dims[rnd.nextInt(dims.size)])
                        val w = 0.5 + rnd.nextDouble() * 3.5
                        weightOps.put(d, w); weights[d] = w
                        "weight $d=$w"
                    }
                }
                controller.runToIdle()
                assertAgrees(Alignment.rankBatch(ratings, weights), folded, "seed=$seed step=$step ($op)")
            }
        }
    }

    private fun assertAgrees(want: Map<IdeaKey, Scored>, got: Map<IdeaKey, Scored>, where: String) {
        assertEquals(want.keys, got.keys, "$where: scored idea set")
        for ((idea, w) in want) {
            val g = got.getValue(idea)
            fun near(a: Double, b: Double, what: String) {
                if (abs(a - b) >= 1e-9) fail("$where $idea $what: batch $a, incremental $b")
            }
            near(w.score, g.score, "score")
            assertEquals(w.contributions.keys, g.contributions.keys, "$where $idea contribution dims")
            w.contributions.forEach { (d, c) -> near(c, g.contributions.getValue(d), "contribution[$d]") }
            assertEquals(w.byDim.keys, g.byDim.keys, "$where $idea byDim dims")
            w.byDim.forEach { (d, s) ->
                val gs = g.byDim.getValue(d)
                assertEquals(s.n, gs.n, "$where $idea n[$d]")
                near(s.mean, gs.mean, "mean[$d]")
                near(s.stdev, gs.stdev, "stdev[$d]")
            }
            assertEquals(w.split, g.split, "$where $idea split")
            assertTrue(abs(g.contributions.values.sum() - g.score) < 1e-9, "$where $idea contributions sum to score")
        }
    }
}
