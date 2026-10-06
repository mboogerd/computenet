package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Progress
import civictech.cell.data.Aggregators
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/** Regression for G-40/F-15 at the canonical observation edge. */
class ProgressRelayObservationTest {

    private interface StringSetInlet {
        val inlet: Use<SetOps<String>>
    }

    private class ProgressSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Int>>>())
    }

    private class ProgressProbe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, Long>>>())
        val seen = mutableListOf<Progress>()

        init {
            inlet.onEach { }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                seen += message as Progress
            }
        }
    }

    @Test
    fun `relay preserves the exact source and counter through filter and group-by`() {
        val host = ManagedHost()
        val source = ProgressSource()
        val filtered = FilterCell<Int> { true }
        val grouped = GroupByCell(
            keyFn = { value: Int -> value },
            aggregator = Aggregators.count<Int>(),
        )
        val probe = ProgressProbe()
        val management = host.managementInlet.call
        listOf(source, filtered, grouped, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", filtered.ref, "inlet")
        management.connect(filtered.ref, "outlet", grouped.ref, "inlet")
        management.connect(grouped.ref, "outlet", probe.ref, "inlet")

        val expected = Progress(UUID.fromString("4db40459-f6f9-4666-9528-94151265d8a0"), 37L)
        Protocols.sendDownstream(
            source.outlet.linking.links.single(),
            Protocols.Progress,
            expected,
        )

        probe.seen shouldBe listOf(expected)
    }

    @Test
    fun `slotfinder quorum filter group-by observation settles swallowed waves over 20 seeds`() {
        for (seed in 0L until 20L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())
            val sources = List(3) { SetCell<String>() }
            val common = QuorumSetCell<String>(threshold = { liveSources -> liveSources })
            val filtered = FilterCell<String> { slot -> slot.substringAfter('-').toInt() in 9..17 }
            val byDay = GroupByCell(
                keyFn = { slot: String -> slot.substringBefore('-') },
                aggregator = Aggregators.count<String>(),
            )

            val management = host.managementInlet.call
            (sources + listOf(common, filtered, byDay)).forEach(management::spawn)
            sources.forEach { source ->
                management.connect(source.ref, "outlet", common.ref, "inlet")
            }
            management.connect(common.ref, "outlet", filtered.ref, "inlet")
            management.connect(filtered.ref, "outlet", byDay.ref, "inlet")

            val observation = host.observation {
                set("common", common.ref)
                set("filtered", filtered.ref)
                count("byDay", byDay.ref)
            }
            observation.groups shouldBe setOf("common+filtered+byDay")
            controller.runToIdle()

            val writers = sources.map { source ->
                host.lookup<StringSetInlet>(source.ref)!!.inlet.call
            }
            val random = Random(seed)
            fun partiallyDrain() = repeat(random.nextInt(4)) { controller.step() }

            listOf("Tue-14", "Tue-19").forEach { slot ->
                writers.forEach { writer ->
                    writer.add(slot)
                    partiallyDrain()
                }
            }
            writers[0].add("Wed-10") // swallowed by common; Progress must cross filter and group-by
            partiallyDrain()
            writers[1].remove("Tue-14")
            controller.runToIdle()

            withClue("seed $seed") {
                observation.bufferedWaves shouldBe 0
                observation.get<Set<String>>("common") shouldBe setOf("Tue-19")
                observation.get<Set<String>>("filtered") shouldBe emptySet()
                observation.get<Map<String, Long>>("byDay") shouldBe emptyMap()
            }

            observation.close()
        }
    }
}
