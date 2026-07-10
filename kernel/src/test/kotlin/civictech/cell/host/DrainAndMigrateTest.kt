package civictech.cell.host

import civictech.cell.*
import civictech.cell.data.Propagate
import civictech.cell.port.*
import civictech.cell.proxy.HostedCellProxy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

/**
 * M3.3 (G-5 items 3/4, G-25 start, G-16 remainder): the two-phase drain
 * protocol — accepted-before-close messages flush before deactivation, parked
 * messages replay only after resume/migration, snapshots restore through a
 * real serialization round-trip, per-link order end to end.
 */
class DrainAndMigrateTest {

    interface CounterProxy {
        val inlet: Use<Consumer<Int>>
    }

    class StatefulCounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        val received = mutableListOf<Int>()
        var count = 0
        var activations = 0
        var deactivations = 0
        var restores = 0

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
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

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, PortRef.generate()))
        return letters
    }

    private fun registryApi(registry: LocationRegistry, ref: CellRef): Consumer<Int> =
        (HostedCellProxy.create(ref, registry, CounterProxy::class.java) as CounterProxy).inlet.call

    @Test
    fun `drain flushes accepted messages before deactivation, resume replays parked ones after`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val letters = collectDeadLetters(host)

            val cell = StatefulCounterCell()
            host.managementInlet.call.spawn(cell)
            val api = registryApi(registry, cell.ref)

            val rnd = Random(seed)
            val drainAt = 2 + rnd.nextInt(waves - 2)
            for (n in 1..drainAt) {
                api.provide(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            // drain races the still-flushing sends above; closure is effective once the task runs
            host.managementInlet.call.drainHost()
            controller.runToIdle()

            // everything accepted before closure was processed, then the cell deactivated
            cell.received shouldBe (1..drainAt).toList()
            cell.deactivations shouldBe 1

            // everything sent while drained parks; parking is not an error
            for (n in drainAt + 1..waves) {
                api.provide(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            registry.parkedFor(cell.ref).size shouldBe waves - drainAt
            letters.shouldBeEmpty()

            host.managementInlet.call.resumeHost()
            controller.runToIdle()

            cell.received shouldBe (1..waves).toList()
            cell.activations shouldBe 2
            registry.parkedFor(cell.ref).shouldBeEmpty()
            letters.shouldBeEmpty()
        }
    }

    @Test
    fun `migration under load moves state through a serialization round-trip with zero loss`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val lettersA = collectDeadLetters(hostA)
            val lettersB = collectDeadLetters(hostB)

            val cell = StatefulCounterCell()
            hostA.managementInlet.call.spawn(cell)
            val api = registryApi(registry, cell.ref)

            val rnd = Random(seed)
            val moveAt = 2 + rnd.nextInt(waves - 2)
            for (n in 1..waves) {
                api.provide(n)
                // migration mid-stream, with sends continuing throughout the window
                if (n == moveAt) hostA.managementInlet.call.migrate(hostB.managementInlet)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()

            cell.received shouldBe (1..waves).toList()
            cell.count shouldBe waves
            cell.restores shouldBe 1 // state travelled as a round-tripped snapshot
            cell.deactivations shouldBe 1
            cell.activations shouldBe 2

            hostA.lookup<CounterProxy>(cell.ref).shouldBeNull()
            hostB.lookup<CounterProxy>(cell.ref).shouldNotBeNull()
            registry.parkedFor(cell.ref).shouldBeEmpty()
            lettersA.shouldBeEmpty()
            lettersB.shouldBeEmpty()
        }
    }

    @Test
    fun `a suspending host drains cleanly - a mid-message suspension completes before deactivation`() {
        val controller = SimulationController(seed = 7)
        val registry = LocationRegistry()
        val purple = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING), registry = registry)
        val letters = collectDeadLetters(purple)

        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val cell = object : SuspendingCell {
            override val ref = CellRef(UUID.randomUUID())
            val inlet = registerPort("inlet", FanInlet.create<CoroutineHostTest.SuspendingApi>())

            init {
                inlet.serve(object : CoroutineHostTest.SuspendingApi {
                    override suspend fun process(n: Int) {
                        if (n == 1) gate.await()
                        order += "msg-$n"
                    }
                })
            }

            override fun onDeactivate(ctx: CellContext) {
                order += "deactivate"
            }
        }
        purple.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(cell.ref, registry, CoroutineHostTest.SuspendingCellProxy::class.java)
                as CoroutineHostTest.SuspendingCellProxy).inlet.call

        kotlinx.coroutines.runBlocking { for (n in 1..3) api.process(n) }
        purple.managementInlet.call.drainHost()
        controller.runToIdle()
        order.shouldBeEmpty() // parked mid-message; drain waits for the task, not the reverse

        gate.complete(Unit)
        controller.runToIdle()

        // the suspended message finished, accepted traffic flushed, THEN deactivation
        order shouldBe listOf("msg-1", "msg-2", "msg-3", "deactivate")
        letters.shouldBeEmpty()
    }
}
