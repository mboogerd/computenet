package civictech.cell.host

import civictech.cell.*
import civictech.cell.data.Propagate
import civictech.cell.port.*
import civictech.cell.proxy.HostedCellProxy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

/**
 * M3.5 (G-26 remainder): per-cell supervision policies. Every policy still
 * dead-letters (observability is not a policy); RESTART restores the
 * spawn-time checkpoint; SUSPEND parks per-cell and replays in order on
 * resume; PROPAGATE is today's behavior, unchanged.
 */
class SupervisionTest {

    interface CounterProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** Counts everything it accepts; a negative input throws mid-message. */
    class FragileCounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        val received = mutableListOf<Int>()
        var count = 0
        var restores = 0
        var activations = 0
        var deactivations = 0

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input < 0) throw IllegalStateException("poison: $input")
                    count++
                    received += input
                }
            })
        }

        override fun onActivate(ctx: CellContext) {
            activations++
        }

        override fun onDeactivate(ctx: CellContext) {
            deactivations++
        }

        override fun snapshot(): Serializable = count
        override fun restore(state: Serializable) {
            count = state as Int
            restores++
        }
    }

    private class Fixture {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        val cell = FragileCounterCell()
        val api: Consumer<Int>

        init {
            host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    letters += value
                }
            }, PortRef.generate()))
            host.managementInlet.call.spawn(cell)
            api = (HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy).inlet.call
        }
    }

    @Test
    fun `PROPAGATE - the default - dead-letters and keeps processing`() {
        val f = Fixture()
        listOf(1, 2, -1, 3).forEach(f.api::provide)
        f.controller.runToIdle()

        f.cell.received shouldBe listOf(1, 2, 3)
        f.letters.size shouldBe 1
        f.letters[0].cause!!.message shouldContain "poison"
        f.cell.restores shouldBe 0
        f.cell.deactivations shouldBe 0
    }

    @Test
    fun `RESTART recovers the spawn checkpoint and keeps processing`() {
        val f = Fixture()
        f.host.managementInlet.call.supervise(f.cell.ref, SupervisionPolicy.RESTART)
        listOf(1, 2, -1, 3, 4).forEach(f.api::provide)
        f.controller.runToIdle()

        // failure dead-lettered, cell bounced through deactivate/activate, count restored to spawn state
        f.letters.size shouldBe 1
        f.cell.deactivations shouldBe 1
        f.cell.activations shouldBe 2
        f.cell.restores shouldBe 1
        f.cell.received shouldBe listOf(1, 2, 3, 4) // no message lost besides the poison
        f.cell.count shouldBe 2 // spawn checkpoint (0) + the two post-restart messages
    }

    @Test
    fun `SUSPEND parks subsequent traffic and resume replays it in order`() {
        val f = Fixture()
        f.host.managementInlet.call.supervise(f.cell.ref, SupervisionPolicy.SUSPEND)
        listOf(1, -1, 2, 3).forEach(f.api::provide)
        f.controller.runToIdle()

        // after the poison the cell is sidelined: 2 and 3 parked, not processed, not dead-lettered
        f.cell.received shouldBe listOf(1)
        f.letters.size shouldBe 1

        f.host.managementInlet.call.resume(f.cell.ref)
        f.controller.runToIdle()
        f.cell.received shouldBe listOf(1, 2, 3)
        f.letters.size shouldBe 1

        // new traffic flows again
        f.api.provide(4)
        f.controller.runToIdle()
        f.cell.received shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun `despawning a suspended cell dead-letters its parked traffic instead of dropping it`() {
        val f = Fixture()
        f.host.managementInlet.call.supervise(f.cell.ref, SupervisionPolicy.SUSPEND)
        listOf(-1, 1, 2).forEach(f.api::provide)
        f.controller.runToIdle()
        f.cell.received shouldBe emptyList<Int>()

        f.host.managementInlet.call.despawn(f.cell.ref)
        f.controller.runToIdle()

        // 1 poison + 2 parked-then-orphaned
        f.letters.size shouldBe 3
        f.letters.drop(1).forEach { it.description shouldContain "left the host while suspended" }
    }
}
