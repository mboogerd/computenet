package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*
import civictech.cell.data.Replicable
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta

/**
 * Replicable grouped aggregation (CP-G1): the mergeable sibling of
 * [GroupByCell]. Where [GroupByCell] recomputes accumulators from convergent
 * membership and is deliberately **not** [Replicable] (last-writer-wins on its
 * `MapDelta` would lose a peer's concurrent partial sum), this cell is
 * parameterized by a **commutative-associative** [merge] on the accumulator, so
 * peers gossip O(groups) *aggregate* deltas over [deltaInlet] and converge —
 * instead of replicating the O(input) membership and recomputing per peer.
 *
 * - [inlet] folds local elements: `accumulate` projects an element to a partial
 *   accumulator, [merge] combines partials sharing a key.
 * - [deltaInlet] folds a peer's aggregate delta into local groups via [merge]
 *   (absent key = the operator's identity — see [MapDelta.merge]); only keys
 *   whose value actually changed re-emit (effective-only, 21), which is what
 *   terminates mesh echoes when [merge] is **idempotent** (max/min/set-union).
 *   A *counted* operator (`+`) converges only over disjoint keys (no echo
 *   re-merge) — exactly the disjoint-shard scatter-gather the demo uses.
 *
 * Declaring [Replicable] makes CP-F2's marker scan stamp the ports
 * `MergeClass.IDEMPOTENT` on `MERGE_IDEMPOTENCE`, so a non-idempotent
 * accumulator wired to a replicated sink is refused at link time (CP-F3) rather
 * than silently drifting.
 *
 * Retraction: unlike [GroupByCell] there is no `retract` — a merge cannot be
 * un-applied in general. The accumulator itself must encode removal (a peer's
 * `MapDelta.removals` on [deltaInlet] drop a key); element-level retraction on
 * [inlet] belongs to the non-replicated [GroupByCell].
 */
class MergeableGroupByCell<E, K, A : Serializable>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    private val keyOf: (E) -> K,
    private val accumulate: (E) -> A,
    private val merge: (A, A) -> A,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : Cell, Stateful, BoundedStateful, Replicable<MapDelta<K, A>> {

    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())

    override val outlet =
        registerPort("outlet", FanOutlet.create<Propagate<MapDelta<K, A>>>())

    override val deltaInlet =
        registerPort("deltaInlet", FanInlet.create<Propagate<MapDelta<K, A>>>())

    private val groups = mutableMapOf<K, A>()

    /** Current per-key aggregates — the materialized group-by value. */
    fun aggregates(): Map<K, A> = groups.toMap()

    /** Fold [value] into `groups[key]` via [merge]; return the new value iff it changed. */
    private fun fold(key: K, value: A): A? {
        val merged = groups[key]?.let { merge(it, value) } ?: value
        if (merged == groups[key]) return null // effective-only (21) — echo dies here
        groups[key] = merged
        return merged
    }

    /** Local elements → per-key accumulator (grow/merge only; see class KDoc on retraction). */
    private fun onLocal(delta: SetDelta<E>) {
        val puts = mutableMapOf<K, A>()
        delta.adds.keys.forEach { e -> fold(keyOf(e), accumulate(e))?.let { puts[keyOf(e)] = it } }
        if (puts.isNotEmpty()) outlet.call.propagate(MapDelta(puts, emptySet()))
    }

    /** A peer's aggregate delta → merged into local groups; re-emit only the net change.
     *  The net change rides the INCOMING wave (like the scatter-gather forward it
     *  replaces), so a downstream wave-aligned sink still sees one wave per input. */
    private fun onRemote(delta: MapDelta<K, A>) {
        val puts = mutableMapOf<K, A>()
        delta.puts.forEach { (k, v) -> fold(k, v)?.let { puts[k] = it } }
        val removals = delta.removals.filterTo(mutableSetOf()) { groups.remove(it) != null }
        if (puts.isNotEmpty() || removals.isNotEmpty())
            outlet.call.propagate(MapDelta(puts, removals))
    }

    init {
        inlet.serve(Propagate { onLocal(it) })
        deltaInlet.serve(Propagate { onRemote(it) })
        // late-join catch-up (G-22) / replica anti-entropy: current aggregates
        // as a delta-from-empty; merge idempotence makes the replay harmless.
        outlet.catchUpOnLinked { if (groups.isEmpty()) null else MapDelta(groups.toMap(), emptySet()) }
    }

    override fun snapshot(): Serializable = HashMap(groups)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        groups.clear()
        groups.putAll(state as Map<K, A>)
    }

    /**
     * One page of this cell's aggregates (V1C-OPS).
     *
     * | ordinal | sub-state | key | value |
     * |---|---|---|---|
     * | 0 | `"groups"` | `K` | `A` (the merged accumulator) |
     *
     * A **single** sub-state — [snapshot] is a bare `HashMap(groups)` — so the
     * cross-sub-state ordering is degenerate here and the cursor reduces to
     * `(0, key)` over one frozen key sequence. It is still built on the same
     * skeleton ([OperatorPaging]) so that the enumeration order is imposed
     * rather than inherited from a `LinkedHashMap`: a key removed by a peer's
     * `MapDelta.removals` and re-merged mid-walk must not be returned twice.
     *
     * **Decision G — an unbounded accumulator rides whole.** `A` is an
     * app-supplied `Serializable` and a set-union `merge` makes it exactly as
     * large as the group's support. The entry is emitted whole and
     * [StateRead.byteBudget] — which is *advisory*, and which this cell
     * estimates with a constant because measuring an arbitrary `A` would mean
     * serializing it on the cell's own thread — is simply exceeded. Splitting is
     * not an option ([StatePage] promises whole entries) and eliding would put
     * the walk's union at odds with [snapshot]'s content (Decision E).
     * [StateRead.limit] is still a hard cap.
     *
     * [StatePage.frontier] is null: aggregates are untagged, so the across-page
     * stability check and the `since` escalation path are unavailable and
     * [supportsSince] stays `false`. `[24-SCOPED-01]` and this cell's own
     * merge/idempotence contract are untouched — this method only reads.
     */
    override fun readBounded(request: StateRead): StatePage =
        pageOver(request, listOf(mapSubState("groups", groups)))
}
