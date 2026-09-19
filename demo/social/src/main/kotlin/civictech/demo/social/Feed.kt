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
 *   `snb-authored` cell the viewer's [Interest.Ranges] scope admits, legs
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
 * [scope] is passed in: its arms are the viewer's friends as singleton ranges
 * `Range(id, id + 1)` (07k-D2 — `Range` is half-open). Deriving it from the
 * `knows` set is a later feature's job.
 *
 * **Legs.** Keys are enumerated from [scope]'s ranges, never by scanning the
 * family. `KeyedCells.refFor` is private and `getOrSpawn` spawns for an
 * unknown key, so a key is resolved to a ref only if it is in ONE
 * `authored.keys()` snapshot taken per pull. A friend who has authored
 * nothing has no cell: no read is issued for them and they are not in the
 * [PullReport] (there is no ref to key them by); they join the pull that
 * follows their first post, reading from `since = null`.
 *
 * Not reentrant: one caller, one pull at a time.
 */
class FeedSession(
    val viewer: Long,
    private val scope: Interest.Ranges,
    private val families: SnbPipeline.Families,
    private val registry: LocationRegistry,
    private val reader: BoundedReader,
    private val pageLimit: Int = 200,
) {
    init {
        require(pageLimit > 0) { "pageLimit must be positive, got $pageLimit" }
    }

    /** Guards [messages] and [retained]: pages complete on the host's scheduler thread. */
    private val state = Any()
    private val messages = HashSet<Message>()
    private val retained = HashMap<CellRef, TagFrontier>()
    private val inFlight = AtomicBoolean(false)

    /** The union of every answered leg's slice so far (a copy). */
    fun board(): Set<Message> = synchronized(state) { messages.toSet() }

    /** The frontier retained per leg ref (a copy) — one entry per leg that has ever answered with one. */
    fun frontiers(): Map<CellRef, TagFrontier> = synchronized(state) { retained.toMap() }

    /**
     * Fans one bounded read walk out per leg, all issued before any is
     * awaited, and completes when every leg has answered or been deferred.
     * Never blocks: on a simulated host the pages land on `runToIdle()`.
     */
    fun pull(): CompletableFuture<PullReport> {
        check(inFlight.compareAndSet(false, true)) { "FeedSession.pull() is not reentrant" }
        val live = families.authored.keys()
        val legs = scope.ranges
            .flatMap { r -> (r.lo until r.hi).asIterable() }
            .distinct()
            .filter { it in live }
            .map { families.authored.getOrSpawn(it).ref }
            .filter { registry.interestOf(it).overlaps(scope) }
        val outcomes = legs.map { ref ->
            val since = synchronized(state) { retained[ref] }
            ref to walk(ref, since)
        }
        return CompletableFuture.allOf(*outcomes.map { it.second }.toTypedArray())
            .thenApply { PullReport(outcomes.associateTo(LinkedHashMap()) { (ref, f) -> ref to f.join() }) }
            .whenComplete { _, _ -> inFlight.set(false) }
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
