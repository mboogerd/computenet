package civictech.cell.observe

import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The cost of an *aligned* view nobody observes (computenet-dqy.23), and the two
 * guarantees that paying nothing must not buy: T08 finding 4's off-thread
 * dispatch, and `[22-OBS-01]`/`[22-OBS-02]` alignment.
 *
 * [AlignedCompositeCell] carried [ObserveCell]'s defect line for line
 * (computenet-dqy.19): it minted its listener-dispatch executor in a field
 * initializer, so every wave-aligned view owned an idle single-thread daemon
 * executor for its whole life whether or not anyone had ever called
 * [ObservationSink.onChange] — and [current] with no listener is the shape every
 * in-tree instrument uses. A monotonic per-view, per-run thread leak in a
 * long-lived test JVM, and a `RESTART` or migration minted a fresh one.
 *
 * The dispatcher is now minted on first submission, and a submission only
 * happens when a listener exists. **Both halves are load-bearing**: with only
 * the lazy mint, the eager mint relocates to the first released wave and the
 * leak persists, so [AlignedCompositeCell.publish] skips the fire on an empty
 * listener list and the tests below fold real deltas rather than merely
 * constructing sinks.
 *
 * Held here:
 *
 *  - **no listener ⇒ no thread**, across many built-folded-and-discarded aligned
 *    views and across the deactivate/reactivate cycle a restart performs;
 *  - **listener ⇒ still off-thread** (T08 finding 4, doc/remediation/tickets/
 *    T08-link-typecheck-dsl-dx.md §D): the listener never runs on the thread
 *    that propagated, and a listener that blocks indefinitely cannot pin it;
 *  - **alignment is unchanged either way**: what publishes an aligned composite
 *    is the `latest` swap under the sink lock after a *complete* wave has been
 *    applied to every arm, and the dispatcher only carries the notification. So
 *    an unobserved view still becomes visible one settled wave at a time, and a
 *    listener registering concurrently with a wave release — the mid-wave
 *    hazard a lazy mint could plausibly have opened — cannot observe a torn
 *    composite, because [AlignedCompositeCell.onChange] contends for the same
 *    lock the whole apply-then-publish sequence holds.
 *
 * **Thread accounting.** Assertions are always on the *delta* of live
 * `aligned-observe-*` threads across the body, never on absolute emptiness:
 * `:kernel` runs many test classes per JVM fork and some deliberately leave a
 * subscribed sink's daemon dispatcher running, so "no such thread exists" is not
 * a property of this JVM — "this body created none" is.
 */
class AlignedObserveDispatcherLifecycleTest {

    private var tagCounter = 0L
    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    /** Live platform threads whose name starts with [prefix]. */
    private fun threadsNamed(prefix: String): Set<String> =
        Thread.getAllStackTraces().keys.mapNotNull { it.name.takeIf { n -> n.startsWith(prefix) } }.toSet()

    private fun platformThreadCount(): Int =
        java.lang.management.ManagementFactory.getThreadMXBean().threadCount

    private fun twoViewSink() = AlignedCompositeCell(
        mapOf("a" to View.set<Int>(), "b" to View.set<Int>()),
        mapOf("a" to "set", "b" to "set"),
    )

    // ---- no listener ⇒ no dispatch thread -----------------------------------

    @Test
    fun `many aligned views built, folded and discarded with no listener create no dispatch threads`() {
        val n = 300
        val dispatchersBefore = threadsNamed("aligned-observe-")
        val baseline = platformThreadCount()

        // Held for the duration on purpose: a leak GC could hide is not the leak
        // being measured. These are exactly the views an instrument builds —
        // folded, read through current(), never subscribed.
        val sinks = (0 until n).map { twoViewSink() }
        sinks.forEachIndexed { i, sink ->
            sink.inlets.getValue("a").call.propagate(SetDelta(adds = mapOf(i to setOf(freshTag()))))
            sink.inlets.getValue("b").call.propagate(SetDelta(adds = mapOf(i + n to setOf(freshTag()))))
            // a non-effective delta too: the fold's Boolean is false here, so
            // that path must not dispatch either
            sink.inlets.getValue("a").call.propagate(SetDelta(adds = mapOf(i to setOf(freshTag()))))
        }

        // the folds all really happened — these are working views, not stubs
        sinks.forEachIndexed { i, sink ->
            sink.current() shouldBe mapOf("a" to setOf(i), "b" to setOf(i + n))
        }

        // ...and not one of them minted a dispatcher.
        (threadsNamed("aligned-observe-") - dispatchersBefore).shouldBeEmpty()

        // The JVM thread count does not grow with the number of views. The bound
        // is generous (unrelated JVM threads come and go) but far below n: the
        // old behaviour was exactly +n.
        val growth = platformThreadCount() - baseline
        growth shouldBeLessThanOrEqual (n / 10)

        sinks.forEach { it.close() }
        (threadsNamed("aligned-observe-") - dispatchersBefore).shouldBeEmpty()
    }

