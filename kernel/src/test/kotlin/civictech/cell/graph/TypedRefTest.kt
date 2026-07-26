package civictech.cell.graph

import civictech.cell.data.CounterDelta
import civictech.cell.data.CountCell
import civictech.cell.data.CountSetApi
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TypedRefTest {

    private interface NotImplemented

    @Test
    fun `refAs verifies the built cell implements the API at graph build`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        graph(host.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref = ref) }
            a.refAs<SetApi<String>>() // fine
            assertThrows<IllegalArgumentException> { a.refAs<NotImplemented>() }
        }
    }

    @Test
    fun `lookup by TypedRef round-trips inlet calls and outlet navigation`() {
        val controller = SimulationController(seed = 12)
        val host = ManagedHost(scheduler = controller.scheduler())
        lateinit var writer: TypedRef<SetApi<String>>
        lateinit var counter: TypedRef<CountSetApi<String>>
        graph(host.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref = ref) }
            val c = spawn("count") { ref -> CountCell<String>(ref = ref) }
            link(a.cell.outlet, c.cell.inlet)
            writer = a.refAs()
            counter = c.refAs()
        }

        val counts = mutableListOf<CounterDelta>()
        host.lookup(counter)!!.outlet.subscribe(Use.fixed(Propagate { counts += it }, PortRef.generate()))
        host.lookup(writer)!!.inlet.call.add("x")
        host.lookup(writer)!!.inlet.call.add("y")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 2
    }
}
