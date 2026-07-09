package civictech.kernel.germ

import civictech.kernel.germ.port.*
import civictech.kernel.germ.proxy.Invocation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.*

class HostTest {

    @Test
    fun `managed host can spawn and connect cells`() {
        val host = ManagedHost()
        val hostApi = host.managementInlet.call

        val producer = ProducerCell("Hello")
        val consumer = CollectingConsumerCell()

        hostApi.spawn(producer)
        hostApi.spawn(consumer)

        // Wait a bit for async spawn
        Thread.sleep(100)

        hostApi.connect(producer.ref, "outlet", consumer.ref, "inlet")

        // Wait a bit for async connect
        Thread.sleep(100)

        producer.trigger()

        consumer.received shouldBe listOf("Hello")
    }

    @Test
    fun `managed host can route calls to cell inlets`() {
        val host = ManagedHost()
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val consumer = CollectingConsumerCell()
        hostApi.spawn(consumer)

        // Wait a bit for async spawn
        Thread.sleep(100)

        // Route a call to the consumer's inlet
        val invocation = Invocation.of(Consumer::class.java.methods.find { it.name == "provide" }, arrayOf("Routed Hello"))
        routerApi.route(consumer.ref, "inlet", invocation)

        // Wait a bit for async route
        Thread.sleep(100)

        consumer.received shouldBe listOf("Routed Hello")
    }

    @Test
    fun `managed host operations run in a different thread`() {
        val host = ManagedHost()
        val hostApi = host.managementInlet.call
        val mainThread = Thread.currentThread()
        var hostThread: Thread? = null

        val cell = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            override fun onActivate(ctx: CellContext) {
                hostThread = Thread.currentThread()
            }
        }

        hostApi.spawn(cell)
        
        Thread.sleep(200)

        hostThread shouldNotBe mainThread
        hostThread?.isVirtual shouldBe true
    }

    @Test
    fun `managed host prioritizes management calls over router calls`() {
        val host = ManagedHost()
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val consumer = CollectingConsumerCell()
        hostApi.spawn(consumer)
        Thread.sleep(100)

        val executionOrder = Collections.synchronizedList(mutableListOf<String>())

        // 1. Send a call that blocks the host thread briefly
        val blockingInlet = object : Consumer<String> {
            override fun provide(input: String) {
                Thread.sleep(500)
                executionOrder += "blocking"
            }
        }
        val blockingCell = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            @Suppress("UNUSED")
            val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>()).apply { serve(blockingInlet) }
        }
        hostApi.spawn(blockingCell)
        Thread.sleep(100)

        // Start blocking
        val blockInvocation = Invocation.of(Consumer::class.java.methods.find { it.name == "provide" }, arrayOf("block"))
        routerApi.route(blockingCell.ref, "inlet", blockInvocation)
        Thread.sleep(50) // Ensure it started processing

        // 2. While blocked, queue a router call
        val routerInvocation = Invocation.of(Consumer::class.java.methods.find { it.name == "provide" }, arrayOf("router-call"))
        routerApi.route(consumer.ref, "inlet", routerInvocation)

        // 3. Queue a management call (spawn another cell)
        val managementCell = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            override fun onActivate(ctx: CellContext) {
                executionOrder += "management"
            }
        }
        hostApi.spawn(managementCell)

        // Wait for all to finish
        Thread.sleep(1000)

        // Check overall execution order
        // "blocking" (priority 10, but first) -> "management" (priority 0) -> "router-call" (priority 10)
        executionOrder shouldBe listOf("blocking", "management")
        consumer.received shouldBe listOf("router-call")
    }


    class ProducerCell(val value: String, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Consumer<String>>()

        fun trigger() {
            outlet.use { provide(value) }
        }
    }

    class CollectingConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()
        val received = mutableListOf<String>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }
    }

    interface ProducerInterface {
        val outlet: Subscribe<Consumer<String>>
    }

    interface CollectingConsumerInterface {
        val inlet: Use<Consumer<String>>
    }

    @Test
    fun `can connect cells across different hosts using cell proxy`() {
        val host1 = ManagedHost()
        val host2 = ManagedHost()

        val producer = ProducerCell("Proxy Hello")
        val consumer = CollectingConsumerCell()

        host1.managementInlet.call.spawn(producer)
        host2.managementInlet.call.spawn(consumer)

        // Wait for spawns
        Thread.sleep(100)

        // Use cell proxy to represent the consumer in host2
        val consumerProxy = host2.lookup<CollectingConsumerInterface>(consumer.ref)!!

        // Link producer outlet (in host1) to consumer inlet proxy
        producer.outlet.linkTo(consumerProxy.inlet)

        // Trigger producer
        producer.trigger()

        // Wait for message propagation across hosts
        Thread.sleep(200)

        consumer.received shouldContain "Proxy Hello"
    }

    @Test
    fun `can connect cells using cell proxy outlet`() {
        val host1 = ManagedHost()
        val host2 = ManagedHost()

        val producer = ProducerCell("OutletProxy Hello")
        val consumer = CollectingConsumerCell()

        host1.managementInlet.call.spawn(producer)
        host2.managementInlet.call.spawn(consumer)

        // Wait for spawns
        Thread.sleep(100)

        // Use cell proxy to represent the producer in host1
        val producerProxy = host1.lookup<ProducerInterface>(producer.ref)!!

        // Use cell proxy to represent the consumer in host2
        val consumerProxy = host2.lookup<CollectingConsumerInterface>(consumer.ref)!!

        // Link producer outlet proxy to consumer inlet proxy
        producerProxy.outlet.linkTo(consumerProxy.inlet)

        Thread.sleep(100)

        // Trigger producer
        producer.trigger()

        // Wait for message propagation across hosts
        Thread.sleep(200)

        consumer.received shouldContain "OutletProxy Hello"
    }
}
