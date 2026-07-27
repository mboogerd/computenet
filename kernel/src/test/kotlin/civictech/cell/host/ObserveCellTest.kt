package civictech.cell.host

import civictech.cell.Timestamp
import civictech.cell.Propagate
import civictech.cell.data.Aggregators
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.GroupByCell
import civictech.cell.graph.graphOf
import civictech.cell.graph.lookupOrThrow
import civictech.cell.graph.refAs
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.cell.observe.observeAll
import civictech.cell.port.Use
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.UnionSetCell

/**
 * The observation sink's HOSTING concern (spec
 * `observation-sink-materialized-edge`): spawn/connect wiring, late-join
 * catch-up sourced from materialized state (not a delta replay), effective-only
 * `onChange`, and thread-safe `current()`. The per-outlet fold correctness is
 * the read-model's own job ([civictech.cell.data.MaterializedViewTest]); these
 * tests never re-assert the fold, only the wrapper over it.
 *
 * T08 finding 4: listener dispatch moved off the host/caller thread onto a
 * dedicated per-sink executor, so tests that assert on listener-driven state
 * poll ([awaitUntil]) rather than reading synchronously right after the call
 * that triggers the fire. `current()`/the fold itself are unaffected — they
 * still settle synchronously under the sink's lock.
 */
class ObserveCellTest {

    /** Minimal lookup proxy exposing a hosted [SetCell]'s write inlet. */
    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private var tagCounter = 0L
    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    // ---- effective-only onChange + late-join, exercised on the sink directly ----

