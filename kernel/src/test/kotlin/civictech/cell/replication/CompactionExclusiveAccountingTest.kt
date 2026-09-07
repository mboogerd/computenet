package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Proxy
import civictech.testkit.dst.ExclusiveLedger
import civictech.testkit.dst.ExclusiveRecord
import civictech.testkit.dst.TrackedExclusive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.NotSerializableException
import java.io.ObjectOutputStream
import java.util.UUID

/** The element type under test: an exclusive payload as an OR-set element. */
private typealias Element = Owned<TrackedExclusive>

/**
 * `[KE3-38]` BS-18 (computenet-9sm.6.6) — **ownership accounting across the two
 * spans the reclaimer added**.
 *
 * The clause: for a replicated cell carrying `Owned` elements, the ownership
 * accounting shows **zero** consumes, releases or drops attributable to
 * compaction, and `compactBelow` touches tags only, never payloads. It was
 * EXTENDED, not merely restated, by what computenet-pay7 landed: the same
 * accounting must cover `applyRemote`'s **repair emission**, a new outbound
 * `SetDelta` on a path that previously emitted nothing when a frame was a pure
 * duplicate. This file measures both spans, on the production triggers —
 * `SetCell.snapshot()`'s checkpoint-driven compaction (computenet-9sm.6.1) and
 * a fenced re-delivery at `deltaInlet`.
 *
 * It sits on an AGENTS.md core invariant — *no failure, suppression, shadow,
 * park or dead-letter path may silently drop an exclusive payload* — so the
 * zeros here are assertions, not formalities, and each is paired with a
 * positive measurement that the span it is about actually happened.
 *
 * ## What "zero" is measured against
 *
 * [ExclusiveLedger] (testkit) is a **balance**, not an observation of the
 * kernel's bookkeeping: `Owned.consumed` is private with no accessor, so a
 * `take()` is invisible to it. Three independent instruments are therefore read
 * together, and each catches a different way a payload could be lost:
 *
 *  - `ledger.records()` — every tracked payload's dispositions. Empty across a
 *    span means nothing the ledger *cooperates* with fired.
 *  - the host's dead-letter outlet, folded in through `ledger.accountFrom` — a
 *    payload that degenerated onto a failure path surfaces there, and a
 *    `Frozen` in a letter's sanitized arguments still carries its token.
 *  - [Proxy.doubleDischarges] and, at teardown, `Owned.take()` itself. This is
 *    the one that catches an *uncooperative* consume: had compaction or the
 *    repair taken a handle, the teardown consume throws `Owned value already
 *    consumed (use after move)`. `Proxy.discharge` walks map **keys**
 *    (`Proxy.kt`: `is Map<*, *> -> … discharge(key, seen)`) and this cell's map
 *    keys ARE the exclusive handles, so that walk is a real path here.
 *
 * ## Fixture choices, and why they are not the obvious ones
 *
 * **No wire.** `Peering.Loopback` — the mesh `CheckpointReclaimTest` and
 * `CompactionTriggerPinTest` use — encodes every frame to a `ByteArray`, and an
 * `Owned<TrackedExclusive>` has no such encoding. The rig here is instead
 * `StabilityFreezeNoticeTest`'s: ONE real replica plus **phantom member slots**
 * injected straight into its delivered-watermark companion, which puts the
 * stable frontier under exact control with no second host and nothing to
 * serialise. `Owned` has no `equals`/`hashCode`, so the OR-set's element keys
 * are identity keys — sound in one process, and not something that would
 * survive a codec. Stated here rather than assumed.
 *
 * **`snapshot()` rather than `host.checkpoint(journal)`**, which the bead
 * flagged `unverified:` and permitted either way. MEASURED, and asserted in
 * `a journal checkpoint cannot serialise an Owned-element replica` below: the
 * checkpoint blob goes through `ObjectOutputStream`
 * (`HostDurability.checkpoint`), the snapshot map holds the elements as keys,
 * and `Owned` is `kotlinx.serialization.Serializable` but not
 * `java.io.Serializable` — so a journal checkpoint of such a cell throws.
 * `SetCell.snapshot()` is nevertheless the *whole* of the production compaction
 * trigger (it reads the `StabilityReclaim` hook `Replication.trackDeliveries`
 * installs and calls `compactBelow` before serialising) and
 * `HostDurability.checkpoint` reaches compaction only by calling it — so
 * driving the span through `snapshot()` exercises the production trigger in
 * full and loses only the serialisation this element type cannot survive
 * anyway.
 *
 * ## Non-vacuity
 *
 * Route 2 of `mutation-check.md` ("trace each test to the specific production
 * conditional it asserts on") — this is a test-only item and `SetCell.kt` is
 * outside its `metadata.files` claim, so the production mutation is the
 * reviewer's:
 *
 *  - the compaction assertions trace to `SetCell.compactBelow`'s loop body,
 *    whose every statement names `dels`, `adds`, `reclaimed` or a `Timestamp`,
 *    and in which the element appears only as a MAP KEY
 *    (`reclaimed.record(element, it)`, `adds[element]`) — no `take`, `freeze`,
 *    `release`, `borrow` or `.value` appears in it at all;
 *  - the repair assertions trace to `applyRemote`'s `val repaired = …`, which
 *    unions `newDels` with `fenced`, both `Map<E, Set<Timestamp>>` derived from
 *    the INBOUND delta's own keys — so again the element is only a key, and the
 *    emitted delta carries no payload of its own.
 *
 * In the positive direction each span is measured to have happened. The
 * compaction: the snapshot it produced carries no tombstone at all, and the
 * re-admission fence — whose only writer is `compactBelow` — afterwards holds
 * exactly the four tombstone tags the two removes emitted, and none of a live
 * element's add-tags. The repair: it is counted on an outlet tap and its `dels`
 * entry is inspected tag by tag. A self-mutation of this file (recorded on
 * computenet-9sm.6.6) shows the accounting assertions are live: a single
 * `ledger.consume` injected into either span reddens the named clue.
 */
class CompactionExclusiveAccountingTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<Element>>
    }

    /**
     * One replicated `SetCell<Owned<TrackedExclusive>>` with a fully controlled
     * stable frontier.
     *
     * The cell is deliberately **not** journaled: see the class KDoc for why a
     * journal checkpoint cannot serialise this element type, and why
     * `snapshot()` is the whole production trigger regardless.
     */
    private class Rig {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId: UUID = UUID.randomUUID()
        val cell = SetCell<Element>(CellRef(logicalId, 0))
        val ledger = ExclusiveLedger("compaction-bs18")
        val deadLetters = mutableListOf<DeadLetter>()

        /**
         * Everything the data outlet emitted, from before the first `add`. It
         * is the tag record this rig measures through — see [emittedAddTags].
         */
        val emitted = mutableListOf<SetDelta<Element>>()

        init {
            host.deadLetterOutlet.subscribe(
                Use.fixed(Propagate<DeadLetter> { deadLetters += it }, PortRef.generate()),
            )
            replication.replicate(cell, host)
            cell.outlet.subscribe(
                Use.fixed(Propagate<SetDelta<Element>> { emitted += it }, PortRef.generate()),
            )
            controller.runToIdle()
        }

        val ops: SetOps<Element> by lazy {
            (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        }

        fun mint(id: String): Element = ledger.mintOwned(id, origin = "rig")

        /**
         * Announce two phantom members whose rows for [source] sit far above
         * anything this replica has minted, so the stable frontier is bounded
         * by this replica's own (contiguous) delivered prefix and every settled
         * tombstone reads as certified delivered.
         */
        fun certifyEverythingDelivered(source: UUID, high: Long = 1_000L) {
            val companion = replication.watermarkOf(logicalId)!!
            val slotA = WatermarkCell.slotId(replication.watermarkRef(CellRef(logicalId, 1)))
            val slotB = WatermarkCell.slotId(replication.watermarkRef(CellRef(logicalId, 2)))
            companion.deltaInlet.call.propagate(
                WatermarkDelta(
                    rows = mapOf(slotA to mapOf(source to high), slotB to mapOf(source to high)),
                    members = setOf(slotA, slotB),
                ),
            )
            controller.runToIdle()
        }

        /**
         * The add-tags this replica emitted for [element], and the tombstone
         * tags it emitted when the element was removed.
         *
         * **Read from the outlet, not from the cell**, and that is forced
         * rather than chosen: `SetCell.readBounded` — the non-reclaiming tag
         * read `CheckpointReclaimTest` measures through — REPLACES an
         * `Owned`/`Leased` element with a [civictech.cell.ExclusiveEntry]
         * descriptor and elides its tags entirely (`BoundedRead.kt`: "a page
         * never carries an `Owned`/`Leased` value or a copy of one"). That
         * elision is itself part of the property under test and is asserted in
         * [assertReadElidesPayloads]; here it means the tags have to come from
         * somewhere else, and the outlet is the honest source: every `add` and
         * every `remove` emits exactly the tags it minted.
         */
        fun emittedAddTags(element: Element): Set<Timestamp> =
            emitted.mapNotNull { it.adds[element] }.flatten().toSet()

        fun emittedDelTags(element: Element): Set<Timestamp> =
            emitted.mapNotNull { it.dels[element] }.flatten().toSet()

        /** Every tag this replica has been observed to mint, either lane. */
        fun allEmittedTags(): Set<Timestamp> =
            emitted.flatMap { d -> (d.adds.values + d.dels.values).flatten() }.toSet()

        /**
         * `[KE3-38]`'s "never payloads" on the READ path: a bounded read of a
         * cell whose elements are exclusive hands back descriptors, not
         * payloads, and says how many it elided.
         */
        fun assertReadElidesPayloads(expected: Set<Element>) {
            val page = cell.readBounded(StateRead(limit = 64))
            page.next shouldBe null
            page.exclusivesElided shouldBe expected.size
            page.entries.map { (it as ExclusiveEntry).identity }.toSet() shouldBe
                expected.map { System.identityHashCode(it) }.toSet()
            page.entries.forEach { entry ->
                val e = entry as ExclusiveEntry
                e.typeName shouldBe Owned::class.java.name
                e.disposition shouldBe ExclusiveEntry.Disposition.HELD
            }
        }

        /**
         * The dispositions the ledger holds, after folding in anything the host
         * dead-lettered — the reading every "zero" assertion is made on.
         */
        fun dispositions(): List<ExclusiveRecord> {
            ledger.accountFrom(deadLetters)
            return ledger.records()
        }

        /** The dispositions, rendered, so a failure names the payloads and where they went. */
        fun dispositionSummary(): List<String> =
            dispositions().filterNot { it.outstanding }.map { it.render() }
    }

    private companion object {
        fun trace(step: String, vararg pairs: Pair<String, Any?>) {
            println("[bs18-accounting] $step: " + pairs.joinToString(" ") { (k, v) -> "$k=$v" })
        }

        /** The tag source this replica mints under — every tag it emits carries it. */
        fun tagSourceOf(rig: Rig): UUID =
            rig.allEmittedTags().map { it.sourceId }.distinct().single()
    }

    /**
     * `[KE3-38]` BS-18 **as one run over both spans**, because the clause is one
     * property: no consume, release or drop is attributable to *either* the
     * checkpoint-driven compaction or the repair emission the fence added.
     *
     * Splitting them would let each half pass with the other's payloads
     * untouched; here the same four tracked handles cross both spans, and the
     * teardown consume is what proves no uncooperative `take()` happened
     * anywhere in between.
     */
    @Test
    fun `no consume, release or drop is attributable to compaction or to the repair emission`() {
        val rig = Rig()
        val elements = (1..4).map { rig.mint("payload-$it") }

        // ------------------------------------------------------------------
        // The fold: four Owned elements added, two removed, on one replica.
        // ------------------------------------------------------------------
        elements.forEach { rig.ops.add(it) }
        elements.take(2).forEach { rig.ops.remove(it) }
        rig.controller.runToIdle()

        rig.cell.membership() shouldBe setOf(elements[2], elements[3])
        // Identity, not equality: `Owned` has no `equals`, so a membership that
        // matches at all already shows the handles were never copied.
        rig.cell.membership().all { m -> elements.any { it === m } } shouldBe true

        // The read path never copies a payload out of the fold, and says so:
        // four elements in, four descriptors and four elisions out.
        rig.assertReadElidesPayloads(elements.toSet())

        val source = tagSourceOf(rig)
        // Non-vacuity: there IS something to reclaim — {add-tag, del-dot} per
        // remove (computenet-v2ka), so the two removes emitted four tombstone
        // tags, and it is exactly those the fence must hold afterwards.
        val tombstoneTags = elements.take(2).flatMap { rig.emittedDelTags(it) }.toSet()
        tombstoneTags.size shouldBe 4
        // The add-tag of a removed element, kept for the repair span below: it
        // is a tag compaction discards and the fence then rejects.
        val reclaimedAddTag = (rig.emittedAddTags(elements[0]) intersect tombstoneTags).single()
        // Nothing is fenced yet — `compactBelow` is the fence's only writer.
        rig.cell.fencedAmong(elements[0], tombstoneTags).shouldBeEmpty()

        // Nothing in the fold touched a payload obligation.
        rig.dispositionSummary() shouldBe emptyList<String>()

        // ------------------------------------------------------------------
        // SPAN 1 — the checkpoint-driven compaction (`SetCell.snapshot()`).
        // ------------------------------------------------------------------
        rig.certifyEverythingDelivered(source)
        // Non-vacuity of the frontier itself: it certifies every tag minted here.
        val highestMinted = rig.allEmittedTags().maxOf { it.counter }
        rig.replication.stableFrontier(rig.logicalId)
            .perSource.getValue(source) shouldBeGreaterThanOrEqual highestMinted

        val dischargesBefore = Proxy.doubleDischarges
        val membershipBefore = rig.cell.membership()

        @Suppress("UNCHECKED_CAST")
        val snapshot = rig.cell.snapshot() as Map<String, Any> // THE production trigger.

        trace(
            "compaction",
            "dels" to (snapshot.getValue("dels") as Map<*, *>).size,
            "reclaimed" to snapshot["reclaimed"],
            "fenced-e1" to rig.cell.fencedAmong(elements[0], tombstoneTags),
            "dispositions" to rig.dispositionSummary(),
        )

        // Positive direction — the compaction actually happened in THIS run,
        // measured three ways rather than argued:
        //  - the tag maps this very call serialised hold no tombstone at all
        //    (the `[KE3-31]` compact-before-serialise ordering, in passing);
        //  - the fence — whose ONLY writer is `compactBelow` — now holds
        //    exactly the four tombstone tags the two removes emitted;
        //  - so the discards were those tags and nothing else.
        (snapshot.getValue("dels") as Map<*, *>).keys.shouldBeEmpty()
        snapshot["reclaimed"].toString() shouldNotBe "{}"
        rig.cell.fencedAmong(elements[0], tombstoneTags) shouldBe rig.emittedDelTags(elements[0])
        rig.cell.fencedAmong(elements[1], tombstoneTags) shouldBe rig.emittedDelTags(elements[1])
        // ... and the live elements' add-tags were NOT fenced: a live add-tag
        // is never in a discarded `dels` entry, which is `compactBelow`'s own
        // membership-invariance argument.
        rig.cell.fencedAmong(elements[2], rig.emittedAddTags(elements[2])).shouldBeEmpty()

        // [KE3-38] — the accounting across the compaction span.
        withClue("consumes/releases/drops attributable to the COMPACTION span") {
            rig.dispositionSummary() shouldBe emptyList<String>()
            Proxy.doubleDischarges shouldBe dischargesBefore
            rig.deadLetters.shouldBeEmpty()
        }
        // `compactBelow` touched tags only: membership is the SAME handles, and
        // the surviving payloads are still readable through a non-consuming
        // borrow — a `take()` anywhere would have made this throw.
        rig.cell.membership() shouldBe membershipBefore
        rig.cell.membership().map { it.borrow().value.token.id }.toSet() shouldBe
            setOf("payload-3", "payload-4")

        // ------------------------------------------------------------------
        // SPAN 2 — the repair emission (`applyRemote`'s `repaired`).
        // ------------------------------------------------------------------
        val emittedBefore = rig.emitted.size

        // A pure duplicate on the pre-computenet-pay7 reading: it re-delivers an
        // add-tag this replica has reclaimed. The fence rejects it, and the
        // rejection is ANSWERED rather than dropped.
        rig.cell.deltaInlet.call.propagate(SetDelta(adds = mapOf(elements[0] to setOf(reclaimedAddTag))))
        rig.controller.runToIdle()

        trace(
            "repair",
            "emitted" to (rig.emitted.size - emittedBefore),
            "delta" to rig.emitted.lastOrNull()?.let { "adds=${it.adds.size} dels=${it.dels.size}" },
            "dispositions" to rig.dispositionSummary(),
        )

        // Positive direction: the repair emission actually happened. Without
        // this the zeros below would be a property of a path that never ran.
        rig.emitted.size shouldBe emittedBefore + 1
        val repair = rig.emitted.last()
        repair.adds.keys.shouldBeEmpty() // the fenced tag was NOT folded as novelty
        repair.dels.keys.size shouldBe 1
        repair.dels.values.single() shouldBe setOf(reclaimedAddTag)

        // WHAT THE `dels` KEY IS, measured rather than assumed — the half of
        // `[KE3-38]` that exists only because `SetDelta.dels` is `Map<E, …>`, so
        // for an `Owned` element the map KEY is the exclusive handle. It is the
        // SAME OBJECT the inbound frame carried: the repair mints no second live
        // handle to the payload, creates no new obligation, and the ledger
        // balance is untouched by it.
        (repair.dels.keys.single() === elements[0]) shouldBe true
        // Recorded limit, next to the measurement: this identity holds because
        // the frame was injected in-process. Across a codec the key would be a
        // decoded copy, which is a different question and is not measured here —
        // an `Owned` element cannot cross this repo's wire at all today (see the
        // serialisation test below).

        // The element stays absent — the repair is a tombstone, not a resurrection.
        rig.cell.membership() shouldBe membershipBefore

        withClue("consumes/releases/drops attributable to the REPAIR-EMISSION span") {
            rig.dispositionSummary() shouldBe emptyList<String>()
            Proxy.doubleDischarges shouldBe dischargesBefore
            rig.deadLetters.shouldBeEmpty()
        }

        // ------------------------------------------------------------------
        // Teardown — and the strongest instrument in the file.
        //
        // The ledger cannot see an uncooperative `take()`; `Owned` can. Had
        // either span consumed a handle, THIS consume throws "Owned value
        // already consumed (use after move)". Reaching a balanced ledger is
        // positive evidence that all four payloads were still live and singly
        // owned after both spans.
        // ------------------------------------------------------------------
        elements.forEach { rig.ledger.consume(it, "test teardown") }
        rig.ledger.records().count { !it.outstanding } shouldBe 4
        rig.ledger.outstanding().shouldBeEmpty()
        rig.ledger.doubleAccounted().shouldBeEmpty()
        rig.ledger.verify() // [CHA1-53]: green only because nothing was lost
    }

    /**
     * The bead's `unverified:` clause, resolved by measurement: **a journal
     * checkpoint cannot serialise an `Owned`-element replica**, which is why the
     * compaction span above is driven through `SetCell.snapshot()` — the
     * production trigger `HostDurability.checkpoint` itself calls — rather than
     * through `host.checkpoint(journal)`.
     *
     * The snapshot map holds the elements as KEYS (`SetCell.snapshotLocked`),
     * `HostDurability.checkpoint` writes that map with `ObjectOutputStream`, and
     * `Owned` is `kotlinx.serialization.Serializable` but not
     * `java.io.Serializable`. The limit is stated here, next to the measurement,
     * and not only in a report.
     */
    @Test
    fun `a journal checkpoint cannot serialise an Owned-element replica`() {
        val rig = Rig()
        rig.ops.add(rig.mint("unserialisable"))
        rig.controller.runToIdle()

        val snapshot = rig.cell.snapshot()
        shouldThrow<NotSerializableException> {
            ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(snapshot) }
        }

        // The payload is untouched by the failed encode: still live, still ours.
        rig.ledger.records().single().outstanding shouldBe true
        rig.ledger.consume(rig.cell.membership().single(), "test teardown")
        rig.ledger.verify()
    }
}
