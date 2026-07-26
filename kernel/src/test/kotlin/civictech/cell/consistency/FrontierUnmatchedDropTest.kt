package civictech.cell.consistency

import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.port.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.link.Link
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-0a (plan §2 F1): [WaveFrontier.offer]'s unmatched-edge path must not
 * discard invocations silently. A producer whose source matches no open edge
 * (a replayed journal frame, a `streamTo`/`tap` producer that never fired
 * `EdgeOpen`, a duplicate edge) is routed to a counted diagnostic — still not
 * delivered downstream, but no longer invisible. This is the tripwire, not the
 * fix (PN-1/PN-2 remove the cause).
 */
class FrontierUnmatchedDropTest {

    private val consumerInt =
        @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    private fun fakeLink(from: PortRef, to: PortRef): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from: PortRef = from
        override val to: PortRef = to
        override fun unlink() {}
    }

    /** A reactive data invocation carrying its wave context (sourcePort + timestamp). */
    private fun wave(sourcePort: PortRef, sourceId: UUID, counter: Long): Invocation =
        Invocation(
            methodName = "provide",
            parameterTypes = listOf("java.lang.Object"),
            args = listOf(counter.toInt()),
            context = MessageContext(Timestamp(sourceId, counter), sourcePort),
        )

    @Test
    fun `a producer with no open edge yields one diagnostic per emission and zero deliveries`() {
        val delivered = mutableListOf<Invocation>()
        val dropped = mutableListOf<Invocation>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT, onDropped = { dropped += it })
        val inlet = FanInlet.create<Consumer<Int>>()
        frontier.attach(inlet) { delivered += it }

        // No EdgeOpen was ever fired for this producer, so its source matches no
        // open edge — today's silent-drop path.
        val ghostProducer = PortRef.generate()
        val source = UUID.randomUUID()
        repeat(3) { i -> frontier.offer(wave(ghostProducer, source, (i + 1).toLong())) }

        dropped.size shouldBe 3
        frontier.unmatchedDrops shouldBe 3L
        delivered.size shouldBe 0
    }

    @Test
    fun `control - a matched open edge delivers normally with zero diagnostics`() {
        val delivered = mutableListOf<Invocation>()
        val dropped = mutableListOf<Invocation>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT, onDropped = { dropped += it })
        val inlet = FanInlet.create<Consumer<Int>>()
        frontier.attach(inlet) { delivered += it }

        // Open the edge for this producer (the normal handshake path), then feed
        // matching waves down it.
        val producer = PortRef.generate()
        val link = fakeLink(from = producer, to = inlet.ref)
        ProtocolSupport.of(inlet).deliver(Protocols.TopologyOrder, link, EdgeOpen)

        val source = UUID.randomUUID()
        repeat(3) { i -> frontier.offer(wave(producer, source, (i + 1).toLong())) }

        dropped.size shouldBe 0
        frontier.unmatchedDrops shouldBe 0L
        delivered.size shouldBe 3
    }
}
