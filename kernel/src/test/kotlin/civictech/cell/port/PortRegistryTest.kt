package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class PortRegistryTest {

    class DelegateCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()

        @Suppress("unused")
        private val secret by input<Consumer<Int>>()
    }

    class ExplicitCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())
    }

    @Test
    fun `delegate-declared ports register eagerly under the property name`() {
        val cell = DelegateCell()
        PortRegistry.of(cell)["inlet"] shouldBeSameInstanceAs cell.inlet
        PortRegistry.of(cell).names() shouldBe setOf("inlet", "secret")
    }

    @Test
    fun `private delegate ports are resolvable without reflection hacks`() {
        val cell = DelegateCell()
        (PortRegistry.of(cell)["secret"] is FanInlet<*>) shouldBe true
    }

    @Test
    fun `explicitly-registered ports resolve by name`() {
        val cell = ExplicitCell()
        PortRegistry.of(cell)["inlet"] shouldBeSameInstanceAs cell.inlet
    }

    @Test
    fun `duplicate port names are rejected`() {
        val owner = Any()
        owner.registerPort("inlet", FanInlet.create<Consumer<String>>())
        assertThrows<IllegalArgumentException> {
            owner.registerPort("inlet", FanInlet.create<Consumer<String>>())
        }
    }

    @Test
    fun `host resolves both declaration styles through the registry`() {
        val controller = civictech.cell.host.SimulationController()
        val host = civictech.cell.host.ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val received = mutableListOf<String>()
        val delegateCell = DelegateCell()
        val explicitCell = ExplicitCell()
        hostApi.spawn(delegateCell)
        hostApi.spawn(explicitCell)

        val sink = object : Consumer<String> {
            override fun provide(input: String) {
                received += input
            }
        }
        delegateCell.inlet.serve(sink)
        explicitCell.inlet.serve(sink)

        val provide = Consumer::class.java.methods.find { it.name == "provide" }
        routerApi.route(delegateCell.ref, "inlet", civictech.cell.proxy.Invocation.of(provide, arrayOf("to-delegate")))
        routerApi.route(explicitCell.ref, "inlet", civictech.cell.proxy.Invocation.of(provide, arrayOf("to-explicit")))
        controller.runToIdle()

        received shouldBe listOf("to-delegate", "to-explicit")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class UnusedCtx(ctx: CellContext)
}
