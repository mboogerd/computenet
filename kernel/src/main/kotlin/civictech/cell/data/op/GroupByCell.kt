package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.Aggregator
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TagState

@CellBase
interface GroupByApi<E, K, A> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<MapDelta<K, A>>>
}

/**
 * Incremental grouped aggregation (M11.3): folds a tagged set stream into
 * per-key aggregates, `keyFn` deriving the group and [aggregator] the value.
 * Membership flips (not tag churn) drive insert/retract; a group's last
 * retraction removes it (`MapDelta` removal — SQL group-death semantics);
 * emission is effective-only by value equality (21). All groups touched by
 * one input delta emit as one `MapDelta` under the input's wave id (22).
 *
 * The cell is the single writer of its output stream, which is exactly
 * `MapDelta`'s documented contract — so it is not `Replicable`, and needn't
 * be: an aggregate is a deterministic function of convergent membership, so
 * peers recompute from their replicated inputs and converge with no
 * aggregate-level gossip (42).
 *
 * **G-23 note (96 §E1.5) — this cell's INLET is [SetDelta], not [MapDelta].**
 * Unlike [CombineLatestCell]/[LookupJoinCell]/[JoinCell], this cell was never
 * the affected edge for the arrival-order-biased-`MapDelta`-input concern
 * `OrMapCell` → [civictech.cell.data.op.UntagCell] discharges elsewhere: its
 * input is a *set* membership stream, and its single-writer claim above is
 * already conditioned on that membership stream itself being convergent (a
 * plain [civictech.cell.data.SetCell] or an OR-set), not on an untagged
 * `MapDelta` write race. Only
 * this cell's *output* speaks `MapDelta`, and nothing here reads it back in —
 * so there is no G-23 caveat on this cell for `UntagCell` to discharge.
 *
 * ### Keys that cross a boundary must be registered individually (computenet-zxt5)
 *
 * This cell's outlet is `MapDelta<K, A>`, and `WireCodec` registers `MapDelta`
 * with the polymorphic `Any` serializer for both key and value. So **a
 * `GroupByCell` whose output is journaled or bridged requires `K` to have its
 * own polymorphic registration under `WireCodec`'s `Any`-rooted scope** — the
 * key is not covered by anything the cell itself declares, and a `keyFn`
 * returning an unregistered type fails only at the first frame that cell
 * accepts, with `Serializer for subclass '<K>' is not found in the polymorphic
 * scope of 'Any'`.
 *
 * The decision, for compound and value-class keys alike: **register them per
 * application, from that module's `WireSerializers` contribution — not from
 * the kernel baseline.** `Pair` takes
 * `PairSerializer(polymorphicAny, polymorphicAny)` and a `@JvmInline value
 * class` takes its own generated serializer; both were measured to work
 * through the ordinary contribution seam (`:demo:dialogue`'s
 * `MintWireCapabilityTest`). They are deliberately absent from
 * [civictech.cell.wire.WireCodec]'s `baselineModule` because registering
 * `Pair` kernel-wide would silently make *every* `Pair` wire-capable with both
 * components erased to polymorphic `Any` — a repo-wide encoding commitment
 * that no requirement asks for, and one that would hide exactly the loud
 * failure above from the next author of an unregistered key type.
 *
 * No `GroupByCell` in this repository needs such a registration today: every
 * fold whose output can reach a journal or a bridge is keyed by `String`,
 * `Int` or `Long` (all registered in the baseline), and the folds keyed by a
 * compound or value-class type — `:demo:dialogue`'s `projectedStances`,
 * `claimProvenance`, `relationProvenance` — sit in a pipeline that is
 * deliberately volatile and in a module with no `:wire` dependency. The
 * catalog folds in `:concord`/`:oracle` are statically `Any?`-keyed but run
 * only in-process.
 */
