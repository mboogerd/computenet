package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.PnCounterCell
import civictech.cell.data.PnCounterOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.CounterDelta

/**
 * Session delta 4 (spec 24/42): the second CRDT family under gossip. PN-counter
 * deltas carry per-source cumulative totals and merge by pointwise max —
 * idempotent where plain CounterDelta is not — so a full replica mesh
 * (including its echoes) converges to the true total, through the same
 * Replication wiring, partitions and late joins as the tagged set family.
 *
 * ## computenet-4ru.1.7: kernel-fold subsumption judgment — nothing slimmed
 *
 * `civictech.oracle.tagged.TaggedScenariosTest`'s "BS-12 PnCounter converges to
 * the per-source pointwise-max total" (computenet-4ru.1.6, landed) is the
 * closest oracle-side case: three replicas, mixed increments/decrements,
 * checked against `PnCounterConvergenceModel` as an independent reference
 * rather than a golden value. It is a genuine, stronger check on the SAME
 * pointwise-max arithmetic the first test below asserts — but it is not a
 * substitute for that test, and neither of the other two below has any
 * oracle-side counterpart at all:
 *
 * - **"a three-peer mesh converges... despite gossip echoes"** wires three
 *   explicit bilateral `Peering` links (a triangle: every pair peered
 *   directly), so each delta reaches every other replica over TWO paths and
 *   the assertion is specifically that the duplicate path does not
 *   double-count. BS-12 replicates through `Replication`/`ManagedHost`
 *   directly, which does not construct that duplicate-path topology — its
 *   pass says nothing about echo idempotence, only about the total being
 *   right under whatever gossip topology `Replication` itself uses.
 * - **"a late replica syncs... through catch-up"** and **"partitioned
 *   counters diverge... and heal"** are late-join and partition/heal
 *   scenarios respectively; ORA2 is deliberately quiescent and fault-free
 *   (feature computenet-4ru.1's D6: "partition/reorder/duplicate/crash/journal/restart is CHA1's
 *   rig"), so no oracle sweep exercises either.
 *
 * Per this bead's own instruction: kept, not subsumed — BS-12 is additional
 * coverage of the arithmetic, not a replacement for the echo, catch-up or
 * partition properties this file asserts.
 */
class PnCounterReplicationTest {

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

    @Test
    fun `a three-peer mesh converges to the true total despite gossip echoes`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val r = Peer(controller)
        // full mesh: every pair peered — every delta reaches everyone twice
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

        // 5 + 3 - 2 = 6 on every replica — duplicated delivery paths merge
        // idempotently instead of double-counting (the CounterDelta failure mode)
        onP.total() shouldBe 6L
        onQ.total() shouldBe 6L
        onR.total() shouldBe 6L
    }

    @Test
    fun `a late replica syncs the full per-source state through catch-up`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        controller.runToIdle()
        p.ops(onP).increment(10)
        p.ops(onP).decrement(4)
        controller.runToIdle()

        val onQ = q.replica(logicalId, 1) // joins with history already written
        controller.runToIdle()
        onQ.total() shouldBe 6L

        // and stays live after catch-up
        q.ops(onQ).increment(1)
        controller.runToIdle()
        onP.total() shouldBe 7L
    }

    @Test
    fun `partitioned counters diverge without loss and heal to the merged total`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val link = Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()

        val onP = p.replica(logicalId, 0)
        val onQ = q.replica(logicalId, 1)
        controller.runToIdle()
        p.ops(onP).increment(1)
        controller.runToIdle()

        link.partition()
        p.ops(onP).increment(10)
        q.ops(onQ).decrement(5)
        controller.runToIdle()

        onP.total() shouldBe 11L // 1 + 10, blind to Q's decrement
        onQ.total() shouldBe -4L // 1 - 5, blind to P's increment

        link.heal()
        controller.runToIdle()
        onP.total() shouldBe 6L // 1 + 10 - 5, exactly once each
        onQ.total() shouldBe 6L
    }
}
