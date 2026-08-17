package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Provenance
import civictech.cell.ReadCaveat
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.host.LocationRegistry
import civictech.cell.observe.StateWalkOutcome.Termination
import civictech.cell.observe.StateWalkOutcome.Termination.Cancelled
import civictech.cell.observe.StateWalkOutcome.Termination.Completed
import civictech.cell.observe.StateWalkOutcome.Termination.DeadlineExceeded
import civictech.cell.observe.StateWalkOutcome.Termination.Failed
import civictech.cell.observe.StateWalkOutcome.Termination.Refused
import civictech.cell.observe.StateWalkOutcome.Termination.Unbounded
import java.io.Serializable
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * What a [StateWalk] ended up with (computenet-t6b.3.2.1, KRD-12/KRD-21).
 *
 * One value carries **both** how the walk ended ([termination]) and everything
 * it had accumulated when it ended. That is deliberate: every non-completed
 * arm has to carry its pages, and none may be readable as a complete answer
 * (KRD-14). Making accumulation a field of the outcome rather than a payload
 * of the [Termination.Completed] arm makes "a partial walk still reports what
 * it read" structural instead of a convention each arm has to remember, and
 * leaves exactly one place — [isComplete] — where completeness is decided.
 *
 * **No stability verdict is computed here.** [openingFrontier] and
 * [closingFrontier] are the two stamps verbatim; comparing them is the
 * stability check [civictech.cell.StatePage] describes, and it belongs to the
 * sibling that owns it (computenet-t6b.3.4), which extends this type. A walk
 * that answers "stable" is answering a question this primitive did not ask.
 *
 * @property termination How the walk ended; see [Termination].
 * @property entries Every page's entries concatenated in arrival order. **No
 *   entry-level dedup is attempted**: under a key-ordered cursor
 *   [civictech.cell.StatePage] already promises no entry is returned twice in
 *   one walk, and the one documented weakening — a positional cursor — rides
 *   the caveats as [ReadCaveat.POSITIONAL_CURSOR] rather than being papered
 *   over here.
 * @property pages How many pages were answered before the walk ended. One page
 *   is one scheduler task ([civictech.cell.BoundedRead]'s header), so this is
 *   also the number of round trips the walk cost.
 * @property caveats The set-union of every page's
 *   [civictech.cell.StatePage.caveats]. A caveat declared by any page holds for
 *   the union, so weakening never gets lost by aggregation (KRD-21).
 * @property exclusivesElided The sum of every page's
 *   [civictech.cell.StatePage.exclusivesElided] — the honest count of entries
 *   replaced by an [civictech.cell.ExclusiveEntry] descriptor. Summed, never
 *   dropped (KRD-21).
 * @property openingFrontier The first answered page's
 *   [civictech.cell.StatePage.frontier], verbatim, or `null` when there were no
 *   pages or the family carries no frontier.
 * @property closingFrontier The last answered page's frontier, verbatim, same
 *   nullability. On a completed walk both stamps are exact (`StatePage.frontier`
 *   documents the two ends of a walk as the exact points); on a truncated one
 *   the closing stamp is whatever the last page carried, which may be a stale
 *   frontier declared as [ReadCaveat.STALE_FRONTIER] — another reason no verdict
 *   is computed here.
 *
 * `StatePage.attributes` is deliberately **not** accumulated. It is cell-level
 * state that rides *every* page precisely so a caller starting at page 4 still
 * sees it, so a union of it is not information the pages carried — and unlike
 * caveats and elisions, KRD-21 does not name it. A consumer that wants it reads
 * it from the pages it drives, or a later item adds it with a stated merge rule.
 */
data class StateWalkOutcome(
    val termination: Termination,
    val entries: List<Serializable>,
    val pages: Int,
    val caveats: Set<ReadCaveat>,
    val exclusivesElided: Int,
    val openingFrontier: TagFrontier?,
    val closingFrontier: TagFrontier?,
) {

    /**
     * How a walk ended. Every arm is a case the walk *decided*; there is no arm
     * that means "ask the exception". The outcome future never completes
     * exceptionally (KRD-12), which is why even [Failed] is a value.
     */
    sealed interface Termination {

        /**
         * A page reported no resume token, so the walk is over and
         * [StateWalkOutcome.entries] is the whole of the in-scope state the walk
         * saw (KRD-10). This is the **only** arm for which that is true.
         */
        data object Completed : Termination

        /**
         * A page was refused with [reason], and the walk stopped there (KRD-15).
         * The reason is the one [StateReadResult.Unavailable] named — not
         * remapped, not collapsed into another arm. The walk does not retry and
         * does not skip past it: a refusal mid-walk means the remaining pages
         * were never read, and reporting completion would report a hole as a
         * whole.
         */
        data class Refused(val reason: StateReadResult.Reason) : Termination

        /** The caller called [StateWalk.cancel] (KRD-13). */
        data object Cancelled : Termination

        /**
         * The caller-supplied deadline had passed at a page boundary (KRD-14).
         * Checked cooperatively; see [walkRouted]'s KDoc for what that does and
         * does not bound.
         */
        data object DeadlineExceeded : Termination

        /**
         * The read answered [StateReadResult.Unbounded] — the caller passed
         * [StateRead.allowWholeCopy] and the cell is [civictech.cell.Stateful]
         * but not [civictech.cell.BoundedStateful], or the host is drained and
         * answered from its checkpoint blob.
         *
         * There is nothing to page and nothing to resume, so the walk ends here
         * carrying the value. It is **not** [Completed]: no page contract
         * applies to it, [StateWalkOutcome.entries] is empty, and a caller that
         * treats a whole copy as "the walk's entries" would be reading an empty
         * state. Handling it explicitly rather than throwing or silently
         * completing is the point.
         */
        data class Unbounded(val state: Serializable, val provenance: Provenance) : Termination

        /**
         * A page future completed exceptionally for a reason that is not
         * cancellation. Unreachable through the landed seam —
         * [civictech.cell.host.ManagedHost.readState] answers a named
         * [StateReadResult.Reason] for every failure including the cell's own
         * throw ([StateReadResult.Reason.READ_FAILED]) — and it exists so that
         * "the outcome future never completes exceptionally" is a structural
         * property of this driver rather than an assumption about its
         * dependency. Deliberately not collapsed into
         * [Refused]`(READ_FAILED)`: that reason means the *cell* threw, and
         * minting it for something else would be exactly the taxonomy
         * corruption the read surface avoids.
         */
        data class Failed(val cause: Throwable) : Termination
    }

    /**
     * Did the walk see the whole of its in-scope state? True only for
     * [Termination.Completed] — a truncated walk is never reportable as
     * complete (KRD-14), whatever it managed to accumulate.
     */
    val isComplete: Boolean get() = termination is Termination.Completed
}

/**
 * A walk in flight: its eventual [outcome], and the ability to [cancel] it
 * (computenet-t6b.3.2.1). Obtained from [walkRouted]; never constructed
 * directly.
 *
 * ### Why a handle instead of just the future
 *
 * The obvious API is one `CompletableFuture<StateWalkOutcome>` the caller
 * cancels with `cancel()`. It cannot work: `CompletableFuture.cancel`
 * *completes* the future with a `CancellationException`, and KRD-12 requires
 * the outcome future to never complete exceptionally — a caller cancelling
 * would be the one way to make it throw, and would also destroy the pages the
 * cancelled arm is supposed to carry. So cancellation is a method on this
 * handle, and [outcome] is a future the caller only ever reads.
 *
 * ### Cancellation semantics
 *
 * [cancel] sets a flag and cancels the one outstanding page future with
 * `cancel(false)`, which [civictech.cell.host.ManagedHost.readState] already
 * honours per page: its submitted task checks `isCancelled` before entering the
 * cell, so an abandoned read costs a dequeue, not a page. At most one page
 * request is ever outstanding — the walk never issues request N+1 before page
 * N's future has completed, so there is nothing else to stop.
 *
 * Invariants: [outcome] completes exactly once; [cancel] after completion is a
 * no-op; a [cancel] racing a page completion resolves to exactly one of
 * cancelled or next-request, never both.
 */
class StateWalk internal constructor(
    private val registry: LocationRegistry,
    private val ref: CellRef,
    private val request: StateRead,
    private val deadline: Instant?,
    private val clock: InstantSource,
) {

    /**
     * Guards the accumulation and the cancel flag. The page callbacks form a
     * strictly sequential chain, so they need no mutual exclusion against each
     * other — but [cancel] is callable from any thread, and the cancellation it
     * triggers can run the terminal callback on the *canceller's* thread, which
     * then reads the accumulation. That is the race this lock covers.
     */
    private val lock = Any()

    private val entries = ArrayList<Serializable>()
    private val caveats = LinkedHashSet<ReadCaveat>()
    private var exclusivesElided = 0
    private var pages = 0
    private var openingFrontier: TagFrontier? = null
    private var closingFrontier: TagFrontier? = null
    private var cancelRequested = false
    private var outstanding: CompletableFuture<StateReadResult>? = null

    /**
     * Completes exactly once with how the walk ended and everything it read.
     * **Never completes exceptionally** (KRD-12) — do not call `cancel()` on
     * it; use [StateWalk.cancel].
     */
    val outcome: CompletableFuture<StateWalkOutcome> = CompletableFuture()

    /**
     * Stop the walk. Idempotent, safe from any thread, and a no-op once
     * [outcome] is done.
     */
    fun cancel() {
        val pending = synchronized(lock) {
            cancelRequested = true
            outstanding
        }
        // Outside the lock: this can complete `pending` exceptionally and run
        // the terminal callback inline on this thread.
        pending?.cancel(false)
    }

    internal fun start() = issue(null)

    private fun issue(cursor: Cursor?) {
        if (synchronized(lock) { cancelRequested }) return finish(Cancelled)

        // Only `cursor` varies across round trips; every other field of the
        // caller's request passes through verbatim, so the walk can never widen
        // the caller's `limit` (KRD-11) or quietly relax a bound.
        val future = readRouted(registry, ref, request.copy(cursor = cursor))
        val cancelledMeanwhile = synchronized(lock) {
            outstanding = future
            cancelRequested
        }
        // A cancel that landed between the flag check above and this store saw
        // the *previous*, already-completed future; honour it against this one.
        if (cancelledMeanwhile) future.cancel(false)
        future.whenComplete(::onPage)
    }

    private fun onPage(result: StateReadResult?, error: Throwable?) {
        if (error != null) {
            // A cancelled page future is the cancelled arm, never an exceptional
            // outcome: cancellation is how `cancel()` stops the outstanding read.
            return finish(if (error.isCancellation()) Cancelled else Failed(error))
        }
        when (result) {
            is StateReadResult.Unavailable -> finish(Refused(result.reason))

            is StateReadResult.Unbounded -> finish(Unbounded(result.state, result.provenance))

            is StateReadResult.Page -> {
                val next = synchronized(lock) {
                    val page = result.page
                    if (pages == 0) openingFrontier = page.frontier
                    closingFrontier = page.frontier
                    pages++
                    entries += page.entries
                    caveats += page.caveats
                    exclusivesElided += page.exclusivesElided
                    page.next
                }
                // Termination is exactly `next == null` (KRD-10). A short or even
                // empty page carrying a resume token is a documented normal case
                // — since/scope/byteBudget skipping (StatePage's KDoc on `next`)
                // — and must not end the walk.
                if (next == null) return finish(Completed)

                // The page boundary, and the only place the walk yields to the
                // caller's stopping conditions.
                if (synchronized(lock) { cancelRequested }) return finish(Cancelled)
                if (deadlinePassed()) return finish(DeadlineExceeded)

                issue(next)
            }

            null -> finish(Failed(IllegalStateException("read completed with neither a result nor an error")))
        }
    }

    private fun deadlinePassed(): Boolean = deadline != null && !clock.instant().isBefore(deadline)

    private fun finish(termination: Termination) {
        val settled = synchronized(lock) {
            StateWalkOutcome(
                termination = termination,
                entries = entries.toList(),
                pages = pages,
                caveats = caveats.toSet(),
                exclusivesElided = exclusivesElided,
                openingFrontier = openingFrontier,
                closingFrontier = closingFrontier,
            )
        }
        // Outside the lock — this runs the caller's dependent stages — and
        // `complete` is the exactly-once gate: a later arm (a cancel racing a
        // page completion) finds the future already done and changes nothing.
        outcome.complete(settled)
    }

    private fun Throwable.isCancellation(): Boolean =
        this is CancellationException || (this is CompletionException && cause is CancellationException)
}

/**
 * Walk a cell's whole in-scope state through [readRouted], one page per round
 * trip (computenet-t6b.3.2.1, KRD-09..KRD-15, KRD-21).
 *
 * The routed entry point answers one page; the loop, the deadline, the
 * cancellation and the accumulation were the caller's. This is that loop, once:
 * it starts from a fresh cursor, carries each page's
 * [civictech.cell.StatePage.next] into the following request, and terminates
 * **exactly** when a page reports no resume token.
 *
 * ### What it does not do
 *
 * - **It does not batch pages.** One page is one scheduler task
 *   ([civictech.cell.BoundedRead]'s header), and that is the whole win of the
 *   bounded read: a 10⁵-row read becomes ~500 short tasks interleaved with the
 *   cell's real work, not one long one. The walk keeps that structurally — it
 *   never issues request N+1 before page N's future has completed, so there is
 *   no prefetch and never more than one page request outstanding.
 * - **It does not widen the caller's bounds.** [request] passes through
 *   verbatim on every round trip except [StateRead.cursor]; in particular
 *   [StateRead.limit] is the caller's on every request.
 * - **It does not treat a short or empty page as the end.** Only
 *   `next == null` terminates (KRD-10); a page emptied by
 *   `since`/`scope`/`byteBudget` skipping still carries a resume token.
 * - **It does not compute a stability verdict.** The opening and closing
 *   frontier stamps are retained verbatim for the sibling that does.
 *
 * ### The deadline is cooperative, and that is a real limit
 *
 * [deadline] is evaluated at page boundaries only — after a page arrives,
 * before the next request is issued — against [clock]. There is no timer
 * thread, no scheduled executor and no blocking wait anywhere: the walk is
 * fully callback-composed, so nothing exists that could interrupt a page
 * already in flight. **A walk therefore overruns its deadline by up to one
 * page**, and a walk whose page never completes never observes the deadline at
 * all. The bound this gives is "no *further* page is requested past the
 * deadline", not "the outcome lands by the deadline".
 *
 * [clock] is injectable because the deterministic scheduler
 * ([civictech.cell.host.SimulationController]) models no time — there is no
 * clock in it to read — so a simulated walk supplies its own and the expiry
 * point becomes exact and testable rather than wall-clock flaky. It defaults to
 * [InstantSource.system].
 *
 * A `null` [deadline] means no deadline; the walk then ends only by
 * completion, refusal or cancellation.
 *
 * ### Refusals stop it
 *
 * Any page answered [StateReadResult.Unavailable] ends the walk with
 * [StateWalkOutcome.Termination.Refused] carrying that named reason and the
 * pages already accumulated (KRD-15) — no retry, no skip, no completion with a
 * hole. That includes the refusals [readRouted] decides on the caller's thread:
 * a ref that migrates away mid-walk answers
 * [StateReadResult.Reason.MIGRATING] on the next round trip.
 *
 * @param request the caller's bounds. Its [StateRead.cursor] **must** be null —
 *   a walk starts fresh, and resuming someone else's cursor would give an
 *   outcome whose opening frontier and entry union silently describe a
 *   different walk.
 * @return a [StateWalk] handle: read [StateWalk.outcome], call
 *   [StateWalk.cancel] to stop. Nothing blocks; the walk is already in flight
 *   when this returns (and, for a refusal decided on the caller's thread, may
 *   already be over).
 */
fun walkRouted(
    registry: LocationRegistry,
    ref: CellRef,
    request: StateRead = StateRead(),
    deadline: Instant? = null,
    clock: InstantSource = InstantSource.system(),
): StateWalk {
    require(request.cursor == null) {
        "a walk starts from a fresh cursor; request.cursor must be null (was ${request.cursor})"
    }
    return StateWalk(registry, ref, request, deadline, clock).also { it.start() }
}
