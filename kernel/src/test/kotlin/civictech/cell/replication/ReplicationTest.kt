package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.control.AttentionSupport
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.verify.ReplicaConvergence
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta

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

        fun replica(logicalId: UUID, instanceId: Long): SetCell<String> =
            SetCell<String>(CellRef(logicalId, instanceId)).also { replication.replicate(it, host) }

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

    /**
     * `Replication.evict`'s **final push-catch-up**: on a clean eviction with a reachable peer,
     * the departing replica re-fires its state as a delta at one peer's existing link before it
     * despawns — spec 42 §Eviction (*"a final state-as-delta catch-up (21) re-fires at one
     * reachable peer's existing link"*) and 93 I-3 §4.6 step 3.
     *
     * ## Why the pin needs a lossy link, and why it had none before (computenet-078s)
     *
     * The catch-up is deliberately **idempotent anti-entropy**, so on a healthy mesh the peer
     * already holds everything the departing replica could send it: link-time catch-up
     * ([Replication] `maybeLink`), park/replay across a partition and idempotent merge all
     * conspire to make its effect invisible to any fold-level assertion. Measured on
     * 2026-09-05, deleting its body left `:kernel`'s replication package (93 tests), `:testkit`
     * (248), `:concord` core+dist+dur (327) and `:demo:exchange` (17) all green — spec text
     * pinned by nothing. `concord/corpus/42-replication/42-REPL-DEPART-01.yaml` cannot pin it
     * either, by construction rather than by measurement: its `despawn` step routes through
     * `KernelDriver.despawn` → `host.managementInlet.call.despawn`, which never enters `evict`.
     *
     * What makes an end-to-end pin possible here is that a genuinely **dropping** transport seam
     * does exist — [Peering.FrameInterpose], CHA1's frame plane. `Loopback.partition()` only
     * *parks* (senders replay on heal, so nothing is ever missing), but an interposer returning
     * `emptyList()` loses the frame outright. So this test opens a window in which one delta
     * from P is lost, closes it, and shows the element is **still** absent at Q — nothing else
     * in the runtime re-sends it, because the link already exists and no re-link fires — and
     * then evicts P. The catch-up is the only remaining carrier, which is exactly what the
     * final assertion reads.
     */
    @Test
    fun `a clean evict re-fires this replica's state at a reachable peer's link`() {
        val controller = SimulationController()
        var losing = false
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(
            p.side,
            q.side,
            interposeAToB = Peering.FrameInterpose { frame -> if (losing) emptyList() else listOf(frame) },
        )
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        controller.runToIdle()

        // Control: with the seam passing frames through, ordinary gossip reaches Q.
        p.ops(onP).add("gossiped")
        controller.runToIdle()
        onQ.membership() shouldBe setOf("gossiped")

        // Lose exactly the delta carrying "orphan". Unlike partition(), this is a real loss:
        // the sender's frame is dropped after egress, so there is nothing parked to replay.
        losing = true
        p.ops(onP).add("orphan")
        controller.runToIdle()
        onP.membership() shouldBe setOf("gossiped", "orphan")
        onQ.membership() shouldBe setOf("gossiped")

        // Seam restored — and nothing re-sends it. This is the assertion that makes the last one
        // load-bearing: without it, "Q has orphan after the evict" could be ordinary gossip.
        losing = false
        controller.runToIdle()
        onQ.membership() shouldBe setOf("gossiped")

        // Clean eviction of P: Q is reachable, so evict drains, catches up and despawns.
        p.replication.evict(onP, p.host) shouldBe true
        controller.runToIdle()

        onQ.membership() shouldBe setOf("gossiped", "orphan")
    }

    /** Merge-fold across a replica's own delta outlet — reconstructs its OR-set membership. */
    private fun mergeFold(acc: SetDelta<String>, delta: SetDelta<String>): SetDelta<String> = acc.merge(delta)

    private fun membershipOf(delta: SetDelta<String>): Set<String> =
        delta.adds.keys.filterTo(mutableSetOf()) { e -> (delta.adds.getValue(e) - (delta.dels[e] ?: emptySet())).isNotEmpty() }

    @Test
    fun `three-peer mesh - partition suspends rather than evicts, heal resumes, evict despawns once reachable`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val r = Peer(controller)
        val pq = Peering.loopback(p.side, q.side)
        val qr = Peering.loopback(q.side, r.side)
        val pr = Peering.loopback(p.side, r.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        val onR = r.replica(logicalId, 2)
        controller.runToIdle()

        // one convergence invariant per peer, attached to that peer's own local replica
        val convergenceOnP = ReplicaConvergence(p.registry, logicalId, SetDelta<String>(), ::mergeFold).also { it.attach(onP) }
        val convergenceOnQ = ReplicaConvergence(q.registry, logicalId, SetDelta<String>(), ::mergeFold).also { it.attach(onQ) }
        val convergenceOnR = ReplicaConvergence(r.registry, logicalId, SetDelta<String>(), ::mergeFold).also { it.attach(onR) }

        p.ops(onP).add("alpha")
        controller.runToIdle()
        onP.membership() shouldBe setOf("alpha")
        onQ.membership() shouldBe setOf("alpha")
        onR.membership() shouldBe setOf("alpha")

        // isolate R: both its links drop, P and Q stay peered
        qr.partition()
        pr.partition()

        // membership-gated: R has no reachable peer left, so evicting it MUST suspend, not despawn
        r.replication.evict(onR, r.host).shouldBe(false)

        // R's intake is closed: a local write parks rather than applying
        r.ops(onR).add("while-partitioned")
        controller.runToIdle()
        onR.membership() shouldBe setOf("alpha") // parked, not processed

        // P and Q keep gossiping without R and still converge on each other
        p.ops(onP).add("beta")
        controller.runToIdle()
        onP.membership() shouldBe setOf("alpha", "beta")
        onQ.membership() shouldBe setOf("alpha", "beta")

        // heal: R becomes reachable again and resumes on its own, catching up and
        // replaying its parked write
        qr.heal()
        pr.heal()
        controller.runToIdle()
        onR.membership() shouldBe setOf("alpha", "beta", "while-partitioned")
        onP.membership() shouldBe setOf("alpha", "beta", "while-partitioned")
        onQ.membership() shouldBe setOf("alpha", "beta", "while-partitioned")

        listOf(convergenceOnP, convergenceOnQ, convergenceOnR).forEach { it.converged() shouldBe true }
        listOf(membershipOf(convergenceOnP.state(onP.ref)!!), membershipOf(convergenceOnQ.state(onQ.ref)!!), membershipOf(convergenceOnR.state(onR.ref)!!))
            .toSet().size shouldBe 1

        // drain-gated: with a reachable peer, eviction despawns for real
        r.replication.evict(onR, r.host).shouldBe(true)
        controller.runToIdle()

        // departed-stream rule: R left replicasOf, so the invariant no longer
        // requires its frozen last fold to keep agreeing with P/Q's future writes
        p.registry.replicasOf(logicalId) shouldBe setOf(onP.ref, onQ.ref)
        convergenceOnP.converged() shouldBe true

        p.ops(onP).add("gamma") // after R departed — must not need R's participation
        controller.runToIdle()
        onP.membership() shouldBe setOf("alpha", "beta", "while-partitioned", "gamma")
        onQ.membership() shouldBe setOf("alpha", "beta", "while-partitioned", "gamma")
        convergenceOnP.converged() shouldBe true
        convergenceOnQ.converged() shouldBe true
    }
}
