package civictech.cell.port

import civictech.cell.Consumer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FanInletInterposeTest {
    @Test
    fun `interpose delivers to the wrapper first and its current-root delegate second`() {
        val events = mutableListOf<String>()
        val inlet = FanInlet.create<Consumer<String>>()
        inlet.serve(consumer { events += "root:$it" })

        inlet.interpose { delegate ->
            consumer { value ->
                events += "wrapper:$value"
                delegate.provide(value)
            }
        }

        inlet.call.provide("write")
        events shouldBe listOf("wrapper:write", "root:write")
    }

    @Test
    fun `interpose requires an existing root and leaves the parked tail untouched`() {
        val received = mutableListOf<String>()
        val inlet = FanInlet.create<Consumer<String>>()
        inlet.call.provide("parked")

        shouldThrow<IllegalArgumentException> {
            inlet.interpose { it }
        }.message shouldBe "FanInlet.interpose requires an existing active root"

        inlet.serve(consumer(received::add))
        received shouldBe listOf("parked")
    }

    private fun consumer(body: (String) -> Unit): Consumer<String> = object : Consumer<String> {
        override fun provide(input: String) = body(input)
    }
}
