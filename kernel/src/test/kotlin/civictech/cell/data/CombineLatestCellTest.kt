package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.view.MapView

class CombineLatestCellTest {

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    // the skillmatch "market" combine: supply beside demand, outer, never drops.
    private fun market(ref: CellRef = CellRef(UUID.randomUUID())) =
        CombineLatestCell<String, Long, Long, Pair<Long, Long>>(ref) { _, s, d -> (s ?: 0L) to (d ?: 0L) }

    private fun mapView(deltas: List<MapDelta<String, Pair<Long, Long>>>): Map<String, Pair<Long, Long>> {
        val view = MapView<String, Pair<Long, Long>>()
        deltas.forEach { view.apply(it) }
        return view.current()
    }

    @Test
    fun `outer - a key on one side only appears in output computed from that side`() {
        val cell = market()
        val out = collect(cell.outlet)

        cell.left.call.propagate(MapDelta(mapOf("python" to 1L), emptySet()))
        assertEquals(mapOf("python" to (1L to 0L)), out.single().puts) // supply-only, still emitted

        cell.right.call.propagate(MapDelta(mapOf("python" to 2L), emptySet()))
        assertEquals(mapOf("python" to (1L to 2L)), out[1].puts) // now both sides
    }

    @Test
    fun `outer - leaving one side updates against the surviving side, not a retract`() {
        val cell = market()
        cell.left.call.propagate(MapDelta(mapOf("python" to 1L), emptySet()))
        cell.right.call.propagate(MapDelta(mapOf("python" to 2L), emptySet()))
        val out = collect(cell.outlet)

        // left drops the key but right still holds it: this is an UPDATE, not a removal
        cell.left.call.propagate(MapDelta(emptyMap(), setOf("python")))
        assertEquals(mapOf("python" to (0L to 2L)), out.single().puts)
        assertTrue(out.single().removals.isEmpty())
    }

    @Test
    fun `group death - removal iff absent from both sides, no ghost keys`() {
        val cell = market()
        cell.left.call.propagate(MapDelta(mapOf("python" to 1L), emptySet()))
        cell.right.call.propagate(MapDelta(mapOf("python" to 2L), emptySet()))
        cell.left.call.propagate(MapDelta(emptyMap(), setOf("python")))
        val out = collect(cell.outlet)

        // right removes the last surviving side → absent from both → group death
        cell.right.call.propagate(MapDelta(emptyMap(), setOf("python")))
        assertEquals(setOf("python"), out.single().removals)
        assertTrue(out.single().puts.isEmpty())
        assertEquals(emptyMap<String, Pair<Long, Long>>(), mapView(out))
    }

    @Test
    fun `effective-only - a value-neutral input emits nothing, a changing one emits one put`() {
        // combine ignores the left value entirely, so a left change can be value-neutral
        val cell = CombineLatestCell<String, Long, Long, Long>() { _, _, d -> d ?: 0L }
        val out = collect(cell.outlet)

        cell.right.call.propagate(MapDelta(mapOf("k" to 5L), emptySet()))
        assertEquals(1, out.size)

        // left put that leaves combined R unchanged → no emission
        cell.left.call.propagate(MapDelta(mapOf("k" to 99L), emptySet()))
        assertEquals(1, out.size)

        // restating the same right value → no emission
        cell.right.call.propagate(MapDelta(mapOf("k" to 5L), emptySet()))
        assertEquals(1, out.size)

        // a genuine change → exactly one put
        cell.right.call.propagate(MapDelta(mapOf("k" to 6L), emptySet()))
        assertEquals(mapOf("k" to 6L), out[1].puts)
        assertEquals(2, out.size)
    }

    @Test
    fun `combine to null filters the key from output and retracts a previously emitted one`() {
        // qualification-style guard: only emit a key while the right side (required) is present and positive
        val cell = CombineLatestCell<String, Long, Long, Long>() { _, have, need ->
            if (need != null && need > 0) (have ?: 0L) else null
        }
        val out = collect(cell.outlet)

        // supply present but no demand → combine→null → not in output
        cell.left.call.propagate(MapDelta(mapOf("k" to 3L), emptySet()))
        assertTrue(out.isEmpty())

        // demand arrives → key enters
        cell.right.call.propagate(MapDelta(mapOf("k" to 2L), emptySet()))
        assertEquals(mapOf("k" to 3L), out.single().puts)

        // demand goes to zero → combine flips to null → retract (filtering, not both-sides-absent)
        cell.right.call.propagate(MapDelta(mapOf("k" to 0L), emptySet()))
        assertEquals(setOf("k"), out[1].removals)
    }

    @Test
    fun `wave - one input MapDelta touching several keys emits as one output delta`() {
        val cell = market()
        cell.right.call.propagate(MapDelta(mapOf("a" to 1L, "b" to 2L, "c" to 3L), emptySet()))
        val out = collect(cell.outlet)

        // one delta touching three keys → exactly one output MapDelta carrying all three
        cell.left.call.propagate(MapDelta(mapOf("a" to 10L, "b" to 20L), setOf("c")))
        assertEquals(1, out.size)
        assertEquals(mapOf("a" to (10L to 1L), "b" to (20L to 2L)), out.single().puts)
        // "c" left never existed and right still holds it → still (0,3), value-neutral, no key churn
        assertTrue(out.single().removals.isEmpty())
    }

