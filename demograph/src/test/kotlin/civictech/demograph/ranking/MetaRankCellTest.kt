package civictech.demograph.ranking

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
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

    @Test
    fun `same-wave data suppresses progress after every inlet settles in either order`() {
        for (effectiveFirst in listOf(false, true)) {
            val cell = MetaRankCell(sources = listOf("x", "y"))
            val x = MapSource()
            val y = MapSource()
            val probe = ProgressProbe()
            link(x, cell, "x")
            link(y, cell, "y")
            @Suppress("UNCHECKED_CAST")
            cell.outlet.linkTo(probe.inlet as LinkFrom<Propagate<MapDelta<String, Double>>>)
            val sourceId = UUID.fromString("0b973817-a0f3-46fc-aa15-4a560586e06d")

            x.send(sourceId, 1, MapDelta(mapOf("a" to 3.0, "b" to 1.0), emptySet()))
            y.send(sourceId, 1, MapDelta(mapOf("a" to 3.0, "b" to 1.0), emptySet()))
            probe.events.clear()

            val rankPreserving = { x.send(sourceId, 2, MapDelta(mapOf("a" to 4.0), emptySet())) }
            val rankChanging = { y.send(sourceId, 2, MapDelta(mapOf("b" to 5.0), emptySet())) }
            if (effectiveFirst) {
                rankChanging()
                rankPreserving()
            } else {
                rankPreserving()
                rankChanging()
            }

            val expected: List<Event> = listOf(Event.Data(Timestamp(sourceId, 2)))
            assertEquals(
                expected,
                probe.events,
                "effectiveFirst=$effectiveFirst: a wave that emitted data must never also acknowledge Progress",
            )
        }
    }

    @Test
    fun `rating cell acknowledges a tag-only no-op wave`() {
        val source = PreferenceSource()
        val cell = RatingCell(MeanOfSigns())
        val probe = ProgressProbe()
        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<PairwisePreference>>>)
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(probe.inlet as LinkFrom<Propagate<MapDelta<String, Double>>>)
        val sourceId = UUID.fromString("cf13dc99-70b3-4604-bbdf-995ad300021e")
        val preference = PairwisePreference("agent", "a", "b")

        source.send(sourceId, 1, SetDelta(adds = mapOf(preference to setOf(Timestamp(sourceId, 11)))))
        probe.events.clear()
        source.send(sourceId, 2, SetDelta(adds = mapOf(preference to setOf(Timestamp(sourceId, 12)))))

        val expected: List<Event> = listOf(Event.Settled(Progress(sourceId, 2)))
        assertEquals(
            expected,
            probe.events,
            "a duplicate causal tag changes no rating but must settle the downstream meta inlet",
        )
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

    @Suppress("UNCHECKED_CAST")
    private fun link(source: MapSource, cell: MetaRankCell, name: String) {
        source.outlet.linkTo(cell.inlets.getValue(name) as LinkFrom<Propagate<MapDelta<String, Double>>>)
    }

    private sealed interface Event {
        data class Data(val timestamp: Timestamp) : Event
        data class Settled(val progress: Progress) : Event
    }

    private class MapSource(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Double>>>())

        fun send(sourceId: UUID, counter: Long, delta: MapDelta<String, Double>) {
            CurrentContext.with(MessageContext(Timestamp(sourceId, counter), PortRef.generate())) {
                outlet.call.propagate(delta)
            }
        }
    }

    private class PreferenceSource(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<PairwisePreference>>>())

        fun send(sourceId: UUID, counter: Long, delta: SetDelta<PairwisePreference>) {
            CurrentContext.with(MessageContext(Timestamp(sourceId, counter), PortRef.generate())) {
                outlet.call.propagate(delta)
            }
        }
    }

    private class ProgressProbe(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val arrivals = mutableListOf<MapDelta<String, Double>>()
        val progress = mutableListOf<Progress>()
        val events = mutableListOf<Event>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Double>>>())

        init {
            inlet.onEach {
                arrivals += it
                events += Event.Data(requireNotNull(CurrentContext.get()).timestamp)
            }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                val settled = message as Progress
                progress += settled
                events += Event.Settled(settled)
            }
        }
    }
}
