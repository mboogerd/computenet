package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.ActorIngress
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * Write visibility through the wave-aligned sink (KE2 §5.5, feature
 * `computenet-zvq3e`; spec 20/22 §The observation frontier, `[22-OBS-01]`/
 * `[22-OBS-02]`): an [ActorIngress] writer stamps its write with
 * [ActorIngress.driveStamped], hands the stamp to
 * [AlignedCompositeCell.visibilityOf], and the handle completes exactly when
 * the sink's per-source frontier reaches that wave (decisions zvq3e-D1..D10).
 *
 * The graph is [AlignedObserveTest]'s (SetCell → FilterCell(even), a sink over
 * `items` + `filtered`, `items` rerouted through the host queue), copied rather
 * than shared so that file stays untouched.
 */
class WriteVisibilityTest {

    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private class Graph {
        val controller = SimulationController(7)
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        val filter = FilterCell<Int> { it % 2 == 0 }

        init {
            val mgmt = host.managementInlet.call
            mgmt.spawn(source)
            mgmt.spawn(filter)
            mgmt.connect(source.ref, "outlet", filter.ref, "inlet")
        }

        val ops: SetOps<Int> get() = host.lookup<IntSetInlet>(source.ref)!!.inlet.call
    }

    /** `AlignedObserveTest`'s reroute: the handshaken link stays, delivery is queued. */
    private fun rerouteThroughHostQueue(
        host: ManagedHost,
        outlet: Subscribe<Propagate<SetDelta<Int>>>,
        inletRef: PortRef,
        target: CellRef,
        portName: String,
    ) {
        val routed: Propagate<SetDelta<Int>> = host.inlet(target, portName)
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** The two-view aligned sink of [AlignedObserveTest], `items` queued behind the fused `filtered`. */
    private fun twoViewSink(
        graph: Graph,
        maxOutstandingHandles: Int = 1024,
        mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT,
    ): AlignedCompositeCell {
        val sink = graph.host.observeAligned(maxOutstandingHandles, mode = mode) {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        rerouteThroughHostQueue(graph.host, graph.source.outlet, sink.inlets.getValue("items").ref, sink.ref, "items")
        return sink
    }

    /**
     * The ack-suppressed control arm: `OperatorAbsorbAckTest.AckLessMapArm`'s
     * shape, set-typed. It swallows every delta and calls neither `propagate`
     * nor `absorbAck`, so a downstream frontier never learns the wave existed.
     */
    private class AckLessSetArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<Int>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Int>>>())

        init {
            inlet.onEach { _ -> /* swallowed: no propagate, no absorbAck */ }
        }
    }

    /** The sink's private monitor, so a test can hold it or ask whether a dependent does. */
    private fun lockOf(sink: AlignedCompositeCell): Any =
        AlignedCompositeCell::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(sink)

    private fun threadsNamed(prefix: String): Set<String> =
        Thread.getAllStackTraces().keys.mapNotNull { it.name.takeIf { n -> n.startsWith(prefix) } }.toSet()

    private fun abandonment(handle: CompletableFuture<Visibility>): VisibilityAbandoned {
        awaitUntil("handle done", 5_000) { handle.isDone }
        return handle.exceptionNow().shouldBeInstanceOf<VisibilityAbandoned>()
    }

    // ---- [KE2-19] the writer side ---------------------------------------------

    @Test
    fun `driveStamped returns the ambient stamp its block ran under and drive is unchanged`() {
        val ingress = ActorIngress(UUID.randomUUID())
        val (w, r) = ingress.driveStamped { CurrentContext.get()!!.timestamp }
        r shouldBe w
        w.sourceId shouldBe ingress.actorId
        w.counter shouldBe ingress.position

        val inside = ingress.drive { CurrentContext.get()!!.timestamp }
        inside shouldBe Timestamp(ingress.actorId, w.counter + 1)
        ingress.position shouldBe w.counter + 1
    }

    // ---- [KE2-20] BS-10: the handle completes when the sink publishes the wave --

