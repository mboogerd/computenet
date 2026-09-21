package civictech.demo.alignment

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.MapCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cell-level pins of the alignment dataflow (computenet-sigl0.1): the same four
 * cells [AlignmentPipeline.build] spawns, linked here by direct subscription so
 * each propagation is synchronous and every emission is observable in order.
 * Worked numbers are the feature's examples (computenet-sigl0 Design).
 */
class AlignmentPipelineTest {

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    private fun <K, V> fold(deltas: List<MapDelta<K, V>>): Map<K, V> {
        val m = LinkedHashMap<K, V>()
        deltas.forEach { d -> m.putAll(d.puts); d.removals.forEach { m.remove(it) } }
        return m
    }

    private fun near(want: Double, got: Double, what: String) =
        assertTrue(abs(want - got) < 1e-9, "$what: want $want, got $got")

    private val t = TopicId("t")
    private val a = IdeaKey(t, "a")

    /** ratings → stats → fusion ← weights, wired by direct subscription. */
    private inner class Rig {
        val ratings = KeyedSetCell<RatingKey, Rating>()
        val stats = GroupByCell(keyFn = { r: Rating -> r.key.ideaDimKey }, aggregator = RatingStatsAggregator())
        val weights = MapCell<DimKey, Double>()
        val fusion = WeightedFusionCell()
        val statsOut: List<MapDelta<IdeaDimKey, DimStats>> = collect(stats.outlet)
        val out: List<MapDelta<IdeaKey, Scored>> = collect(fusion.outlet)

        init {
            ratings.outlet.subscribe(Use.fixed(stats.inlet.call, PortRef.generate()))
            stats.outlet.subscribe(Use.fixed(fusion.stats.call, PortRef.generate()))
            weights.outlet.subscribe(Use.fixed(fusion.weights.call, PortRef.generate()))
        }

        fun weight(topic: TopicId, dim: String, w: Double) = weights.inlet.call.put(DimKey(topic, dim), w)
        fun rate(idea: IdeaKey, dim: String, who: String, v: Int) {
            val k = RatingKey(idea.topic, idea.idea, dim, who)
            ratings.inlet.call.put(k, Rating(k, v))
        }
        fun unrate(idea: IdeaKey, dim: String, who: String) =
            ratings.inlet.call.remove(RatingKey(idea.topic, idea.idea, dim, who))

        fun scored(): Map<IdeaKey, Scored> = fold(out)
    }

    /** The feature's topic: `impact` weighted 2.0, `effort` 1.0. */
    private fun rig() = Rig().apply { weight(t, "impact", 2.0); weight(t, "effort", 1.0) }

    @Test
    fun `one rating on one dimension scores that dimension alone`() {
        val r = rig()
        r.rate(a, "impact", "ann", 8)
        assertEquals(
            mapOf(a to Scored(8.0, mapOf("impact" to 8.0), mapOf("impact" to DimStats(1, 8.0, 0.0)), split = false)),
            r.scored(),
        )
    }

    @Test
    fun `a second rated dimension re-weights the score and its contributions sum to it`() {
        val r = rig()
        r.rate(a, "impact", "ann", 8)
        r.rate(a, "impact", "bob", 2)
        val before = r.scored().getValue(a)
        near(5.0, before.score, "impact only: mean")

        r.rate(a, "effort", "ann", 3)
        val s = r.scored().getValue(a)
        near((2.0 * 5.0 + 1.0 * 3.0) / 3.0, s.score, "score")
        near(2.0 * 5.0 / 3.0, s.contributions.getValue("impact"), "impact contribution")
        near(1.0 * 3.0 / 3.0, s.contributions.getValue("effort"), "effort contribution")
        near(s.score, s.contributions.values.sum(), "contributions sum")
        assertEquals(setOf("impact", "effort"), s.byDim.keys)
        assertTrue(before.contributions != s.contributions, "contributions must move: $before -> $s")
    }

