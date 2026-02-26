package civictech.kernel.germ

import civictech.kernel.germ.port.FanInlet
import civictech.kernel.germ.port.input
import civictech.kernel.germ.port.output
import civictech.kernel.germ.port.use
import civictech.kernel.germ.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.*

class RunnerTest {

    @Test
    fun `managed runner can spawn and connect cells`() {
        val runner = ManagedRunner()
        val runnerApi = runner.managementInlet.call

        val producer = ProducerCell("Hello")
        val consumer = CollectingConsumerCell()

        runnerApi.spawn(producer)
        runnerApi.spawn(consumer)

        // Wait a bit for async spawn
        Thread.sleep(100)

        runnerApi.connect(producer.ref, "outlet", consumer.ref, "inlet")

        // Wait a bit for async connect
        Thread.sleep(100)

        producer.trigger()

        consumer.received shouldBe listOf("Hello")
    }

    @Test
    fun `managed runner can route calls to cell inlets`() {
        val runner = ManagedRunner()
        val runnerApi = runner.managementInlet.call
        val routerApi = runner.routerInlet.call

        val consumer = CollectingConsumerCell()
        runnerApi.spawn(consumer)

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
    fun `managed runner operations run in a different thread`() {
        val runner = ManagedRunner()
        val runnerApi = runner.managementInlet.call
        val mainThread = Thread.currentThread()
        var runnerThread: Thread? = null

        val cell = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            override fun onActivate(ctx: CellContext) {
                runnerThread = Thread.currentThread()
            }
        }

        runnerApi.spawn(cell)
        
        Thread.sleep(200)

        runnerThread shouldNotBe mainThread
        runnerThread?.isVirtual shouldBe true
    }

    @Test
    fun `managed runner prioritizes management calls over router calls`() {
        val runner = ManagedRunner()
        val runnerApi = runner.managementInlet.call
        val routerApi = runner.routerInlet.call

        val consumer = CollectingConsumerCell()
        runnerApi.spawn(consumer)
        Thread.sleep(100)

        val executionOrder = Collections.synchronizedList(mutableListOf<String>())

        // 1. Send a call that blocks the runner thread briefly
        val blockingInlet = object : Consumer<String> {
            override fun provide(input: String) {
                Thread.sleep(500)
                executionOrder += "blocking"
            }
        }
        val blockingCell = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            @Suppress("UNUSED")
            val inlet = FanInlet.create<Consumer<String>>().apply { serve(blockingInlet) }
        }
        runnerApi.spawn(blockingCell)
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
        runnerApi.spawn(managementCell)

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
}
