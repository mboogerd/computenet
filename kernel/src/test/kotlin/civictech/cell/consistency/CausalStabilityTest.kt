package civictech.cell.consistency

import civictech.cell.CellRef
import civictech.cell.data.WatermarkCell
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * E3.5 (`computenet-9sm.3.1`): [CausalStability.stableFrontier] against a
 * hand-built watermark/membership state — no
 * [civictech.cell.host.LocationRegistry] or
 * [civictech.cell.replication.Replication] mesh, the same idiom
 * [ReplicaQuorumTest] uses (its `companion(...)` helper is private, so it is
 * copied here rather than widened).
 *
 * Pins spec `doc/spec/40-distribution/42-replication.md` [42-WM-05] (the
 * pointwise MIN over every open membership row, absent row = bottom) and
 * [42-WM-07] (one membership snapshot per read), i.e. epic [KE3-16],
 * [KE3-19], [KE3-20] (structural half) and [KE3-24].
 */
class CausalStabilityTest {

    private val logicalId = UUID.randomUUID()
    private val s = UUID.nameUUIDFromBytes("source-s".toByteArray())
    private val u = UUID.nameUUIDFromBytes("source-u".toByteArray())

    private fun memberRef(name: String) = CellRef(UUID.nameUUIDFromBytes("member-$name".toByteArray()))
    private fun watermarkRefOf(ref: CellRef) = CellRef(UUID.nameUUIDFromBytes("wm:${ref.id}".toByteArray()))
    private fun slotOf(ref: CellRef) = WatermarkCell.slotId(watermarkRefOf(ref))

    /** A converged [WatermarkCell] state, hand-built via [WatermarkCell.restore] — no mesh. */
    private fun companion(
        rows: Map<CellRef, Map<UUID, Long>> = emptyMap(),
        closed: Set<CellRef> = emptySet(),
        suspended: Set<CellRef> = emptySet(),
        knownMembers: Set<CellRef> = emptySet(),
    ): WatermarkCell {
        val cell = WatermarkCell()
        val state: HashMap<String, Serializable> = hashMapOf(
            "rows" to HashMap(rows.mapKeys { (ref, _) -> slotOf(ref) }.mapValues { (_, cols) -> HashMap(cols) }),
            "closed" to HashSet(closed.map(::slotOf)),
            "suspended" to HashMap(suspended.associate { slotOf(it) to 1L }), // odd epoch = suspended
            "members" to HashSet(knownMembers.map(::slotOf)),
        )
        cell.restore(state)
        return cell
    }

    private fun stabilityOf(
        watermarkOf: (UUID) -> WatermarkCell?,
        membersOf: (UUID) -> Set<CellRef>,
    ) = CausalStability(watermarkOf, membersOf, ::watermarkRefOf)

    private val a = memberRef("A")
    private val b = memberRef("B")
    private val c = memberRef("C")
    private val d = memberRef("D")

    /** The feature's example-1 rows: `{A: {s→7, u→2}, B: {s→5}, C: {s→9, u→4}}`. */
    private val exampleRows = mapOf(
        a to mapOf(s to 7L, u to 2L),
        b to mapOf(s to 5L),
        c to mapOf(s to 9L, u to 4L),
    )

    @Test
    fun `KE3-16 the frontier is the pointwise MIN over every open row, a source any open slot lacks is absent`() {
        val wm = companion(rows = exampleRows)
        val stability = stabilityOf({ wm }, { setOf(a, b, c) })

        val frontier = stability.stableFrontier(logicalId)

        // `s` is the MIN 5 (B), not the MAX 9 and not A's local 7; `u` is
        // absent because B has no column for it (bottom).
        frontier.perSource shouldBe mapOf(s to 5L)
    }

    @Test
    fun `KE3-20 the read is neither a MAX nor any single member's row`() {
        val wm = companion(rows = exampleRows)
        val stability = stabilityOf({ wm }, { setOf(a, b, c) })

        val frontier = stability.stableFrontier(logicalId)

        // Structural half of KE3-20, stated as the mutation the suite must
        // kill, and no open slot's own row is the answer either.
        //
        // The `shouldNotBe` below is DOCUMENTATION of the MAX shape, not the
        // discriminating assertion: on these rows a min→max mutation reads
        // {s→9} and NOT {s→9, u→4}, because `u` stays absent either way (B
        // has no `u` column, so `u` is bottom under MIN and MAX alike). The
        // assertion that actually goes red under that mutation is the next
        // line, `perSource[s] shouldBe 5L` (expected 5L, was 9L) — measured
        // in the review of computenet-9sm.3.1.
        frontier.perSource shouldNotBe mapOf(s to 9L, u to 4L)
        frontier.perSource[s] shouldBe 5L
        frontier.perSource shouldNotBe exampleRows.getValue(a)
        frontier.perSource shouldNotBe exampleRows.getValue(c)
        // NOT asserted against B's row: with the feature's exact example
        // values B is `{s→5}`, which coincides with the correct MIN, so the
        // comparison would be vacuous either way. The "not any single
        // member's row" half is pinned below on a variant where no member's
        // row equals the answer.

        val rowsWithU = exampleRows + (b to mapOf(s to 5L, u to 3L))
        val noRowMatches = stabilityOf({ companion(rows = rowsWithU) }, { setOf(a, b, c) })
            .stableFrontier(logicalId)

        noRowMatches.perSource shouldBe mapOf(s to 5L, u to 2L)
        for (member in listOf(a, b, c)) {
            noRowMatches.perSource shouldNotBe rowsWithU.getValue(member)
        }
    }

