package civictech.demo.beadsmirror.projector

import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-6wc.1.2 — the winner-derivation re-verification the 2026-09-09
 * SYNC-REPORT WARNING on feature computenet-6wc.1 demands, run against the
 * CURRENT kernel rather than against the feature description's 2026-08-19 pin.
 *
 * The warning's two items, each owned by a test below:
 *
 * **(a)** The winner the write-back applier reads — [MirrorProjector.view] /
 * [MirrorProjector.rawValue], i.e. [OrMapCell.value] — is still the
 * [TaggedMapDelta.DOT_ORDER] maximum over the key's LIVE dots, unaffected by
 * computenet-9sm.8's del-dot semantics. The del-dot (`[24-TAG-04]`, decision
 * 9sm.8-D5) is minted by `OrMapCell`'s own `MapOps` inlet; the projector never
 * drives `MapOps` (it builds [TaggedMapDelta]s and injects them through
 * `cell.deltaInlet`), so no projector-emitted `dels` entry carries a dot that
 * was not an earlier put of the same key. That is asserted here rather than
 * assumed.
 *
 * **(b)** Stability-scoped reclamation (`[KE3-30]`/`[KE3-31]`, 9sm.8-D6/D7)
 * below the stable frontier cannot drop a dot the applier's pre-flight
 * depends on: `OrMapCell.compactBelow` discards a whole `dels` entry — and the
 * `puts` dots it covers — only when EVERY dot in it is at or below the
 * frontier, and never touches an uncovered (winning) put-dot. `snapshot()` is
 * that reclaimer's only production caller, and `Replication.trackDeliveries`
 * installs the read on the mirror's map cell in two-node mode
 * (`MirrorPeering.attach`), so this is a live path, not a hypothetical.
 *
 * Plus a **hazard probe** (not a premise of the applier, a projector/kernel
 * seam question): the projector's `dels` entries hold only covered put-dots —
 * exactly the pre-9sm.8-D5 shape of a remove whose reclaimability does not
 * depend on the REMOVE having reached the peer. Its observed outcome is
 * recorded, not repaired here.
 *
 * Everything is in-process: two [MirrorProjector]s over hand-built
 * [ChangeRecord]s, cross-fed by forwarding each delta `apply` returns into the
 * other's `cell.deltaInlet`. No `bd`, no `dolt`, no `Replication`.
 *
 * ## The packed counters, by hand
 *
 * [DotMinter.counter] packs `(commitHeight shl 31) or (ordinal shl 11) or
 * keyIndex`, so with `ordinal = 0` and `1 shl 31 = 2_147_483_648`:
 *
 * | height | keyIndex 0 (presence) | keyIndex 1 (the single field) |
 * |--------|-----------------------|-------------------------------|
 * | 5      | 10_737_418_240        | 10_737_418_241                |
 * | 6      | 12_884_901_888        | 12_884_901_889                |
 * | 7      | 15_032_385_536        | 15_032_385_537                |
 * | 8      | 17_179_869_184        | 17_179_869_185                |
 * | 9      | 19_327_352_832        | 19_327_352_833                |
 *
 * `DOT_ORDER` is `compareBy(counter).thenBy(sourceId)`, so with distinct
 * heights the higher commit wins outright and the `sourceId` tie-break is
 * reached only when two sources mint at the SAME feed position — the case
 * `a tie on the counter is broken by sourceId` builds deliberately.
 */
class WinnerDerivationTest {

    // Two projectors mirroring the same logical workspace under DISTINCT
    // identities, so their dot sources differ exactly as two mirror nodes'
    // would. `sourceId` is `nameUUIDFromBytes("beads-mirror-dots:<identity>")`
    // — not hand-computable, which is why every tie-break assertion below is
    // expressed relationally against these two values rather than as a literal.
    private val minterA = DotMinter("A")
    private val minterB = DotMinter("B")

    private val srcA = minterA.sourceId
    private val srcB = minterB.sourceId

    private val issue = "I"
    private val priority = MirrorKey(issue, "priority")
    private val presence = MirrorKey.presence(issue)

    /** The JSON string form the projector stores a scalar under. */
    private fun json(value: String) = JsonPrimitive(value).toString()

