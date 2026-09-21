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
 * Worked numbers are the feature's examples (computenet-sigl0 Design); the
 * value ÷ cost pins are computenet-k1d4g-D2..D4's.
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

    private fun near(want: Double, got: Double?, what: String) =
        assertTrue(got != null && abs(want - got) < 1e-9, "$what: want $want, got $got")

    private val t = TopicId("t")
    private val a = IdeaKey(t, "a")

    /** ratings → stats → fusion ← weights, wired by direct subscription. */
    private inner class Rig {
        val ratings = KeyedSetCell<RatingKey, Rating>()
        val stats = GroupByCell(keyFn = { r: Rating -> r.key.ideaDimKey }, aggregator = RatingStatsAggregator())
        val weights = MapCell<DimKey, DimConfig>()
        val fusion = WeightedFusionCell()
        val statsOut: List<MapDelta<IdeaDimKey, DimStats>> = collect(stats.outlet)
        val out: List<MapDelta<IdeaKey, Scored>> = collect(fusion.outlet)

        init {
            ratings.outlet.subscribe(Use.fixed(stats.inlet.call, PortRef.generate()))
            stats.outlet.subscribe(Use.fixed(fusion.stats.call, PortRef.generate()))
            weights.outlet.subscribe(Use.fixed(fusion.weights.call, PortRef.generate()))
        }

        fun weight(topic: TopicId, dim: String, w: Double, direction: Direction = Direction.VALUE) =
            weights.inlet.call.put(DimKey(topic, dim), DimConfig(w, direction))
        fun unconfigure(topic: TopicId, dim: String) = weights.inlet.call.remove(DimKey(topic, dim))
        fun rate(idea: IdeaKey, dim: String, who: String, v: Number) {
            val k = RatingKey(idea.topic, idea.idea, dim, who)
            ratings.inlet.call.put(k, Rating(k, RatingScale.toMilli(v.toDouble())))
        }
        fun unrate(idea: IdeaKey, dim: String, who: String) =
            ratings.inlet.call.remove(RatingKey(idea.topic, idea.idea, dim, who))

        fun scored(): Map<IdeaKey, Scored> = fold(out)
    }

    /** The feature's topic: `impact` weighted 2.0, `effort` 1.0, both VALUE. */
    private fun rig() = Rig().apply { weight(t, "impact", 2.0); weight(t, "effort", 1.0) }

    @Test
    fun `one rating on one dimension scores that dimension alone`() {
        val r = rig()
        r.rate(a, "impact", "ann", 8)
        assertEquals(
            mapOf(
                a to Scored(
                    score = 8.0, value = 8.0, cost = null,
                    contributions = mapOf("impact" to 8.0),
                    byDim = mapOf("impact" to DimStats(1, 8.0, 0.0)),
                    split = false,
                ),
            ),
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
        near(s.score!!, s.contributions.values.sum(), "contributions sum")
        near(s.score!!, s.value, "no cost dim: value is the score")
        assertEquals(null, s.cost, "no cost dim: cost is null")
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
        near(s.score!!, s.contributions.values.sum(), "contributions sum")

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

        r.stats.inlet.call.propagate(SetDelta(adds = mapOf(Rating(k, 8000) to setOf(Timestamp(UUID.randomUUID(), 1)))))

        assertEquals(statsEmitted, r.statsOut.size, "tag churn: no stats emission")
        assertEquals(fusionEmitted, r.out.size, "tag churn: no fusion emission")
        val s = assertNotNull(r.scored()[a])
        assertEquals(1L, s.byDim.getValue("impact").n, "n unchanged by a second tag")
        assertEquals(1L, fold(r.statsOut).getValue(k.ideaDimKey).n)
    }

    // ── value ÷ cost (computenet-k1d4g-D2..D4) ──────────────────────────

    /** k1d4g-D2's topic: impact (VALUE, 2), ease (VALUE, 1), effort (COST, 1). */
    private fun costRig() = Rig().apply {
        weight(t, "impact", 2.0); weight(t, "ease", 1.0); weight(t, "effort", 1.0, Direction.COST)
    }

    @Test
    fun `value over cost - the D2 worked example`() {
        val r = costRig()
        r.rate(a, "impact", "ann", 8)
        r.rate(a, "ease", "ann", 5)
        r.rate(a, "effort", "ann", 4)
        val s = r.scored().getValue(a)
        near(7.0, s.value, "value (16+5)/3")
        near(4.0, s.cost, "cost")
        near(1.75, s.score, "score 7/4")
        near(16.0 / 3.0 / 4.0, s.contributions.getValue("impact"), "impact contribution")
        near(5.0 / 3.0 / 4.0, s.contributions.getValue("ease"), "ease contribution")
        assertEquals(setOf("impact", "ease"), s.contributions.keys, "contributions are over value dims only")
        near(s.score!!, s.contributions.values.sum(), "contributions sum to score")
        assertEquals(setOf("impact", "ease", "effort"), s.byDim.keys, "byDim carries every rated dim")

        // raising the cost rating lowers the score
        r.rate(a, "effort", "ann", 8)
        near(7.0 / 8.0, r.scored().getValue(a).score, "score 7/8")
    }

    @Test
    fun `a topic with a cost dim leaves an idea with no rated cost dim present but unscored`() {
        val r = costRig()
        r.rate(a, "impact", "ann", 8)
        r.rate(a, "ease", "ann", 5)
        val s = r.scored().getValue(a)
        assertEquals(null, s.score, "cost not rated yet: no score")
        near(7.0, s.value, "value")
        assertEquals(null, s.cost, "cost is the missing side")
        assertTrue(s.contributions.isEmpty(), "no contributions without a score: ${s.contributions}")
        assertEquals(mapOf("impact" to DimStats(1, 8.0, 0.0), "ease" to DimStats(1, 5.0, 0.0)), s.byDim)

        // same topic with effort redirected to VALUE: (16+5+4)/4
        r.rate(a, "effort", "ann", 4)
        r.weight(t, "effort", 1.0, Direction.VALUE)
        val v = r.scored().getValue(a)
        near(6.25, v.score, "all value: weighted mean")
        near(6.25, v.value, "value")
        assertEquals(null, v.cost)
        near(v.score!!, v.contributions.values.sum(), "contributions sum")
    }

    @Test
    fun `flipping a topic's has-cost bit re-emits every idea of that topic with stats and no other`() {
        val r = rig() // impact 2, effort 1, both VALUE
        val u = TopicId("u")
        r.weight(u, "impact", 1.0)
        val b = IdeaKey(t, "b")      // impact only: never has stats on the new cost dim
        val other = IdeaKey(u, "x")  // another topic
        r.rate(b, "impact", "ann", 8)
        r.rate(other, "impact", "ann", 6)
        near(8.0, r.scored().getValue(b).score, "value only, no cost dim yet")
        val emitted = r.out.size

        // first COST config in t, on a dim nobody has rated
        r.weight(t, "price", 1.0, Direction.COST)
        assertEquals(emitted + 1, r.out.size, "one emission for the flip")
        val on = r.out.last()
        assertEquals(setOf(b), on.puts.keys, "b re-emitted, x (topic u) untouched")
        assertTrue(on.removals.isEmpty(), "present, not removed: $on")
        assertEquals(null, on.puts.getValue(b).score, "now unscored: cost not rated yet")
        near(8.0, on.puts.getValue(b).value, "value kept")

        // a second COST config does not flip the bit: nothing to re-emit
        r.weight(t, "risk", 1.0, Direction.COST)
        assertEquals(emitted + 1, r.out.size, "no flip, no emission")
        r.unconfigure(t, "risk")
        assertEquals(emitted + 1, r.out.size, "still has a cost dim: no emission")

        // the last COST config redirected to VALUE flips it back
        r.weight(t, "price", 1.0, Direction.VALUE)
        val off = r.out.last()
        assertEquals(emitted + 2, r.out.size)
        assertEquals(setOf(b), off.puts.keys, "b re-emitted again, x untouched")
        near(8.0, off.puts.getValue(b).score, "scored again")

        // and the last COST config REMOVED flips it back too
        r.weight(t, "price", 1.0, Direction.COST)
        assertEquals(null, r.scored().getValue(b).score)
        r.unconfigure(t, "price")
        val removed = r.out.last()
        assertEquals(setOf(b), removed.puts.keys, "removal flip re-emits b, x untouched")
        near(8.0, removed.puts.getValue(b).score, "scored after the removal")
        near(6.0, r.scored().getValue(other).score, "x never moved")
    }

    @Test
    fun `redirecting the last cost dim to value scores an idea rated only on it`() {
        val r = rig()
        r.weight(t, "effort", 1.0, Direction.COST)
        r.rate(a, "effort", "ann", 3)
        r.rate(a, "effort", "bob", 5)
        val c = r.scored().getValue(a)
        assertEquals(null, c.score, "value not rated yet")
        assertEquals(null, c.value)
        near(4.0, c.cost, "cost")

        r.weight(t, "effort", 1.0, Direction.VALUE)
        val v = assertNotNull(r.scored()[a], "present after the flip")
        near(4.0, v.score, "its mean is the score: effort is a value dim now")
        near(4.0, v.value, "value")
        assertEquals(null, v.cost)
        assertEquals(mapOf("effort" to 4.0), v.contributions)
    }
    // ── continuous ratings (epic computenet-9y79n: floating point in [1, 9]) ──

    @Test
    fun `a rating rounds half-up to the nearest thousandth and renders without trailing zeros`() {
        assertEquals(1000, RatingScale.toMilli(1.0004))
        assertEquals(1001, RatingScale.toMilli(1.0006))
        assertEquals(9000, RatingScale.toMilli(8.9996))
        assertEquals(6370, RatingScale.toMilli(6.37))
        assertEquals("6.37", RatingScale.format(6370))
        assertEquals("8", RatingScale.format(8000))
    }

    @Test
    fun `non-integer ratings fold exactly, rounded to thousandths`() {
        val r = rig()
        r.rate(a, "impact", "ann", 6.37)
        r.rate(a, "impact", "bob", 2.5)
        r.rate(a, "impact", "cy", 1.0004)    // rounds to 1.000
        val s = r.scored().getValue(a).byDim.getValue("impact")
        assertEquals(3L, s.n)
        near((6.37 + 2.5 + 1.0) / 3.0, s.mean, "mean")
        near(sqrt(((6370.0 - 3290.0).let { it * it } + (2500.0 - 3290.0).let { it * it } + (1000.0 - 3290.0).let { it * it }) / 2.0) / 1000.0, s.stdev, "sample stdev")

        // insert/retract in another order lands on the identical value
        r.unrate(a, "impact", "bob")
        r.rate(a, "impact", "bob", 2.5)
        assertEquals(s, r.scored().getValue(a).byDim.getValue("impact"), "the fold is exact, not order-dependent")
    }

    @Test
    fun `split flips at stdev 2 on a continuous scale`() {
        // two ratings: stdev = |x - y| / sqrt 2, so the threshold is |x - y| = 2 sqrt 2 = 2.8284...
        val r = rig()
        r.rate(a, "impact", "ann", 3.0)
        r.rate(a, "impact", "bob", 5.828)   // gap 2.828: stdev 1.99970
        val calm = r.scored().getValue(a)
        assertTrue(calm.byDim.getValue("impact").stdev < 2.0, "${calm.byDim}")
        assertFalse(calm.split, "gap 2.828 is not split")

        r.rate(a, "impact", "bob", 5.829)   // gap 2.829: stdev 2.00041
        val torn = r.scored().getValue(a)
        assertTrue(torn.byDim.getValue("impact").stdev >= 2.0, "${torn.byDim}")
        assertTrue(torn.split, "gap 2.829 is split")
    }
}
