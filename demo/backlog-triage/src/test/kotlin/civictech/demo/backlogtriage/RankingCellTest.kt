package civictech.demo.backlogtriage

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.Propagate
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta

class RankingCellTest {

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    private fun tag() = Timestamp(UUID.randomUUID(), 0)

    private fun adds(vararg prefs: Pair<Pref, Timestamp>) =
        SetDelta(adds = prefs.associate { (p, t) -> p to setOf(t) })

    private fun dels(vararg prefs: Pair<Pref, Timestamp>) =
        SetDelta<Pref>(dels = prefs.associate { (p, t) -> p to setOf(t) })

    private fun fold(deltas: List<MapDelta<String, Double>>): Map<String, Double> {
        val m = mutableMapOf<String, Double>()
        deltas.forEach { d -> m.putAll(d.puts); d.removals.forEach { m.remove(it) } }
        return m
    }

    private fun p(w: String, l: String) = Pref("agent", w, l)

    @Test
    fun `rating cell output folds to exactly what the bare engine computes`() {
        val cell = RatingCell(BradleyTerry())
        val out = collect(cell.outlet)
        val t1 = tag(); val t2 = tag(); val t3 = tag()
        cell.inlet.call.propagate(adds(p("a", "b") to t1))
        cell.inlet.call.propagate(adds(p("c", "b") to t2, p("a", "c") to t3))

        val reference = BradleyTerry().apply { add("a", "b"); add("c", "b"); add("a", "c") }
        val want = reference.ratings()
        val got = fold(out)
        assertEquals(want.keys, got.keys)
        want.forEach { (item, r) -> assertTrue(abs(r - got.getValue(item)) < 1e-9, "$item: $want vs $got") }
    }

    @Test
    fun `rating cell folds tags - a second tag is not a second vote, retraction needs all tags gone`() {
        val cell = RatingCell(MeanOfSigns())
        val out = collect(cell.outlet)
        val t1 = tag(); val t2 = tag()

        cell.inlet.call.propagate(adds(p("a", "b") to t1))
        val emissionsAfterFirst = out.size
        cell.inlet.call.propagate(adds(p("a", "b") to t2))   // same pref, new tag
        assertEquals(emissionsAfterFirst, out.size, "tag union must not re-count the pref")
        assertEquals(mapOf("a" to 1.0, "b" to -1.0), fold(out))

        cell.inlet.call.propagate(dels(p("a", "b") to t1))   // one tag left → still live
        assertEquals(emissionsAfterFirst, out.size, "partial tag removal must not retract")
        cell.inlet.call.propagate(dels(p("a", "b") to t2))   // last tag → retract
        assertTrue(fold(out).isEmpty(), "fully-retracted pref must empty the ratings: ${fold(out)}")
    }

    @Test
    fun `rating cell late joiner catches up with current ratings`() {
        val cell = RatingCell(Elo())
        cell.inlet.call.propagate(adds(p("a", "b") to tag()))

        val collector = MapCollector()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(collector.inlet as LinkFrom<Propagate<MapDelta<String, Double>>>)
        assertEquals(1, collector.arrivals.size)
        assertEquals(mapOf("a" to 1016.0, "b" to 984.0), collector.arrivals.single().puts)
    }

    @Test
    fun `pairwise-local engines emit only the voted pair`() {
        for (engine in listOf<RatingEngine>(Elo(), TrueSkill(), Glicko(), WengLin())) {
            val cell = RatingCell(engine)
            val out = collect(cell.outlet)
            val votes = listOf(p("a", "b"), p("c", "d"), p("a", "c"), p("b", "d"), p("a", "d"))
            votes.forEach { cell.inlet.call.propagate(adds(it to tag())) }
            out.forEachIndexed { i, delta ->
                val pair = setOf(votes[i].winner, votes[i].loser)
                assertTrue(delta.puts.keys.all { it in pair },
                    "${engine::class.simpleName} vote $i touched ${delta.puts.keys}, expected ⊆ $pair")
                assertTrue(delta.removals.isEmpty())
            }
            assertEquals(votes.size, out.size, "every vote moves its own pair → one emission each")
        }
    }

    @Test
    fun `bradley-terry emissions are global - one vote can move keys beyond the pair`() {
        val cell = RatingCell(BradleyTerry())
        val out = collect(cell.outlet)
        cell.inlet.call.propagate(adds(p("a", "b") to tag()))
        cell.inlet.call.propagate(adds(p("b", "c") to tag()))
        cell.inlet.call.propagate(adds(p("c", "d") to tag()))   // couples back through b and a
        assertTrue(out.last().puts.keys.any { it !in setOf("c", "d") },
            "the global refit should move keys outside the voted pair: ${out.last().puts.keys}")
    }

    @Test
    fun `meta cell combines its source streams incrementally and effective-only`() {
        val cell = MetaRankCell(sources = listOf("x", "y"))
        val out = collect(cell.outlet)

        cell.inlets.getValue("x").call.propagate(MapDelta(mapOf("a" to 2.0, "b" to 1.0), emptySet()))
        cell.inlets.getValue("y").call.propagate(MapDelta(mapOf("a" to 1.0, "b" to 2.0), emptySet()))
        // opposite orderings → dead heat, same as Borda.combine directly
        assertEquals(mapOf("a" to 0.5, "b" to 0.5), fold(out))

        val emissions = out.size
        cell.inlets.getValue("y").call.propagate(MapDelta(mapOf("b" to 2.0), emptySet()))   // no effective change
        assertEquals(emissions, out.size, "unchanged combination must not emit")

        cell.inlets.getValue("y").call.propagate(MapDelta(mapOf("a" to 3.0), emptySet()))   // y now agrees with x
        assertEquals(mapOf("a" to 1.0, "b" to 0.0), fold(out))
    }

    @Test
    fun `meta cell drops an item that all sources dropped`() {
        val cell = MetaRankCell(sources = listOf("x"))
        val out = collect(cell.outlet)
        cell.inlets.getValue("x").call.propagate(MapDelta(mapOf("a" to 2.0, "b" to 1.0), emptySet()))
        cell.inlets.getValue("x").call.propagate(MapDelta(emptyMap(), setOf("b")))
        assertEquals(setOf("a"), fold(out).keys)
    }

    /** Handshake-linked collector — catch-up (onLinked) fires only on real links. */
    private class MapCollector(
        val arrivals: MutableList<MapDelta<String, Double>> = mutableListOf(),
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Double>>>())

        init {
            inlet.onEach { arrivals += it }
        }
    }
}
