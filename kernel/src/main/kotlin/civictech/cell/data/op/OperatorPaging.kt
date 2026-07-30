package civictech.cell.data.op

import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.delta.TagState
import java.io.Serializable
import java.util.UUID

/**
 * The cross-sub-state paging skeleton for the composite operator cells
 * (V1C-OPS) — the shared half of [civictech.cell.BoundedStateful] that every
 * cell in this package delegates to, in the register of [TaggedSetOperator] and
 * [KeyedBinarySetJoin].
 *
 * ### The problem this file exists to solve
 *
 * `civictech.cell.data.SetCell` — `V1C-KERNEL`'s reference implementation — has
 * **one** key space, so its cursor is one frozen key sequence and one index into
 * it. An operator cell's `snapshot()` is instead an `ArrayList` of two or three
 * *heterogeneous* sub-snapshots (a left index, a right index, an output
 * ledger), whose key spaces are different types and, where they are the same
 * type, **not disjoint in content**: `IntersectSetCell` holds the same element
 * `E` in `leftState`, in `rightState` and in its `ledger`, each with a different
 * tag set. A cursor naming only "the last key `e`" cannot say *which* of the
 * three it had reached, so a resume either re-emits `e` from a sub-state already
 * walked or walks off the end of one sub-state and never enters the next.
 *
 * ### Decision A — an entry is `(subState, key)`, not `key`
 *
 * Every entry carries the name of the sub-state it came from ([OperatorEntry.subState]).
 * The same element in three sub-states is **three distinct entries**, never one
 * entry returned three times and never deduplicated: the three carry different
 * tags, and a consumer that cannot tell them apart cannot reconstruct the cell's
 * state. The label is a stable name (`"left"`, `"right"`, `"ledger"`, `"lanes"`,
 * `"groups"`, …), not a bare ordinal, so a later encoding change cannot silently
 * renumber it. It lives *inside* the entry: [StatePage.attributes] is
 * cell-level, not per-entry, and so is the wrong home for a discriminator.
 *
 * ### Decision B — the cursor is lexicographic `(subStateOrdinal, intraStateKey)`
 *
 * Sub-states are declared in the same order the cell's `snapshot()`
 * `arrayListOf(...)` uses, so the two orderings cannot drift; within a
 * sub-state the order is Decision C's. The resulting strict total order over
 * `(ordinal, key)` pairs gives both properties directly: **no entry twice**
 * (the pairs are unique by construction) and **a resume lands in the right
 * sub-state** (the cursor names the ordinal, so exhausting sub-state *i*
 * continues at the head of *i+1* rather than terminating). A page freely spans a
 * sub-state boundary; only running out of *every* sub-state ends a walk.
 *
 * A single lexicographic token sufficed for every cell here — no composite in
 * this package needed the *vector* of per-sub-state tokens that
 * `20-wave-neutral-read-design.md` §7 item 3 anticipated, because no two
 * sub-states have to be walked in lock-step for a page to mean anything.
 *
 * ### Decision C — intra-sub-state order: `V1C-KERNEL`'s mechanism, verbatim
 *
 * The key sequence of **every** sub-state is frozen into the cursor at walk
 * start, exactly as `SetCell.SetWalk` freezes its one sequence, and for exactly
 * the same reason: the backing maps are `LinkedHashMap`s
 * ([TagState]'s `live`, `JoinCell`'s `leftMap`/`rightMap`,
 * `AdvertisedLedger`'s `advertised`, `GroupByCell`'s `groups`, `PresenceLanes`'
 * `lanes`, …), so a remove-then-re-add moves a key to the *tail* and would hand
 * one key to a walk twice, and `restore()` rebuilds every map from a `HashMap`
 * and reorders wholesale. "No entry twice in one walk" is therefore something
 * the cell *makes* true, not something it inherits. Freezing copies key
 * references only — never a value, never a tag set — so it is a small fraction
 * of what one `snapshot()` costs, and a resume is one array index plus one map
 * lookup per entry: O(page) per page, not O(n).
 *
 * A frozen key that has since left the fold is skipped when the walk reaches it
 * (never restarted, never thrown), so a cursor is stable under the removal of
 * the very key it names.
 *
 * ### Decision F — a nested sub-state, recursively
 *
 * `PresenceLanes` is `laneId -> TagState`, i.e. two levels of enumeration before
 * an element, so `QuorumSetCell`'s cursor is three components deep:
 * `(subStateOrdinal, laneId, element)`. The same lexicographic rule applied
 * recursively materializes the inner two as a frozen sequence of
 * `(laneId, element)` pairs, and every such entry carries its
 * [TaggedEntry.lane]: the lane boundary is preserved, because the lane is
 * exactly what makes a quorum count meaningful.
 *
 * ### Ownership (V1C-KERNEL Decision 3), and one deliberate deviation
 *
 * An `Owned`/`Leased` element, key or value is never copied into a page: it is
 * replaced by an [ExclusiveEntry] presence descriptor and the entry is counted
 * in [StatePage.exclusivesElided]. Nothing is taken, borrowed, released or
 * unwrapped.
 *
 * **The deviation, deliberate and reported:** `SetCell` emits the bare
 * [ExclusiveEntry] *as* the page entry. A composite cannot, because that would
 * discard the sub-state label and reintroduce exactly the ambiguity Decision A
 * exists to remove — an elided element of `IntersectSetCell`'s `leftState` would
 * be indistinguishable from the same elided element of its `ledger`. The
 * descriptor therefore rides **inside** the cell's own entry, in the slot the
 * exclusive value would have occupied ([TaggedEntry.element],
 * [KeyedEntry.key]/[KeyedEntry.value]). [StatePage.entries] is documented as "a
 * cell may mix its own entry type with [ExclusiveEntry] descriptors"; nesting
 * keeps the honest signal ([StatePage.exclusivesElided] still counts it) while
 * keeping the identity. A consumer looking only for a top-level [ExclusiveEntry]
 * must read the count, which is the field that never lies.
 *
 * ### The frontier, and what its equality does *not* prove here
 *
 * A tag-carrying operator cell stamps [StatePage.frontier] with the max
 * per-source counter over every tag in every sub-state, **exact on the first
 * page of a walk and on the last** and carrying the opening stamp with
 * [ReadCaveat.STALE_FRONTIER] in between — recomputing it per page would be
 * O(n) per page and O(n²) per walk, the cost the C7 measurement gate ruled out.
 *
 * `StatePage`'s across-page stability check ("equal endpoint frontiers ⇒ the
 * union is exactly a snapshot") holds **for a family in which every state change
 * mints or absorbs a tag**, and no operator cell in this package is such a
 * family — a strictly weaker position than `SetCell`'s, and stated here rather
 * than discovered:
 *
 * - These [TagState] folds are **non-retaining** (`retainTombstones = false`
 *   everywhere except `UnionSetCell`, which this ticket excludes), so a del
 *   *deletes* tags from the live map instead of tombstoning them. A mid-walk
 *   removal therefore mints nothing, and can even *lower* the max for its
 *   source — so this frontier is not even monotone, and two equal endpoint
 *   stamps do not by themselves prove that nothing moved in between.
 * - `MintedLedger.exit`/`AdvertisedLedger.exit` remove an advertised tag with
 *   the same effect.
 *
 * What the stamp *does* prove is what it measures: a frontier that **advanced**
 * is proof the fold gained a tag mid-walk, so the union is the documented smear
 * rather than a snapshot. Equal endpoints are necessary, not sufficient. Callers
 * are told so on each cell's `readBounded`, and [civictech.cell.BoundedStateful.supportsSince]
 * is left at its `false` default throughout this package, so the `since`
 * escalation path — which has the same limit — is refused on the caller's thread
 * rather than answered with a bound that was not honoured.
 *
 * ### Decision D — scalar riders ride every page
 *
 * Cell-level state that is not an entry (`MintedLedger`'s mint counter,
 * `PresenceLanes`' lane frontier) goes in [StatePage.attributes], the field
 * `SetCell` puts its tag `counter` in and the one `ShardCell` puts its
 * `interest`/`assignedEpoch` in. Attributes do not count against
 * [StateRead.limit], and they ride **every** page — a consumer that abandons a
 * walk after page 1, or joins it at page 4, still sees them. Like the frontier
 * they are exact on the first and last page and carry the opening value in
 * between, so their staleness is exactly [ReadCaveat.STALE_FRONTIER]'s and needs
 * no second caveat.
 *
 * ### Decision E — the content domain is exactly `snapshot()`'s
 *
 * The union of a walk equals **`snapshot()`'s content**: every sub-state,
 * nothing extra. In particular no derived structure a `restore()` rebuilds is
 * paged (`LookupJoinCell`'s `byDim` and publisher, `CombineLatestCell`'s
 * publisher, `KeyedBinarySetJoin`'s key indexes, `PresenceCountCell`'s
 * `counts`), and no live fold state `snapshot()` itself omits
 * (`TagState.deadSources`).
 */
