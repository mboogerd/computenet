package civictech.kernel.germ

import civictech.kernel.germ.port.input
import civictech.kernel.germ.port.output
import civictech.kernel.germ.port.use
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