    @Test
    fun `an unobserved aligned view stays thread-free across the deactivate-reactivate cycle a restart performs`() {
        // reopen() must not mint a replacement eagerly either: a restarted or
        // migrated aligned sink nobody observes is still nobody's business.
        val dispatchersBefore = threadsNamed("aligned-observe-")
        val ctx = object : CellContext {}
        val sinks = (0 until 100).map { twoViewSink() }

        sinks.forEach { sink ->
            sink.inlets.getValue("a").call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
            sink.onDeactivate(ctx)
            sink.onActivate(ctx)
            sink.inlets.getValue("b").call.propagate(SetDelta(adds = mapOf(2 to setOf(freshTag()))))
        }

        // the fold keeps working across the cycle (it always did — only dispatch
        // is gated by close/reopen)
        sinks.forEach { it.current() shouldBe mapOf("a" to setOf(1), "b" to setOf(2)) }
        (threadsNamed("aligned-observe-") - dispatchersBefore).shouldBeEmpty()
    }

    // ---- listener ⇒ T08 finding 4 preserved ---------------------------------

    @Test
    fun `registering a listener mints exactly one dispatcher and invokes off the propagating thread`() {
        val sink = twoViewSink()
        val own = "aligned-observe-${sink.ref.id}"
        threadsNamed(own).shouldBeEmpty()

        val invokedOn = AtomicReference<String>()
        sink.onChange { invokedOn.set(Thread.currentThread().name) }

        awaitUntil("registration catch-up delivered") { invokedOn.get() != null }
        // T08 finding 4: never the registering/host thread, always this sink's own
        invokedOn.get() shouldNotBe Thread.currentThread().name
        threadsNamed(own).size shouldBe 1

        val propagating = Thread.currentThread().name
        invokedOn.set(null)
        sink.inlets.getValue("a").call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        awaitUntil("change fire delivered") { invokedOn.get() != null }
        invokedOn.get() shouldNotBe propagating

        // still one — a second registration reuses the dispatcher rather than
        // minting a second
        sink.onChange { }
        threadsNamed(own).size shouldBe 1

        sink.close()
    }

    /**
     * Deliberately asserted on *state*, not on elapsed time: the two assertions
     * that carry this test are [blockedOn] ≠ the propagating thread and
     * `stillParked == true` at the moment the propagating thread has already
     * settled five further folds. Both are what make it non-vacuous. An inline
     * dispatch (the pre-T08 shape, and the shape a careless "skip the executor
     * when it is cheap" optimisation of a lazy mint would reintroduce) would run
     * the listener on the propagating thread and could not return from
     * `propagate` until the listener's own 60s bail-out elapsed — so it would
     * reach these assertions with `blockedOn` equal to this thread and the
     * listener no longer parked, and FAIL. Without them the test passes under
     * inline dispatch too, merely one minute later, which would prove nothing.
     * Verified against exactly that patch (dqy.19's reviewer's method): with
     * `target.execute(block)` replaced by `block()` this test FAILS.
     */
    @Test
    fun `a listener blocked indefinitely cannot pin the thread that propagates an aligned fold`() {
        val sink = twoViewSink()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedOn = AtomicReference<String>()
        val stillParked = AtomicBoolean(false)
        val lastSeen = AtomicReference<Map<String, Any?>>()
        sink.onChange { snap ->
            @Suppress("UNCHECKED_CAST")
            val a = snap["a"] as Set<Int>
            if (a.contains(1) && entered.count > 0L) {
                blockedOn.set(Thread.currentThread().name)
                stillParked.set(true)
                entered.countDown()
                // a stalled app I/O call (a slow SSE write, say)
                release.await(60, TimeUnit.SECONDS)
                stillParked.set(false)
            }
            lastSeen.set(snap)
        }

        val propagating = Thread.currentThread().name
        sink.inlets.getValue("a").call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        check(entered.await(30, TimeUnit.SECONDS)) { "listener never entered" }

        // The listener is now parked inside the dispatcher. The propagating
        // thread — this one — must stay free: further folds settle and are
        // readable while the listener is still stuck, which is only possible if
        // dispatch is off-thread.
        repeat(5) { i ->
            sink.inlets.getValue("b").call.propagate(SetDelta(adds = mapOf(i + 2 to setOf(freshTag()))))
        }
        val settled = mapOf<String, Any?>("a" to setOf(1), "b" to setOf(2, 3, 4, 5, 6))
        sink.current() shouldBe settled
        // T08 finding 4, stated as the two facts that can only hold off-thread:
        // the listener ran somewhere else, and it is STILL inside its blocking
        // call now that this thread has propagated five more times.
        blockedOn.get() shouldNotBe propagating
        stillParked.get() shouldBe true
        entered.count shouldBe 0L // still parked; nothing above unblocked it

        release.countDown()
        // the queued fires then arrive, ending at the settled composite: the
        // delay was the listener's own, never the graph's
        awaitUntil("the released listener catches up to the settled composite") { lastSeen.get() == settled }

        sink.close()
    }

