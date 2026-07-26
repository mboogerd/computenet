package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.PnCounterCell
import civictech.cell.data.PnCounterDelta
import civictech.cell.data.PnCounterOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.wire.Peering
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * CP-B2 / milestone E3.3 (spec 40/42 §Delivered watermarks): replica delivery
 * paths advance a per-replica [civictech.cell.data.WatermarkCell] that gossips
 * over the *same* replica mesh as ordinary deltas — no new protocol. When a
 * replica makes a wave visible on its outlet (a local mint, or a peer delta it
 * absorbed and re-emitted), the delivery seam advances its watermark for that
 * wave's `(sourceId, counter)`; pointwise-max gossip then converges every peer
 * to the true per-source delivered frontier.
 *
 * The **control** proves the lattice tracks *per-peer* delivery rather than a
 * global fiction: an isolated peer that delivers nothing is individually
 * absent from the merged watermark — the gap is visible, not papered over.
 */
class DeliveredWatermarkTest {

    interface CounterInletProxy {
        val inlet: Use<PnCounterOps>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)

        fun replica(logicalId: UUID, instanceId: Long): PnCounterCell =
            PnCounterCell(CellRef(logicalId, instanceId)).also { replication.replicate(it, host) }

        fun ops(replica: PnCounterCell): PnCounterOps =
            (HostedCellProxy.create(replica.ref, registry, CounterInletProxy::class.java)
                    as CounterInletProxy).inlet.call
    }

    /** The `(sourceId, highWater)` a data replica's outlet epoch has reached. */
    @Suppress("UNCHECKED_CAST")
    private fun wave(cell: PnCounterCell) =
        (cell.outlet as FanOutlet<Propagate<PnCounterDelta>>).waveState()

    @Test
    fun `each peer's watermark converges to every source's true delivered frontier`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val r = Peer(controller)
        Peering.loopback(p.side, q.side)
        Peering.loopback(q.side, r.side)
        Peering.loopback(p.side, r.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        val onR = r.replica(logicalId, 2)
        controller.runToIdle()

        p.ops(onP).increment(5)
        q.ops(onQ).increment(3)
        r.ops(onR).decrement(2)
        controller.runToIdle()

        // Each replica makes THREE effective waves visible on its outlet: its own
        // write plus the two foreign deltas it absorbs and re-emits. So every
        // source's true high-water is 3, and — because the watermark cells gossip
        // over the same mesh — every peer converges to the full {sP:3, sQ:3, sR:3}.
        val peers = listOf(p, q, r)
        for (data in listOf(onP, onQ, onR)) {
            val w = wave(data)
            w.highWater shouldBe 3L
            for (peer in peers) {
                peer.replication.watermarkOf(logicalId)!!.watermark(w.sourceId) shouldBe w.highWater
            }
        }
    }

    @Test
    fun `three-replica mesh converges under 100 seeds with a mid-run partition and heal`() {
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val rnd = Random(seed)
            val peers = List(3) { Peer(controller) }
            val pq = Peering.loopback(peers[0].side, peers[1].side)
            val pr = Peering.loopback(peers[0].side, peers[2].side)
            Peering.loopback(peers[1].side, peers[2].side)

            val logicalId = UUID.randomUUID()
            val replicas = peers.mapIndexed { i, peer -> peer.replica(logicalId, i.toLong()) }
            controller.runToIdle()
            val ops = peers.map { peer -> peer.ops(replicas[peers.indexOf(peer)]) }

            for (op in 1..40) {
                if (op == 15) { pq.partition(); pr.partition() }   // peer 0 drops off
                if (op == 30) { pq.heal(); pr.heal() }             // and heals
                val who = rnd.nextInt(3)
                if (rnd.nextBoolean()) ops[who].increment(rnd.nextInt(5) + 1L)
                else ops[who].decrement(rnd.nextInt(5) + 1L)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()

            // Safety + liveness: once healed and quiescent, every peer's merged
            // watermark equals every source's true delivered high-water — the
            // lattice never overshoots and reaches the frontier at idle.
            for (data in replicas) {
                val w = wave(data)
                for (peer in peers) {
                    val seen = peer.replication.watermarkOf(logicalId)!!.watermark(w.sourceId) ?: 0L
                    seen shouldBe w.highWater
                }
            }
        }
    }

    @Test
    fun `control - an isolated peer that never delivers is individually absent from the merged lattice`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val r = Peer(controller) // deliberately NOT bridged to p or q — it delivers nothing
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        val onR = r.replica(logicalId, 2)
        controller.runToIdle()

        p.ops(onP).increment(4)
        q.ops(onQ).increment(6)
        controller.runToIdle()

        val sP = wave(onP).sourceId
        val sQ = wave(onQ).sourceId
        val sR = wave(onR).sourceId

        // P and Q delivered each other's waves — both sources are present on both.
        p.replication.watermarkOf(logicalId)!!.watermark(sP).shouldNotBeNull()
        p.replication.watermarkOf(logicalId)!!.watermark(sQ).shouldNotBeNull()
        q.replication.watermarkOf(logicalId)!!.watermark(sP).shouldNotBeNull()

        // R is isolated: its source is absent from the connected peers' merged
        // lattice, and R itself never delivered P/Q. The gap is per-peer visible —
        // proving the lattice tracks who delivered what, not a global counter.
        p.replication.watermarkOf(logicalId)!!.watermark(sR).shouldBeNull()
        q.replication.watermarkOf(logicalId)!!.watermark(sR).shouldBeNull()
        r.replication.watermarkOf(logicalId)!!.watermark(sP).shouldBeNull()
    }
}
