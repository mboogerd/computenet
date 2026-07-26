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
import civictech.cell.data.op.LookupJoinCell
import civictech.cell.data.view.MapView

class LookupJoinCellTest {

    // fact key: a (candidate, job) pair; dimension key: the job string it references.
    private data class CJ(val candidate: String, val job: String) : Serializable
    // enriched result: matched count beside the job's required count, and whether it qualifies.
    private data class Qual(val matched: Long, val need: Long, val qualified: Boolean) : Serializable

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    // the skillmatch qualification join: fk projects a pair to its job; combine is
    // left-outer (a missing required-row ⇒ need 0) and never drops.
    private fun qualification(ref: CellRef = CellRef(UUID.randomUUID())) =
        LookupJoinCell<CJ, Long, String, Long, Qual>(ref, fk = { it.job }) { _, matched, need ->
            Qual(matched, need ?: 0L, need != null && matched == need)
        }

    private fun mapView(deltas: List<MapDelta<CJ, Qual>>): Map<CJ, Qual> {
        val view = MapView<CJ, Qual>()
        deltas.forEach { view.apply(it) }
        return view.current()
    }

    @Test
    fun `dimension change re-emits every referencing fact as one wave-grouped delta`() {
        val cell = qualification()
        // two facts share the FK "backend"; one references "frontend"
        cell.fact.call.propagate(
            MapDelta(mapOf(CJ("ada", "backend") to 2L, CJ("bob", "backend") to 1L, CJ("cyd", "frontend") to 3L), emptySet())
        )
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L, "frontend" to 3L), emptySet()))
        val out = collect(cell.outlet)

        // one dimension row changes → all facts under that FK re-emit, as ONE delta
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 3L), emptySet()))
        assertEquals(1, out.size, "a single dimension change must fan out to exactly one output delta")
        assertEquals(
            mapOf(
                CJ("ada", "backend") to Qual(2L, 3L, false), // was 2==2 qual, now 2!=3
                CJ("bob", "backend") to Qual(1L, 3L, false),
            ),
            out.single().puts,
        )
        // the frontend fact is untouched by a backend dimension change
        assertTrue(CJ("cyd", "frontend") !in out.single().puts)
        assertTrue(out.single().removals.isEmpty())
    }

    @Test
    fun `left-outer - a fact with no matching dimension row still emits with D null`() {
        val cell = qualification()
        val out = collect(cell.outlet)

        // fact arrives before any dimension row: D = null → need defaults to 0, still emitted
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L), emptySet()))
        assertEquals(mapOf(CJ("ada", "backend") to Qual(2L, 0L, false)), out.single().puts)

        // dimension row arrives → the referencing fact re-emits enriched
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L), emptySet()))
        assertEquals(mapOf(CJ("ada", "backend") to Qual(2L, 2L, true)), out[1].puts)
    }

    @Test
    fun `combine to null filters a fact out and retracts a previously emitted one`() {
        // guarded combine: only emit while the job has a positive required count
        val cell = LookupJoinCell<CJ, Long, String, Long, Long>(fk = { it.job }) { _, matched, need ->
            if (need != null && need > 0) matched else null
        }
        val out = collect(cell.outlet)

        // fact present but no required row → combine→null → not in output
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L), emptySet()))
        assertTrue(out.isEmpty())

        // required row arrives → fact enters
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 3L), emptySet()))
        assertEquals(mapOf(CJ("ada", "backend") to 2L), out.single().puts)

        // required goes to zero → combine flips to null → retract (filtering)
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 0L), emptySet()))
        assertEquals(setOf(CJ("ada", "backend")), out[1].removals)
        assertTrue(out[1].puts.isEmpty())
    }

    @Test
    fun `fact put indexes and emits, removal de-indexes and removes with no stale reverse-index entry`() {
        val cell = qualification()
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L), emptySet()))
        val out = collect(cell.outlet)

        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L), emptySet()))
        assertEquals(mapOf(CJ("ada", "backend") to Qual(2L, 2L, true)), out.single().puts)

        // removal drops the only fact under "backend" → output removal
        cell.fact.call.propagate(MapDelta(emptyMap(), setOf(CJ("ada", "backend"))))
        assertEquals(setOf(CJ("ada", "backend")), out[1].removals)

        // reverse index no longer holds "backend": a later dimension change emits nothing
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 5L), emptySet()))
        assertEquals(2, out.size, "a dimension change with no referencing facts must emit nothing")
    }

    @Test
    fun `dimension removal re-emits referencing facts per the left-outer rule with D null`() {
        val cell = qualification()
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L, CJ("bob", "backend") to 2L), emptySet()))
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L), emptySet()))
        val out = collect(cell.outlet)

        // the job's required row is removed → both referencing facts re-emit with need 0
        cell.dimension.call.propagate(MapDelta(emptyMap(), setOf("backend")))
        assertEquals(1, out.size)
        assertEquals(
            mapOf(CJ("ada", "backend") to Qual(2L, 0L, false), CJ("bob", "backend") to Qual(2L, 0L, false)),
            out.single().puts,
        )
    }

    @Test
    fun `effective-only - a change leaving a fact's R unchanged emits nothing`() {
        // combine ignores the required value entirely, so a dimension change is value-neutral
        val cell = LookupJoinCell<CJ, Long, String, Long, Long>(fk = { it.job }) { _, matched, _ -> matched }
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L), emptySet()))
        val out = collect(cell.outlet)

        // a dimension put that leaves R unchanged → no emission
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 9L), emptySet()))
        assertTrue(out.isEmpty())

        // a genuine fact change → exactly one put
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 3L), emptySet()))
        assertEquals(mapOf(CJ("ada", "backend") to 3L), out.single().puts)
    }

    @Test
    fun `wave - one dimension delta touching several jobs emits as one output delta`() {
        val cell = qualification()
        cell.fact.call.propagate(
            MapDelta(mapOf(CJ("ada", "backend") to 2L, CJ("bob", "frontend") to 1L, CJ("cyd", "data") to 4L), emptySet())
        )
        val out = collect(cell.outlet)

        // one delta touching three jobs → one output MapDelta carrying all three referencing facts
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L, "frontend" to 1L, "data" to 4L), emptySet()))
        assertEquals(1, out.size)
        assertEquals(
            mapOf(
                CJ("ada", "backend") to Qual(2L, 2L, true),
                CJ("bob", "frontend") to Qual(1L, 1L, true),
                CJ("cyd", "data") to Qual(4L, 4L, true),
            ),
            out.single().puts,
        )
    }

    @Test
    fun `serves catch-up to late-linking subscribers as one delta-from-empty`() {
        val cell = qualification()
        val live = collect(cell.outlet) // subscribed before data: sees deltas as they flow
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L, CJ("bob", "backend") to 1L), emptySet()))
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L), emptySet()))

        val late = MapCollector<CJ, Qual>()
        cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<CJ, Qual>>>)

        // late subscriber receives the current enriched map as one delta-from-empty
        assertEquals(1, late.arrivals.size)
        assertTrue(late.arrivals.single().removals.isEmpty())
        // a late subscriber's MapView equals the live one
        assertEquals(mapView(live), mapView(late.arrivals))
        assertEquals(
            mapOf(CJ("ada", "backend") to Qual(2L, 2L, true), CJ("bob", "backend") to Qual(1L, 2L, false)),
            mapView(late.arrivals),
        )
    }

    @Test
    fun `snapshot-restore round-trips facts, dims and rebuilds the reverse index`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = qualification(ref)
        cell.fact.call.propagate(MapDelta(mapOf(CJ("ada", "backend") to 2L, CJ("bob", "backend") to 1L), emptySet()))
        cell.dimension.call.propagate(MapDelta(mapOf("backend" to 2L), emptySet()))

        // round-trip through real serialization, as migration does
        val restored = qualification(ref)
        restored.restore(roundTrip(cell.snapshot()))

        // rebuilt enriched map is served to a fresh subscriber as delta-from-empty
        val late = MapCollector<CJ, Qual>()
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<CJ, Qual>>>)
        assertEquals(
            mapOf(CJ("ada", "backend") to Qual(2L, 2L, true), CJ("bob", "backend") to Qual(1L, 2L, false)),
            mapView(late.arrivals),
        )

        // the rebuilt reverse index still fans a dimension change out to both facts
        val out = collect(restored.outlet)
        restored.dimension.call.propagate(MapDelta(mapOf("backend" to 1L), emptySet()))
        assertEquals(
            mapOf(CJ("ada", "backend") to Qual(2L, 1L, false), CJ("bob", "backend") to Qual(1L, 1L, true)),
            out.single().puts,
        )
    }

    @Test
    fun `pipeline - incremental left-outer join equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val factSrc = MapCell<CJ, Long>()
            val dimSrc = MapCell<String, Long>()
            val cell = qualification()
            factSrc.outlet.linkTo(cell.fact as LinkFrom<Propagate<MapDelta<CJ, Long>>>)
            dimSrc.outlet.linkTo(cell.dimension as LinkFrom<Propagate<MapDelta<String, Long>>>)
            val out = collect(cell.outlet)

            // small job domain ⇒ many facts share a FK ⇒ shared-FK dimension churn
            val jobs = listOf("backend", "frontend", "data")
            val candidates = listOf("ada", "bob", "cyd", "dee")
            val pairs = candidates.flatMap { c -> jobs.map { j -> CJ(c, j) } }
            val heldFacts = mutableMapOf<CJ, Long>()
            val heldDims = mutableMapOf<String, Long>()
            repeat(200) {
                if (rnd.nextBoolean()) {
                    val k = pairs[rnd.nextInt(pairs.size)]
                    if (rnd.nextInt(10) < 6 || k !in heldFacts) {
                        val v = rnd.nextInt(5).toLong(); factSrc.inlet.call.put(k, v); heldFacts[k] = v
                    } else {
                        factSrc.inlet.call.remove(k); heldFacts.remove(k)
                    }
                } else {
                    val j = jobs[rnd.nextInt(jobs.size)]
                    if (rnd.nextInt(10) < 6 || j !in heldDims) {
                        val v = rnd.nextInt(5).toLong(); dimSrc.inlet.call.put(j, v); heldDims[j] = v
                    } else {
                        dimSrc.inlet.call.remove(j); heldDims.remove(j)
                    }
                }
            }

            // batch: for each live fact, look up the FINAL dimension map and combine
            val batch = heldFacts.mapValues { (k, matched) ->
                val need = heldDims[k.job]
                Qual(matched, need ?: 0L, need != null && matched == need)
            }
            assertEquals(batch, mapView(out), "left-outer join diverged from batch on seed $seed")
        }
    }

    @Test
    fun `pipeline - null-filtering join equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val factSrc = MapCell<CJ, Long>()
            val dimSrc = MapCell<String, Long>()
            // guard: keep a fact only while its job has a positive required count
            val cell = LookupJoinCell<CJ, Long, String, Long, Long>(fk = { it.job }) { _, matched, need ->
                if (need != null && need > 0) matched else null
            }
            factSrc.outlet.linkTo(cell.fact as LinkFrom<Propagate<MapDelta<CJ, Long>>>)
            dimSrc.outlet.linkTo(cell.dimension as LinkFrom<Propagate<MapDelta<String, Long>>>)
            val out = collect(cell.outlet)

            val jobs = listOf("backend", "frontend", "data")
            val candidates = listOf("ada", "bob", "cyd", "dee")
            val pairs = candidates.flatMap { c -> jobs.map { j -> CJ(c, j) } }
            val heldFacts = mutableMapOf<CJ, Long>()
            val heldDims = mutableMapOf<String, Long>()
            repeat(200) {
                if (rnd.nextBoolean()) {
                    val k = pairs[rnd.nextInt(pairs.size)]
                    if (rnd.nextInt(10) < 6 || k !in heldFacts) {
                        val v = rnd.nextInt(5).toLong(); factSrc.inlet.call.put(k, v); heldFacts[k] = v
                    } else {
                        factSrc.inlet.call.remove(k); heldFacts.remove(k)
                    }
                } else {
                    val j = jobs[rnd.nextInt(jobs.size)]
                    if (rnd.nextInt(10) < 6 || j !in heldDims) {
                        val v = rnd.nextInt(4).toLong(); dimSrc.inlet.call.put(j, v); heldDims[j] = v // 0 toggles filter
                    } else {
                        dimSrc.inlet.call.remove(j); heldDims.remove(j)
                    }
                }
            }

            val batch = heldFacts.mapNotNull { (k, matched) ->
                val need = heldDims[k.job]
                if (need != null && need > 0) k to matched else null
            }.toMap()
            val view = MapView<CJ, Long>().also { v -> out.forEach { v.apply(it) } }.current()
            assertEquals(batch, view, "null-filtering join diverged from batch on seed $seed")
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
