package civictech.demo.tiering

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.data.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class FuseCellTest {

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    /** Handshake-linked collector — catch-up (onLinked) fires only on real links. */
    private class TieredCollector(
        val arrivals: MutableList<MapDelta<String, Tiered>> = mutableListOf(),
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Tiered>>>())

        init {
            inlet.onEach { arrivals += it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun linkCollector(cell: FuseCell): TieredCollector {
        val collector = TieredCollector()
        cell.outlet.linkTo(collector.inlet as LinkFrom<Propagate<MapDelta<String, Tiered>>>)
        return collector
    }

    private fun puts(vararg pairs: Pair<String, Double>) = MapDelta(pairs.toMap(), emptySet<String>())
    private fun removals(vararg keys: String) = MapDelta(emptyMap<String, Double>(), keys.toSet())

    @Test
    fun `fusion math and thresholds`() {
        // single-signal rule: each side alone
        assertEquals(Tiered(0.75, "A"), Tiering.fuse(4.5, null))
        assertEquals(Tiered(1.0, "S"), Tiering.fuse(null, 1.0))
        assertEquals(Tiered(0.0, "F"), Tiering.fuse(null, -1.0))
        assertNull(Tiering.fuse(null, null))
        // blend: 0.7·(6/6) + 0.3·((−1+1)/2) = 0.7 → A boundary
        assertEquals(Tiered(0.7, "A"), Tiering.fuse(6.0, -1.0))
        // threshold edges
        assertEquals("S", Tiering.tierOf(0.85))
        assertEquals("A", Tiering.tierOf(0.8499))
        assertEquals("F", Tiering.tierOf(0.0999))
    }

    @Test
    fun `outer semantics, effective-only emission, and both-sides-gone removal`() {
        val cell = FuseCell()
        val out = collect(cell.outlet)

        // left only → published from the single signal
        cell.left.call.propagate(puts("pizza" to 4.5))
        assertEquals(mapOf("pizza" to Tiered(0.75, "A")), out.single().puts)

        // right arrives → blended score, new emission
        cell.right.call.propagate(puts("pizza" to -1.0))
        assertEquals(mapOf("pizza" to Tiered(0.7 * 0.75, "C")), out[1].puts)

        // same value again → effective-only: nothing emitted
        cell.right.call.propagate(puts("pizza" to -1.0))
        assertEquals(2, out.size)

        // one side drops → falls back to the surviving signal
        cell.right.call.propagate(removals("pizza"))
        assertEquals(mapOf("pizza" to Tiered(0.75, "A")), out[2].puts)

        // both sides gone → key removal, not a zero put
        cell.left.call.propagate(removals("pizza"))
        assertEquals(setOf("pizza"), out[3].removals)
        assertTrue(out[3].puts.isEmpty())
    }

    @Test
    fun `late joiner catches up with the current fused state`() {
        val cell = FuseCell()
        cell.left.call.propagate(puts("pizza" to 6.0, "sushi" to 3.0))
        cell.right.call.propagate(puts("pizza" to 1.0))

        val late = linkCollector(cell)
        assertEquals(1, late.arrivals.size)
        assertEquals(
            mapOf("pizza" to Tiering.fuse(6.0, 1.0), "sushi" to Tiering.fuse(3.0, null)),
            late.arrivals.single().puts,
        )
    }

    @Test
    fun `snapshot and restore recompute the published view`() {
        val cell = FuseCell()
        cell.left.call.propagate(puts("pizza" to 4.5))
        cell.right.call.propagate(puts("pizza" to 0.5, "sushi" to -0.5))

        val twin = FuseCell()
        twin.restore(cell.snapshot())
        val fromTwin = linkCollector(twin)
        assertEquals(
            mapOf("pizza" to Tiering.fuse(4.5, 0.5), "sushi" to Tiering.fuse(null, -0.5)),
            fromTwin.arrivals.single().puts,
        )
    }
}