object OperatorPaging {

    /**
     * [StatePage.attributes] key carrying `MintedLedger`'s mint counter
     * (V1C-OPS, Decision D) — `JoinSetCell` and `SemiJoinCell`. A `Long`.
     */
    const val MINT_COUNTER = "mintCounter"

    /**
     * [StatePage.attributes] key carrying `PresenceLanes`' open lane ids
     * (V1C-OPS, Decision D) — `QuorumSetCell` and `PresenceCountCell`. An
     * `ArrayList<UUID>`, and the `n` a quorum threshold reads.
     *
     * It rides as an attribute rather than as entries because a lane that
     * asserts no element is still in `snapshot()` and still counts towards `n`,
     * yet has no entry to carry it; and because the lane frontier is cell-level
     * state, not one entry's business.
     */
    const val LANES = "lanes"

    // Crude, deliberately, in SetCell's register: StateRead.byteBudget is
    // advisory and cell-estimated, and an estimate a cell cannot make it is free
    // to ignore. These are rough JVM object sizes, not an encoder's measurement
    // — in particular VALUE_BYTES is a constant for an app-supplied value of
    // unknown size, which is why an oversized GroupByCell accumulator overruns
    // the advisory budget rather than being detected by it (Decision G).
    internal const val ENTRY_OVERHEAD_BYTES = 64
    internal const val TAG_BYTES = 48
    internal const val EXCLUSIVE_ENTRY_BYTES = 64
    internal const val VALUE_BYTES = 64
}

