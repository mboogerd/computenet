package civictech.cell

import civictech.cell.link.Interest
import java.io.Serializable

/**
 * The bounded state read (V1C-KERNEL, closing MRB-157) — the *cell* half of a
 * read an instrument can afford.
 *
 * ### Why this exists
 *
 * A wave-neutral read already shipped:
 * [civictech.cell.host.ManagedHost.snapshotOf] runs [Stateful.snapshot] on the
 * cell's own execution context, links nothing, emits nothing, moves no wave
 * counter, honours cancellation and never completes exceptionally. What it
 * lacks is a **bound**: `snapshot()` copies the whole fold, so a 10⁵-row cell
 * pays for 10⁵ rows on its own thread and the instrument then throws 99.8% of
 * them away at its 200-row render budget. Measured (`V1C-BENCH`,
 * `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`):
 * a concurrent whole-state copy stalls a 10⁵-element cell's own live traffic
 * for ~28 ms — `snapshotOf` submits at scheduler priority 0, *above* data's
 * 20, so the copy jumps the queue and then owns the thread for its duration.
 * Slicing the same read into 200-entry pages removes ~85–99% of that stall at
 * a ~1.7–2.4× total-work premium.
 *
 * **One page = one scheduler task.** That is the entire win, and implementors
 * must not batch pages inside one invocation: a 10⁵-row read should become
 * ~500 short tasks interleaved with the cell's real work, not one long one.
 *
 * ### Opt-in, and why [Stateful] did not grow a method
 *
 * Four subsystems depend on `snapshot()` being a whole, restorable value —
 * drain, migration (spec 33 §Mobility), promotion state transfer
 * ([civictech.cell.evolve]) and durability checkpoints (spec 24). Adding
 * `readBounded` to [Stateful] would make a paged read a precondition for being
 * drainable, ripple into [civictech.cell.observe.View] and into every
 * conformance-driver adapter. This interface is therefore additive and opt-in:
 * a cell that does not implement it is answered
 * [StateReadResult.Unavailable] with [StateReadResult.Reason.NOT_BOUNDED],
 * or — only on explicit [StateRead.allowWholeCopy] —
 * [StateReadResult.Unbounded]. **Never a silent whole copy**: the point of the
 * primitive is that a caller learns what a read costs *before* paying for it.
 *
 * ### The obligations an implementation takes on
 *
 * 1. **Impose a total enumeration order; it is not inherited.** The backing
 *    maps of the data-cell family are `LinkedHashMap`s, so insertion order is
 *    *not* a stable enumeration order: a remove-then-re-add moves a key to the
 *    tail and can hand one key to a walk twice, and [Stateful.restore] rebuilds
 *    every map from a `HashMap`/`HashSet`, so a restored instance enumerates
 *    differently. "No entry returned twice in one walk" is therefore something
 *    the cell must *make* true — freezing the key sequence into the cursor at
 *    walk start is the cheapest way, and the one
 *    [civictech.cell.data.SetCell] takes.
 * 2. **Resume in O(page), not O(n).** A cursor that rescans the cell's state
 *    from the start on each page turns the measured ~2× premium into O(n²) and
 *    invalidates the trade the C7 measurement gate accepted. A walk may pay a
 *    bounded number of O(n) passes (`SetCell` pays two: one at open, one at
 *    close), but *per page* work must be O([StateRead.limit]).
 * 3. **Key-based, not index-based.** An index into live state is invalidated by
 *    any removal earlier in the enumeration; a key survives every mutation but
 *    its own. A **positional** cursor is a permitted, documented per-cell
 *    exception for a family with no element identity (`ListCell`), and the
 *    weaker guarantee it carries must be declared on the page as
 *    [ReadCaveat.POSITIONAL_CURSOR] — not buried in the cell.
 * 4. **Never page an exclusive value.** See [ExclusiveEntry].
 * 5. **Never silently widen a bound.** [supportsSince] / [supportsScope]
 *    default to `false`, so a family that cannot honour [StateRead.since] or
 *    [StateRead.scope] refuses the request rather than answering full state as
 *    though the bound had been applied.
 *
 * ### Threading
 *
 * [readBounded] is invoked by [civictech.cell.host.ManagedHost.readState] on
 * the cell's own execution context, between invocations — the same guarantee
 * [civictech.cell.observe.ObservationSink.current] documents, and the reason a
 * page is never a partially-applied delta. Implementations must not assume
 * anything else about the calling thread, and must not emit, link, or raise
 * attention (P6): a read is not a subscription.
 */
