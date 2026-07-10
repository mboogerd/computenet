package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.input
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M8.1 (G-28): hosts hosting hosts — subtree quotas walk every ancestor, and
 * draining a parent cascades to its children (a sandboxed subtree cannot
 * outlive or outgrow its enclosure).
 */
class HierarchyTest {

    class FlagCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        var active = false

        @Suppress("unused")
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            active = true
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {}
            })
        }

        override fun onDeactivate(ctx: CellContext) {
            active = false
        }
    }

    @Test
    fun `subtree quota rejects spawns anywhere below the budgeted host`() {
        val controller = SimulationController()
        val parent = ManagedHost(scheduler = controller.scheduler(), quota = 3)
        val child = ManagedHost(scheduler = controller.scheduler())

        parent.managementInlet.call.spawn(FlagCell()) // 1
        parent.managementInlet.call.spawn(child) // 2 — the child host is a cell too
        child.managementInlet.call.spawn(FlagCell()) // 3 (counts against the parent's budget)

        shouldThrow<IllegalStateException> { child.managementInlet.call.spawn(FlagCell()) }
        shouldThrow<IllegalStateException> { parent.managementInlet.call.spawn(FlagCell()) }

        // the child's own quota binds too, independently of the parent's
        val strictChild = ManagedHost(scheduler = controller.scheduler(), quota = 0)
        shouldThrow<IllegalStateException> { strictChild.managementInlet.call.spawn(FlagCell()) }
    }

    @Test
    fun `draining a parent cascades to child hosts`() {
        val controller = SimulationController()
        val parent = ManagedHost(scheduler = controller.scheduler())
        val child = ManagedHost(scheduler = controller.scheduler())
        parent.managementInlet.call.spawn(child)

        val inner = FlagCell()
        child.managementInlet.call.spawn(inner)
        controller.runToIdle()
        inner.active.shouldBeTrue()

        parent.managementInlet.call.drainHost()
        controller.runToIdle()

        inner.active shouldBe false // the child's cells deactivated with the parent
        // and the child's intake is closed: data sends fail fast (spec 33)
        val provide = Consumer::class.java.methods.first { it.name == "provide" }
        shouldThrow<IntakeClosedException> {
            child.enqueueHostedInvocation(
                HostedPortInvocation(
                    inner.ref, "inlet", HostedPortInvocation.Type.PORT_API,
                    Invocation.of(provide, arrayOf("late")),
                )
            )
        }
    }
}