    @Test
    fun `a handle completes Visible once the aligned sink publishes its wave, and current already reflects it`() {
        val graph = Graph()
        val sink = twoViewSink(graph)
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops

        // A position consumed but never written: the sink never sees wave `skipped`,
        // so its handle is completed by the retirement of the next wave, and names it.
        val skipped = ingress.next().timestamp
        val (w, _) = ingress.driveStamped { ops.add(2) }
        val early = sink.visibilityOf(skipped)
        val h = sink.visibilityOf(w)
        sink.outstandingHandles shouldBe 2

        val seenInDependent = CompletableFuture<Map<String, Any?>>()
        val completions = AtomicInteger()
        h.thenAccept { completions.incrementAndGet(); seenInDependent.complete(sink.current()) }

        graph.controller.runToIdle()

        h.get(5, SECONDS) shouldBe Visible(w)
        early.get(5, SECONDS) shouldBe Visible(w) // the frontier position at completion, not the handle's wave
        seenInDependent.get(5, SECONDS) shouldBe mapOf("items" to setOf(2), "filtered" to setOf(2))
        completions.get() shouldBe 1
        sink.outstandingHandles shouldBe 0
        sink.close()
    }

    // ---- [KE2-21] BS-11: a vacuous wave, and its ack-less control ----------------

    @Test
    fun `a wave that reaches the sink only as an absorb-ack completes VisibleVacuously`() {
        val graph = Graph()
        val sink = graph.host.observeAligned { set("filtered", graph.filter.ref) }
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops

        val (w, _) = ingress.driveStamped { ops.add(1) } // odd: FilterCell absorb-acks
        val h = sink.visibilityOf(w)
        graph.controller.runToIdle()

        h.get(5, SECONDS) shouldBe VisibleVacuously(w)
        sink.outstandingHandles shouldBe 0
        sink.current() shouldBe mapOf("filtered" to emptySet<Int>())
        sink.bufferedWaves shouldBe 0
        sink.close()
    }

    @Test
    fun `control - the same wave swallowed by an ack-less arm leaves the handle outstanding`() {
        val graph = Graph()
        val arm = AckLessSetArm()
        val mgmt = graph.host.managementInlet.call
        mgmt.spawn(arm)
        mgmt.connect(graph.source.ref, "outlet", arm.ref, "inlet")
        val sink = graph.host.observeAligned { set("swallowed", arm.ref) }
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops

        val (w, _) = ingress.driveStamped { ops.add(1) }
        val h = sink.visibilityOf(w)
        graph.controller.runToIdle()

        (!h.isDone) shouldBe true
        sink.outstandingHandles shouldBe 1
        sink.bufferedWaves shouldBe 0 // the sink never even learned of the wave
        sink.close()
        abandonment(h).reason shouldBe VisibilityAbandoned.Reason.SINK_CLOSED
    }

    // ---- [KE2-23] BS-13: lifecycle ------------------------------------------------

    @Test
    fun `closing the sink abandons an outstanding handle with SINK_CLOSED`() {
        val graph = Graph()
        val sink = twoViewSink(graph)
        val wave = Timestamp(UUID.randomUUID(), 1)
        val h = sink.visibilityOf(wave)
        (!h.isDone) shouldBe true

        sink.close()

        val abandoned = abandonment(h)
        abandoned.reason shouldBe VisibilityAbandoned.Reason.SINK_CLOSED
        abandoned.wave shouldBe wave
        sink.outstandingHandles shouldBe 0
    }

    @Test
    fun `draining the host abandons every outstanding handle with HOST_SHUTDOWN`() {
        val graph = Graph()
        val sink = twoViewSink(graph)
        val s = UUID.randomUUID()
        val hs = (1L..3L).map { sink.visibilityOf(Timestamp(s, it)) }
        sink.outstandingHandles shouldBe 3

        graph.host.managementInlet.call.drainHost()
        graph.controller.runToIdle()

        hs.forEach { abandonment(it).reason shouldBe VisibilityAbandoned.Reason.HOST_SHUTDOWN }
        sink.outstandingHandles shouldBe 0
    }

    // ---- zvq3e-D3 rules 1 and 2: registration against a closed sink or a passed frontier

    @Test
    fun `a handle for a wave the frontier already passed is done on return`() {
        val graph = Graph()
        val sink = twoViewSink(graph)
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops
        val (w1, _) = ingress.driveStamped { ops.add(2) }
        val (w2, _) = ingress.driveStamped { ops.add(1) }
        graph.controller.runToIdle()

        val h = sink.visibilityOf(w1)
        h.isDone shouldBe true
        h.getNow(null) shouldBe Visible(w2) // Timestamp(sourceId, flushedHighWater)
        sink.outstandingHandles shouldBe 0
        sink.close()
    }

