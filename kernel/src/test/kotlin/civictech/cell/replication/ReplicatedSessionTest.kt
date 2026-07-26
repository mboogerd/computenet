package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M7 exit (spec 42, 92): a three-peer replicated set session — each peer
 * writing only to its local replica, gossip as the only coordination — with a
 * mid-run partition of one peer and a heal, converging under 100 seeds of
 * randomized scheduling; the control run (no heal) proves the harness
 * detects divergence.
 */
class ReplicatedSessionTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
        val deadLetters = mutableListOf<DeadLetter>()

        init {
            listOf(host, bridgeHost).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLetters += value
                    }
                }, PortRef.generate()))
            }
        }
    }

    private data class Run(val memberships: List<Set<String>>, val deadLetters: List<DeadLetter>)

    private fun runSession(seed: Long, heal: Boolean): Run {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val peers = List(3) { Peer(controller) }
        val pq = Peering.loopback(peers[0].side, peers[1].side)
        val pr = Peering.loopback(peers[0].side, peers[2].side)
        Peering.loopback(peers[1].side, peers[2].side)

        val logicalId = UUID.randomUUID()
        val replicas = peers.mapIndexed { i, peer ->
            SetCell<String>(CellRef(logicalId, i.toLong())).also { peer.replication.replicate(it, peer.host) }
        }
        controller.runToIdle()
        val ops = peers.mapIndexed { i, peer ->
            (HostedCellProxy.create(replicas[i].ref, peer.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
        }

        val universe = listOf("apple", "banana", "cherry", "date", "elder")
        val totalOps = 40
        for (op in 1..totalOps) {
            if (op == 15) { // peer 0 drops off the network
                pq.partition()
                pr.partition()
            }
            if (op == 30 && heal) {
                pq.heal()
                pr.heal()
            }
            val who = rnd.nextInt(3)
            val element = universe[rnd.nextInt(universe.size)]
            if (rnd.nextBoolean()) ops[who].add(element) else ops[who].remove(element)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        return Run(replicas.map { it.membership() }, peers.flatMap { it.deadLetters })
    }

    @Test
    fun `three replicas converge under 100 seeds with a mid-run partition and heal`() {
        for (seed in 0L until 100L) {
            val run = runSession(seed, heal = true)
            run.memberships.toSet().size shouldBe 1 // all replicas identical
            run.deadLetters.shouldBeEmpty()
        }
    }

    @Test
    fun `control - without the heal the partitioned replica diverges on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val run = runSession(seed, heal = false)
            if (run.memberships.toSet().size > 1) diverged++
        }
        // if this fails the harness is too weak to detect divergence — add ops
        (diverged > 0).shouldBeTrue()
    }
}
