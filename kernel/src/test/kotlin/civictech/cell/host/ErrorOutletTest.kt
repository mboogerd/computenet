package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.verify.InvariantCell
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.*

/**
 * The G-26 remainder: a cell declaring [ErrorReporting] receives its own
 * invocation failures on its error outlet — under every supervision policy —
 * so errors flow through visible topology to a consumer (e.g. an invariant
 * cell) instead of stopping at the host's dead-letter log.
 */
class ErrorOutletTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    interface InletProxy {
        val inlet: Use<Consumer<Int>>
    }

    class FailingCell(
        clazz: Class<Consumer<Int>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, ErrorReporting {
        val inlet = registerPort("inlet", FanInlet(clazz))
        override val errorOutlet = registerPort("errorOutlet", FanOutlet.create<Propagate<CellError>>())
        val processed = mutableListOf<Int>()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    require(input >= 0) { "negative input: $input" }
                    processed += input
                }
            })
        }
    }

    private fun runWithPolicy(policy: SupervisionPolicy?): Pair<FailingCell, MutableList<CellError>> {
        val controller = SimulationController(seed = 5)
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = FailingCell(consumerInt)
        host.managementInlet.call.spawn(cell)
        policy?.let { host.managementInlet.call.supervise(cell.ref, it) }

        val errors = mutableListOf<CellError>()
        cell.errorOutlet.subscribe(Use.fixed(object : Propagate<CellError> {
            override fun propagate(value: CellError) {
                errors += value
            }
        }, PortRef.generate()))

        val routed = host.lookup<InletProxy>(cell.ref)!!.inlet.call
        routed.provide(1)
        routed.provide(-1) // fails
        controller.runToIdle()
        return cell to errors
    }

    @Test
    fun `every supervision policy emits the failure on the cell's error outlet`() {
        for (policy in listOf(null, SupervisionPolicy.PROPAGATE, SupervisionPolicy.RESTART, SupervisionPolicy.SUSPEND)) {
            val (cell, errors) = runWithPolicy(policy)
            errors.size shouldBe 1
            errors[0].cellRef shouldBe cell.ref
            errors[0].cause.shouldBeInstanceOf<IllegalArgumentException>()
        }
    }

    @Test
    fun `an invariant cell is a natural error consumer`() {
        val controller = SimulationController(seed = 5)
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = FailingCell(consumerInt)
        host.managementInlet.call.spawn(cell)

        val noErrors = InvariantCell.observing<CellError>("no cell errors") {
            "cell ${it.cellRef} failed: ${it.cause.message}"
        }
        val violations = mutableListOf<civictech.cell.verify.Violation>()
        noErrors.violations.subscribe(Use.fixed(object : Propagate<civictech.cell.verify.Violation> {
            override fun propagate(value: civictech.cell.verify.Violation) {
                violations += value
            }
        }, PortRef.generate()))
        @Suppress("UNCHECKED_CAST")
        cell.errorOutlet.linkTo(noErrors.inlet as LinkFrom<Propagate<CellError>>)

        host.lookup<InletProxy>(cell.ref)!!.inlet.call.provide(-7)
        controller.runToIdle()

        violations.size shouldBe 1
        violations[0].message shouldBe "cell ${cell.ref} failed: negative input: -7"
    }
}
