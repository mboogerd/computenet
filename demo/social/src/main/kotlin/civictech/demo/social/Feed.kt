/**
 * The viewer feed as a **scatter-gather pull** over per-author cells (SOC1,
 * epic `computenet-07k` §4 "Scatter-gather feed"; feature `computenet-8eb53`,
 * designs 8eb53-D1..D10). Spec: `doc/spec/40-distribution/42-replication.md`
 * §"Scatter-gather pull over an instance set (PN-5)", `[42-INT-01]`,
 * `[24-PART-05]`.
 *
 * The kernel's own PN-5 machinery (`StateRequestProtocol`,
 * `RetainedFrontiers`, `PartitionedShardSet.pull`) lives in packages this demo
 * may not import (`DemoSurfaceAllowlistTest`, `[SOC1-MOD-03]`). This file
 * re-realises the same rules from the demo-legal surface: the [BoundedReader]
 * seam, [StateRead.since], and the [TagFrontier] each page reports.
 *
 * **PN-5, rule by rule.**
 *
 * - *A pull fans out to every instance whose interest overlaps the
 *   requester's scope* — [FeedSession.pull] issues one bounded read walk per
 *   `snb-authored` cell the viewer's scope (an [Interest.Ranges], or
 *   [Interest.Empty] for no legs at all) admits, legs
 *   enumerated from the scope's own ranges (never from a scan of the family),
 *   and checked against the interest each cell registered at spawn
 *   ([SnbPipeline.build]'s `registry`, 8eb53-D3/D4).
 * - *Each instance answers its own slice with its own frontier; the requester
 *   unions the slices* — each leg's page entries are applied to one board
 *   ([FeedSession.board]); no author cell holds another author's messages, so
 *   the slices are disjoint.
 * - *Per-shard-consistent, cross-shard-arbitrary; an unreachable shard defers
 *   its leg* — a leg answering [StateReadResult.Unavailable] ends as
 *   [LegOutcome.Deferred] with its retained frontier untouched, and the pull
 *   completes from the others (`[SOC1-FEED-05]`). There is no joint cut and
 *   [PullReport] claims none: it has no global frontier (`[SOC1-FEED-06]`).
 * - *A baseline is never a wave* — a bounded read is an app-facing scheduler
 *   task that emits nothing on any outlet, so nothing a pull returns can enter
 *   a wave frontier (`[SOC1-FEED-07]`, 07k-D6; pinned by
 *   `SocialFeedFrontierTest` with the probes of the kernel's
 *   `BoundedReadWaveNeutralityTest`).
 * - *Per-instance retained currency, never one frontier merged across
 *   instances* — [FeedSession] keeps one frontier per leg ref and sends each
 *   leg only its own as `since` (`[SOC1-FEED-03/04]`). This file never builds a
 *   frontier and never reads one's per-source map; it only forwards the value
 *   a page reported, keyed by the ref that reported it. A merge cannot be
 *   written without doing one of the two, and `SocialFeedFrontierTest` greps
 *   this source for both.
 *
 * **IC2 ordering (`[SOC1-FEED-09/10]`, feature `computenet-flfkm`, flfkm-D1).**
 * [board] with a limit orders the accumulated set demo-side —
 * `creationDate` descending, ties by message id descending — and applies an
 * optional `before` cutoff and the limit, entirely over the per-leg pages
 * this file already retains; no kernel operator is involved. The missing
 * kernel-side ordered top-K / ordered key-range scan this stands in for is
 * the KAGG-R gap `[SOC1-FIND-02]` records (task `computenet-flfkm.6`).
 *
 * **8eb53-D1 — what a since-page carries.** `SetCell.readBounded` returns
 * whole `SetStateEntry(element, addTags, delTags)` records, but with `since`
 * set each tag set holds only the tags strictly beyond `since` for their
 * source, and an element with no such tag is not on the page at all. So on a
 * since-page `present == true` is an add the session has not seen and
 * `present == false` is a remove (a novel del-dot over add-tags already
 * seen). The board applies every entry that way, idempotently. A below-floor
 * `since` is not refused but escalated to a full walk (`sinceEscalated`), to
 * which the same rule applies unchanged.
 *
 * **8eb53-D2 — why the OPENING frontier is retained.** Every page of one walk
 * carries the stamp computed when the walk froze its enumeration order,
 * except the last page of a multi-page walk, which is recomputed as the walk
 * closes. A message added mid-walk is not in the frozen order — so it is on
 * no page — yet it IS under the closing stamp. Retaining the closing stamp
 * would make the next pull's `since` claim that message as seen, and it would
 * never arrive. The leg therefore retains the frontier of its walk's FIRST
 * page; whatever lies beyond it is re-delivered next pull, and re-applying an
 * add or a remove to the board is idempotent.
 *
 * **8eb53-D9 — what per-leg retention protects here, and what it cannot
 * show.** The spec's drop needs shards holding interleaved counters of one
 * shared source. In this demo every `snb-authored` cell mints tags from its
 * OWN source (derived from its ref), so a pointwise-max merge across legs
 * would put A's source into B's `since` without lowering anything B reports:
 * the drop itself is not reproducible on this data. The retention is kept
 * per leg anyway because it is the rule, and because the observable
 * difference — a leg's `since` naming only that leg's own sources — is what
 * the frontier test pins. A demo whose legs shared a source would lose
 * messages under a merged frontier; this one would only send wrong requests.
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.link.Interest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** How one leg of a pull ended (8eb53-D5). */
sealed interface LegOutcome {
    /**
     * The leg's walk completed. [frontier] is the opening stamp of that walk
     * (8eb53-D2) — the value now retained for the leg, or null if the cell
     * reported none (then nothing is retained and the next pull re-reads the
     * leg in full). [delivered] counts the entries the leg's pages carried:
     * with a correct `since`, the adds and removes the session had not seen.
     */
    data class Answered(val frontier: TagFrontier?, val delivered: Int) : LegOutcome

