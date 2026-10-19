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
import civictech.cell.data.delta.TagState
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort

@CellBase
interface IntersectSetApi<E> {
    val left: Serve<Propagate<SetDelta<E>>>
    val right: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Binary intersection of two tagged set streams (n-ary by chaining): an
 * element is in the intersection iff it is live on both sides. On entry the
 * element is advertised downstream under **one freshly minted, cell-owned
 * output tag**; on exit exactly that tag is deleted, so downstream membership
 * tracks exactly. Tag churn while membership is unchanged is absorbed
 * (effective-only, 21) — the advertised tag never changes while membership
 * holds.
 *
 * ### Tag policy: minted, never borrowed (21 §Tag hygiene, computenet-vvre)
 *
 * This cell used to advertise the *union of both sides' observed input tags*
 * ([AdvertisedLedger]), justified by the premise that "downstream only ever
 * sees tags this cell later deletes itself". **That premise is false across a
 * reconvergent (diamond) path**, and it is false in two independent ways:
 *
 * - **A borrowed tag is not this cell's to delete.** In `union(A, intersect(A,
 *   B))` the element's tag from `A` reaches the [UnionSetCell] twice — once on
 *   the direct edge, once re-advertised by this cell — and the union correctly
 *   folds the two into ONE fact keyed by `(element, tag)`
 *   (`[24-OP-UNION-01]`, "duplicate deliveries of the same tag across a
 *   diamond fan-in are deduplicated"). When the element then left this
 *   intersection, the exit deleted `A`'s tag, and the union retracted the
 *   *direct* edge's still-live contribution with it: `A ∪ (A ∩ B)` lost an
 *   element that is live in `A`. The tags this cell deletes are only "its own"
 *   when no other path carries them; a diamond is exactly the shape where
 *   downstream cannot tell the two apart, and by construction should not have
 *   to.
 * - **Re-entry re-emits a deleted tag.** Intersection membership flips ON when
 *   the *other* side adds, so a flip-ON does not ride a fresh input add-tag on
 *   the flipping element: re-advertising the unchanged side's tag after an exit
 *   that deleted it violates 21's flat prohibition — "an emitter of tagged
 *   deltas never re-emits a tag it previously deleted" — and leaves the
 *   re-entry dead under a tombstone-folding consumer.
 *
 * Minting per entry ([MintedLedger]/`MintedTags`) removes both: the advertised
 * tag is unconfusable with any upstream's, so a diamond sees two independent
 * facts, and every re-entry carries a tag no consumer has tombstoned.
 * `[24-OP-INTERSECT-01]` is unchanged in substance — an entry tag is
 * advertised on entry and every advertised tag deleted on exit — only its
 * provenance moves from borrowed to minted, matching what every other join
 * operator in this family already does.
 *
 * Both spec chapters say the same thing: 21 §Tag hygiene (requirement list
 * item 4) names `intersect` among the operators that must mint, and 24
 * §"Required next steps in the family" classes it under *convergent
 * duplicates* — "agree on membership but mint distinct tags (intersect,
 * semijoin/antijoin, equi-join)". 21's stale parenthetical that once listed
 * `intersect` as pass-through-permitted was corrected in computenet-88hv.
 *
 * RS-5.3 note: unlike [JoinSetCell]/[SemiJoinCell], this is an identity join
 * (both sides share element type `E`, matching is direct membership — no key
 * projection), so it holds its own [TagState] pair directly rather than
 * through [KeyedBinarySetJoin]'s per-side key index, which this operator has
 * no use for. It shares both [JoinLedger] and, since computenet-vvre, its
 * [MintedLedger] policy.
 *
 * ### Lateness: the `waterline` inlet and the `lateLeft`/`lateRight` outlets (KE4.5)
 *
 * Constructed with a [Windows.Lateness] on either or both sides, the cell
 * tracks one event-time floor delivered on [waterline] and guards each
 * declaring inlet at **arrival**, before folding: an add whose
 * `timeFn(element)` is strictly below
 * the floor is excluded from the fold and leaves verbatim — tags preserved —
 * on that side's late outlet ([lateLeft]/[lateRight]), counted in
 * [droppedBelowFloorLeft]/[droppedBelowFloorRight] whether or not the outlet
 * is linked (`[24-WL-07]`). Dels are never filtered by time: the tag fold's
 * liveness check is the guard (`[24-WL-08]`). A side declaring no lateness is
 * never guarded and never evicted.
 *
 * The three ports are registered on this class, always present and unlinked
 * by default, and deliberately **not** on [IntersectSetApi] (as
 * [JoinSetCell]'s): a cell reached through the Api, or constructed without
 * lateness, is exactly the pre-lateness operator (`[24-WL-11]`): its
 * snapshot keeps the three-part shape, and a `WaterlineDelta` on it is a
 * structural error and throws.
 *
 * **Eviction on a floor rise (`[24-WL-06]`/`[24-WL-16]`).** Unlike
 * [JoinSetCell]/[SemiJoinCell], this is an identity join with no key index
 * (RS-5.3): the unit is one **element** of a declaring side's own
 * [TagState], evicted exactly when that side's `timeFn(element) < floor` —
 * the late-drop threshold, so an evicted element can be neither re-admitted
 * nor retracted (`[24-WL-09]` in row form; see [WaterlineEviction]). The
 * killed elements' dels run through the same membership-then-reconcile fold
 * [onLeft]/[onRight] use, so every minted entry whose support on either side
 * left exits **with its minted tag** (`[24-OP-INTERSECT-01]`'s exit rule,
 * unchanged in substance) — a tombstone-folding consumer drops it, and it is
 * an ordinary exit of a minted tag, never a borrowed one (the diamond
 * hazard this class's tag-policy section describes does not apply to an
 * eviction exit). The element is its own unit on each side: an element below
 * the floor on the left leaves only [leftState] (its right-side copy, if any,
 * is a separate element identity-wise and is untouched unless the right side
 * also declares lateness and its own copy has passed). Both sides' eviction
 * leaves as one `SetDelta` under the waterline delivery's wave; eviction
 * never forwards on the late outlets.
 *
 * **Per-element exclusive refusal (`[24-WL-17]`).** A passed element that is
 * itself `Owned`/`Leased` is left live, untouched and undischarged, recorded
 * as an [ExclusiveEvictionRefused] (unit `"row"`) in [refusedRows] (replaced
 * on every rise) and counted in [refusedEvictions]; every other passed
 * element still evicts in the same delta, and every later rise re-evaluates
 * it. Only a top-level exclusive element is detected (computenet-woto).
 *
 * **Single-instance only (`[24-WL-18]`)**, checked in [WaterlineEviction.evict].
 */
class IntersectSetCell<E>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    /** The left inlet's lateness declaration (`[24-WL-01]`); `null` = never guarded, never evicted (`[24-WL-16]`). */
    private val leftLateness: Windows.Lateness<E>? = null,
    /** The right inlet's lateness declaration; `null` = never guarded, never evicted. */
    private val rightLateness: Windows.Lateness<E>? = null,
) :
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
    IntersectSetCellBase<E>(ref), Stateful, BoundedStateful {
    private val leftState = TagState<E>()
    private val rightState = TagState<E>()
    // minted, not advertised — see the tag-policy section on this class's KDoc
    private val ledger: JoinLedger<E> = MintedLedger(ref, "intersect")

    /** The event-time floor (`[24-WL-02]`); `null` is the identity — nothing is below it. */
    private var floor: Long? = null

    /** The current waterline floor, or `null` before any raising [WaterlineDelta] (or without lateness). */
    fun floor(): Long? = floor

    /** Left adds excluded by the late-drop guard, per dropped element (`[24-WL-07]`); moves even when [lateLeft] is unlinked. A counter, not state. */
    var droppedBelowFloorLeft: Long = 0
        private set

    /** Right adds excluded by the late-drop guard, per dropped element; the mirror of [droppedBelowFloorLeft]. */
    var droppedBelowFloorRight: Long = 0
        private set

    private var refused: List<ExclusiveEvictionRefused> = emptyList()

    /** The passed elements refused eviction on the latest rise because they are exclusive (`[24-WL-17]`). Not snapshotted. */
    fun refusedRows(): List<ExclusiveEvictionRefused> = refused

    /** Cumulative count of per-element eviction refusals, one per refused element per rise. A counter, not state. */
    var refusedEvictions: Long = 0
        private set

    /** The floor's inlet (`[24-WL-03]`): a value read here, never re-emitted (`[24-WL-04]`). */
    val waterline = registerPort("waterline", FanInlet.create<Propagate<WaterlineDelta>>())

    /** Sub-floor left adds, forwarded verbatim under the delivery that carried them (`[24-WL-07]`). */
    val lateLeft = registerPort("lateLeft", FanOutlet.create<Propagate<SetDelta<E>>>())

    /** Sub-floor right adds, forwarded verbatim under the delivery that carried them (`[24-WL-07]`). */
    val lateRight = registerPort("lateRight", FanOutlet.create<Propagate<SetDelta<E>>>())

    init {
        // late-join catch-up (G-22): the advertised intersection as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
        waterline.serve(object : Propagate<WaterlineDelta> {
            override fun propagate(value: WaterlineDelta) = onWaterline(value)
        })
    }

    override fun onLeft(value: SetDelta<E>) {
        // [24-WL-07] guard at arrival (3vd7k-D4)
        val admitted = guardLeft(value)
        val adds = mutableMapOf<E, Set<Timestamp>>()
        val dels = mutableMapOf<E, Set<Timestamp>>()
        foldEffective(leftState.apply(admitted), adds, dels)
        if (adds.isNotEmpty() || dels.isNotEmpty()) outlet.call.propagate(SetDelta(adds, dels))
    }

    override fun onRight(value: SetDelta<E>) {
        val admitted = guardRight(value)
        val adds = mutableMapOf<E, Set<Timestamp>>()
        val dels = mutableMapOf<E, Set<Timestamp>>()
        foldEffective(rightState.apply(admitted), adds, dels)
        if (adds.isNotEmpty() || dels.isNotEmpty()) outlet.call.propagate(SetDelta(adds, dels))
    }

    /**
     * The ungated per-element fold of an already-applied delta: for each
     * touched element, check membership on both sides and enter/exit the
     * minted ledger. Shared by [onLeft]/[onRight] and eviction
     * ([onFloorRaised]), so an eviction is an ordinary retraction — the
     * element leaves the touched side's [TagState] and its minted entry (if
     * any) exits with its tag.
     */
    private fun foldEffective(
        effective: SetDelta<E>,
        adds: MutableMap<E, Set<Timestamp>>,
        dels: MutableMap<E, Set<Timestamp>>,
    ) {
        (effective.adds.keys + effective.dels.keys).forEach { element -> reconcile(element, adds, dels) }
    }

    private fun reconcile(element: E, adds: MutableMap<E, Set<Timestamp>>, dels: MutableMap<E, Set<Timestamp>>) {
        val isIn = element in leftState && element in rightState
        if (isIn) {
            // [MintedLedger] mints its own tag and ignores the supplier —
            // the input tags are deliberately NOT borrowed (see class KDoc)
            ledger.enter(element) { emptySet() }?.let { adds[element] = it }
        } else {
            ledger.exit(element)?.let { dels[element] = it }
        }
    }

    // ---- lateness (KE4.5): arrival guard, waterline, eviction ----

    /**
     * `[24-WL-07]` on the left inlet: forward the sub-floor adds on [lateLeft]
     * and return the admitted remainder. Without a left
     * declaration or a floor this is the identity.
     */
    private fun guardLeft(value: SetDelta<E>): SetDelta<E> {
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
    private fun guardRight(value: SetDelta<E>): SetDelta<E> {
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
            "IntersectSetCell $ref: waterline linked on a cell constructed without lateness"
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
     * Evict every passed, non-exclusive element of each declaring side
     * (`timeFn(element) < newFloor`) through [foldEffective], both sides into
     * one accumulator, emitted once under the current (waterline) delivery;
     * record the exclusive elements as refused (`[24-WL-16]`, `[24-WL-17]`).
     * Not gated (3vd7k-D9); never forwards on the late outlets.
     */
    private fun onFloorRaised(newFloor: Long) {
        val adds = mutableMapOf<E, Set<Timestamp>>()
        val dels = mutableMapOf<E, Set<Timestamp>>()
        val refusedNow = mutableListOf<ExclusiveEvictionRefused>()
        leftLateness?.let { lateness ->
            val (evictees, refusedElements) = WaterlineEviction.passedRows(leftState, lateness, newFloor)
            refusedElements.mapTo(refusedNow) { ExclusiveEvictionRefused(ref, it, 1, unit = "row") }
            val killed = WaterlineEviction.evict(this, leftState) { it in evictees }
            foldEffective(killed, adds, dels)
        }
        rightLateness?.let { lateness ->
            val (evictees, refusedElements) = WaterlineEviction.passedRows(rightState, lateness, newFloor)
            refusedElements.mapTo(refusedNow) { ExclusiveEvictionRefused(ref, it, 1, unit = "row") }
            val killed = WaterlineEviction.evict(this, rightState) { it in evictees }
            foldEffective(killed, adds, dels)
        }
        refusedEvictions += refusedNow.size
        refused = refusedNow
        if (adds.isNotEmpty() || dels.isNotEmpty()) outlet.call.propagate(SetDelta(adds, dels))
    }

    // A lateness-declaring cell appends its floor as a fourth element (3vd7k-D7,
    // nt17o-D4's pattern) so a recovered cell keeps late-dropping; a cell
    // without lateness keeps the three-element form byte-for-byte ([24-WL-11]).
    override fun snapshot(): Serializable =
        arrayListOf<Serializable?>(leftState.snapshot(), rightState.snapshot(), ledger.snapshot())
            .apply { if (leftLateness != null || rightLateness != null) add(floor) }

    /** Accepts the three-element form (no floor: `floor()` stays `null`) and the four-element form. */
    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val parts = state as List<Serializable?>
        require(parts.size == 3 || parts.size == 4) { "IntersectSetCell snapshot has ${parts.size} parts, want 3 or 4" }
        leftState.restore(parts[0] as Serializable)
        rightState.restore(parts[1] as Serializable)
        ledger.restore(parts[2] as Serializable)
        floor = if (parts.size == 4) parts[3] as Long? else null
        refused = emptyList()
    }

    /**
     * One page of this intersection's three sub-states (V1C-OPS) — **the
     * Decision A case**.
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"left"` | `E` | [TaggedEntry] — the left side's live tags |
     * | 1 | `"right"` | `E` | [TaggedEntry] — the right side's live tags |
     * | 2 | `"ledger"` | `E` | [TaggedEntry] — the minted tag advertised downstream |
     *
     * Same order as [snapshot]'s
     * `arrayListOf(leftState, rightState, ledger)`. **All three are keyed by the
     * same `E`, and an element of the intersection is live in all three at
     * once**, each with a *different* tag set — the left side's observed tags,
     * the right side's, and the single cell-owned tag minted on entry. A cursor naming only
     * "the last element `e`" could not say which of the three it had reached, so
     * a resume would either re-emit `e` from a sub-state already walked or skip
     * a whole sub-state. Here the same `e` is **three distinct entries**,
     * `("left", e)`, `("right", e)` and `("ledger", e)`, and the cursor is
     * lexicographic `(subStateOrdinal, element)` over three frozen key sequences
     * ([OperatorPaging], Decisions A and B). Deduplicating across the three
     * would be wrong: their tag sets differ, and a consumer that cannot tell
     * them apart cannot reconstruct this cell.
     *
     * [StatePage.frontier] is the max per-source counter over all three
     * sub-states' tags, exact on the first and last page of a walk. Its equality
     * across a walk is **necessary but not sufficient** for "the union is a
     * snapshot": neither `TagState` retains tombstones, so a mid-walk exit
     * *removes* tags rather than minting one and can even lower the stamp.
     * [supportsSince] stays `false` accordingly. (`MintedLedger`'s own
     * monotone mint counter rides the page attributes — `OperatorPaging`'s
     * `mintCounter` — as it does for every other minting operator; it does not
     * make the frontier sufficient, because the two `TagState`s can still
     * lower it.)
     *
     * `[24-OP-INTERSECT-01]` is untouched: this method only reads, emits
     * nothing, and reaches no output path.
     */
    override fun readBounded(request: StateRead): StatePage = pageOver(
        request,
        listOf(
            tagSubState("left", leftState),
            tagSubState("right", rightState),
            ledgerSubState("ledger", ledger),
        ),
        frontier = {
            val builder = FrontierBuilder()
            leftState.contributeTo(builder)
            rightState.contributeTo(builder)
            ledger.contributeTo(builder)
            builder.build()
        },
        attributes = { ledger.readerAttributes() },
    )
}
