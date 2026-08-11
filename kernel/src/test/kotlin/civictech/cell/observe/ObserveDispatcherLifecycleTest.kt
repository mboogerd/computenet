package civictech.cell.observe

import civictech.cell.CellContext
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The cost of a view nobody observes (computenet-dqy.19), and the T08 finding 4
 * guarantee that paying nothing must not buy.
 *
 * [ObserveCell] used to mint its listener-dispatch executor in a field
 * initializer, so *every* view owned an idle single-thread daemon executor for
 * its whole life whether or not anyone had ever called
 * [ObservationSink.onChange]. That is a monotonic per-graph thread leak inside a
 * long-lived test JVM — the conformance suite builds and discards a graph per
 * scenario per seed, and both of the kernel's in-tree instruments (concord's
 * recorded views, the inspector's observation sinks) read through
 * [ObservationSink.current] with **no** listener at all, so each of those views
 * leaked a thread that could never have anything to run.
 *
 * The dispatcher is now minted on first submission, and a submission only
 * happens when a listener exists. These tests hold both ends of that:
 *
 *  - **no listener ⇒ no thread**, measured as JVM thread count across many
 *    built-and-discarded views, including the folding and close/reopen paths
 *    (the cheap fix would have been to defer only until the first *fold*);
 *  - **listener ⇒ still off-thread**, i.e. T08 finding 4 survives: the listener
 *    never runs on the thread that propagated, and a listener that blocks
 *    indefinitely cannot pin that thread. Trading finding 4 away for the thread
 *    saving would be the wrong fix, so it is asserted here rather than left to
 *    the hosting test ([civictech.cell.host.ObserveCellTest]).
 *
 * **Thread accounting.** Assertions are always on the *delta* of live
 * dispatcher-named threads across the body, never on an absolute emptiness:
 * `:kernel` runs many test classes per JVM fork and some deliberately leave a
 * subscribed sink's daemon dispatcher running, so "no `observe-cell-*` thread
 * exists" is not a property of this JVM — "this body created none" is.
 * [Thread.getAllStackTraces] and `ThreadMXBean.threadCount` both see platform
 * threads only, so a host's virtual scheduler threads can neither mask nor fake
 * the result; these tests drive unhosted sinks anyway, precisely so the only
 * threads in play are the ones under test.
 */
class ObserveDispatcherLifecycleTest {

    private var tagCounter = 0L
    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    /** Live platform threads whose name starts with [prefix]. */
    private fun threadsNamed(prefix: String): Set<String> =
        Thread.getAllStackTraces().keys.mapNotNull { it.name.takeIf { n -> n.startsWith(prefix) } }.toSet()

    private fun platformThreadCount(): Int =
        java.lang.management.ManagementFactory.getThreadMXBean().threadCount

    // ---- no listener ⇒ no dispatch thread -----------------------------------

    @Test
    fun `many views built, folded and discarded with no listener create no dispatch threads`() {
        val n = 300
        val dispatchersBefore = threadsNamed("observe-cell-")
        val baseline = platformThreadCount()

        // Held for the duration on purpose: a leak GC could hide is not the leak
        // being measured. These are exactly the views concord and the inspector
        // build — folded, read through current(), never subscribed.
        val sinks = (0 until n).map { ObserveCell(View.set<Int>()) }
        sinks.forEachIndexed { i, sink ->
            sink.inlet.call.propagate(SetDelta(adds = mapOf(i to setOf(freshTag()))))
            sink.inlet.call.propagate(SetDelta(adds = mapOf(i + n to setOf(freshTag()))))
            // a non-effective delta too: the fold's Boolean is false here, so
            // that path must not dispatch either
            sink.inlet.call.propagate(SetDelta(adds = mapOf(i to setOf(freshTag()))))
        }

        // the folds all really happened — these are working views, not stubs
        sinks.forEachIndexed { i, sink -> sink.current() shouldBe setOf(i, i + n) }

        // ...and not one of them minted a dispatcher.
        (threadsNamed("observe-cell-") - dispatchersBefore).shouldBeEmpty()

        // The JVM thread count does not grow with the number of views. The bound
        // is generous (unrelated JVM threads come and go) but far below n: the
        // old behaviour was exactly +n.
        val growth = platformThreadCount() - baseline
        growth shouldBeLessThanOrEqual (n / 10)

        sinks.forEach { it.close() }
        (threadsNamed("observe-cell-") - dispatchersBefore).shouldBeEmpty()
    }

