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
 * bottom (`[KE3-30]`), and nothing is emitted. `delivered`/`tagCounter` survive
 * compaction.
 *
 * **What computenet-pay7 changed here.** The discard now RECORDS what it
 * discarded, in `ReclaimedDots` — the re-admission fence, `[24-TAG-04]`'s second
 * clause. So a later delta carrying a discarded tag is no longer re-admitted as
 * new information; the assertion that used to pin that re-admission is flipped
 * below, not deleted. The fence is a per-source **dot set**, never a per-source
 * high-water floor: computenet-v2ka MEASURED all three floor variants and each
 * fenced LIVE add-tags into permanent membership divergence
 * (`doc/kernel-lane-findings.md ## KE3-GC-DEL-DOT`). `a live add-tag below a
 * reclaimed run is still admitted` is the deterministic pin of that distinction
 * — it is the test a floor fails and this fence passes.
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

        // THE RE-ADMISSION FENCE (computenet-pay7, `[24-TAG-04]` clause 2).
        // The straggler: a duplicated/reordered frame re-asserting the discarded
        // add-tag. Before the fence this test asserted the OPPOSITE here —
        // `membership() == {z}` and a third emission — under the comment
        // "re-admitted", because `applyRemote`'s novelty is `tags − adds[e]` and
        // the discard is what made (s,1) absent from `adds[e]` again. That is the
        // deterministic form of the sweep's 6-of-200 branch-F-B residual, and the
        // assertion is FLIPPED here rather than deleted.
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        Invocation.of(
            propagate,
            arrayOf(SetDelta<String>(adds = mapOf("z" to setOf(Timestamp(s, 1))))),
            null,
        ).invoke(cell.deltaInlet.call)

        assertEquals(emptySet<String>(), cell.membership()) // fenced, not re-admitted
        // …and the fence is not SILENT: the straggler is answered with a minimal
        // tombstone naming exactly the tag that was fenced, so the peer that
        // still holds it live drops it instead of diverging for ever. Fencing
        // without this repair was MEASURED to take the sweep's STABLE membership
        // divergence from 3 of 200 to 30 of 200 (see `applyRemote`).
        assertEquals(3, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val repair = invocationBuffer.last().args[0] as SetDelta<String>
        assertTrue(repair.adds.isEmpty(), "a fenced frame re-admits nothing: $repair")
        assertEquals(setOf(Timestamp(s, 1)), repair.dels.getValue("z"))
        assertEquals(deliveredBeforeCompaction, delivered) // deliver(s,1) is below the prefix: it survived

        cell.inlet.call.add("w") // must mint (s, 3), not (s, 1): tagCounter survived compaction
        @Suppress("UNCHECKED_CAST")
        val lastDelta = invocationBuffer.last().args[0] as SetDelta<String>
        assertEquals(3L, lastDelta.adds.getValue("w").single().counter)
    }

    /**
     * **The property that distinguishes this fence from the per-source floor
     * the acceptance forbids** (computenet-pay7). Source `o` mints (o,1) and
     * (o,2); only (o,1) is ever removed, so only it is reclaimed. A per-source
     * high-water floor raised to the reclaimed counter — or to this replica's
     * delivered prefix, which reaches 3 — would then reject a re-delivery of the
     * LIVE tag (o,2), and a replica that had not yet learned (o,2) could never
     * learn it: that is the 31-33-of-200 permanent membership divergence
     * computenet-v2ka measured. The dot-set fence rejects (o,1) and admits
     * (o,2), because only (o,1) was ever discarded.
     */
    @Test
    fun `a live add-tag below a reclaimed run is still admitted`() {
        val cell = SetCell<String>()
        val o = UUID.randomUUID()
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        fun deliverTo(target: SetCell<String>, delta: SetDelta<String>) =
            Invocation.of(propagate, arrayOf(delta), null).invoke(target.deltaInlet.call)

        // (o,1) is "a"'s add-tag, removed with the del-dot (o,3); (o,2) is "b"'s
        // add-tag from the SAME source and is never removed.
        deliverTo(cell, SetDelta(adds = mapOf("a" to setOf(Timestamp(o, 1)), "b" to setOf(Timestamp(o, 2)))))
        deliverTo(cell, SetDelta(dels = mapOf("a" to setOf(Timestamp(o, 1), Timestamp(o, 3)))))
        assertEquals(setOf("b"), cell.membership())

        // reclaim: the whole `a` entry is ≤ 3, so it and the add-tag under it go.
        assertEquals(3, cell.compactBelow(TagFrontier(mapOf(o to 3L))))
        assertTrue("a" !in delsOf(cell))
        assertTrue("a" !in addsOf(cell))
        assertEquals(setOf("b"), cell.membership())

        // A replica carrying the fence but NOT (o,2) — a checkpoint restored
        // into a cell that then loses the live tag — must still be able to learn
        // it, even though 2 is below the reclaimed counter 3. This is the
        // assertion a per-source floor fails.
        val fresh = SetCell<String>()
        fresh.restore(
            HashMap(snapshotOf(cell)).apply {
                put("adds", HashMap<String, Set<Timestamp>>())
                put("dels", HashMap<String, Set<Timestamp>>())
            }
        )
        assertEquals(emptySet<String>(), fresh.membership())
        deliverTo(fresh, SetDelta(adds = mapOf("b" to setOf(Timestamp(o, 2)))))
        assertEquals(setOf("b"), fresh.membership())

        // …while the reclaimed tag stays fenced on that same restored replica,
        // which is what makes the fence checkpoint state and not a cache.
        deliverTo(fresh, SetDelta(adds = mapOf("a" to setOf(Timestamp(o, 1)))))
        assertEquals(setOf("b"), fresh.membership())
    }

    /**
     * The fence covers the DEL lane too (computenet-pay7). A re-delivered `dels`
     * entry that was already reclaimed carries no novelty, so nothing is
     * re-admitted and nothing is re-emitted — the loop that
     * `GcSafetySweep.RECLAIM_UNTIL` exists to bound (discard, re-deliver,
     * re-emit, discard again) cannot start.
     */
    @Test
    fun `a re-delivered reclaimed dels entry is fenced on the del lane too`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)
        val o = UUID.randomUUID()
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        fun deliver(delta: SetDelta<String>) =
            Invocation.of(propagate, arrayOf(delta), null).invoke(cell.deltaInlet.call)

        val entry = setOf(Timestamp(o, 1), Timestamp(o, 2))
        deliver(SetDelta(adds = mapOf("q" to setOf(Timestamp(o, 1)))))
        deliver(SetDelta(dels = mapOf("q" to entry)))
        assertEquals(3, cell.compactBelow(TagFrontier(mapOf(o to 2L))))
        val emissionsAfterCompaction = invocationBuffer.size

        deliver(SetDelta(dels = mapOf("q" to entry)))
        assertTrue("q" !in delsOf(cell), "a reclaimed tombstone must not be rebuilt by a replayed frame")
        assertEquals(emissionsAfterCompaction, invocationBuffer.size, "a fully fenced frame re-emits nothing")
        assertEquals(emptySet<String>(), cell.membership())
    }
}
