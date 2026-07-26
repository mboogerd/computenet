package civictech.cell.host

import civictech.cell.*
import civictech.cell.Propagate
import civictech.cell.port.*
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

/**
 * M3.2 (G-5 items 1/2/5): closable intake fails fast, registry-resolved sends
 * park in order on closure and replay exactly once — per-link FIFO preserved —
 * when the cell's location is (re)published; parking is never a dead letter.
 */
class RelocationTest {

    interface CollectorProxy {
        val inlet: Use<Consumer<Int>>
    }

    class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    /** Registry-resolving consumer API for [cell], as a registry-aware `lookup` builds it. */
    private fun registryApi(registry: LocationRegistry, cell: CollectorCell): Consumer<Int> =
        (HostedCellProxy.create(cell.ref, registry, CollectorProxy::class.java) as CollectorProxy).inlet.call

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, PortRef.generate()))
        return letters
    }

    @Test
    fun `closure parks, re-publication replays exactly once in order`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val letters = collectDeadLetters(host)

            val cell = CollectorCell()
            host.managementInlet.call.spawn(cell)
            val api = registryApi(registry, cell)

            val rnd = Random(seed)
            val closeAt = 2 + rnd.nextInt(waves - 2)
            for (n in 1..waves) {
                if (n == closeAt) {
                    controller.runToIdle()
                    host.closeIntake()
                }
                api.provide(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()

            // everything sent after closure parked — no loss, no dead letters
            registry.parkedFor(cell.ref).size shouldBe waves - closeAt + 1
            letters.shouldBeEmpty()

            host.openIntake()
            registry.publish(cell.ref, host)
            controller.runToIdle()

            cell.received shouldBe (1..waves).toList()
            registry.parkedFor(cell.ref).shouldBeEmpty()
            letters.shouldBeEmpty()
        }
    }

    @Test
    fun `spawning on a new host re-publishes and replays parked traffic there`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)
            val lettersA = collectDeadLetters(hostA)
            val lettersB = collectDeadLetters(hostB)

            val cell = CollectorCell()
            hostA.managementInlet.call.spawn(cell)
            val api = registryApi(registry, cell)

            val rnd = Random(seed)
            val moveAt = 2 + rnd.nextInt(waves - 2)
            for (n in 1..waves) {
                if (n == moveAt) {
                    // relocate: old intake closes mid-stream, traffic parks...
                    controller.runToIdle()
                    hostA.closeIntake()
                }
                api.provide(n)
                repeat(rnd.nextInt(4)) { controller.step() }
                if (n == moveAt) {
                    // ...and spawning on the new host publishes + replays (spec 33 step 7)
                    hostB.managementInlet.call.spawn(cell)
                }
            }
            controller.runToIdle()

            cell.received shouldBe (1..waves).toList()
            registry.parkedFor(cell.ref).shouldBeEmpty()
            lettersA.shouldBeEmpty()
            lettersB.shouldBeEmpty()
        }
    }

    @Test
    fun `a fixed-host proxy fails fast at the send site when the intake is closed`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = CollectorCell()
        host.managementInlet.call.spawn(cell)
        val api = host.lookup<CollectorProxy>(cell.ref)!!.inlet.call

        api.provide(1)
        controller.runToIdle()
        host.closeIntake()

        assertThrows<IntakeClosedException> { api.provide(2) }
        // the router path fails fast too; management stays open
        assertThrows<IntakeClosedException> {
            host.routerInlet.call.route(cell.ref, "inlet", Invocation.of(null, null))
        }
        host.managementInlet.call.lookup(cell.ref, CollectorProxy::class.java)

        host.openIntake()
        api.provide(3)
        controller.runToIdle()
        cell.received shouldBe listOf(1, 3)
    }

    /**
     * The one property the single-threaded simulation cannot check: publish/replay
     * racing concurrent delivery loses or duplicates nothing (per-ref lock in
     * [LocationRegistry]). Real threads, no sleeps — exactly-once + per-host order.
     */
    @Test
    fun `concurrent relocation is exactly-once under real threads`() {
        class RecordingHost(scheduler: HostScheduler) : ManagedHost(scheduler = scheduler) {
            @Volatile
            var closed = false
            val recorded = Collections.synchronizedList(mutableListOf<Int>())
            override fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
                if (closed) throw IntakeClosedException(ref)
                recorded += hostedInvocation.invocation.args[0] as Int
            }
        }

        val controller = SimulationController()
        val registry = LocationRegistry()
        val hostA = RecordingHost(controller.scheduler())
        val hostB = RecordingHost(controller.scheduler())
        val cellRef = CellRef(UUID.randomUUID())
        registry.publish(cellRef, hostA)

        val messages = 20_000
        fun inv(n: Int) = HostedPortInvocation(
            cellRef, "inlet", HostedPortInvocation.Type.PORT_API,
            Invocation("provide", listOf("java.lang.Object"), listOf(n))
        )

        val producer = Thread {
            for (n in 1..messages) registry.deliver(inv(n))
        }
        val mover = Thread {
            var to = hostB
            var from = hostA
            repeat(500) {
                from.closed = true
                to.closed = false
                registry.publish(cellRef, to)
                from = to.also { to = from }
            }
        }
        producer.start(); mover.start()
        producer.join(); mover.join()

        // final flush: whatever parked during the last flip replays to an open host
        hostA.closed = false
        registry.publish(cellRef, hostA)

        val all = hostA.recorded + hostB.recorded
        all.size shouldBe messages
        all.toSortedSet().size shouldBe messages
        hostA.recorded shouldBe hostA.recorded.sorted()
        hostB.recorded shouldBe hostB.recorded.sorted()
    }
}
