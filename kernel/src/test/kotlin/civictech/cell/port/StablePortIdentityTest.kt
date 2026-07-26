package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.link.Link
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID
import civictech.cell.data.delta.MintedTags

/**
 * PN-1 (plan §2 F1 root, §4 PN-1): a hosted cell's port [PortRef] is DERIVED
 * from `(ownerRef, portName)` — the SetCell/MintedTags/watermark M10.1 pattern —
 * so it survives a rebuild. `MessageContext.sourcePort` therefore keys the same
 * durable identity the wave plane keys on, and a wave minted before a restart
 * still matches its edge in the rebuilt graph. Anonymous/test ports keep a fresh
 * random ref.
 */
class StablePortIdentityTest {

    class ProducerCell(override val ref: CellRef) : Cell {
        val outlet by output<Consumer<Int>>()
    }

    class ConsumerCell(override val ref: CellRef) : Cell {
        val inlet by input<Consumer<Int>>()
    }

    private fun fakeLink(from: PortRef, to: PortRef): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from: PortRef = from
        override val to: PortRef = to
        override fun unlink() {}
    }

    private fun wave(sourcePort: PortRef, sourceId: UUID, counter: Long): Invocation =
        Invocation(
            methodName = "provide",
            parameterTypes = listOf("java.lang.Object"),
            args = listOf(counter.toInt()),
            context = MessageContext(Timestamp(sourceId, counter), sourcePort),
        )

    @Test
    fun `hosted port refs are equal across two builds of the same graph`() {
        val pRef = CellRef(UUID.randomUUID())
        val cRef = CellRef(UUID.randomUUID())

        val build1 = ProducerCell(pRef) to ConsumerCell(cRef)
        val build2 = ProducerCell(pRef) to ConsumerCell(cRef)

        // Fresh JVM objects, same refs + names → identical derived port identity.
        build2.first.outlet.ref shouldBe build1.first.outlet.ref
        build2.second.inlet.ref shouldBe build1.second.inlet.ref

        // Distinct (cellRef, name) pairs derive distinct refs — no collisions.
        build1.first.outlet.ref shouldNotBe build1.second.inlet.ref
    }

    @Test
    fun `a wave minted pre-rebuild matches its edge in the rebuilt graph`() {
        val pRef = CellRef(UUID.randomUUID())
        val cRef = CellRef(UUID.randomUUID())

        // Build 1: mint a wave context stamped with the producer outlet's ref,
        // as MessageContext.sourcePort would be at emit time.
        val pre = ProducerCell(pRef)
        val source = UUID.randomUUID()
        val ctx = wave(pre.outlet.ref, source, 1L)

        // Build 2: a fresh graph (new JVM objects, same refs/names). Open the
        // edge from the rebuilt producer outlet into the rebuilt consumer inlet.
        val producer = ProducerCell(pRef)
        val consumer = ConsumerCell(cRef)
        val delivered = mutableListOf<Invocation>()
        val dropped = mutableListOf<Invocation>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT, onDropped = { dropped += it })
        frontier.attach(consumer.inlet) { delivered += it }
        val link = fakeLink(from = producer.outlet.ref, to = consumer.inlet.ref)
        ProtocolSupport.of(consumer.inlet).deliver(Protocols.TopologyOrder, link, EdgeOpen)

        // The pre-rebuild context's sourcePort matches the rebuilt edge's `from`
        // because the derivation is replay-stable — the frame is delivered, not
        // dropped. With the derivation reverted to generate() (the control) the
        // rebuilt outlet ref differs, the edge does not match, and this frame is
        // routed to the unmatched-drop diagnostic instead.
        frontier.offer(ctx)

        delivered.size shouldBe 1
        dropped.size shouldBe 0
        frontier.unmatchedDrops shouldBe 0L
    }

    @Test
    fun `anonymous ports keep a fresh random ref`() {
        // Not registered on a Cell → no PortIdentity → no derivation.
        FanOutlet.create<Consumer<Int>>().ref shouldNotBe FanOutlet.create<Consumer<Int>>().ref
        FanInlet.create<Consumer<Int>>().ref shouldNotBe FanInlet.create<Consumer<Int>>().ref
    }
}
