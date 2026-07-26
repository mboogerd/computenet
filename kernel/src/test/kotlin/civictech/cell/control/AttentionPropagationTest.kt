package civictech.cell.control

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.link.LinkResult
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M6.2 (spec 34): upstream attention propagation, max aggregation,
 * quantization damping, link churn.
 */
class AttentionPropagationTest {

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

    private fun link(from: Stage, to: Stage): LinkResult.Connected =
        from.outlet.linkTo(to.inlet as civictech.cell.port.LinkFrom<Propagate<String>>) as LinkResult.Connected

    @Test
    fun `attention travels upstream through a chain and quantizes to bands`() {
        val source = Stage()
        val middle = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        val middleAttention = AttentionSupport.of(middle)
        val sinkAttention = AttentionSupport.of(sink)
        link(source, middle)
        link(middle, sink)

        sourceAttention.band shouldBe AttentionBand.NORMAL // neutral: nobody said anything

        sinkAttention.attend(1f)
        middleAttention.band shouldBe AttentionBand.HIGH
        sourceAttention.band shouldBe AttentionBand.HIGH

        sinkAttention.attend(0f)
        middleAttention.band shouldBe AttentionBand.NONE
        sourceAttention.band shouldBe AttentionBand.NONE
    }

    @Test
    fun `aggregation is max over downstream links`() {
        val source = Stage()
        val hot = Stage()
        val cold = Stage()
        val sourceAttention = AttentionSupport.of(source)
        val hotAttention = AttentionSupport.of(hot)
        val coldAttention = AttentionSupport.of(cold)
        val hotLink = link(source, hot)
        link(source, cold)

        hotAttention.attend(1f)
        coldAttention.attend(0.2f)
        sourceAttention.band shouldBe AttentionBand.HIGH // max(1, 0.2)

        hotAttention.attend(0f)
        sourceAttention.band shouldBe AttentionBand.LOW // max(0, 0.2)

        // unlinking the hot branch leaves only the cold subscriber's signal
        hotAttention.attend(1f)
        sourceAttention.band shouldBe AttentionBand.HIGH
        hotLink.link.unlink()
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    @Test
    fun `quantization damps intra-band jitter`() {
        // upstream endpoint deliberately NOT attention-wired: we observe raw deliveries
        val source = Stage()
        val sink = Stage()
        val sinkAttention = AttentionSupport.of(sink)

        var deliveries = 0
        ProtocolSupport.of(source.outlet).handle(Protocols.Attention) { _, _ -> deliveries++ }
        link(source, sink)
        deliveries shouldBe 1 // install pushes the sink's current (NORMAL) band

        sinkAttention.attend(0.9f) // NORMAL -> HIGH: emits
        sinkAttention.attend(0.8f) // still HIGH: damped
        sinkAttention.attend(0.99f) // still HIGH: damped
        deliveries shouldBe 2

        sinkAttention.attend(0.1f) // HIGH -> LOW: emits
        deliveries shouldBe 3
    }

    @Test
    fun `a new inbound link immediately learns the downstream band`() {
        val sink = Stage()
        val sinkAttention = AttentionSupport.of(sink)
        sinkAttention.attend(1f)

        val lateSource = Stage()
        val lateAttention = AttentionSupport.of(lateSource)
        lateAttention.band shouldBe AttentionBand.NORMAL
        link(lateSource, sink)
        lateAttention.band shouldBe AttentionBand.HIGH // pushed on link install
    }
}
