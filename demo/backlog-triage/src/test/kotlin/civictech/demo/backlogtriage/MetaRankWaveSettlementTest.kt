package civictech.demo.backlogtriage

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.delta.MapDelta
import civictech.cell.graph.lookup
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.testkit.SimWorld
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals

class MetaRankWaveSettlementTest {

    @Test
    fun `real triage pipeline never settles a meta wave that also carries data`() {
        for (seed in 0L until 20L) {
            val world = SimWorld(seed)
            val refs = TriagePipeline.build(world.host)
            val probe = ProgressProbe()
            val management = world.host.managementInlet.call
            management.spawn(probe)
            val connected = management.connect(refs.ratings.getValue("meta"), "outlet", probe.ref, "inlet")
            check(connected !is LinkResult.Rejected) { "meta probe link rejected: $connected" }
            world.runToIdle()
            probe.events.clear()

            val preferences = world.host.lookup(refs.prefs)!!.inlet.call
            val random = Random(seed)
            repeat(20) { step ->
                val winner = random.nextInt(8)
                var loser = random.nextInt(7)
                if (loser >= winner) loser++
                preferences.add(Pref("agent-$step", "feature-$winner", "feature-$loser"))
                world.runToIdle()
            }

            val byWave = probe.events.groupBy(Event::timestamp)
            val mixedWaves = byWave.filterValues { events ->
                events.any { it is Event.Data } && events.any { it is Event.Settled }
            }.keys
            val progressBeforeData = byWave.filterValues { events ->
                val firstProgress = events.indexOfFirst { it is Event.Settled }
                val lastData = events.indexOfLast { it is Event.Data }
                firstProgress >= 0 && lastData > firstProgress
            }.keys
            assertEquals(emptySet<Timestamp>(), mixedWaves, "seed $seed emitted both Progress and data")
            assertEquals(emptySet<Timestamp>(), progressBeforeData, "seed $seed emitted Progress before same-wave data")
        }
    }

    private sealed interface Event {
        val timestamp: Timestamp

        data class Data(override val timestamp: Timestamp) : Event

        data class Settled(val progress: Progress) : Event {
            override val timestamp: Timestamp = Timestamp(progress.sourceId, progress.thru)
        }
    }

    private class ProgressProbe(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val events = mutableListOf<Event>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Double>>>())

        init {
            inlet.onEach { events += Event.Data(requireNotNull(CurrentContext.get()).timestamp) }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                events += Event.Settled(message as Progress)
            }
        }
    }
}