interface BoundedStateful : Stateful {

    /**
     * Produce one page of this cell's state for [request]. Runs on the cell's
     * execution context; must not emit, link, or mutate the fold.
     *
     * Returns whole entries only, at most [StateRead.limit] of them, with
     * [StatePage.next] naming where a follow-up page resumes (`null` = the walk
     * is complete). Implementations own the cursor encoding entirely — the
     * kernel never interprets it.
     */
    fun readBounded(request: StateRead): StatePage

    /**
     * Can this cell honour [StateRead.since] — i.e. answer with only the tags
     * beyond a [TagFrontier], and stamp its pages with a real frontier?
     *
     * Default `false`, which is the *safe* default: several state families
     * carry no tag frontier at all (`MapCell`, `ListCell`, `Watermark`,
     * `InstanceSet`), and for them
     * [civictech.cell.host.ManagedHost.readState] refuses a non-null `since`
     * with [StateReadResult.Reason.SINCE_UNSUPPORTED] rather than letting the
     * cell answer unbounded-by-`since` state as though the bound had been
     * honoured. The precedent for being explicit about it is [TagFrontier]'s
     * own "full-state fallback otherwise, `since = null`".
     *
     * **Must be a constant for the cell's lifetime.** It is read on the
     * caller's thread, before anything is submitted to this cell's host, so
     * that a caller learns a request cannot be served without paying for a
     * scheduler round trip.
     */
    val supportsSince: Boolean get() = false

    /**
     * Can this cell honour [StateRead.scope] — restrict the page to the
     * sub-state an [Interest] admits? Default `false`, refused with
     * [StateReadResult.Reason.SCOPE_UNSUPPORTED], for the same
     * never-silently-widen reason as [supportsSince]. A widened `scope` answer
     * is worse than a widened `since` one: it discloses state the caller
     * declared no interest in.
     *
     * Must be a constant for the cell's lifetime; read on the caller's thread.
     */
    val supportsScope: Boolean get() = false
}

/**
 * What a caller asks of [BoundedStateful.readBounded] (V1C-KERNEL).
 *
 * Three orthogonal bounds, deliberately: [since] bounds by **time**, [scope]
 * bounds by **interest**, [cursor]/[limit] bound by **size**. The first two are
 * reused verbatim from
 * [civictech.cell.protocol.StateRequest] rather than forked or generalized — a
 * big-cell read wants all three at once (search wants `scope`, a live view
 * wants `since`, the UI wants `limit`). This is *not* a `StateRequest`: a pull
 * reply installs a [MessageContext.baseline] in a consumer's fold, which a read
 * must never do, and a pull reply needs a link or a tap to be received at all
 * (P6) — which is the actual reason an instrument cannot use one.
 *
 * @property cursor Opaque, cell-minted resume token; `null` starts a fresh
 *   walk. The kernel never interprets it.
 * @property limit **Hard** cap on the number of entries in the returned page.
 * @property byteBudget **Advisory**, cell-estimated ceiling on a page's
 *   encoded size; a cell that cannot estimate ignores it. A cell honouring it
 *   must still return at least one entry when one is available, so a walk
 *   always makes progress.
 * @property scope `null` ⇒ [Interest.Total] ⇒ the whole state.
 * @property since `null` ⇒ full state, not a delta.
 * @property allowWholeCopy Opt in to an unbounded answer for a cell that is
 *   [Stateful] but not [BoundedStateful], and for a drained host's retained
 *   checkpoint blob. Default `false` — a caller that has not said this is never
 *   handed a whole copy.
 */
data class StateRead(
    val cursor: Cursor? = null,
    val limit: Int = 200,
    val byteBudget: Int = 50_000,
    val scope: Interest? = null,
    val since: TagFrontier? = null,
    val allowWholeCopy: Boolean = false,
) {
    init {
        require(limit > 0) { "limit must be positive (was $limit)" }
        require(byteBudget > 0) { "byteBudget must be positive (was $byteBudget)" }
    }
}

