package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.ExclusiveEntry
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
import civictech.cell.data.Aggregator
import civictech.cell.data.Windows
import civictech.cell.control.absorbAck
import civictech.cell.control.relayAbsorbAcks
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TagState
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort

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
 * *Per application* is meant literally: `Pair` is a stdlib type with one slot
 * in the `Any` scope, so two contributions that both register it collide and
 * fail codec construction fast (`SerializerAlreadyRegisteredException`, the
 * behaviour [civictech.cell.wire.WireSerializers] documents; measured — a
 * production `Pair` registration reddens `MintWireCapabilityTest`'s
 * contribution arm on exactly that exception). One owner per process, not one
 * per module that happens to want it.
 *
 * No `GroupByCell` in this repository needs such a registration today: every
 * fold whose output can reach a journal or a bridge is keyed by `String`,
 * `Int` or `Long` (all registered in the baseline), and the folds keyed by a
 * compound or value-class type reach neither. Those are `:demo:dialogue`'s
 * `projectedStances`, `claimProvenance` and `relationProvenance`, which sit in
 * a pipeline that is deliberately volatile and in a module with no `:wire`
 * dependency; and `:demo:skillmatch`'s `matchCounts`, keyed by the compound
 * `CandidateJob`, in a module that likewise has no `:wire` dependency and
 * journals nothing (`:inspect`, which it does depend on, renders values
 * through its own reflective `ValueEncoder`, not through `WireCodec`). The
 * catalog folds in `:concord`/`:oracle` are statically `Any?`-keyed but run
 * only in-process.
 *
 * ### Lateness: the `waterline` inlet and the `late` outlet (KE4.3)
 *
 * Constructed with a [Windows.Lateness] and a `keyTime` (both or neither), the
 * cell tracks the event-time floor delivered on [waterline] and guards
 * [inlet]: an add whose `timeFn(e)` is strictly below the floor is excluded
 * from the fold, creates no group, retracts no live copy of the element, and
 * leaves verbatim — tags preserved — on [late], counted in [droppedBelowFloor]
 * whether or not `late` is linked (`[24-WL-07]`). Dels are never filtered by
 * time: the tag fold's liveness check is the guard (`[24-WL-08]`). The two
 * ports are registered on this class, always present and unlinked by default,
 * and deliberately **not** on [GroupByApi]: `PartitionedCell` implements that
 * Api directly and could honour neither. So a cell reached through a
 * `GroupByApi` ref, or constructed without lateness, is exactly the
 * pre-lateness operator (`[24-WL-11]`); a `WaterlineDelta` on such a cell is a
 * structural error and throws.
 *
 * **Eviction on a floor rise (`[24-WL-05]`/`[24-WL-06]`).** The eviction unit
 * is the window, never a piecemeal subset of it: a window `k` has passed once
 * `keyTime(k) <= floor` (`keyTime` is the window's exclusive end, so `[t, t+w)`
 * passes at `floor >= t+w`). Every passed window's live elements are killed
 * through [WaterlineEviction.evict] and folded through the same
 * membership-flip fold [onInlet] uses, so counts decrement, emptied groups die
 * as `MapDelta` removals (`[24-OP-GROUPBY-02]`), and the whole eviction leaves
 * as one `MapDelta` under the waterline delivery's wave (`[24-OP-GROUPBY-03]`)
 * — the cell's state stays equal to its integrated output. A del arriving
 * later for an evicted element finds no live tag and is a no-op (`[24-WL-09]`);
 * an add for it is below the floor and late-dropped.
 *
 * **Per-window exclusive refusal (`[24-WL-17]`, nt17o-D3).** A passed window
 * holding an `Owned`/`Leased` element is skipped: untouched, nothing emitted
 * for it, the exclusive never taken, released or borrowed by this cell. It is
 * recorded as an [ExclusiveEvictionRefused] in [refusedWindows] (replaced on
 * every rise) and counted in [refusedEvictions]; the other passed windows are
 * still evicted in the same delta. Its tags stay live, so an ordinary del still
 * retracts its members (`[24-WL-08]`), and every later rise re-evaluates it.
 * Only a top-level exclusive element is detected, not one nested inside a
 * plain element (computenet-woto).
 *
 * **Single-instance only (`[24-WL-18]`).** The `Replicable` refusal lives in
 * [WaterlineEviction.evict], where the host is a parameter: this class is
 * final and not `Replicable`, so an in-class check would be vacuous.
 */
