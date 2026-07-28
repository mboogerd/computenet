package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T21 regression, the single-writer half of [GossipLinkIdempotenceTest].
 *
 * `Peering.Loopback.partition` calls [LocationRegistry.unpublishRemotes], which
 * since T21 notifies `onUnpublish` — so [SingleWriterReplication]'s
 * reconciliation handler now fires on a plain peer *disconnect*, not just on a
 * follower's despawn. That handler `unlink()`s the shipping link, and `heal`
 * rebuilds it through [SingleWriterReplication.shipTo].
 *
 * Unlike the mergeable mesh the *attachment* was never duplicated here (the
 * unlink teardown does call `unsubscribe`), but until T21 the teardown of a
 * `streamTo`-built link dropped only the attachment and never the source-side
 * [civictech.cell.link.LinkSupport] record — the negotiated `handshake` path
 * has always removed both. Every disconnect/reconnect therefore left one dead
 * link in `deltaOutlet.linking.links` for `Protocols.sendDownstream`,
 * `AbsorbAck`, `Attention` and the topology walks to keep walking, growing
 * without bound. Only the link count exposes it: shipping itself stays correct.
 */
class ShippingLinkIdempotenceTest {

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = SingleWriterReplication(registry)
    }

    /** See [GossipLinkIdempotenceTest.consumerRefs] — `FanOutlet.consumers` is private by design. */
    @Suppress("UNCHECKED_CAST")
    private fun consumerRefs(outlet: FanOutlet<*>): Set<PortRef> {
        val field = FanOutlet::class.java.getDeclaredField("consumers").apply { isAccessible = true }
        return (field.get(outlet) as Map<PortRef, *>).keys.toSet()
    }

    @Test
    fun `repeated partition and heal leaves exactly one shipping link per leader-follower pair`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val loopback = Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val followerRef = CellRef(logicalId, 1)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val leader = SingleWriterReplicationTest.SwSetCell(leaderRef)
            .also { p.replication.replicate(it, p.host, mark) }
        val follower = SingleWriterReplicationTest.SwSetCell(followerRef)
            .also { q.replication.replicate(it, q.host, mark) }
        controller.runToIdle()

        consumerRefs(leader.deltaOutlet).size shouldBe 1
        leader.deltaOutlet.linking.links.size shouldBe 1
        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1

        repeat(3) { cycle ->
            loopback.partition()
            controller.runToIdle()
            loopback.heal()
            controller.runToIdle()

            withClue(cycle) { consumerRefs(leader.deltaOutlet).size shouldBe 1 }
            withClue(cycle) { leader.deltaOutlet.linking.links.size shouldBe 1 }
            withClue(cycle) { p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1 }
        }

        // and the rebuilt link still ships
        val ops = (HostedCellProxy.create(
            leaderRef,
            p.registry,
            SingleWriterReplicationTest.WriteSetInletHolder::class.java,
        ) as SingleWriterReplicationTest.WriteSetInletHolder).writeInlet.call
        ops.add("a")
        controller.runToIdle()
        leader.membership shouldBe setOf("a")
        follower.membership shouldBe setOf("a")
    }

    private fun <T> withClue(cycle: Int, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("after heal cycle ${cycle + 1}: ${e.message}", e)
        }
}
