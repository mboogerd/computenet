package civictech.kernel.germ

import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.germ.port.PortRef
import org.junit.jupiter.api.Test

class MapperTest {

    @Test
    fun `mapper can be used without attached outlet`() {
        val host = ManagedHost()
        val api = host.managementInlet.call
        val mapper = MapperCell<Int, String>(f = { it.toString() })
        api.spawn(mapper)
        mapper.inlet.call.provide(1)
    }

    @Test
    fun `mapper propagates transformed value`() {
        val host = ManagedHost()
        val api = host.managementInlet.call
        val mapper = MapperCell<Int, String>(f = { it.toString() })
        api.spawn(mapper)

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Consumer<String>>(invocationBuffer)
        mapper.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))
        mapper.inlet.call.provide(1337)

        assert(invocationBuffer.size == 1)
        assert(invocationBuffer[0].args.size == 1)
        assert(invocationBuffer[0].args[0] == "1337")
    }

    @Test
    fun `propagation is transitive`() {
        val host = ManagedHost()
        val api = host.managementInlet.call
        val mapper1 = MapperCell<Int, String>(f = { it.toString() })
        val mapper2 = MapperCell<String, Long>(f = { it.toLong() })
        api.spawn(mapper1)
        api.spawn(mapper2)

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Consumer<Long>>(invocationBuffer)
        mapper2.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        api.connect(mapper1.ref, "outlet", mapper2.ref, "inlet")
        Thread.sleep(100) // Give the host time to process connect call

        mapper1.inlet.call.provide(1337)
        Thread.sleep(100) // Give the host time to process the propagation

        assert(invocationBuffer.size == 1)
        assert(invocationBuffer[0].args.size == 1)
        assert(invocationBuffer[0].args[0] == 1337L)
    }

}