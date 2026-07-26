package civictech.cell.control

import civictech.cell.*
import civictech.cell.Propagate
import civictech.cell.host.AttentionPolicy
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.*
import civictech.cell.port.*
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M6 exit harness (spec 34, 92): a fan-out graph with a shared upstream cell
 * and two sink cones under seeded randomized cross-host scheduling.
 * Per seed: (a) the stride floor keeps the low-attention cone progressing
 * under sustained high-attention load; (b) dropping one sink's attention
 * quiesces exactly its exclusive cone — parked, zero loss on re-attention —
 * while the shared cell and the hot cone keep flowing. The control run
 * proves the harness detects the starvation the floor prevents. The
 * glitch-freedom interaction (WAIT/DEGRADE) is verified deterministically in
 * GlitchFreeSuspensionTest.
 */
class AttentionGenerativeTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    class SinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()

        @Suppress("unused")
        val inlet by input<Consumer<Int>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    interface MapperProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface SinkProxy {
        val inlet: Use<Consumer<Int>>
    }

    private class Run(seed: Long, stride: Int) {
        val controller = SimulationController(seed)
        val rnd = Random(seed)

        val hostM = ManagedHost(scheduler = controller.scheduler())
        val hostXY = ManagedHost(
            scheduler = controller.scheduler(),
            attention = AttentionPolicy(suspendAfter = 3, stride = stride),
        )

        var mCount = 0
        var yCount = 0
        val source = SourceCell(@Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>))
        val shared = MapperCell<Int, Int>(f = { mCount++; it })
        val x1 = MapperCell<Int, Int>(f = { it })
        val y1 = MapperCell<Int, Int>(f = { yCount++; it })
        val sinkX = SinkCell()
        val sinkY = SinkCell()
        val deadLetters = mutableListOf<DeadLetter>()

        init {
            listOf(hostM, hostXY).forEach { host ->
                host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLetters += value
                    }
                }, PortRef.generate()))
            }
            hostM.managementInlet.call.spawn(shared)
            listOf(x1, y1, sinkX, sinkY).forEach { hostXY.managementInlet.call.spawn(it) }

            // source feeds the shared cell over hostM's queue (plain routed send)
            source.outlet.subscribe(Use.fixed(hostM.lookup<MapperProxy>(shared.ref)!!.inlet.call, PortRef.generate()))

            // handshaken links carry attention upstream; delivery rides host queues
            link(shared.outlet, x1.inlet, hostXY.lookup<MapperProxy>(x1.ref)!!.inlet.call)
            link(shared.outlet, y1.inlet, hostXY.lookup<MapperProxy>(y1.ref)!!.inlet.call)
            link(x1.outlet, sinkX.inlet, hostXY.lookup<SinkProxy>(sinkX.ref)!!.inlet.call)
            link(y1.outlet, sinkY.inlet, hostXY.lookup<SinkProxy>(sinkY.ref)!!.inlet.call)
            controller.runToIdle()

            AttentionSupport.of(sinkX).attend(1f) // hot cone
            AttentionSupport.of(sinkY).attend(0.2f) // low but live cone
        }

        private fun <T : Any> link(outlet: FanOutlet<Consumer<T>>, inlet: FanInlet<Consumer<T>>, routed: Consumer<T>) {
            (outlet.linkTo(inlet as LinkFrom<Consumer<T>>) is LinkResult.Connected).shouldBeTrue()
            outlet.unsubscribe(inlet.ref)
            outlet.subscribe(Use.fixed(routed, inlet.ref))
        }

        /** Steps until the hot sink holds [count] values; returns y-cone progress at that instant. */
        fun stepUntilHotDone(count: Int): Int {
            while (sinkX.received.size < count) controller.step().shouldBeTrue()
            return yCount
        }
    }

    @Test
    fun `attention harness converges under 100 seeds with floor, park and replay`() {
        val waves = 10
        for (seed in 0L until 100L) {
            val run = Run(seed, stride = 2)

            // phase 1: burst under mixed attention; the floor must move the low cone
            (1..waves).forEach { run.source.emit(it) }
            val yProgressAtHotDone = run.stepUntilHotDone(waves)
            yProgressAtHotDone shouldBeGreaterThan 0 // stride floor: no starvation
            run.controller.runToIdle()
            run.sinkY.received shouldBe (1..waves).toList() // convergence, FIFO

            // phase 2: zero interest quiesces exactly the y-cone
            AttentionSupport.of(run.sinkY).attend(0f)
            (waves + 1..2 * waves).forEach {
                run.source.emit(it)
                repeat(run.rnd.nextInt(4)) { run.controller.step() }
            }
            run.controller.runToIdle()
            run.sinkX.received shouldBe (1..2 * waves).toList() // hot cone unaffected
            run.mCount shouldBe 2 * waves // shared cell stays live
            run.sinkY.received.size shouldBeLessThan 2 * waves // parked, not delivered
            run.deadLetters.shouldBeEmpty() // parked, not dropped

            // phase 3: renewed interest replays the cone with zero loss, in order
            AttentionSupport.of(run.sinkY).attend(0.6f)
            run.controller.runToIdle()
            run.sinkY.received shouldBe (1..2 * waves).toList()
            run.deadLetters.shouldBeEmpty()
        }
    }

    @Test
    fun `control - without the stride floor the low cone starves on some seed`() {
        val waves = 10
        var starved = 0
        for (seed in 0L until 100L) {
            val run = Run(seed, stride = Int.MAX_VALUE)
            (1..waves).forEach { run.source.emit(it) }
            if (run.stepUntilHotDone(waves) == 0) starved++
            run.controller.runToIdle()
        }
        // if this fails the harness is too weak to detect starvation — tune load
        (starved > 0).shouldBeTrue()
    }
}
