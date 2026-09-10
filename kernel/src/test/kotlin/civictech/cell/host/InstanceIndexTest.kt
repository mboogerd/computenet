package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.link.Interest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [KX-04]/[KX-10] proof: [InstanceIndex] is constructible with no arguments
 * and unit-testable with no [LocationRegistry] in existence.
 */
class InstanceIndexTest {

    @Test
    fun `add, remove, instancesOf and replicasOf round-trip`() {
        val index = InstanceIndex()
        val logicalId = UUID.randomUUID()
        val ref = CellRef(logicalId, instanceId = 1)

        index.instancesOf(logicalId).shouldBeEmpty()

        index.add(ref)
        index.instancesOf(logicalId) shouldBe setOf(ref)
        index.replicasOf(logicalId) shouldBe setOf(ref)

        index.remove(ref)
        index.instancesOf(logicalId).shouldBeEmpty()
        index.replicasOf(logicalId).shouldBeEmpty()
    }

    @Test
    fun `removing the last instance drops the logical-id entry entirely, not just empties it`() {
        val index = InstanceIndex()
        val logicalId = UUID.randomUUID()
        val ref = CellRef(logicalId, instanceId = 1)

        index.add(ref)
        index.remove(ref)

        // Probe below the instancesOf() read: an empty-set read alone cannot
        // distinguish "entry removed" from "entry present but empty" (BS-3).
        index.byLogicalId.containsKey(logicalId) shouldBe false
    }

    @Test
    fun `double add of the same ref reports it exactly once`() {
        val index = InstanceIndex()
        val logicalId = UUID.randomUUID()
        val ref = CellRef(logicalId, instanceId = 1)

        index.add(ref)
        index.add(ref)

        index.instancesOf(logicalId) shouldBe setOf(ref)
    }

    @Test
    fun `instancesOf returns a snapshot, unaffected by later mutation`() {
        val index = InstanceIndex()
        val logicalId = UUID.randomUUID()
        val refA = CellRef(logicalId, instanceId = 1)
        val refB = CellRef(logicalId, instanceId = 2)

        index.add(refA)
        val snapshot = index.instancesOf(logicalId)

        // Mutate the index while iterating the previously returned snapshot:
        // no ConcurrentModificationException, and the snapshot is unaffected.
        index.add(refB)
        index.remove(refA)
        val iterated = snapshot.toList()

        iterated shouldBe listOf(refA)
        index.instancesOf(logicalId) shouldBe setOf(refB)
    }

    @Test
    fun `replicasOf equals instancesOf`() {
        val index = InstanceIndex()
        val logicalId = UUID.randomUUID()
        val refA = CellRef(logicalId, instanceId = 1)
        val refB = CellRef(logicalId, instanceId = 2)

        index.add(refA)
        index.add(refB)

        index.replicasOf(logicalId) shouldBe index.instancesOf(logicalId)
    }

    /**
     * BS-5 / [KX-04] standalone proof, interest half: a bare [InstanceIndex]
     * — no [LocationRegistry], no host — defaults an unassigned ref to
     * [Interest.Total] and reports back exactly what [InstanceIndex.setInterest]
     * last recorded for it ([KX-15]).
     */
    @Test
    fun `interestOf defaults to Total and reflects the last setInterest`() {
        val index = InstanceIndex()
        val logicalId = UUID.randomUUID()
        val ref = CellRef(logicalId, instanceId = 1)

        index.interestOf(ref) shouldBe Interest.Total

        val partial = Interest.Slots(setOf(0), totalSlots = 2)
        index.setInterest(ref, partial)
        index.interestOf(ref) shouldBe partial
    }

    // ------------------------------------------------------- LeaderMark fold

    /**
     * [MEM1-02]: the fold's order is TOTAL over `(epoch, leaderRef.instanceId)`,
     * not epoch-only — at an EQUAL epoch the greater instanceId wins, and the
     * result is the same regardless of fold order. `leaderOf` before any fold
     * is null ([MEM1-03]).
     */
    @Test
    fun `markLeader adopts the strictly greater mark under the total order, same counter either way`() {
        val forward = InstanceIndex()
        val id = UUID.randomUUID()
        forward.leaderOf(id) shouldBe null
        val low = LeaderMark(id, epoch = 2, leaderRef = CellRef(id, instanceId = 7))
        val high = LeaderMark(id, epoch = 2, leaderRef = CellRef(id, instanceId = 9))

        forward.markLeader(low) shouldBe true
        forward.markLeader(high) shouldBe true
        forward.leaderOf(id)!!.leaderRef.instanceId shouldBe 9L

        val reverse = InstanceIndex()
        reverse.markLeader(high) shouldBe true
        reverse.markLeader(low) shouldBe false
        reverse.leaderOf(id)!!.leaderRef.instanceId shouldBe 9L
    }

    /**
     * [MEM1-09]/[MEM1-22], fold half: a lower epoch, an equal-epoch lower
     * instanceId, and a refold of the identical mark are each rejected and
     * leave [InstanceIndex.leaderOf] unchanged.
     */
    @Test
    fun `a lower epoch, an equal-epoch lower instanceId, and a duplicate mark are all rejected`() {
        val index = InstanceIndex()
        val id = UUID.randomUUID()
        val current = LeaderMark(id, epoch = 5, leaderRef = CellRef(id, instanceId = 3))
        index.markLeader(current) shouldBe true

        index.markLeader(LeaderMark(id, epoch = 2, leaderRef = CellRef(id, instanceId = 99))) shouldBe false
        index.leaderOf(id) shouldBe current

        val lowerInstanceAtSameEpoch = LeaderMark(id, epoch = 5, leaderRef = CellRef(id, instanceId = 1))
        index.markLeader(lowerInstanceAtSameEpoch) shouldBe false
        index.leaderOf(id) shouldBe current

        index.markLeader(current) shouldBe false
        index.leaderOf(id) shouldBe current
    }

    /** [MEM1-03]: [InstanceIndex.leaderMarks] is a snapshot, one per logical id. */
    @Test
    fun `leaderMarks snapshots every folded mark and is unaffected by a later fold`() {
        val index = InstanceIndex()
        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        val markA = LeaderMark(idA, epoch = 1, leaderRef = CellRef(idA, instanceId = 1))
        val markB = LeaderMark(idB, epoch = 1, leaderRef = CellRef(idB, instanceId = 1))
        index.markLeader(markA)
        index.markLeader(markB)

        val snapshot = index.leaderMarks()
        index.markLeader(LeaderMark(idA, epoch = 2, leaderRef = CellRef(idA, instanceId = 2)))

        snapshot.toSet() shouldBe setOf(markA, markB)
        index.leaderMarks().toSet() shouldBe setOf(index.leaderOf(idA), markB)
    }

    /**
     * f7h.1-D6 / [MEM1-14]: removing the leaderRef instance from [InstanceIndex]
     * (an unpublish) does not clear its [LeaderMark] — the mark stays folded
     * until superseded, independent of the leaderRef's continued presence in
     * [InstanceIndex.replicasOf].
     */
    @Test
    fun `remove(leaderRef) leaves leaderOf intact`() {
        val index = InstanceIndex()
        val id = UUID.randomUUID()
        val leaderRef = CellRef(id, instanceId = 1)
        val mark = LeaderMark(id, epoch = 1, leaderRef = leaderRef)
        index.add(leaderRef)
        index.markLeader(mark) shouldBe true

        index.remove(leaderRef)

        index.leaderOf(id) shouldBe mark
        index.instancesOf(id).shouldBeEmpty()
    }
}
