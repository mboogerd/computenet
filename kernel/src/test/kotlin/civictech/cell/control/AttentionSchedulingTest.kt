package civictech.cell.control

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.DeadLetter
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.testkit.SimWorld
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M6.3 (spec 34): the host maps attention bands to dispatch order, the stride
 * floor bounds starvation, and band NONE past the policy window parks traffic
 * without loss.
 */
class AttentionSchedulingTest {

    class SinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()

        @Suppress("unused")
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }
    }

    interface SinkInterface {
        val inlet: Use<Consumer<String>>
    }

    private class Fixture(policy: AttentionPolicy?) {
        private val world = SimWorld(attention = policy)
        val controller = world.controller
        val host = world.host
        val deadLetters = mutableListOf<DeadLetter>()
        val hot = SinkCell()
        val cold = SinkCell()

        init {
            host.deadLetterOutlet.subscribe(Use.fixed(object : civictech.cell.Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    deadLetters += value
                }
            }, civictech.cell.port.PortRef.generate()))
            host.managementInlet.call.spawn(hot)
            host.managementInlet.call.spawn(cold)
            controller.runToIdle()
        }

        fun sendInterleaved(count: Int) {
            val hotApi = host.lookup<SinkInterface>(hot.ref)!!
            val coldApi = host.lookup<SinkInterface>(cold.ref)!!
            repeat(count) { i ->
                coldApi.inlet.call.provide("cold-$i")
                hotApi.inlet.call.provide("hot-$i")
            }
        }
    }

    @Test
    fun `higher bands dispatch before lower bands`() {
        val fixture = Fixture(AttentionPolicy(stride = Int.MAX_VALUE))
        AttentionSupport.of(fixture.hot).attend(1f)
        AttentionSupport.of(fixture.cold).attend(0.2f)

        fixture.sendInterleaved(5)
        // observe global completion order via per-cell counts at each step:
        // when the last hot message lands, no cold message may have landed yet
        while (fixture.hot.received.size < 5) fixture.controller.step().shouldBeTrue()
        fixture.cold.received.size shouldBe 0

        fixture.controller.runToIdle()
        fixture.cold.received shouldBe (0 until 5).map { "cold-$it" } // FIFO within the cell
        fixture.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `stride floor keeps low-band cells progressing under high-band load`() {
        val fixture = Fixture(AttentionPolicy(stride = 2))
        AttentionSupport.of(fixture.hot).attend(1f)
        AttentionSupport.of(fixture.cold).attend(0.2f)

        fixture.sendInterleaved(20)
        // drive until half the hot traffic has landed; the floor must have
        // let cold work through by then (with stride=2, roughly every third)
        while (fixture.hot.received.size < 10) fixture.controller.step().shouldBeTrue()
        fixture.cold.received.size shouldBeGreaterThan 0

        fixture.controller.runToIdle()
        fixture.cold.received shouldBe (0 until 20).map { "cold-$it" }
        fixture.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `control - stride disabled starves the low band while high-band work remains`() {
        val fixture = Fixture(AttentionPolicy(stride = Int.MAX_VALUE))
        AttentionSupport.of(fixture.hot).attend(1f)
        AttentionSupport.of(fixture.cold).attend(0.2f)

        fixture.sendInterleaved(20)
        while (fixture.hot.received.size < 20) fixture.controller.step().shouldBeTrue()
        fixture.cold.received.size shouldBe 0 // starved: the harness detects what the floor prevents

        fixture.controller.runToIdle()
    }

    @Test
    fun `band NONE past the policy window parks traffic and re-attention replays it in order`() {
        val fixture = Fixture(AttentionPolicy(suspendAfter = 3))
        AttentionSupport.of(fixture.cold).attend(0f) // explicit zero interest

        val coldApi = fixture.host.lookup<SinkInterface>(fixture.cold.ref)!!
        repeat(10) { i -> coldApi.inlet.call.provide("cold-$i") }
        fixture.controller.runToIdle()

        // the window admits a few dispatches, then the rest parked (not dropped, not delivered)
        fixture.cold.received.size shouldBe 3
        fixture.deadLetters.shouldBeEmpty()

        // renewed interest replays the parked tail in order
        AttentionSupport.of(fixture.cold).attend(1f)
        fixture.controller.runToIdle()
        fixture.cold.received shouldBe (0 until 10).map { "cold-$it" }
        fixture.deadLetters.shouldBeEmpty()
    }
}
