package civictech.cell.consistency

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.WatermarkCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import kotlin.test.assertTrue

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

    /**
     * AMENDED by `computenet-s0tq` ([KE3-23]), the way `computenet-07vb` amended
     * `CausalStabilityTest`'s two synthetic pins and for the same reason: this
     * case used to name `b` in BOTH `closed` and `membersOf`, which is the
     * REJOIN state, not the departure state. A cleanly departed replica is
     * despawned, so the mesh presents it as *gone from* `instancesOf` — and
     * `ReplicaQuorum`'s covering set is derived from `membersOf` alone (there is
     * no announced-member union here, unlike `CausalStability.stableFrontier`),
     * so a departed member simply is not in the quorum. The `closed` marker is
     * still in the lattice and is asserted here to keep the case honest about
     * what changed: nothing is retracted.
     */
    @Test
    fun `a cleanly-departed member no longer constrains`() {
        val a = memberRef(1)
        val b = memberRef(2) // departed: closed in the lattice AND gone from `membersOf`
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), closed = setOf(b))
        assertTrue(slotOf(b) in wm.closed(), "the departure marker was not recorded")
        val quorum = quorumOf({ wm }, { setOf(a) })
        quorum.frontier(logicalId).completeAt(source, 5, null) shouldBe true
    }

    /**
     * `computenet-s0tq` ([KE3-23]) — the REGRESSION, synthetic half. A slot that
     * is `closed` in the grow-only lattice AND named by `membersOf` is a LIVE
     * replica that returned onto its old slot ([WatermarkCell.slotId] is
     * ref-derived and replay-stable, M10.1, and nothing retracts `closed`). Its
     * row is at 3, below the wave's counter 5, so the covering quorum must HOLD.
     *
     * Before the repair the per-member check read
     * `slot in closed || (rows[slot]?.get(source) ?: MIN) >= counter`, so the
     * stale marker satisfied the predicate VACUOUSLY — the row was never
     * consulted — and this returned `true`: a false certificate of the same
     * family `computenet-07vb` fixed on the stable-frontier read.
     */
    @Test
    fun `computenet-s0tq a live member's stale closed marker does not excuse its lagging row`() {
        val a = memberRef(1)
        val b = memberRef(2) // rejoined: closed marker survives, row is at 3
        val wm = companion(
            rows = mapOf(a to mapOf(source to 5L), b to mapOf(source to 3L)),
            closed = setOf(b),
        )
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId).completeAt(source, 5, null) shouldBe false
    }

    /**
     * `computenet-s0tq` ([KE3-23]) — the same shape against the R13 creation
     * fence, which is the switch the vacuous arm actually defeated. A rejoined
     * replica is ROWLESS for the source, and the fence exists precisely so that
     * a rowless covering member reads as bottom and holds the wave. The stale
     * `closed` marker bypassed it: `covering` kept the member (the fence's own
     * filter is `creationFence || ...`), and then the settlement check let it
     * pass on `slot in closed` without ever reading the absent row.
     */
    @Test
    fun `computenet-s0tq a rowless rejoined member still holds under the R13 fence`() {
        val a = memberRef(1)
        val b = memberRef(2) // rejoined and rowless
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), closed = setOf(b))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId, creationFence = true).completeAt(source, 5, null) shouldBe false
    }

    /**
     * `computenet-s0tq` — the CONSERVATIVE COST of the repair, pinned rather than
     * left to be rediscovered. While this node's `instancesOf` view still lags a
     * genuinely departed replica, its `closed` marker no longer excuses it and
     * the wave HOLDS until the view converges. That is a hold, never a premature
     * release, and it is self-healing (the departed replica leaves `instancesOf`
     * on despawn — the case pinned by `a cleanly-departed member no longer
     * constrains` above). It is the mirror image of the stability FREEZE
     * `computenet-07vb` accepted for the same reason.
     */
    @Test
    fun `computenet-s0tq a departed member this node has not yet dropped from instancesOf holds the wave`() {
        val a = memberRef(1)
        val b = memberRef(2) // really departed, but `instancesOf` has not caught up
        val wm = companion(rows = mapOf(a to mapOf(source to 5L)), closed = setOf(b))
        val quorum = quorumOf({ wm }, { setOf(a, b) })
        quorum.frontier(logicalId).completeAt(source, 5, null) shouldBe false
    }

    /**
     * `computenet-s0tq` — the FU-2 converged-membership barrier is UNCHANGED by
     * the repair, and this pins the derivation rather than the assertion alone.
     * Its `accounted` set is `known + closed + suspended`; applying
     * `computenet-07vb`'s shape there would make it
     * `known + (closed - known) + suspended`, which is the same set. So an
     * announced slot that is closed and NOT live still counts as accounted and
     * still does not hold a keyed wave.
     */
    @Test
    fun `computenet-s0tq the FU-2 barrier still accounts for an announced-but-closed non-member slot`() {
        val a = memberRef(1)
        val b = memberRef(2) // announced to the companion, closed, not a live instance
        val wm = companion(
            rows = mapOf(a to mapOf(source to 5L)),
            closed = setOf(b),
            knownMembers = setOf(a, b),
        )
        val quorum = quorumOf({ wm }, { setOf(a) })
        quorum.frontier(logicalId, membershipBarrier = true).completeAt(source, 5, "key") shouldBe true
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

    /**
     * `computenet-s0tq` ([KE3-23]) — **the REACHABILITY settlement**, and the
     * whole reason this item existed. The synthetic pins above show what
     * `ReplicaQuorum.frontier` does when a slot is `closed` and live at once;
     * they cannot say that state occurs. This one drives the real mesh
     * (`Replication` + `Peering.loopback` + `SimulationController`, the rig
     * `StabilityOpenSetOnRejoinTest` uses for the sibling read) through an
     * `evict(closeDepartedRow = true)` and a re-`replicate` onto the SAME
     * `CellRef`, and reads the covering quorum at each of the three states.
     *
     * The bead asked whether the vacuous-satisfaction path is reachable "in
     * practice". It is: state (iii) below is an ordinary crash-restart /
     * rejoin, the `closed` marker is still in the lattice, the rejoined replica
     * is a live instance again, and it has no row for the wave's source. Before
     * the repair the quorum certified that wave anyway.
     *
     * Deterministic, not a sweep: one seeded [SimulationController], one
     * eviction, one rejoin, no faults.
     */
    @Test
    fun `computenet-s0tq a replica rejoining the same CellRef is consulted by the covering quorum, not excused by its stale closed marker`() {
        val controller = SimulationController(17L)
        val p0 = MeshPeer(controller)
        val p1 = MeshPeer(controller)
        val p2 = MeshPeer(controller)
        Peering.loopback(p0.side, p1.side)
        Peering.loopback(p1.side, p2.side)
        Peering.loopback(p0.side, p2.side)
        val id = UUID.randomUUID()

        SetCell<String>(CellRef(id, 0)).also { p0.replication.replicate(it, p0.host) }
        SetCell<String>(CellRef(id, 1)).also { p1.replication.replicate(it, p1.host) }
        val r2 = SetCell<String>(CellRef(id, 2)).also { p2.replication.replicate(it, p2.host) }
        controller.runToIdle()

        val slot2 = WatermarkCell.slotId(p0.replication.watermarkRef(r2.ref))
        val wave = UUID.randomUUID()

        // p0 and p1 have delivered the origin wave through counter 5; p2 never
        // has, so it has no entry for `wave` at any point below.
        p0.replication.watermarkOf(id)!!.advance(wave, 5L)
        p1.replication.watermarkOf(id)!!.advance(wave, 5L)
        controller.runToIdle()

        // (i) BEFORE any departure: p2 is a rowless covering member, so the R13
        // creation fence reads it as bottom and the wave HOLDS.
        p0.replication.replicaFrontier(id).completeAt(wave, 5L, null) shouldBe false

        // (ii) A clean eviction — a real despawn that closes the departed row
        // (PN-0c). p2 leaves `instancesOf`, so it is not in the covering set at
        // all, and the wave settles. This is PN-0c doing its job and the repair
        // must not disturb it.
        assertTrue(p2.replication.evict(r2, p2.host, closeDepartedRow = true), "evict suspended instead of despawning")
        controller.runToIdle()
        p0.replication.replicaFrontier(id).completeAt(wave, 5L, null) shouldBe true

        // (iii) THE REJOIN, onto the same CellRef — the state this item was
        // filed to settle. The slot is derived from the ref (M10.1), so the
        // returning replica lands on the slot already marked closed.
        val rejoined = SetCell<String>(CellRef(id, 2)).also { p2.replication.replicate(it, p2.host) }
        rejoined.ref shouldBe r2.ref
        controller.runToIdle()

        // The precondition, read off the real mesh rather than assumed: the slot
        // is a LIVE instance again and the grow-only marker is still present.
        val read = p0.replication.openSlots(id)
        assertTrue(slot2 in read.memberSlots, "the rejoined slot is not a live instance slot; read=$read")
        assertTrue(slot2 in read.closed, "the closed marker did not survive the rejoin; read=$read")

        // THE REGRESSION. The rejoined replica has no row for `wave`, exactly as
        // in (i), so the quorum must hold exactly as in (i). Before the repair
        // `slot in closed` satisfied the predicate without the row being read
        // and this returned `true` — a false certificate for a live member.
        p0.replication.replicaFrontier(id).completeAt(wave, 5L, null) shouldBe false

        // …and it releases the moment the rejoined replica really catches up,
        // so the repair holds the wave rather than wedging it (liveness).
        p2.replication.watermarkOf(id)!!.advance(wave, 5L)
        controller.runToIdle()
        p0.replication.replicaFrontier(id).completeAt(wave, 5L, null) shouldBe true
    }

    /** The three-host rig `StabilityOpenSetOnRejoinTest` uses, for the reachability test above. */
    private class MeshPeer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
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
