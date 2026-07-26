package civictech.cell.host

import civictech.cell.*
import civictech.cell.Propagate
import civictech.cell.port.*
import civictech.cell.proxy.HostedCellProxy
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

/**
 * The M3 exit criterion: migrate a running subchain between hosts — across
 * colors — with zero message loss and preserved per-link order, under load
 * (the source keeps emitting through the migration window), over many seeds.
 * A control run with fixed-host proxies proves the harness detects loss.
 *
 * Topology: source on 🔵 H1 → two stateful mappers (direct-linked subchain)
 * on their own 🔵 H2 → suspending observer on 🟣 H3; boundaries are
 * registry-resolved. Mid-run H2's cells migrate to 🟣 H4: the pure subchain
 * crosses colors.
 */
class SubchainMigrationTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    interface ConsumerCellProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface ObserverApi {
        suspend fun observe(n: Int)
    }

    interface ObserverCellProxy {
        val inlet: Use<ObserverApi>
    }

    /** Pure relay: an inlet trigger re-emitted through a context-stamping outlet. */
    inner class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet(consumerInt))
        val outlet = registerPort("outlet", FanOutlet(consumerInt))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) = outlet.call.provide(input)
            })
        }
    }

    inner class StatefulMapperCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        var processed = 0
        var restores = 0
        val inlet = registerPort("inlet", FanInlet(consumerInt))
        val outlet = registerPort("outlet", FanOutlet(consumerInt))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    processed++
                    outlet.call.provide(input)
                }
            })
        }

        override fun snapshot(): Serializable = processed
        override fun restore(state: Serializable) {
            processed = state as Int
            restores++
        }
    }

    inner class ObserverCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : SuspendingCell {
        val received = mutableListOf<Int>()
        val timestamps = mutableListOf<Timestamp>()
        val inlet = registerPort("inlet", FanInlet.create<ObserverApi>())

        init {
            inlet.serve(object : ObserverApi {
                override suspend fun observe(n: Int) {
                    received += n
                    timestamps += CurrentContext.get()!!.timestamp
                }
            })
        }
    }

    private fun collectDeadLetters(vararg hosts: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        hosts.forEach { host ->
            host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    letters += value
                }
            }, PortRef.generate()))
        }
        return letters
    }

    private data class Run(
        val observer: ObserverCell,
        val mapper1: StatefulMapperCell,
        val mapper2: StatefulMapperCell,
        val letters: List<DeadLetter>,
        val h2: ManagedHost,
        val h4: ManagedHost,
    )

    private fun runSubchainMigration(seed: Long, waves: Int, reResolving: Boolean): Run {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val h1 = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val h2 = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val h3 = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING), registry = registry)
        val h4 = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING), registry = registry)
        val letters = collectDeadLetters(h1, h2, h3, h4)

        val source = SourceCell()
        val mapper1 = StatefulMapperCell()
        val mapper2 = StatefulMapperCell()
        val observer = ObserverCell()

        h1.managementInlet.call.spawn(source)
        h2.managementInlet.call.spawn(mapper1)
        h2.managementInlet.call.spawn(mapper2)
        h3.managementInlet.call.spawn(observer)

        // boundaries: registry-resolved (re-resolving) or pinned to the spawn host (control)
        fun mapperApi(m: StatefulMapperCell): Consumer<Int> {
            val proxy =
                if (reResolving) HostedCellProxy.create(m.ref, registry, ConsumerCellProxy::class.java)
                else HostedCellProxy.create(m.ref, h2, ConsumerCellProxy::class.java)
            return (proxy as ConsumerCellProxy).inlet.call
        }

        val observerApi = (HostedCellProxy.create(observer.ref, registry, ObserverCellProxy::class.java)
                as ObserverCellProxy).inlet.call

        source.outlet.subscribe(Use.fixed(mapperApi(mapper1), PortRef.generate()))
        // inside the subchain the link is direct — it travels with the cells (spec 33)
        mapper1.outlet.subscribe(Use.fixed(mapper2.inlet.call, mapper2.inlet.ref))
        mapper2.outlet.subscribe(Use.fixed(object : Consumer<Int> {
            override fun provide(input: Int) {
                kotlinx.coroutines.runBlocking { observerApi.observe(input) }
            }
        }, PortRef.generate()))

        val sourceApi = (HostedCellProxy.create(source.ref, registry, ConsumerCellProxy::class.java)
                as ConsumerCellProxy).inlet.call

        val rnd = Random(seed)
        val moveAt = 5 + rnd.nextInt(waves - 10)
        for (n in 1..waves) {
            sourceApi.provide(n)
            // migration lands mid-stream; emission continues throughout the window
            if (n == moveAt) h2.managementInlet.call.migrate(h4.managementInlet)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        return Run(observer, mapper1, mapper2, letters, h2, h4)
    }

    @Test
    fun `a running subchain migrates across colors with zero loss and per-link order - every seed`() {
        val waves = 50
        for (seed in 0L until 100L) {
            val run = runSubchainMigration(seed, waves, reResolving = true)

            // zero loss, exactly once, per-link FIFO end to end
            run.observer.received shouldBe (1..waves).toList()
            // per-source monotonic wave order survived the move
            run.observer.timestamps.map { it.counter } shouldBe run.observer.timestamps.map { it.counter }.sorted()
            run.letters.shouldBeEmpty()

            // the subchain really moved, state intact through the serialization round-trip
            run.mapper1.processed shouldBe waves
            run.mapper2.processed shouldBe waves
            run.mapper1.restores shouldBe 1
            run.mapper2.restores shouldBe 1
            run.h2.lookup<ConsumerCellProxy>(run.mapper1.ref).shouldBeNull()
            run.h4.lookup<ConsumerCellProxy>(run.mapper1.ref).shouldNotBeNull()
        }
    }

    @Test
    fun `control - without re-resolution the same run demonstrably loses messages`() {
        var lossy = 0
        for (seed in 0L until 20L) {
            val run = runSubchainMigration(seed, waves = 50, reResolving = false)
            if (run.observer.received.size < 50 || run.letters.isNotEmpty()) lossy++
        }
        // if this fails, the harness is too weak to detect loss — the main test proves nothing
        (lossy > 0).shouldBeTrue()
    }
}
