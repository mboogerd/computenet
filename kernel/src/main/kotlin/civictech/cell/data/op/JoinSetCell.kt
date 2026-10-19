package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.Windows
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort

@CellBase
interface JoinSetApi<A, B, C> {
    val left: Serve<Propagate<SetDelta<A>>>
    val right: Serve<Propagate<SetDelta<B>>>
    val outlet: Subscribe<Propagate<SetDelta<C>>>
}

/**
 * Incremental relational equi-join over tagged set streams (M11.5): a pair
 * `(a, b)` is live iff both rows are live and their keys match; each live
 * pair carries one minted tag (`MintedTags` — pairs re-enter when a removed
 * row returns, so input tags can't be borrowed), emitted under
 * `combine(a, b)`. Many-to-one `combine` collapses correctly: the output
 * element holds one tag per contributing pair and stays live until the last
 * pair dies (deleting the whole element on one pair's exit is the divergent
 * naive form, control-tested). Many-to-many keys yield all pairs.
 *
 * `combine` outputs must be `@Serializable` app types if they cross the wire
 * (`Pair` is not WireCodec-registered). This is the relational join over
 * convergent set streams; `JoinCell` remains the LWW dictionary join over
 * single-writer map streams.
 *
 * ### Lateness: the `waterline` inlet and the `lateLeft`/`lateRight` outlets (KE4.5)
 *
 * Constructed with a [Windows.Lateness] on either or both sides, the cell
 * tracks one event-time floor delivered on [waterline] and guards each
 * declaring inlet at **arrival**, before folding: an add whose `timeFn(row)` is strictly below the
 * floor is excluded from the fold and leaves verbatim — tags preserved — on
 * that side's late outlet ([lateLeft]/[lateRight]), counted in
 * [droppedBelowFloorLeft]/[droppedBelowFloorRight] whether or not the outlet is
 * linked (`[24-WL-07]`). Dels are never filtered by time: the tag fold's
 * liveness check is the guard (`[24-WL-08]`). An inlet declaring no lateness
 * is never guarded and never evicted.
 *
 * The three ports are registered on this class, always present and unlinked
 * by default, and deliberately **not** on [JoinSetApi] (as [GroupByCell]'s):
 * the Api is consumed as a `TypedRef` elsewhere (demo/skillmatch,
 * demo/dialogue), so a cell reached through it — or constructed without
 * lateness — is exactly the pre-lateness operator (`[24-WL-11]`): its
 * snapshot keeps the three-part shape, and a `WaterlineDelta` on it is a
 * structural error and throws.
 *
 * **Eviction on a floor rise (`[24-WL-06]`/`[24-WL-16]`).** The unit is one
 * input **row** of a declaring side, evicted exactly when that side's
 * `timeFn(row) < floor` — the late-drop threshold, so an evicted row can be
 * neither re-admitted nor retracted (`[24-WL-09]` in row form; see
 * [WaterlineEviction]). A windowed join's window is therefore evicted in
 * part when the floor falls inside it. The killed rows' dels run through the
 * same index-then-reconcile fold [onLeft]/[onRight] use, so they leave the key
 * index too and every minted pair on them exits **with its minted tag**
 * (`[KE4-25]`/`[KE4-32]`) — a tombstone-folding consumer drops it. Pairs are
 * never a unit of their own; a later del of an evicted row is a no-op, so no
 * pair exits twice. Both sides' eviction leaves as one `SetDelta` under the
 * waterline delivery's wave; eviction never forwards on the late outlets.
 *
 * **Per-row exclusive refusal (`[24-WL-17]`).** A passed row that is itself
 * `Owned`/`Leased` is left live, untouched and undischarged, recorded as an
 * [ExclusiveEvictionRefused] (unit `"row"`) in [refusedRows] (replaced on every
 * rise)
 * and counted in [refusedEvictions]; every other passed row still
 * evicts in the same delta, and every later rise re-evaluates it. Only a
 * top-level exclusive row is detected (computenet-woto).
 *
 * **Single-instance only (`[24-WL-18]`)**, checked in [WaterlineEviction.evict].
 */