    @Test
    fun `retracting the last rating removes the idea - absence, never a sentinel`() {
        val r = rig()
        r.rate(a, "impact", "ann", 8)
        r.rate(a, "effort", "ann", 3)
        r.unrate(a, "effort", "ann")
        near(8.0, r.scored().getValue(a).score, "back to impact alone")

        r.unrate(a, "impact", "ann")
        assertFalse(a in r.scored(), "unrated idea must be absent: ${r.scored()}")
        val last = r.out.last()
        assertEquals(setOf(a), last.removals, "the last emission removes the idea")
        assertTrue(last.puts.isEmpty(), "and puts nothing: $last")
    }

    @Test
    fun `a weight change re-emits only ideas with stats on that dimension`() {
        val r = rig()
        val u = TopicId("u")
        r.weight(u, "effort", 1.0)
        val b = IdeaKey(t, "b")        // impact only: no stats on effort
        val c = IdeaKey(t, "c")        // effort only: score is its mean under any weight
        val other = IdeaKey(u, "x")    // effort in ANOTHER topic
        r.rate(a, "impact", "ann", 8); r.rate(a, "impact", "bob", 2); r.rate(a, "effort", "ann", 3)
        r.rate(b, "impact", "ann", 6)
        r.rate(c, "effort", "bob", 4)
        r.rate(other, "effort", "ann", 9)
        val emittedBefore = r.out.size

        r.weight(t, "effort", 4.0)

        assertEquals(emittedBefore + 1, r.out.size, "one emission for one weight delta")
        val delta = r.out.last()
        assertEquals(setOf(a), delta.puts.keys, "only a moves: b has no effort stats, c's score is its mean, x is another topic")
        assertTrue(delta.removals.isEmpty())
        val s = delta.puts.getValue(a)
        near((2.0 * 5.0 + 4.0 * 3.0) / 6.0, s.score, "re-weighted score")
        near(2.0 * 5.0 / 6.0, s.contributions.getValue("impact"), "impact contribution")
        near(4.0 * 3.0 / 6.0, s.contributions.getValue("effort"), "effort contribution")
        near(s.score, s.contributions.values.sum(), "contributions sum")

        // and a reweight of a dimension no idea has stats on emits nothing at all
        r.weight(t, "reach", 3.0)
        assertEquals(emittedBefore + 1, r.out.size, "untouched dimension: no emission")
    }

    @Test
    fun `split flips at the sample-stdev threshold`() {
        val r = rig()
        r.rate(a, "impact", "ann", 5)
        r.rate(a, "impact", "bob", 7)
        val calm = r.scored().getValue(a)
        near(sqrt(2.0), calm.byDim.getValue("impact").stdev, "{5,7} stdev")
        assertFalse(calm.split, "{5,7} is not split")

        r.rate(a, "impact", "ann", 3)   // re-put: {3,7}
        val torn = r.scored().getValue(a)
        near(sqrt(8.0), torn.byDim.getValue("impact").stdev, "{3,7} stdev")
        assertEquals(2L, torn.byDim.getValue("impact").n, "a re-put replaces, never adds")
        assertTrue(torn.split, "{3,7} is split (stdev ${torn.byDim})")

        r.unrate(a, "impact", "bob")    // n = 1: never split, stdev 0
        val alone = r.scored().getValue(a)
        assertEquals(DimStats(1, 3.0, 0.0), alone.byDim.getValue("impact"))
        assertFalse(alone.split)
    }

    @Test
    fun `the same rating under a second tag is not a second rating`() {
        val r = rig()
        r.rate(a, "impact", "ann", 8)
        val k = RatingKey(t, "a", "impact", "ann")
        val statsEmitted = r.statsOut.size
        val fusionEmitted = r.out.size

        r.stats.inlet.call.propagate(SetDelta(adds = mapOf(Rating(k, 8) to setOf(Timestamp(UUID.randomUUID(), 1)))))

        assertEquals(statsEmitted, r.statsOut.size, "tag churn: no stats emission")
        assertEquals(fusionEmitted, r.out.size, "tag churn: no fusion emission")
        val s = assertNotNull(r.scored()[a])
        assertEquals(1L, s.byDim.getValue("impact").n, "n unchanged by a second tag")
        assertEquals(1L, fold(r.statsOut).getValue(k.ideaDimKey).n)
    }
}