    private fun record(
        height: Long,
        type: DiffType?,
        vararg fields: Pair<String, String?>,
        ordinal: Int = 0,
        issueId: String = issue,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issueId,
        diffType = type,
        fieldDiffs = fields.map { (column, value) ->
            FieldDiff(column, old = null, new = value?.let(::JsonPrimitive))
        },
        edgeDiffs = emptyList(),
    )

    /**
     * A projector plus the running audit acceptance criterion 2 demands: every
     * dot that appears in a `dels` entry of a delta this projector emits must
     * have appeared in an EARLIER `puts` entry for the same key. A
     * projector-minted del-dot — a dot in `dels` that was never put — is what
     * would falsify (a)'s premise, so the audit runs over the whole sequence
     * rather than at one chosen point.
     */
    private inner class Node(identity: String) {
        val minter = if (identity == "A") minterA else minterB
        val cell = OrMapCell<MirrorKey, String>()
        val projector = MirrorProjector(minter, cell)

        /** Every dot this projector has ever emitted as a put, per key. */
        private val everPut = mutableMapOf<MirrorKey, MutableSet<Timestamp>>()

        /** `dels` dots that were never an earlier put of the same key. */
        val unbackedDelDots = mutableListOf<Pair<MirrorKey, Timestamp>>()

        /** Apply, audit, and forward the emitted delta to [peers]. */
        fun feed(record: ChangeRecord, vararg peers: Node): TaggedMapDelta<MirrorKey, String>? {
            val delta = projector.apply(record) ?: return null
            // Audit BEFORE folding this delta's own puts in: re-put atomicity
            // means one delta can carry a key's new put and its predecessor's
            // tombstone together, and only the predecessor may back the del.
            delta.dels.forEach { (key, dots) ->
                dots.forEach { dot ->
                    if (everPut[key]?.contains(dot) != true) unbackedDelDots += key to dot
                }
            }
            delta.puts.forEach { (key, dots) -> everPut.getOrPut(key) { mutableSetOf() } += dots.keys }
            peers.forEach { it.cell.deltaInlet.call.propagate(delta) }
            return delta
        }
    }

    // -----------------------------------------------------------------
    // (a) the winner is the DOT_ORDER maximum over the key's LIVE dots
    // -----------------------------------------------------------------

    @Test
    fun `one live dot per source - the higher counter wins on both folds`() {
        val a = Node("A")
        val b = Node("B")

        // A creates the issue at height 5: presence @ 10_737_418_240 (srcA),
        // priority="3" @ 10_737_418_241 (srcA).
        a.feed(record(5, DiffType.ADDED, "priority" to "3"), b)
        // B edits priority at height 7: priority="1" @ 15_032_385_537 (srcB).
        // B's own `mintedLive` is empty for that key — a projector tombstones
        // only dots IT minted — so this delta carries no `dels` at all, and
        // srcA's 10_737_418_241 stays live beside it.
        b.feed(record(7, DiffType.MODIFIED, "priority" to "1"), a)

        // Both cells now hold exactly two live dots at `priority`:
        //   (10_737_418_241, srcA) -> "3"
        //   (15_032_385_537, srcB) -> "1"
        // DOT_ORDER maximum is the higher counter: srcB's, value "1".
        a.cell.value(priority) shouldBe json("1")
        b.cell.value(priority) shouldBe json("1")
        a.projector.rawValue(priority) shouldBe json("1")
        b.projector.rawValue(priority) shouldBe json("1")

        // and the two dots really are both live — the winner is a PICK over a
        // two-element live set, not the only dot left standing.
        a.cell.values(priority) shouldBe setOf(json("3"), json("1"))
        b.cell.values(priority) shouldBe setOf(json("3"), json("1"))

        val expected = mapOf(issue to mapOf("priority" to json("1")))
        a.projector.view() shouldBe expected
        b.projector.view() shouldBe expected

        a.unbackedDelDots shouldBe emptyList()
        b.unbackedDelDots shouldBe emptyList()
    }

