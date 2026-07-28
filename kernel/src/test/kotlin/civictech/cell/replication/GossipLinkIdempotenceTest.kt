package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * T21 regression: a disconnect/reconnect cycle must not accumulate outbound
 * gossip subscriptions.
 *
 * `Peering.Loopback.partition` calls [LocationRegistry.unpublishRemotes], which
 * since T21 notifies `onUnpublish` — so [Replication]'s reconciliation handler
 * drops the `(local, remote)` entry from its `linked` bookkeeping. `heal`
 * re-announces, `Replication.linkOut` re-runs `maybeLink`, and the pair is
 * re-linked. If the gossip link's [PortRef] were freshly generated on each
 * `streamTo`, that re-link would install a *second* consumer on the local delta
 * outlet beside the orphaned first one — permanently, since nothing names the
 * old one any more. Every cycle would add one, unbounded.
 *
 * Membership convergence cannot see this: the mergeable merge is idempotent, so
 * duplicated delivery still converges. Only the subscription count exposes it,
 * which is what this test asserts — on both halves of an attachment, the
 * outlet's consumer map and the port's [civictech.cell.link.LinkSupport] record.
 */
class GossipLinkIdempotenceTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)

        fun replica(logicalId: UUID, instanceId: Long): SetCell<String> =
            SetCell<String>(CellRef(logicalId, instanceId)).also { replication.replicate(it, host) }

        fun ops(replica: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(replica.ref, registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
    }

    /**
     * Live consumer attachments on [outlet]. `FanOutlet.consumers` is private —
     * deliberately, it is the fan-out hot path — and no public projection counts
     * it, so the probe reads it reflectively rather than widening the port API
     * for one assertion. Taps live in a separate map and are not counted here.
     */
    @Suppress("UNCHECKED_CAST")
    private fun consumerRefs(outlet: FanOutlet<*>): Set<PortRef> {
        val field = FanOutlet::class.java.getDeclaredField("consumers").apply { isAccessible = true }
        return (field.get(outlet) as Map<PortRef, *>).keys.toSet()
    }

    @Test
    fun `repeated partition and heal leaves exactly one gossip subscription per peer pair`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val loopback = Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        controller.runToIdle()

        // one peer, one outbound gossip link — the baseline the invariant holds at
        consumerRefs(onP.outlet).size shouldBe 1
        consumerRefs(onQ.outlet).size shouldBe 1
        onP.outlet.linking.links.size shouldBe 1
        p.replication.linkCountAmong(setOf(onP.ref, onQ.ref)) shouldBe 1

        val installed = consumerRefs(onP.outlet)

        repeat(3) { cycle ->
            loopback.partition()
            controller.runToIdle()
            loopback.heal()
            controller.runToIdle()

            // still exactly one subscription each way, and the SAME one: the
            // gossip PortRef is derived from the (local, remote) pair, so the
            // re-link replaces the entry rather than adding a sibling
            withClue(cycle) { consumerRefs(onP.outlet) shouldBe installed }
            withClue(cycle) { consumerRefs(onQ.outlet).size shouldBe 1 }
            // and the link bookkeeping tracks the attachment rather than the
            // history of attachments: `LinkSupport.active` is keyed by a random
            // `Link.id`, so a re-stream to the same ref must evict the old record
            withClue(cycle) { onP.outlet.linking.links.size shouldBe 1 }
            withClue(cycle) { onQ.outlet.linking.links.size shouldBe 1 }
            withClue(cycle) { p.replication.linkCountAmong(setOf(onP.ref, onQ.ref)) shouldBe 1 }
        }

        // and the mesh still works after all that re-linking
        p.ops(onP).add("apple")
        q.ops(onQ).add("banana")
        controller.runToIdle()
        onP.membership() shouldBe setOf("apple", "banana")
        onQ.membership() shouldBe setOf("apple", "banana")
    }

    private fun <T> withClue(cycle: Int, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("after heal cycle ${cycle + 1}: ${e.message}", e)
        }
}
