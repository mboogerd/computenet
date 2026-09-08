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

    /**
     * **A repair entry carries no del-dot, and that is safe on the receiver**
     * (`[KE3-23]`, computenet-684h — the open question computenet-pay7's review
     * raised, settled here by construction rather than by sampling).
     *
     * THE SEAM. `applyRemote`'s repair emission answers a fenced add-tag with a
     * `dels` entry naming exactly that tag and mints no dot ("no new remove
     * happened and nothing new needs certifying"). A receiver folding that entry
     * therefore holds a tombstone whose every tag is an ADD-tag, so
     * [SetCell.compactBelow]'s every-tag rule — which for a `remove`-minted entry
     * reaches the del-dot and thereby certifies REMOVE delivery — certifies only
     * that the ADD was delivered. The receiver can consequently reclaim the
     * repair entry at a **strictly lower** frontier than the originating
     * tombstone needed. Arm 2 measures exactly that gap: at `o -> 1` the repair
     * entry is fully discarded while the dotted entry it was reconstructed from
     * discards nothing.
     *
     * THE ANSWER, and it is the FIRST of the bead's two admissible outcomes: the
     * early reclaim is harmless, because what guards the receiver against the
     * replayed add is not the tombstone but the **fence**, and the discard is the
     * fence's only writer. Reclaiming the repair entry *records* `(o,1)` in
     * [ReclaimedDots] in the same step that drops it, and a fenced tag is
     * inadmissible however it later arrives. The del-dot's job is upstream and
     * different — it stops a straggler that holds the add and missed the remove
     * from resurrecting the element at a replica that reclaimed on ADD delivery
     * alone. A repair-entry receiver does not need that guarantee, because it
     * does not drop the straggler's frame: arm 3 shows the replayed add is
     * answered with the receiver's OWN repair, so the straggler is repaired
     * instead of stranded and the fence spreads rather than fragmenting.
     *
     * Arm 4 closes the last branch: the ORIGINAL dotted entry, replayed to a
     * receiver that only ever reclaimed the undotted repair, rebuilds a tombstone
     * from the one tag it never fenced (the dot). That is a tombstone, not a
     * resurrection — membership stays empty — and it terminates, since the
     * rebuilt entry carries no add-tag novelty for any peer.
     *
     * Nothing here is hand-rolled: the repair delta is captured from a real
     * emitter driven through the real fence path, so a change to the emission
     * shape reaches this test.
     */
    @Test
    fun `a receiver that compacts an undotted repair entry does not re-admit the add it covered`() {
        val o = UUID.randomUUID()
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        fun deliverTo(target: SetCell<String>, delta: SetDelta<String>) =
            Invocation.of(propagate, arrayOf(delta), null).invoke(target.deltaInlet.call)

        // ARM 1 — obtain a REAL repair emission. The emitter folds the origin's
        // add (o,1) and its dotted tombstone {(o,1),(o,2)}, reclaims the entry,
        // and is then handed the straggler's replay of (o,1); the fence answers.
        val emitter = SetCell<String>()
        val emitted = mutableListOf<Invocation>()
        buffer(emitter, emitted)
        val dottedEntry = setOf(Timestamp(o, 1), Timestamp(o, 2)) // (o,2) is the origin's del-dot
        deliverTo(emitter, SetDelta(adds = mapOf("r" to setOf(Timestamp(o, 1)))))
        deliverTo(emitter, SetDelta(dels = mapOf("r" to dottedEntry)))
        assertEquals(3, emitter.compactBelow(TagFrontier(mapOf(o to 2L))))
        val beforeStraggler = emitted.size
        deliverTo(emitter, SetDelta(adds = mapOf("r" to setOf(Timestamp(o, 1)))))
        assertEquals(beforeStraggler + 1, emitted.size, "a fenced add-tag must be answered, not dropped")

        @Suppress("UNCHECKED_CAST")
        val repair = emitted.last().args[0] as SetDelta<String>
        assertTrue(repair.adds.isEmpty(), "the repair re-admits nothing: $repair")
        // The premise of this bead, ASSERTED rather than assumed: the entry is
        // exactly the fenced add-tag. No dot rides with it, so nothing in it
        // certifies that any remove was delivered.
        assertEquals(setOf(Timestamp(o, 1)), repair.dels.getValue("r"))

        // ARM 2 — the receiver folds the repair, and can compact it at a frontier
        // BELOW the origin del-dot, which the dotted entry would refuse.
        val receiver = SetCell<String>()
        val received = mutableListOf<Invocation>()
        buffer(receiver, received)
        deliverTo(receiver, SetDelta(adds = mapOf("r" to setOf(Timestamp(o, 1)))))
        assertEquals(setOf("r"), receiver.membership())
        deliverTo(receiver, repair)
        assertEquals(emptySet<String>(), receiver.membership())
        assertEquals(setOf(Timestamp(o, 1)), delsOf(receiver).getValue("r"))

        val control = SetCell<String>() // the same element, tombstoned WITH the dot
        deliverTo(control, SetDelta(adds = mapOf("r" to setOf(Timestamp(o, 1)))))
        deliverTo(control, SetDelta(dels = mapOf("r" to dottedEntry)))
        assertEquals(
            0,
            control.compactBelow(TagFrontier(mapOf(o to 1L))),
            "a dotted entry is not certified at a frontier short of its dot",
        )
        assertEquals(
            2,
            receiver.compactBelow(TagFrontier(mapOf(o to 1L))),
            "the undotted repair entry IS reclaimable on the add-tag alone — the seam under test",
        )
        assertTrue("r" !in delsOf(receiver))
        assertTrue("r" !in addsOf(receiver))

        // ARM 3 — THE ACCEPTANCE ASSERTION. Replay the original add into the
        // receiver that just compacted the repair entry. The element must not
        // become live; the discard recorded (o,1) in the fence as it dropped it.
        val beforeReplay = received.size
        deliverTo(receiver, SetDelta(adds = mapOf("r" to setOf(Timestamp(o, 1)))))
        assertEquals(
            emptySet<String>(),
            receiver.membership(),
            "compacting an undotted repair entry must not let the add it covered resurrect the element",
        )
        assertTrue("r" !in addsOf(receiver), "the fenced tag is not absorbed into adds either")
        // …and the receiver repairs the straggler in turn rather than dropping
        // its frame, so the early reclaim strands nobody.
        assertEquals(beforeReplay + 1, received.size)
        @Suppress("UNCHECKED_CAST")
        val onward = received.last().args[0] as SetDelta<String>
        assertTrue(onward.adds.isEmpty(), "the receiver's answer re-admits nothing: $onward")
        assertEquals(setOf(Timestamp(o, 1)), onward.dels.getValue("r"))

        // ARM 4 — the original DOTTED entry replayed into that receiver. (o,2)
        // was never fenced here (this receiver only ever saw the undotted
        // repair), so it rebuilds a tombstone carrying the dot alone. A
        // tombstone, not a resurrection, and it carries no add novelty onward.
        deliverTo(receiver, SetDelta(dels = mapOf("r" to dottedEntry)))
        assertEquals(emptySet<String>(), receiver.membership())
        assertEquals(setOf(Timestamp(o, 2)), delsOf(receiver).getValue("r"))
        assertTrue("r" !in addsOf(receiver))
    }

    /**
     * Pins computenet-fzd3's DECISION, not a protocol property: the computenet-dwkp
     * provenance maps are deliberately unbounded, so `compactBelow` — which discards the
     * tag state precisely to bound it — must leave `mintedHere` alone, and
     * [SetCell.fenceProvenance] must still name the element a discarded tag was minted for.
     *
     * The bead offered pruning `mintedHere` in `compactBelow` as option (a); option (c) was
     * chosen because pruning changes this reading to `ABSENT` under computenet-dwkp's and
     * computenet-typw's live fence-attribution measurements. This test is what stops (a)
     * from landing silently: it fails the moment a discard starts pruning the map, which
     * forces whoever does it to re-state the decision recorded at the declaration site and
     * to name the change on those two beads. The measured per-entry cost and the workload
     * bound that make the retention acceptable live in `SetCell.kt`, not here.
     *
     * Read-only in both directions: nothing here asserts anything a protocol path consults.
     */
    @Test
    fun `compactBelow leaves the dwkp diagnostic maps alone`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)

        cell.inlet.call.add("x") // (s, 1)
        cell.inlet.call.add("x") // (s, 2)
        cell.inlet.call.remove("x") // dels[x] = {1,2} + del-dot (s, 3)

        @Suppress("UNCHECKED_CAST")
        val s = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("x").single().sourceId
        val incarnationBefore = cell.diagnosticIncarnation

        // the whole entry is covered, so the discard takes both add-tags and all three
        // del-tags — exactly the tags whose provenance is asserted to survive below.
        assertEquals(5, cell.compactBelow(TagFrontier(mapOf(s to 10L))))
        assertTrue("x" !in delsOf(cell), "precondition: the tombstone really was discarded")

        for (counter in listOf(1L, 2L, 3L)) {
            val provenance = cell.fenceProvenance("x", Timestamp(s, counter))
            assertTrue(
                "mintedHere=x" in provenance,
                "compactBelow must not prune mintedHere for the discarded tag ($counter): $provenance",
            )
            assertTrue(
                "mintedHere=ABSENT" !in provenance,
                "a locally minted tag must not read ABSENT after compaction ($counter): $provenance",
            )
            assertTrue("own=true" in provenance, "the tag was minted here ($counter): $provenance")
        }
        assertEquals(incarnationBefore, cell.diagnosticIncarnation, "compaction is not a reincarnation")
    }
}