/**
 * One page of a cell's state (V1C-KERNEL).
 *
 * ### What one page promises
 *
 * - It is **internally consistent**: produced on the cell's own execution
 *   context, between invocations, so no partially-applied delta is ever
 *   visible.
 * - **Entries are whole.** An entry is never split across pages.
 * - Under a key-ordered cursor, **no entry is returned twice in one walk**.
 * - It carries the fold's [TagFrontier] as of the last point in this walk the
 *   cell determined one exactly — see [frontier].
 *
 * ### What a *walk* promises: verifiable stability, not isolation
 *
 * A walk is a **sequence of per-page-consistent reads, not a snapshot**.
 *
 * - **If the frontier is unchanged from the first page to the last, the union
 *   of the pages is exactly a snapshot of that fold at that frontier** — for a
 *   family in which every state change mints or absorbs a tag. This is a claim
 *   the caller *checks*, not one the kernel promises. A [TagFrontier] is
 *   monotone, so comparing the walk's opening and closing stamps is sufficient
 *   to detect any tag the fold *gained*, and both stamps are exact (see
 *   [frontier]); no intermediate stamp can differ from two equal endpoints.
 *
 *   **The check detects tag gains, and only tag gains**, which is the whole of
 *   what a [TagFrontier] measures. A family whose mutations do not all mint
 *   tags therefore has a stability check that is *necessary but not
 *   sufficient*, and must say so on its own `readBounded`. The known instance
 *   is the OR-set: [civictech.cell.data.SetCell]'s observed-remove tombstones
 *   an element by copying the add-tags it already holds into its del-map
 *   (`21`, effective-only removal), minting nothing — so a mid-walk removal of
 *   an element the walk has already paged leaves both endpoint stamps equal
 *   while the union names that element present. A caller that must not be
 *   wrong about removals cannot get that from the stamp alone; the `since`
 *   escalation path below has the same limit, and closing it is a state-family
 *   question filed as research, not a property this page can carry.
 * - **If it advanced, the union is a smeared read**: it contains every entry
 *   present for the whole walk, may contain entries added mid-walk, and may
 *   miss entries added or removed mid-walk after the walk had already passed
 *   their position. It is never torn at entry granularity and never
 *   duplicated.
 *
 * **Why not snapshot isolation.** It would need either copy-on-write
 * versioning inside every state cell — a per-message cost on the fold path,
 * forbidden by P2 — or holding the cell's execution context for the whole
 * walk, which is "viz blocks the graph" by construction. Detection is cheaper
 * than locking.
 *
 * **The escalation path for a caller who genuinely needs a snapshot**, for a
 * tag-carrying cell: record `frontier₀` from page 1, walk to completion, then
 * issue one final read with `since = frontier₀`; folding that delta over the
 * smeared union yields a real snapshot at the closing frontier. This reuses
 * [StateRead.since] rather than adding a second mechanism, and it is why
 * `since` belongs on a read at all. It is unavailable for a family whose
 * [frontier] is null (see [BoundedStateful.supportsSince]).
 *
 * @property entries Whole entries, in the cell's own enumeration order. A cell
 *   may mix its own entry type with [ExclusiveEntry] descriptors.
 * @property next Resume token; `null` = the walk is complete. A page may be
 *   short — or even empty — with a non-null `next`, when keys were skipped by
 *   [StateRead.since]/[StateRead.scope] or by [StateRead.byteBudget]; only
 *   `next == null` terminates a walk.
 * @property frontier The fold's tag frontier. **Exact on the first page of a
 *   walk and on the last** (the two points where a full pass is already paid
 *   for or is the walk's closing act); on an intermediate page it may be the
 *   most recent exactly-determined frontier instead, which the cell declares
 *   with [ReadCaveat.STALE_FRONTIER]. Null for a family that has no tag
 *   frontier at all — which also makes the stability check and the `since`
 *   escalation path unavailable.
 * @property provenance Where the answer came from. A cell always reports
 *   [Provenance.LIVE]; the host overwrites it — it, not the cell, knows whether
 *   the cell is suspended or whether the answer came from a drain checkpoint.
 * @property exclusivesElided How many entries were replaced by an
 *   [ExclusiveEntry] descriptor because their value is `Owned`/`Leased`. A
 *   non-zero count is an honest signal, never a silent gap.
 * @property attributes Cell-level state that is not per-entry and must ride
 *   *every* page rather than only the first — `SetCell`'s tag `counter`,
 *   `ShardCell`'s `interest`/`assignedEpoch`. A caller that starts reading at
 *   page 4 still sees them.
 * @property caveats Guarantees this page is weaker than the contract above.
 *   Empty is the norm.
 */