    @Test
    fun `a subscribed aligned sink reopened after a restart dispatches again on a freshly minted executor`() {
        // reopen() now defers the mint, so this is the path that proves deferral
        // did not turn a restart into permanent deafness (the regression T08
        // finding 4's own lifecycle fix was added for).
        val ctx = object : CellContext {}
        val sink = twoViewSink()
        val own = "aligned-observe-${sink.ref.id}"
        val fires = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { fires += it }
        awaitUntil("registration catch-up") { fires.isNotEmpty() }

        sink.onDeactivate(ctx)
        sink.onActivate(ctx)
        // nothing dispatched yet since the reopen, so nothing was minted yet
        // either; the superseded executor is only draining.
        awaitUntil("the closed dispatcher terminates") { threadsNamed(own).isEmpty() }

        sink.inlets.getValue("a").call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        awaitUntil("post-reactivation change still reaches the listener") { fires.size >= 2 }
        fires.last() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())
        threadsNamed(own).size shouldBe 1

        sink.close()
    }

    // ---- alignment survives the lazy dispatcher ------------------------------

    /**
     * [AlignedObserveTest]'s graph and reroute device, reduced to what these
     * tests need: one `SetCell` source fanning to two named views — `items`
     * straight off the source and `filtered` through an even-keeping
     * [FilterCell] — with the `items` arm's *delivery* rerouted through the
     * host queue so it lags the fused `filtered` arm and the sink must hold,
     * order and release a deep buffer. `filtered == items.filter(even)` is
     * therefore the alignment invariant for every settled per-source frontier,
     * and fails for any composite assembled from two different waves. (Only the
     * non-absorbing arm is queued: see [AlignedObserveTest]'s CC1 note on why
     * queueing the absorbing arm instead breaks per-edge FIFO in the *harness*.)
     */
    private class Graph(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        val filter = FilterCell<Int> { it % 2 == 0 }

        init {
            val mgmt = host.managementInlet.call
            mgmt.spawn(source)
            mgmt.spawn(filter)
            mgmt.connect(source.ref, "outlet", filter.ref, "inlet")
        }
    }

    /** Minimal lookup proxy exposing a hosted [SetCell]'s write inlet. */
    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private fun ops(graph: Graph): SetOps<Int> =
        graph.host.lookup<IntSetInlet>(graph.source.ref)!!.inlet.call

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

    private fun alignedGraph(seed: Long): Pair<Graph, AlignedCompositeCell> {
        val graph = Graph(seed)
        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        rerouteThroughHostQueue(graph.host, graph.source.outlet, sink.inlets.getValue("items").ref, sink.ref, "items")
        return graph to sink
    }

    private fun evens(upTo: Int) = (1..upTo).filter { it % 2 == 0 }.toSet()

    private fun mixesWaves(composite: Map<String, Any?>): Boolean {
        val items = composite["items"] as Set<*>
        val filtered = composite["filtered"] as Set<*>
        return filtered != items.filter { (it as Int) % 2 == 0 }.toSet()
    }

    @Test
    fun `an unobserved aligned view still becomes visible one settled wave at a time`() {
        // The alignment half of the fix's safety argument: publication is the
        // `latest` swap under the sink lock, which happens with or without a
        // listener, so a thread-free sink is not a *delayed* or *torn* sink.
        // current() is sampled after every partial drain step, so a mixed-wave
        // composite would have to be caught here — the same invariant
        // AlignedObserveTest asserts through a listener, asserted with no
        // listener (and therefore no dispatcher) in existence at all.
        val dispatchersBefore = threadsNamed("aligned-observe-")
        val waves = 20

        for (seed in 0L until 25L) {
            val (graph, sink) = alignedGraph(seed)
            val ops = ops(graph)
            val rnd = Random(seed)
            val seen = mutableListOf<Map<String, Any?>>()

            for (n in 1..waves) {
                ops.add(n)
                repeat(rnd.nextInt(4)) {
                    graph.controller.step()
                    seen += sink.current()
                }
                seen += sink.current()
            }
            graph.controller.runToIdle()
            seen += sink.current()

            // every read was one settled wave (22-OBS-01)...
            seen.forEach { mixesWaves(it) shouldBe false }
            // ...and the succession of distinct reads is the per-source monotone
            // publication order (22-OBS-02): each is exactly the aligned fold of
            // waves 1..k for a strictly increasing k, so no read was a wave
            // early, a wave late, or a blend.
            val chain = seen.filterIndexed { i, c -> i == 0 || c != seen[i - 1] }
            val ks = chain.map { (it["items"] as Set<*>).size }
            ks shouldBe ks.sorted().distinct()
            chain.forEach { c ->
                val k = (c["items"] as Set<*>).size
                c shouldBe mapOf<String, Any?>("items" to (1..k).toSet(), "filtered" to evens(k))
            }
            // The view really advanced through waves rather than jumping from
            // empty to settled, so the invariant above is not vacuous. The count
            // is deterministic per seed (seeded scheduler, seeded drain
            // schedule, one thread, no dispatcher in existence): measured
            // minimum across these 25 seeds is 12 of a possible 21, so this
            // bound has margin and is not a timing assertion.
            chain.size shouldBeGreaterThanOrEqual (waves / 2)
            // ...and it ends fully folded: nothing was left buffered because
            // nobody was listening.
            ks.last() shouldBe waves
            sink.current() shouldBe mapOf<String, Any?>("items" to (1..waves).toSet(), "filtered" to evens(waves))
            sink.bufferedWaves shouldBe 0
            sink.unmatchedDeltas shouldBe 0L

            sink.close()
        }

        // 25 aligned views over 25 seeds, none of them observed, no threads.
        (threadsNamed("aligned-observe-") - dispatchersBefore).shouldBeEmpty()
    }

    @Test
    fun `listeners registered concurrently with wave releases mint one dispatcher and never see a torn composite`() {
        // The mid-wave registration hazard a lazy mint could plausibly open:
        // eight registrations race the releases. onChange contends for the same
        // lock flushReady holds across apply-every-arm-then-publish, so each
        // catch-up snapshot is a composite from before a wave or after it, never
        // inside it — and whichever order the lock grants, a listener either
        // lands in that release's fired list or takes its catch-up from the
        // already-published snapshot, so none of them misses the settled state.
        val waves = 30
        val (graph, sink) = alignedGraph(seed = 7)
        val own = "aligned-observe-${sink.ref.id}"
        val registrars = 8
        val seen = (0 until registrars).map { Collections.synchronizedList(mutableListOf<Map<String, Any?>>()) }
        val barrier = CyclicBarrier(registrars + 1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val threads = (0 until registrars).map { i ->
            Thread {
                try {
                    barrier.await(30, TimeUnit.SECONDS)
                    sink.onChange { seen[i] += it }
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }

        barrier.await(30, TimeUnit.SECONDS)
        val ops = ops(graph)
        val rnd = Random(7)
        for (n in 1..waves) {
            ops.add(n)
            repeat(rnd.nextInt(4)) { graph.controller.step() }
        }
        graph.controller.runToIdle()
        threads.forEach { it.join(30_000) }
        errors.shouldBeEmpty()

        // one further wave after every registration has returned, so the settled
        // composite is reachable by every listener whenever it joined
        ops.add(waves + 1)
        graph.controller.runToIdle()
        val settled = mapOf<String, Any?>(
            "items" to (1..waves + 1).toSet(),
            "filtered" to evens(waves + 1),
        )
        sink.current() shouldBe settled

        awaitUntil("every concurrently registered listener reached the settled composite") {
            seen.all { it.isNotEmpty() && it.last() == settled }
        }
        // 22-OBS-01 for every listener, at every point it was delivered
        seen.forEach { deliveries -> deliveries.toList().forEach { mixesWaves(it) shouldBe false } }
        // one executor for eight concurrent registrations
        threadsNamed(own).size shouldBe 1

        sink.close()
    }
}
