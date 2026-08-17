package civictech.cell.observe

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.time.Instant
import java.time.InstantSource
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * computenet-t6b.3.2.1: [walkRouted] drives [readRouted] page by page to a
 * stated outcome — BS-20 (termination is the absent resume token, not an empty
 * page) and BS-24 (a mid-walk refusal stops the walk), plus direct reachability
 * of the cancelled and deadline-exceeded arms and the KRD-21 accumulation.
 *
 * Scaffolding follows `RoutedReadTest` in this package: a [LocationRegistry], a
 * [SimulationController], a [ManagedHost] over its scheduler, and
 * `controller.step()`/`runToIdle()` as the only clock. Nothing here compares a
 * [Cursor] or a [StatePage] across two independently-opened walks: `Cursor`'s
 * token is opaque and `SetCell`'s walk token overrides neither `equals` nor
 * `hashCode`, so two walks over identical state are never `==` — a page
 * comparison fails even against itself. Cursors are only ever compared *within*
 * one walk, where the token is literally the object the previous page handed
 * back.
 */
class StateWalkTest {

    private val registry = LocationRegistry()
    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

    private fun setCell(n: Int): SetCell<String> {
        val cell = SetCell<String>()
        repeat(n) { cell.inlet.call.add("k$it") }
        host.managementInlet.call.spawn(cell)
        controller.runToIdle() // spawn leaves nothing queued; drained so step counts below are the walk's
        return cell
    }

    private fun scripted(vararg pages: StatePage): ScriptedBoundedCell =
        ScriptedBoundedCell(pages.toList()).also {
            host.managementInlet.call.spawn(it)
            controller.runToIdle()
        }

    private fun pagingCell(n: Int): PagingCountingCell =
        PagingCountingCell(n).also {
            host.managementInlet.call.spawn(it)
            controller.runToIdle()
        }

    // --------------------------------------------------------------- BS-20

    /**
     * KRD-09/KRD-10. The shape `since`/`scope`/`byteBudget` skipping produces:
     * a short page and then a wholly empty page, both carrying a resume token,
     * before a token-less final page. A walk that terminated on "no entries" —
     * the obvious wrong loop — would stop at page 2 and report two thirds of
     * the state as the whole of it.
     */
    @Test
    fun `a short page and an empty page carrying resume tokens do not terminate the walk`() {
        val cell = scripted(
            StatePage(entries = listOf("a", "b"), next = Cursor("c1")),
            StatePage(entries = emptyList(), next = Cursor("c2")),
            StatePage(entries = listOf("c"), next = null),
        )

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        outcome.isComplete.shouldBeTrue()
        outcome.pages shouldBe 3
        outcome.entries shouldBe listOf<Serializable>("a", "b", "c")
        cell.requests.size shouldBe 3
    }

    /**
     * KRD-09/KRD-11. Only the cursor varies across round trips: each request
     * carries the previous page's `next` verbatim, the first carries none, and
     * every other field of the caller's [StateRead] — the `limit` above all —
     * is passed through unchanged. A walk that widened `limit` to "finish
     * sooner" would break the one-page-one-task bargain the bounded read exists
     * for.
     */
    @Test
    fun `each request carries the previous page's resume token and never widens the caller's bounds`() {
        val cell = scripted(
            StatePage(entries = listOf("a"), next = Cursor("c1")),
            StatePage(entries = listOf("b"), next = Cursor("c2")),
            StatePage(entries = listOf("c"), next = null),
        )
        val request = StateRead(limit = 1, byteBudget = 17, allowWholeCopy = true)

        walkRouted(registry, cell.ref, request)
        controller.runToIdle()

        cell.requests.map { it.cursor } shouldBe listOf(null, Cursor("c1"), Cursor("c2"))
        cell.requests.forEach { it shouldBe request.copy(cursor = it.cursor) }
    }

    /**
     * One page is one scheduler task ([civictech.cell.BoundedRead]'s header) —
     * the whole win of the bounded read. Ten pages of a hundred-entry cell are
     * ten steps of the deterministic scheduler, not one; a driver that batched
     * pages inside one host invocation, or prefetched, would not produce this
     * count.
     */
    @Test
    fun `a real SetCell walks to completion at one scheduler task per page`() {
        val cell = setCell(100)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        val steps = controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        outcome.pages shouldBe 10
        steps shouldBe 10
        outcome.entries.size shouldBe 100

        // cancel after completion is a no-op (KRD-13)
        walk.cancel()
        walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe outcome
    }

    // --------------------------------------------------------------- BS-24

