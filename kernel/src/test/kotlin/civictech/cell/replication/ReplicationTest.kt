package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.attention.AttentionSupport
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M7.3–M7.5: replicas of one logical set converge by delta gossip over
 * ordinary links; partitions heal through park/replay + idempotent catch-up;
 * attention scopes a replica's activity.
 */
class ReplicationTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(val controller: SimulationController, policy: AttentionPolicy? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, attention = policy)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)

        fun replica(logicalId: UUID, incarnation: Long): SetCell<String> =
            SetCell<String>(CellRef(logicalId, incarnation)).also { replication.replicate(it, host) }

        fun ops(replica: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(replica.ref, registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
    }

    @Test
    fun `two replicas converge across the wire including removes and re-adds`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        controller.runToIdle()

        onP.ref.sameLogical(onQ.ref) shouldBe true
        onP.ref shouldNotBe onQ.ref

        p.ops(onP).add("apple")
        q.ops(onQ).add("banana")
        controller.runToIdle()
        onP.membership() shouldBe setOf("apple", "banana")
        onQ.membership() shouldBe setOf("apple", "banana")

        // remove propagates; a fresh re-add wins over the old tombstones
        q.ops(onQ).remove("apple")
        controller.runToIdle()
        onP.membership() shouldBe setOf("banana")
        p.ops(onP).add("apple")
        controller.runToIdle()
        onQ.membership() shouldBe setOf("apple", "banana")
    }

    @Test
    fun `a late replica syncs full state through catch-up`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        controller.runToIdle()
        p.ops(onP).add("apple")
        p.ops(onP).add("pear")
        p.ops(onP).remove("apple")
        controller.runToIdle()

        val onQ = q.replica(logicalId, 1) // joins with history already written
        controller.runToIdle()
        onQ.membership() shouldBe setOf("pear")
        // and the tombstone traveled: a stale replayed add of "apple" would stay dead
    }

    @Test
    fun `partition parks gossip and heal converges both sides`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val link = Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        controller.runToIdle()
        p.ops(onP).add("before")
        controller.runToIdle()

        link.partition()
        p.ops(onP).add("p-only")
        q.ops(onQ).add("q-only")
        q.ops(onQ).remove("before")
        controller.runToIdle()

        // divergence while partitioned — and nothing lost, only parked
        onP.membership() shouldBe setOf("before", "p-only")
        onQ.membership() shouldBe setOf("q-only")

        link.heal()
        controller.runToIdle()
        onP.membership() shouldBe setOf("p-only", "q-only")
        onQ.membership() shouldBe setOf("p-only", "q-only")
    }

    @Test
    fun `zero attention quiesces a replica until interest returns`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller, policy = AttentionPolicy(suspendAfter = 0))
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        controller.runToIdle()
        AttentionSupport.of(onQ).attend(0f) // local interest gone

        p.ops(onP).add("while-unattended")
        controller.runToIdle()
        onQ.membership() shouldBe emptySet<String>() // parked, not processed
        p.registry.parkedFor(onQ.ref) // (delivered to Q's host, parked there)

        AttentionSupport.of(onQ).attend(1f)
        controller.runToIdle()
        onQ.membership() shouldBe setOf("while-unattended")
        onQ.membership().shouldNotBeEmpty()
    }
}
