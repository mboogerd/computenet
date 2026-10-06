package civictech.demograph.ranking

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.delta.MapDelta
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class MetaRankCellTest {

    @Test
    fun `an effective no-op acknowledges the consumed wave downstream`() {
        val cell = MetaRankCell(sources = listOf("x"))
        val probe = ProgressProbe()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(probe.inlet as LinkFrom<Propagate<MapDelta<String, Double>>>)
        val sourceId = UUID.fromString("2a5ae837-b133-43f9-aad9-c85b975a568e")
        val input = MapDelta(mapOf("a" to 2.0, "b" to 1.0), emptySet())

        propagateInWave(cell, sourceId, counter = 1, input)
        propagateInWave(cell, sourceId, counter = 2, input)

        assertEquals(1, probe.arrivals.size, "the effective no-op must not emit a second data delta")
        assertEquals(listOf(Progress(sourceId, 2)), probe.progress)
    }

    private fun propagateInWave(
        cell: MetaRankCell,
        sourceId: UUID,
        counter: Long,
        input: MapDelta<String, Double>,
    ) {
        val context = MessageContext(Timestamp(sourceId, counter), PortRef.generate())
        CurrentContext.with(context) {
            cell.inlets.getValue("x").call.propagate(input)
        }
    }

    private class ProgressProbe(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val arrivals = mutableListOf<MapDelta<String, Double>>()
        val progress = mutableListOf<Progress>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Double>>>())

        init {
            inlet.onEach { arrivals += it }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                progress += message as Progress
            }
        }
    }
}