    /**
     * KRD-15. Between pages the ref enters the migration flip window — held,
     * and no longer placed anywhere, exactly the state `ManagedHost.migrate`
     * passes through (`cells.clear()`, unpublish, republish), for which
     * [readRouted] answers MIGRATING. The walk stops there carrying what it
     * read: it does not retry the refused page, does not skip past it, and does
     * not report a hole as a whole.
     */
    @Test
    fun `a ref that migrates away mid-walk stops the walk with MIGRATING and the pages already read`() {
        val cell = setCell(100)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.step().shouldBeTrue() // page 1 lands; page 2's request is queued

        registry.hold(cell.ref)
        registry.unpublish(cell.ref)
        registry.location(cell.ref) shouldBe null

        controller.step().shouldBeTrue() // page 2 lands; page 3 is refused on this thread
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination shouldBe StateWalkOutcome.Termination.Refused(StateReadResult.Reason.MIGRATING)
        outcome.isComplete.shouldBeFalse()
        outcome.pages shouldBe 2
        outcome.entries.size shouldBe 20

        // no retry and no skip: the refusal was decided on the caller's thread,
        // and nothing further was ever submitted
        controller.runToIdle() shouldBe 0
        outcome.entries.size shouldBe 20
    }

    // ------------------------------------------------- cancelled / deadline

    /**
     * KRD-12/KRD-13 reachability. The outcome carries the pages already read
     * and does not complete exceptionally — which is why cancellation cannot be
     * `outcome.cancel()`. The one outstanding page request survives the cancel
     * and costs a dequeue rather than a page: `readState`'s submitted task
     * checks `isCancelled` before entering the cell.
     */
    @Test
    fun `cancelling mid-walk reports cancelled with the pages already accumulated`() {
        val cell = setCell(100)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.step().shouldBeTrue() // page 1 lands; page 2's request is outstanding
        walk.outcome.isDone.shouldBeFalse()

        walk.cancel()

        walk.outcome.isDone.shouldBeTrue()
        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        outcome.termination shouldBe StateWalkOutcome.Termination.Cancelled
        outcome.isComplete.shouldBeFalse()
        outcome.pages shouldBe 1
        outcome.entries.size shouldBe 10

        // exactly one already-submitted request remained; running it produces
        // no further page and issues nothing
        controller.runToIdle() shouldBe 1
        walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe outcome
    }

