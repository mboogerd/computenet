package civictech.cell

import civictech.cell.membrane.TrafficLightCell
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.HostLiveView
import civictech.cell.graph.InstanceFactory
import civictech.cell.graph.InstanceSetStep
import civictech.cell.graph.InstanceSpec
import civictech.cell.graph.Verdict
import civictech.cell.graph.precheck
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.nature.manifestOf
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class TrafficLightTest {

    @Test
    fun `a traffic light remains journal optional in an instance set`() {
        manifestOf(TrafficLightCell::class.java).shouldBeEmpty()

        val registry = LocationRegistry()
        val controller = SimulationController(seed = 42)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val factory = InstanceFactory { ref, _ -> TrafficLightCell(Consumer::class.java, ref) }
        val step = InstanceSetStep(
            handle = "lights",
            logicalId = UUID.randomUUID(),
            factory = factory,
            instances = listOf(
                InstanceSpec(Interest.Total, 0),
                InstanceSpec(Interest.Total, 1),
            ),
        )

        GraphSpec(listOf(step)).precheck(live = HostLiveView(host, registry)).verdict shouldBe Verdict.Appliable
        step.lower().size shouldBe 2
    }

    @Test
    fun `a traffic light stops invocations when red`() {
        val trafficLight = TrafficLightCell.create<Consumer<String>>()
        val invocations = mutableListOf<Invocation>()

        trafficLight.dataOutlet.subscribe(Use.fixed(buffering(invocations), PortRef.generate()))

        trafficLight.controlInlet.call.setRed()

        trafficLight.dataInlet.call.provide("first")
        trafficLight.dataInlet.call.provide("second")
        invocations.shouldBeEmpty()
    }

    @Test
    fun `a traffic light propagations invocations when disabled`() {
        val trafficLight = TrafficLightCell.create<Consumer<String>>()
        val invocations = mutableListOf<Invocation>()
        trafficLight.dataOutlet.subscribe(Use.fixed(buffering(invocations), PortRef.generate()))

        trafficLight.controlInlet.call.setGreen()

        trafficLight.dataInlet.call.provide("first")
        trafficLight.dataInlet.call.provide("second")

        invocations.map { it.args.first() } shouldBe listOf("first", "second")
    }

    @Test
    fun `a traffic light propagates buffered invocations before others`() {
        val trafficLight = TrafficLightCell.create<Consumer<String>>()
        val invocations = mutableListOf<Invocation>()
        trafficLight.dataOutlet.subscribe(Use.fixed(buffering(invocations), PortRef.generate()))

        trafficLight.controlInlet.call.setRed()
        trafficLight.dataInlet.call.provide("first")
        trafficLight.dataInlet.call.provide("second")
        invocations.shouldBeEmpty()

        trafficLight.controlInlet.call.setGreen()
        invocations.map { it.args.first() } shouldBe listOf("first", "second")

        trafficLight.dataInlet.call.provide("third")
        invocations.map { it.args.first() } shouldBe listOf("first", "second", "third")
    }
}
