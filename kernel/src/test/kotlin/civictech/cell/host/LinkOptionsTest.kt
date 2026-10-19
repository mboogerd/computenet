package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.LinkSupport
import civictech.cell.link.Linked
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.LinkTo
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.port.registerPort
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeEvent
import civictech.cell.protocol.EdgeOpen
import civictech.testkit.SimWorld
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** Acceptance coverage for host-admitted link role and staging options. */
class LinkOptionsTest {

    private class ConsumerSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Consumer<Int>>()

        fun emit(value: Int) = outlet.call.provide(value)
    }

    private open class ConsumerSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()
        val inlet by input<Consumer<Int>>()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    private class EventConsumerSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()
        val events = mutableListOf<Pair<LinkRole, EdgeEvent>>()
        val inlet by input<Consumer<Int>>()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
            inlet.onEdgeEvent { link, event -> events += link.role to event }
        }
    }

    private class PropagateSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Propagate<Int>>()

        fun emit(value: Int) = outlet.call.propagate(value)
    }

    private class PropagateSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()
        val inlet by input<Propagate<Int>>()

        init {
            inlet.serve(Propagate { received += it })
        }
    }

    private class PlainOutlet<Api : Any>(
        override val ref: PortRef = PortRef.generate(),
    ) : LinkTo<Api>, Linked {
        override val linking = LinkSupport()
        var installs = 0

        override fun linkTo(useApi: Use<Api>) {
            installs++
        }
    }

    private class PlainSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", PlainOutlet<Consumer<Int>>())
    }

    private class RelayCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()
        val inlet by input<Consumer<Int>>()
        val outlet by output<Consumer<Int>>()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }

        fun emit(value: Int) = outlet.call.provide(value)
    }

    @Test
    fun `a staged link is topology-recorded and unlink removes it`() {
        val world = SimWorld()
        val source = ConsumerSource()
        val sink = ConsumerSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)

        val connected = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>()

        val edge = world.registry.localLinks().single()
        edge.id shouldBe connected.link.id
        edge.from shouldBe source.outlet.ref
        edge.to shouldBe sink.inlet.ref

        connected.link.unlink()
        world.registry.localLinks().shouldBeEmpty()
    }

    @Test
    fun `staged delivery enters the host intake while defaults remain fused`() {
        val stagedWorld = SimWorld()
        val stagedSource = ConsumerSource()
        val stagedSink = ConsumerSink()
        stagedWorld.host.managementInlet.call.spawn(stagedSource)
        stagedWorld.host.managementInlet.call.spawn(stagedSink)
        stagedWorld.host.managementInlet.call.connect(
            stagedSource.ref, "outlet", stagedSink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>()

        stagedSource.emit(1)

        stagedWorld.host.stagedWorkDepth()[stagedSink.ref] shouldBe 1
        stagedSink.received.shouldBeEmpty()
        stagedWorld.runToIdle()
        stagedSink.received shouldBe listOf(1)

        val defaultWorld = SimWorld()
        val defaultSource = ConsumerSource()
        val defaultSink = ConsumerSink()
        defaultWorld.host.managementInlet.call.spawn(defaultSource)
        defaultWorld.host.managementInlet.call.spawn(defaultSink)
        defaultWorld.host.managementInlet.call.connect(
            defaultSource.ref, "outlet", defaultSink.ref, "inlet", LinkOptions.DEFAULT,
        ).shouldBeInstanceOf<LinkResult.Connected>()

        defaultSource.emit(1)

        defaultSink.received shouldBe listOf(1)
        defaultWorld.host.stagedWorkDepth() shouldBe emptyMap()

        val legacyWorld = SimWorld()
        val legacySource = ConsumerSource()
        val legacySink = ConsumerSink()
        legacyWorld.host.managementInlet.call.spawn(legacySource)
        legacyWorld.host.managementInlet.call.spawn(legacySink)
        legacyWorld.host.managementInlet.call.connect(
            legacySource.ref, "outlet", legacySink.ref, "inlet",
        ).shouldBeInstanceOf<LinkResult.Connected>()

        legacySource.emit(2)
        legacySink.received shouldBe listOf(2)
        legacyWorld.host.stagedWorkDepth() shouldBe emptyMap()
    }

    @Test
    fun `catch-up is staged from the first installed delivery`() {
        val world = SimWorld()
        val source = PropagateSource()
        val sink = PropagateSink()
        source.outlet.catchUpOnLinked { 42 }
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)

        world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>()

        world.host.stagedWorkDepth()[sink.ref] shouldBe 1
        sink.received.shouldBeEmpty()
        world.runToIdle()
        sink.received shouldBe listOf(42)
    }

    @Test
    fun `staging composes with an Observe link`() {
        val world = SimWorld()
        val source = ConsumerSource()
        val sink = ConsumerSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)

        val connected = world.host.managementInlet.call.connect(
            source.ref,
            "outlet",
            sink.ref,
            "inlet",
            LinkOptions(role = LinkRole.Observe, staged = true),
        ).shouldBeInstanceOf<LinkResult.Connected>()

        connected.link.role shouldBe LinkRole.Observe
        source.emit(5)
        world.host.stagedWorkDepth()[sink.ref] shouldBe 1
        sink.received.shouldBeEmpty()
        world.runToIdle()
        sink.received shouldBe listOf(5)
    }

    @Test
    fun `an Observe option is negotiated and recorded`() {
        val world = SimWorld()
        val consumer = ConsumerSource()
        val observer = ConsumerSource()
        val sink = EventConsumerSink()
        world.host.managementInlet.call.spawn(consumer)
        world.host.managementInlet.call.spawn(observer)
        world.host.managementInlet.call.spawn(sink)
        world.host.managementInlet.call.connect(
            consumer.ref, "outlet", sink.ref, "inlet", LinkOptions.DEFAULT,
        ).shouldBeInstanceOf<LinkResult.Connected>()
        val observed = world.host.managementInlet.call.connect(
            observer.ref, "outlet", sink.ref, "inlet", LinkOptions(role = LinkRole.Observe),
        ).shouldBeInstanceOf<LinkResult.Connected>()

        observed.link.role shouldBe LinkRole.Observe
        world.registry.localLinks().map { it.id }.toSet() shouldBe
            setOf(sink.inlet.linking.links.single { it.role == LinkRole.Consume }.id, observed.link.id)
        sink.events.count { it == (LinkRole.Observe to EdgeOpen) } shouldBe 1

        consumer.emit(7)
        sink.received shouldBe listOf(7)

        observed.link.unlink()
        sink.events.count { it == (LinkRole.Observe to EdgeClose) } shouldBe 1
        world.registry.localLinks().map { it.id }.toSet() shouldBe
            setOf(sink.inlet.linking.links.single { it.role == LinkRole.Consume }.id)
    }

    @Test
    fun `staged and Observe options reject a non-fan outlet without installing`() {
        val world = SimWorld()
        val source = PlainSource()
        val sink = ConsumerSink()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(sink)

        val staged = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Rejected>()
        staged.reason.shouldStartWith("StagedRequiresFanOutlet")

        val observed = world.host.managementInlet.call.connect(
            source.ref, "outlet", sink.ref, "inlet", LinkOptions(role = LinkRole.Observe),
        ).shouldBeInstanceOf<LinkResult.Rejected>()
        observed.reason.shouldStartWith("ObserveRequiresFanOutlet")

        source.outlet.installs shouldBe 0
        source.outlet.linking.links.shouldBeEmpty()
        sink.inlet.linking.links.shouldBeEmpty()
        world.registry.localLinks().shouldBeEmpty()
    }

    @Test
    fun `both staged and fused options refuse a headless cycle before installation`() {
        val world = SimWorld()
        val a = RelayCell()
        val b = RelayCell()
        world.host.managementInlet.call.spawn(a)
        world.host.managementInlet.call.spawn(b)
        world.host.managementInlet.call.connect(
            a.ref, "outlet", b.ref, "inlet", LinkOptions.DEFAULT,
        ).shouldBeInstanceOf<LinkResult.Connected>()

        val staged = world.host.managementInlet.call.connect(
            b.ref, "outlet", a.ref, "inlet", LinkOptions(staged = true),
        ).shouldBeInstanceOf<LinkResult.Rejected>()
        staged.reason shouldContain "CycleWithoutHead"

        val fused = world.host.managementInlet.call.connect(
            b.ref, "outlet", a.ref, "inlet", LinkOptions.DEFAULT,
        ).shouldBeInstanceOf<LinkResult.Rejected>()
        fused.reason shouldContain "CycleWithoutHead"

        b.outlet.linking.links.shouldBeEmpty()
        a.inlet.linking.links.shouldBeEmpty()
        b.emit(9)
        a.received.shouldBeEmpty()
        world.host.stagedWorkDepth()[a.ref] shouldBe null
        world.registry.localLinks().size shouldBe 1
    }
}