class JoinSetCell<A, B, K, C>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    /** The left inlet's lateness declaration (`[24-WL-01]`); `null` = never guarded, never evicted (`[24-WL-16]`). */
    private val leftLateness: Windows.Lateness<A>? = null,
    /** The right inlet's lateness declaration; `null` = never guarded, never evicted. */
    private val rightLateness: Windows.Lateness<B>? = null,
    private val combine: (A, B) -> C,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : JoinSetCellBase<A, B, C>(ref), Stateful, BoundedStateful {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<Pair<A, B>> = MintedLedger(ref, "join")

    /** The event-time floor (`[24-WL-02]`); `null` is the identity — nothing is below it. */
    private var floor: Long? = null

    /** The current waterline floor, or `null` before any raising [WaterlineDelta] (or without lateness). */
    fun floor(): Long? = floor

    /** Left adds excluded by the late-drop guard, per dropped row (`[24-WL-07]`); moves even when [lateLeft] is unlinked. A counter, not state. */
    var droppedBelowFloorLeft: Long = 0
        private set

    /** Right adds excluded by the late-drop guard, per dropped row; the mirror of [droppedBelowFloorLeft]. */
    var droppedBelowFloorRight: Long = 0
        private set

    private var refused: List<ExclusiveEvictionRefused> = emptyList()

    /** The passed rows refused eviction on the latest rise because they are exclusive (`[24-WL-17]`). Not snapshotted. */
    fun refusedRows(): List<ExclusiveEvictionRefused> = refused

    /** Cumulative count of per-row eviction refusals, one per refused row per rise. A counter, not state. */
    var refusedEvictions: Long = 0
        private set

    /** The floor's inlet (`[24-WL-03]`): a value read here, never re-emitted (`[24-WL-04]`). */
    val waterline = registerPort("waterline", FanInlet.create<Propagate<WaterlineDelta>>())

    /** Sub-floor left adds, forwarded verbatim under the delivery that carried them (`[24-WL-07]`). */
    val lateLeft = registerPort("lateLeft", FanOutlet.create<Propagate<SetDelta<A>>>())

    /** Sub-floor right adds, forwarded verbatim under the delivery that carried them (`[24-WL-07]`). */
    val lateRight = registerPort("lateRight", FanOutlet.create<Propagate<SetDelta<B>>>())

    init {
        // late-join catch-up (G-22): advertised pairs folded under combine
        outlet.catchUpOnLinked {
            if (ledger.isEmpty) null
            else {
                val adds = mutableMapOf<C, MutableSet<Timestamp>>()
                ledger.entries.forEach { (pair, tags) ->
                    adds.getOrPut(combine(pair.first, pair.second)) { mutableSetOf() } += tags
                }
                SetDelta(adds = adds)
            }
        }
        waterline.serve(object : Propagate<WaterlineDelta> {
            override fun propagate(value: WaterlineDelta) = onWaterline(value)
        })
    }

    override fun onLeft(value: SetDelta<A>) {
        // [24-WL-07] guard at arrival (3vd7k-D4)
        val admitted = guardLeft(value)
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        foldLeft(join.leftState.apply(admitted), adds, dels)
        join.emitIfNonEmpty(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
        )
    }

    override fun onRight(value: SetDelta<B>) {
        val admitted = guardRight(value)
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        foldRight(join.rightState.apply(admitted), adds, dels)
        join.emitIfNonEmpty(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
        )
    }

    /**
     * The ungated per-row fold of an already-applied left delta: index each
     * touched row, then reconcile it against every right row under its key.
     * Shared by [onLeft] and eviction ([onFloorRaised]), so an eviction is an
     * ordinary retraction — the row leaves [KeyedBinarySetJoin.leftIndex] and
     * each of its pairs exits with its minted tag.
     */
    private fun foldLeft(
        effective: SetDelta<A>,
        adds: MutableMap<C, MutableSet<Timestamp>>,
        dels: MutableMap<C, MutableSet<Timestamp>>,
    ) {
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            val k = leftKey(a)
            join.index(join.leftIndex, k, a, live = a in join.leftState)
            join.rightIndex[k]?.forEach { b -> reconcile(a, b, adds, dels) }
        }
    }

    /** The mirror image of [foldLeft]. */
    private fun foldRight(
        effective: SetDelta<B>,
        adds: MutableMap<C, MutableSet<Timestamp>>,
        dels: MutableMap<C, MutableSet<Timestamp>>,
    ) {
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            join.leftIndex[k]?.forEach { a -> reconcile(a, b, adds, dels) }
        }
    }

    // ---- lateness (KE4.5): arrival guard, waterline, eviction ----

    /**
     * `[24-WL-07]` on the left inlet: forward the sub-floor adds on [lateLeft]
     * and return the admitted remainder. Without a left
     * declaration or a floor this is the identity.
     */
    private fun guardLeft(value: SetDelta<A>): SetDelta<A> {
        val lateness = leftLateness
        val current = floor
        if (lateness == null || current == null) return value
        val (admitted, dropped) = WaterlineEviction.lateSplit(lateness, current, value)
        if (dropped.isNotEmpty()) {
            droppedBelowFloorLeft += dropped.size
            lateLeft.call.propagate(SetDelta(adds = dropped))
        }
        return admitted
    }

    /** The mirror image of [guardLeft] on the right inlet and [lateRight]. */
    private fun guardRight(value: SetDelta<B>): SetDelta<B> {
        val lateness = rightLateness
        val current = floor
        if (lateness == null || current == null) return value
        val (admitted, dropped) = WaterlineEviction.lateSplit(lateness, current, value)
        if (dropped.isNotEmpty()) {
            droppedBelowFloorRight += dropped.size
            lateRight.call.propagate(SetDelta(adds = dropped))
        }
        return admitted
    }

    private fun onWaterline(delta: WaterlineDelta) {
        checkNotNull(leftLateness ?: rightLateness) {
            "JoinSetCell $ref: waterline linked on a cell constructed without lateness"
        }
        val current = floor
        if (current != null && delta.floor <= current) {
            // [24-WL-03] fixpoint: a non-raising floor changes nothing
            return
        }
        floor = delta.floor
        onFloorRaised(delta.floor)
    }

    /**
     * Evict every passed, non-exclusive row of each declaring side
     * (`timeFn(row) < newFloor`) through [foldLeft]/[foldRight], both sides into
     * one accumulator, emitted once under the current (waterline) delivery;
     * record the exclusive rows as refused (`[24-WL-16]`, `[24-WL-17]`). Not
     * gated (3vd7k-D9); never forwards on the late outlets.
     */
    private fun onFloorRaised(newFloor: Long) {
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        val refusedNow = mutableListOf<ExclusiveEvictionRefused>()
        leftLateness?.let { lateness ->
            val (evictees, refusedRows) = WaterlineEviction.passedRows(join.leftState, lateness, newFloor)
            refusedRows.mapTo(refusedNow) { ExclusiveEvictionRefused(ref, it, 1, unit = "row") }
            val killed = WaterlineEviction.evict(this, join.leftState) { it in evictees }
            foldLeft(killed, adds, dels)
        }
        rightLateness?.let { lateness ->
            val (evictees, refusedRows) = WaterlineEviction.passedRows(join.rightState, lateness, newFloor)
            refusedRows.mapTo(refusedNow) { ExclusiveEvictionRefused(ref, it, 1, unit = "row") }
            val killed = WaterlineEviction.evict(this, join.rightState) { it in evictees }
            foldRight(killed, adds, dels)
        }
        refusedEvictions += refusedNow.size
        refused = refusedNow
        join.emitIfNonEmpty(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
        )
    }

    private fun reconcile(
        a: A,
        b: B,
        adds: MutableMap<C, MutableSet<Timestamp>>,
        dels: MutableMap<C, MutableSet<Timestamp>>,
    ) {
        val wanted = a in join.leftState && b in join.rightState // keys match by index construction
        if (wanted) {
            ledger.enter(a to b) { emptySet() }?.let { adds.getOrPut(combine(a, b)) { mutableSetOf() } += it }
        } else {
            ledger.exit(a to b)?.let { dels.getOrPut(combine(a, b)) { mutableSetOf() } += it }
        }
    }

    // A lateness-declaring cell appends its floor as a fourth element (3vd7k-D7,
    // nt17o-D4's pattern) so a recovered cell keeps late-dropping; a cell
    // without lateness keeps the three-element form byte-for-byte ([24-WL-11]).
    override fun snapshot(): Serializable =
        arrayListOf<Serializable?>(join.leftState.snapshot(), join.rightState.snapshot(), ledger.snapshot())
            .apply { if (leftLateness != null || rightLateness != null) add(floor) }

    /** Accepts the three-element form (no floor: `floor()` stays `null`) and the four-element form. */
    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val parts = state as List<Serializable?>
        require(parts.size == 3 || parts.size == 4) { "JoinSetCell snapshot has ${parts.size} parts, want 3 or 4" }
        join.leftState.restore(parts[0] as Serializable)
        join.rightState.restore(parts[1] as Serializable)
        ledger.restore(parts[2] as Serializable)
        floor = if (parts.size == 4) parts[3] as Long? else null
        refused = emptyList()
        join.rebuildIndexes(leftKey, rightKey)
    }

    /**
     * One page of this equi-join's three sub-states (V1C-OPS).
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"left"` | `A` | [TaggedEntry] — the left rows' live tags |
     * | 1 | `"right"` | `B` | [TaggedEntry] — the right rows' live tags |
     * | 2 | `"ledger"` | `Pair<A, B>` | [TaggedEntry] — the pair's one minted tag |
     *
     * Same order as [snapshot]'s
     * `arrayListOf(leftState, rightState, ledger)`. Three *different* key
     * spaces, the third a pair of the other two, so the cursor is lexicographic
     * `(subStateOrdinal, key)` over three frozen key sequences and a resume that
     * exhausts one sub-state continues at the head of the next
     * ([OperatorPaging], Decision B). Note the ledger is keyed by the **pair**,
     * not by the combined output `C`: `combine` is many-to-one, so `C` would
     * collapse distinct pairs, and `snapshot()` stores the pairs.
     *
     * **Decision D — the mint counter rides every page.** [StatePage.attributes]
     * carries [OperatorPaging.MINT_COUNTER], the counter behind
     * `MintedTags`' fresh-tag-per-entry discipline (tag hygiene, 21): it is
     * genuinely state — a restored instance must not re-mint a spent tag — and
     * it is `MintedLedger.snapshot()`'s second element, so a walk whose union is
     * to equal [snapshot]'s content has to carry it. It is an attribute rather
     * than an entry because it is cell-level, which also means it does not count
     * against [StateRead.limit], and it rides *every* page so a caller who joins
     * a walk at page 4 or abandons it after page 1 still sees it. Like the
     * frontier it is exact on the first and last page and carries the opening
     * value in between; a stale one is impossible without the frontier also
     * advancing, since minting a tag is what puts it in the ledger.
     *
     * The **key indexes** are not paged: `KeyedBinarySetJoin.rebuildIndexes`
     * derives them from the two tag states on [restore] and they are not in
     * [snapshot] (Decision E).
     *
     * [StatePage.frontier] covers all three sub-states' tags, exact on the first
     * and last page. Its equality across a walk is **necessary but not
     * sufficient** for "the union is a snapshot": neither `TagState` retains
     * tombstones, and `MintedLedger.exit` *removes* the minted tag rather than
     * tombstoning it, so a pair leaving the join mid-walk mints nothing.
     * [supportsSince] stays `false` accordingly.
     *
     * `[24-OP-JOINSET-01]`/`-02` are untouched: this method only reads.
     */
    override fun readBounded(request: StateRead): StatePage = pageOver(
        request,
        listOf(
            tagSubState("left", join.leftState),
            tagSubState("right", join.rightState),
            ledgerSubState("ledger", ledger),
        ),
        frontier = {
            val builder = FrontierBuilder()
            join.leftState.contributeTo(builder)
            join.rightState.contributeTo(builder)
            ledger.contributeTo(builder)
            builder.build()
        },
        attributes = { ledger.readerAttributes() },
    )
}

/** Equi-join to pairs — the default combine. */
fun <A, B, K> joinSet(
    leftKey: (A) -> K,
    rightKey: (B) -> K,
    ref: CellRef = CellRef(UUID.randomUUID()),
): JoinSetCell<A, B, K, Pair<A, B>> = JoinSetCell(ref, leftKey, rightKey, combine = { a, b -> a to b })

/** Cross product: the equi-join on the unit key. */
fun <A, B> crossProduct(ref: CellRef = CellRef(UUID.randomUUID())): JoinSetCell<A, B, Unit, Pair<A, B>> =
    JoinSetCell(ref, leftKey = { }, rightKey = { }, combine = { a, b -> a to b })
