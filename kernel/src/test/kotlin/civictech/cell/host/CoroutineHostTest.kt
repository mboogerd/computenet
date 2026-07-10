package civictech.cell.host

import civictech.cell.*
import civictech.cell.port.*
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * M3.1 (G-3, G-27): the 🟣 coroutine host under deterministic simulation — real
 * suspension parks the host without loss or reorder, the wave context survives
 * suspension, wrong-color spawns are rejected, and cross-color links keep
 * per-link FIFO in both directions.
 */
class CoroutineHostTest {

    interface SuspendingApi {
        suspend fun process(n: Int)
    }

    data class Seen(val n: Int, val before: Timestamp?, val after: Timestamp?)

    class GatedSuspendingCell(
        private val gate: CompletableDeferred<Unit>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : SuspendingCell {
        val received = mutableListOf<Seen>()
        val inlet = registerPort("inlet", FanInlet.create<SuspendingApi>())

        init {
            inlet.serve(object : SuspendingApi {
                override suspend fun process(n: Int) {
                    val before = CurrentContext.get()?.timestamp
                    if (n == 1) gate.await() // the first message parks the host
                    received += Seen(n, before, CurrentContext.get()?.timestamp)
                }
            })
        }
    }

    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val outlet = registerPort("outlet", FanOutlet(Consumer::class.java as Class<Consumer<Int>>))
        fun emit(n: Int) = outlet.call.provide(n)
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

    interface SuspendingCellProxy {
        val inlet: Use<SuspendingApi>
    }

    interface ConsumerCellProxy {
        val inlet: Use<Consumer<Int>>
    }

    class MarkedBlockingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : BlockingCell

    @Test
    fun `a genuinely suspending message parks the host and resumes without loss or reorder`() {
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val purple = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING))
            val gate = CompletableDeferred<Unit>()
            val cell = GatedSuspendingCell(gate)
            purple.managementInlet.call.spawn(cell)
            val api = purple.lookup<SuspendingCellProxy>(cell.ref)!!.inlet.call

            runBlocking { for (n in 1..5) api.process(n) }
            controller.runToIdle()

            // message 1 is suspended on the gate; the parked host runs nothing else (actor semantics)
            cell.received shouldBe emptyList()

            gate.complete(Unit) // resumption re-enters the simulation as a step
            controller.runToIdle()
            cell.received.map { it.n } shouldBe listOf(1, 2, 3, 4, 5)
        }
    }

    @Test
    fun `the wave context survives suspension`() {
        val controller = SimulationController(seed = 42)
        val blue = ManagedHost(scheduler = controller.scheduler())
        val purple = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING))

        val gate = CompletableDeferred<Unit>()
        val cell = GatedSuspendingCell(gate)
        purple.managementInlet.call.spawn(cell)

        // a releasing cell on the 🔵 host completes the gate as an ordinary simulation step
        val releaser = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            @Suppress("UNCHECKED_CAST")
            val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

            init {
                inlet.serve(object : Consumer<Int> {
                    override fun provide(input: Int) {
                        gate.complete(Unit)
                    }
                })
            }
        }
        blue.managementInlet.call.spawn(releaser)

        // context-stamped emission: source outlet → routed proxy → suspend fun
        val source = SourceCell()
        val bridge = object : Consumer<Int> {
            val api = purple.lookup<SuspendingCellProxy>(cell.ref)!!.inlet.call
            override fun provide(input: Int) = runBlocking { api.process(input) }
        }
        source.outlet.subscribe(Use.fixed(bridge, PortRef.generate()))

        source.emit(1)
        controller.runToIdle()
        cell.received shouldBe emptyList() // parked mid-wave

        blue.lookup<ConsumerCellProxy>(releaser.ref)!!.inlet.call.provide(0)
        controller.runToIdle()

        val seen = cell.received.single()
        seen.before.shouldNotBeNull()
        seen.after shouldBe seen.before // no wave-welding across the suspension point
    }

    @Test
    fun `wrong-color spawns are rejected`() {
        val controller = SimulationController()
        val blue = ManagedHost(scheduler = controller.scheduler(HostColor.BLOCKING))
        val purple = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING))

        assertThrows<IllegalArgumentException> {
            blue.managementInlet.call.spawn(GatedSuspendingCell(CompletableDeferred()))
        }
        assertThrows<IllegalArgumentException> {
            purple.managementInlet.call.spawn(MarkedBlockingCell())
        }
        // 🟢 pure cells spawn on either color
        blue.managementInlet.call.spawn(CollectorCell())
        purple.managementInlet.call.spawn(CollectorCell())
    }

    @Test
    fun `cross-color links preserve per-link FIFO in both directions`() {
        val waves = 30
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val purple = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING))
            val blue = ManagedHost(scheduler = controller.scheduler(HostColor.BLOCKING))

            // blue → purple(mapper, pure) → blue(collector): both boundaries routed
            val mapper = MapperCell<Int, Int>(f = { it })
            val collector = CollectorCell()
            purple.managementInlet.call.spawn(mapper)
            blue.managementInlet.call.spawn(collector)

            val source = SourceCell()
            source.outlet.subscribe(
                Use.fixed(purple.lookup<ConsumerCellProxy>(mapper.ref)!!.inlet.call, PortRef.generate())
            )
            mapper.outlet.subscribe(
                Use.fixed(blue.lookup<ConsumerCellProxy>(collector.ref)!!.inlet.call, PortRef.generate())
            )

            val rnd = Random(seed)
            for (n in 1..waves) {
                source.emit(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()
            collector.received shouldBe (1..waves).toList()
        }
    }

    @Test
    fun `coroutine scheduler smoke test on a real dispatcher`() {
        val purple = ManagedHost(scheduler = CoroutineScheduler("purple-smoke"))
        val done = CompletableFuture<List<Int>>()
        val received = mutableListOf<Int>()

        val cell = object : SuspendingCell {
            override val ref = CellRef(UUID.randomUUID())
            val inlet = registerPort("inlet", FanInlet.create<SuspendingApi>())

            init {
                inlet.serve(object : SuspendingApi {
                    override suspend fun process(n: Int) {
                        received += n
                        if (n == 3) done.complete(received.toList())
                    }
                })
            }
        }
        purple.managementInlet.call.spawn(cell)
        val api = purple.lookup<SuspendingCellProxy>(cell.ref)!!.inlet.call

        runBlocking { for (n in 1..3) api.process(n) }
        done.get(5, TimeUnit.SECONDS) shouldBe listOf(1, 2, 3)
    }
}
