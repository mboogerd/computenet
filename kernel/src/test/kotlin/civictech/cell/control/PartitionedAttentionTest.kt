package civictech.cell.control

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-19 (plan §3b, spec 34 decisions 3/5) — attention scatter by interest and
 * per-instance parking. A consumer declares interest in one key range and
 * scatters its attention by the data-plane overlap rule ([Interest.overlaps]):
 * a covering shard is attended (HIGH), a non-covering shard is explicitly
 * unattended (NONE) and parks like any cell. The control (broadcast — no
 * scatter) attends both, so the non-covering shard never parks.
 */
class PartitionedAttentionTest {

    /** A hosted shard: its inlet traffic parks when its band falls to NONE (per-cell park). */
    private class ShardStage(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        @Suppress("unused")
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init { inlet.serve(Propagate<String> { received += it }) }
    }

    /** The downstream consumer whose scattered attention drives the shards' bands. */
    private class ConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        init { inlet.serve(Propagate<String> { /* attention source only */ }) }
    }

    private interface ShardFeed {
        val inlet: Use<Propagate<String>>
    }

    private class ScatterFixture(scatter: Boolean) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), attention = AttentionPolicy(suspendAfter = 3))
        val covering = ShardStage()
        val nonCovering = ShardStage()
        val consumer = ConsumerCell()

        private val scope: Interest = Interest.Ranges(listOf(Interest.Ranges.Range(0, 10)))
        private val shardInterest: Map<Any?, Interest> = mapOf(
            covering.outlet to Interest.Ranges(listOf(Interest.Ranges.Range(0, 10))),
            nonCovering.outlet to Interest.Ranges(listOf(Interest.Ranges.Range(10, 20))),
        )

        init {
            host.managementInlet.call.spawn(covering)
            host.managementInlet.call.spawn(nonCovering)
            controller.runToIdle()

            val consumerAttention = AttentionSupport.of(consumer)
            if (scatter) {
                consumerAttention.scatter = { link ->
                    (shardInterest[link.fromPort] ?: Interest.Total).overlaps(scope)
                }
            }
            AttentionSupport.of(covering)
            AttentionSupport.of(nonCovering)

            for (shard in listOf(covering, nonCovering)) {
                (shard.outlet.linkTo(consumer.inlet as LinkFrom<Propagate<String>>) is LinkResult.Connected)
                    .shouldBeTrue()
            }
            consumerAttention.attend(1f)
        }

        fun feed(count: Int) {
            val cov = host.lookup<ShardFeed>(covering.ref)!!.inlet.call
            val non = host.lookup<ShardFeed>(nonCovering.ref)!!.inlet.call
            repeat(count) { index ->
                cov.propagate("hot-$index")
                non.propagate("cold-$index")
            }
            controller.runToIdle()
        }
    }

    @Test
    fun `interest scatter attends the covering shard and parks the non-covering one`() {
        val f = ScatterFixture(scatter = true)

        AttentionSupport.of(f.covering).band shouldBe AttentionBand.HIGH
        AttentionSupport.of(f.nonCovering).band shouldBe AttentionBand.NONE

        f.feed(8)
        f.covering.received.size shouldBe 8
        (f.nonCovering.received.size < 8).shouldBeTrue()

        AttentionSupport.of(f.consumer).scatter = { true }
        f.controller.runToIdle()
        f.nonCovering.received shouldBe (0 until 8).map { "cold-$it" }
    }

    @Test
    fun `control - without scatter the broadcast attends both shards and neither parks`() {
        val f = ScatterFixture(scatter = false)

        AttentionSupport.of(f.covering).band shouldBe AttentionBand.HIGH
        AttentionSupport.of(f.nonCovering).band shouldBe AttentionBand.HIGH

        f.feed(8)
        f.covering.received.size shouldBe 8
        f.nonCovering.received.size shouldBe 8
    }
}