    /** The leg was refused on some page; its retained frontier is untouched and it is retried next pull. */
    data class Deferred(val reason: StateReadResult.Reason) : LegOutcome
}

/**
 * One pull's answer, per leg. Deliberately the only property: there is no
 * global frontier, because a scatter-gather board has no joint cut to report
 * (`[SOC1-FEED-06]`).
 */
data class PullReport(val legs: Map<CellRef, LegOutcome>)

/**
 * One viewer's feed over the `snb-authored` family (8eb53-D3..D6).
 *
 * **Scope (4q9is-D1, D5, D6).** The session asks its [ScopeSource] for the
 * viewer's scope at the START of every [pull]: its arms are the viewer's
 * friends as singleton ranges `Range(id, id + 1)` (07k-D2 — `Range` is
 * half-open), or [Interest.Empty] for a viewer who knows nobody. A
 * [ViewerInterest] source derives it from the viewer's `knows` set — one
 * extra bounded read of the viewer's `snb-person` cell per pull, which is
 * what lets an added edge widen the next pull and a removed edge narrow it
 * with no event plumbing (`[SOC1-INT-02/03]`). The secondary constructor
 * keeps the caller-supplied fixed scope ([ScopeSource.fixed]). Any scope
 * other than `Ranges` or `Empty` fails the pull: `Total` in particular is the
 * control-b anti-pattern (`[SOC1-FEED-02]`) and nothing here may pull it. A
 * refused scope read fails the pull with [ScopeUnavailable] and leaves
 * [scope], the board and every retained frontier as they were (4q9is-D4).
 *
 * **Narrowing keeps state and hides output (4q9is-D6).** A removed friend
 * gets no leg from the next pull on, so nothing they post afterwards is
 * pulled. Their already-delivered messages stay in the accumulated set and
 * their retained frontier stays in [frontiers] — PN-5 promises no retraction
 * on unfollow — but both [board] overloads admit only messages whose
 * `creatorId` the current [scope] admits, so those messages leave the board.
 * Re-adding the edge resumes the leg from its retained frontier
 * (`since != null`) and the filter shows the old messages again.
 *
 * **Legs.** Keys are enumerated from [scope]'s ranges, never by scanning the
 * family. `KeyedCells.refFor` is private and `getOrSpawn` spawns for an
 * unknown key, so a key is resolved to a ref only if `KeyedCells.contains`
 * admits it — one O(1) lookup per scope key, never a whole-family `keys()`
 * copy (tbmhn: `FeedSession.pull` cost O(total authors) per pull before this).
 * A friend who has authored nothing has no cell: no read is issued for them
 * and they are not in the [PullReport] (there is no ref to key them by); they
 * join the pull that follows their first post, reading from `since = null`.
 *
 * Not reentrant: one caller, one pull at a time — [pull] itself. A caller
 * that cannot guarantee single-flight callers of its own (SocialApp's
 * per-viewer cache, `computenet-1iz73`) must go through [pullShared]
 * instead, which shares one in-flight pull's future across overlapping
 * callers rather than racing [pull]'s reentrancy check.
 */