    @Test
    fun `serves catch-up to late-linking subscribers as one delta-from-empty`() {
        val cell = market()
        val live = collect(cell.outlet) // subscribed before data: sees the deltas as they flow
        cell.left.call.propagate(MapDelta(mapOf("a" to 1L), emptySet()))
        cell.right.call.propagate(MapDelta(mapOf("a" to 2L, "b" to 5L), emptySet()))

        val late = MapCollector<String, Pair<Long, Long>>()
        cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<String, Pair<Long, Long>>>>)

        // late subscriber receives the current combined map as one delta-from-empty
        assertEquals(1, late.arrivals.size)
        assertTrue(late.arrivals.single().removals.isEmpty())
        // a late subscriber's MapView equals the live one
        assertEquals(mapView(live), mapView(late.arrivals))
        assertEquals(mapOf("a" to (1L to 2L), "b" to (0L to 5L)), mapView(late.arrivals))
    }

    @Test
    fun `snapshot-restore round-trips both latest maps and rebuilds emitted by recomputation`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = market(ref)
        cell.left.call.propagate(MapDelta(mapOf("a" to 1L, "b" to 7L), emptySet()))
        cell.right.call.propagate(MapDelta(mapOf("a" to 2L), emptySet()))

        // round-trip through real serialization, as migration does
        val restored = market(ref)
        restored.restore(roundTrip(cell.snapshot()))

        // rebuilt `emitted` is served to a fresh subscriber as delta-from-empty
        val late = MapCollector<String, Pair<Long, Long>>()
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<String, Pair<Long, Long>>>>)
        assertEquals(mapOf("a" to (1L to 2L), "b" to (7L to 0L)), mapView(late.arrivals))

        // continued operation against restored latest-value maps
        val out = collect(restored.outlet)
        restored.right.call.propagate(MapDelta(mapOf("b" to 4L), emptySet())) // b: right arrives → (7,4)
        assertEquals(mapOf("b" to (7L to 4L)), out.single().puts)
    }

    @Test
    fun `pipeline - incremental outer combine equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val supply = MapCell<String, Long>()
            val demand = MapCell<String, Long>()
            val cell = market()
            supply.outlet.linkTo(cell.left as LinkFrom<Propagate<MapDelta<String, Long>>>)
            demand.outlet.linkTo(cell.right as LinkFrom<Propagate<MapDelta<String, Long>>>)
            val out = collect(cell.outlet)

            val domain = listOf("python", "kotlin", "rust", "go", "java")
            val heldL = mutableMapOf<String, Long>()
            val heldR = mutableMapOf<String, Long>()
            repeat(120) {
                val toLeft = rnd.nextBoolean()
                val (writer, held) = if (toLeft) supply to heldL else demand to heldR
                val k = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || k !in held) {
                    val v = rnd.nextInt(5).toLong()
                    writer.inlet.call.put(k, v); held[k] = v
                } else {
                    writer.inlet.call.remove(k); held.remove(k)
                }
            }

            val batch = (heldL.keys + heldR.keys).associateWith { k -> (heldL[k] ?: 0L) to (heldR[k] ?: 0L) }
            assertEquals(batch, mapView(out), "outer combine diverged from batch on seed $seed")
        }
    }

    @Test
    fun `pipeline - combine to null filtering equals batch recompute on every seed`() {
        // combine keeps only keys where demand is present and positive (group-death via null)
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val supply = MapCell<String, Long>()
            val demand = MapCell<String, Long>()
            val cell = CombineLatestCell<String, Long, Long, Long>() { _, have, need ->
                if (need != null && need > 0) (have ?: 0L) else null
            }
            supply.outlet.linkTo(cell.left as LinkFrom<Propagate<MapDelta<String, Long>>>)
            demand.outlet.linkTo(cell.right as LinkFrom<Propagate<MapDelta<String, Long>>>)
            val out = collect(cell.outlet)

            val domain = listOf("python", "kotlin", "rust", "go", "java")
            val heldL = mutableMapOf<String, Long>()
            val heldR = mutableMapOf<String, Long>()
            repeat(120) {
                val toLeft = rnd.nextBoolean()
                val (writer, held) = if (toLeft) supply to heldL else demand to heldR
                val k = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || k !in held) {
                    val v = rnd.nextInt(4).toLong() // includes 0 → toggles the null filter
                    writer.inlet.call.put(k, v); held[k] = v
                } else {
                    writer.inlet.call.remove(k); held.remove(k)
                }
            }

            val batch = (heldL.keys + heldR.keys).mapNotNull { k ->
                val need = heldR[k]
                if (need != null && need > 0) k to (heldL[k] ?: 0L) else null
            }.toMap()
            val view = MapView<String, Long>().also { v -> out.forEach { v.apply(it) } }.current()
            assertEquals(batch, view, "filtering combine diverged from batch on seed $seed")
        }
    }

    private class MapCollector<K, V> {
        val arrivals = mutableListOf<MapDelta<K, V>>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<K, V>>>))

        init {
            inlet.serve(object : Propagate<MapDelta<K, V>> {
                override fun propagate(value: MapDelta<K, V>) {
                    arrivals += value
                }
            })
        }
    }

    companion object {
        fun roundTrip(state: Serializable): Serializable {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.ObjectOutputStream(bytes).use { it.writeObject(state) }
            return java.io.ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() as Serializable }
        }
    }
}
