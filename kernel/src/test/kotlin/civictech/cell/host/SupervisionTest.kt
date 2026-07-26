package civictech.cell.host

import civictech.cell.*
import civictech.cell.Propagate
import civictech.cell.port.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

    // --- W2.5 (G-46): exclusive payloads off the happy path ---------------

    interface OwnedCounterProxy {
        val inlet: Use<Consumer<Owned<String>>>
    }

    interface LeasedCounterProxy {
        val inlet: Use<Consumer<Leased<String>>>
    }

    /** Throws on its Nth call, holding an exclusive payload live at the moment of failure. */
    class FragileOwnedCell(
        private val poisonAt: Int,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val received = mutableListOf<String>()
        var calls = 0

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Owned<String>>>))

        init {
            inlet.serve(object : Consumer<Owned<String>> {
                override fun provide(input: Owned<String>) {
                    calls++
                    if (calls == poisonAt) throw IllegalStateException("poison at call $calls")
                    received += input.take()
                }
            })
        }
    }

    class FragileLeasedCell(
        private val poisonAt: Int,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val received = mutableListOf<String>()
        var calls = 0

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Leased<String>>>))

        init {
            inlet.serve(object : Consumer<Leased<String>> {
                override fun provide(input: Leased<String>) {
                    calls++
                    if (calls == poisonAt) throw IllegalStateException("poison at call $calls")
                    received += input.value
                    input.release()
                }
            })
        }
    }

    @Test
    fun `dead-letter capture freezes a live Owned payload instead of leaking it into the fan-out`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) { letters += value }
        }, PortRef.generate()))
        val cell = FragileOwnedCell(poisonAt = 1)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(cell.ref, host, OwnedCounterProxy::class.java) as OwnedCounterProxy).inlet.call

        val poisoned = Owned("secret")
        api.provide(poisoned)
        controller.runToIdle()

        letters.size shouldBe 1
        val captured = letters[0].invocation!!.invocation.args.single()
        captured.shouldBeInstanceOf<Frozen<*>>()
        (captured as Frozen<*>).value shouldBe "secret"
        // the sender's own reference died with the capture (move-by-serialize, spec 23 R8) —
        // the dead letter is the only surviving handle, and it only ever holds a Frozen
        shouldThrow<IllegalStateException> { poisoned.take() }
    }

    @Test
    fun `dead-letter capture releases a live Leased payload and fans a redacted marker`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) { letters += value }
        }, PortRef.generate()))
        val cell = FragileLeasedCell(poisonAt = 1)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(cell.ref, host, LeasedCounterProxy::class.java) as LeasedCounterProxy).inlet.call

        var returnedToPool = false
        val poisoned = Leased("pooled") { returnedToPool = true }
        api.provide(poisoned)
        controller.runToIdle()

        letters.size shouldBe 1
        val captured = letters[0].invocation!!.invocation.args.single()
        captured.shouldBeInstanceOf<Redacted>()
        returnedToPool shouldBe true
        // released exactly once at capture — not left dangling for the caller to double-release
        shouldThrow<IllegalStateException> { poisoned.release() }
    }

    @Test
    fun `RESTART never re-consumes an already-taken Owned payload`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = FragileOwnedCell(poisonAt = 2)
        host.managementInlet.call.spawn(cell)
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = (HostedCellProxy.create(cell.ref, host, OwnedCounterProxy::class.java) as OwnedCounterProxy).inlet.call

        api.provide(Owned("first"))
        val poisoned = Owned("second")
        api.provide(poisoned)
        controller.runToIdle()

        // RESTART restores state, it never replays the poisoned invocation's input —
        // the already-taken Owned is not, and cannot be, re-delivered (spec 23 R6)
        cell.received shouldBe listOf("first")
        host.supervisionAccounting().restarts shouldBe 1
    }

    @Test
    fun `despawning a suspended cell accounts every parked invocation drained at teardown`() {
        val f = Fixture()
        f.host.managementInlet.call.supervise(f.cell.ref, SupervisionPolicy.SUSPEND)
        listOf(-1, 1, 2).forEach(f.api::provide)
        f.controller.runToIdle()

        f.host.managementInlet.call.despawn(f.cell.ref)
        f.controller.runToIdle()

        // 1 poison dead-letter + 2 parked-then-drained dead-letters, all accounted
        f.host.supervisionAccounting().deadLetters shouldBe 3
        f.host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 2
    }

    @Test
    fun `a suspended cell's parked Owned payload is dead-lettered frozen, not dropped, on despawn`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) { letters += value }
        }, PortRef.generate()))
        val cell = FragileOwnedCell(poisonAt = 1)
        host.managementInlet.call.spawn(cell)
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.SUSPEND)
        val api = (HostedCellProxy.create(cell.ref, host, OwnedCounterProxy::class.java) as OwnedCounterProxy).inlet.call

        api.provide(Owned("poison-trigger")) // poisons and suspends the cell
        val parked = Owned("parked-payload")
        api.provide(parked) // parks behind the suspended cell — still live
        controller.runToIdle()
        letters.size shouldBe 1 // only the poison so far; the parked payload has not been touched

        host.managementInlet.call.despawn(cell.ref)
        controller.runToIdle()

        letters.size shouldBe 2
        val captured = letters[1].invocation!!.invocation.args.single()
        captured.shouldBeInstanceOf<Frozen<*>>()
        (captured as Frozen<*>).value shouldBe "parked-payload"
        shouldThrow<IllegalStateException> { parked.take() }
    }
}