    @Test
    fun `a tie on the counter is broken by sourceId on both folds`() {
        val a = Node("A")
        val b = Node("B")

        // Both sources mint at the SAME feed position (height 5, ordinal 0),
        // so both `priority` dots carry counter 10_737_418_241 and DOT_ORDER
        // falls through to `thenBy { sourceId }`.
        a.feed(record(5, DiffType.ADDED, "priority" to "3"), b)
        b.feed(record(5, DiffType.ADDED, "priority" to "1"), a)

        val winner = if (srcA > srcB) json("3") else json("1")

        a.cell.value(priority) shouldBe winner
        b.cell.value(priority) shouldBe winner
        a.cell.values(priority) shouldBe setOf(json("3"), json("1"))
        b.cell.values(priority) shouldBe setOf(json("3"), json("1"))
        a.projector.view() shouldBe mapOf(issue to mapOf("priority" to winner))
        b.projector.view() shouldBe mapOf(issue to mapOf("priority" to winner))

        a.unbackedDelDots shouldBe emptyList()
        b.unbackedDelDots shouldBe emptyList()
    }

    @Test
    fun `a REMOVED record competing with a concurrent re-put leaves the peer's live dot winning`() {
        val a = Node("A")
        val b = Node("B")

        // height 5 (A): presence @ 10_737_418_240, priority="3" @ 10_737_418_241.
        a.feed(record(5, DiffType.ADDED, "priority" to "3"), b)
        // height 8 (B): priority="1" @ 17_179_869_185 (srcB). No dels — B has
        // minted nothing at this key before.
        b.feed(record(8, DiffType.MODIFIED, "priority" to "1"), a)
        // height 9 (A): the issue is REMOVED. A tombstones exactly the dots it
        // holds live and minted BELOW the floor 19_327_352_832, i.e. srcA's
        // 10_737_418_240 (presence) and 10_737_418_241 (priority). srcB's
        // 17_179_869_185 is not A's to tombstone and survives.
        val removal = a.feed(record(9, DiffType.REMOVED), b)!!

        removal.dels[presence] shouldBe setOf(Timestamp(srcA, 10_737_418_240L))
        removal.dels[priority] shouldBe setOf(Timestamp(srcA, 10_737_418_241L))
        removal.puts shouldBe emptyMap()

        // Presence lost its only dot, so the issue leaves `view()` on both
        // folds — membership is the presence key alone.
        a.projector.view() shouldBe emptyMap()
        b.projector.view() shouldBe emptyMap()

        // ...but the raw key still carries B's live dot, and BOTH folds agree
        // on its value. This is exactly the read the write-back applier's
        // pre-flight takes.
        a.projector.rawValue(priority) shouldBe json("1")
        b.projector.rawValue(priority) shouldBe json("1")
        a.cell.values(priority) shouldBe setOf(json("1"))
        b.cell.values(priority) shouldBe setOf(json("1"))

        a.unbackedDelDots shouldBe emptyList()
        b.unbackedDelDots shouldBe emptyList()
    }

    @Test
    fun `no projector-emitted del carries a dot that was not an earlier put of that key`() {
        val a = Node("A")
        val b = Node("B")

        // A sequence that exercises every path `fieldDelta` has: create, edit,
        // field clear, whole-issue remove, re-create, and a replay of an
        // already-applied record.
        val created = record(5, DiffType.ADDED, "priority" to "3", "status" to "open")
        a.feed(created, b)
        a.feed(record(6, DiffType.MODIFIED, "priority" to "4"), b)
        b.feed(record(7, DiffType.MODIFIED, "priority" to "1"), a)
        a.feed(record(8, DiffType.MODIFIED, "status" to null), b) // field clear
        a.feed(record(9, DiffType.REMOVED), b)
        a.feed(record(10, DiffType.ADDED, "priority" to "5"), b)
        a.feed(created, b) // replay of the create
        b.feed(record(11, DiffType.MODIFIED, "priority" to "2"), a)

        // The projector never drives `MapOps`, so 9sm.8-D5's del-dot is never
        // minted on this path: every dot in every `dels` entry is a dot the
        // same projector previously put at the same key.
        a.unbackedDelDots shouldBe emptyList()
        b.unbackedDelDots shouldBe emptyList()

        // and the folds still agree on the winner afterwards.
        a.projector.view() shouldBe b.projector.view()
        a.projector.rawValue(priority) shouldBe b.projector.rawValue(priority)
    }

    // -----------------------------------------------------------------
    // (b) reclamation below the stable frontier drops no winning dot
    // -----------------------------------------------------------------

