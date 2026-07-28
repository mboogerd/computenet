package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M3 — the flow feed (`tickets/M3-BE.md`): per-edge rates sampled through
 * `FanOutlet.tap`, aggregated once per window into the contract's `flow.rates`
 * batch.
 *
 * The collector is driven directly here rather than through the 1 Hz schedule:
 * these tests assert what one window *contains*, not when the scheduler fires
 * it (AGENTS.md — "assert semantic outcomes, not internal scheduling timing").
 */
class InspectorFlowTest {

    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val batches = CopyOnWriteArrayList<FlowBatch>()
    private val flow = FlowCollector(registry, onBatch = batches::add)

    @AfterEach
    fun tearDown() {
        flow.close()
    }

    // ------------------------------------------------------------ scaffolding

    private fun spawnSet(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun connect(from: CellRef, outlet: String, to: CellRef, inlet: String): Link {
        val result = host.managementInlet.call.connect(from, outlet, to, inlet)
        return (result as LinkResult.Connected).link
    }

    /** Bind every live edge, as `InspectorModel.sync()` does at startup. */
    private fun bindAll(): Map<UUID, Boolean?> = registry.all().associate { it.id to flow.bind(it) }

    private fun ops(cell: SetCell<String>) = host.lookup<SetApi<String>>(cell.ref)!!.inlet.call

    /** Drive [n] distinct — therefore effective, therefore emitting — adds. */
    private fun drive(cell: SetCell<String>, n: Int, prefix: String = "e") {
        val ops = ops(cell)
        repeat(n) { ops.add("$prefix$it") }
    }

    /**
     * Close one window. Null when the collector published nothing at all —
     * which it does exactly while it watches no edge and watched none in the
     * previous window either (an inspector with nothing observable stays quiet
     * rather than emitting an empty batch every second forever).
     */
    private fun sampleOrNull(): FlowBatch? {
        val before = batches.size
        flow.sample()
        return batches.getOrNull(before)
    }

    private fun sample(): FlowBatch = sampleOrNull() ?: error("expected a published flow window")

    // ------------------------------------------------------------ attribution

    @Test
    fun `a driven edge's rate lands on that edge and no other`() {
        val busy = spawnSet()
        val busySink = spawnSet()
        val quiet = spawnSet()
        val quietSink = spawnSet()
        val driven = connect(busy.ref, "outlet", busySink.ref, "deltaInlet")
        val idle = connect(quiet.ref, "outlet", quietSink.ref, "deltaInlet")
        bindAll().values.toSet() shouldBe setOf(false)

        drive(busy, 7)
        awaitUntil("the driven deltas reached the sink") {
            host.lookup<SetApi<String>>(busySink.ref) != null && sampleReady(busy, 7)
        }

        val batch = sample()

        batch.window shouldBe FlowCollector.WINDOW_MS
        // contract: "edges with rate 0 omitted" — the idle edge is simply absent
        batch.edges.map { it.id } shouldContainExactly listOf(driven.id.toString())
        // window == 1000 ms, so messages-per-second is the raw count
        batch.edges.single().rate shouldBe 7.0
        batch.edges.none { it.id == idle.id.toString() } shouldBe true
    }

    @Test
    fun `a window with no traffic reports no edges, and the next window starts clean`() {
        val source = spawnSet()
        val sink = spawnSet()
        connect(source.ref, "outlet", sink.ref, "deltaInlet")
        bindAll()

        drive(source, 3)
        awaitUntil("deltas observed") { sampleReady(source, 3) }
        sample().edges.single().rate shouldBe 3.0

        // counters reset with the window: a quiet second is an empty batch,
        // not a repeat of the previous one
        sample().edges shouldContainExactly emptyList()
    }

    @Test
    fun `a broadcasting outlet reports the same rate on every edge it feeds`() {
        val source = spawnSet()
        val left = spawnSet()
        val right = spawnSet()
        val toLeft = connect(source.ref, "outlet", left.ref, "deltaInlet")
        val toRight = connect(source.ref, "outlet", right.ref, "deltaInlet")
        bindAll()
        // one tap serves both edges — the outlet is the emission point, and it
        // broadcasts, so its count is *each* edge's count, never a share of it
        flow.tappedOutlets.size shouldBe 1

        drive(source, 4)
        awaitUntil("deltas observed") { sampleReady(source, 4) }

        val rates = sample().edges.associate { it.id to it.rate }
        rates shouldBe mapOf(toLeft.id.toString() to 4.0, toRight.id.toString() to 4.0)
    }

    // ------------------------------------------------------------- wave stamp

    @Test
    fun `the batch carries the driven wave, carried through and hop-counted`() {
        // a transparent (reactive) two-hop chain: the relay re-emits inside the
        // incoming context, so one driven wave shows up on both edges — same
        // source and counter, one hop apart. `SetCell`'s replica intake is
        // deliberately NOT used here: it re-originates (`outlet.originate`), a
        // gossip boundary, which is a different (and correct) stamping story.
        val emitter = Emitter().also { host.managementInlet.call.spawn(it) }
        val relay = Relay().also { host.managementInlet.call.spawn(it) }
        val sink = Sink().also { host.managementInlet.call.spawn(it) }
        val upstream = connect(emitter.ref, "outlet", relay.ref, "inlet")
        val downstream = connect(relay.ref, "outlet", sink.ref, "inlet")
        bindAll().values.toSet() shouldBe setOf(false)

        emitter.emit("one")
        awaitUntil("the wave reached the sink") { sink.seen.isNotEmpty() }

        val rows = sample().edges.associateBy { it.id }
        val minted = emitter.outlet.waveState()

        val first = rows.getValue(upstream.id.toString())
        first.lastWave.shouldNotBeNull() shouldBe WaveStamp(minted.sourceId.toString(), 1L)
        // a spontaneous emission starts the hop count
        first.hop shouldBe 0

        val second = rows.getValue(downstream.id.toString())
        // the same wave, transparently carried — not a new one
        second.lastWave.shouldNotBeNull() shouldBe WaveStamp(minted.sourceId.toString(), 1L)
        second.hop shouldBe 1
    }

    // ------------------------------------------------------------ fused edges

    @Test
    fun `an edge whose producer is a delegating pass-through is fused and never rated`() {
        val passThrough = PassThrough().also { host.managementInlet.call.spawn(it) }
        val sink = Sink().also { host.managementInlet.call.spawn(it) }
        // linking an *inlet* as the producer installs a delegation, not a
        // subscription: the pass-through is removed from the per-message path
        // entirely (spec 10/14, 20/21 §Fusion), so this edge has no emission
        // point to observe
        connect(passThrough.ref, "passthrough", sink.ref, "inlet")

        bindAll().values.toSet() shouldBe setOf(true)
        flow.tappedOutlets shouldBe emptySet()

        host.lookup<PassThroughApi>(passThrough.ref)!!.passthrough.call.propagate("through")
        awaitUntil("the delegated call arrived") { sink.seen.isNotEmpty() }

        // fused, therefore never rated — a zero rate would claim the edge was
        // observed and quiet, which is a different (and false) statement
        sampleOrNull() shouldBe null
    }

    @Test
    fun `an edge whose producer this registry does not host reports fused unknown`() {
        val detached = ManagedHost()
        val source = SetCell<String>().also { detached.managementInlet.call.spawn(it) }
        val sink = SetCell<String>().also { detached.managementInlet.call.spawn(it) }
        val link = detached.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
        (link is LinkResult.Connected) shouldBe true

        // the registry-less host publishes nowhere, so `locate` cannot reach it
        flow.bind(
            civictech.cell.host.TopologyLink(
                (link as LinkResult.Connected).link.id,
                source.outlet.ref,
                sink.deltaInlet.ref,
            )
        ) shouldBe null
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    fun `unlinking an edge untaps its outlet and stops its rate`() {
        val source = spawnSet()
        val sink = spawnSet()
        val link = connect(source.ref, "outlet", sink.ref, "deltaInlet")
        bindAll()
        flow.tappedOutlets shouldBe setOf(source.outlet.ref)
        drive(source, 2, prefix = "before")
        awaitUntil("deltas observed") { sampleReady(source, 2) }
        sample().edges.single().rate shouldBe 2.0

        link.unlink()
        flow.unbind(link.id)
        flow.tappedOutlets shouldBe emptySet()

        // the handler is off the outlet: post-unlink traffic is not observed at
        // all, and the one trailing window says so
        drive(source, 5, prefix = "after")
        sample().edges shouldContainExactly emptyList()
        // with nothing left to watch, the feed then falls silent
        sampleOrNull() shouldBe null
    }

    @Test
    fun `an outlet keeps its tap while any of its edges survives`() {
        val source = spawnSet()
        val left = spawnSet()
        val right = spawnSet()
        val toLeft = connect(source.ref, "outlet", left.ref, "deltaInlet")
        val toRight = connect(source.ref, "outlet", right.ref, "deltaInlet")
        bindAll()

        toLeft.unlink()
        flow.unbind(toLeft.id)
        flow.tappedOutlets shouldBe setOf(source.outlet.ref)

        drive(source, 2, prefix = "still")
        awaitUntil("deltas observed") { sampleReady(source, 2) }
        sample().edges.map { it.id } shouldContainExactly listOf(toRight.id.toString())
    }

    @Test
    fun `despawning a producer untaps its outlet`() {
        val source = spawnSet()
        val sink = spawnSet()
        connect(source.ref, "outlet", sink.ref, "deltaInlet")
        bindAll()
        flow.tappedOutlets shouldBe setOf(source.outlet.ref)

        // despawn unpublishes without unlinking — the drop has to be driven
        // from the unpublish hook, which is what `InspectorModel` wires
        host.managementInlet.call.despawn(source.ref)
        awaitUntil("despawn unpublished the producer") { registry.locate(source.ref) == null }
        flow.dropCell(source.ref)

        flow.tappedOutlets shouldBe emptySet()
    }

    @Test
    fun `closing the collector untaps every outlet`() {
        val first = spawnSet()
        val second = spawnSet()
        val sink = spawnSet()
        connect(first.ref, "outlet", sink.ref, "deltaInlet")
        connect(second.ref, "outlet", sink.ref, "deltaInlet")
        bindAll()
        flow.tappedOutlets.size shouldBe 2

        flow.close()

        flow.tappedOutlets shouldBe emptySet()
        // the handlers are gone from the live graph: traffic after the close is
        // not merely unreported, it is not observed at all — nothing is
        // published, and the still-running graph is untouched
        drive(first, 3, prefix = "orphan")
        drive(second, 3, prefix = "orphan")
        awaitUntil("post-close traffic really flowed") { sampleReady(first, 3) && sampleReady(second, 3) }
        sampleOrNull() shouldBe null
    }

    // --------------------------------------------------------------- helpers

    /**
     * Has [cell]'s outlet emitted at least [n] waves? The outlet's own wave
     * counter is the producer-side ground truth, so this bounds the wait
     * without reaching into the collector it is about to assert on.
     */
    private fun sampleReady(cell: SetCell<String>, n: Int): Boolean =
        cell.outlet.waveState().highWater >= n

    /** A spontaneous producer — its emissions mint fresh waves at hop 0. */
    private class Emitter(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        fun emit(value: String) = outlet.call.propagate(value)
    }

    /** A transparent hop: re-emits inside the incoming context, so the wave carries through. */
    private class Relay(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        init {
            inlet.serve(Propagate { value -> outlet.call.propagate(value) })
        }
    }

    /** A cell that exposes an inlet as a link's *producer*: linking delegates it away. */
    private interface PassThroughApi {
        val passthrough: civictech.cell.port.Use<Propagate<String>>
    }

    private class PassThrough(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, PassThroughApi {
        override val passthrough = registerPort("passthrough", FanInlet.create<Propagate<String>>())
    }

    private class Sink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val seen = CopyOnWriteArrayList<String>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        init {
            inlet.serve(Propagate { value -> seen += value })
        }
    }
}
