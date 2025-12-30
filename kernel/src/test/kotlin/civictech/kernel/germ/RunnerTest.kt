package civictech.kernel.germ

import civictech.kernel.germ.port.input
import civictech.kernel.germ.port.output
import civictech.kernel.germ.port.use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

class RunnerTest {

    @Test
    fun `managed runner can spawn and connect cells`() {
        val runner = ManagedRunner()
        val runnerApi = runner.managementInlet.call

        val producer = ProducerCell("Hello")
        val consumer = CollectingConsumerCell()

        val producerRef = runnerApi.spawn(producer)
        val consumerRef = runnerApi.spawn(consumer)

        runnerApi.connect(producerRef, "outlet", consumerRef, "inlet")

        producer.trigger()

        consumer.received shouldBe listOf("Hello")
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
