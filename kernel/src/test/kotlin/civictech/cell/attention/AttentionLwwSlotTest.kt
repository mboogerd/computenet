package civictech.cell.attention

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.Protocols
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * W4.5 (G-58 core, 93 I-4 Candidate C, 30/34 decision 1 continuation):
 * per-link LWW slot algebra — idempotency law, later-version supersession,
 * retraction on unlink (slot GC), and version wraparound.
 */
class AttentionLwwSlotTest {

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

    // ---- Direct algebra tests (AttentionFrontier in isolation) ----

    @Test
    fun `idempotency law- duplicate version is absorbed, not applied`() {
        val frontier = AttentionFrontier()
        val link = UUID.randomUUID()

        frontier.onUpdate(link, 0.5f, version = 5L) shouldBe true
        frontier.levels.toList() shouldBe listOf(0.5f)

        // exact duplicate re-delivery of the same version: absorbed, no change
        frontier.onUpdate(link, 0.5f, version = 5L) shouldBe false
        frontier.levels.toList() shouldBe listOf(0.5f)

        // a stale (lower) version arriving late must not regress the slot
        frontier.onUpdate(link, 0.9f, version = 3L) shouldBe false
        frontier.levels.toList() shouldBe listOf(0.5f)
    }

    @Test
    fun `a later version supersedes an earlier one`() {
        val frontier = AttentionFrontier()
        val link = UUID.randomUUID()

        frontier.onUpdate(link, 0.2f, version = 1L)
        frontier.onUpdate(link, 0.9f, version = 2L) shouldBe true
        frontier.levels.toList() shouldBe listOf(0.9f)
    }

    @Test
    fun `onUnlink retracts and GCs the slot`() {
        val frontier = AttentionFrontier()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        frontier.onUpdate(a, 1f, version = 1L)
        frontier.onUpdate(b, 0.3f, version = 1L)
        frontier.levels.toList().sorted() shouldBe listOf(0.3f, 1f)

        frontier.onUnlink(a) shouldBe true
        frontier.levels.toList() shouldBe listOf(0.3f)
        frontier.contains(a) shouldBe false

        // retracting an already-absent link is a no-op, not an error
        frontier.onUnlink(a) shouldBe false

        // an in-flight update for the removed link finds no slot to key it: dropped
        frontier.onUpdate(a, 1f, version = 99L) shouldBe true // fresh slot, distinct from retraction
        frontier.onUnlink(a)
        frontier.levels.toList() shouldBe listOf(0.3f)
    }

    @Test
    fun `version wraparound is handled by signed-difference comparison`() {
        val frontier = AttentionFrontier()
        val link = UUID.randomUUID()

        frontier.onUpdate(link, 0.1f, version = Long.MAX_VALUE - 1)
        frontier.onUpdate(link, 0.2f, version = Long.MAX_VALUE)
        // wraps past Long.MAX_VALUE into Long.MIN_VALUE: still "newer" in mint order
        frontier.onUpdate(link, 0.3f, version = Long.MIN_VALUE) shouldBe true
        frontier.levels.toList() shouldBe listOf(0.3f)
        frontier.onUpdate(link, 0.4f, version = Long.MIN_VALUE + 1) shouldBe true
        frontier.levels.toList() shouldBe listOf(0.4f)

        // a version from "before" the wrap must not resurrect and override post-wrap state
        frontier.onUpdate(link, 0.9f, version = Long.MAX_VALUE) shouldBe false
        frontier.levels.toList() shouldBe listOf(0.4f)
    }

    @Test
    fun `version minter mints strictly increasing versions across wraparound`() {
        val minter = VersionMinter(start = Long.MAX_VALUE - 2)
        val v1 = minter.next()
        val v2 = minter.next()
        val v3 = minter.next() // wraps: Long.MAX_VALUE + 1 -> Long.MIN_VALUE
        val v4 = minter.next()

        VersionMinter.isNewer(v2, v1) shouldBe true
        VersionMinter.isNewer(v3, v2) shouldBe true // crosses the wrap
        VersionMinter.isNewer(v4, v3) shouldBe true
        VersionMinter.isNewer(v1, v4) shouldBe false
    }

    // ---- Integration: the algebra wired through AttentionSupport/ports ----

    @Test
    fun `AttentionSupport absorbs duplicate and stale deliveries on a real link`() {
        val source = Stage()
        val middle = Stage()
        val sourceAttention = AttentionSupport.of(source)
        AttentionSupport.of(middle)
        val connected = link(source, middle)

        Protocols.sendUpstream(connected.link, Protocols.Attention, Attention(0.9f, 10L))
        sourceAttention.band shouldBe AttentionBand.HIGH

        // duplicate: same version, redelivered (e.g. a retried frame) — absorbed
        Protocols.sendUpstream(connected.link, Protocols.Attention, Attention(0.9f, 10L))
        // stale: a lower version arriving late (reorder) — absorbed, must not regress
        Protocols.sendUpstream(connected.link, Protocols.Attention, Attention(0.1f, 5L))
        sourceAttention.band shouldBe AttentionBand.HIGH

        // genuinely later version supersedes
        Protocols.sendUpstream(connected.link, Protocols.Attention, Attention(0.1f, 11L))
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    @Test
    fun `onUnlink retracts the slot end-to-end and re-folds the remainder`() {
        val source = Stage()
        val hot = Stage()
        val cold = Stage()
        val sourceAttention = AttentionSupport.of(source)
        AttentionSupport.of(hot).attend(1f)
        AttentionSupport.of(cold).attend(0.2f)
        val hotLink = link(source, hot)
        link(source, cold)

        sourceAttention.band shouldBe AttentionBand.HIGH // max(1, 0.2)
        hotLink.link.unlink()
        sourceAttention.band shouldBe AttentionBand.LOW // hot's slot GC'd: max(0.2) remains
    }

    // ---- Decay cadence knob ----

    @Test
    fun `decay cadence knob quantizes recompute into cadence buckets`() {
        var now = 0L
        val sink = Stage()
        val attention = AttentionSupport.of(sink)
        attention.ticks = { now }
        // half-life 10 ticks, but decay only advances in buckets of 5 ticks
        attention.aggregator = AttentionAggregator.decay(halfLifeTicks = 10, cadenceTicks = 5)

        attention.attend(1f)
        attention.band shouldBe AttentionBand.HIGH

        now = 3 // inside the first cadence bucket: floored to 0 elapsed ticks, no advance
        attention.refresh()
        attention.band shouldBe AttentionBand.HIGH

        now = 5 // first cadence boundary: decay advances as if 5 ticks elapsed
        attention.refresh()
        attention.band shouldBe AttentionBand.NORMAL // 1.0 * 0.5^(5/10) ~= 0.707

        now = 20 // four cadence buckets of 5 = 20 ticks = two half-lives
        attention.refresh()
        attention.band shouldBe AttentionBand.LOW // 1.0 * 0.5^(20/10) = 0.25
    }
}