// ---------------------------------------------------------------- entries

/**
 * One entry of a composite operator cell's paged state (V1C-OPS, Decision A):
 * always labelled with the sub-state it came from, so `(subState, key)` is the
 * entry's identity and the same key in two sub-states is two entries.
 *
 * The key/element/value slots are `Any?` rather than a type parameter because
 * one shared paging skeleton serves thirteen cells whose key spaces are
 * unrelated types; a consumer casts, exactly as it must for
 * `SetCell.SetStateEntry`'s `element`. A slot may hold an [ExclusiveEntry]
 * descriptor instead of the value — see [OperatorPaging]'s ownership section.
 */
sealed interface OperatorEntry : Serializable {
    /** The declaring cell's stable name for the sub-state this entry came from. */
    val subState: String
}

/**
 * A tagged-set sub-state's entry (V1C-OPS): an element and the live tags this
 * sub-state currently holds for it — the shape of a [TagState]'s `live` map, of
 * a `JoinLedger`'s advertisements, and of one `PresenceLanes` lane.
 *
 * @property lane the lane id for a nested sub-state (`PresenceLanes`, Decision
 *   F); null for a flat one.
 */
data class TaggedEntry(
    override val subState: String,
    val element: Any?,
    val tags: Set<Timestamp>,
    val lane: UUID? = null,
) : OperatorEntry

/**
 * A map-shaped sub-state's entry (V1C-OPS): `JoinCell`'s and
 * `CombineLatestCell`'s per-side latest values, `LookupJoinCell`'s facts and
 * dimensions, `MergeableGroupByCell`'s per-key accumulator.
 */
data class KeyedEntry(
    override val subState: String,
    val key: Any?,
    val value: Any?,
) : OperatorEntry

