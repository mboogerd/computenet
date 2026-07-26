package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetOps
import civictech.cell.data.SetCell
import civictech.cell.data.CollectorCell
import civictech.cell.data.DeltaInletProxy
import civictech.cell.data.tagFold
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M5.4 (spec 41 point 3): the location registry resolves refs to Local or
 * Remote; remote locations arrive via peer announcements over the same wire
 * as data. Senders are placement-blind: proxies built against the local
 * registry reach the other side, park while a ref is unlocated or mid-move,
 * and replay in order.
 */
class RemoteAddressingTest {

    interface IntInletProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    class IntCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals = mutableListOf<Int>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    arrivals += input
                }
            })
        }
    }

    private class TwoPeers(seed: Long) {
        val controller = SimulationController(seed)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val hostB2 = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val bridgeHostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeHostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)

        init {
            Peering.loopback(
                Peering.Side(registryA, bridgeHostA),
                Peering.Side(registryB, bridgeHostB),
            )
        }
    }

    @Test
    fun `a registry proxy reaches a ref announced by the peer`() {
        val peers = TwoPeers(seed = 1)
        val collector = IntCollectorCell()
        peers.hostB.managementInlet.call.spawn(collector)
        peers.controller.runToIdle() // announcement crosses

        // A-side proxy against A's registry — placement-blind
        val remote = (HostedCellProxy.create(collector.ref, peers.registryA, IntInletProxy::class.java)
                as IntInletProxy).inlet.call
        (1..5).forEach(remote::provide)
        peers.controller.runToIdle()

        collector.arrivals shouldBe listOf(1, 2, 3, 4, 5)
        (peers.registryA.location(collector.ref) is LocationRegistry.Remote) shouldBe true
    }

    @Test
    fun `sends park until the peer announces, then replay in order`() {
        val peers = TwoPeers(seed = 2)
        val collector = IntCollectorCell()

        // proxy created and used BEFORE the target exists anywhere
        val remote = (HostedCellProxy.create(collector.ref, peers.registryA, IntInletProxy::class.java)
                as IntInletProxy).inlet.call
        (1..3).forEach(remote::provide)
        peers.controller.runToIdle()
        peers.registryA.parkedFor(collector.ref).shouldNotBeEmpty()
        collector.arrivals shouldBe emptyList<Int>()

        peers.hostB.managementInlet.call.spawn(collector)
        peers.controller.runToIdle()
        collector.arrivals shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `mid-stream migration on the remote side parks and replays in order`() {
        for (seed in 0L until 50L) {
            val peers = TwoPeers(seed)
            val collector = IntCollectorCell()
            peers.hostB.managementInlet.call.spawn(collector)
            peers.controller.runToIdle()

            val remote = (HostedCellProxy.create(collector.ref, peers.registryA, IntInletProxy::class.java)
                    as IntInletProxy).inlet.call
            val rnd = Random(seed)
            var next = 1
            repeat(20) {
                remote.provide(next++)
                repeat(rnd.nextInt(3)) { peers.controller.step() }
            }
            // migrate B's cells to another B-side host while traffic is in flight
            peers.hostB.managementInlet.call.migrate(peers.hostB2.managementInlet)
            repeat(20) {
                remote.provide(next++)
                repeat(rnd.nextInt(3)) { peers.controller.step() }
            }
            peers.controller.runToIdle()

            collector.arrivals shouldBe (1..40).toList()
        }
    }

    @Test
    fun `tagged deltas converge across peers through registry proxies`() {
        val peers = TwoPeers(seed = 3)
        val writer = SetCell<String>()
        peers.hostA.managementInlet.call.spawn(writer)
        val collector = CollectorCell()
        peers.hostB.managementInlet.call.spawn(collector)
        peers.controller.runToIdle()

        val collectorInlet = (HostedCellProxy.create(collector.ref, peers.registryA, DeltaInletProxy::class.java)
                as DeltaInletProxy).inlet.call
        writer.outlet.subscribe(Use.fixed(collectorInlet, PortRef.generate()))

        val api = (HostedCellProxy.create(writer.ref, peers.registryA, SetInletProxy::class.java)
                as SetInletProxy).inlet.call
        listOf("a", "b", "c").forEach(api::add)
        api.remove("b")
        peers.controller.runToIdle()

        tagFold(collector.arrivals) shouldBe setOf("a", "c")
    }

    @Test
    fun `lookup returns a remote-backed proxy for announced refs`() {
        val peers = TwoPeers(seed = 4)
        val collector = IntCollectorCell()
        peers.hostB.managementInlet.call.spawn(collector)
        peers.controller.runToIdle()

        val viaLookup = peers.hostA.lookup(collector.ref, IntInletProxy::class.java)
        viaLookup.shouldNotBeNull()
        viaLookup.inlet.call.provide(7)
        peers.controller.runToIdle()
        collector.arrivals shouldBe listOf(7)

        // unknown refs still answer null
        peers.hostA.lookup(CellRef(UUID.randomUUID()), IntInletProxy::class.java).shouldBeNull()
    }
}
