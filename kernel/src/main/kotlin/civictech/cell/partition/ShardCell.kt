package civictech.cell.partition

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.Replicable
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
import civictech.cell.replication.Interest
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
) : Cell, Stateful, Replicable<SetDelta<E>>, Partitioned {

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
