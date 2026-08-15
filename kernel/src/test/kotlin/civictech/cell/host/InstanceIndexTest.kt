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
}
