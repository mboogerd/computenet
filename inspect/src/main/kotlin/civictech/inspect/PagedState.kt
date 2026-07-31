package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Provenance
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * V1C-BE — the paged state read: `GET /api/inspect/cell/{ref}/state?cursor=&limit=`
 * answered from `ManagedHost.readState` (V1C-KERNEL) instead of a whole-state
 * copy.
 *
 * ### What changed, and why it needed its own collaborator
 *
 * Every unobserved state read used to be a whole-state copy on a cell's thread,
 * and the bound the user actually saw (200 rows) was applied *after* the copy,
 * in [ValueEncoder]. A 10⁵-row cell paid its own thread for 10⁵ rows so a panel
 * could show 200. `ManagedHost.readState` now answers one bounded page per
 * scheduler task; what this file adds is everything between that primitive and
 * the wire: the cursor table, the render/re-read reconciliation, the verified
 * stability verdict, and the vocabulary that says *which* nothing an
 * `unavailable` is.
 *
 * ### Read-only, and checkably so (P6)
 *
 * Every path here reaches exactly one kernel seam, `ManagedHost.readState`,
 * whose own KDoc states the property: it installs no link, spawns no sink,
 * attaches no tap, fires no `PullOnOpen` and raises no attention; it never
 * enters `FanOutlet.call`/`at`, so no wave counter moves and no delivered
 * watermark advances. The cursor table holds ids, refs and kernel-minted
 * cursors — no cell, no host, no port — and a walk abandoned by its client
 * expires ([CURSOR_TTL_MS]) rather than pinning anything.
 */
