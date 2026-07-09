package civictech.cell

import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Test

class MapperTest {

    @Test
    fun `mapper can be used without attached outlet`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val api = host.managementInlet.call
        val mapper = MapperCell<Int, String>(f = { it.toString() })
        api.spawn(mapper)
        mapper.inlet.call.provide(1)
        controller.runToIdle()
    }

    @Test
    fun `mapper propagates transformed value`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val api = host.managementInlet.call
        val mapper = MapperCell<Int, String>(f = { it.toString() })
        api.spawn(mapper)

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Consumer<String>>(invocationBuffer)
        mapper.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))
        mapper.inlet.call.provide(1337)
        controller.runToIdle()

        assert(invocationBuffer.size == 1)
        assert(invocationBuffer[0].args.size == 1)
        assert(invocationBuffer[0].args[0] == "1337")
    }

    @Test
    fun `propagation is transitive`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val api = host.managementInlet.call
        val mapper1 = MapperCell<Int, String>(f = { it.toString() })
        val mapper2 = MapperCell<String, Long>(f = { it.toLong() })
        api.spawn(mapper1)
        api.spawn(mapper2)

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Consumer<Long>>(invocationBuffer)
        mapper2.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        api.connect(mapper1.ref, "outlet", mapper2.ref, "inlet")
        controller.runToIdle()

        mapper1.inlet.call.provide(1337)
        controller.runToIdle()

        assert(invocationBuffer.size == 1)
        assert(invocationBuffer[0].args.size == 1)
        assert(invocationBuffer[0].args[0] == 1337L)
    }
}
