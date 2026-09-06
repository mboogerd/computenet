package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.data.delta.SetDelta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins `SetCell.compactBelow` (computenet-9sm.4.1, decision 9sm.4-D1/D2): the
 * minimal safe discard — tags at or below a per-source frontier are dropped
 * from `dels` and the `adds` tags they cover, membership is unchanged by
 * construction, an absent source is bottom (`[KE3-30]`), and nothing is
 * recorded — no floor, no fence, no emission. `delivered`/`tagCounter` survive
 * compaction, and a later delta carrying a discarded tag is re-admitted as new
 * information (deliberate, load-bearing — feature computenet-9sm.6 must add a
 * fence before this seam is wired to a checkpoint).
 *
 * Every expected count below was hand-evaluated against the rule in the
 * bead's description and re-derived here rather than merely copied.
 */
class SetCellCompactBelowTest {

    @Suppress("UNCHECKED_CAST")
    private fun snapshotOf(cell: SetCell<String>): Map<String, Any> = cell.snapshot() as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun addsOf(cell: SetCell<String>): Map<String, Set<Timestamp>> =
        snapshotOf(cell)["adds"] as Map<String, Set<Timestamp>>

    @Suppress("UNCHECKED_CAST")
    private fun delsOf(cell: SetCell<String>): Map<String, Set<Timestamp>> =
        snapshotOf(cell)["dels"] as Map<String, Set<Timestamp>>

    @Suppress("UNCHECKED_CAST")
    private fun buffer(cell: SetCell<String>, into: MutableList<Invocation>) {
        cell.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<String>>>(into), PortRef.generate()))
    }

    @Test
    fun `drops covered pairs, keeps the live tag, membership unchanged`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)

        cell.inlet.call.add("x") // (s, 1)
        cell.inlet.call.add("x") // (s, 2)
        cell.inlet.call.add("x") // (s, 3)
        cell.inlet.call.remove("x") // dels[x] = {1,2,3}
        cell.inlet.call.add("x") // (s, 4), live

        @Suppress("UNCHECKED_CAST")
        val s = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("x").single().sourceId

        assertEquals(setOf("x"), cell.membership())

        val discarded1 = cell.compactBelow(TagFrontier(mapOf(s to 2L)))
        assertEquals(4, discarded1)
        assertEquals(setOf(3L, 4L), addsOf(cell).getValue("x").map { it.counter }.toSet())
        assertEquals(setOf(3L), delsOf(cell).getValue("x").map { it.counter }.toSet())
        assertEquals(setOf("x"), cell.membership())

        val discarded2 = cell.compactBelow(TagFrontier(mapOf(s to 10L)))
        assertEquals(2, discarded2)
        assertEquals(setOf(4L), addsOf(cell).getValue("x").map { it.counter }.toSet())
        assertTrue("x" !in delsOf(cell), "dels should have no key for x once its tombstone set empties")
        assertEquals(setOf("x"), cell.membership())
    }

    @Test
    fun `interlock KE3-30 an absent source is bottom`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)

        cell.inlet.call.add("x")
        cell.inlet.call.add("x")
        cell.inlet.call.add("x")
        cell.inlet.call.remove("x")
        cell.inlet.call.add("x")

        val addsBefore = addsOf(cell)
        val delsBefore = delsOf(cell)

        assertEquals(0, cell.compactBelow(TagFrontier(emptyMap())))
        assertEquals(addsBefore, addsOf(cell))
        assertEquals(delsBefore, delsOf(cell))

        assertEquals(0, cell.compactBelow(TagFrontier(mapOf(UUID.randomUUID() to 100L))))
        assertEquals(addsBefore, addsOf(cell))
        assertEquals(delsBefore, delsOf(cell))
    }

    @Test
    fun `tombstone without a matching add is discarded like any other`() {
        val cell = SetCell<String>()
        val o = UUID.randomUUID()
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        Invocation.of(
            propagate,
            arrayOf(SetDelta<String>(dels = mapOf("y" to setOf(Timestamp(o, 1))))),
            null,
        ).invoke(cell.deltaInlet.call)

        assertEquals(emptySet<String>(), cell.membership())

        val discarded = cell.compactBelow(TagFrontier(mapOf(o to 1L)))
        assertEquals(1, discarded)
        assertEquals(emptySet<String>(), cell.membership())
        assertTrue("y" !in addsOf(cell))
        assertTrue("y" !in delsOf(cell))
    }

    @Test
    fun `no emission, delivered frontier and counter untouched, and re-admission is real`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)
        var delivered = 0
        cell.onDeliver { _, _ -> delivered++ }

        cell.inlet.call.add("z") // the first mint on a fresh cell: (s, 1)
        cell.inlet.call.remove("z")

        @Suppress("UNCHECKED_CAST")
        val s = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("z").single().sourceId

        assertEquals(2, invocationBuffer.size)
        assertEquals(1, delivered)

        val discarded = cell.compactBelow(TagFrontier(mapOf(s to 1L)))
        assertEquals(2, discarded)
        assertEquals(2, invocationBuffer.size) // nothing emitted by compaction
        assertEquals(1, delivered) // untouched

        // the straggler: a delta re-asserting the discarded add-tag
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        Invocation.of(
            propagate,
            arrayOf(SetDelta<String>(adds = mapOf("z" to setOf(Timestamp(s, 1))))),
            null,
        ).invoke(cell.deltaInlet.call)

        assertEquals(setOf("z"), cell.membership()) // re-admitted
        assertEquals(3, invocationBuffer.size) // re-emission of the novel add
        assertEquals(1, delivered) // DeliveredFrontier.deliver(s, 1) returns null: prefix survived

        cell.inlet.call.add("w") // must mint (s, 2), not (s, 1): tagCounter survived compaction
        @Suppress("UNCHECKED_CAST")
        val lastDelta = invocationBuffer.last().args[0] as SetDelta<String>
        assertEquals(2L, lastDelta.adds.getValue("w").single().counter)
    }
}