    /** A stability read certifying EVERYTHING both sources have ever minted. */
    private fun certifyAll(): () -> TagFrontier? =
        { TagFrontier(perSource = mapOf(srcA to Long.MAX_VALUE, srcB to Long.MAX_VALUE)) }

    @Test
    fun `reclamation at a fully certifying frontier leaves every winner unchanged`() {
        val a = Node("A")
        // `onStability` is the seam `Replication.trackDeliveries` installs on
        // the mirror's map cell in two-node mode; `snapshot()` is the
        // reclaimer's only production caller.
        a.cell.onStability(certifyAll())

        // put / re-put / remove / re-put, single source:
        //   h5  presence @ 10_737_418_240, priority="3" @ 10_737_418_241
        //   h6  priority="4" @ 12_884_901_889, dels priority {10_737_418_241}
        //   h7  REMOVED: dels presence {10_737_418_240}, priority {12_884_901_889}
        //   h8  presence @ 17_179_869_184, priority="5" @ 17_179_869_185, no dels
        a.feed(record(5, DiffType.ADDED, "priority" to "3"))
        a.feed(record(6, DiffType.MODIFIED, "priority" to "4"))
        a.feed(record(7, DiffType.REMOVED))
        a.feed(record(8, DiffType.ADDED, "priority" to "5"))

        val viewBefore = a.projector.view()
        val rawBefore = a.cell.membership().associateWith { a.projector.rawValue(it) }
        viewBefore shouldBe mapOf(issue to mapOf("priority" to json("5")))

        // The production reclamation trigger. Every `dels` entry is fully below
        // the frontier, so `compactBelow`'s `allCovered` guard fires on all of
        // them and discards them plus the `puts` dots they cover. The h8 dots
        // are in no `dels` entry at all, so nothing touches them.
        a.cell.snapshot()

        a.projector.view() shouldBe viewBefore
        a.cell.membership().associateWith { a.projector.rawValue(it) } shouldBe rawBefore
        a.projector.rawValue(priority) shouldBe json("5")

        // The reclamation really happened — otherwise this test would pass
        // vacuously against a cell that compacted nothing. `state()` is the
        // public read of the whole dot state; the fence itself is `internal`
        // to `:kernel` and not reachable from this module, so the discard is
        // observed as the ABSENCE of the covered dots rather than as their
        // presence in the fence.
        val after = a.cell.state()
        after.dels shouldBe emptyMap() // every entry was fully certified
        after.puts[priority]?.keys shouldBe setOf(Timestamp(srcA, 17_179_869_185L))
        after.puts[presence]?.keys shouldBe setOf(Timestamp(srcA, 17_179_869_184L))
    }

    @Test
    fun `reclamation leaves a peer's winning dot live when only the local source is tombstoned`() {
        val a = Node("A")
        val b = Node("B")
        a.cell.onStability(certifyAll())

        a.feed(record(5, DiffType.ADDED, "priority" to "3"), b)
        b.feed(record(8, DiffType.MODIFIED, "priority" to "1"), a)
        a.feed(record(9, DiffType.REMOVED), b)

        val before = a.projector.rawValue(priority)
        before shouldBe json("1")

        a.cell.snapshot()

        // srcB's 17_179_869_185 appears in no `dels` entry, so `compactBelow`
        // cannot reach it: it only removes `puts` dots that the discarded
        // `dels` entry covered.
        a.projector.rawValue(priority) shouldBe before
        a.cell.state().puts[priority]?.keys shouldBe setOf(Timestamp(srcB, 17_179_869_185L))
        a.projector.rawValue(priority) shouldBe b.projector.rawValue(priority)
    }

    // -----------------------------------------------------------------
    // hazard probe — recorded, not repaired (acceptance criterion 4)
    // -----------------------------------------------------------------

