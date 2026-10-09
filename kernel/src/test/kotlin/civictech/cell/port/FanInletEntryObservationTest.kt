package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class FanInletEntryObservationTest {
    private class InputCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()
    }

    @Test
    fun `repeated entry observations do not return to the global registration table`() {
        val cell = InputCell()
        val (consumer, received) = Consumer.buffering<String>()
        cell.inlet.serve(consumer)
        var lookups = 0
        val previousObserver = PortIdentities.onEntryObservationLookup
        PortIdentities.onEntryObservationLookup = { port ->
            if (port === cell.inlet) lookups++
        }

        try {
            cell.inlet.call.provide("external-first")
            lookups shouldBe 1
            cell.inlet.call.provide("external-second")
            lookups shouldBe 1

            val context = MessageContext(
                timestamp = Timestamp(UUID.randomUUID(), 1L),
                sourcePort = PortRef.generate(),
            )
            CurrentContext.with(context) { cell.inlet.call.provide("reactive-first") }
            lookups shouldBe 1
            CurrentContext.with(context) { cell.inlet.call.provide("reactive-second") }
            lookups shouldBe 1
        } finally {
            PortIdentities.onEntryObservationLookup = previousObserver
        }

        received shouldBe listOf("external-first", "external-second", "reactive-first", "reactive-second")
    }
}