internal class PagedState(
    /**
     * Read through, so a [BoundedReadSource] installed after construction (a
     * test standing in for a slow or absent kernel accessor) still takes effect
     * — the same discipline [InspectorServer.snapshots] follows.
     */
    private val reads: () -> BoundedReadSource,
    private val cursors: CursorTable,
    /**
     * The single bounded wait one request may spend
     * ([InspectorServer.SNAPSHOT_WAIT_MS]). One deadline per read, never two:
     * an `Unavailable` is an *answer* and is never retried against the older
     * whole-copy seam.
     */
    private val waitMs: Long,
) {

    /** What one paged read produced — a body, or the reason it is not one. */
    sealed interface Outcome {
        /** Serve this [CellState] with 200. */
        data class Answered(val state: CellState) : Outcome

        /**
         * The bounded seam did not answer at all (no local host, or a caller
         * that installed [BoundedReadSource.Unavailable]). The caller falls
         * through to the older [SnapshotSource] path, which is what keeps the
         * whole-copy labelling tested and an app-supplied source working.
         */
        data object NoSource : Outcome

        /** 400 — a malformed `?limit=`. */
        data class BadRequest(val reason: String) : Outcome

        /**
         * 410 — an unknown, expired, already-consumed or wrong-cell `?cursor=`.
         * The client drops the cursor and restarts the walk. 410 rather than a
         * silent restart: silently restarting would let a client believe it was
         * continuing a walk it was not, which is the class of lie this whole
         * vertical exists to remove.
         */
        data class Gone(val reason: String) : Outcome
    }

    /**
     * One page of [ref], for the request's `cursor`/`limit` [params].
     *
     * The order is deliberate: a malformed `limit` is refused before a cursor is
     * consumed (so a client's typo does not cost it its walk), and the cursor is
     * consumed before the read is issued (so an id is retired exactly once
     * whatever the read answers).
     */
    fun read(ref: CellRef, encodedRef: String, params: Map<String, String>): Outcome {
        val limit = when (val requested = params[LIMIT_PARAM]?.takeIf { it.isNotBlank() }) {
            null -> InspectorServer.PAGE_LIMIT_DEFAULT
            else -> requested.toIntOrNull()?.takeIf { it > 0 }
                ?: return Outcome.BadRequest("limit must be a positive integer: $requested")
        }.coerceAtMost(InspectorServer.PAGE_LIMIT_MAX)

        val resumeId = params[CURSOR_PARAM]?.takeIf { it.isNotBlank() }
        val resumed = resumeId?.let {
            cursors.take(it, ref) ?: return Outcome.Gone("unknown, expired or already-consumed cursor")
        }

        val request = StateRead(
            cursor = resumed?.cursor,
            limit = limit,
            // Passing false would make every cell V1C-CELLS/V1C-OPS did not
            // cover regress from `kind: "snapshot"` to `kind: "unavailable"` —
            // a strict loss against shipped behaviour. The flag exists so a
            // caller learns the cost before paying; the absent `page` object is
            // how the detail panel knows no bounded read was available.
            allowWholeCopy = true,
        )
        val pending = reads().readState(ref, request) ?: return Outcome.NoSource

        return when (val result = await(pending)) {
            null -> Outcome.Answered(unavailable(encodedRef, CellState.UNANSWERED))

            is StateReadResult.Page ->
                Outcome.Answered(paged(ref, encodedRef, result.page, request, resumed, limit))

            is StateReadResult.Unbounded -> Outcome.Answered(
                CellState(
                    ref = encodedRef,
                    kind = CellState.SNAPSHOT,
                    value = ValueEncoder.encode(result.state),
                    provenance = provenanceOf(result.provenance),
                ),
            )

            // Never a fall-back to the whole-copy seam after this: for MIGRATING
            // that would answer a stale local read, which Decision 7 forbids,
            // and for a drained host it would burn a second deadline on a
            // scheduler that will not run.
            is StateReadResult.Unavailable -> Outcome.Answered(unavailable(encodedRef, unreadableOf(result.reason)))
        }
    }

    /** Drop every open walk — [InspectorServer.close]'s symmetry. */
    fun close() = cursors.clear()

    // ------------------------------------------------------------ the page

    /**
     * One `kind: "page"` body, after the render reconciliation below.
     *
     * **The encoder's budget must never silently swallow entries the cursor has
     * already advanced past.** A page is served only when every entry the kernel
     * returned was rendered: the entries are encoded under an unbounded row
     * allowance (the read's own `limit` is the row bound — see
     * [ValueEncoder.PAGE_ROWS_UNBOUNDED]) and the contract's byte budget, and if
     * the byte budget cut whole entries the *same* read is re-issued at
     * `limit = rendered`, at most [InspectorServer.PAGE_RENDER_RETRIES] times,
     * so the cursor names exactly the entries that were shown.
     *
     * `$truncated` may then still appear *inside* a rendered entry — one wide
     * record abbreviated — which is that marker's existing, unchanged meaning.
     * `page.cursor != null` is the one and only signal that more state exists.
     */
    private fun paged(
        ref: CellRef,
        encodedRef: String,
        first: StatePage,
        request: StateRead,
        resumed: CursorTable.Walk?,
        limit: Int,
    ): CellState {
        var page = first
        var value = encode(page)
        var rendered = ValueEncoder.renderedOf(value, page.entries.size)
        var retries = 0
        while (rendered < page.entries.size && retries < InspectorServer.PAGE_RENDER_RETRIES) {
            retries += 1
            val narrowed = reads().readState(ref, request.copy(limit = rendered.coerceAtLeast(1)))
                ?: break
            val result = await(narrowed) as? StateReadResult.Page ?: break
            page = result.page
            value = encode(page)
            rendered = ValueEncoder.renderedOf(value, page.entries.size)
        }

        val opening = resumed?.opening ?: page.frontier
        val smearedSoFar = resumed?.smeared == true
        val stable = verdict(opening, page, smearedSoFar)
        val caveats = resumed?.caveats.orEmpty() + page.caveats.map(::caveatOf)

        val cursor = page.next?.let { next ->
            cursors.mint(CursorTable.Walk(ref, next, opening, smeared = stable == false, caveats = caveats))
        }

        return CellState(
            ref = encodedRef,
            kind = CellState.PAGE,
            value = value,
            provenance = provenanceOf(page.provenance),
            page = StatePageView(
                cursor = cursor,
                limit = limit,
                entries = page.entries.size,
                exclusivesElided = page.exclusivesElided,
                walkStable = stable,
                caveats = caveats.sorted(),
                attributes = JsonObject(page.attributes.mapValues { (_, v) -> ValueEncoder.encode(v) }),
            ),
        )
    }

    private fun encode(page: StatePage): JsonElement =
        ValueEncoder.encode(page.entries, ValueEncoder.PAGE_ROWS_UNBOUNDED, ValueEncoder.MAX_BYTES)

    /**
     * The walk's stability verdict for this page (V1C-KERNEL Decision 5),
     * verified rather than promised.
     *
     * The comparison is the walk's **opening** stamp against this page's, and it
     * cannot be made per page against page 1 the way this ticket originally
     * assumed: a cell stamps `frontier` exactly on the first and last page of a
     * walk only, declaring [ReadCaveat.STALE_FRONTIER] in between, because an
     * exact per-page frontier costs an O(n) rescan per page — the O(n²) shape
     * the C7 measurement gate ruled out. A per-page equality test would
     * therefore report `true` on every intermediate page of a walk whose fold
     * had already moved, and only flip to `false` on the closing page: the
     * opposite of an honest verdict. So an intermediate page answers `null`
     * ("not determined yet"), and the verdict is complete once the walk closes.
     *
     * `false` **latches**: once a walk is known to have smeared, no later page
     * un-knows it.
     *
     * What `true` licenses is deliberately narrow, and narrower still for some
     * families — see [StatePageView.walkStable].
     */
    private fun verdict(opening: TagFrontier?, page: StatePage, smearedSoFar: Boolean): Boolean? = when {
        smearedSoFar -> false
        opening == null || page.frontier == null -> null
        ReadCaveat.STALE_FRONTIER in page.caveats -> null
        page.frontier == opening -> true
        else -> false
    }

    private fun unavailable(encodedRef: String, unreadable: String): CellState =
        CellState(ref = encodedRef, kind = CellState.UNAVAILABLE, unreadable = unreadable)

    /**
     * The one bounded wait a read may spend; null when it was not spent inside
     * it. `cancel(false)` on the miss, mirroring the [SnapshotSource] default's
     * pattern for the identical accessor: an abandoned read costs the host a
     * dequeue instead of a page, because `readState`'s task checks the future
     * before entering the cell.
     */
    private fun await(pending: CompletableFuture<StateReadResult>): StateReadResult? =
        runCatching { pending.get(waitMs, TimeUnit.MILLISECONDS) }
            .onFailure { pending.cancel(false) }
            .getOrNull()

    internal companion object {
        const val CURSOR_PARAM = "cursor"
        const val LIMIT_PARAM = "limit"

        /** The kernel's `Provenance` on the wire — see [CellState.LIVE] and its siblings. */
        fun provenanceOf(provenance: Provenance): String = when (provenance) {
            Provenance.LIVE -> CellState.LIVE
            Provenance.LIVE_SUSPENDED -> CellState.LIVE_SUSPENDED
            Provenance.CHECKPOINT -> CellState.CHECKPOINT
        }

        fun caveatOf(caveat: ReadCaveat): String = when (caveat) {
            ReadCaveat.STALE_FRONTIER -> StatePageView.STALE_FRONTIER
            ReadCaveat.POSITIONAL_CURSOR -> StatePageView.POSITIONAL_CURSOR
        }

        /**
         * The kernel's nine `StateReadResult.Reason` arms in the contract's
         * `unreadable` vocabulary.
         *
         * `SCHEDULER_TERMINATED` and `READ_FAILED` are mapped explicitly rather
         * than falling through to [CellState.UNKNOWN]: both are real, reachable
         * answers about a live local host, which is exactly what `"unknown"` is
         * reserved *not* to mean.
         *
         * The four remaining arms are **unreachable from this endpoint** as long
         * as it passes `allowWholeCopy = true` and neither `since` nor `scope`
         * — `NOT_BOUNDED` and `CHECKPOINT_NOT_BOUNDED` are the refusals that
         * flag turns off, and `SINCE_UNSUPPORTED`/`SCOPE_UNSUPPORTED` are
         * refused only for a bound this endpoint never asks for. They are mapped
         * to [CellState.UNKNOWN] rather than guessed at a nicer word: if one
         * ever appears, the server is doing something it does not think it is.
         */
        fun unreadableOf(reason: StateReadResult.Reason): String = when (reason) {
            StateReadResult.Reason.MIGRATING -> CellState.MIGRATING
            StateReadResult.Reason.NOT_HOSTED -> CellState.REMOTE
            StateReadResult.Reason.NOT_STATEFUL -> CellState.NOT_STATEFUL
            StateReadResult.Reason.SCHEDULER_TERMINATED -> CellState.TERMINATED
            StateReadResult.Reason.READ_FAILED -> CellState.READ_FAILED
            StateReadResult.Reason.NOT_BOUNDED,
            StateReadResult.Reason.CHECKPOINT_NOT_BOUNDED,
            StateReadResult.Reason.SINCE_UNSUPPORTED,
            StateReadResult.Reason.SCOPE_UNSUPPORTED,
            -> CellState.UNKNOWN
        }
    }
}

