package civictech.cell.consistency

import civictech.cell.CellRef
import civictech.cell.data.WatermarkCell
import civictech.cell.link.Interest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * T11-D: [ReplicaQuorum.frontier] against a hand-built watermark/membership
 * state — no [civictech.cell.host.LocationRegistry] or
 * [civictech.cell.replication.Replication] mesh required, the missing direct
 * unit test the move's ticket called for. A [WatermarkCell]'s converged state
 * is built directly via [WatermarkCell.restore] (its own snapshot format)
 * instead of driving it through gossip deltas, so each case pins one policy
 * switch in isolation.
 *
 * Covers the four documented switches verbatim-preserved from
 * `Replication.replicaFrontier`: the R13 creation fence, the PN-19 DEGRADE
 * quorum-shrink, the FU-2 converged-membership barrier, and the null-key
 * (unfiltered) path.
 */
class ReplicaQuorumTest {

    private val logicalId = UUID.randomUUID()
    private val source = UUID.randomUUID()

    private fun memberRef(n: Int) = CellRef(UUID.nameUUIDFromBytes("member-$n".toByteArray()))
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

    private fun quorumOf(
        watermarkOf: (UUID) -> WatermarkCell?,
        membersOf: (UUID) -> Set<CellRef>,
        interestOf: (CellRef) -> Interest = { Interest.Total },
    ) = ReplicaQuorum(watermarkOf, membersOf, interestOf, ::watermarkRefOf)

    @Test
    fun `no companion never completes`() {
        val a = memberRef(1)
        val quorum = quorumOf({ null }, { setOf(a) })
        quorum.frontier(logicalId).completeAt(source, 1, null) shouldBe false
    }

    @Test
    fun `empty membership never completes`() {
        val a = memberRef(1)
        val quorum = quorumOf({ companion(rows = mapOf(a to mapOf(source to 5L))) }, { emptySet() })
        quorum.frontier(logicalId).completeAt(source, 1, null) shouldBe false
    }

    @Test
    fun `every covering member delivered at or past the counter completes`() {
        val a = memberRef(1)
        val b = memberRef(2)
        val wm = companion(rows = mapOf(a to mapOf(source to 5L), b to mapOf(source to 7L)))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId).completeAt(source, 5, null) shouldBe true
    }

    @Test
    fun `a lagging covering member withholds completion`() {
        val a = memberRef(1)
        val b = memberRef(2)
        val wm = companion(rows = mapOf(a to mapOf(source to 5L), b to mapOf(source to 3L)))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId).completeAt(source, 5, null) shouldBe false
    }

    @Test
    fun `a cleanly-departed member no longer constrains`() {
        val a = memberRef(1)
        val b = memberRef(2) // no row at all for b
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), closed = setOf(b))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId).completeAt(source, 5, null) shouldBe true
    }

    @Test
    fun `R13 creation fence on holds for a rowless freshly-joined covering member`() {
        val a = memberRef(1)
        val b = memberRef(2) // rowless, not closed: freshly joined
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId, creationFence = true).completeAt(source, 5, null) shouldBe false
    }

    @Test
    fun `R13 creation fence off skips a rowless freshly-joined covering member`() {
        val a = memberRef(1)
        val b = memberRef(2) // rowless, not closed
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId, creationFence = false).completeAt(source, 5, null) shouldBe true
    }

    @Test
    fun `PN-19 DEGRADE off keeps a suspended member in the quorum (holds)`() {
        val a = memberRef(1)
        val b = memberRef(2)
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), suspended = setOf(b))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId, degrade = false).completeAt(source, 5, null) shouldBe false
    }

    @Test
    fun `PN-19 DEGRADE on drops a suspended member from the quorum`() {
        val a = memberRef(1)
        val b = memberRef(2)
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), suspended = setOf(b))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId, degrade = true).completeAt(source, 5, null) shouldBe true
    }

    @Test
    fun `FU-2 membership barrier holds a keyed wave on an unaccounted known member`() {
        val a = memberRef(1)
        val b = memberRef(2) // known to the companion (announced) but NOT in this node's instancesOf view
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), knownMembers = setOf(a, b))
        val quorum = quorumOf({ wm }, { setOf(a) }) // instancesOf view only knows `a`
        quorum.frontier(logicalId, membershipBarrier = true).completeAt(source, 5, "key") shouldBe false
    }

    @Test
    fun `FU-2 membership barrier does not hold an unkeyed (null-key) wave`() {
        val a = memberRef(1)
        val b = memberRef(2)
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), knownMembers = setOf(a, b))
        val quorum = quorumOf({ wm }, { setOf(a) })
        quorum.frontier(logicalId, membershipBarrier = true).completeAt(source, 5, null) shouldBe true
    }

    @Test
    fun `FU-2 membership barrier off ignores the unaccounted known member`() {
        val a = memberRef(1)
        val b = memberRef(2)
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), knownMembers = setOf(a, b))
        val quorum = quorumOf({ wm }, { setOf(a) })
        quorum.frontier(logicalId, membershipBarrier = false).completeAt(source, 5, "key") shouldBe true
    }

    @Test
    fun `a non-covering member (interest does not admit the key) is excluded from the quorum`() {
        val a = memberRef(1) // covers the key
        val b = memberRef(2) // does NOT cover the key, no row at all
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)))
        val quorum = quorumOf(
            watermarkOf = { wm },
            membersOf = { setOf(a, b) },
            interestOf = { ref -> if (ref == a) Interest.Total else Interest.Empty },
        )
        quorum.frontier(logicalId).completeAt(source, 5, "key") shouldBe true
    }
}