data class StatePage(
    val entries: List<Serializable>,
    val next: Cursor? = null,
    val frontier: TagFrontier? = null,
    val provenance: Provenance = Provenance.LIVE,
    val exclusivesElided: Int = 0,
    val attributes: Map<String, Serializable> = emptyMap(),
    val caveats: Set<ReadCaveat> = emptySet(),
)

/**
 * An opaque, cell-minted resume token (V1C-KERNEL). The kernel never
 * interprets one; only the cell knows its state layout — the same reasoning
 * that keeps [Interest] polymorphic across a bridge.
 *
 * Typed [Serializable] to keep a token representable, not because it travels:
 * remote bounded reads are explicitly out of scope, and a cursor's meaning
 * across a scatter-gather boundary (where the answering instance may change
 * between pages) is an open research question.
 */
@JvmInline
value class Cursor(val token: Serializable)

/**
 * Where a [StatePage] or [StateReadResult.Unbounded] came from (V1C-KERNEL) —
 * the kernel's answer to a question the instrument previously had to guess
 * from registry metadata.
 */
enum class Provenance {
    /** The live cell, read on its own execution context. */
    LIVE,

    /**
     * The live cell, whose data intake is parked
     * ([civictech.cell.host.ManagedHost.isSuspended]). A suspended cell's fold
     * is quiescent by construction, which makes it the *most* stable thing in
     * the graph to read — it is answered, never skipped.
     */
    LIVE_SUSPENDED,

    /**
     * The checkpoint blob a drained host already holds (spec 33 §Drain step 3).
     * No cell thread is touched to produce it.
     */
    CHECKPOINT,
}

/**
 * A named way in which a [StatePage] is weaker than [StatePage]'s stated
 * contract (V1C-KERNEL). Declared on the page rather than in a cell's KDoc, so
 * a caller can see it.
 */
enum class ReadCaveat {
    /**
     * [StatePage.frontier] is the most recent frontier this walk determined
     * exactly (its opening one), not the fold's frontier at this page's
     * production. The cell declined to rescan its whole tag state per page,
     * which would be O(n) per page and O(n²) per walk — the cost the C7
     * measurement gate ruled out. The first and last page of a walk always
     * carry an exact frontier, and a [TagFrontier] is monotone, so the
     * stability check of [StatePage] is unaffected.
     */
    STALE_FRONTIER,

    /**
     * The cursor is **positional**, not key-based — the documented exception
     * for a family with no element identity (`ListCell`). A removal earlier in
     * the sequence can shift or skip an entry, so "no entry twice in one walk"
     * and "every surviving entry appears" both weaken to best-effort.
     */
    POSITIONAL_CURSOR,
}

/**
 * The presence descriptor that stands in for an exclusive payload in a page
 * (V1C-KERNEL).
 *
 * **A page never carries an `Owned`/`Leased` value or a copy of one.**
 * [Serializable] means *copy*, and copying an exclusive payload is the
 * prohibition itself (spec 23 §Ownership). A tap is already borrow-only —
 * "never retained, mutated, or released" — and a read is weaker than a tap, so
 * anything a tap may not do a read certainly may not. The entry is replaced by
 * this descriptor and [StatePage.exclusivesElided] is incremented, which is an
 * honest signal rather than a silent gap.
 *
 * This makes a bounded read's ownership contract deliberately **stronger** than
 * [Stateful.snapshot]'s, which today serializes whatever the fold holds.
 * Closing that older seam is out of scope here; spec 23 has no rule for a fold
 * whose state contains exclusive values (G-46 covers only the crash-loss half).
 *
 * @property key The entry's key, when the key itself is not the exclusive
 *   value (a map whose *values* are exclusive). Null when the exclusive value
 *   *is* the key — a set of `Owned` elements.
 * @property typeName The wrapper type observed **without unwrapping**
 *   (`civictech.cell.Owned` / `civictech.cell.Leased`). The wrapped value's own
 *   type is deliberately not read: obtaining it would mean `borrow()`ing or
 *   reflecting into the payload, and a read may do neither.
 * @property identity `System.identityHashCode` of the wrapper — enough to tell
 *   two elided entries apart within one JVM, without touching the value.
 * @property disposition What the *fold* knows about its own obligation; see
 *   [Disposition].
 */