    /**
     * AMENDED by `computenet-07vb` ([KE3-23]). The state this pinned was `b`
     * closed while `membersOf` still names it as a live instance — which since
     * `computenet-07vb` is the REJOIN state, not the departure state (see the
     * test below and [CausalStability.stableFrontier]'s KDoc). It is modelled
     * here the way a clean departure actually looks on the mesh: `b` is gone
     * from `membersOf` (`evict` despawned it), still named by the grow-only
     * announced set, and closed. PN-0c's own job is unchanged.
     */
    @Test
    fun `KE3-16 a closed slot that has DEPARTED stops constraining the MIN`() {
        val wm = companion(rows = exampleRows, closed = setOf(b), knownMembers = setOf(a, b, c))
        val stability = stabilityOf({ wm }, { setOf(a, c) })

        stability.stableFrontier(logicalId).perSource shouldBe mapOf(s to 7L, u to 2L)
    }

    /**
     * [KE3-23], `computenet-07vb`: the same `closed` marker on a slot that IS a
     * live member — the state a replica evicted with `closeDepartedRow = true`
     * and re-replicated onto the same [CellRef] returns to, because
     * [WatermarkCell.slotId] is ref-derived and `closed` is grow-only.
     * `closed`'s premise ("this row can never advance again") is false there,
     * so it does not apply and `b`'s row stays in the MIN — the difference
     * between certifying a del-dot to a quorum of one and not.
     *
     * The pair with the test above is the discriminator: the qualifier is what
     * separates them, and dropping it makes exactly one of the two red.
     */
    @Test
    fun `computenet-07vb KE3-23 a closed slot that is a LIVE member keeps constraining the MIN`() {
        val wm = companion(rows = exampleRows, closed = setOf(b), knownMembers = setOf(a, b, c))
        val stability = stabilityOf({ wm }, { setOf(a, b, c) })

        // b = {s→5} and has no `u` column, so `u` reads as bottom and is absent.
        stability.stableFrontier(logicalId).perSource shouldBe mapOf(s to 5L)
        // The diagnostic read agrees, and reports no exclusion for the slot.
        val read = stability.openSlots(logicalId)
        (slotOf(b) in read.open) shouldBe true
        (slotOf(b) in read.closed) shouldBe true
        read.exclusionOf(slotOf(b)) shouldBe null
    }

    @Test
    fun `KE3-16 DEGRADE off keeps a suspended slot in the MIN, DEGRADE on drops it`() {
        val wm = companion(rows = exampleRows, suspended = setOf(b))
        val stability = stabilityOf({ wm }, { setOf(a, b, c) })

        stability.stableFrontier(logicalId, degrade = false).perSource shouldBe mapOf(s to 5L)
        stability.stableFrontier(logicalId, degrade = true).perSource shouldBe mapOf(s to 7L, u to 2L)
    }

    @Test
    fun `KE3-19 a members-only slot with no row drags every source to bottom until its row arrives`() {
        val rowsWithU = exampleRows + (b to mapOf(s to 5L, u to 3L))
        // D is announced on the companion but has no row at all.
        val rowless = companion(rows = rowsWithU, knownMembers = setOf(d))
        val before = stabilityOf({ rowless }, { setOf(a, b, c) })

        before.stableFrontier(logicalId).perSource shouldBe emptyMap()

        val withRow = companion(rows = rowsWithU + (d to mapOf(s to 9L)), knownMembers = setOf(d))
        val after = stabilityOf({ withRow }, { setOf(a, b, c) })

        // D's row arrives with `s` only: `s` settles at the MIN 5, `u` is
        // still bottom because D lacks that column.
        after.stableFrontier(logicalId).perSource shouldBe mapOf(s to 5L)
    }

    @Test
    fun `degenerate - no companion reads as everything-bottom`() {
        stabilityOf({ null }, { setOf(a, b) }).stableFrontier(logicalId).perSource shouldBe emptyMap()
    }

    @Test
    fun `degenerate - an empty open slot set reads as everything-bottom`() {
        val wm = companion(rows = exampleRows)
        stabilityOf({ wm }, { emptySet() }).stableFrontier(logicalId).perSource shouldBe emptyMap()
    }

    @Test
    fun `degenerate - every member closed reads as everything-bottom`() {
        // `membersOf` is empty because all three despawned on eviction; the
        // grow-only announced set still names them and `closed` removes them
        // (computenet-07vb: a closed slot that is still a live member is the
        // rejoin case and stays open).
        val wm = companion(rows = exampleRows, closed = setOf(a, b, c), knownMembers = setOf(a, b, c))
        stabilityOf({ wm }, { emptySet() }).stableFrontier(logicalId).perSource shouldBe emptyMap()
    }

    @Test
    fun `KE3-24 membership is one snapshot per read`() {
        val wm = companion(rows = exampleRows)
        val calls = AtomicInteger()
        val stability = stabilityOf({ wm }, { calls.incrementAndGet(); setOf(a, b, c) })

        stability.stableFrontier(logicalId)
        calls.get() shouldBe 1

        stability.stableFrontier(logicalId, degrade = true)
        calls.get() shouldBe 2
    }
}
