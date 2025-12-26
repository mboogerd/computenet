package civictech.kernel.germ

import civictech.kernel.germ.port.FanInlet
import civictech.kernel.germ.port.FanOutlet
import civictech.kernel.germ.port.Subscribe
import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Buffering
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.Proxy
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.port.PortRef
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

interface TrafficLightControl {
    fun setGreen()
    fun setRed()
}

interface TrafficLightApi<T> {
    val controlInlet: Use<TrafficLightControl>
    val dataInlet: Use<T>
    val dataOutlet: Subscribe<T>
}

class TrafficLightCell<T : Any>(clazz: Class<T>) : TrafficLightApi<T> {
    override val controlInlet = FanInlet.create<TrafficLightControl>()
    override val dataInlet = FanInlet(clazz)
    override val dataOutlet = FanOutlet(clazz)

    private var isStopped = true
    private val buffer = mutableListOf<Invocation>()

    init {
        controlInlet.serve(object : TrafficLightControl {
            override fun setGreen() {
                if (!isStopped) return
                buffer.forEach { invocation ->
                    invocation.invoke(dataOutlet.call)
                }
                buffer.clear()
                dataInlet.delegate(dataOutlet)
                isStopped = false
            }

            override fun setRed() {
                if (isStopped) return
                dataInlet.serve(Proxy.fromClass(clazz, Buffering(buffer)))
                isStopped = true
            }
        })
        dataInlet.serve(Proxy.fromClass(clazz, Buffering(buffer)))
    }

    companion object {
        inline fun <reified T : Any> create(): TrafficLightApi<T> =
            TrafficLightCell(T::class.java)
    }
}