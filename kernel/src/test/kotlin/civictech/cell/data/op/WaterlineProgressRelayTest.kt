package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.Aggregators
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WaterlineCell
import civictech.cell.data.Windows
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.observe.get
import civictech.cell.observe.observation
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/** Regression for absorb-acks crossing the unary waterline hop. */
class WaterlineProgressRelayTest {

    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(value: Long): Long = value
        private fun readResolve(): Any = LongTime
    }

    private interface LongSetInlet {
        val inlet: Use<SetOps<Long>>
    }

    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())

        fun send(value: Long) {
            outlet.originate {
                propagate(SetDelta(adds = mapOf(value to setOf(Timestamp(UUID.randomUUID(), 1L)))))
            }
        }
    }

    private class Probe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<WaterlineDelta>>())
        val data = mutableListOf<Pair<WaterlineDelta, Timestamp?>>()
        val progress = mutableListOf<Progress>()

        init {
            inlet.onEach { data += it to CurrentContext.get()?.timestamp }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                progress += message as Progress
            }
        }
    }

    @Test
    fun `a managed Progress crosses the waterline exactly once while a raising delivery emits only data`() {
        val host = ManagedHost()
        val source = Source()
        val waterline = WaterlineCell(lateness = Windows.Lateness(LongTime, 5))
        val probe = Probe()
        val management = host.managementInlet.call
        listOf(source, waterline, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", waterline.ref, "inlet")
        management.connect(waterline.ref, "outlet", probe.ref, "inlet")

        val expected = Progress(UUID.fromString("937c965a-4a3f-4210-8059-fb4098876a73"), 37L)
        Protocols.sendDownstream(
            source.outlet.linking.links.single(),
            Protocols.Progress,
            expected,
        )

        probe.progress shouldBe listOf(expected)
        probe.data shouldBe emptyList()

        source.send(10L)

        probe.data.map { it.first } shouldBe listOf(WaterlineDelta(5L))
        probe.progress shouldBe listOf(expected)
    }

    @Test
    fun `silent filtered waves drain an aligned GroupBy observation through its waterline sibling`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Long>()
        val filtered = FilterCell<Long> { it % 2L == 0L }
        val waterline = WaterlineCell(lateness = Windows.Lateness(LongTime, 0))
        val byValue = GroupByCell(
            keyFn = { value: Long -> value },
            aggregator = Aggregators.count<Long>(),
            lateness = Windows.Lateness(LongTime, 0),
            keyTime = { value: Long -> value + 1L },
        )
        val management = host.managementInlet.call
        listOf(source, filtered, waterline, byValue).forEach(management::spawn)
        management.connect(source.ref, "outlet", filtered.ref, "inlet")
        management.connect(filtered.ref, "outlet", waterline.ref, "inlet")
        management.connect(waterline.ref, "outlet", byValue.ref, "waterline")
        management.connect(filtered.ref, "outlet", byValue.ref, "inlet")

        val observation = host.observation {
            set("filtered", filtered.ref)
            count("byValue", byValue.ref)
            set("late", byValue.ref, outletName = "late")
        }
        observation.groups shouldBe setOf("filtered+byValue+late")
        controller.runToIdle()

        val writer = host.lookup<LongSetInlet>(source.ref)!!.inlet.call
        writer.add(10L)
        controller.runToIdle()
        writer.add(11L)
        controller.runToIdle()
        writer.add(13L)
        controller.runToIdle()

        observation.bufferedWaves shouldBe 0
        observation.get<Set<Long>>("filtered") shouldBe setOf(10L)
        observation.get<Map<Long, Long>>("byValue") shouldBe mapOf(10L to 1L)
        observation.get<Set<Long>>("late") shouldBe emptySet()
        observation.close()
    }
}
