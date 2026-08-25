package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.cell.control.absorbAck
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta

@CellBase
interface QuorumSetApi<E> {
    /** Fan-in: one link per source, each carrying `SetDelta<E>` under its own tag lane. */
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Quorum over a dynamic `SetDelta<E>` fan-in: emits `SetDelta<E>` of the elements
 * whose presence count (distinct live source links asserting them, see
 * [PresenceLanes]) meets [threshold]. The threshold receives the current
 * **live-source count `n`** (the open-edge frontier), so the whole family of
 * quorum views is one lambda:
 *
 * | view | `threshold` |
 * |---|---|
 * | union (any)          | `{ 1 }`       |
 * | intersection (all)   | `{ n -> n }`  |
 * | majority             | `{ n -> n / 2 + 1 }` |
 * | k-of-n quorum        | `{ k }`       |
 * | near-miss (all-but-one) | `{ n -> n - 1 }` |
 *
 * Output tag discipline is [IntersectSetCell]'s — and, since computenet-s6l2,
 * shared with it via [MintedLedger]: on entry an element is advertised
 * downstream under **one freshly minted, cell-owned output tag**; on exit
 * exactly that tag is deleted, so a downstream `SetView`/[UnionSetCell] tracks
 * membership precisely and tag churn while membership holds is absorbed
 * (effective-only, spec 21). Because the threshold reads `n`, a link
 * opening/closing re-evaluates the quorum even for elements whose own count did
 * not move (e.g. an empty source joining tightens an intersection).
 *
 * ### Tag policy: minted, never borrowed (21 §Tag hygiene, computenet-s6l2)
 *
 * This cell used to advertise the union of the contributing lanes' *observed
 * input tags* ([AdvertisedLedger]) — the policy T07 finding 3 unified it with
 * [IntersectSetCell] on, and the one computenet-vvre then found unsound there.
 * It is unsound here for the same two independent reasons, both now pinned by
 * `QuorumDiamondTagTest`:
 *
 * - **A borrowed tag is not this cell's to delete.** In `union(A, quorum(A,
 *   B))` the element's tag from `A` reaches the [UnionSetCell] twice — once on
 *   the direct edge, once re-advertised by this cell — and the union correctly
 *   folds the two into ONE fact keyed by `(element, tag)`
 *   (`[24-OP-UNION-01]`'s diamond dedup). When the element then dropped below
 *   the threshold, the exit deleted `A`'s tag and the union retracted the
 *   *direct* edge's still-live contribution: `A ∪ quorum(A, B)` lost an element
 *   live in `A`. Measured, not inferred — the reproduction and its
 *   majority-threshold variant both failed against the borrowing code, while
 *   the distinct-source control passed.
 * - **Re-entry re-emits a deleted tag.** A quorum's membership flips ON when
 *   *another* lane asserts the element, so a flip-ON does not ride a fresh
 *   input add-tag on the flipping element — which is exactly the precondition
 *   21 §Tag hygiene attaches to pass-through — and re-advertising a tag a
 *   previous exit deleted violates 21's flat prohibition outright.
 *
 * Minting per entry ([MintedLedger]/`MintedTags`) removes both: the advertised
 * tag is unconfusable with any upstream's, so a diamond sees two independent
 * facts, and every re-entry carries a tag no consumer has tombstoned. Nothing
 * in `[24-OP-QUORUM-01]` moves — an entry tag is advertised on entry and every
 * advertised tag deleted on exit — only its provenance, from borrowed to
 * minted, matching every other join operator in this family.
 *
 * A delivery flagged [civictech.cell.MessageContext.baseline] is a recovery,
 * not a live wave, and is admitted regardless of [threshold] — see [onInlet]
 * (`[24-REPLAY-01]`).
 */
class QuorumSetCell<E>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val threshold: (liveSources: Int) -> Int,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : QuorumSetCellBase<E>(ref), Stateful, BoundedStateful {
    private val lanes = PresenceLanes<E>()

    /**
     * Elements currently advertised downstream, each under the single tag
     * minted on entry and deleted on exit (RS-5.3; minted, not borrowed — see
     * the tag-policy section on this class's KDoc, computenet-s6l2).
     */
    private val ledger: JoinLedger<E> = MintedLedger(ref, "quorum")

    init {
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event) {
                // n changed → the threshold shifted; re-evaluate the whole
                // working set, not just an incoming delta's elements.
                EdgeOpen -> {
                    lanes.open(link)
                    evaluate(lanes.elements() + ledger.entries.keys)
                }
                EdgeClose -> {
                    val orphaned = lanes.close(link)
                    evaluate(lanes.elements() + ledger.entries.keys + orphaned)
                }
                else -> {}
            }
        }
        // late-join catch-up (G-22): the advertised quorum as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
    }

    override fun onInlet(value: SetDelta<E>) {
        val ctx = CurrentContext.get()
        val effective = lanes.foldEffective(ctx, value)
        // PN-2 / `[24-REPLAY-01]` (spec 20/24 §Durable replay of a mid-graph
        // data cell): a journaled upstream's replayed frames re-enter flagged
        // MessageContext.baseline. That is a *recovery*, not a live wave — the
        // sibling arms are volatile and will never replay the same state, so
        // evaluating a replayed delta against the live threshold would leave
        // recovered arm state at lane-count 1 forever and silently drop it.
        // The SET-fan-in analogue of WaveFrontier.offer()'s baseline branch:
        // what the baseline ADDS to its lane is installed as authoritative
        // recovered arm state, bypassing the threshold. What it removes stays
        // on the live rule (the recovered arm no longer asserts it, so only a
        // live quorum can keep it advertised).
        val recovered = if (ctx?.baseline != null) effective.adds.keys else emptySet()
        evaluate(effective.adds.keys + effective.dels.keys, recovered)
    }

    /**
     * [recovered] holds the elements this evaluation must admit regardless of
     * [threshold] — a replayed baseline's installs (see [onInlet]); empty on
     * every live path, which therefore behaves exactly as before. Installed
     * elements are NOT remembered: the next live delta touching one, or an
     * `EdgeOpen`/`EdgeClose` shifting `n`, re-evaluates it under the ordinary
     * threshold, so the view converges back to live semantics after recovery.
     * Nothing is dropped silently — a baseline either enters/leaves the view
     * here or funnels through [emitOrAbsorb] below (whose absorb-ack half is a
     * documented no-op for a baseline: it holds no wave position, so no
     * downstream completeness set is waiting on it).
     */
    private fun evaluate(candidates: Collection<E>, recovered: Set<E> = emptySet()) {
        if (candidates.isEmpty()) return
        val target = threshold(lanes.liveSources)
        val adds = mutableMapOf<E, Set<Timestamp>>()
        val dels = mutableMapOf<E, Set<Timestamp>>()
        candidates.toSet().forEach { element ->
            val count = lanes.count(element)
            // an absent element (count 0) is never in the quorum, even if the
            // threshold is non-positive (near-miss with a single source).
            val meets = count >= 1 && (element in recovered || count >= target)
            if (meets) {
                // [MintedLedger] mints its own tag and ignores the supplier —
                // the lanes' input tags are deliberately NOT borrowed
                // (computenet-s6l2; see the tag-policy section on this class)
                ledger.enter(element) { emptySet() }?.let { adds[element] = it }
            } else {
                ledger.exit(element)?.let { dels[element] = it }
            }
        }
        // T05 finding 2: a re-evaluation that changes no membership (tag
        // churn, or a link open/close that doesn't tip any element's count
        // across the threshold) now absorb-acks the reactive wave instead of
        // silently dropping it — a GlitchFreeCell downstream would otherwise
        // stall forever on such a wave. Behavior change: this operator now
        // acks. (No-op via absorbAck's own CurrentContext guard when
        // `evaluate` runs from the EdgeOpen/EdgeClose protocol path, which
        // carries no reactive wave context.)
        emitOrAbsorb(
            adds.isEmpty() && dels.isEmpty(),
            emit = { outlet.call.propagate(SetDelta(adds, dels)) },
            absorbAck = { outlet.absorbAck() },
        )
    }

    override fun snapshot(): Serializable = arrayListOf(lanes.snapshot(), ledger.snapshot())

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (laneState, ledgerState) = state as ArrayList<Serializable>
        lanes.restore(laneState)
        ledger.restore(ledgerState)
    }

    /**
     * One page of this quorum's two sub-states, the first of them **nested**
     * (V1C-OPS, Decision F) — the deepest cursor in this package.
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"lanes"` | `(laneId: UUID, E)` | [TaggedEntry] with [TaggedEntry.lane] set — that lane's own tags for the element |
     * | 1 | `"ledger"` | `E` | [TaggedEntry], `lane = null` — the single minted tag advertised downstream |
     *
     * Same order as [snapshot]'s `arrayListOf(lanes, ledger)`.
     * `PresenceLanes.snapshot()` is `laneId -> TagState.snapshot()`, i.e. **two
     * levels of enumeration before an element**, so the cursor is three
     * components deep: `(subStateOrdinal, laneId, element)`. Decision B's
     * lexicographic rule is applied recursively — lanes in lane order, elements
     * in frozen order within a lane — and materialized as one frozen sequence of
     * `(laneId, element)` pairs. It is *not* flattened into a bare element
     * sequence: the same element asserted by three lanes is three entries, and
     * the lane is exactly what makes the presence count meaningful. A resume
     * therefore lands back **inside** a lane, finishes it, continues to the next
     * lane, and only then enters `"ledger"`.
     *
     * **Decision D — the lane frontier and the mint counter ride every page.**
     * [StatePage.attributes] carries [OperatorPaging.LANES], the open lane ids,
     * and — since this ledger mints (computenet-s6l2) —
     * [OperatorPaging.MINT_COUNTER], as it does for every other minting
     * operator: a restored instance must not re-mint a spent tag, so a walk
     * whose union is to equal `snapshot()`'s content has to carry it.
     * A lane that asserts no element is still in [snapshot] and still counts
     * towards the `n` a [threshold] reads, but has no entry to ride on; and the
     * lane set is cell-level state either way. It does not count against
     * [StateRead.limit], and it is exact on the first and last page of a walk.
     *
     * [StatePage.frontier] is the max per-source counter over every lane's tags
     * and the ledger's, exact at both ends of a walk. Its equality is
     * **necessary but not sufficient** for "the union is a snapshot": lane tag
     * states do not retain tombstones and the ledger's exit removes rather than
     * tombstones, so an element leaving the quorum mid-walk lowers the stamp
     * rather than raising it. [supportsSince] stays `false` accordingly — the
     * mint counter riding the attributes does not change that, since the lanes
     * can still lower the frontier.
     *
     * `[24-OP-QUORUM-01]` is the requirement covering this cell, but it
     * constrains admission and tag provenance, not reads; the contract this
     * method preserves is therefore its own KDoc — the advertise-on-entry /
     * delete-exactly-those-tags-on-exit discipline and `[24-REPLAY-01]`'s
     * baseline disposition — and this method only reads: it evaluates no
     * threshold, opens and closes no lane, and reaches neither `propagate` nor
     * `absorbAck`.
     */
    override fun readBounded(request: StateRead): StatePage = pageOver(
        request,
        listOf(laneSubState("lanes", lanes), ledgerSubState("ledger", ledger)),
        frontier = {
            val builder = FrontierBuilder()
            lanes.contributeTo(builder)
            ledger.contributeTo(builder)
            builder.build()
        },
        attributes = { lanes.readerAttributes() + ledger.readerAttributes() },
    )

    companion object {
        fun <E> create(threshold: (Int) -> Int): QuorumSetApi<E> = QuorumSetCell(threshold = threshold)

        /** Every live source must assert the element — generalises a chained [IntersectSetCell]. */
        fun <E> intersection(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { n -> n }

        /** Any live source suffices — matches [UnionSetCell] membership. */
        fun <E> union(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { 1 }

        /** A strict majority of live sources: `n / 2 + 1`. */
        fun <E> majority(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { n -> n / 2 + 1 }

        /** All-but-one of the live sources: `n - 1` (the near-miss view). */
        fun <E> nearMiss(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { n -> n - 1 }

        /** A fixed quorum of [k] live sources, independent of `n` (k-of-n). */
        fun <E> kOfN(k: Int, ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { k }
    }
}