/**
 * `GroupByCell`'s per-group entry (V1C-OPS): the live-member [count] and the
 * aggregator's [accumulator], which is `snapshot()`'s `arrayListOf(count, acc)`
 * with the positions named.
 *
 * The accumulator rides **whole**, however large it is — see `GroupByCell`'s
 * `readBounded` for why (Decision G).
 */
data class GroupEntry(
    override val subState: String,
    val key: Any?,
    val count: Int,
    val accumulator: Any?,
) : OperatorEntry

// ------------------------------------------------------------- the skeleton

/**
 * One entry, already built and costed (V1C-OPS): [value] is what goes on the
 * page, [exclusive] says whether an `Owned`/`Leased` payload was elided out of
 * it, and [bytes] is the crude estimate [StateRead.byteBudget] is advised by.
 */
internal class PagedEntry(val value: Serializable, val exclusive: Boolean, val bytes: Int)

/**
 * One sub-state of a composite cell (V1C-OPS): its stable [name], how to
 * [freeze] its key sequence at walk start, and how to build the entry for one
 * frozen key — returning null for a key that has since left the fold, which the
 * walk skips.
 */
internal class SubState(
    val name: String,
    val freeze: () -> List<Any?>,
    val entry: (Any?) -> PagedEntry?,
)

/**
 * The walk cursor (V1C-OPS, Decision B): a lexicographic
 * `(subStateOrdinal, intraStateKey)` position, opaque to the kernel.
 *
 * [order] holds one frozen key sequence per sub-state, in the cell's declared
 * ordinal order; [subState] and [next] are the position within it. [opening] and
 * [attributes] are the walk's opening stamps, carried so an intermediate page
 * can report them without paying a second O(n) pass — see [OperatorPaging].
 */
internal class OperatorWalk(
    val order: List<List<Any?>>,
    val subState: Int,
    val next: Int,
    val opening: TagFrontier?,
    val attributes: Map<String, Serializable>,
) : Serializable

/**
 * Produce one page across [subStates] in declared order (V1C-OPS) — the whole
 * of [civictech.cell.BoundedStateful.readBounded] for every cell in this
 * package.
 *
 * Per page at most [StateRead.limit] frozen positions are examined and at most
 * [StateRead.limit] whole entries returned, so the work is O(limit) and never a
 * rescan. A page may be short — or empty — when frozen keys have vanished; only
 * `next == null` ends a walk. [frontier] and [attributes] are evaluated exactly
 * twice per walk, on the opening page and on the closing one.
 *
 * [StateRead.since] and [StateRead.scope] are not consulted: no cell in this
 * package declares [civictech.cell.BoundedStateful.supportsSince] or
 * [civictech.cell.BoundedStateful.supportsScope], so
 * [civictech.cell.host.ManagedHost.readState] has already refused any narrowing
 * request on the caller's thread and only `null`/`Interest.Total` — both meaning
 * "the whole state" — can reach here.
 */
internal fun pageOver(
    request: StateRead,
    subStates: List<SubState>,
    frontier: () -> TagFrontier? = { null },
    attributes: () -> Map<String, Serializable> = { emptyMap() },
): StatePage {
    val walk = (request.cursor?.token as? OperatorWalk)
        ?: OperatorWalk(subStates.map { it.freeze() }, 0, 0, frontier(), attributes())
    // a cursor is cell-minted and the kernel never interprets one, but nothing
    // stops a caller handing this cell another cell's token; refuse it by name
    // rather than indexing off the end of the frozen order
    require(walk.order.size == subStates.size) {
        "cursor spans ${walk.order.size} sub-states; this cell declares ${subStates.map { it.name }}"
    }

    val entries = ArrayList<Serializable>(minOf(request.limit, 64))
    var elided = 0
    var bytes = 0
    var sub = walk.subState
    var index = walk.next
    var examined = 0

    while (sub < subStates.size && examined < request.limit) {
        val keys = walk.order[sub]
        if (index >= keys.size) {
            // Decision B: the cursor names the ordinal, so exhausting sub-state
            // i continues at the head of i+1 instead of terminating the walk
            sub++
            index = 0
            continue
        }
        val key = keys[index]
        index++
        examined++
        val produced = subStates[sub].entry(key) ?: continue // vanished since the walk opened
        entries += produced.value
        if (produced.exclusive) elided++
        bytes += produced.bytes
        // advisory (StateRead.byteBudget): honoured only once the page already
        // carries an entry, so a walk always makes progress
        if (bytes >= request.byteBudget) break
    }

    // normalize past exhausted trailing sub-states so `complete` is exact and a
    // cursor never names a position one past the end of a non-final sub-state
    while (sub < subStates.size && index >= walk.order[sub].size) {
        sub++
        index = 0
    }

    val complete = sub >= subStates.size
    val opening = walk.subState == 0 && walk.next == 0
    val exact = complete && !opening
    return StatePage(
        entries = entries,
        next = if (complete) null else Cursor(OperatorWalk(walk.order, sub, index, walk.opening, walk.attributes)),
        frontier = if (exact) frontier() else walk.opening,
        exclusivesElided = elided,
        attributes = if (exact) attributes() else walk.attributes,
        caveats =
            if (walk.opening == null || complete || opening) emptySet()
            else setOf(ReadCaveat.STALE_FRONTIER),
    )
}