    @Test
    fun `a registration on a closed sink is refused, and a reactivated sink registers again`() {
        val graph = Graph()
        val sink = twoViewSink(graph)
        val ctx = object : CellContext {}
        sink.onDeactivate(ctx)

        val refused = sink.visibilityOf(Timestamp(UUID.randomUUID(), 1))
        refused.isDone shouldBe true
        refused.exceptionNow().shouldBeInstanceOf<VisibilityAbandoned>().reason shouldBe
            VisibilityAbandoned.Reason.SINK_CLOSED
        sink.outstandingHandles shouldBe 0

        sink.onActivate(ctx)
        val registered = sink.visibilityOf(Timestamp(UUID.randomUUID(), 1))
        registered.isDone shouldBe false
        sink.outstandingHandles shouldBe 1
        sink.close()
    }

    // ---- [KE2-25]: the bound and the lock-free counter ---------------------------

    @Test
    fun `a registration past the bound is refused, registers nothing, and earlier handles still complete`() {
        val graph = Graph()
        val sink = twoViewSink(graph, maxOutstandingHandles = 2)
        sink.maxOutstandingHandles shouldBe 2
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops
        val (w1, _) = ingress.driveStamped { ops.add(2) }
        val (w2, _) = ingress.driveStamped { ops.add(4) }
        val h1 = sink.visibilityOf(w1)
        val h2 = sink.visibilityOf(w2)

        val h3 = sink.visibilityOf(Timestamp(ingress.actorId, w2.counter + 1))
        h3.isDone shouldBe true
        h3.exceptionNow().shouldBeInstanceOf<VisibilityAbandoned>().reason shouldBe
            VisibilityAbandoned.Reason.BOUND_EXCEEDED
        sink.outstandingHandles shouldBe 2

        graph.controller.runToIdle()
        h1.get(5, SECONDS) shouldBe Visible(w1)
        h2.get(5, SECONDS) shouldBe Visible(w2)
        sink.outstandingHandles shouldBe 0
        sink.close()
    }