    /**
     * The projector's `dels` entries hold ONLY covered put-dots — no del-dot of
     * their own. That is the pre-9sm.8-D5 shape whose reclaimability is
     * certified by the delivery of the PUTS, not of the REMOVE. The probe:
     * A tombstones the issue, A reclaims at a certifying frontier, and B's
     * put-dot for the surviving key is then re-delivered to A.
     *
     * Asserting convergence is the honest form: the applier premise this
     * feature rests on is that the two folds agree on the winner. A red
     * assertion here is a FINDING about the projector/kernel seam, to be
     * reported, never weakened.
     *
     * **What this probe does NOT cover, and what its green therefore does not
     * license.** Both probe variants DELIVER A's removal delta to B before A
     * reclaims. The sharper hazard the `SetCell.remove` KDoc / computenet-v2ka
     * describe is the one where the REMOVE never reaches the peer while the
     * PUTS it covers are certified anyway: A's `dels` entry carries no del-dot
     * of its own, so `compactBelow`'s `allCovered` guard is satisfied by the
     * delivery of the put-dots alone, and A can discard the tombstone while B
     * has never seen it. Reaching that state needs a stability read that
     * certifies a frontier the peer has not actually received — i.e. a
     * deliberately false certification, or a real `Replication` partition —
     * and neither is built here. So these two greens say the probed sequences
     * converge; they do NOT say the del-dot-less tombstone is safe under a
     * partition. Treat that as open.
     */
    @Test
    fun `hazard probe - a peer put-dot re-delivered after local reclamation keeps the folds agreed`() {
        val a = Node("A")
        val b = Node("B")
        a.cell.onStability(certifyAll())

        a.feed(record(5, DiffType.ADDED, "priority" to "3"), b)
        // B's put-dot, captured so it can be replayed verbatim below.
        val bPut = b.feed(record(8, DiffType.MODIFIED, "priority" to "1"), a)!!
        a.feed(record(9, DiffType.REMOVED), b)

        a.cell.snapshot() // reclaim: A's `dels` entries are fully certified

        // re-deliver B's put-dot to A, verbatim
        a.cell.deltaInlet.call.propagate(bPut)

        a.projector.view() shouldBe b.projector.view()
        a.projector.rawValue(priority) shouldBe b.projector.rawValue(priority)
        a.projector.rawValue(presence) shouldBe b.projector.rawValue(presence)
        a.cell.values(priority) shouldBe b.cell.values(priority)

        // the observed outcome, pinned rather than inferred
        a.projector.view() shouldBe emptyMap()
        a.projector.rawValue(priority) shouldBe json("1")
        a.projector.rawValue(presence) shouldBe null
    }

    /**
     * The sharper variant of the same probe, and the one the `SetCell.remove`
     * KDoc / computenet-v2ka hazard actually describes: A's OWN put-dot, which
     * reclamation has just discarded from both `puts` and `dels`, is
     * re-delivered to A. The re-admission fence ([OrMapCell] `reclaimed`,
     * 9sm.8-D6) is what has to refuse it; B refuses it because it still holds
     * the covering tombstone.
     */
    @Test
    fun `hazard probe - a reclaimed local put-dot re-delivered is refused by the fence on both folds`() {
        val a = Node("A")
        val b = Node("B")
        a.cell.onStability(certifyAll())

        val creation = a.feed(record(5, DiffType.ADDED, "priority" to "3"), b)!!
        b.feed(record(8, DiffType.MODIFIED, "priority" to "1"), a)
        a.feed(record(9, DiffType.REMOVED), b)

        a.cell.snapshot()
        // A's own creation dots are gone from A's state entirely — neither a
        // live put nor a tombstone remains to refuse their re-arrival. Only
        // the `reclaimed` fence (internal to `:kernel`) stands in the way.
        a.cell.state().puts[presence]?.keys.orEmpty() shouldBe emptySet()
        a.cell.state().dels[presence].orEmpty() shouldBe emptySet()

        // A's own reclaimed put-dots, re-delivered verbatim.
        a.cell.deltaInlet.call.propagate(creation)
        b.cell.deltaInlet.call.propagate(creation)

        a.projector.view() shouldBe b.projector.view()
        a.projector.rawValue(priority) shouldBe b.projector.rawValue(priority)
        a.projector.rawValue(presence) shouldBe b.projector.rawValue(presence)
        a.cell.membership() shouldBe b.cell.membership()

        // the observed outcome, pinned rather than inferred: the re-delivered
        // creation dots resurrect nothing on either fold.
        a.projector.view() shouldBe emptyMap()
        a.projector.rawValue(priority) shouldBe json("1")
        a.projector.rawValue(presence) shouldBe null
        a.cell.membership() shouldBe setOf(priority)
    }
}
