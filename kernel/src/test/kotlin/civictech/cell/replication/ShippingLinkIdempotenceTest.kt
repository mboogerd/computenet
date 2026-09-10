package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldBeEmpty
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

    /**
     * The step-down arm (computenet-f7h.3.2, f7h.3-D4). The same
     * partition/heal churn, but run against a pair whose roles have SWAPPED:
     * the original follower now leads and the original leader has stepped
     * down.
     *
     * Two distinct properties, and the derived `PortRef` is what makes them
     * both hold across repetition:
     *
     * - the NEW leader's outlet carries exactly one attachment per cycle. Its
     *   link is torn down by the unpublish reconciliation on every partition
     *   and rebuilt on every heal, and `shipTo` now passes
     *   `at = shipRef(leader, follower)` — a ref derived from the pair — so a
     *   rebuild REPLACES the attachment at the outlet rather than joining a
     *   second one. With `streamTo`'s random default this is only ever as safe
     *   as the teardown that happened to run first.
     * - the EX-leader's outlet carries zero, forever. Its shipping link was
     *   unlinked and dropped by the step-down ([MEM1-15]), and the heal never
     *   rebuilds it: `onPeerPublished` returns early for a ref that IS the
     *   folded leaderRef.
     */
    @Test
    fun `after a step-down the new leader keeps one attachment per cycle and the ex-leader none`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val loopback = Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val followerRef = CellRef(logicalId, 1)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val exLeader = SingleWriterReplicationTest.SwSetCell(leaderRef)
            .also { p.replication.replicate(it, p.host, mark) }
        val newLeader = SingleWriterReplicationTest.SwSetCell(followerRef)
            .also { q.replication.replicate(it, q.host, mark) }
        controller.runToIdle()
        consumerRefs(exLeader.deltaOutlet).size shouldBe 1

        // The mark is folded on BOTH registries — each peer holds its own
        // InstanceIndex, and the fold is per-registry: q's engine has to adopt
        // it to promote its local replica, and p's to demote its own. (The
        // breakdown left this `unverified:`; it is verified here by the
        // assertions below, which cannot hold if either fold is missing.)
        // q folds it locally, p receives it the way F2's announcement path
        // will — `mirrorLeaderMark`.
        val handoff = LeaderMark(logicalId, epoch = 1, leaderRef = followerRef)
        q.replication.designateLeader(handoff) shouldBe true
        p.registry.mirrorLeaderMark(handoff) shouldBe true
        controller.runToIdle()

        newLeader.leading shouldBe true
        exLeader.leading shouldBe false
        consumerRefs(exLeader.deltaOutlet).shouldBeEmpty()
        exLeader.deltaOutlet.linking.links.size shouldBe 0
        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 0
        consumerRefs(newLeader.deltaOutlet).size shouldBe 1
        q.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1

        // The DERIVED ref itself, not merely the count (f7h.3-D4). The count
        // alone does not discriminate: measured on this branch, reverting
        // `shipTo` to `streamTo(sink)`'s random default leaves every count
        // assertion in this file green, because the only path that rebuilds a
        // shipping link is one that first REMOVED it from `shipped`, and every
        // removal — the unpublish reconciliation, the step-down — unlinks and
        // therefore unsubscribes. The derived ref is what makes re-linking
        // idempotent *at the outlet*, independent of that; the observable
        // consequence is that the attachment keeps the SAME PortRef across
        // every rebuild, which a random default cannot.
        val shipAttachment = consumerRefs(newLeader.deltaOutlet)

        repeat(3) { cycle ->
            loopback.partition()
            controller.runToIdle()
            loopback.heal()
            controller.runToIdle()

            withClue(cycle) { consumerRefs(newLeader.deltaOutlet) shouldBe shipAttachment }
            withClue(cycle) { newLeader.deltaOutlet.linking.links.size shouldBe 1 }
            withClue(cycle) { q.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1 }
            withClue(cycle) { consumerRefs(exLeader.deltaOutlet).shouldBeEmpty() }
            withClue(cycle) { p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 0 }
        }

        // and shipping now runs the other way
        val ops = (HostedCellProxy.create(
            followerRef,
            q.registry,
            SingleWriterReplicationTest.WriteSetInletHolder::class.java,
        ) as SingleWriterReplicationTest.WriteSetInletHolder).writeInlet.call
        ops.add("z")
        controller.runToIdle()
        newLeader.membership shouldBe setOf("z")
        exLeader.membership shouldBe setOf("z")
    }

    private fun <T> withClue(cycle: Int, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("after heal cycle ${cycle + 1}: ${e.message}", e)
        }
}
