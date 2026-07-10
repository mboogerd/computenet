package civictech.cell.graph

import civictech.cell.data.CountCell
import civictech.cell.data.CounterDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class GraphDslTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface CounterOutletProxy {
        val outlet: civictech.cell.port.Subscribe<Propagate<CounterDelta>>
    }

    private fun countsOf(host: ManagedHost, ref: civictech.cell.CellRef): MutableList<CounterDelta> {
        val counts = mutableListOf<CounterDelta>()
        host.lookup<CounterOutletProxy>(ref)!!.outlet.subscribe(Use.fixed(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) {
                counts += value
            }
        }, PortRef.generate()))
        return counts
    }

    private fun pipelineSpec(host: ManagedHost): Pair<GraphSpec, Map<String, civictech.cell.CellRef>> {
        val handles = mutableMapOf<String, civictech.cell.CellRef>()
        val spec = graph(host.managementInlet) {
            val a = spawn("writerA") { SetCell<String>() }
            val b = spawn("writerB") { SetCell<String>() }
            val union = spawn("union") { UnionSetCell<String>() }
            val count = spawn("count") { CountCell<String>() }
            a linkTo union
            b linkTo union
            union linkTo count
            listOf(a, b, union, count).forEach { handles[it.name] = it.ref }
        }
        return spec to handles
    }

    @Test
    fun `a DSL-built graph behaves like a hand-built one`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val (_, handles) = pipelineSpec(host)

        val counts = countsOf(host, handles.getValue("count"))
        val writerA = host.lookup<SetInletProxy>(handles.getValue("writerA"))!!.inlet.call
        val writerB = host.lookup<SetInletProxy>(handles.getValue("writerB"))!!.inlet.call

        writerA.add("x")
        writerB.add("x") // second tag, same element: no membership change
        writerB.add("y")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 2
    }

    @Test
    fun `a GraphSpec survives serialization and replays onto a fresh host`() {
        val controller = SimulationController(seed = 2)
        val hostA = ManagedHost(scheduler = controller.scheduler())
        val (spec, _) = pipelineSpec(hostA)

        val bytes = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(spec) } }
            .toByteArray()
        val revived = ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as GraphSpec
        // topology data survives verbatim (factories serialize but don't compare equal)
        revived.steps.filterIsInstance<ConnectStep>() shouldBe spec.steps.filterIsInstance<ConnectStep>()

        val hostB = ManagedHost(scheduler = controller.scheduler())
        val refs = revived.applyTo(hostB.managementInlet)
        refs.keys shouldBe setOf("writerA", "writerB", "union", "count")

        val counts = countsOf(hostB, refs.getValue("count"))
        val writerA = hostB.lookup<SetInletProxy>(refs.getValue("writerA"))!!.inlet.call
        writerA.add("x")
        writerA.remove("x")
        writerA.add("z")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 1 // z only — replayed graph is live
    }
}
