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

    /** Re-registers an existing inlet under a second owner, as a composite's `flatten` does. */
    private class ReexportCell(inlet: FanInlet<Consumer<String>>) : Cell {
        override val ref: CellRef = CellRef(UUID.randomUUID())
        val reexported = registerPort("reexported", inlet)
        val outlet by output<Consumer<String>>()
    }

    private val context = MessageContext(
        timestamp = Timestamp(UUID.randomUUID(), 1L),
        sourcePort = PortRef.generate(),
    )

    @Test
    fun `inlet dispatch never reads the global registration table`() {
        val cell = InputCell()
        val (consumer, received) = Consumer.buffering<String>()
        cell.inlet.serve(consumer)
        var reads = 0
        val previousObserver = PortIdentities.onTableRead
        PortIdentities.onTableRead = { port ->
            if (port === cell.inlet) reads++
        }

        try {
            cell.inlet.call.provide("external-first")
            cell.inlet.call.provide("external-second")
            CurrentContext.with(context) { cell.inlet.call.provide("reactive-first") }
            CurrentContext.with(context) { cell.inlet.call.provide("reactive-second") }
            reads shouldBe 0
        } finally {
            PortIdentities.onTableRead = previousObserver
        }

        received shouldBe listOf("external-first", "external-second", "reactive-first", "reactive-second")
    }

    @Test
    fun `a re-stamped inlet records entries on its current registration`() {
        val original = InputCell()
        original.inlet.serve(Consumer.buffering<String>().first)
        original.inlet.call.provide("before-restamp")

        val reexport = ReexportCell(original.inlet)
        // Fresh registration: no entry observed yet, so conservatively non-root.
        PortIdentities.hasInboundWavePath(reexport.outlet) shouldBe true

        original.inlet.call.provide("after-restamp")
        // The external entry lands on the new owner's registration.
        PortIdentities.hasInboundWavePath(reexport.outlet) shouldBe false

        CurrentContext.with(context) { original.inlet.call.provide("reactive") }
        PortIdentities.hasInboundWavePath(reexport.outlet) shouldBe true
    }
}
