package civictech.cell.graph

import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
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
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.CountSetApi

class GraphDslTest {

    private data class PipelineRefs(
        val writerA: TypedRef<SetApi<String>>,
        val writerB: TypedRef<SetApi<String>>,
        val count: TypedRef<CountSetApi<String>>,
        val byName: Map<String, civictech.cell.CellRef>,
    )

    private fun countsOf(host: ManagedHost, ref: TypedRef<CountSetApi<String>>): MutableList<CounterDelta> {
        val counts = mutableListOf<CounterDelta>()
        host.lookup(ref)!!.outlet.subscribe(Use.fixed(Propagate { counts += it }, PortRef.generate()))
        return counts
    }

    private fun pipelineSpec(host: ManagedHost): Pair<GraphSpec, PipelineRefs> {
        lateinit var refs: PipelineRefs
        val spec = graph(host.managementInlet) {
            val a = spawn("writerA") { ref -> SetCell<String>(ref = ref) }
            val b = spawn("writerB") { ref -> SetCell<String>(ref = ref) }
            val union = spawn("union") { ref -> UnionSetCell<String>(ref = ref) }
            val count = spawn("count") { ref -> CountCell<String>(ref = ref) }
            a linkTo union
            b linkTo union
            union linkTo count
            refs = PipelineRefs(
                writerA = a.refAs(), writerB = b.refAs(), count = count.refAs(),
                byName = listOf(a, b, union, count).associate { it.name to it.ref },
            )
        }
        return spec to refs
    }

    @Test
    fun `a DSL-built graph behaves like a hand-built one`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val (_, refs) = pipelineSpec(host)

        val counts = countsOf(host, refs.count)
        val writerA = host.lookup(refs.writerA)!!.inlet.call
        val writerB = host.lookup(refs.writerB)!!.inlet.call

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

        // ref-only side (replay has no instances): TypedRef constructed directly
        val counts = countsOf(hostB, TypedRef(refs.getValue("count")))
        val writerA = hostB.lookup(TypedRef<SetApi<String>>(refs.getValue("writerA")))!!.inlet.call
        writerA.add("x")
        writerA.remove("x")
        writerA.add("z")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 1 // z only — replayed graph is live
    }
}
