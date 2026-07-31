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
import civictech.cell.data.delta.SetDelta

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
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : SemiJoinCellBase<A, B>(ref), Stateful, BoundedStateful {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<A> = MintedLedger(ref, "semijoin")

    /** The `emitOnFrontier` fold, or null when the cell runs the ungated default. */
    private val gate: WaveGate<A>? =
        if (!emitOnFrontier) null
        else WaveGate(left, right) { timestamp, context, folds -> flush(timestamp, context, folds) }

    /** Waves currently held by the gate; always 0 when ungated. Diagnostic only. */
    val bufferedWaves: Int get() = gate?.bufferedWaves ?: 0

    init {
        // late-join catch-up (G-22): the advertised output as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
    }

    override fun onLeft(value: SetDelta<A>) {
        if (gate?.offerLeft(GatedFold { applyLeft(value) }) == true) return
        // The ungated path below is the shipped handler verbatim — including its
        // per-row interleaving of index-then-reconcile — so `emitOnFrontier =
        // false` stays byte-identical. A gated cell also lands here for a delta
        // the gate admits to no completeness set (catch-up, straggler, unmatched
        // edge): applying and reconciling it immediately is exactly right.
        val effective = join.leftState.apply(value)
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            join.index(join.leftIndex, leftKey(a), a, live = a in join.leftState)
            reconcile(a, adds, dels)
        }
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
        if (gate?.offerRight(GatedFold { applyRight(value) }) == true) return
        // ungated (or gate-exempt) — the shipped handler verbatim; see [onLeft].
        val effective = join.rightState.apply(value)
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            // key presence may have flipped: reconcile is idempotent,
            // so visiting unflipped keys' rows is just a no-op
            join.leftIndex[k]?.forEach { a -> reconcile(a, adds, dels) }
        }
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() },
        )
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

    override fun snapshot(): Serializable =
        arrayListOf(join.leftState.snapshot(), join.rightState.snapshot(), ledger.snapshot())

    override fun restore(state: Serializable) {
        val (l, r, m) = state as ArrayList<Serializable>
        join.leftState.restore(l)
        join.rightState.restore(r)
        ledger.restore(m)
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
 * [emitOnFrontier] forwards the opt-in gate — see [SemiJoinCell]'s KDoc.
 */
fun <E> differenceSet(
    ref: CellRef = CellRef(UUID.randomUUID()),
    emitOnFrontier: Boolean = false,
): SemiJoinCell<E, E, E> =
    SemiJoinCell(ref, leftKey = { it }, rightKey = { it }, negated = true, emitOnFrontier = emitOnFrontier)
