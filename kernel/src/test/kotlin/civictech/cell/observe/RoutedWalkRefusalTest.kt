package civictech.cell.observe

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Provenance
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * computenet-t6b.3.3.3: the three "the primitive never lies to the caller"
 * rules, exercised at [readRouted]/[walkRouted] level rather than only at
 * [ManagedHost.readState] — D1 (BS-12, [KRD-08]), D2 (BS-41, [KRD-25]), D3
 * (BS-42, [KRD-26]) of the feature's breakdown design.
 *
 * Scaffolding follows `RoutedReadTest`/`StateWalkTest` in this package: a
 * [LocationRegistry], a [SimulationController], a [ManagedHost] over its
 * scheduler, and `controller.step()`/`runToIdle()` as the only clock.
 */
class RoutedWalkRefusalTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private fun <C : Cell> spawn(cell: C): C {
        host.managementInlet.call.spawn(cell)
        controller.runToIdle() // spawn leaves nothing queued; drained so step counts below are the walk's
        return cell
    }

    // ================================================================= D1
    // BS-12 / [KRD-08]: a cell whose readBounded throws surfaces as the
    // named READ_FAILED outcome, never as Failed and never as an exception.

    @Test
    fun `readRouted on a cell that always throws answers READ_FAILED, not an exception`() {
        val cell = spawn(ThrowingBoundedCell())

        val result = readRouted(registry, cell.ref, StateRead())
        controller.runToIdle()

        result.isDone.shouldBeTrue()
        result.isCompletedExceptionally.shouldBeFalse()
        result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe
            StateReadResult.Unavailable(StateReadResult.Reason.READ_FAILED)
    }

    @Test
    fun `walkRouted on a cell that always throws refuses READ_FAILED with no pages and no entries`() {
        val cell = spawn(ThrowingBoundedCell())

        val walk = walkRouted(registry, cell.ref, StateRead())
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.READ_FAILED)
        outcome.pages shouldBe 0
        outcome.entries.shouldBeEmpty()
        outcome.isComplete.shouldBeFalse()
    }

    @Test
    fun `walkRouted on a cell that throws after one page carries that page's entries with isComplete false`() {
        val cell = spawn(ThrowingAfterFirstPageCell())

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.READ_FAILED)
        outcome.pages shouldBe 1
        outcome.entries shouldBe FIRST_PAGE_ENTRIES
        outcome.isComplete.shouldBeFalse()
    }

    /**
     * The KDoc on [StateWalkOutcome.Termination.Failed] pins it as the
     * driver's own structural arm, unreachable through the landed seam — a
     * cell throw must surface as [StateWalkOutcome.Termination.Refused] with
     * [StateReadResult.Reason.READ_FAILED], never collapsed into it or
     * escaping as [StateWalkOutcome.Termination.Failed].
     */
    @Test
    fun `a cell throw never surfaces as the walk's own Failed arm, on any of the three fixtures above`() {
        val alwaysThrows = spawn(ThrowingBoundedCell())
        val throwsAfterFirstPage = spawn(ThrowingAfterFirstPageCell())

        val walk1 = walkRouted(registry, alwaysThrows.ref, StateRead())
        val walk2 = walkRouted(registry, throwsAfterFirstPage.ref, StateRead(limit = 10))
        controller.runToIdle()

        walk1.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).termination
            .shouldBeInstanceOf<StateWalkOutcome.Termination.Refused>()
        walk2.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).termination
            .shouldBeInstanceOf<StateWalkOutcome.Termination.Refused>()
    }

    // ================================================================= D2
    // BS-41 / [KRD-25]: a bound the target family cannot honour is refused
    // on the caller's thread, before any task reaches the cell — never
    // silently widened.

    @Test
    fun `readRouted refuses SINCE_UNSUPPORTED and SCOPE_UNSUPPORTED before any task reaches the cell`() {
        val cell = spawn(PlainBoundedCell())

        val sinceResult = readRouted(registry, cell.ref, StateRead(since = TagFrontier(emptyMap())))
        sinceResult.isDone.shouldBeTrue()
        sinceResult.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe
            StateReadResult.Unavailable(StateReadResult.Reason.SINCE_UNSUPPORTED)

        val scopeResult = readRouted(registry, cell.ref, StateRead(scope = Prefix("x")))
        scopeResult.isDone.shouldBeTrue()
        scopeResult.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe
            StateReadResult.Unavailable(StateReadResult.Reason.SCOPE_UNSUPPORTED)

        // decided on the caller's thread: no task was ever queued
        controller.step().shouldBeFalse()
        cell.reads shouldBe 0
    }

    @Test
    fun `walkRouted refuses SINCE_UNSUPPORTED and SCOPE_UNSUPPORTED on return, with no page and no cell read`() {
        val sinceCell = spawn(PlainBoundedCell())
        val sinceWalk = walkRouted(registry, sinceCell.ref, StateRead(since = TagFrontier(emptyMap())))
        sinceWalk.outcome.isDone.shouldBeTrue()
        val sinceOutcome = sinceWalk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        sinceOutcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.SINCE_UNSUPPORTED)
        sinceOutcome.pages shouldBe 0
        sinceOutcome.entries.shouldBeEmpty()
        sinceCell.reads shouldBe 0
        controller.step().shouldBeFalse()

        val scopeCell = spawn(PlainBoundedCell())
        val scopeWalk = walkRouted(registry, scopeCell.ref, StateRead(scope = Prefix("x")))
        scopeWalk.outcome.isDone.shouldBeTrue()
        val scopeOutcome = scopeWalk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        scopeOutcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.SCOPE_UNSUPPORTED)
        scopeOutcome.pages shouldBe 0
        scopeOutcome.entries.shouldBeEmpty()
        scopeCell.reads shouldBe 0
        controller.step().shouldBeFalse()
    }

    /** [Interest.Total] is not a narrowing, so it is not a refusal — the contrast case. */
    @Test
    fun `walkRouted with scope Interest Total completes normally rather than being refused`() {
        val cell = spawn(PlainBoundedCell())

        val walk = walkRouted(registry, cell.ref, StateRead(scope = Interest.Total))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        cell.reads shouldBe 1
    }

    /** The positive half of "never widened": the declaring family serves a narrowing scope honestly. */
    @Test
    fun `a SetCell walked with a narrowing scope returns only the admitted keys`() {
        val cell = spawn(SetCell<String>())
        repeat(5) { cell.inlet.call.add("a$it") }
        repeat(5) { cell.inlet.call.add("b$it") }
        controller.runToIdle()

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 4, scope = Prefix("b")))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        @Suppress("UNCHECKED_CAST")
        val keys = outcome.entries.filterIsInstance<SetCell.SetStateEntry<*>>().map { it.element as String }
        keys.toSet() shouldBe (0 until 5).map { "b$it" }.toSet()
    }

    // ================================================================= D3
    // BS-42 / [KRD-26]: a Stateful-but-not-BoundedStateful cell is refused
    // rather than copied whole, unless the caller opted in.

    @Test
    fun `readRouted refuses NOT_BOUNDED for a stateful-but-not-bounded cell without opt-in`() {
        val cell = spawn(WholeCopyCell())

        val result = readRouted(registry, cell.ref, StateRead())
        result.isDone.shouldBeTrue()
        result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe
            StateReadResult.Unavailable(StateReadResult.Reason.NOT_BOUNDED)

        controller.step().shouldBeFalse()
    }

    @Test
    fun `walkRouted refuses NOT_BOUNDED for a stateful-but-not-bounded cell without opt-in`() {
        val cell = spawn(WholeCopyCell())

        val walk = walkRouted(registry, cell.ref, StateRead())
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.NOT_BOUNDED)
        outcome.pages shouldBe 0
        outcome.entries.shouldBeEmpty()
        controller.step().shouldBeFalse()
    }

    @Test
    fun `readRouted with allowWholeCopy answers Unbounded only after a scheduler round trip`() {
        val cell = spawn(WholeCopyCell())

        val result = readRouted(registry, cell.ref, StateRead(allowWholeCopy = true))
        // a task was submitted: not decided on the caller's thread
        result.isDone.shouldBeFalse()
        controller.runToIdle()

        result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe
            StateReadResult.Unbounded("whole", Provenance.LIVE)
    }

    @Test
    fun `walkRouted with allowWholeCopy ends Unbounded with the snapshot and LIVE provenance, no pages`() {
        val cell = spawn(WholeCopyCell())

        val walk = walkRouted(registry, cell.ref, StateRead(allowWholeCopy = true))
        walk.outcome.isDone.shouldBeFalse()
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Unbounded("whole", Provenance.LIVE)
        outcome.pages shouldBe 0
        outcome.entries.shouldBeEmpty()
        outcome.isComplete.shouldBeFalse()
    }

    // --------------------------------------------------------------- fixtures

    /** [BoundedStateful] whose [readBounded] throws on every call. */
    private class ThrowingBoundedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BoundedStateful {
        override fun readBounded(request: StateRead): StatePage = throw IllegalStateException("always throws")
        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    /** [BoundedStateful] that answers one real page, then throws on every call after. */
    private class ThrowingAfterFirstPageCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BoundedStateful {
        private var calls = 0
        override fun readBounded(request: StateRead): StatePage {
            calls++
            if (calls == 1) return StatePage(entries = FIRST_PAGE_ENTRIES, next = Cursor("c1"))
            throw IllegalStateException("throws after the first page")
        }

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    /** A [BoundedStateful] cell that declares neither optional bound, counting `readBounded` calls (copy of `BoundedStateReadTest.PlainBoundedCell`). */
    private class PlainBoundedCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BoundedStateful {
        var reads = 0
        override fun readBounded(request: StateRead): StatePage {
            reads++
            return StatePage(entries = emptyList())
        }

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    /** [Stateful] but not [BoundedStateful]: the `Unbounded` answer's vehicle (copy of `StateWalkTest.WholeCopyCell`). */
    private class WholeCopyCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        override fun snapshot(): Serializable = "whole"
        override fun restore(state: Serializable) = Unit
    }

    /** A tiny, honest [Interest] for scope tests (copy of `BoundedStateReadTest.Prefix`). */
    private data class Prefix(val prefix: String) : Interest {
        override fun overlaps(other: Interest) = true
        override fun admits(key: Any?) = (key as? String)?.startsWith(prefix) == true
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val FIRST_PAGE_ENTRIES: List<Serializable> = listOf("a", "b")
    }
}