class GroupByCell<E, K, A, ACC : Serializable>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val keyFn: (E) -> K,
    private val aggregator: Aggregator<E, A, ACC>,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : GroupByCellBase<E, K, A>(ref), Stateful, BoundedStateful {
    private val state = TagState<E>()

    private class Group<ACC>(var count: Int, var acc: ACC)

    private val groups = mutableMapOf<K, Group<ACC>>()

    init {
        // late-join catch-up (G-22): current aggregates as a delta-from-empty
        outlet.catchUpOnLinked {
            if (groups.isEmpty()) null
            else MapDelta(groups.mapValues { aggregator.value(it.value.acc) }, emptySet())
        }
    }

    override fun onInlet(value: SetDelta<E>) {
        val touched = value.adds.keys + value.dels.keys
        val liveBefore = touched.filterTo(mutableSetOf()) { it in state }
        state.apply(value)

        // first-touch snapshot per affected group: emission compares
        // against the value before this delta, not mid-fold values
        val before = mutableMapOf<K, A?>()
        touched.forEach { e ->
            val was = e in liveBefore
            val now = e in state
            if (was == now) return@forEach // tag churn, no membership flip
            val k = keyFn(e)
            if (k !in before) before[k] = groups[k]?.let { aggregator.value(it.acc) }
            if (now) {
                val g = groups.getOrPut(k) { Group(0, aggregator.empty()) }
                g.count++
                g.acc = aggregator.insert(g.acc, e)
            } else {
                val g = checkNotNull(groups[k]) { "retract for untracked group $k" }
                g.count--
                g.acc = aggregator.retract(g.acc, e)
                if (g.count == 0) groups.remove(k)
            }
        }

        val puts = mutableMapOf<K, A>()
        val removals = mutableSetOf<K>()
        before.forEach { (k, old) ->
            val now = groups[k]?.let { aggregator.value(it.acc) }
            when {
                now == null && old != null -> removals += k
                now != null && now != old -> puts[k] = now // effective-only: value-equals gates
            }
        }
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        } else {
            outlet.absorbAck() // tag churn / value-equal fold — ack the swallowed wave (CP-A3)
        }
    }

    /**
     * This shard's live input membership as a delta-from-empty (PN-6): the raw
     * tagged elements it currently holds, tags verbatim. A `PartitionedCell`
     * repartition sources its replay from the shards' own contents instead of a
     * router-side `routed` ledger (deleted, PN-6 §one linker one assignment), so
     * the composite holds O(instances) routing state, never a second O(total)
     * copy of every element.
     */
    internal fun contents(): SetDelta<E> = state.asDelta()

    // ponytail: acc is not deep-copied — every snapshot consumer (checkpoint,
    // migrate) serializes immediately; copy-on-snapshot if one ever retains it
    override fun snapshot(): Serializable = arrayListOf(
        state.snapshot(),
        HashMap(groups.mapValues { arrayListOf(it.value.count, it.value.acc) }),
    )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (tags, gs) = state as ArrayList<Serializable>
        this.state.restore(tags)
        groups.clear()
        (gs as Map<K, List<Serializable>>).forEach { (k, g) ->
            groups[k] = Group(g[0] as Int, g[1] as ACC)
        }
    }

    /**
     * One page of this aggregation's two sub-states (V1C-OPS).
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"input"` | `E` | [TaggedEntry] — the live element and its tags |
     * | 1 | `"groups"` | `K` | [GroupEntry] — the group's `count` and `accumulator` |
     *
     * Same order as [snapshot]'s `arrayListOf(state.snapshot(), groups)`. The two
     * key spaces are **different types** (`E` and `K = keyFn(E)`), so a cursor
     * has to order across them: it is lexicographic `(subStateOrdinal, key)`
     * over the two frozen key sequences, and a resume that exhausts `"input"`
     * continues at the head of `"groups"` ([OperatorPaging], Decision B).
     *
     * **Decision G — an unbounded accumulator rides whole.** For the
     * non-invertible aggregator family (`minOf`/`maxOf`/`topK`/`collectToSet`,
     * `[24-OP-GROUPBY-04]`) the accumulator *is* the group's full support
     * multiset — required, not incidental — so one [GroupEntry] can be
     * arbitrarily large. It is emitted whole and [StateRead.byteBudget], which
     * is *advisory* and which this cell estimates with a constant (measuring an
     * arbitrary `ACC` would mean serializing it on the cell's own thread), is
     * simply exceeded. The alternatives both lose: splitting contradicts
     * [StatePage]'s "entries are whole", and a size-describing descriptor would
     * put the walk's union at odds with [snapshot]'s content (Decision E) for
     * the one field a restore actually needs. [StateRead.limit] remains a hard
     * cap, so an oversized entry costs one page, not an unbounded one.
     *
     * `TagState.deadSources` is deliberately **not** paged: it is live fold
     * state that [snapshot] itself omits and [restore] does not rebuild, and
     * Decision E fixes the walk's domain at exactly [snapshot]'s.
     *
     * [StatePage.frontier] is the max per-source counter over the input tag
     * state, exact on the first and last page of a walk (see [OperatorPaging]
     * for why an intermediate page carries the opening stamp with
     * [civictech.cell.ReadCaveat.STALE_FRONTIER] instead). Its equality across
     * a walk is **necessary but not sufficient** for "the union is a snapshot":
     * this `TagState` is non-retaining, so a mid-walk membership retraction
     * deletes tags rather than minting one and is invisible to the check.
     * [supportsSince] stays `false` accordingly.
     *
     * `[24-OP-GROUPBY-01]`/`-02`/`-03`/`-06` and `[24-AGG-01]` are untouched:
     * this method only reads, and emits nothing.
     */
    override fun readBounded(request: StateRead): StatePage = pageOver(
        request,
        listOf(
            tagSubState("input", state),
            SubState("groups", { ArrayList<Any?>(groups.keys) }) { key ->
                @Suppress("UNCHECKED_CAST")
                val k = key as K
                groups[k]?.let { groupEntry("groups", k, it.count, it.acc) }
            },
        ),
        frontier = { state.contributeTo(FrontierBuilder()).build() },
    )

    companion object {
        /** Fold-to-scalar: one global group under the constant key `"global"`. */
        fun <E, A, ACC : Serializable> global(
            aggregator: Aggregator<E, A, ACC>,
            ref: CellRef = CellRef(UUID.randomUUID()),
        ): GroupByCell<E, String, A, ACC> = GroupByCell(ref, keyFn = { "global" }, aggregator = aggregator)
    }
}