/**
 * V1C-BE — the server-minted cursor ids `page.cursor` hands a client, and the
 * walk state behind them.
 *
 * ### Why an id, and not the kernel's own cursor
 *
 * `Cursor(token: Serializable)` is cell-minted and opaque, and **must not be
 * reconstructed from client input**: Java-deserializing a client-supplied token
 * would be a deserialization sink on an HTTP endpoint, and no dev-instrument
 * convenience is worth that. **No client-supplied bytes are ever deserialized
 * here.** The string a client echoes is an id into this table and nothing else;
 * an id this table does not hold is a 410, never an attempt to interpret it.
 *
 * ### One id per page
 *
 * Each response mints a fresh id for its `next` cursor and retires the id that
 * produced it, so an accidentally re-sent cursor answers 410 (visible) instead
 * of silently skipping a page (invisible). The walk's opening frontier, its
 * running `walkStable` verdict and its accumulated caveats are inherited by the
 * successor id — which is what makes V1C-KERNEL Decision 5's *verifiable*
 * stability claim checkable without asking the client to do bookkeeping it
 * cannot do. A raw `TagFrontier` never goes on the wire: it is a
 * `Map<UUID, Long>` whose size grows with the tag source count, the client
 * cannot construct one, and the only actionable fact is the verdict.
 *
 * ### Bounded, because an abandoned walk must not pin server state
 *
 * Entries expire after [InspectorServer.CURSOR_TTL_MS] and the table holds at
 * most [InspectorServer.CURSOR_MAX_OPEN] of them, evicting oldest-first; a final
 * page (`next == null`) mints nothing at all, so a completed walk leaves no
 * entry behind.
 *
 * This table belongs to the HTTP endpoint, not to the read seam: [DataSearch]
 * reads one page per cell and never resumes, so it mints no entries.
 */