// ------------------------------------------------------------- entry builders

/**
 * A tagged-set entry, with the element elided to an [ExclusiveEntry] descriptor
 * when it is itself an `Owned`/`Leased` payload (V1C-OPS). The element *is* the
 * key here, so the descriptor's own `key` names the [lane] when there is one and
 * is null otherwise — see [ExclusiveEntry.key].
 */
internal fun taggedEntry(subState: String, element: Any?, tags: Set<Timestamp>, lane: UUID? = null): PagedEntry =
    if (ExclusiveEntry.isExclusive(element)) {
        PagedEntry(
            TaggedEntry(subState, ExclusiveEntry.of(key = lane, exclusive = element as Any), tags, lane),
            exclusive = true,
            bytes = OperatorPaging.ENTRY_OVERHEAD_BYTES + OperatorPaging.EXCLUSIVE_ENTRY_BYTES,
        )
    } else {
        PagedEntry(
            TaggedEntry(subState, element, tags, lane),
            exclusive = false,
            bytes = OperatorPaging.ENTRY_OVERHEAD_BYTES + OperatorPaging.TAG_BYTES * tags.size,
        )
    }

/**
 * A map-shaped entry, with the key and/or the value elided to an
 * [ExclusiveEntry] descriptor when either is an `Owned`/`Leased` payload
 * (V1C-OPS). An exclusive key is never used as another descriptor's `key`.
 */
internal fun keyedEntry(subState: String, key: Any?, value: Any?): PagedEntry {
    val keyExclusive = ExclusiveEntry.isExclusive(key)
    val valueExclusive = ExclusiveEntry.isExclusive(value)
    if (!keyExclusive && !valueExclusive) {
        return PagedEntry(KeyedEntry(subState, key, value), false, OperatorPaging.ENTRY_OVERHEAD_BYTES + OperatorPaging.VALUE_BYTES)
    }
    val pagedKey = if (keyExclusive) ExclusiveEntry.of(key = null, exclusive = key as Any) else key
    val pagedValue =
        if (valueExclusive) {
            ExclusiveEntry.of(key = if (keyExclusive) null else key as? Serializable, exclusive = value as Any)
        } else {
            value
        }
    return PagedEntry(KeyedEntry(subState, pagedKey, pagedValue), true, OperatorPaging.ENTRY_OVERHEAD_BYTES + OperatorPaging.EXCLUSIVE_ENTRY_BYTES)
}

/**
 * A group entry (V1C-OPS). Only the key can be exclusive: an accumulator is
 * bound `ACC : java.io.Serializable`, which `Owned`/`Leased` are not.
 */
internal fun groupEntry(subState: String, key: Any?, count: Int, accumulator: Any?): PagedEntry =
    if (ExclusiveEntry.isExclusive(key)) {
        PagedEntry(
            GroupEntry(subState, ExclusiveEntry.of(key = null, exclusive = key as Any), count, accumulator),
            exclusive = true,
            bytes = OperatorPaging.ENTRY_OVERHEAD_BYTES + OperatorPaging.EXCLUSIVE_ENTRY_BYTES,
        )
    } else {
        PagedEntry(GroupEntry(subState, key, count, accumulator), false, OperatorPaging.ENTRY_OVERHEAD_BYTES + OperatorPaging.VALUE_BYTES)
    }