    /**
     * KRD-12/KRD-14 reachability. `SimulationController` models no time, so the
     * walk reads an injected [InstantSource] and the expiry point is exact
     * rather than wall-clock flaky. The deadline is evaluated at the page
     * boundary — after a page arrives, before the next request is issued — so
     * the walk stops requesting rather than interrupting a page in flight.
     */
    @Test
    fun `a deadline passing mid-walk reports deadline-exceeded with the pages already accumulated`() {
        val cell = setCell(100)
        var now = Instant.EPOCH
        val deadline = Instant.EPOCH.plusMillis(5)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10), deadline, InstantSource { now })
        controller.step().shouldBeTrue() // page 1 lands, deadline not yet passed, page 2 requested

        now = deadline // at the deadline counts as passed, not before it

        controller.step().shouldBeTrue() // page 2 lands, and the boundary check stops the walk
        controller.runToIdle() shouldBe 0 // the walk stopped: no further page was requested

        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination shouldBe StateWalkOutcome.Termination.DeadlineExceeded
        outcome.isComplete.shouldBeFalse()
        outcome.pages shouldBe 2
        outcome.entries.size shouldBe 20
    }

    // ------------------------------------------------- BS-21 / BS-22 / BS-23
    //
    // computenet-t6b.3.2.2. The tests above (BS-20, BS-24, and the cancelled /
    // deadline reachability pair) prove the walk terminates correctly and that
    // the cancelled/deadline-exceeded arms are reachable. They do not prove the
    // caller-side determinism properties this task owns: that a limit is never
    // widened *and* costs a real read floor (BS-21), that a cancel racing an
    // outstanding request never lets that request reach the cell (BS-22), and
    // that a deadline expiring mid-walk — advanced by the test, not wall-clock
    // — stops the walk from issuing anything further (BS-23). All three need a
    // fixture that counts `readBounded` invocations, which `SetCell` cannot give
    // (its own reads are invisible) and `ScriptedBoundedCell` does not track;
    // [PagingCountingCell] below extends the `CountingBoundedCell` shape
    // (`RoutedReadTest`) with real limit-bounded paging over `n` entries.

    /**
     * BS-21 / KRD-11. With `n` entries and caller limit `L`, a walk that
     * batched pages inside one host invocation, or widened `L` to finish in
     * fewer round trips, would still terminate and still return the right
     * entries — the earlier tests would not notice. Only counting the
     * fixture's own invocations and the limit each one carried catches it:
     * the host read count must not fall below `ceil(n / L)`, no answered page
     * may exceed `L` entries, and no issued request may carry a limit larger
     * than `L`.
     */
    @Test
    fun `a walk costs at least ceil(n divided by L) host reads and never widens the caller's limit`() {
        val n = 100
        val limit = 10
        val cell = pagingCell(n)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = limit))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        outcome.entries.size shouldBe n

        val expectedFloor = (n + limit - 1) / limit // ceil(n / limit)
        cell.reads.get() shouldBeGreaterThanOrEqual expectedFloor
        cell.pageSizes.forEach { it shouldBeLessThanOrEqual limit }
        cell.limits.forEach { it shouldBeLessThanOrEqual limit }
    }

    /**
     * BS-22 / KRD-13. Steps the scheduler exactly once so page 1 has landed
     * and page 2's request is submitted but not yet run — the deterministic
     * scheduler's version of "one page request outstanding". Cancelling there
     * must not let that outstanding request reach the cell at all: per
     * `ManagedHost.submitRead`'s `isCancelled` check, running it costs a
     * dequeue, never a `readBounded` call, which only a fixture that counts
     * invocations can show — the previous cancellation test's
     * `outcome shouldBe outcome` check would pass identically whether or not
     * the cancelled task actually reached the cell.
     */
    @Test
    fun `cancelling mid-walk costs a dequeue, not a page — the fixture's read count is stable`() {
        val cell = pagingCell(100)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.step().shouldBeTrue() // page 1 lands; page 2's request is outstanding, not yet run
        cell.reads.get() shouldBe 1

        walk.cancel()

        // cancel() itself never touches the cell: it only cancels the
        // outstanding future and completes the outcome from that.
        walk.outcome.isDone.shouldBeTrue()
        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        cell.reads.get() shouldBe 1

        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        outcome.termination shouldBe StateWalkOutcome.Termination.Cancelled
        outcome.isComplete.shouldBeFalse()
        outcome.pages shouldBe 1
        outcome.entries.size shouldBe 10

        // exactly one already-submitted request remains; stepping it must cost
        // a dequeue, not a page — the fixture's own invocation count is the
        // proof, not just outcome stability (completing an already-done
        // future is a no-op regardless of whether the cell was ever entered).
        controller.runToIdle() shouldBe 1
        cell.reads.get() shouldBe 1
        walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe outcome
    }

    /**
     * BS-23 / KRD-14. `SimulationController` models no time, so the fake
     * [InstantSource] here is the only clock — the test itself advances it
     * past the deadline between two chosen `controller.step()` calls, which is
     * what makes the expiry point exact rather than a wall-clock race. Beyond
     * the reachability test above, this also proves the fixture's own read
     * count never grows once the deadline has passed: quiescence
     * (`runToIdle() shouldBe 0`) is checked before the outcome is read, so a
     * defect shows as a real assertion rather than a 30s `TimeoutException`.
     */
    @Test
    fun `a deadline exceeded mid-walk issues no further page request, exactly at the fake clock's expiry`() {
        val cell = pagingCell(100)
        var now = Instant.EPOCH
        val deadline = Instant.EPOCH.plusMillis(5)

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10), deadline, InstantSource { now })
        controller.step().shouldBeTrue() // page 1 lands, deadline not yet passed, page 2 requested
        cell.reads.get() shouldBe 1

        now = deadline // test-advanced: at the deadline counts as passed, not before it

        controller.step().shouldBeTrue() // page 2 lands, and the boundary check stops the walk
        cell.reads.get() shouldBe 2

        // quiescent BEFORE reading the outcome: a defect here is a real
        // assertion, not a 30s TimeoutException on the get() below
        controller.runToIdle() shouldBe 0
        cell.reads.get() shouldBe 2 // stable: no further page was ever requested

        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination shouldBe StateWalkOutcome.Termination.DeadlineExceeded
        outcome.isComplete.shouldBeFalse()
        outcome.pages shouldBe 2
        outcome.entries.size shouldBe 20
    }

    // --------------------------------------------------------------- KRD-21

    /**
     * KRD-21 plus KRD-12's completed arm: nothing a page carried is dropped by
     * aggregation. Caveats are set-unioned (a weakening declared by any page
     * holds for the union), elisions are summed (an honest count, never a
     * silent gap), and the opening and closing frontier stamps are retained
     * verbatim — with no verdict computed from them.
     */
    @Test
    fun `a completed walk unions every caveat, sums every elision, and keeps both frontier stamps verbatim`() {
        val opening = TagFrontier(mapOf(SOURCE to 3L))
        val closing = TagFrontier(mapOf(SOURCE to 9L))
        val cell = scripted(
            StatePage(entries = listOf("a"), next = Cursor("c1"), frontier = opening, exclusivesElided = 1),
            StatePage(
                entries = listOf("b"),
                next = Cursor("c2"),
                frontier = opening,
                exclusivesElided = 2,
                caveats = setOf(ReadCaveat.STALE_FRONTIER),
            ),
            StatePage(
                entries = listOf("c"),
                next = null,
                frontier = closing,
                exclusivesElided = 4,
                caveats = setOf(ReadCaveat.POSITIONAL_CURSOR),
            ),
        )

        val walk = walkRouted(registry, cell.ref, StateRead(limit = 10))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        outcome.termination shouldBe StateWalkOutcome.Termination.Completed
        outcome.caveats shouldBe setOf(ReadCaveat.STALE_FRONTIER, ReadCaveat.POSITIONAL_CURSOR)
        outcome.exclusivesElided shouldBe 7
        outcome.openingFrontier shouldBe opening
        outcome.closingFrontier shouldBe closing
    }

    // ----------------------------------------------------- explicit oddities

    /**
     * An `Unbounded` answer has nothing to page and nothing to resume. It is
     * handled as its own arm carrying the value — neither thrown, nor silently
     * reported as a completed walk whose entries happen to be empty.
     */
    @Test
    fun `an Unbounded answer ends the walk on its own arm, never as completion`() {
        val cell = WholeCopyCell().also { host.managementInlet.call.spawn(it) }
        controller.runToIdle()

        val walk = walkRouted(registry, cell.ref, StateRead(allowWholeCopy = true))
        controller.runToIdle()
        val outcome = walk.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        walk.outcome.isCompletedExceptionally.shouldBeFalse()
        outcome.termination.shouldBeInstanceOf<StateWalkOutcome.Termination.Unbounded>()
        outcome.isComplete.shouldBeFalse()
        outcome.entries shouldBe emptyList()
    }

    @Test
    fun `a walk refuses to start from someone else's cursor`() {
        val cell = setCell(10)

        shouldThrow<IllegalArgumentException> {
            walkRouted(registry, cell.ref, StateRead(cursor = Cursor("borrowed")))
        }
    }

    // --------------------------------------------------------------- fixtures

    /**
     * Replays a scripted page sequence, recording the request that asked for
     * each page — the fixture shape `RoutedReadTest.CountingBoundedCell`
     * establishes, extended from a counter to a transcript so the cursor
     * threading and the pass-through of the caller's bounds are both
     * observable.
     */
    private class ScriptedBoundedCell(
        private val script: List<StatePage>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, BoundedStateful {
        val requests = mutableListOf<StateRead>()

        override fun readBounded(request: StateRead): StatePage {
            requests += request
            check(requests.size <= script.size) {
                "the walk asked for page ${requests.size} of a ${script.size}-page script"
            }
            return script[requests.size - 1]
        }

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    /**
     * A cell with `n` entries that genuinely pages — at most `request.limit`
     * entries per `readBounded` call, resuming from an `Int` offset cursor —
     * and records every invocation: how many there were ([reads]), the size
     * of every page it answered ([pageSizes]), and the `limit` every request
     * actually carried ([limits]). Extends the `CountingBoundedCell` shape
     * from `RoutedReadTest` (a bare counter) into a transcript, which is what
     * BS-21/BS-22/BS-23 need and a step count alone cannot give: a driver
     * that batched two pages into one invocation, or widened the limit to
     * finish sooner, would still produce the right total entries and even the
     * right *page* count as seen from the walk's side — only the fixture's
     * own invocation count and per-request limit expose it. The offset cursor
     * is never compared across walks; it is read back only within the one
     * walk that minted it.
     */
    private class PagingCountingCell(
        private val n: Int,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, BoundedStateful {
        val reads = AtomicInteger()
        val limits = mutableListOf<Int>()
        val pageSizes = mutableListOf<Int>()

        override fun readBounded(request: StateRead): StatePage {
            reads.incrementAndGet()
            limits += request.limit
            val start = request.cursor?.token as? Int ?: 0
            val end = minOf(start + request.limit, n)
            val entries = (start until end).map { "k$it" as Serializable }
            pageSizes += entries.size
            return StatePage(entries = entries, next = if (end < n) Cursor(end) else null)
        }

        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    /** [Stateful] but not [BoundedStateful]: the `Unbounded` answer's vehicle. */
    private class WholeCopyCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        override fun snapshot(): Serializable = "whole"
        override fun restore(state: Serializable) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val SOURCE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    }
}