internal class CursorTable(
    private val clock: () -> Long,
    private val ttlMs: Long = InspectorServer.CURSOR_TTL_MS,
    private val maxOpen: Int = InspectorServer.CURSOR_MAX_OPEN,
) {

    /**
     * One open walk. [ref]-bound: an id used against another cell is a 410, not
     * a page of the wrong cell.
     *
     * @property opening the walk's page-1 `TagFrontier`, the only thing
     *   `walkStable` compares against.
     * @property smeared the latched `walkStable == false` verdict — once a walk
     *   is known to have smeared, no later page un-knows it.
     * @property caveats every `ReadCaveat` any page of this walk declared, in
     *   the contract's spelling, so a client joining at page 4 still learns that
     *   this walk's cursor is positional.
     */
    internal class Walk(
        val ref: CellRef,
        val cursor: Cursor,
        val opening: TagFrontier?,
        val smeared: Boolean,
        val caveats: Set<String>,
        var mintedAtMs: Long = 0,
    )

    private val lock = Any()

    /** Insertion-ordered, so eviction is "oldest first" without a second index. */
    private val open = LinkedHashMap<String, Walk>()

    /** Live walk ids — diagnostics and tests. */
    val size: Int get() = synchronized(lock) { open.size }

    /**
     * Consume [id] for [ref], or null when it is unknown, expired, already
     * consumed, or minted for a different cell — the four cases that are all
     * one 410.
     */
    fun take(id: String, ref: CellRef): Walk? = synchronized(lock) {
        expire()
        val walk = open[id] ?: return null
        if (walk.ref != ref) return null
        open.remove(id)
        walk
    }

    /** Mint a fresh id for [walk]; the id that produced it was already retired by [take]. */
    fun mint(walk: Walk): String = synchronized(lock) {
        expire()
        while (open.size >= maxOpen) open.remove(open.keys.first())
        val id = "$ID_PREFIX${UUID.randomUUID()}"
        walk.mintedAtMs = clock()
        open[id] = walk
        id
    }

    fun clear() = synchronized(lock) { open.clear() }

    private fun expire() {
        val cutoff = clock() - ttlMs
        val stale = open.entries.takeWhile { it.value.mintedAtMs <= cutoff }.map { it.key }
        stale.forEach(open::remove)
    }

    private companion object {
        /**
         * Opaque to the client by contract; a prefix only so a cursor is
         * recognisable in a log or a bug report.
         */
        const val ID_PREFIX = "p-"
    }
}