class GroupByCell<E, K, A, ACC : Serializable>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val keyFn: (E) -> K,
    private val aggregator: Aggregator<E, A, ACC>,
    /** The inlet's lateness declaration (`[24-WL-01]`); `null` = no guard, no floor (`[24-WL-11]`). */
    private val lateness: Windows.Lateness<E>? = null,
    /** A window key's end in event time — the eviction unit's clock (`[24-WL-06]`); given iff [lateness] is. */
    private val keyTime: ((K) -> Long)? = null,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : GroupByCellBase<E, K, A>(ref), Stateful, BoundedStateful {
    private val state = TagState<E>()

    private class Group<ACC>(var count: Int, var acc: ACC)

    private val groups = mutableMapOf<K, Group<ACC>>()

    /** The event-time floor (`[24-WL-02]`); `null` is the identity — nothing is below it. */
    private var floor: Long? = null

    /** The current waterline floor, or `null` before any raising [WaterlineDelta] (or without lateness). */
    fun floor(): Long? = floor

    /**
     * Adds excluded by the late-drop guard, counted per dropped element (map
     * entry). The observable half of the drop (`[24-WL-07]`): it moves even
     * when [late] is unlinked. A counter, not state — neither snapshotted nor
     * paged by [readBounded].
     */
    var droppedBelowFloor: Long = 0
        private set

    private val refused = LinkedHashMap<K, ExclusiveEvictionRefused>()

    /**
     * The passed windows currently refused eviction because they hold an
     * exclusive (`[24-WL-17]`), recomputed on every floor rise; a window leaves
     * it when it is evicted or its last member is retracted. Not snapshotted.
     */
    fun refusedWindows(): Map<K, ExclusiveEvictionRefused> = LinkedHashMap(refused)

    /** Cumulative count of per-window eviction refusals, one per refused window per rise. A counter, not state. */
    var refusedEvictions: Long = 0
        private set

    /** The floor's inlet (`[24-WL-03]`): a value read here, never re-emitted (`[24-WL-04]`). */
    val waterline = registerPort("waterline", FanInlet.create<Propagate<WaterlineDelta>>())

    /** Sub-floor adds, forwarded verbatim — original tags — under the delivery that carried them (`[24-WL-07]`). */
    val late = registerPort("late", FanOutlet.create<Propagate<SetDelta<E>>>())

    init {
        require((lateness == null) == (keyTime == null)) {
            "GroupByCell needs both lateness and keyTime, or neither (lateness=$lateness, keyTime=$keyTime)"
        }
        // Data and waterline share one per-edge settlement fold. Progress on
        // either input waits for every open sibling edge; a real emission on
        // `outlet` or `late` suppresses that output's ack for the wave.
        inlet.relayAbsorbAcks(listOf(outlet, late), waterline)
        // late-join catch-up (G-22): current aggregates as a delta-from-empty
        outlet.catchUpOnLinked {
            if (groups.isEmpty()) null
            else MapDelta(groups.mapValues { aggregator.value(it.value.acc) }, emptySet())
        }
        waterline.serve(object : Propagate<WaterlineDelta> {
            override fun propagate(value: WaterlineDelta) = onWaterline(value)
        })
    }

    private fun onWaterline(delta: WaterlineDelta) {
        checkNotNull(lateness) {
            "GroupByCell $ref: waterline linked on a cell constructed without lateness/keyTime"
        }
        val current = floor
        if (current != null && delta.floor <= current) {
            // [24-WL-03] fixpoint: a non-raising floor changes nothing — ack the wave on both outlets
            outlet.absorbAck()
            late.absorbAck()
            return
        }
        floor = delta.floor
        onFloorRaised(delta.floor)
    }

    /**
     * Evict every passed window (`keyTime(k) <= newFloor`) that holds no
     * exclusive, as one retraction `MapDelta` under the current delivery's wave;
     * record the rest as refused (`[24-WL-06]`, `[24-WL-17]`). Eviction never
     * forwards on `late`.
     */
    private fun onFloorRaised(newFloor: Long) {
        val keyTime = checkNotNull(keyTime)
        val passed = state.elements.groupBy(keyFn).filterKeys { keyTime(it) <= newFloor }
        refused.clear()
        val evictees = mutableSetOf<E>()
        passed.forEach { (k, members) ->
            val exclusives = members.count { ExclusiveEntry.isExclusive(it) }
            if (exclusives > 0) refused[k] = ExclusiveEvictionRefused(ref, k, exclusives)
            else evictees += members
        }
        refusedEvictions += refused.size

        // every evictee is live by construction: liveBefore == evictees
        val killed = WaterlineEviction.evict(this, state) { it in evictees }
        val delta = foldMembership(killed.dels.keys, evictees)
        if (delta != null) {
            outlet.call.propagate(delta)
        } else {
            outlet.absorbAck() // nothing passed, or every passed window refused
        }
        late.absorbAck()
    }

    override fun onInlet(value: SetDelta<E>) {
        // [24-WL-07] late-drop guard; consulted only when lateness is declared and the floor is set
        val lateness = lateness
        val current = floor
        var dropped: Map<E, Set<Timestamp>> = emptyMap()
        val admitted = if (lateness != null && current != null) {
            val (below, rest) = value.adds.entries.partition { lateness.timeFn(it.key) < current }
            if (below.isEmpty()) value
            else {
                dropped = below.associate { it.key to it.value }
                // dels are never filtered by time: TagState.apply's liveness is the guard ([24-WL-08])
                SetDelta(rest.associate { it.key to it.value }, value.dels)
            }
        } else value

        val touched = admitted.adds.keys + admitted.dels.keys
        val liveBefore = touched.filterTo(mutableSetOf()) { it in state }
        state.apply(admitted)

        val delta = foldMembership(touched, liveBefore)
        if (delta != null) {
            outlet.call.propagate(delta)
        } else {
            outlet.absorbAck() // tag churn / value-equal fold — ack the swallowed wave (CP-A3)
        }
        if (dropped.isNotEmpty()) {
            droppedBelowFloor += dropped.size
            late.call.propagate(SetDelta(adds = dropped))
        } else {
            late.absorbAck()
        }
    }

    /**
     * Membership flips of [touched] (given which were live before the tag fold)
     * into [groups], and the effective-only `MapDelta` they produce — `null`
     * when no group's value changed. Shared by [onInlet] and window eviction
     * ([onFloorRaised]), so an eviction is an ordinary retraction.
     */
    private fun foldMembership(touched: Set<E>, liveBefore: Set<E>): MapDelta<K, A>? {
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
                if (g.count == 0) {
                    groups.remove(k)
                    refused.remove(k) // a refused window whose last member left is no longer refused
                }
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
        return if (puts.isNotEmpty() || removals.isNotEmpty()) MapDelta(puts, removals) else null
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
    //
    // A lateness-declaring cell appends its floor as a third element (nt17o-D4,
    // the floor half of [KE4-45]) so a recovered cell keeps late-dropping; a
    // cell without lateness keeps the two-element form byte-for-byte ([24-WL-11]).
    override fun snapshot(): Serializable = arrayListOf<Serializable?>(
        state.snapshot(),
        HashMap(groups.mapValues { arrayListOf(it.value.count, it.value.acc) }),
    ).apply { if (lateness != null) add(floor) }

    /** Accepts the two-element form (no floor: `floor()` stays `null`) and the three-element form. */
    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val parts = state as List<Serializable?>
        require(parts.size == 2 || parts.size == 3) { "GroupByCell snapshot has ${parts.size} parts, want 2 or 3" }
        val tags = parts[0] as Serializable
        val gs = parts[1] as Serializable
        floor = if (parts.size == 3) parts[2] as Long? else null
        refused.clear()
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
     * non-invertible aggregator family (`minOf`/`maxOf`/`topK`/`topKBy`/`collectToSet`/`countDistinct`,
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
