package civictech.cell.durability

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M10 exit, sim level (spec 24 durability, G-25): a three-peer replicated
 * session where one peer CRASHES mid-run — its host, registry, queues, and
 * links all discarded; only its journal survives. The peer rebuilds its
 * graph, recovers by checkpoint-restore + frame replay through the ordinary
 * intake, re-peers, and all replicas converge under 100 seeds — including
 * the burst of writes accepted (journaled) but still in flight at the crash.
 * The control run (recovery without the journal) still converges — replicated
 * state is recoverable from peers via anti-entropy catch-up, which is
 * replication doing its job — but the burst is LOST on every seed: accepted
 * writes that never left the process are exactly what only a write-ahead
 * journal can protect. The control proves the harness detects that loss.
 */
class CrashRecoveryTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController, journal: Journal? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journal)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    private fun runSession(seed: Long, journaled: Boolean): List<Set<String>> {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash

        var peer0 = Peer(controller, journal = if (journaled) journal else null)
        val peer1 = Peer(controller)
        val peer2 = Peer(controller)
        var links0 = listOf(Peering.loopback(peer0.side, peer1.side), Peering.loopback(peer0.side, peer2.side))
        Peering.loopback(peer1.side, peer2.side)

        val logicalId = UUID.randomUUID()
        var replica0 = SetCell<String>(CellRef(logicalId, 0)).also { peer0.replication.replicate(it, peer0.host) }
        val replica1 = SetCell<String>(CellRef(logicalId, 1)).also { peer1.replication.replicate(it, peer1.host) }
        val replica2 = SetCell<String>(CellRef(logicalId, 2)).also { peer2.replication.replicate(it, peer2.host) }
        controller.runToIdle()

        fun ops(peer: Peer, replica: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(replica.ref, peer.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call

        var ops0 = ops(peer0, replica0)
        val ops1 = ops(peer1, replica1)
        val ops2 = ops(peer2, replica2)

        // disjoint universes: pre-crash elements are never touched again, so
        // their post-recovery fate depends ONLY on the journal — post-crash
        // traffic cannot wash the loss out of the membership comparison
        val preUniverse = listOf("apple", "banana", "cherry", "date", "elder")
        val postUniverse = listOf("fig", "grape", "kiwi", "lime", "mango")
        val totalOps = 40
        for (op in 1..totalOps) {
            if (op == 8 && journaled) peer0.host.checkpoint(journal) // compaction mid-history

            if (op == 12) {
                // a burst of peer-0 writes with no scheduling in between:
                // accepted (and journaled) but guaranteed still in flight...
                ops0.add("p0-burst-a")
                ops0.add("p0-burst-b")
                // ...when the CRASH hits: peer 0 vanishes — host, registry,
                // queues, links. Only the journal object survives.
                links0.forEach { it.partition() }
                peer0 = Peer(controller, journal = if (journaled) journal else null)
                replica0 = SetCell<String>(CellRef(logicalId, 0)).also {
                    peer0.replication.replicate(it, peer0.host)
                }
                controller.runToIdle() // spawn is management-band async: graph first…
                if (journaled) peer0.host.recoverFrom(journal) // …then restore + replay tail
                links0 = listOf(
                    Peering.loopback(peer0.side, peer1.side),
                    Peering.loopback(peer0.side, peer2.side),
                )
                ops0 = ops(peer0, replica0)
                controller.runToIdle()
            }

            val who = rnd.nextInt(3)
            val universe = if (op < 12) preUniverse else postUniverse
            val element = universe[rnd.nextInt(universe.size)]
            val target = listOf(ops0, ops1, ops2)[who]
            if (rnd.nextBoolean()) target.add(element) else target.remove(element)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        return listOf(replica0, replica1, replica2).map { it.membership() }
    }

    @Test
    fun `a crashed peer recovers from its journal and all replicas converge under 100 seeds`() {
        for (seed in 0L until 100L) {
            val memberships = runSession(seed, journaled = true)
            memberships.toSet().size shouldBe 1 // all replicas identical
            // the in-flight burst was journaled at accept: write-ahead means
            // accepted-but-undispatched writes survive the crash everywhere
            memberships[0].containsAll(listOf("p0-burst-a", "p0-burst-b")).shouldBeTrue()
        }
    }

    @Test
    fun `control - without a journal, accepted-but-unflushed writes are lost on every seed`() {
        for (seed in 0L until 50L) {
            val memberships = runSession(seed, journaled = false)
            // replication's anti-entropy still converges the replicas...
            memberships.toSet().size shouldBe 1
            // ...but the accepted burst is gone — consistently, invisibly:
            // the loss only a write-ahead journal prevents
            memberships[0].none { it.startsWith("p0-burst") }.shouldBeTrue()
        }
    }
}
