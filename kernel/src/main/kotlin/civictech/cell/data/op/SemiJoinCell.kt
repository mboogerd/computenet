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
interface SemiJoinApi<A, B> {
    val left: Serve<Propagate<SetDelta<A>>>
    val right: Serve<Propagate<SetDelta<B>>>
    val outlet: Subscribe<Propagate<SetDelta<A>>>
}

/**
 * Incremental semijoin/antijoin over tagged set streams (M11.2): a left row is
 * in the output iff it is live and its key's presence among live right rows
 * matches the polarity — `negated = false` keeps matched rows (A ⋉ B),
 * `negated = true` keeps unmatched rows (A ▷ B; with identity keys that is
 * set difference).
 *
 * Non-monotone: a row can (re-)enter when the *right* side removes, with no
 * fresh left tag to ride — so output tags are minted per entry (`MintedTags`,
 * tag hygiene, 21), never borrowed from the inputs. Output membership at idle
 * is a deterministic function of the converged input memberships (add-wins on
 * both sides); duplicates converge on membership, not on tags. Not
 * glitch-free — opposing in-flight updates may flicker transiently (22's
 * wrapper is the remedy).
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
 * is never guarded and never evicted. Every delivery on either inlet (and on
 * [waterline]) emits on or absorb-acks **both** late outlets, so a consumer
 * linked to one of them sees every wave the cell sees and its frontier
 * advances.
 *
 * The three ports are registered on this class, always present and unlinked
 * by default, and deliberately **not** on [SemiJoinApi] (as [JoinSetCell]'s):
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
 * index too and every advertised row on them exits **with its minted tag**;
 * a later del of an evicted row is a no-op, so no row exits twice.
 *
 * **The antijoin's transient admit is normative, not a bug (`[24-WL-06]`,
 * `[24-WL-16]`, the acknowledged design cost).** With `negated = true`,
 * [reconcile] already admits a left row when `leftKey(a) in rightIndex` flips
 * from `true` to `false` — that is exactly what evicting the right side's last
 * matching row does, so a left row whose window is still open **enters** the
 * output, with a freshly minted tag, in the same delta that evicts its former
 * match. Row-granular eviction has no `keyTime` on the join key or the rows
 * to defer that admit to the window's own close, so a still-open left row can
 * transiently read as "no match" the moment its match ages out — and an
 * outer-join composition built on this antijoin (`leftJoin` etc.) would show a
 * transient `(L, null)` for the same reason. Whole-window eviction would avoid
 * it but needs a `keyTime` the join family does not have today; revisiting the
 * eviction unit would also revise `[24-WL-06]`/`[24-WL-10]`/`[24-WL-16]`, a
 * spec fork out of scope here (3vd7k-D6, DESIGN COST comment of 2026-09-26 on
 * computenet-3vd7k).
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
class SemiJoinCell<A, B, K>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    private val negated: Boolean = false,
    /** The left inlet's lateness declaration (`[24-WL-01]`); `null` = never guarded, never evicted (`[24-WL-16]`). */
    private val leftLateness: Windows.Lateness<A>? = null,
    /** The right inlet's lateness declaration; `null` = never guarded, never evicted. */
    private val rightLateness: Windows.Lateness<B>? = null,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : SemiJoinCellBase<A, B>(ref), Stateful, BoundedStateful {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<A> = MintedLedger(ref, "semijoin")

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
        // late-join catch-up (G-22): the advertised output as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
        waterline.serve(object : Propagate<WaterlineDelta> {
            override fun propagate(value: WaterlineDelta) = onWaterline(value)
        })
    }

    override fun onLeft(value: SetDelta<A>) {
        // [24-WL-07] guard at arrival (3vd7k-D4)
        val admitted = guardLeft(value)
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        foldLeft(join.leftState.apply(admitted), adds, dels)
        join.emitIfNonEmpty(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
        )
    }

    override fun onRight(value: SetDelta<B>) {
        val admitted = guardRight(value)
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        foldRight(join.rightState.apply(admitted), adds, dels)
        join.emitIfNonEmpty(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
        )
    }

    /**
     * The ungated per-row fold of an already-applied left delta: index each
     * touched row, then reconcile it against the right index. Shared by
     * [onLeft] and eviction ([onFloorRaised]), so an eviction is an ordinary
     * retraction — the row leaves [KeyedBinarySetJoin.leftIndex] and its
     * advertised entry exits with its minted tag (or, negated, a still-live
     * left row now missing a match enters with a fresh one).
     */
    private fun foldLeft(
        effective: SetDelta<A>,
        adds: MutableMap<A, Set<Timestamp>>,
        dels: MutableMap<A, Set<Timestamp>>,
    ) {
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            join.index(join.leftIndex, leftKey(a), a, live = a in join.leftState)
            reconcile(a, adds, dels)
        }
    }

    /** The mirror image of [foldLeft]. */
    private fun foldRight(
        effective: SetDelta<B>,
        adds: MutableMap<A, Set<Timestamp>>,
        dels: MutableMap<A, Set<Timestamp>>,
    ) {
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            // key presence may have flipped: reconcile is idempotent,
            // so visiting unflipped keys' rows is just a no-op
            join.leftIndex[k]?.forEach { a -> reconcile(a, adds, dels) }
        }
    }

    // ---- lateness (KE4.5): arrival guard, waterline, eviction ----

    /**
     * `[24-WL-07]` on the left inlet: forward the sub-floor adds on [lateLeft]
     * (else absorb-ack it) and return the admitted remainder. Without a left
     * declaration or a floor this is the identity and [lateLeft] just acks.
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
            "SemiJoinCell $ref: waterline linked on a cell constructed without lateness"
        }
        val current = floor
        if (current != null && delta.floor <= current) {
            // [24-WL-03] fixpoint: a non-raising floor changes nothing — ack the wave on all three outlets
            return
        }
        floor = delta.floor
        onFloorRaised(delta.floor)
    }

    /**
     * Evict every passed, non-exclusive row of each declaring side
     * (`timeFn(row) < newFloor`) through [foldLeft]/[foldRight], both sides
     * into one accumulator, emitted once under the current (waterline)
     * delivery; record the exclusive rows as refused (`[24-WL-16]`,
     * `[24-WL-17]`). For a negated cell, evicting the right side's rows this
     * way is exactly what mints the antijoin's transient admits — see the
     * class KDoc. Not gated (3vd7k-D9); never forwards on the late outlets.
     */
    private fun onFloorRaised(newFloor: Long) {
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
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

    private fun reconcile(a: A, adds: MutableMap<A, Set<Timestamp>>, dels: MutableMap<A, Set<Timestamp>>) {
        val wanted = a in join.leftState && ((leftKey(a) in join.rightIndex) xor negated)
        if (wanted) {
            ledger.enter(a) { emptySet() }?.let { adds[a] = it }
        } else {
            ledger.exit(a)?.let { dels[a] = it }
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
        require(parts.size == 3 || parts.size == 4) { "SemiJoinCell snapshot has ${parts.size} parts, want 3 or 4" }
        join.leftState.restore(parts[0] as Serializable)
        join.rightState.restore(parts[1] as Serializable)
        ledger.restore(parts[2] as Serializable)
        floor = if (parts.size == 4) parts[3] as Long? else null
        refused = emptyList()
        join.rebuildIndexes(leftKey, rightKey)
    }

    /**
     * One page of this semijoin's three sub-states (V1C-OPS) — structurally
     * [JoinSetCell]'s, with an `A`-keyed ledger instead of a pair-keyed one.
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"left"` | `A` | [TaggedEntry] — the left rows' live tags |
     * | 1 | `"right"` | `B` | [TaggedEntry] — the right rows' live tags |
     * | 2 | `"ledger"` | `A` | [TaggedEntry] — the advertised row's minted tag |
     *
     * Same order as [snapshot]'s `arrayListOf(leftState, rightState, ledger)`.
     * `"left"` and `"ledger"` share key type `A` and overlap in content — an
     * advertised row is live on the left — so the `(subState, key)` identity is
     * load-bearing here exactly as in [IntersectSetCell]: one row is two
     * entries, carrying the input tags and the minted output tag respectively.
     *
     * [StatePage.attributes] carries [OperatorPaging.MINT_COUNTER] on **every**
     * page (Decision D) — see [JoinSetCell.readBounded] for the full argument;
     * the ledger is the same `MintedLedger`.
     *
     * The key indexes are derived and not in [snapshot], so they are not paged
     * (Decision E). [StatePage.frontier] covers all three sub-states, exact at
     * both ends of a walk, and its equality is **necessary but not sufficient**
     * for stability — non-retaining tag states, and a `MintedLedger.exit` that
     * removes rather than tombstones. [supportsSince] stays `false`.
     *
     * `[24-OP-SEMIJOIN-01]` is untouched: this method only reads.
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

/**
 * Set difference `A ⊖ B` (SQL EXCEPT DISTINCT): antijoin on identity keys.
 * [leftLateness]/[rightLateness] forward the opt-in waterline eviction — see
 * [SemiJoinCell]'s KDoc, including
 * the antijoin transient-admit cost that applies here whenever a lateness is
 * declared.
 */
fun <E> differenceSet(
    ref: CellRef = CellRef(UUID.randomUUID()),
    leftLateness: Windows.Lateness<E>? = null,
    rightLateness: Windows.Lateness<E>? = null,
): SemiJoinCell<E, E, E> =
    SemiJoinCell(
        ref,
        leftKey = { it },
        rightKey = { it },
        negated = true,
        leftLateness = leftLateness,
        rightLateness = rightLateness,
    )
