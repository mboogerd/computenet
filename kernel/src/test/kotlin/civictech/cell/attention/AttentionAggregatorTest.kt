package civictech.cell.attention

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Session delta 2 (spec 34 decision 1): aggregation is programmable per cell —
 * strategies are declaratively defined, assembled (decay composes over a
 * base), and configured on [AttentionSupport.aggregator]. Max stays the
 * default; time-aware strategies read scheduling-step ticks, never wall time.
 */
class AttentionAggregatorTest {

    private class Stage : Cell {
        override val ref = CellRef(UUID.randomUUID())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.serve(object : Propagate<String> {
                override fun propagate(value: String) = outlet.call.propagate(value)
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun link(from: Stage, to: Stage): LinkResult.Connected =
        from.outlet.linkTo(to.inlet as LinkFrom<Propagate<String>>) as LinkResult.Connected

    @Test
    fun `sum aggregation crosses a band max would not`() {
        val source = Stage()
        val a = Stage()
        val b = Stage()
        val sourceAttention = AttentionSupport.of(source)
        link(source, a)
        link(source, b)

        AttentionSupport.of(a).attend(0.3f)
        AttentionSupport.of(b).attend(0.3f)
        sourceAttention.band shouldBe AttentionBand.LOW // max(0.3, 0.3) < 0.4

        sourceAttention.aggregator = AttentionAggregator.Sum // reconfigure: re-evaluates at once
        sourceAttention.band shouldBe AttentionBand.NORMAL // 0.3 + 0.3 = 0.6

        sourceAttention.aggregator = AttentionAggregator.Max // back: original behavior restores
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    @Test
    fun `decay lowers the band as ticks pass without a fresh signal`() {
        var now = 0L
        val sink = Stage()
        val attention = AttentionSupport.of(sink)
        attention.ticks = { now }
        attention.aggregator = AttentionAggregator.decay(halfLifeTicks = 10)

        attention.attend(1f)
        attention.band shouldBe AttentionBand.HIGH

        now = 10 // one half-life: 1.0 -> 0.5
        attention.refresh()
        attention.band shouldBe AttentionBand.NORMAL

        now = 30 // three half-lives: 1.0 -> 0.125
        attention.refresh()
        attention.band shouldBe AttentionBand.LOW

        attention.attend(1f) // fresh signal restamps the clock
        attention.band shouldBe AttentionBand.HIGH
    }

    @Test
    fun `no signal stays neutral under every shipped strategy`() {
        for (aggregator in listOf(
            AttentionAggregator.Max,
            AttentionAggregator.Sum,
            AttentionAggregator.decay(halfLifeTicks = 10),
        )) {
            val cell = Stage()
            val attention = AttentionSupport.of(cell)
            attention.aggregator = aggregator
            attention.band shouldBe AttentionBand.NORMAL
        }
    }
}
