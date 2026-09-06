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
 * Pins `SetCell.compactBelow` (computenet-9sm.4.1, decision 9sm.4-D1/D2, as
 * amended by computenet-v2ka): the minimal safe discard — a `dels` **entry**
 * every one of whose tags is at or below a per-source frontier is dropped
 * whole, together with the `adds` tags it covers, and an entry that is only
 * partly covered is left alone in full. Since `remove` mints a **del-dot**
 * into the entry, "every tag ≤ frontier" reaches the dot, which is what makes
 * the rule certify that the REMOVE was delivered and not merely the add
 * (`[KE3-23]`). Membership is unchanged by construction, an absent source is
 * bottom (`[KE3-30]`), and nothing is recorded — no floor, no fence, no
 * emission. `delivered`/`tagCounter` survive compaction, and a later delta
 * carrying a discarded tag is re-admitted as new information (deliberate,
 * load-bearing — feature computenet-9sm.6 must add a fence before this seam is
 * wired to a checkpoint, and computenet-v2ka MEASURED that a per-source
 * high-water floor is not that fence).
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

    /**
     * The discard is **per entry, not per tag** (`[KE3-31]` as written —
     * "`dels` entries whose every tag is ≤ `stableFrontier` … SHALL be
     * discarded" — restored by computenet-v2ka; the shipped 9sm.4 seam
     * implemented the weaker per-tag reading and this test pinned it).
     *
     * Two arms, and the first is the safety property rather than a stale
     * literal: at a frontier that covers only *part* of a tombstone the
     * reclaimer discards NOTHING, because a partly-covered entry is not
     * certified delivered and dropping half of it is what resurrects the
     * element. At a frontier that covers the whole entry — the del-dot
     * included — it reclaims the entry and the add-tags under it, so
     * reclamation is demonstrably still happening and the first arm's zero is
     * a fence, not a broken reclaimer.
     */
    @Test
    fun `discards an entry only when every tag including the del-dot is covered, and membership is unchanged`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)

        cell.inlet.call.add("x") // (s, 1)
        cell.inlet.call.add("x") // (s, 2)
        cell.inlet.call.add("x") // (s, 3)
        cell.inlet.call.remove("x") // dels[x] = {1,2,3} + del-dot (s, 4)
        cell.inlet.call.add("x") // (s, 5), live

        @Suppress("UNCHECKED_CAST")
        val s = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("x").single().sourceId

        assertEquals(setOf("x"), cell.membership())
        assertEquals(setOf(1L, 2L, 3L, 4L), delsOf(cell).getValue("x").map { it.counter }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 5L), addsOf(cell).getValue("x").map { it.counter }.toSet())

        // ARM 1 — the frontier covers 1 and 2 but neither the covered add-tag 3
        // nor the del-dot 4: the entry is untouched, in full.
        val addsBefore = addsOf(cell)
        val delsBefore = delsOf(cell)
        assertEquals(0, cell.compactBelow(TagFrontier(mapOf(s to 2L))))
        assertEquals(addsBefore, addsOf(cell))
        assertEquals(delsBefore, delsOf(cell))
        assertEquals(setOf("x"), cell.membership())

        // ARM 2 — the frontier reaches the dot, so the whole entry goes: four
        // del-tags plus the three add-tags they cover. The LIVE add-tag (s,5),
        // which no del names, is never touched even though it is ≤ frontier.
        val discarded = cell.compactBelow(TagFrontier(mapOf(s to 10L)))
        assertEquals(7, discarded)
        assertEquals(setOf(5L), addsOf(cell).getValue("x").map { it.counter }.toSet())
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
        cell.inlet.call.remove("z") // covers (s,1), mints the del-dot (s, 2)

        @Suppress("UNCHECKED_CAST")
        val s = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("z").single().sourceId

        assertEquals(2, invocationBuffer.size)
        // TWO delivered advances, not one: the local remove mints a del-dot and
        // folds it into the delivered lane, which is the whole del-dot
        // mechanism (computenet-v2ka). What this test pins is that COMPACTION
        // moves neither counter, so the figure is captured here and compared
        // across the discard rather than asserted as a literal.
        assertEquals(2, delivered)
        val deliveredBeforeCompaction = delivered

        // the frontier must reach the dot at (s,2), or the entry is not
        // certified delivered and nothing is discarded
        assertEquals(0, cell.compactBelow(TagFrontier(mapOf(s to 1L))))
        val discarded = cell.compactBelow(TagFrontier(mapOf(s to 2L)))
        assertEquals(3, discarded) // dels {1,2} plus the add-tag 1 they cover
        assertEquals(2, invocationBuffer.size) // nothing emitted by compaction
        assertEquals(deliveredBeforeCompaction, delivered) // untouched

        // the straggler: a delta re-asserting the discarded add-tag
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        Invocation.of(
            propagate,
            arrayOf(SetDelta<String>(adds = mapOf("z" to setOf(Timestamp(s, 1))))),
            null,
        ).invoke(cell.deltaInlet.call)

        assertEquals(setOf("z"), cell.membership()) // re-admitted
        assertEquals(3, invocationBuffer.size) // re-emission of the novel add
        assertEquals(deliveredBeforeCompaction, delivered) // deliver(s,1) is below the prefix: it survived

        cell.inlet.call.add("w") // must mint (s, 3), not (s, 1): tagCounter survived compaction
        @Suppress("UNCHECKED_CAST")
        val lastDelta = invocationBuffer.last().args[0] as SetDelta<String>
        assertEquals(3L, lastDelta.adds.getValue("w").single().counter)
    }
}
