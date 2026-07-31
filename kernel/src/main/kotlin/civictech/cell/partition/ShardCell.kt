package civictech.cell.partition

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Propagate
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.EntryOrder
import civictech.cell.data.KeyWalk
import civictech.cell.data.PageBudget
import civictech.cell.data.Replicable
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.StateRequest
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.registerPort
import civictech.cell.replication.Assignment
import civictech.cell.link.Interest
import java.io.Serializable
import java.util.UUID

/**
 * An interest-scoped **hosted instance** of a partitioned logical id (spec
 * 40/42 §Interest-scoped instance sets, 20/24 §Partitioned state, CP-D3): one
 * shard of a [PartitionedCell], spawnable onto any real
 * [civictech.cell.host.ManagedHost] and reached by the router over the registry
 * — in-process or across a bridge, transparently. It holds the disjoint
 * key-range assigned by its [Interest] and merges routed slices idempotently
 * (tag union, replay-safe), exactly like a [SetCell] replica; the difference is
 * one predicate.
 *
 * Its [routeInlet] is the receiving end of the disjoint-interest linker. When
 * [epochAware] (the default), every routed slice is re-filtered to the shard's
 * *current* [interest] before merging, and an interest reassignment ([assign])
 * sheds the elements the shard no longer owns — so a repartition flip (interest
 * reassignment + a bump of the routing epoch) loses nothing and double-counts
 * nothing even while stale commands are still in flight. With [epochAware]
 * false the guard is off (the CP-D3 control): a shard applies every slice blind
 * and keeps sheddable elements, so a flip forks a group across two shards and
 * the board diverges from a batch group-by.
 */
