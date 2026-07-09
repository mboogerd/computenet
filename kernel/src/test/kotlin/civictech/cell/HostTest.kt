package civictech.cell

import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.*
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.*

class HostTest {

    @Test
    fun `managed host can spawn and connect cells`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val producer = ProducerCell("Hello")
        val consumer = CollectingConsumerCell()

        hostApi.spawn(producer)
        hostApi.spawn(consumer)

        hostApi.connect(producer.ref, "outlet", consumer.ref, "inlet")
        controller.runToIdle()

        producer.trigger()
        controller.runToIdle()

        consumer.received shouldBe listOf("Hello")
    }

    @Test
    fun `managed host can route calls to cell inlets`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val consumer = CollectingConsumerCell()
        hostApi.spawn(consumer)

        val invocation = Invocation.of(Consumer::class.java.methods.find { it.name == "provide" }, arrayOf("Routed Hello"))
        routerApi.route(consumer.ref, "inlet", invocation)
        controller.runToIdle()

        consumer.received shouldBe listOf("Routed Hello")
    }

    @Test
    fun `managed host operations run in a different virtual thread`() {
        // Intentionally uses the threaded scheduler — this test is about it.
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

        // spawn awaits activation, so hostThread is set when it returns
        hostApi.spawn(cell)

        hostThread shouldNotBe mainThread
        hostThread?.isVirtual shouldBe true
    }

    @Test
    fun `managed host prioritizes management calls over router calls`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val executionOrder = mutableListOf<String>()

        val consumer = CollectingConsumerCell()
        hostApi.spawn(consumer)

        // Park a router call (priority 10) — nothing drains until we say so
        val routerInvocation = Invocation.of(Consumer::class.java.methods.find { it.name == "provide" }, arrayOf("router-call"))
        routerApi.route(consumer.ref, "inlet", routerInvocation)

        // A management call (priority 0) queued afterwards must still run first:
        // spawn's await drives tasks in (priority, sequence) order and stops at
        // its own completion — the parked router call stays parked.
        val managementCell = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            override fun onActivate(ctx: CellContext) {
                executionOrder += "management"
            }
        }
        hostApi.spawn(managementCell)
        executionOrder += "spawn-returned"

        controller.runToIdle()
        executionOrder += "router:" + consumer.received.single()

        executionOrder shouldBe listOf("management", "spawn-returned", "router:router-call")
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
        val controller = SimulationController()
        val host1 = ManagedHost(scheduler = controller.scheduler())
        val host2 = ManagedHost(scheduler = controller.scheduler())

        val producer = ProducerCell("Proxy Hello")
        val consumer = CollectingConsumerCell()

        host1.managementInlet.call.spawn(producer)
        host2.managementInlet.call.spawn(consumer)

        val consumerProxy = host2.lookup<CollectingConsumerInterface>(consumer.ref)!!

        producer.outlet.linkTo(consumerProxy.inlet)
        producer.trigger()
        controller.runToIdle()

        consumer.received shouldContain "Proxy Hello"
    }

    @Test
    fun `can connect cells using cell proxy outlet`() {
        val controller = SimulationController()
        val host1 = ManagedHost(scheduler = controller.scheduler())
        val host2 = ManagedHost(scheduler = controller.scheduler())

        val producer = ProducerCell("OutletProxy Hello")
        val consumer = CollectingConsumerCell()

        host1.managementInlet.call.spawn(producer)
        host2.managementInlet.call.spawn(consumer)

        val producerProxy = host1.lookup<ProducerInterface>(producer.ref)!!
        val consumerProxy = host2.lookup<CollectingConsumerInterface>(consumer.ref)!!

        producerProxy.outlet.linkTo(consumerProxy.inlet)
        controller.runToIdle()

        producer.trigger()
        controller.runToIdle()

        consumer.received shouldContain "OutletProxy Hello"
    }
}
