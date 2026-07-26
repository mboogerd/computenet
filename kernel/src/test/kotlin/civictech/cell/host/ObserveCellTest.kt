package civictech.cell.host

import civictech.cell.Timestamp
import civictech.cell.data.MapDelta
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.port.Use
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The observation sink's HOSTING concern (spec
 * `observation-sink-materialized-edge`): spawn/connect wiring, late-join
 * catch-up sourced from materialized state (not a delta replay), effective-only
 * `onChange`, and thread-safe `current()`. The per-outlet fold correctness is
 * the read-model's own job ([civictech.cell.data.MaterializedViewTest]); these
 * tests never re-assert the fold, only the wrapper over it.
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

        val fires = mutableListOf<Set<Int>>()
        sink.onChange { fires += it }
        // late-join catch-up on registration: one call with the (empty) snapshot
        fires shouldBe listOf(emptySet())

        val t1 = freshTag()
        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(t1))))   // effective
        // a fresh add-tag for an already-live element: real tag info, no
        // membership change -> the View's Boolean is false -> no fire
        sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))
        sink.inlet.call.propagate(SetDelta(adds = mapOf(2 to setOf(freshTag())))) // effective

        sink.current() shouldBe setOf(1, 2)
        // registration catch-up + two effective changes = three calls, no churn call
        fires shouldBe listOf(emptySet(), setOf(1), setOf(1, 2))

        // a listener added AFTER N changes gets the current snapshot in ONE call,
        // never a replay of the individual historical deltas
        val lateCalls = mutableListOf<Set<Int>>()
        sink.onChange { lateCalls += it }
        lateCalls shouldBe listOf(setOf(1, 2))
        lateCalls.single() shouldBe sink.current()
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
        catchUpDeltas.get() shouldBe 1

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

        // catch-up seeds each named slot from materialized state
        view.current() shouldBe mapOf("alice" to setOf(1, 2), "bob" to setOf(9))

        // a composite listener catches up in one call, then tracks per-outlet changes
        val snapshots = mutableListOf<Map<String, Any?>>()
        view.onChange { snapshots += it }
        snapshots.last() shouldBe mapOf("alice" to setOf(1, 2), "bob" to setOf(9))

        bobOps.add(10)
        controller.runToIdle()
        view.current() shouldBe mapOf("alice" to setOf(1, 2), "bob" to setOf(9, 10))
        (view.current()["alice"] as Set<*>).shouldContainExactlyInAnyOrder(1, 2)
    }
}
