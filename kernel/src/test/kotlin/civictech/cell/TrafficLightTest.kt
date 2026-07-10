package civictech.cell

import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrafficLightTest {

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