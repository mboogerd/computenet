package civictech.cell.graph

import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class TypedLinkTest {

    @Test
    fun `link lowers to the identical ConnectStep and the spec replays`() {
        val controller = SimulationController(seed = 3)
        val hostA = ManagedHost(scheduler = controller.scheduler())
        val stringSpec = graph(hostA.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref = ref) }
            val u = spawn("u") { ref -> UnionSetCell<String>(ref = ref) }
            connect(a, "outlet", u, "inlet")
        }

        val hostB = ManagedHost(scheduler = controller.scheduler())
        val typedSpec = graph(hostB.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref = ref) }
            val u = spawn("u") { ref -> UnionSetCell<String>(ref = ref) }
            link(a.cell.outlet, u.cell.inlet)
        }

        typedSpec.steps.filterIsInstance<ConnectStep>() shouldBe
            stringSpec.steps.filterIsInstance<ConnectStep>()

        // graphs-as-data acceptance: serialization round trip replays onto a third host
        val bytes = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(typedSpec) } }
            .toByteArray()
        val revived = ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as GraphSpec
        val hostC = ManagedHost(scheduler = controller.scheduler())
        val refs = revived.applyTo(hostC.managementInlet)
        refs.keys shouldBe setOf("a", "u")
    }

    @Test
    fun `typed-linked cells flow data`() {
        val controller = SimulationController(seed = 4)
        val host = ManagedHost(scheduler = controller.scheduler())
        val collected = mutableListOf<SetDelta<String>>()
        graph(host.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref = ref) }
            val u = spawn("u") { ref -> UnionSetCell<String>(ref = ref) }
            link(a.cell.outlet, u.cell.inlet)
            u.cell.outlet.subscribe(Use.fixed(Propagate { collected += it }, PortRef.generate()))
            a.cell.inlet.call.add("x")
        }
        controller.runToIdle()
        collected.shouldNotBeEmpty()
    }

    @Test
    fun `addressOf is null for a port with no cell owner`() {
        PortRegistry.addressOf(FanInlet.create<SetOps<String>>()).shouldBeNull()
    }

    @Test
    fun `link rejects a cell foreign to this builder`() {
        val controller = SimulationController(seed = 5)
        val host = ManagedHost(scheduler = controller.scheduler())
        val foreign = SetCell<String>() // constructed, never spawned in the builder
        assertThrows<IllegalArgumentException> {
            graph(host.managementInlet) {
                val u = spawn("u") { ref -> UnionSetCell<String>(ref = ref) }
                link(foreign.outlet, u.cell.inlet)
            }
        }
    }

    // payload mismatch is a COMPILE error — kept as a comment (kotlin-compile-testing
    // lives in :gen, which cannot depend on :kernel; the type system is the enforcement):
    //   link(setCell.outlet /* Propagate<SetDelta<String>> */, mapHub.inlet /* Propagate<MapDelta<..>> */)
}