// ------------------------------------------------------ sub-state declarations

/**
 * A map-shaped sub-state (V1C-OPS): `JoinCell`'s / `CombineLatestCell`'s per-side
 * latest values, `LookupJoinCell`'s facts and dimensions,
 * `MergeableGroupByCell`'s aggregates. Untagged — `MapDelta`'s documented
 * convergence limit (G-23) — so it contributes nothing to a frontier.
 */
internal fun <K, V> mapSubState(name: String, map: Map<K, V>): SubState =
    SubState(name, { ArrayList<Any?>(map.keys) }) { key ->
        @Suppress("UNCHECKED_CAST")
        val k = key as K
        if (map.containsKey(k)) keyedEntry(name, k, map[k]) else null
    }

/**
 * A [TagState]-backed sub-state (V1C-OPS): the live element → tag-set map that
 * is `TagState.snapshot()`'s content for every non-retaining ledger in this
 * package. `tags` hands back a copy, so a page never aliases the fold's own
 * mutable set.
 */
internal fun <E> tagSubState(name: String, state: TagState<E>): SubState =
    SubState(name, { ArrayList<Any?>(state.elements) }) { key ->
        @Suppress("UNCHECKED_CAST")
        val element = key as E
        if (element in state) taggedEntry(name, element, state.tags(element)) else null
    }

/**
 * A [JoinLedger]-backed sub-state (V1C-OPS, Decision H): the ledger pages
 * *through its owning cell*, which is what declares the sub-state ordinals, so
 * the ledger grew read accessors rather than a second paging interface.
 */
internal fun <X> ledgerSubState(name: String, ledger: JoinLedger<X>): SubState =
    SubState(name, { ArrayList<Any?>(ledger.advertisedKeys) }) { key ->
        @Suppress("UNCHECKED_CAST")
        val x = key as X
        // HashSet: page-owned, never an alias of the ledger's stored set
        ledger.tagsOf(x)?.let { taggedEntry(name, x, HashSet(it)) }
    }

/**
 * The nested `PresenceLanes` sub-state (V1C-OPS, Decision F): the recursive
 * application of Decision B's lexicographic rule, materialized as a frozen
 * sequence of `(laneId, element)` pairs. Every entry carries its
 * [TaggedEntry.lane], so the lane boundary — which is what makes a presence
 * count meaningful — survives paging.
 */
internal fun <E> laneSubState(name: String, lanes: PresenceLanes<E>): SubState =
    SubState(
        name,
        {
            val order = ArrayList<Any?>()
            for (lane in lanes.laneIds) for (element in lanes.laneElements(lane)) order += lane to element
            order
        },
    ) { key ->
        @Suppress("UNCHECKED_CAST")
        val position = key as Pair<UUID, E>
        val lane = position.first
        val element = position.second
        if (lanes.laneHolds(lane, element)) {
            taggedEntry(name, element, lanes.laneTags(lane, element), lane)
        } else {
            null
        }
    }

// ----------------------------------------------------------------- frontier

/** Accumulates a [TagFrontier] as a max per tag source (V1C-OPS). */
internal class FrontierBuilder {
    private val perSource = HashMap<UUID, Long>()

    fun add(tags: Iterable<Timestamp>): FrontierBuilder = apply {
        for (tag in tags) perSource.merge(tag.sourceId, tag.counter, ::maxOf)
    }

    fun build(): TagFrontier = TagFrontier(perSource)
}

/** Fold one [TagState]'s live tags into [builder] (V1C-OPS). */
internal fun <E> TagState<E>.contributeTo(builder: FrontierBuilder): FrontierBuilder =
    builder.apply { for (element in elements) add(tags(element)) }

/** Fold one ledger's advertised tags into [builder] (V1C-OPS). */
internal fun <X> JoinLedger<X>.contributeTo(builder: FrontierBuilder): FrontierBuilder =
    builder.apply { for (x in advertisedKeys) add(tagsOf(x) ?: emptySet()) }
