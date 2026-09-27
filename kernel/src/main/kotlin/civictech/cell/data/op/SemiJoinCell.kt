package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
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
import civictech.cell.control.absorbAck
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
 * ### `emitOnFrontier` — the opt-in flicker gate (`[24-OP-SEMIJOIN-04]`, 96 §E2.4)
 *
 * Antijoin membership flips are **absence assertions** — emitting or retracting
 * a row because the *other* side does or doesn't hold a matching key needs
 * knowing non-membership, non-monotone in the CALM sense — so a row can enter
 * and exit within one wave as the two sides' opposing updates arrive on separate
 * invocations. A downstream glitch-free wrapper cannot rescue that: the
 * per-arrival emissions are two *complete* one-edge waves, which the wrapper
 * faithfully replays, flicker and all (the D-COMBINE lesson). Remediation has to
 * happen here, before emission.
 *
 * Constructed with `emitOnFrontier = true`, this cell buffers each wave's input
 * deltas across both inlets ([WaveGate]), applies them together at wave
 * completeness, and reconciles each touched row **once** against both sides'
 * settled membership — so a transient enter-then-exit cancels *before* any tag
 * is minted on the outlet, and the wave emits only its net enter/exit set. That
 * ordering is also the `MintedTags` hygiene contract (`[24-OP-SEMIJOIN-02]`):
 * reconciliation is on membership first, minting second, so no tag that never
 * reached the wire is ever tombstoned and a re-entry stays live under
 * tombstone-folding consumers.
 *
 * The gate is **opt-in**; the default stays ungated and byte-identical, because
 * this is deliberately *not* a smarter convergent cell — absence-based emission
 * is non-monotone and some sealing is unavoidable; per-wave sealing over the
 * completeness frontier is the cheapest ComputeNet has. Read [WaveGate]'s
 * phantom-expected-edge caveat before enabling it: the gate suits the
 * shared-source diamond (both inlets descending from one root, the only topology
 * in which the within-wave flicker exists at all), not two independent roots.
 * **One root is necessary and not sufficient** — each arm must also *carry* the
 * root's waves onto the gated edge, and where it structurally cannot, its
 * absorb-ack rescues the wave only when the absorbing operator links directly
 * into this cell's inlet; a pure hop in between swallows the ack and the gate
 * withholds output at rest. [WaveGate]'s "One root is NOT sufficient" section
 * has the mechanism and the measurement (computenet-23bf).
 *
 * ### Lateness: the `waterline` inlet and the `lateLeft`/`lateRight` outlets (KE4.5)
 *
 * Constructed with a [Windows.Lateness] on either or both sides, the cell
 * tracks one event-time floor delivered on [waterline] and guards each
 * declaring inlet at **arrival**, before the [WaveGate] offer on the gated
 * path as on the ungated one: an add whose `timeFn(row)` is strictly below the
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
 * rise, and appended to by a gated flush that keeps one — see [sinceArrival])
 * and counted in [refusedEvictions]; every other passed row still
 * evicts in the same delta, and every later rise re-evaluates it. Only a
 * top-level exclusive row is detected (computenet-woto).
 *
 * **The waterline is not gated (3vd7k-D9).** With `emitOnFrontier = true` the
 * eviction is still applied and emitted on the `WaterlineDelta`'s own
 * delivery, while buffered data waves stay buffered. A buffered wave's adds
 * were late-split against the floor they *arrived* under; any a later rise
 * has passed by flush are evicted before they land
 * ([WaterlineEviction.dropPassedAdds]) — admitted, so not forwarded late —
 * which is the ungated cell's admit-then-evict under the same arrival order.
 * An exclusive row kept at flush because it cannot be evicted is recorded as
 * an [ExclusiveEvictionRefused] immediately, in the same flush, rather than
 * waiting for the next rise (`[24-WL-17]`, computenet-7y4sm) — the gated cell
 * and the ungated one agree on `refusedRows()` at quiescence with no further
 * floor rise, matching `[24-WL-10]`. `refusedEvictions` agrees when one rise
 * passed the buffered add; when several did, the ungated cell counts one per
 * rise and the gated cell one at flush.
 *
 * **Single-instance only (`[24-WL-18]`)**, checked in [WaterlineEviction.evict].
 */
