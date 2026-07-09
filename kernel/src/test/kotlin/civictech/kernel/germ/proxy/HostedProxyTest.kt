package civictech.kernel.germ.proxy

import civictech.kernel.germ.*
import civictech.kernel.germ.port.FanInlet
import civictech.kernel.germ.port.FanOutlet
import civictech.kernel.germ.port.Subscribe
import civictech.kernel.germ.port.Use
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

interface Mapper1Interface {
    val inlet: Use<Consumer<Int>>
    val outlet: Subscribe<Consumer<String>>
}

interface Mapper2Interface {
    val inlet: Use<Consumer<String>>
    val outlet: Subscribe<Consumer<Long>>
}

class HostedProxyTest {

    @Test
    fun `proxies forward messages to the queue`() {
        val queue = LinkedBlockingQueue<HostedPortInvocation>()
        val host = object : ManagedHost() {
            override fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
                queue.put(hostedInvocation)
            }
        }
        val cellRef = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(MapperCell<Int, String>(f = { it.toString() }, ref = cellRef))

        // 1. HostedCellProxy for MapperCell via ManagedHost
        val cellProxy = host.lookup<Mapper1Interface>(cellRef)!!

        // 2. Accessing the inlet
        val inlet = cellProxy.inlet
        inlet.shouldNotBeNull()

        // 3. Using the inlet's API
        inlet.call.provide(42)

        // 4. Verify message in queue
        val msg = queue.poll(1, TimeUnit.SECONDS)
        msg.shouldNotBeNull()
        msg.cellRef shouldBe cellRef
        msg.portName shouldBe "inlet"
        msg.type shouldBe HostedPortInvocation.Type.PORT_API
        msg.invocation.methodName shouldBe "provide"
        msg.invocation.args shouldBe listOf(42)

        // 5. Test management call (linking)
        val outlet = cellProxy.outlet
        val mockUse = Use.fixed<Consumer<String>>(object : Consumer<String> {
            override fun provide(input: String) {}
        }, civictech.kernel.germ.port.PortRef.generate())

        outlet.linkTo(mockUse)

        val msg2 = queue.poll(1, TimeUnit.SECONDS)
        msg2.shouldNotBeNull()
        msg2.cellRef shouldBe cellRef
        msg2.portName shouldBe "outlet"
        msg2.type shouldBe HostedPortInvocation.Type.PORT_MANAGEMENT
        msg2.invocation.methodName shouldBe "linkTo"
        msg2.invocation.args.size shouldBe 1
    }

    @Test
    fun `cross-host linking works`() {
        val host1 = ManagedHost()
        val host2 = ManagedHost()

        val mapper1 = MapperCell<Int, String>(f = { it.toString() })
        val mapper2 = MapperCell<String, Long>(f = { it.toLong() })

        val ref1 = host1.managementInlet.call.spawn(mapper1)
        val ref2 = host2.managementInlet.call.spawn(mapper2)

        // Create proxies for the cells via ManagedHost
        val proxy1 = host1.lookup<Mapper1Interface>(ref1)!!
        val proxy2 = host2.lookup<Mapper2Interface>(ref2)!!

        // Capture results from the end of the chain
        val results = LinkedBlockingQueue<Long>()
        val collector = object : Consumer<Long> {
            override fun provide(input: Long) {
                results.put(input)
            }
        }

        // Use the proxy to subscribe the collector to mapper2's outlet
        val collectorUse: Use<Consumer<Long>> = Use.fixed(collector, civictech.kernel.germ.port.PortRef.generate())
        proxy2.outlet.linkTo(collectorUse)

        // Link mapper1's outlet to mapper2's inlet across hosts
        // We use the proxies to perform the linking
        proxy1.outlet.linkTo(proxy2.inlet)

        // Push data into mapper1
        proxy1.inlet.call.provide(123)

        // Verify the result propagated through both hosts
        val result = results.poll(5, TimeUnit.SECONDS)
        result shouldBe 123L
    }

    @Test
    fun `cell level metadata access`() {
        val host = ManagedHost()
        val cellRef = host.managementInlet.call.spawn(MapperCell<Int, String>(f = { it.toString() }))
        val cellProxy = host.lookup<Mapper1Interface>(cellRef)!!
        cellProxy.shouldNotBeNull()
    }

    interface CellWithMetadata : Mapper1Interface {
        fun getRef(): CellRef
    }

    @Test
    fun `cell metadata is returned immediately`() {
        val host = ManagedHost()
        val cellRef = host.managementInlet.call.spawn(MapperCell<Int, String>(f = { it.toString() }))
        val cellProxy = host.lookup(cellRef, CellWithMetadata::class.java)!!

        cellProxy.getRef() shouldBe cellRef
    }

    @Test
    fun `differentiation between inlet and outlet`() {
        val queue = LinkedBlockingQueue<HostedPortInvocation>()
        val host = object : ManagedHost() {
            override fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
                queue.put(hostedInvocation)
            }
        }
        val cellRef = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(MapperCell<Int, String>(f = { it.toString() }, ref = cellRef))
        val cellProxy = host.lookup<Mapper1Interface>(cellRef)!!

        // Inlet management call
        cellProxy.inlet.linkFrom(FanOutlet.create<Consumer<Int>>())
        val msgIn = queue.poll(1, TimeUnit.SECONDS)
        msgIn?.portName shouldBe "inlet"

        // Outlet management call
        cellProxy.outlet.subscribe(FanInlet.create<Consumer<String>>())
        val msgOut = queue.poll(1, TimeUnit.SECONDS)
        msgOut?.portName shouldBe "outlet"
    }

    @Test
    fun `port level metadata access`() {
        val host = ManagedHost()
        val cellRef = host.managementInlet.call.spawn(MapperCell<Int, String>(f = { it.toString() }))
        val cellProxy = host.lookup<Mapper1Interface>(cellRef)!!

        // Test ref access on port (ImmediateReturn returns null by current implementation)
        cellProxy.inlet.ref.shouldBeNull()
        cellProxy.outlet.ref.shouldBeNull()
    }

    @Test
    fun `hostedCell returns null for unknown cell`() {
        val host = ManagedHost()
        val cellRef = CellRef(UUID.randomUUID())
        val cellProxy = host.lookup<Mapper1Interface>(cellRef)
        cellProxy.shouldBeNull()
    }
}