    @Test
    fun `onChange fires once per effective change and a late listener catches up without replay`() {
        // Unhosted: an ObserveCell is eager (serves in init), so we can drive its
        // inlet directly and exercise the effective-only guard deterministically.
        val sink = ObserveCell(View.set<Int>())

        val fires = java.util.Collections.synchronizedList(mutableListOf<Set<Int>>())
        sink.onChange { fires += it }
        // late-join catch-up on registration: one call with the (empty) snapshot
        awaitUntil("registration catch-up") { fires.size >= 1 }
        fires.toList() shouldBe listOf(emptySet())

        val t1 = freshTag()
        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(t1))))   // effective
        // a fresh add-tag for an already-live element: real tag info, no
        // membership change -> the View's Boolean is false -> no fire
        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        sink.inlet.call.propagate(SetDelta(adds = mapOf(2 to setOf(freshTag())))) // effective

        sink.current() shouldBe setOf(1, 2)
        // registration catch-up + two effective changes = three calls, no churn call
        awaitUntil("both effective fires delivered") { fires.size >= 3 }
        fires.toList() shouldBe listOf(emptySet(), setOf(1), setOf(1, 2))

        // a listener added AFTER N changes gets the current snapshot in ONE call,
        // never a replay of the individual historical deltas
        val lateCalls = java.util.Collections.synchronizedList(mutableListOf<Set<Int>>())
        sink.onChange { lateCalls += it }
        awaitUntil("late listener catch-up") { lateCalls.isNotEmpty() }
        lateCalls.toList() shouldBe listOf(setOf(1, 2))
        lateCalls.single() shouldBe sink.current()

        sink.close()
    }

    // ---- spawn / connect / catch-up over a live in-process outlet -------------

    @Test
    fun `observe spawns, connects, and catches up an already-populated outlet from materialized state`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val writer = SetCell<Int>()
        mgmt.spawn(writer)
        val ops = host.lookup<IntSetInlet>(writer.ref)!!.inlet.call

        // N changes happen BEFORE anyone observes
        ops.add(1); ops.add(2); ops.add(3); ops.remove(2)
        controller.runToIdle()

        // observe an outlet that is already at {1,3}: the producer's onLinked
        // push seeds current() with a single state-as-delta — no per-op replay
        val catchUpDeltas = AtomicInteger()
        val seen = host.observe(writer.ref, View.set<Int>()) { catchUpDeltas.incrementAndGet() }
        controller.runToIdle()

        seen.current() shouldBe setOf(1, 3)
        // exactly one listener invocation: the immediate catch-up with the
        // materialized snapshot, not four (one per historical op)
        awaitUntil("catch-up delivered") { catchUpDeltas.get() == 1 }

        // and it keeps folding live changes afterwards
        ops.add(4); ops.remove(1)
        controller.runToIdle()
        seen.current() shouldBe setOf(3, 4)
    }

    @Test
    fun `current equals the batch fold through a live UnionSet pipeline`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val a = SetCell<Int>()
        val b = SetCell<Int>()
        val union = UnionSetCell<Int>()
        mgmt.spawn(a); mgmt.spawn(b); mgmt.spawn(union)
        mgmt.connect(a.ref, "outlet", union.ref, "inlet")
        mgmt.connect(b.ref, "outlet", union.ref, "inlet")

        val sink = host.observe(union.ref, View.set<Int>())

        val aOps = host.lookup<IntSetInlet>(a.ref)!!.inlet.call
        val bOps = host.lookup<IntSetInlet>(b.ref)!!.inlet.call
        aOps.add(1); aOps.add(2); bOps.add(2); bOps.add(3); aOps.remove(1)
        controller.runToIdle()

        // the union of {2} (a) and {2,3} (b) — batch fold of every applied delta
        sink.current() shouldBe setOf(2, 3)
    }

    // ---- concurrent current() under a live threaded host ---------------------

    @Test
    fun `concurrent current reads never observe a torn snapshot`() {
        // A real (virtual-thread) host: writer ops dispatch on scheduler threads
        // while a reader hammers current() from another thread.
        val host = ManagedHost()
        val mgmt = host.managementInlet.call
        val writer = SetCell<Int>()
        mgmt.spawn(writer)
        val ops = host.lookup<IntSetInlet>(writer.ref)!!.inlet.call
        val sink = host.observe(writer.ref, View.set<Int>())

        val n = 500
        val reader = AtomicBoolean(true)
        val readerError = ConcurrentLinkedQueue<Throwable>()
        val readerThread = Thread {
            try {
                while (reader.get()) {
                    // every snapshot is an internally-consistent immutable set:
                    // iterating it must never see a partially-applied delta / CME
                    val snap = sink.current()
                    val copy = HashSet(snap) // forces a full traversal
                    check(copy.size == snap.size)
                }
            } catch (t: Throwable) {
                readerError += t
            }
        }
        readerThread.start()

        repeat(n) { ops.add(it) }
        // settle: poll current() until the fold has caught up
        val deadline = System.currentTimeMillis() + 10_000
        while (sink.current().size < n && System.currentTimeMillis() < deadline) Thread.sleep(5)
        reader.set(false)
        readerThread.join(5_000)

        readerError.shouldBeEmpty()
        sink.current() shouldBe (0 until n).toSet()
    }

    // ---- observeAll composite (point-consistent per outlet) ------------------

    @Test
    fun `observeAll assembles a named composite snapshot from several outlets`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val alice = SetCell<Int>()
        val bob = SetCell<Int>()
        mgmt.spawn(alice); mgmt.spawn(bob)
        val aliceOps = host.lookup<IntSetInlet>(alice.ref)!!.inlet.call
        val bobOps = host.lookup<IntSetInlet>(bob.ref)!!.inlet.call

        aliceOps.add(1); aliceOps.add(2); bobOps.add(9)
        controller.runToIdle()

        val view = host.observeAll {
            set("alice", alice.ref)
            set("bob", bob.ref)
        }
        controller.runToIdle()

        // Construction seeds each named slot from materialized state
        // SYNCHRONOUSLY — `observeAll` never returns a composite whose
        // `current()` is empty (dedicated regression test below). Only
        // *listener* dispatch is off-thread (T08 finding 4).
        view.current() shouldBe mapOf("alice" to setOf(1, 2), "bob" to setOf(9))

        // a composite listener catches up in one call, then tracks per-outlet changes
        val snapshots = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        view.onChange { snapshots += it }
        awaitUntil("composite onChange catch-up") { snapshots.isNotEmpty() }
        snapshots.last() shouldBe mapOf("alice" to setOf(1, 2), "bob" to setOf(9))

        bobOps.add(10)
        controller.runToIdle()
        awaitUntil("composite reflects bob's update") {
            view.current() == mapOf("alice" to setOf(1, 2), "bob" to setOf(9, 10))
        }
        (view.current()["alice"] as Set<*>).shouldContainExactlyInAnyOrder(1, 2)

        view.close()
    }

    /**
     * Regression for the T08 finding 4 follow-up: `CompositeSink` used to
     * populate its named slots as a side effect of registering `onChange` on
     * each per-outlet sink, which fired its catch-up INLINE. Finding 4 made
     * that catch-up an asynchronous submission on the per-sink dispatcher, so
     * for a window after `observeAll` returned, `current()` was `{}` — and
     * `get(name)` threw `no observe named '<name>' (available: [])` rather
     * than returning the state that was already materialized. `slotfinder`'s
     * `/state` route reads exactly that way, so a request landing in the
     * window 500'd. Construction now seeds each slot from `sink.current()`
     * synchronously; this test reads with NO polling on purpose.
     */
    @Test
    fun `observeAll seeds every named slot synchronously — current is never empty on return`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val alice = SetCell<Int>()
        val bob = SetCell<Int>()
        mgmt.spawn(alice); mgmt.spawn(bob)
        host.lookup<IntSetInlet>(alice.ref)!!.inlet.call.add(1)
        host.lookup<IntSetInlet>(bob.ref)!!.inlet.call.add(9)
        controller.runToIdle()

        val view = host.observeAll {
            set("alice", alice.ref)
            set("bob", bob.ref)
        }

        // no awaitUntil, no runToIdle: the composite is fully seeded the
        // instant observeAll returns.
        view.current() shouldBe mapOf("alice" to setOf(1), "bob" to setOf(9))
        view.get<Set<Int>>("alice") shouldBe setOf(1)

        view.close()
    }

    // ---- T08 finding 2: typed observeAll overloads + CompositeSink.get -------

    @Test
    fun `typed observeAll overloads compile-check the element type and get returns it checked`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val (refs, _) = graphOf(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val byLength = spawn("byLength") { ref ->
                GroupByCell(ref = ref, keyFn = { s: String -> s.length }, aggregator = Aggregators.count<String>())
            }
            link(items.cell.outlet, byLength.cell.inlet)
            items.refAs<SetApi<String>>() to byLength.refAs<GroupByApi<String, Int, Long>>()
        }
        val (items, byLength) = refs

        host.lookupOrThrow(items).inlet.call.add("x")
        host.lookupOrThrow(items).inlet.call.add("yy")
        controller.runToIdle()

        val view = host.observeAll {
            set("items", items)          // typed overload: TypedRef<SetApi<String>>
            count("byLength", byLength)  // typed overload: TypedRef<GroupByApi<String, Int, Long>>
        }
        controller.runToIdle()

        awaitUntil("typed composite catch-up") {
            view.current()["items"] == setOf("x", "yy") && view.current()["byLength"] == mapOf(1 to 1L, 2 to 1L)
        }

        // checked accessor: the right shape returns cleanly...
        view.get<Set<String>>("items") shouldBe setOf("x", "yy")
        view.get<Map<Int, Long>>("byLength") shouldBe mapOf(1 to 1L, 2 to 1L)

        // ...a wrong-shaped request throws a real, named message instead of a
        // silent empty degrade.
        val error = shouldThrow<IllegalStateException> { view.get<Map<String, Long>>("items") }
        error.message shouldContain "items"
        error.message shouldContain "set"

        val missing = shouldThrow<IllegalArgumentException> { view.get<Set<String>>("nope") }
        missing.message shouldContain "nope"

        view.close()
    }

    // ---- T08 finding 4: ordering + non-blocking dispatch ----------------------

    @Test
    fun `a subscriber added mid-stream sees catch-up then every subsequent change with no gap or duplicate`() {
        val sink = ObserveCell(View.set<Int>())

        // some changes happen before anyone subscribes
        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        sink.inlet.call.propagate(SetDelta(adds = mapOf(2 to setOf(freshTag()))))

        val seen = java.util.Collections.synchronizedList(mutableListOf<Set<Int>>())
        sink.onChange { seen += it }
        awaitUntil("mid-stream catch-up") { seen.isNotEmpty() }
        // catch-up reflects everything already applied — no replay of the two
        // individual historical deltas
        seen.toList() shouldBe listOf(setOf(1, 2))

        // further changes arrive live, exactly once each, in order
        sink.inlet.call.propagate(SetDelta(adds = mapOf(3 to setOf(freshTag()))))
        sink.inlet.call.propagate(SetDelta(adds = mapOf(4 to setOf(freshTag()))))
        awaitUntil("both live increments delivered") { seen.size >= 3 }

        seen.toList() shouldBe listOf(setOf(1, 2), setOf(1, 2, 3), setOf(1, 2, 3, 4))
        // no gap (every effective change present), no duplicate (each exactly once)
        seen.toList().distinct().size shouldBe seen.size

        sink.close()
    }

    @Test
    fun `a blocking listener delays only its own sink, never the host's dispatch of other cells`() {
        val host = ManagedHost()
        val mgmt = host.managementInlet.call

        val watched = SetCell<Int>()
        val other = SetCell<Int>()
        mgmt.spawn(watched)
        mgmt.spawn(other)
        val watchedOps = host.lookup<IntSetInlet>(watched.ref)!!.inlet.call
        val otherOps = host.lookup<IntSetInlet>(other.ref)!!.inlet.call

        val sink = host.observe(watched.ref, View.set<Int>())
        val blockGate = CountDownLatch(1)
        val listenerEntered = CountDownLatch(1)
        sink.onChange { snap ->
            if (snap.contains(1)) {
                listenerEntered.countDown()
                // simulate a stalled app I/O call (e.g. a slow SSE write)
                blockGate.await(10, TimeUnit.SECONDS)
            }
        }

        watchedOps.add(1)
        // the listener is now blocked inside its own dispatcher thread
        check(listenerEntered.await(5, TimeUnit.SECONDS)) { "listener never entered" }

        // the host's dispatch of a wholly unrelated cell must not be stalled by
        // the blocked observation listener (T08 finding 4's whole point).
        otherOps.add(42)
        val otherSeen = host.observe(other.ref, View.set<Int>())
        awaitUntil("unrelated cell still dispatches while a listener is blocked") {
            otherSeen.current() == setOf(42)
        }

        blockGate.countDown() // release the blocked listener; sinks are ObservationSink
        // (interface) here, not ObserveCell — their daemon dispatcher threads are
        // harmless to leave running for the remainder of this test JVM.
    }
}
