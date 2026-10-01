package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkRole
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.protocol.EdgeClose
import civictech.testkit.SimWorld
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Acceptance coverage for link options and recorded unlink steps in GraphSpec. */
class GraphSpecLinkStepsTest {

    private class Source(override val ref: CellRef) : Cell {
        val outlet by output<Propagate<Int>>()

        fun emit(value: Int) = outlet.call.propagate(value)
    }

    private class Sink(override val ref: CellRef) : Cell {
        val received = mutableListOf<Int>()
        val events = mutableListOf<String>()
        val inlet by input<Propagate<Int>>()

        init {
            inlet.serve(Propagate { value ->
                received += value
                events += "data($value)"
            })
            inlet.onEdgeEvent { _, event -> if (event == EdgeClose) events += "EdgeClose" }
        }
    }

    private class Capture {
        lateinit var source: Source
        lateinit var sink: Sink
    }

    private fun spec(capture: Capture, options: LinkOptions, unlink: Boolean = false): GraphSpec = GraphSpec(
        buildList {
            add(SpawnStep("source", CellFactory { ref -> Source(ref).also { capture.source = it } }))
            add(SpawnStep("sink", CellFactory { ref -> Sink(ref).also { capture.sink = it } }))
            add(ConnectStep("source", "outlet", "sink", "inlet", options))
            if (unlink) add(UnlinkStep("source", "outlet", "sink", "inlet"))
        },
    )

    private fun assertStagedObserve(world: SimWorld, capture: Capture) {
        capture.source.outlet.linking.links.single().role shouldBe LinkRole.Observe
        capture.source.emit(7)
        capture.sink.received.shouldBeEmpty()
        world.host.stagedWorkDepth()[capture.sink.ref] shouldBe 1
        world.runToIdle()
        capture.sink.received shouldBe listOf(7)
    }

    @Test
    fun `default ConnectStep shape stays equal and builders record supplied options`() {
        ConnectStep("a", "out", "b", "in") shouldBe
            ConnectStep("a", "out", "b", "in", LinkOptions.DEFAULT)

        val world = SimWorld()
        val options = LinkOptions(role = LinkRole.Observe, staged = true)
        val graph = graph(world.host.managementInlet) {
            val source = spawn("source") { ref -> Source(ref) }
            val sink = spawn("sink") { ref -> Sink(ref) }
            link(source.cell.outlet, sink.cell.inlet, options)
        }

        graph.steps.filterIsInstance<ConnectStep>().single().options shouldBe options
    }

    @Test
    fun `all three apply paths admit staged Observe options`() {
        val options = LinkOptions(role = LinkRole.Observe, staged = true)

        val contextWorld = SimWorld()
        val contextCapture = Capture()
        val applied = spec(contextCapture, options).apply(ApplyContext(contextWorld.host))
        applied.links.getValue("source.outlet->sink.inlet").role shouldBe LinkRole.Observe
        assertStagedObserve(contextWorld, contextCapture)

        val localWorld = SimWorld()
        val localCapture = Capture()
        spec(localCapture, options).applyTo(localWorld.host.managementInlet)
        assertStagedObserve(localWorld, localCapture)

        val remoteWorld = SimWorld()
        val remoteCapture = Capture()
        val report = spec(remoteCapture, options).applyRemote(remoteWorld.host.managementInlet)
        report.results.getValue("source.outlet->sink.inlet") shouldBe StepResult.Applied(null)
        assertStagedObserve(remoteWorld, remoteCapture)
    }

    @Test
    fun `builder unlink sequences staged close behind accepted frames and records the step`() {
        val world = SimWorld()
        lateinit var source: Source
        lateinit var sink: Sink

        val graph = graph(world.host.managementInlet) {
            val sourceHandle = spawn("source") { ref -> Source(ref).also { source = it } }
            val sinkHandle = spawn("sink") { ref -> Sink(ref).also { sink = it } }
            connect(sourceHandle, "outlet", sinkHandle, "inlet", LinkOptions(staged = true))
            (1..3).forEach(source::emit)
            unlink(sourceHandle, "outlet", sinkHandle, "inlet")
        }

        world.registry.localLinks().shouldBeEmpty()
        source.emit(4)
        world.runToIdle()

        sink.received shouldBe listOf(1, 2, 3)
        sink.events shouldBe listOf("data(1)", "data(2)", "data(3)", "EdgeClose")
        graph.steps.last() shouldBe UnlinkStep("source", "outlet", "sink", "inlet")
    }

    @Test
    fun `local replay unlinks the admitted handle and rejects an unknown edge by name`() {
        val contextWorld = SimWorld()
        val applied = spec(Capture(), LinkOptions(staged = true), unlink = true)
            .apply(ApplyContext(contextWorld.host))
        applied.links shouldBe emptyMap()
        contextWorld.registry.localLinks().shouldBeEmpty()

        val localWorld = SimWorld()
        spec(Capture(), LinkOptions.DEFAULT, unlink = true).applyTo(localWorld.host.managementInlet)
        localWorld.registry.localLinks().shouldBeEmpty()

        val missing = GraphSpec(listOf(UnlinkStep("source", "outlet", "sink", "inlet")))
        shouldThrow<IllegalStateException> {
            missing.applyTo(SimWorld().host.managementInlet)
        }.message.shouldContain("source.outlet->sink.inlet")

        val builderWorld = SimWorld()
        shouldThrow<IllegalStateException> {
            graph(builderWorld.host.managementInlet) {
                val source = spawn("source") { ref -> Source(ref) }
                val sink = spawn("sink") { ref -> Sink(ref) }
                unlink(source, "outlet", sink, "inlet")
            }
        }.message.shouldContain("source.outlet->sink.inlet")
    }

    @Test
    fun `remote unlink is rejected under its distinct key and reported through progress`() {
        val world = SimWorld()
        val events = mutableListOf<StepEvent>()
        val report = spec(Capture(), LinkOptions.DEFAULT, unlink = true)
            .applyRemote(world.host.managementInlet) { events += it }

        val key = "unlink source.outlet->sink.inlet"
        val rejected = report.results.getValue(key).shouldBeInstanceOf<StepResult.Rejected>()
        rejected.reason shouldContain "source.outlet->sink.inlet"
        events.map { it.index to it.handle } shouldBe listOf(
            0 to "source",
            1 to "sink",
            2 to "source.outlet->sink.inlet",
            3 to key,
        )
        events.last().result shouldBe rejected
        world.registry.localLinks().size shouldBe 1
    }
}
