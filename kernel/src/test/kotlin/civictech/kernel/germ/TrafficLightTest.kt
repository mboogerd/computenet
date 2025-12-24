package civictech.kernel.germ

import civictech.kernel.germ.port.FanInPort
import civictech.kernel.germ.port.FanOutPort
import civictech.kernel.germ.port.ServeMany
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

        trafficLight.controlInlet.use { setRed() }

        trafficLight.dataInlet.use { provide("first") }
        trafficLight.dataInlet.use { provide("second") }
        invocations.shouldBeEmpty()
    }

    @Test
    fun `a traffic light propagations invocations when disabled`() {
        val trafficLight = TrafficLightCell.create<Consumer<String>>()
        val invocations = mutableListOf<Invocation>()
        trafficLight.dataOutlet.subscribe(Use.fixed(buffering(invocations), PortRef.generate()))

        trafficLight.controlInlet.use { setGreen() }

        trafficLight.dataInlet.use { provide("first") }
        trafficLight.dataInlet.use { provide("second") }

        invocations.map { it.args.first() } shouldBe listOf("first", "second")
    }

    @Test
    fun `a traffic light propagates buffered invocations before others`() {
        val trafficLight = TrafficLightCell.create<Consumer<String>>()
        val invocations = mutableListOf<Invocation>()
        trafficLight.dataOutlet.subscribe(Use.fixed(buffering(invocations), PortRef.generate()))

        trafficLight.controlInlet.use { setRed() }
        trafficLight.dataInlet.use { provide("first") }
        trafficLight.dataInlet.use { provide("second") }
        invocations.shouldBeEmpty()

        trafficLight.controlInlet.use { setGreen() }
        invocations.map { it.args.first() } shouldBe listOf("first", "second")

        trafficLight.dataInlet.use { provide("third") }
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
    val dataOutlet: ServeMany<T>
}

class TrafficLightCell<T : Any>(clazz: Class<T>) : TrafficLightApi<T> {
    override val controlInlet = FanInPort<TrafficLightControl>()
    override val dataInlet = FanInPort<T>()
    override val dataOutlet = FanOutPort<T>()

    private var isStopped = true
    private val buffer = mutableListOf<Invocation>()

    init {
        controlInlet.serve(object : TrafficLightControl {
            override fun setGreen() {
                if (!isStopped) return
                buffer.forEach { invocation ->
                    // Do _not_ cache `use` here. Each invocation might change the downstream implementation, e.g.
                    // we need to re-obtain the current implementation each time.
                    dataOutlet.use { invocation.invoke(this) }
                }
                buffer.clear()
                dataInlet.delegate(dataOutlet)
            }

            override fun setRed() {
                if (isStopped) return
                dataInlet.serve(Proxy.fromClass(clazz, Buffering(buffer)))
            }
        })
        dataInlet.serve(Proxy.fromClass(clazz, Buffering(buffer)))
    }

    companion object {
        inline fun <reified T : Any> create(): TrafficLightApi<T> =
            TrafficLightCell(T::class.java)
    }
}