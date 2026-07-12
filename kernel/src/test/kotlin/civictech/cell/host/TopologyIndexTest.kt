package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Link
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class TopologyIndexTest {
    private fun interface Sink { fun accept(value: Int) }

    private class Node(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val input = registerPort("input", FanInlet.create<Sink>(PortRef.generate(ref)))
        val output = registerPort("output", FanOutlet.create<Sink>(PortRef.generate(ref)))
    }

    @Test
    fun `promotion swap-set enumeration equals actual links under generative churn`() {
        repeat(50) { seed ->
            val random = Random(seed)
            val registry = LocationRegistry()
            val host = ManagedHost(scheduler = SimulationController().scheduler(), registry = registry)
            val nodes = List(10) { Node() }
            nodes.forEach(host.managementInlet.call::spawn)
            val live = mutableMapOf<UUID, Link>()

            repeat(250) {
                if (live.isNotEmpty() && random.nextBoolean()) {
                    live.values.random(random).unlink()
                    live.entries.removeIf { (_, link) -> registry.topology.all().none { it.id == link.id } }
                } else {
                    val from = nodes.random(random)
                    val to = nodes.random(random)
                    val result = host.managementInlet.call.connect(from.ref, "output", to.ref, "input")
                    if (result is LinkResult.Connected) live[result.link.id] = result.link
                }

                nodes.forEach { node ->
                    val actual = live.values.filterTo(mutableSetOf()) {
                        it.from.cell == node.ref || it.to.cell == node.ref
                    }.mapTo(mutableSetOf()) { it.id }
                    registry.topology.swapSet(node.ref).mapTo(mutableSetOf()) { it.id } shouldBe actual
                }
            }
        }
    }

    @Test
    fun `peer announcements mirror link and unlink events`() {
        val controller = SimulationController()
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        Peering.loopback(Peering.Side(registryA, bridgeA), Peering.Side(registryB, bridgeB))
        controller.runToIdle()

        val from = Node()
        val to = Node()
        hostA.managementInlet.call.spawn(from)
        hostA.managementInlet.call.spawn(to)
        val link = (hostA.managementInlet.call.connect(from.ref, "output", to.ref, "input") as LinkResult.Connected).link
        controller.runToIdle()

        registryB.topology.swapSet(from.ref).map { it.id }.toSet() shouldBe setOf(link.id)
        registryB.topology.swapSet(to.ref).map { it.id }.toSet() shouldBe setOf(link.id)

        link.unlink()
        controller.runToIdle()
        registryB.topology.swapSet(from.ref) shouldBe emptySet()
        registryB.topology.swapSet(to.ref) shouldBe emptySet()
    }
}