    @Test
    fun `the handle counters are readable while another thread holds the sink lock`() {
        val graph = Graph()
        val sink = twoViewSink(graph)
        sink.visibilityOf(Timestamp(UUID.randomUUID(), 1))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lockOf(sink)) { held.countDown(); release.await() }
        }.apply { isDaemon = true; start() }
        held.await(5, SECONDS) shouldBe true
        try {
            val read = CompletableFuture.supplyAsync { sink.outstandingHandles to sink.maxOutstandingHandles }
            read.get(5, SECONDS) shouldBe (1 to 1024)
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        sink.close()
    }

    // ---- zvq3e-D5: where dependents run -------------------------------------------

    @Test
    fun `a blocking dependent stalls neither the host nor a later handle, and runs off the scheduler and the lock`() {
        val dispatchersBefore = threadsNamed("aligned-observe-")
        val graph = Graph()
        val sink = twoViewSink(graph) // no onChange listener anywhere in this test
        val lock = lockOf(sink)
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops
        val schedulerThread = Thread.currentThread() // SimulationController runs tasks on the caller

        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        // One append per dependent run — its thread and its lock-holding together —
        // so the await below and the assertions read the same record. Two separate
        // lists let `size == 2` on the first pass while the second list still
        // lacked its entry (computenet-517q4, the `:379` variant).
        data class Run(val thread: Thread, val heldLock: Boolean)
        val runs = Collections.synchronizedList(mutableListOf<Run>())

        val (w1, _) = ingress.driveStamped { ops.add(2) }
        val h1 = sink.visibilityOf(w1)
        h1.thenAccept {
            runs += Run(Thread.currentThread(), Thread.holdsLock(lock))
            entered.countDown()
            gate.await()
        }
        graph.controller.runToIdle()
        entered.await(5, SECONDS) shouldBe true // the first dependent is now blocked

        val (w2, _) = ingress.driveStamped { ops.add(4) }
        val h2 = sink.visibilityOf(w2)
        h2.thenAccept { runs += Run(Thread.currentThread(), Thread.holdsLock(lock)) }
        graph.controller.runToIdle()

        sink.current() shouldBe mapOf("items" to setOf(2, 4), "filtered" to setOf(2, 4))
        // Wait for h2 WITHOUT a completion method. `h2.get(...)` on this (scheduler)
        // thread made the JDK run h2's still-pending plain dependent right here:
        // `CompletableFuture.get`/`join` call `postComplete()` once they see the
        // result, and whichever thread pops a dependent off the stack runs it — the
        // pool thread that completed h2, or this waiter (computenet-517q4, the
        // `:378` variant; ~1.6% of runs locally). `isDone`/`getNow` only read the
        // result, so the dependent can run only on the thread that completed it.
        awaitUntil("h2 completes while h1's dependent is blocked", 5_000) { h2.isDone }
        h2.getNow(null) shouldBe Visible(w2)
        gate.countDown()
        awaitUntil("both dependents ran", 5_000) { runs.size == 2 }

        val recorded = runs.toList()
        recorded.none { it.thread === schedulerThread } shouldBe true
        recorded.map { it.heldLock } shouldBe listOf(false, false)
        (threadsNamed("aligned-observe-") - dispatchersBefore).shouldBeEmpty()
        sink.close()
    }

    // ---- [KE2-22]/[KE2-26] BS-12: a degraded publication completes VisibleDegraded --

    @Test
    fun `DEGRADE - a recoverable stall on the queued arm completes the handle VisibleDegraded, naming it`() {
        val graph = Graph()
        val sink = twoViewSink(graph, mode = GlitchFreeCell.WaveMode.DEGRADE)
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops
        val itemsLink = sink.inlets.getValue("items").linking.links.single()
        val composites = Collections.synchronizedList(mutableListOf<AlignedComposite>())
        sink.onComposite { composites += it }

        // Stamped, but nothing has propagated yet: `items` is still queued
        // behind `filtered`, which settles synchronously once the controller
        // runs — so suspending `items` first is what makes it the edge this
        // wave releases without.
        val (w, _) = ingress.driveStamped { ops.add(2) }
        val h = sink.visibilityOf(w)
        ProtocolSupport.of(sink.inlets.getValue("items"))
            .deliver(Protocols.Suspension, itemsLink, StallNotice.Stall(StallReason.SUSPENDED))

        graph.controller.runToIdle()

        val dropped = setOf(DroppedEdge("items", itemsLink.id))
        h.get(5, SECONDS) shouldBe VisibleDegraded(w, dropped)
        sink.outstandingHandles shouldBe 0
        // items is still suspended when the wave releases, so the composite
        // published at that moment discloses it — even though a later
        // straggler install (items' own delayed delta, arriving after the
        // wave already retired without it) catches the edge's watermark up
        // and clears the disclosure on `sink.composite()` by the time
        // `runToIdle` returns.
        composites.any { it.droppedEdges == dropped } shouldBe true
        sink.close()
    }

    @Test
    fun `WAIT - a terminal stall on the queued arm re-scopes the wave and completes the handle VisibleDegraded`() {
        val graph = Graph()
        val sink = twoViewSink(graph) // WAIT is the default
        val ingress = ActorIngress(UUID.randomUUID())
        val ops = graph.ops
        val itemsLink = sink.inlets.getValue("items").linking.links.single()

        val (w, _) = ingress.driveStamped { ops.add(2) }
        val h = sink.visibilityOf(w)
        ProtocolSupport.of(sink.inlets.getValue("items"))
            .deliver(Protocols.Suspension, itemsLink, StallNotice.Stall(StallReason.DEAD_LETTERED, timestamp = w))

        graph.controller.runToIdle()

        val dropped = setOf(DroppedEdge("items", itemsLink.id))
        h.get(5, SECONDS) shouldBe VisibleDegraded(w, dropped)
        sink.outstandingHandles shouldBe 0
        sink.violations shouldBe 1L
        sink.close()
    }

    // ---- [KE2-27] BS-15: who witnesses ---------------------------------------------

    @Test
    fun `only the aligned sink is a FrontierWitness`() {
        FrontierWitness::class.java.isAssignableFrom(AlignedCompositeCell::class.java) shouldBe true
        FrontierWitness::class.java.isAssignableFrom(CompositeSink::class.java) shouldBe false
    }
}