class ShardCell<E>(
    override val ref: CellRef,
    private val keyFn: (E) -> Any?,
    initialInterest: Interest,
    private val epochAware: Boolean = true,
) : Cell, BoundedStateful, Replicable<SetDelta<E>>, Partitioned {

    private val state = TagState<E>()

    @Volatile
    private var interestField: Interest = initialInterest

    @Volatile
    private var assignedEpochField: Long = 0L

    /**
     * This shard's current key-`Interest` (PN-4). It is *snapshotted state*, not
     * a constructor constant: a recovered instance restores the interest it held
     * at checkpoint, and [PartitionedShardSet.rebuildFrom] reads it back to
     * recompute the routing table. Rebuilding the table from the constructor's
     * `initialInterest` instead (the pre-PN-4 behavior) resurrects a shed range.
     */
    val interest: Interest get() = interestField

    /** The routing epoch this shard has adopted (PN-4) — the max-register [rebuildFrom] folds. */
    val assignedEpoch: Long get() = assignedEpochField

    /** The disjoint-interest linker's receiving end — routed [RoutedCommand] slices merge here. */
    val routeInlet = registerPort("routeInlet", FanInlet.create<Propagate<RoutedCommand<E>>>())

    /**
     * The journaled control-plane channel (PN-6): an interest reassignment arrives
     * as an [Assignment] ref-addressed to this shard. Because it flows through the
     * host intake (not a direct method call), it lands in the shard host's WAL and
     * replays on recovery — so a non-checkpointed shard reconstructs the shed it
     * performed, closing PN-4's residual (an unjournaled in-process narrow). The
     * router sends here over the registry, in-process or across a bridge, exactly
     * as it sends routed slices to [routeInlet].
     */
    val assignInlet = registerPort("assignInlet", FanInlet.create<Propagate<Assignment>>())

    /**
     * The shard's effective-delta stream (PN-4): every membership change — a
     * routed slice, a gossip merge, or a shed — re-emits here, so a shard is an
     * ordinary dataflow source (partitioned+durable/replicated/pull, no longer a
     * write-only sink reachable only by the direct [membership] call).
     */
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    /**
     * Replica gossip intake (PN-4, [Replicable]): a peer instance's effective
     * deltas merge here, re-filtered to this shard's interest; only new tag
     * information re-emits, so echoes die out — the overlapping-interest
     * (sharded-replication) setting of one mesh (spec 42).
     */
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<SetDelta<E>>>())

    init {
        routeInlet.serve(Propagate<RoutedCommand<E>> { cmd -> onRouted(cmd) })
        assignInlet.serve(Propagate<Assignment> { a -> assign(a.interest, a.epoch) })
        deltaInlet.serve(Propagate<SetDelta<E>> { delta -> onGossip(delta) })
        // late-join catch-up (G-22): this shard's key-range state-as-delta-from-empty
        outlet.catchUpOnLinked { state.asDelta().takeIf { it.adds.isNotEmpty() } }
        // on-demand pull (spec 20/21 §Pull; the SetCell six-liner): a single-wave
        // state-as-delta reply, scoped to the requester's interest slice and
        // stamped as a catch-up baseline, delivered only to the requester.
        ProtocolSupport.of(outlet).handle(Protocols.StateRequest) { _, message ->
            val request = message as StateRequest
            val out = contentsSince(request.since, request.scope)
            if (out.isEmpty()) return@handle
            outlet.baselineTo(request.replyTo, currentFrontier(request.scope)) {
                propagate(SetDelta(adds = out))
            }
        }
    }

    private fun onRouted(cmd: RoutedCommand<E>) {
        // Interest guard (CP-D3): re-filter the slice to the shard's CURRENT
        // interest before merging. A stale in-flight slice for a key this shard
        // no longer owns is dropped — the new owner already holds it (replay).
        val delta = if (epochAware) (cmd.delta.within(interestField) { keyFn(it as E) } ?: return) else cmd.delta
        emit(state.apply(delta))
    }

    /** Merge a peer replica's delta (interest-scoped); re-emit exactly the new tag information. */
    private fun onGossip(delta: SetDelta<E>) {
        val scoped = if (epochAware) (delta.within(interestField) { keyFn(it as E) } ?: return) else delta
        val eff = state.apply(scoped)
        if (eff.adds.isNotEmpty() || eff.dels.isNotEmpty()) outlet.originate { propagate(eff) }
    }

    private fun emit(eff: SetDelta<E>) {
        if (eff.adds.isNotEmpty() || eff.dels.isNotEmpty()) outlet.call.propagate(eff)
    }

    /**
     * Interest reassignment (spec 42 §Interest-scoped instance sets, CP-D3):
     * adopt [newInterest] at routing [epoch] and shed every element the new
     * interest no longer admits — the moved range leaves its old owner as the
     * router replays it into the new owner, so no key is ever held by two
     * shards. A no-op shed under [epochAware] false is the control's defect.
     */
    fun assign(newInterest: Interest, epoch: Long) {
        if (epochAware) {
            val shed = state.elements.filterTo(mutableSetOf()) { !newInterest.admits(keyFn(it)) }
            if (shed.isNotEmpty()) emit(state.apply(SetDelta(dels = shed.associateWith { state.tags(it) })))
        }
        interestField = newInterest
        assignedEpochField = maxOf(assignedEpochField, epoch)
    }

    /** This shard's live key range — its contribution to the scatter-gather board. */
    fun membership(): Set<E> = state.elements

    /** This shard's full tag state as a delta-from-empty — the router's [rebuildFrom] ledger source. */
    internal fun contents(): SetDelta<E> = state.asDelta()

    /** Tags a [since] frontier has not seen, restricted to the [scope] the requester admits (the SetCell pattern). */
    private fun contentsSince(since: TagFrontier?, scope: Interest?): Map<E, Set<Timestamp>> {
        val admit: (E) -> Boolean =
            if (scope == null || scope is Interest.Total) { _ -> true } else { e -> scope.admits(keyFn(e)) }
        return state.elements.filter(admit).associateWith { e ->
            state.tags(e).filterTo(mutableSetOf()) { since == null || (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }
    }

    /** Highest tag counter per source over the [scope]-admitted keys — the reply's reported currency. */
    private fun currentFrontier(scope: Interest?): TagFrontier {
        val admit: (E) -> Boolean =
            if (scope == null || scope is Interest.Total) { _ -> true } else { e -> scope.admits(keyFn(e)) }
        val frontier = mutableMapOf<UUID, Long>()
        state.elements.filter(admit).forEach { e ->
            state.tags(e).forEach { t -> frontier.merge(t.sourceId, t.counter, ::maxOf) }
        }
        return TagFrontier(frontier)
    }

    // ---------------------------------------------------------------------
    // Bounded read (V1C-CELLS). Purely additive, and inert with respect to
    // repartitioning: it reads `state`, `interestField` and `assignedEpochField`
    // and writes none of them. It never calls [assign], `emit`, `onRouted`,
    // `onGossip`, `outlet.baselineTo`, `outlet.call` or `outlet.originate`, and
    // it is not an invocation — it arrives through neither [routeInlet] nor
    // [assignInlet] and never touches the WAL. `[24-SHARD-03]`'s rebuildFrom
    // reads the same two fields this read reads, and sees them unchanged;
    // `[24-SHARD-04]`'s recovery interest is neither shed nor re-admitted here.
    // ---------------------------------------------------------------------

    /**
     * One page of this shard's key range (V1C-CELLS).
     *
     * - **One entry** is one `(element, tags)` pair out of the shard's
     *   [civictech.cell.data.delta.TagState] — the same
     *   [SetCell.SetStateEntry] shape `SetCell` pages and `asDelta()` produces,
     *   reused deliberately so a consumer renders both set-shaped families
     *   identically. `del` tags are always empty here: this shard's tag state
     *   does not retain tombstones.
     * - **The cursor** names a position in a frozen list of *elements*
     *   ([KeyWalk]); it resumes in O(1) and survives any merge into the live
     *   ledger.
     * - **The order** is [EntryOrder]'s deterministic total order over `E`,
     *   imposed rather than inherited. The ledger is a `LinkedHashMap` whose
     *   insertion order ordinary set churn destroys — a fold that kills an
     *   element's last tag removes it, and a later re-add re-inserts it at the
     *   tail — and which `restore` discards when it refills from a `HashMap`.
     * - **`frontier` is real**: [currentFrontier], the same "highest tag counter
     *   per source over the scope-admitted keys" this shard's pull reply already
     *   reports. It is exact on the first page of a walk and on the last, and an
     *   intermediate page carries the opening stamp with
     *   [ReadCaveat.STALE_FRONTIER] — recomputing it per page is an O(n) rescan
     *   per page, the O(n²)-per-walk shape the bounded read exists to avoid, and
     *   maintaining it incrementally would put a secondary index on the fold
     *   path, which P2 forbids. This is `SetCell`'s discipline verbatim, for the
     *   same reason. Note the same limit applies: a [TagFrontier] measures tag
     *   *gains*, so a mid-walk shed (which retracts tags rather than minting
     *   them) can leave the endpoint stamps equal — equal endpoints are
     *   necessary but not sufficient here too.
     *
     * **[StatePage.attributes] carries `interest` and `assignedEpoch`, on every
     * page, and that is load-bearing rather than convenient.** A shard's
     * recoverable state is the triple `(TagState, interest, assignedEpoch)`
     * (`[24-SHARD-01]`), and [assign] can run *between* two pages of a walk: it
     * sheds every element the new interest no longer admits, then swaps the
     * interest and raises the epoch. A consumer holding page 1's interest and
     * page 7's entries would attribute entries to the wrong key range at the
     * wrong routing epoch — a partition-membership claim that was never true.
     * Carrying the pair on every page makes that error unrepresentable: a
     * consumer either sees a constant pair across the walk and may attribute, or
     * sees it change and knows the walk straddled a repartition.
     *
     * An element that is an `Owned`/`Leased` payload is never copied into a
     * page: it becomes an [ExclusiveEntry] descriptor and is counted in
     * [StatePage.exclusivesElided].
     */
    override fun readBounded(request: StateRead): StatePage {
        val scope = request.scope
        @Suppress("UNCHECKED_CAST")
        val walk = (request.cursor?.token as? KeyWalk<E>) ?: openWalk(scope)
        val order = walk.order
        val opening = walk.opening as TagFrontier

        val entries = ArrayList<Serializable>(minOf(request.limit, 64))
        var elided = 0
        var bytes = 0
        var index = walk.next
        val examineThrough = minOf(index + request.limit, order.size)
        while (index < examineThrough) {
            val element = order[index]
            index++
            if (element !in state) continue // shed or retracted since the walk opened
            if (ExclusiveEntry.isExclusive(element)) {
                entries += ExclusiveEntry.of(key = null, exclusive = element as Any)
                elided++
                bytes += PageBudget.ENTRY_OVERHEAD_BYTES
                if (PageBudget.exhausted(bytes, request.byteBudget)) break
                continue
            }
            val tags = state.tags(element).filterTo(HashSet()) {
                request.since == null || (request.since!!.perSource[it.sourceId] ?: -1L) < it.counter
            }
            if (tags.isEmpty()) continue // nothing beyond `since` for this element
            entries += SetCell.SetStateEntry(element, tags, emptySet())
            bytes += PageBudget.ENTRY_OVERHEAD_BYTES + PageBudget.TAG_BYTES * tags.size
            if (PageBudget.exhausted(bytes, request.byteBudget)) break
        }

        val complete = index >= order.size
        val isFirstPage = walk.next == 0
        return StatePage(
            entries = entries,
            next = if (complete) null else Cursor(KeyWalk(order, index, opening)),
            frontier = if (complete && !isFirstPage) currentFrontier(scope) else opening,
            exclusivesElided = elided,
            attributes = mapOf(
                "interest" to interestField,
                "assignedEpoch" to java.lang.Long.valueOf(assignedEpochField),
            ),
            caveats = if (complete || isFirstPage) emptySet() else setOf(ReadCaveat.STALE_FRONTIER),
        )
    }

    /** This shard scopes by [keyFn], exactly as its pull reply does, and carries a real tag clock. */
    override val supportsScope: Boolean get() = true
    override val supportsSince: Boolean get() = true

    /**
     * The walk's one O(n log n) pass (V1C-CELLS): impose the element order and
     * compute the opening frontier once, never per page.
     */
    private fun openWalk(scope: Interest?): KeyWalk<E> {
        val admit: (E) -> Boolean =
            if (scope == null || scope is Interest.Total) { _ -> true } else { e -> scope.admits(keyFn(e)) }
        return KeyWalk(EntryOrder.freeze(state.elements, admit), 0, currentFrontier(scope))
    }

    // snapshot/restore (PN-4): a shard's recoverable state is its tag state AND
    // its (interest, assignedEpoch) — so a checkpoint-restored shard keeps the
    // range it holds and the epoch it adopted, instead of resurrecting its
    // constructor interest and re-admitting a shed range on tail replay.
    override fun snapshot(): Serializable = arrayListOf(state.snapshot(), interestField, assignedEpochField)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val parts = state as ArrayList<Serializable>
        this.state.restore(parts[0])
        interestField = parts[1] as Interest
        assignedEpochField = parts[2] as Long
    }
}