data class ExclusiveEntry(
    val key: Serializable?,
    val typeName: String,
    val identity: Int,
    val disposition: Disposition,
) : Serializable {

    /**
     * What the fold knows about an exclusive payload's obligation. Deliberately
     * coarse: neither `Owned` nor `Leased` exposes a non-consuming predicate for
     * "already taken" / "already released", and a read may not use reflection to
     * find out (kernel changes stay reflection-free), so a cell reports what it
     * knows about *its own* handling and nothing more.
     */
    enum class Disposition {
        /** The fold holds the reference and has neither taken nor released it. */
        HELD,

        /** The fold discharged the obligation (`take()`/`release()`) itself. */
        DISCHARGED,

        /** The fold cannot say. */
        UNKNOWN,
    }

    companion object {
        /** Is [value] an exclusive payload that must never be paged? */
        fun isExclusive(value: Any?): Boolean = value is Owned<*> || value is Leased<*>

        /**
         * Describe [exclusive] without unwrapping, consuming, borrowing or
         * releasing it. [key] is the entry's key, or null when the exclusive
         * value is itself the key.
         */
        fun of(
            key: Serializable?,
            exclusive: Any,
            disposition: Disposition = Disposition.HELD,
        ): ExclusiveEntry = ExclusiveEntry(
            key = key,
            typeName = exclusive.javaClass.name,
            identity = System.identityHashCode(exclusive),
            disposition = disposition,
        )
    }
}

/**
 * What [civictech.cell.host.ManagedHost.readState] answers with (V1C-KERNEL).
 *
 * A read never completes exceptionally and never answers with a whole copy the
 * caller did not ask for: every way a read can fail to produce a page is a
 * named [Reason] the caller can act on, which is the information the
 * instrument previously reconstructed *after* the fact from a truncated
 * response.
 */
sealed interface StateReadResult {

    /** One page of a [BoundedStateful] cell's state. */
    data class Page(val page: StatePage) : StateReadResult

    /**
     * A whole, unbounded value — only ever returned when the caller passed
     * [StateRead.allowWholeCopy]. Either a non-[BoundedStateful] cell's
     * [Stateful.snapshot] ([Provenance.LIVE]/[Provenance.LIVE_SUSPENDED]) or a
     * drained host's retained checkpoint blob ([Provenance.CHECKPOINT]).
     */
    data class Unbounded(
        val state: Serializable,
        val provenance: Provenance = Provenance.LIVE,
    ) : StateReadResult

    /** No page and no value; [reason] says which of the defined cases applies. */
    data class Unavailable(val reason: Reason) : StateReadResult

    /**
     * Why a read produced neither a page nor a value. Each arm is a case the
     * kernel *decided*, rather than a silence the caller has to interpret.
     */
    enum class Reason {
        /** The ref is not hosted here and this host's registry does not place it anywhere. */
        NOT_HOSTED,

        /** The cell is hosted here but is not [Stateful]; there is no state seam to read. */
        NOT_STATEFUL,

        /**
         * The cell is [Stateful] but not [BoundedStateful], and the caller did
         * not pass [StateRead.allowWholeCopy]. Never a silent whole copy.
         */
        NOT_BOUNDED,

        /**
         * The host is drained and holds a checkpoint blob for this cell, but a
         * blob captured by [Stateful.snapshot] is not itself pageable and the
         * caller did not pass [StateRead.allowWholeCopy].
         */
        CHECKPOINT_NOT_BOUNDED,

        /**
         * The authoritative instance is not this host's: the ref is held for a
         * migration flip ([civictech.cell.host.LocationRegistry.isHeld]), or it
         * left this host's `cells` and is published elsewhere. Answering from a
         * stale local object would be a lie with a timestamp on it.
         */
        MIGRATING,

        /** [StateRead.since] was set on a cell that declares [BoundedStateful.supportsSince] false. */
        SINCE_UNSUPPORTED,

        /** [StateRead.scope] was set on a cell that declares [BoundedStateful.supportsScope] false. */
        SCOPE_UNSUPPORTED,

        /** The host's scheduler is terminated; a dead host has no state to read. */
        SCHEDULER_TERMINATED,

        /**
         * The cell's own `readBounded`/`snapshot` threw. A diagnostic read must
         * not turn a broken cell into a broken caller.
         */
        READ_FAILED,
    }
}
