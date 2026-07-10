package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.verify.InvariantCell
import civictech.cell.verify.Violation
import civictech.cell.data.Propagate
import civictech.cell.port.PortRef
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

/**
 * M9 exit (spec 52/53, 92): a candidate incarnation of a running middle cell
 * — different internal representation — shadows production traffic without
 * duplicating side effects, is judged by an invariant cell, and is promoted
 * mid-stream through a buffered swap window with state carried across
 * incarnations; the post-swap output stream is identical to an unswapped
 * control run. 100 seeds; a control proves the harness detects duplicated
 * effects from an unsuppressed shadow sink.
 */
class ShadowPromotionTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    /** v1: running sum held as a Long. */
    class SummerV1(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.provide(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum
        override fun restore(state: Serializable) {
            sum = state as Long
        }
    }

    /** v2: same behavior, different internal representation (string-encoded). */
    class SummerV2(override val ref: CellRef) : Cell, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var repr = "sum=0"

        private fun sum(): Long = repr.removePrefix("sum=").toLong()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    repr = "sum=${sum() + input}"
                    outlet.call.provide(sum())
                }
            })
        }

        override fun importFrom(prior: Serializable) {
            repr = "sum=${prior as Long}" // v1's Long → v2's string form (G-33)
        }
    }

    class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Long>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Long>>())

        init {
            inlet.serve(object : Consumer<Long> {
                override fun provide(input: Long) {
                    received += input
                }
            })
        }
    }

    /** The side-effecting sink: counts how often it acted on the world. */
    class NotifierCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Effectful {
        var fired = 0
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Long>>())

        init {
            inlet.serve(object : Consumer<Long> {
                override fun provide(input: Long) {
                    fired++
                }
            })
        }
    }

    interface GateProxy {
        val dataInlet: Use<Consumer<Int>>
    }

    private class Run(seed: Long, promoteAt: Int?, suppressShadowEffects: Boolean = true) {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val host = ManagedHost(scheduler = controller.scheduler())

        val logicalId = UUID.randomUUID()
        val source = SourceCell(@Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>))
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, incarnation = 0))
        val candidate = SummerV2(CellRef(logicalId, incarnation = 1))
        val view = CollectorCell()
        val prodNotifier = NotifierCell()
        val shadowNotifier = NotifierCell()
        val violations = mutableListOf<Violation>()
        val judge = InvariantCell<Long, Long>(
            "candidate sums are non-decreasing", 0L,
            fold = { _, sum -> sum },
            check = { prev, sum -> if (sum < prev) "sum regressed: $sum < $prev" else null },
        )

        init {
            listOf(source, gate, incumbent, view, prodNotifier, judge).forEach {
                host.managementInlet.call.spawn(it)
            }
            // candidate + its effectful sink form the shadow subgraph (G-32)
            Shadow.spawn(host, candidate)
            if (suppressShadowEffects) Shadow.spawn(host, shadowNotifier)
            else host.managementInlet.call.spawn(shadowNotifier) // control: unsuppressed
            controller.runToIdle()

            // production path: source → (host queue) → gate → v1 → view + notifier
            val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java)
                    as GateProxy).dataInlet.call
            source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
            gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
            incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
            incumbent.outlet.subscribe(prodNotifier.inlet as Use<Consumer<Long>>)
            // shadow fan-out: candidate sees the same live inputs, feeds its
            // own (suppressed) sink and the judging invariant
            gate.dataOutlet.subscribe(candidate.inlet as Use<Consumer<Int>>)
            candidate.outlet.subscribe(shadowNotifier.inlet as Use<Consumer<Long>>)
            // adapt Consumer -> Propagate for the invariant judge
            candidate.outlet.subscribe(Use.fixed(object : Consumer<Long> {
                override fun provide(input: Long) = judge.inlet.call.propagate(input)
            }, PortRef.generate()))
            judge.violations.subscribe(Use.fixed(object : Propagate<Violation> {
                override fun propagate(value: Violation) {
                    violations += value
                }
            }, PortRef.generate()))

            gate.controlInlet.call.setGreen() // traffic lights start red
        }

        fun drive(waves: Int, promoteAt: Int?): List<Long> {
            for (n in 1..waves) {
                if (n == promoteAt) {
                    violations.shouldBeEmpty() // the promotion gate: judge approves
                    Promotion.promote(
                        gate, incumbent, candidate, "outlet",
                        downstream = listOf(view.inlet, prodNotifier.inlet),
                    )
                }
                source.emit(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()
            return view.received.toList()
        }
    }

    @Test
    fun `promotion mid-stream is invisible to the production view under 100 seeds`() {
        val waves = 20
        for (seed in 0L until 100L) {
            val promoted = Run(seed, promoteAt = 10)
            val output = promoted.drive(waves, promoteAt = 10)

            val control = Run(seed, promoteAt = null)
            val expected = control.drive(waves, promoteAt = null)

            output shouldBe expected // zero loss, per-link FIFO, same sums
            promoted.violations.shouldBeEmpty()
            promoted.shadowNotifier.fired shouldBe 0 // shadow never acted on the world
            promoted.prodNotifier.fired shouldBe waves // production effects exactly once each
        }
    }

    @Test
    fun `control - an unsuppressed shadow sink double-fires`() {
        val run = Run(seed = 1, promoteAt = null, suppressShadowEffects = false)
        run.drive(20, promoteAt = null)
        run.shadowNotifier.fired shouldBeGreaterThan 0 // the harness detects duplicated effects
    }
}
