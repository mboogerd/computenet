package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T05 finding 5: `clearSupervision` dead-lettered + counted the suspended
 * queue and the attention-parked queue, but a `FanInlet`'s ACTIVATE-tier cold
 * tail — invocations that arrived before a handler was installed (spec
 * 10/15, 10/13 §Admission vs activation) — simply vanished with the cell
 * object on despawn: no dead letter, no counter, no exclusive discharge.
 */
class DespawnColdInletDrainTest {

    interface OwnedSinkApi {
        fun accept(value: Owned<String>)
    }

    /** Registers its inlet but deliberately never [FanInlet.serve]s it — stays cold. */
    private class ColdInletCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<OwnedSinkApi>())
    }

    @Test
    fun `despawn drains a cold FanInlet's parked tail — dead-lettered, counted, Owned discharged`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) { letters += value }
            }, PortRef.generate()),
        )

        val cell = ColdInletCell()
        host.managementInlet.call.spawn(cell)

        // deliver before serve: the invocation parks in the cold ACTIVATE tail
        val method = OwnedSinkApi::class.java.methods.single { it.name == "accept" }
        val owned = Owned("payload")
        host.routerInlet.call.route(cell.ref, "inlet", Invocation.of(method, arrayOf(owned)))
        controller.runToIdle()

        host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 0
        letters.shouldBeEmpty()

        host.managementInlet.call.despawn(cell.ref)
        controller.runToIdle()

        host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 1
        letters.size shouldBe 1
        letters[0].description shouldContain "inlet"
        // frozen (discharged) by dead-letter sanitization, not leaked untouched:
        // a second take() is a genuine use-after-move if it hadn't been
        shouldThrow<IllegalStateException> { owned.take() }
    }
}