class FeedSession(
    val viewer: Long,
    private val source: ScopeSource,
    private val families: SnbPipeline.Families,
    private val registry: LocationRegistry,
    private val reader: BoundedReader,
    private val pageLimit: Int = 200,
    // 4q9is-D7: opt-in demo-layer join of a derived scope to
    // KeyedCells.getOrSpawn (the kernel seam `doc/demo-findings.md` F-24
    // records as missing). Null (the default) is today's behavior: an
    // admitted-but-absent friend gets no leg. Non-null spawns their
    // `snb-authored` cell durably on the pull that first admits them, so they
    // get a leg answering Empty at since = null from then on.
    private val spawner: InterestDrivenFamily? = null,
    // computenet-pvtcj: the executor [fanOut] dispatches spawner.admit() onto
    // (see the companion's KDoc). Unused when spawner is null. A constructor
    // parameter rather than the former mutable companion `var`, so
    // `SocialApp` can own and shut down its own instance, and a test can
    // inject its own without touching shared global state.
    private val spawnExecutor: Executor = DEFAULT_SPAWN_EXECUTOR,
) {
    /** A session over a caller-supplied scope that never changes ([ScopeSource.fixed]). */
    constructor(
        viewer: Long,
        scope: Interest.Ranges,
        families: SnbPipeline.Families,
        registry: LocationRegistry,
        reader: BoundedReader,
        pageLimit: Int = 200,
        spawner: InterestDrivenFamily? = null,
        spawnExecutor: Executor = DEFAULT_SPAWN_EXECUTOR,
    ) : this(viewer, ScopeSource.fixed(scope), families, registry, reader, pageLimit, spawner, spawnExecutor) {
        this.scope = scope
    }

    init {
        require(pageLimit > 0) { "pageLimit must be positive, got $pageLimit" }
    }

    private companion object {
        /**
         * Fallback [spawnExecutor] used only when a caller omits the
         * constructor parameter. 4q9is-D7's admit() call needs a thread that
         * is not the host's own (see [fanOut]'s KDoc): production
         * (`VirtualThreadScheduler`) drains its queue on its own dedicated
         * thread regardless of who else is waiting, so a genuinely separate
         * pool thread calling the blocking `getOrSpawn` is exactly the normal
         * "application thread" usage pattern every other `getOrSpawn` call
         * site in this demo already relies on.
         *
         * Every caller that never sets [spawner] (the default) never touches
         * this either, since [fanOut] only reads [spawnExecutor] on the
         * `spawner != null` branch. `SocialApp(interestDriven = true)` is the
         * one production caller that does use it, and it builds and passes
         * its own instance instead of relying on this fallback, so it can
         * shut that instance down in `stop()` (computenet-pvtcj residual of
         * 4q9is). This value is an immutable default, not shared mutable
         * state: nothing here mutates it, and no test substitutes it by
         * assignment — `SocialInterestTest`'s queueing [Executor] (needed
         * because `SimulationController`'s own KDoc says "Stepping and
         * awaiting are expected on one thread... not thread-safe by design",
         * and a genuine background thread calling back into it concurrently
         * with the test's driving thread is a real, observed race) is passed
         * through this same constructor parameter instead.
         */
        val DEFAULT_SPAWN_EXECUTOR: Executor =
            Executors.newCachedThreadPool { r -> Thread(r, "FeedSession-spawn").apply { isDaemon = true } }
    }

    /**
     * The scope the most recent pull derived and validated (4q9is-D1) — set
     * before that pull's legs are issued, so [board] already filters by it
     * while the pull is in flight: for a fixed
     * source, that value from construction; for a derived one,
     * [Interest.Empty] until the first pull completes. Always `Ranges` or
     * `Empty`. The [board] filter reads it.
     */
    @Volatile
    var scope: Interest = Interest.Empty
        private set

    /** Guards [messages] and [retained]: pages complete on the host's scheduler thread. */
    private val state = Any()
    private val messages = HashSet<Message>()
    private val retained = HashMap<CellRef, TagFrontier>()
    private val inFlight = AtomicBoolean(false)

    /** Guards [currentPull]: read-and-maybe-start-[pull] must be one atomic step. */
    private val pullLock = Any()
    private var currentPull: CompletableFuture<PullReport>? = null

    /**
     * The union of every answered leg's slice so far that the current [scope]
     * admits by `creatorId` (a copy; 4q9is-D6 — a removed friend's delivered
     * messages are kept but not shown).
     */
    fun board(): Set<Message> {
        val admitted = scope
        return synchronized(state) { messages.filterTo(HashSet()) { admitted.admits(it.creatorId) } }
    }

    /**
     * IC2 (`[SOC1-FEED-09]`): the accumulated set, filtered to messages the
     * current [scope] admits by `creatorId` (4q9is-D6) and to
     * `creationDate < before` when [before] is given, ordered `creationDate`
     * descending then message id descending, first [limit]. Demo-side over
     * the per-leg pages already retained by [pull] (`[SOC1-FEED-10]`); no
     * kernel ordering.
     */
    fun board(limit: Int, before: Long? = null): List<Message> {
        require(limit > 0) { "limit must be positive, got $limit" }
        val admitted = scope
        return synchronized(state) {
            messages.asSequence()
                .filter { admitted.admits(it.creatorId) }
                .filter { before == null || it.creationDate < before }
                .sortedWith(compareByDescending<Message> { it.creationDate }.thenByDescending { it.id })
                .take(limit)
                .toList()
        }
    }

    /** The frontier retained per leg ref (a copy) — one entry per leg that has ever answered with one. */
    fun frontiers(): Map<CellRef, TagFrontier> = synchronized(state) { retained.toMap() }

    /**
     * Derives the scope from the [ScopeSource] first (4q9is-D6), then fans
     * one bounded read walk out per leg that scope admits, all issued before
     * any is awaited, and completes when every leg has answered or been
     * deferred. Never blocks: on a simulated host the pages land on
     * `runToIdle()`. A failed derivation ([ScopeUnavailable]) or a scope that
     * is neither `Ranges` nor `Empty` completes the future exceptionally
     * with no leg issued and [scope] unchanged. Every completion path,
     * exceptional included, releases the non-reentrancy guard.
     */
    fun pull(): CompletableFuture<PullReport> {
        check(inFlight.compareAndSet(false, true)) { "FeedSession.pull() is not reentrant" }
        val derivation = try {
            source.scopeOf(viewer)
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
        return derivation
            .thenCompose { derived -> fanOut(derived) }
            .whenComplete { _, _ -> inFlight.set(false) }
    }

    /**
     * Legs for [derived] (validated before it becomes [scope]), then the
     * walks. [spawner], when present, is admitted first — dispatched onto
     * [spawnExecutor] rather than called inline (see its KDoc): this method
     * runs as the continuation of [derived]'s own future, which for a derived
     * [ScopeSource] completes ON THE HOST'S OWN THREAD (the read that
     * produced it), and `KeyedCells.getOrSpawn` blocks synchronously waiting
     * on that same host — a wait it (or the production `VirtualThreadScheduler`)
     * refuses as a same-thread deadlock. A null [spawner] takes the
     * already-completed branch, so every existing call site (no spawner) runs
     * exactly as before, inline, on this same thread.
     */
    private fun fanOut(derived: Interest): CompletableFuture<PullReport> {
        val keys = keysOf(derived)
        scope = derived
        val admitted: CompletableFuture<Void> =
            if (spawner != null) {
                CompletableFuture.supplyAsync({ spawner.admit(derived) }, spawnExecutor).thenApply { null }
            } else {
                CompletableFuture.completedFuture(null)
            }
        return admitted.thenCompose {
            val legs = keys
                .filter { families.authored.contains(it) }
                .map { families.authored.getOrSpawn(it).ref }
                .filter { registry.interestOf(it).overlaps(derived) }
            val outcomes = legs.map { ref ->
                val since = synchronized(state) { retained[ref] }
                ref to walk(ref, since)
            }
            CompletableFuture.allOf(*outcomes.map { it.second }.toTypedArray())
                .thenApply { PullReport(outcomes.associateTo(LinkedHashMap()) { (ref, f) -> ref to f.join() }) }
        }
    }

    /**
     * 4q9is-D5: `Ranges` -> every key of every arm, distinct; `Empty` -> none;
     * anything else (`Total`, `Slots`, `Union`, ...) is refused — a feed never
     * pulls an unbounded scope.
     */
    private fun keysOf(scope: Interest): List<Long> = when (scope) {
        is Interest.Ranges -> scope.ranges.flatMap { r -> (r.lo until r.hi).asIterable() }.distinct()
        Interest.Empty -> emptyList()
        else -> throw IllegalStateException("feed scope must be Ranges or Empty, got $scope")
    }

    /**
     * `computenet-1iz73`: a caller that arrives while a pull is already in
     * flight for this session gets that pull's own future instead of calling
     * [pull] again — which would throw, since [pull] enforces its own
     * non-reentrancy unconditionally. This is what lets SocialApp's per-viewer
     * `FeedSession` cache serve two overlapping `/feed` requests for the same
     * viewer safely: the cache hands both callers the same session, and this
     * method is what makes that safe regardless of how the cache decided to
     * reuse it (rebuild-on-scope-change today; `computenet-4q9is.3` is
     * expected to move that to `computeIfAbsent` without needing this to
     * change, since the sharing lives here, per session, not in the cache's
     * own branching).
     *
     * [pullLock] is held only long enough to read/replace [currentPull] and
     * call [pull] — [pull] itself never blocks (it only issues async reads and
     * returns), so this never blocks a caller behind a slow leg.
     */
    fun pullShared(): CompletableFuture<PullReport> = synchronized(pullLock) {
        val existing = currentPull
        if (existing != null && !existing.isDone) return existing
        val started = pull()
        currentPull = started
        started.whenComplete { _, _ -> synchronized(pullLock) { if (currentPull === started) currentPull = null } }
        started
    }

    /**
     * One leg: follows `next` cursors with the SAME [since] until exhausted,
     * applying each page as it lands, then retains the FIRST page's frontier
     * (8eb53-D2). A refusal on any page ends the leg as [LegOutcome.Deferred]
     * with the retained frontier untouched; entries already applied stay,
     * since they are real state and will be re-delivered.
     */
    private fun walk(ref: CellRef, since: TagFrontier?): CompletableFuture<LegOutcome> {
        fun step(cursor: Cursor?, first: Boolean, opening: TagFrontier?, delivered: Int): CompletableFuture<LegOutcome> =
            reader.read(ref, StateRead(cursor = cursor, limit = pageLimit, since = since)).thenCompose { result ->
                when (result) {
                    is StateReadResult.Page -> {
                        val page = result.page
                        val open = if (first) page.frontier else opening
                        val total = delivered + apply(page)
                        val next = page.next
                        if (next != null) {
                            step(next, false, open, total)
                        } else {
                            if (open != null) synchronized(state) { retained[ref] = open }
                            CompletableFuture.completedFuture<LegOutcome>(LegOutcome.Answered(open, total))
                        }
                    }

                    is StateReadResult.Unavailable ->
                        CompletableFuture.completedFuture<LegOutcome>(LegOutcome.Deferred(result.reason))

                    is StateReadResult.Unbounded ->
                        CompletableFuture.completedFuture<LegOutcome>(
                            LegOutcome.Deferred(StateReadResult.Reason.READ_FAILED),
                        )
                }
            }
        return step(null, true, null, 0)
    }

    /**
     * Applies one page to the board (8eb53-D1): a present entry is an add, an
     * absent one a remove. Returns the number of entries applied.
     */
    private fun apply(page: StatePage): Int = synchronized(state) {
        var n = 0
        for (entry in page.entries) {
            if (entry !is SetCell.SetStateEntry<*>) continue // ExclusiveEntry: no Owned payloads in this schema
            val message = entry.element as? Message ?: continue
            if (entry.present) messages += message else messages -= message
            n++
        }
        n
    }
}
