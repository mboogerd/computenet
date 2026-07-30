package civictech.cell.host

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.Provenance
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Stateful
import civictech.cell.data.SetCell
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-KERNEL: the answers [ManagedHost.readState] gives for a cell that is not
 * simply hot — the decisions the kernel previously did not make, which left the
 * instrument guessing `HOT`/`SUSPENDED`/`DRAINED`/`HELD` from registry metadata
 * and skipping everything but the first.
 *
 * This is the [SnapshotOfHardeningTest] of the paged read: every edge that
 * `snapshotOf` can only report as a null completion is asserted here as a named
 * result, plus the two properties that keep a diagnostic read cheap — an
 * abandoned read never enters the cell, and a drained host is answered without
 * scheduling anything on any cell thread.
 */
class BoundedReadProvenanceTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private fun setCell(n: Int): SetCell<String> {
        val cell = SetCell<String>()
        repeat(n) { cell.inlet.call.add("k$it") }
        return cell.also { host.managementInlet.call.spawn(it) }
    }

    private fun read(ref: CellRef, request: StateRead = StateRead()): StateReadResult {
        val pending = host.readState(ref, request)
        controller.runToIdle()
        return pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------- suspended

    @Test
    fun `a cell suspended mid-walk is answered from the live cell with LIVE_SUSPENDED, and the walk completes`() {
        val cell = setCell(20)

        val first = (read(cell.ref, StateRead(limit = 6)) as StateReadResult.Page).page
        first.provenance shouldBe Provenance.LIVE

        // only the cell's data intake is parked; its fold is quiescent by
        // construction, which makes it the *most* stable thing to read
        host.managementInlet.call.suspend(cell.ref)
        controller.runToIdle()
        host.isSuspended(cell.ref).shouldBeTrue()

        var cursor: Cursor? = first.next
        val pages = mutableListOf<StatePage>()
        while (cursor != null) {
            val page = (read(cell.ref, StateRead(cursor = cursor, limit = 6)) as StateReadResult.Page).page
            pages += page
            cursor = page.next
        }

        pages.size shouldBeGreaterThan 1
        pages.forEach {
            it.provenance shouldBe Provenance.LIVE_SUSPENDED
            // a parked fold cannot advance, so the stamp cannot either
            it.frontier shouldBe first.frontier
        }
        // and the walk really did complete over the whole cell
        (first.entries + pages.flatMap { it.entries }).size shouldBe 20
    }

    // --------------------------------------------------------------- drained

    @Test
    fun `a drained host answers from its retained checkpoint, with CHECKPOINT provenance and no cell-thread task`() {
        val cell = setCell(20)
        val live = cell.snapshot()

        host.managementInlet.call.drainHost()
        controller.runToIdle()
        host.isDrained.shouldBeTrue()

        // refused without an explicit opt-in: a `snapshot()` blob is not a page,
        // and nothing in the kernel can slice an opaque Serializable
        val refused = host.readState(cell.ref, StateRead())
        refused.isDone.shouldBeTrue()
        refused.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.CHECKPOINT_NOT_BOUNDED)

        val answered = host.readState(cell.ref, StateRead(allowWholeCopy = true))
        // answered on the caller's thread: nothing was scheduled anywhere
        answered.isDone.shouldBeTrue()
        controller.step().shouldBeFalse()

        val result = answered.get()
        result.shouldBeInstanceOf<StateReadResult.Unbounded>()
        result.provenance shouldBe Provenance.CHECKPOINT
        result.state shouldBe live
    }

    // ------------------------------------------------------------- migrating

    @Test
    fun `a ref held for a migration flip is never answered from the stale local object`() {
        val cell = setCell(5)

        registry.hold(cell.ref)
        val held = host.readState(cell.ref, StateRead())
        held.isDone.shouldBeTrue()
        held.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.MIGRATING)

        // released, it reads normally again — the refusal tracks the flip window,
        // not the cell
        registry.release(cell.ref)
        read(cell.ref).shouldBeInstanceOf<StateReadResult.Page>()
    }

    @Test
    fun `a migrated cell is answered MIGRATING by the host it left`() {
        val cell = setCell(5)
        val target = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        host.managementInlet.call.migrate(target.managementInlet)
        controller.runToIdle()

        val left = host.readState(cell.ref, StateRead(allowWholeCopy = true))
        left.isDone.shouldBeTrue()
        left.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.MIGRATING)

        // the authoritative instance answers
        val pending = target.readState(cell.ref, StateRead(limit = 100))
        controller.runToIdle()
        pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeInstanceOf<StateReadResult.Page>()
    }

    // --------------------------------------------------- unhosted / not stateful

    @Test
    fun `an unknown ref and a non-Stateful cell are named, not silently null`() {
        val unknown = host.readState(CellRef(UUID.randomUUID()), StateRead())
        unknown.isDone.shouldBeTrue()
        unknown.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.NOT_HOSTED)

        val plain = PlainCell().also { host.managementInlet.call.spawn(it) }
        val notStateful = host.readState(plain.ref, StateRead())
        notStateful.isDone.shouldBeTrue()
        notStateful.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.NOT_STATEFUL)
    }

    // ----------------------------------------------------------- not bounded

    @Test
    fun `a Stateful-but-not-BoundedStateful cell is refused, and copied whole only on explicit opt-in`() {
        val cell = CountingStatefulCell().also { host.managementInlet.call.spawn(it) }
        cell.snapshots.set(0)

        val refused = host.readState(cell.ref, StateRead())
        refused.isDone.shouldBeTrue()
        refused.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.NOT_BOUNDED)
        // the refusal cost the cell nothing at all
        controller.runToIdle()
        cell.snapshots.get() shouldBe 0

        val opted = read(cell.ref, StateRead(allowWholeCopy = true))
        opted shouldBe StateReadResult.Unbounded(1, Provenance.LIVE)
        cell.snapshots.get() shouldBe 1
    }

    // ------------------------------------------------------- abandoned / dead

    @Test
    fun `a page cancelled before its task runs never enters the cell`() {
        val cell = CountingBoundedCell().also { host.managementInlet.call.spawn(it) }
        cell.reads.set(0)

        val pending = host.readState(cell.ref, StateRead())
        pending.isDone.shouldBeFalse()
        pending.cancel(false).shouldBeTrue()

        controller.runToIdle()

        cell.reads.get() shouldBe 0
        pending.isCancelled.shouldBeTrue()
    }

    @Test
    fun `an uncancelled page on the same path does enter the cell`() {
        // the control for the test above: same host, same queueing, cancellation
        // the only difference
        val cell = CountingBoundedCell().also { host.managementInlet.call.spawn(it) }
        cell.reads.set(0)

        read(cell.ref).shouldBeInstanceOf<StateReadResult.Page>()

        cell.reads.get() shouldBe 1
    }

    @Test
    fun `a terminated scheduler answers rather than throwing`() {
        val scheduler = VirtualThreadScheduler("BoundedReadProvenanceTest-dead")
        val dead = ManagedHost(scheduler = scheduler, registry = LocationRegistry())
        val cell = SetCell<String>().also { dead.managementInlet.call.spawn(it) }
        scheduler.shutdown()

        // shutdown interrupts the drain thread, which marks itself terminated
        // asynchronously — bounded wait, no sleep-and-hope
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        var answer: StateReadResult
        while (true) {
            val pending = dead.readState(cell.ref, StateRead())
            if (pending.isDone) {
                answer = pending.get()
                if (answer == StateReadResult.Unavailable(StateReadResult.Reason.SCHEDULER_TERMINATED)) break
            }
            check(System.nanoTime() < deadline) { "scheduler did not report termination within ${TIMEOUT_MS}ms" }
        }

        // never thrown, never left pending: a dead host has no state to read
        answer shouldBe StateReadResult.Unavailable(StateReadResult.Reason.SCHEDULER_TERMINATED)
    }

    @Test
    fun `a cell whose readBounded throws is reported, not propagated to the caller`() {
        val cell = ThrowingBoundedCell().also { host.managementInlet.call.spawn(it) }

        read(cell.ref) shouldBe StateReadResult.Unavailable(StateReadResult.Reason.READ_FAILED)
    }

    // --------------------------------------------------------------- fixtures

    private class PlainCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("unused")
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
    }

    /** Mirrors [SnapshotOfHardeningTest]'s counting cell: [Stateful], never [BoundedStateful]. */
    private class CountingStatefulCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        val snapshots = AtomicInteger()
        override fun snapshot(): Serializable = snapshots.incrementAndGet()
        override fun restore(state: Serializable) = snapshots.set(state as Int)
    }

    private class CountingBoundedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BoundedStateful {
        val reads = AtomicInteger()
        override fun readBounded(request: StateRead): StatePage {
            reads.incrementAndGet()
            return StatePage(entries = emptyList())
        }

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    private class ThrowingBoundedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BoundedStateful {
        override fun readBounded(request: StateRead): StatePage = error("broken fold")
        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