class SemiJoinCell<A, B, K>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    private val negated: Boolean = false,
    /**
     * Opt-in frontier-gated emission (`[24-OP-SEMIJOIN-04]`, 96 §E2.4) — see the
     * class KDoc. `false` (the default) is the shipped, ungated behavior,
     * unchanged.
     */
    emitOnFrontier: Boolean = false,
    /** The left inlet's lateness declaration (`[24-WL-01]`); `null` = never guarded, never evicted (`[24-WL-16]`). */
    private val leftLateness: Windows.Lateness<A>? = null,
    /** The right inlet's lateness declaration; `null` = never guarded, never evicted. */
    private val rightLateness: Windows.Lateness<B>? = null,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : SemiJoinCellBase<A, B>(ref), Stateful, BoundedStateful, FrontierGateable {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<A> = MintedLedger(ref, "semijoin")

    /** [FrontierGateable]: `true` iff constructed with `emitOnFrontier = true`. */
    override val frontierGated: Boolean = emitOnFrontier

    /** The `emitOnFrontier` fold, or null when the cell runs the ungated default. */
    private val gate: WaveGate<A>? =
        if (!emitOnFrontier) null
        else WaveGate(left, right) { timestamp, context, folds -> flush(timestamp, context, folds) }

    /** Waves currently held by the gate; always 0 when ungated. Diagnostic only. */
    val bufferedWaves: Int get() = gate?.bufferedWaves ?: 0

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

    /** The passed rows refused eviction on the latest rise, plus any a gated flush kept since, because they are exclusive (`[24-WL-17]`). Not snapshotted. */
    fun refusedRows(): List<ExclusiveEvictionRefused> = refused

    /** Cumulative count of per-row eviction refusals, one per refused row per rise and one per exclusive row a gated flush keeps. A counter, not state. */
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
        // [24-WL-07] guard at arrival, before the gate offer (3vd7k-D4)
        val admitted = guardLeft(value)
        lateRight.absorbAck() // every delivery acks both late outlets, so a linked consumer's frontier advances
        if (gate?.offerLeft(GatedFold { applyLeft(sinceArrival(admitted, leftLateness)) }) == true) return
        // The ungated path below is the shipped handler verbatim — including its
        // per-row interleaving of index-then-reconcile — so `emitOnFrontier =
        // false` stays byte-identical. A gated cell also lands here for a delta
        // the gate admits to no completeness set (catch-up, straggler, unmatched
        // edge): applying and reconciling it immediately is exactly right.
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        foldLeft(join.leftState.apply(admitted), adds, dels)
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            // frontier-gated antijoin/semijoin emission (CP-A3): a wave that flips
            // no membership still advances the downstream frontier by an absorb-ack.
            absorbAck = { outlet.absorbAck() },
        )
    }

    override fun onRight(value: SetDelta<B>) {
        val admitted = guardRight(value)
        lateLeft.absorbAck()
        if (gate?.offerRight(GatedFold { applyRight(sinceArrival(admitted, rightLateness)) }) == true) return
        // ungated (or gate-exempt) — the shipped handler verbatim; see [onLeft].
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        foldRight(join.rightState.apply(admitted), adds, dels)
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() },
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
        if (lateness == null || current == null) {
            lateLeft.absorbAck()
            return value
        }
        val (admitted, dropped) = WaterlineEviction.lateSplit(lateness, current, value)
        if (dropped.isNotEmpty()) {
            droppedBelowFloorLeft += dropped.size
            lateLeft.call.propagate(SetDelta(adds = dropped))
        } else {
            lateLeft.absorbAck()
        }
        return admitted
    }

    /** The mirror image of [guardLeft] on the right inlet and [lateRight]. */
    private fun guardRight(value: SetDelta<B>): SetDelta<B> {
        val lateness = rightLateness
        val current = floor
        if (lateness == null || current == null) {
            lateRight.absorbAck()
            return value
        }
        val (admitted, dropped) = WaterlineEviction.lateSplit(lateness, current, value)
        if (dropped.isNotEmpty()) {
            droppedBelowFloorRight += dropped.size
            lateRight.call.propagate(SetDelta(adds = dropped))
        } else {
            lateRight.absorbAck()
        }
        return admitted
    }

    /**
     * Gated flush only: evict the adds of a buffered, arrival-admitted delta
     * that a floor rise has passed since it arrived (see
     * [WaterlineEviction.dropPassedAdds]). An exclusive row kept despite
     * having passed is recorded as an immediate `[24-WL-17]` refusal — appended
     * to [refused] and counted in [refusedEvictions] right here at flush,
     * rather than left to the next floor rise's [onFloorRaised] to discover
     * (computenet-7y4sm: the gated cell must agree with the ungated one at
     * quiescence, per `[24-WL-10]`). The identity without a floor or without
     * this side's declaration.
     */
    private fun <E> sinceArrival(value: SetDelta<E>, lateness: Windows.Lateness<E>?): SetDelta<E> {
        val current = floor
        if (lateness == null || current == null) return value
        val (kept, refusedRows) = WaterlineEviction.dropPassedAdds(lateness, current, value)
        if (refusedRows.isNotEmpty()) {
            val newlyRefused = refusedRows.map { ExclusiveEvictionRefused(ref, it, 1, unit = "row") }
            refused = refused + newlyRefused
            refusedEvictions += newlyRefused.size
        }
        return kept
    }

    private fun onWaterline(delta: WaterlineDelta) {
        checkNotNull(leftLateness ?: rightLateness) {
            "SemiJoinCell $ref: waterline linked on a cell constructed without lateness"
        }
        val current = floor
        if (current != null && delta.floor <= current) {
            // [24-WL-03] fixpoint: a non-raising floor changes nothing — ack the wave on all three outlets
            outlet.absorbAck()
            lateLeft.absorbAck()
            lateRight.absorbAck()
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
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() }, // nothing passed, or every passed row refused / matchless
        )
        lateLeft.absorbAck()
        lateRight.absorbAck()
    }

    // ---- the emitOnFrontier path: apply now, reconcile at completeness ----

    /**
     * Fold a left delta into membership and the key index **without**
     * reconciling; returns the rows the completed wave must reconcile.
     */
    private fun applyLeft(value: SetDelta<A>): Set<A> {
        val effective = join.leftState.apply(value)
        val rows = LinkedHashSet<A>()
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            join.index(join.leftIndex, leftKey(a), a, live = a in join.leftState)
            rows += a
        }
        return rows
    }

    /**
     * Fold a right delta into membership and the key index **without**
     * reconciling; returns the left rows the completed wave must reconcile.
     *
     * The rows are read off [KeyedBinarySetJoin.leftIndex] as it stands *now*,
     * so a left fold of the same wave applied afterwards would see a different
     * index — but the union over the wave's folds is unaffected: any row whose
     * membership in `leftIndex[k]` differs between the two application orders is
     * precisely a row the left fold itself touched, and so is in the union
     * either way ([GatedFold]).
     */
    private fun applyRight(value: SetDelta<B>): Set<A> {
        val effective = join.rightState.apply(value)
        val rows = LinkedHashSet<A>()
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            join.leftIndex[k]?.let { rows += it }
        }
        return rows
    }

    /**
     * One completed wave (gated only): apply **both** sides' buffered deltas,
     * then reconcile the union of their touched rows once, against settled
     * membership — the point at which a transient enter-then-exit cancels before
     * a tag is minted. The emission runs inside the buffered context so the
     * outlet's reactive stamping keys it to the completed input wave, however
     * completeness was reached; a wave known only from acks carries no context of
     * its own, so its ack is minted from the wave position directly
     * ([CoalescingCombineCell]'s pattern).
     */
    private fun flush(timestamp: Timestamp, context: MessageContext?, folds: List<GatedFold<A>>) {
        val rows = LinkedHashSet<A>()
        folds.forEach { rows += it.applyAndTouch() }
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        rows.forEach { reconcile(it, adds, dels) }
        CurrentContext.with(context ?: MessageContext(timestamp, outlet.ref)) {
            join.emitOrAbsorb(
                adds,
                dels,
                propagate = { outlet.call.propagate(it) },
                absorbAck = { outlet.absorbAck() },
            )
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the gate's transient
     * wave buffer is dropped — its deltas were never applied to either side's
     * membership and never observed downstream.
     */
    override fun onDeactivate(ctx: CellContext) {
        gate?.clear()
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
 * [emitOnFrontier] forwards the opt-in gate, and [leftLateness]/[rightLateness]
 * forward the opt-in waterline eviction — see [SemiJoinCell]'s KDoc, including
 * the antijoin transient-admit cost that applies here whenever a lateness is
 * declared.
 */
fun <E> differenceSet(
    ref: CellRef = CellRef(UUID.randomUUID()),
    emitOnFrontier: Boolean = false,
    leftLateness: Windows.Lateness<E>? = null,
    rightLateness: Windows.Lateness<E>? = null,
): SemiJoinCell<E, E, E> =
    SemiJoinCell(
        ref,
        leftKey = { it },
        rightKey = { it },
        negated = true,
        emitOnFrontier = emitOnFrontier,
        leftLateness = leftLateness,
        rightLateness = rightLateness,
    )
