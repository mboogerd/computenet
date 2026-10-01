package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.durability.InMemoryJournal
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.protocol.EdgeClose
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.WireCodec
import civictech.testkit.SimWorld
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/** Acceptance coverage for sequencing a staged link's close behind its accepted data. */
class StagedLinkCloseOrderTest {

    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Propagate<Int>>()

        fun emit(value: Int) = outlet.call.propagate(value)
    }

    private class GlitchSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()
        val events = mutableListOf<String>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet by input<Propagate<Int>>()

        init {
            inlet.serve(Propagate { value ->
                received += value
                events += "data($value)"
            })
            inlet.install(frontier)
            inlet.onEdgeEvent { _, event ->
                if (event == EdgeClose) events += "EdgeClose"
            }
        }
    }

    @Test
    fun `staged close follows accepted data and stops further intake immediately`() {
        val world = SimWorld()
        val source = Source()
        val sink = GlitchSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)
        val link = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>().link

        (1..3).forEach(source::emit)
        sink.received.shouldBeEmpty()
        link.unlink()

        world.registry.localLinks().shouldBeEmpty()
        val depthAfterClose = world.host.stagedWorkDepth()[sink.ref]
        source.emit(4)
        world.host.stagedWorkDepth()[sink.ref] shouldBe depthAfterClose

        world.runToIdle()

        sink.frontier.unmatchedDrops shouldBe 0L
        sink.received shouldBe listOf(1, 2, 3)
        sink.events shouldBe listOf("data(1)", "data(2)", "data(3)", "EdgeClose")
    }

    @Test
    fun `staged close marker for a despawned target is not a dead letter`() {
        val world = SimWorld()
        val source = Source()
        val sink = GlitchSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)
        val link = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>().link

        (1..3).forEach(source::emit)
        link.unlink()
        world.host.stagedWorkDepth()[sink.ref] shouldBe 4
        world.host.managementInlet.call.despawn(sink.ref)

        world.runToIdle()

        // The three payload-bearing data frames keep the existing despawn policy:
        // each dead-letters as an unknown-cell delivery. The terminal EdgeClose is
        // payload-free and its edge disappeared with the target, so it adds none.
        world.host.supervisionAccounting().deadLetters shouldBe 3L
    }

    @Test
    fun `attention parked staged close marker is discarded on despawn`() {
        val world = SimWorld(attention = AttentionPolicy(suspendAfter = 0))
        val source = Source()
        val sink = GlitchSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)
        val link = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>().link
        AttentionSupport.of(sink).attend(0f)

        (1..3).forEach(source::emit)
        world.runToIdle()
        sink.received.shouldBeEmpty()
        link.unlink()
        world.host.managementInlet.call.despawn(sink.ref)

        world.runToIdle()

        world.host.supervisionAccounting().deadLetters shouldBe 3L
        world.host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 3L
    }

    @Test
    fun `fused close remains synchronous`() {
        val world = SimWorld()
        val source = Source()
        val sink = GlitchSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)
        val link = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions.DEFAULT,
        ).shouldBeInstanceOf<LinkResult.Connected>().link

        (1..3).forEach(source::emit)
        sink.received shouldBe listOf(1, 2, 3)
        sink.events shouldBe listOf("data(1)", "data(2)", "data(3)")

        link.unlink()

        sink.events shouldBe listOf("data(1)", "data(2)", "data(3)", "EdgeClose")
        world.host.stagedWorkDepth() shouldBe emptyMap()
    }

    @Test
    fun `staged close marker is not journaled`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val journal = InMemoryJournal()
        val source = Source()
        val sink = GlitchSink()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journalForPort = { ref, port ->
                if (ref == sink.ref && port == "inlet") journal else null
            },
        )
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(sink)
        val link = host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>().link

        (1..3).forEach(source::emit)
        link.unlink()

        val frames = journal.replay()
            .map { JournalRecords.decode(it) }
            .filterIsInstance<DecodedJournalRecord.Frame>()
            .map { WireCodec.decode(it.payload) }
        frames.size shouldBe 3
        frames.map { it.type } shouldBe List(3) { HostedPortInvocation.Type.PORT_API }

        controller.runToIdle()
        sink.received shouldBe listOf(1, 2, 3)
        sink.frontier.unmatchedDrops shouldBe 0L
    }

    @Test
    fun `attention parked staged close keeps its place behind data`() {
        val world = SimWorld(attention = AttentionPolicy(suspendAfter = 0))
        val source = Source()
        val sink = GlitchSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)
        val link = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>().link
        AttentionSupport.of(sink).attend(0f)

        (1..3).forEach(source::emit)
        world.runToIdle()
        sink.received.shouldBeEmpty()

        link.unlink()
        AttentionSupport.of(sink).attend(1f)
        world.runToIdle()

        sink.received shouldBe listOf(1, 2, 3)
        sink.events shouldBe listOf("data(1)", "data(2)", "data(3)", "EdgeClose")
        sink.frontier.unmatchedDrops shouldBe 0L
    }

    private class StatefulSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        val received = mutableListOf<Int>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet by input<Propagate<Int>>()

        init {
            inlet.serve(Propagate { value -> received += value })
            inlet.install(frontier)
        }

        override fun snapshot(): Serializable = ArrayList(received)
        override fun restore(state: Serializable) {}
    }

    @Test
    fun `checkpoint with a staged close marker queued carries the data and not the marker`() {
        val controller = SimulationController()
        val journal = InMemoryJournal()
        val source = Source()
        val sink = StatefulSink()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = LocationRegistry(),
            journalForPort = { ref, port ->
                if (ref == sink.ref && port == "inlet") journal else null
            },
        )
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(sink)
        val link = host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>().link

        (1..3).forEach(source::emit)
        link.unlink()
        host.checkpoint(journal)
        controller.runToIdle()

        val frames = journal.replay()
            .map { JournalRecords.decode(it) }
            .filterIsInstance<DecodedJournalRecord.Frame>()
            .map { WireCodec.decode(it.payload) }
        frames.map { it.type } shouldBe List(3) { HostedPortInvocation.Type.PORT_API }
        sink.received shouldBe listOf(1, 2, 3)
        sink.frontier.unmatchedDrops shouldBe 0L
    }
}