    @Test
    fun `an unobserved view stays thread-free across the deactivate-reactivate cycle a restart performs`() {
        // reopen() must not mint a replacement eagerly either: a restarted or
        // migrated sink nobody observes is still nobody's business.
        val dispatchersBefore = threadsNamed("observe-cell-")
        val ctx = object : CellContext {}
        val sinks = (0 until 100).map { ObserveCell(View.set<Int>()) }

        sinks.forEach { sink ->
            sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
            sink.onDeactivate(ctx)
            sink.onActivate(ctx)
            sink.inlet.call.propagate(SetDelta(adds = mapOf(2 to setOf(freshTag()))))
        }

        // the fold keeps working across the cycle (it always did — only dispatch
        // is gated by close/reopen)
        sinks.forEach { it.current() shouldBe setOf(1, 2) }
        (threadsNamed("observe-cell-") - dispatchersBefore).shouldBeEmpty()
    }

    @Test
    fun `a composite read only through current owns no dispatch thread`() {
        val compositesBefore = threadsNamed("observe-composite-")
        val sinkA = ObserveCell(View.set<Int>())
        val sinkB = ObserveCell(View.set<Int>())
        sinkA.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))

        val composite = CompositeSink(mapOf("a" to sinkA, "b" to sinkB), mapOf("a" to "set", "b" to "set"))

        // The composite tracks its members through their onChange, so THOSE are
        // observed views and legitimately own a dispatcher each; the composite
        // itself has no listener, so it owns none.
        sinkB.inlet.call.propagate(SetDelta(adds = mapOf(9 to setOf(freshTag()))))
        awaitUntil("composite tracks b through its member listener") {
            composite.current() == mapOf("a" to setOf(1), "b" to setOf(9))
        }
        (threadsNamed("observe-composite-") - compositesBefore).shouldBeEmpty()

        // and a composite that IS subscribed still dispatches off-thread
        val seenOn = AtomicReference<String>()
        composite.onChange { seenOn.set(Thread.currentThread().name) }
        awaitUntil("composite catch-up delivered") { seenOn.get() != null }
        seenOn.get() shouldNotBe Thread.currentThread().name
        (threadsNamed("observe-composite-") - compositesBefore).size shouldBe 1

        composite.close()
        sinkA.close(); sinkB.close()
    }

    // ---- listener ⇒ T08 finding 4 preserved ---------------------------------

    @Test
    fun `registering a listener mints exactly one dispatcher and invokes off the propagating thread`() {
        val sink = ObserveCell(View.set<Int>())
        val own = "observe-cell-${sink.ref.id}"
        threadsNamed(own).shouldBeEmpty()

        val invokedOn = AtomicReference<String>()
        sink.onChange { invokedOn.set(Thread.currentThread().name) }

        awaitUntil("registration catch-up delivered") { invokedOn.get() != null }
        // T08 finding 4: never the registering/host thread, always this sink's own
        invokedOn.get() shouldNotBe Thread.currentThread().name
        threadsNamed(own).size shouldBe 1

        val propagating = Thread.currentThread().name
        invokedOn.set(null)
        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        awaitUntil("change fire delivered") { invokedOn.get() != null }
        invokedOn.get() shouldNotBe propagating

        // still one — a second registration reuses the dispatcher rather than
        // minting a second
        sink.onChange { }
        threadsNamed(own).size shouldBe 1

        sink.close()
    }

    @Test
    fun `a listener blocked indefinitely cannot pin the thread that propagates`() {
        val sink = ObserveCell(View.set<Int>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lastSeen = AtomicReference<Set<Int>>()
        sink.onChange { snap ->
            if (snap.contains(1) && entered.count > 0L) {
                entered.countDown()
                // a stalled app I/O call (a slow SSE write, say)
                release.await(60, TimeUnit.SECONDS)
            }
            lastSeen.set(snap)
        }

        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        check(entered.await(30, TimeUnit.SECONDS)) { "listener never entered" }

        // The listener is now parked inside the dispatcher. The propagating
        // thread — this one — must stay free: further folds settle and are
        // readable while the listener is still stuck, which is only possible if
        // dispatch is off-thread.
        repeat(5) { i -> sink.inlet.call.propagate(SetDelta(adds = mapOf(i + 2 to setOf(freshTag())))) }
        val settled = setOf(1, 2, 3, 4, 5, 6)
        sink.current() shouldBe settled
        entered.count shouldBe 0L   // still parked; nothing above unblocked it

        release.countDown()
        // the queued fires then arrive, ending at the settled snapshot: the
        // delay was the listener's own, never the graph's
        awaitUntil("the released listener catches up to the settled state") { lastSeen.get() == settled }

        sink.close()
    }

    @Test
    fun `a subscribed sink reopened after a restart dispatches again on a freshly minted executor`() {
        // reopen() now defers the mint, so this is the path that proves deferral
        // did not turn a restart into permanent deafness (the regression T08
        // finding 4's own lifecycle fix was added for).
        val ctx = object : CellContext {}
        val sink = ObserveCell(View.set<Int>())
        val own = "observe-cell-${sink.ref.id}"
        val fires = Collections.synchronizedList(mutableListOf<Set<Int>>())
        sink.onChange { fires += it }
        awaitUntil("registration catch-up") { fires.isNotEmpty() }

        sink.onDeactivate(ctx)
        sink.onActivate(ctx)
        // nothing dispatched yet since the reopen, so nothing was minted yet
        // either; the superseded executor is only draining.
        awaitUntil("the closed dispatcher terminates") { threadsNamed(own).isEmpty() }

        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        awaitUntil("post-reactivation change still reaches the listener") { fires.size >= 2 }
        fires.last() shouldBe setOf(1)
        threadsNamed(own).size shouldBe 1

        sink.close()
    }

    // ---- the lazy mint is race-free ----------------------------------------

    @Test
    fun `concurrent registrations mint one dispatcher and none of them misses the settled state`() {
        val sink = ObserveCell(View.set<Int>())
        val own = "observe-cell-${sink.ref.id}"
        val registrars = 8
        val changes = 200
        val lastSeen = (0 until registrars).map { AtomicReference<Set<Int>>() }
        val barrier = CyclicBarrier(registrars + 1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val threads = (0 until registrars).map { i ->
            Thread {
                try {
                    barrier.await(30, TimeUnit.SECONDS)
                    sink.onChange { lastSeen[i].set(it) }
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }

        // Registrations race the folds. Whichever order the sink lock grants, a
        // listener either lands in that fold's firing list or takes its catch-up
        // from the already-updated snapshot — neither order drops a
        // notification, so every listener must end at the settled state.
        barrier.await(30, TimeUnit.SECONDS)
        repeat(changes) { sink.inlet.call.propagate(SetDelta(adds = mapOf(it to setOf(freshTag())))) }
        threads.forEach { it.join(30_000) }
        errors.shouldBeEmpty()

        // one further change after every registration has returned, so the
        // settled state is reachable by every listener whenever it joined
        sink.inlet.call.propagate(SetDelta(adds = mapOf(-1 to setOf(freshTag()))))
        val settled = sink.current()

        awaitUntil("every concurrently registered listener reached the settled state") {
            lastSeen.all { it.get() == settled }
        }
        // one executor for eight concurrent registrations
        threadsNamed(own).size shouldBe 1

        sink.close()
    }
}
