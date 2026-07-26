package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.host.LocationRegistry
import civictech.cell.membrane.CompositeCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Protocols
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.StateRequest
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import civictech.cell.replication.Assignment
import civictech.cell.replication.Interest
import civictech.cell.replication.sliceTo
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import java.io.Serializable
import java.util.UUID

/**
 * Composite over key-disjoint [GroupByCell] shards (spec 20/24 §Partitioned
 * state, G-24 realized; distribution edges remain open, G-56).
 *
 * A `PartitionedCell` is one composite cell — one membrane, one logical id —
 * owning organelle [GroupByCell]s that each hold a disjoint key range.
 * External links bind THIS cell's [inlet]/[outlet]; from outside the
 * composite is indistinguishable from a single [GroupByCell] (same
 * [GroupByApi] shape), so rebalancing never re-handshakes counterparts.
 *
 * - **Routing** (the router bullet of §Partitioned state): every incoming
 *   [SetDelta] element is assigned to exactly one shard by
 *   `partitionOf(keyFn(element))` — the partitioner is total and
 *   deterministic, so elements sharing a group key always land on the same
 *   organelle (the disjointness the merge-safety proof below depends on).
 *   `GroupByCell`'s own aggregation is itself keyed by the same `keyFn`, so
 *   partitioning by the *group* key (not the raw element) is exactly what
 *   keeps each shard's key range disjoint from every other's.
 * - **Wave-transparent merge**: each shard's [MapDelta] emission is forwarded
 *   to this cell's own outlet synchronously, inside the same call frame the
 *   shard used to emit it — no wave is re-minted, no source is coalesced;
 *   each organelle outlet remains its own wave source (spec 20/22). Because
 *   ranges are disjoint, one organelle's delta only ever mentions its own
 *   keys, so merging is conflict-free union.
 * - **Late join = per-organelle catch-up union**: this cell keeps its own
 *   `merged` view (the union of every live shard aggregate) purely to serve
 *   a fresh external subscriber with one coherent delta-from-empty, mirroring
 *   [GroupByCell]'s own onLinked catch-up.
 * - **Repartition = a versioned routing table + full state-as-delta-from-
 *   empty replay** (spec 20/24 "Repartition = per-range Buffering + a
 *   versioned routing table"): [repartition] bumps [routingEpoch] and
 *   rebuilds every organelle from this cell's own tag ledger ([routed]),
 *   replaying each live element's ORIGINAL tags (never re-minted — spec
 *   20/24 §Tag continuity) into whichever shard the new partition function
 *   assigns it to. `PartitionedCell` is a single, ordinary cell dispatched
 *   like any other under the one authority lattice (30/34 decision 5:
 *   "partitions... are placements in this lattice, not exceptions to it") —
 *   [repartition] runs synchronously on this cell's own turn, so there is no
 *   concurrent command to race the routing-table flip; the deeper
 *   incremental-buffering/backpressure edges under real concurrent placement
 *   are the explicitly deferred G-56 residual, not this ticket's scope.
 * - **A membrane, not a host**: organelles are ordinary [GroupByCell]s held
 *   only as direct in-process references (never independently spawned onto a
 *   [civictech.cell.host.ManagedHost]) — [CompositeCell]'s existing
 *   hidden-by-default containment (G-28 extended to composites, 30/31
 *   §Hierarchy) already makes their containment cascade free: when this cell
 *   deactivates, its organelles simply become unreachable, with no separate
 *   host-level bookkeeping required.
 */
class PartitionedCell<E, K, A, ACC : Serializable>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    initialShardCount: Int,
    private val keyFn: (E) -> K,
    private val aggregator: Aggregator<E, A, ACC>,
    private val partitionOf: (K) -> Int = { k -> k.hashCode() },
) : CompositeCell(ref), GroupByApi<E, K, A> {

    init {
        require(initialShardCount > 0) { "shardCount must be positive, got $initialShardCount" }
    }

    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<K, A>>>())

    /** Union-of-shards catch-up view, kept for late-join replay on THIS cell's own outlet. */
    private val merged = mutableMapOf<K, A>()

    private var shards: List<GroupByCell<E, K, A, ACC>> = List(initialShardCount) { newShard() }

    /** Versioned routing table epoch (spec 20/24 "flip the table atomically and bump its epoch"). */
    var routingEpoch: Long = 0
        private set

    val shardCount: Int get() = shards.size

    private fun newShard(): GroupByCell<E, K, A, ACC> {
        val shard = GroupByCell(keyFn = keyFn, aggregator = aggregator)
        shard.outlet.subscribe(
            Use.fixed(
                object : Propagate<MapDelta<K, A>> {
                    override fun propagate(value: MapDelta<K, A>) {
                        value.puts.forEach { (k, v) -> merged[k] = v }
                        value.removals.forEach { merged.remove(it) }
                        // wave-transparent forward: same call frame, no re-origination
                        outlet.call.propagate(value)
                    }
                },
                PortRef.generate(),
            ),
        )
        return shard
    }

    private fun shardFor(key: K): Int = Math.floorMod(partitionOf(key), shards.size)

    private fun route(value: SetDelta<E>) {
        // PN-6: no router-side ledger — each element lives in exactly one shard's
        // own TagState, so the router holds O(instances) routing state and never
        // a second O(total) copy. Repartition sources its replay from the shards.
        val addsByShard = splitByShard(value.adds)
        val delsByShard = splitByShard(value.dels)
        (addsByShard.keys + delsByShard.keys).forEach { shard ->
            shards[shard].inlet.call.propagate(
                SetDelta(addsByShard[shard] ?: emptyMap(), delsByShard[shard] ?: emptyMap()),
            )
        }
    }

    private fun splitByShard(byElement: Map<E, Set<civictech.cell.Timestamp>>): Map<Int, Map<E, Set<civictech.cell.Timestamp>>> {
        val out = HashMap<Int, MutableMap<E, Set<civictech.cell.Timestamp>>>()
        byElement.forEach { (e, tags) -> out.getOrPut(shardFor(keyFn(e))) { mutableMapOf() }[e] = tags }
        return out
    }

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) = route(value)
        })
        // late-join catch-up (G-22): the union view as a delta-from-empty
        outlet.catchUpOnLinked { if (merged.isEmpty()) null else MapDelta(merged.toMap(), emptySet()) }
    }

    /**
     * Repartitions to [newShardCount] organelles (spec 20/24 "Repartition =
     * per-range Buffering + a versioned routing table"): bumps
     * [routingEpoch] and rebuilds every shard from this cell's own tag
     * ledger, replaying each live element's ORIGINAL tags (never re-minted)
     * against the new partition function. Synchronous — this cell's own
     * turn under the one authority lattice — so external links observe one
     * coherent flip, never a torn one.
     */
    fun repartition(newShardCount: Int) {
        require(newShardCount > 0) { "shardCount must be positive, got $newShardCount" }
        if (newShardCount == shards.size) return
        // Source the live state-as-delta from the OLD shards' own contents (PN-6:
        // the `routed` ledger is deleted) BEFORE replacing them — each element's
        // ORIGINAL tags, verbatim (never re-minted, spec 20/24 §Tag continuity).
        val live = shards.map { it.contents() }.fold(mutableMapOf<E, Set<Timestamp>>()) { acc, d ->
            d.adds.forEach { (e, tags) -> acc.merge(e, tags) { a, b -> a + b } }
            acc
        }
        shards = List(newShardCount) { newShard() }
        routingEpoch++
        live.forEach { (e, tags) ->
            if (tags.isNotEmpty()) {
                shards[shardFor(keyFn(e))].inlet.call.propagate(SetDelta(adds = mapOf(e to tags)))
            }
        }
    }
}

/**
 * A routed shard command (spec 20/24 §Partitioned state, 40/42 §Interest-scoped
 * instance sets, CP-D3): a key-range slice of a [SetDelta]. A shard whose
 * interest no longer admits a key drops the slice, so an in-flight command
 * crossing a repartition flip neither loses nor double-counts — admission checks
 * the shard's CURRENT interest, established by the journaled [Assignment].
 *
 * [epoch] is **deprecated (PN-6)**: it was decorative at the point of use
 * (admission never read it — the current interest is the authority), and PN-6
 * makes the assignment epoch durable on the [Assignment] lattice instead. The
 * field is retained for one release (old frames still decode); `WireCodec` no
 * longer sniffs it onto `WireFrame.routingEpoch`, and no reader consults it.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("RoutedCommand")
data class RoutedCommand<E>(
    val epoch: Long,
    val delta: SetDelta<E>,
) : Serializable

/**
 * One leg of a scatter-gather pull (PN-5, spec 20/24 §Partitioned state, 40/42
 * §Interest-scoped instance sets): the [delta] slice one [instance] shard
 * answered a pull with, plus the [frontier] that slice is current to. The
 * consumer unions the deltas into the board and retains the frontier **per
 * instance** ([civictech.cell.port.RetainedFrontiers]) — merging one scalar
 * `since` across instances silently loses each shard's non-contiguous tags.
 */
data class PullReply<E>(
    val instance: CellRef,
    val delta: SetDelta<E>,
    val frontier: TagFrontier,
)

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
) : Cell, Stateful, Replicable<SetDelta<E>> {

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

/**
 * The distributed placement of a [PartitionedCell] over an **instance set**
 * (spec 40/42 §Interest-scoped instance sets, 20/24 §Partitioned state, CP-D3):
 * shards are interest-scoped [ShardCell] instances of one logical id, each
 * hosted on a real [civictech.cell.host.ManagedHost] (possibly behind a bridge)
 * and reached over [registry]. The single-host, in-process [PartitionedCell] is
 * the degenerate placement of this same model; here the shards are genuinely
 * distributed and fed over the wire.
 *
 * The router **is** the disjoint-interest linker: for each incoming element it
 * consults the routing table — the per-shard [Interest] assignment — and routes
 * the element's slice to exactly the shard whose interest admits its group key,
 * using the same `Scoped.within` filter the gossip linker uses (CP-D2). Each
 * routed slice carries [routingEpoch], the versioned interest-assignment epoch,
 * which crosses the wire (additive `WireFrame.routingEpoch`). Because ranges are
 * disjoint, the scatter-gather union of the shards' key ranges is the coherent
 * cross-partition board with the merge function never exercised.
 *
 * [repartition] is interest reassignment, not a bespoke protocol: bump the
 * epoch, reassign each shard's [Interest] (each shard sheds the range it lost),
 * and replay the live state-as-delta into the new owners over the ordinary
 * routing path — the same machinery a re-announce drives. The [ledger] is the
 * router's own total-interest view (the sharded-replication setting, 42), kept
 * so the replay is a local state-as-delta rather than a scatter of shard-to-
 * shard pulls.
 */
class PartitionedShardSet<E>(
    private val totalSlots: Int,
    private val keyFn: (E) -> Any?,
    private val registry: LocationRegistry,
    /**
     * How a repartition applies an interest reassignment to a shard (PN-6, the
     * ticket's payoff). `true` ⇒ the assignment rides a **journaled, ref-addressed
     * hosted invocation** to the shard's [ShardCell.assignInlet] over [registry]
     * (in-process or across a bridge) — so it lands in the shard's WAL and a
     * non-checkpointed shard replays the shed on recovery (PN-4's shed is now
     * journaled-durable). `false` (the default, and control a) ⇒ a plain
     * in-process `ShardCell.assign` call — unjournaled, so a crash+replay
     * resurrects the shed range. The four PN-4/5 partition pin tests run the
     * default (direct) path unchanged.
     */
    private val journaledAssign: Boolean = false,
) {

    /** The router's proxy view of one shard: the wire-crossing data plane plus the local control handle. */
    private class Shard<E>(
        val cell: ShardCell<E>,
        var interest: Interest,
        val route: Propagate<RoutedCommand<E>>,
        val assign: Propagate<Assignment>,
    )

    /** Registry proxy shape for a shard's [ShardCell.routeInlet] (resolved by port name, in-process or bridged). */
    interface ShardRoute<E> {
        val routeInlet: Use<Propagate<RoutedCommand<E>>>
    }

    /** Registry proxy shape for a shard's [ShardCell.assignInlet] — the journaled control-plane channel (PN-6). */
    interface ShardAssign {
        val assignInlet: Use<Propagate<Assignment>>
    }

    private val shards = mutableListOf<Shard<E>>()

    /**
     * The router's repartition-replay source (PN-6, *scoped* — plan §3 "scope or
     * delete ledger"). The **routing** path ([route]) and the **pull** path
     * ([pull]) are O(instances): routing consults only the per-instance interest
     * table ([shards]); pull fans a `StateRequest` to each shard (PN-5). The
     * ledger is retained solely because a repartition replay must be *complete and
     * synchronous* even while routed slices are still in flight across a bridge to
     * a shard — a snapshot gathered from the shards' own (async-lagging) contents
     * would miss in-flight elements and lose them at the flip. It is written on
     * the write path but read by no correctness path other than the flip replay
     * (and the [pullFromLedger] control b). The fully leaderless, watermark-gated,
     * mesh-sourced replay that would let this go entirely is R1 (out of scope —
     * see spec §Interest-scoped instance sets, "single routing authority").
     */
    private var ledger = TagState<E>()

    /** Synthetic instance id for a ledger-served pull reply (the control-b answerer). */
    private val ledgerRef = CellRef(UUID.randomUUID())

    /** The versioned interest-assignment epoch (spec 20/24 "flip the table atomically and bump its epoch"). */
    var routingEpoch: Long = 0L
        private set

    // Repartition flip window (spec 20/24 §Partitioned state "per-range
    // Buffering", 93 I-19, CP-D4): while a flip is open, commands touching a
    // moving key range are buffered — parked at the router — and replayed once,
    // to the new owner, after the flip closes. Non-moving ranges flow untouched
    // (the funnel never blocks). [flipBuffered] false is the CP-D4 control.
    private var flipping = false
    private var flipBuffered = true
    private val flipBuffer = mutableListOf<SetDelta<E>>()
    private var movingInterest: Interest? = null

    val shardCount: Int get() = shards.size

    /**
     * Register a shard already spawned+published on its host (reachable via
     * [registry], in-process or over a bridge). The router opens a wire-crossing
     * data-plane proxy to its [ShardCell.routeInlet] and records the shard's
     * [Interest] in the routing table.
     */
    @Suppress("UNCHECKED_CAST")
    fun addShard(cell: ShardCell<E>, interest: Interest) {
        val route = (HostedCellProxy.create(cell.ref, registry, ShardRoute::class.java) as ShardRoute<E>).routeInlet.call
        // The journaled control-plane proxy (PN-6): assignment rides the registry
        // to the shard's assignInlet exactly as routed slices ride to routeInlet —
        // in-process or over a bridge — so it lands in the shard host's WAL.
        val assign = (HostedCellProxy.create(cell.ref, registry, ShardAssign::class.java) as ShardAssign).assignInlet.call
        shards += Shard(cell, interest, route, assign)
        registry.setInterest(cell.ref, interest)
    }

    /**
     * Apply an interest reassignment to a shard (PN-6). Journaled path: a
     * ref-addressed hosted invocation to the shard's [ShardCell.assignInlet] over
     * the registry — durable and replayed on recovery. Direct path (control a):
     * an in-process `assign` call — unjournaled, so the shed resurrects on replay.
     */
    private fun assignShard(shard: Shard<E>, interest: Interest, epoch: Long) {
        if (journaledAssign) shard.assign.propagate(Assignment(interest, epoch))
        else shard.cell.assign(interest, epoch)
    }

    /** Route [delta] to the owning shards (each slice filtered to that shard's interest), stamped with the epoch. */
    fun route(delta: SetDelta<E>) {
        ledger.apply(delta)
        val moving = movingInterest
        if (flipping && flipBuffered && moving != null) {
            // Per-range Buffering (spec 20/24, CP-D4): a command touching the
            // moving range is parked at the router (delivered once, to the new
            // owner, when the flip closes); the non-moving remainder flows now.
            val movingPart = delta.within(moving) { keyFn(it as E) }
            if (movingPart != null) flipBuffer += movingPart
            val stablePart = delta.within(complement(moving)) { keyFn(it as E) }
            if (stablePart != null) emit(stablePart, routingEpoch)
            return
        }
        emit(delta, routingEpoch)
    }

    private fun emit(delta: SetDelta<E>, epoch: Long) {
        shards.forEach { shard ->
            // the one slice-and-route primitive (PN-6) — the same [sliceTo] the
            // gossip linker uses, here with the group keyOf.
            val slice = sliceTo(delta, shard.interest) { keyFn(it as E) } ?: return@forEach
            shard.route.propagate(RoutedCommand(epoch, slice))
        }
    }

    /**
     * Repartition = interest reassignment (spec 42, 20/24 §Partitioned state,
     * CP-D3): bump the epoch, reassign each shard's [Interest] (each shard sheds
     * the range it lost), then replay the live state-as-delta into the new
     * owners over the routing path. Under the epoch guard this loses nothing and
     * double-counts nothing; the epoch-blind control skips the shed and forks
     * moved groups across two shards.
     */
    fun repartition(newInterests: List<Interest>) {
        require(newInterests.size == shards.size) { "expected ${shards.size} interests, got ${newInterests.size}" }
        routingEpoch++
        shards.forEachIndexed { i, shard ->
            shard.interest = newInterests[i]
            registry.setInterest(shard.cell.ref, newInterests[i])
            assignShard(shard, newInterests[i], routingEpoch)
        }
        emit(ledger.asDelta(), routingEpoch)
    }

    /**
     * Open a repartition flip window under concurrent placement (spec 20/24
     * §Partitioned state, 93 I-19, CP-D4). The moving key range — every key
     * whose owning shard changes between the current and [newInterests]
     * assignment — is set to Buffering ([route] parks commands touching it while
     * the window is open); the live state-as-delta of the moving range is
     * replayed into its new owners now (parking per-ref if a shard is migrating,
     * so the funnel never blocks other ranges). Each gaining shard adopts a
     * transient union of its old and new interest so it keeps serving its old
     * range and accepts the moved range during the window. Close with
     * [endRepartition].
     *
     * With [buffered] false (the CP-D4 control) the moving range is not buffered:
     * during the window a command for a moving key is routed to both its old and
     * its new owner (their interests transiently overlap) and double-counts.
     */
    fun beginRepartition(newInterests: List<Interest>, buffered: Boolean = true) {
        require(newInterests.size == shards.size) { "expected ${shards.size} interests, got ${newInterests.size}" }
        check(!flipping) { "a repartition flip is already open" }
        routingEpoch++
        flipping = true
        flipBuffered = buffered
        val old = shards.map { it.interest }
        // Moving range = keys whose owning shard changes (β's algebra, PN-4/F6):
        // a key stays put iff the SAME shard admits it in both tables, so the
        // stable set is the union of per-shard old∩new and the moving set its
        // complement — serializable and honest, replacing the anonymous
        // predicate whose `overlaps` unconditionally lied.
        val stable = Interest.Union(newInterests.indices.map { i -> Interest.Intersect(listOf(old[i], newInterests[i])) })
        val moving = Interest.Complement(stable)
        movingInterest = moving
        pendingInterests = newInterests
        // The routing table stays OLD for the whole window (routing continuity —
        // the old owner keeps serving the moving range until the flip closes);
        // only the moving range is set to Buffering. Each new owner's *guard* is
        // widened (old ∪ new) so it accepts the replay of its moved-in range, and
        // that pre-window snapshot is replayed to it now — parking per-ref if the
        // shard is migrating, so the funnel never blocks other ranges.
        val snapshot = ledger.asDelta()
        shards.forEachIndexed { i, shard ->
            assignShard(shard, unionInterest(old[i], newInterests[i]), routingEpoch) // widen guard only; sheds nothing
            val movedIn = snapshot.within(newInterests[i]) { keyFn(it as E) }?.within(moving) { keyFn(it as E) }
            if (movedIn != null) shard.route.propagate(RoutedCommand(routingEpoch, movedIn))
        }
    }

    /**
     * Close the flip window (spec 20/24 §Partitioned state, CP-D4): narrow every
     * shard to its final [Interest] (each old owner sheds the range it lost),
     * then replay the buffered moving-range commands once, in order, to their new
     * owners under the settled table. Zero loss, no double-count.
     */
    fun endRepartition() {
        check(flipping) { "no repartition flip is open" }
        val newInterests = checkNotNull(pendingInterests)
        shards.forEachIndexed { i, shard ->
            shard.interest = newInterests[i]
            registry.setInterest(shard.cell.ref, newInterests[i])
            assignShard(shard, newInterests[i], routingEpoch)
        }
        flipping = false
        movingInterest = null
        pendingInterests = null
        val buffered = flipBuffer.toList()
        flipBuffer.clear()
        buffered.forEach { emit(it, routingEpoch) }
    }

    private var pendingInterests: List<Interest>? = null

    /** The scatter-gather board: each shard's live key range (spec: "union of disjoint-key catch-ups"). */
    fun memberships(): List<Set<E>> = shards.map { it.cell.membership() }

    /**
     * Scatter-gather pull (PN-5, spec 20/24 §Partitioned state, 40/42
     * §Interest-scoped instance sets). A pull against a partitioned logical id
     * has no single answerer: the router serving it from its own [ledger] would
     * hold O(total state) at one node — the very thing partitioning exists to
     * avoid ([pullFromLedger] is that control). Instead the router **fans** a
     * `StateRequest` to every shard whose interest overlaps [scope], **over the
     * registry** — the same wire transport the write path uses ([addShard]'s
     * `HostedCellProxy`/`ShardRoute` proxy). Each shard answers with its own
     * existing PN-4 [Protocols.StateRequest] handler
     * (`outlet.baselineTo(replyTo, currentFrontier(scope))`), so a shard behind a
     * bridge is genuinely reached over that bridge and its reply genuinely
     * crosses back to [replyTo] — the router holds no direct object reference to
     * a bridged shard's state on this path (only its [CellRef], to address the
     * wire send, exactly as the write path does). The board is never materialized
     * at one node — each baseline reply carries only its shard's range.
     *
     * The reply is asynchronous (it rides a bridge): this call **fires** the
     * fan-out and returns; the requester at [replyTo] assembles the union from
     * the N per-shard baseline replies as they arrive (each a
     * [PullReply]-shaped `baselineTo` whose [civictech.cell.MessageContext.baseline]
     * is that shard's frontier — a baseline is never a wave, so a live routed
     * delta on the same subscription is not mistaken for a pull leg). The
     * consumer retains the frontier **per instance**
     * ([civictech.cell.port.RetainedFrontiers]).
     *
     * Freshness is per-shard-consistent, cross-shard-arbitrary: each leg is a
     * baseline (never a wave), and legs are independent — a shard that is
     * mid-migration ([LocationRegistry.isHeld], the funnel-hold reuse) is skipped
     * here and its leg deferred to a later pull rather than read torn.
     *
     * [sinceOf] supplies the currency to pull each instance from — the consumer's
     * **per-instance** retained frontier ([civictech.cell.port.RetainedFrontiers.sinceFor]).
     * Feeding one frontier merged across instances instead silently loses a
     * deferred shard's non-contiguous tags (control a): its counters read as
     * already-seen under a sibling's higher water.
     */
    fun pull(replyTo: PortRef, scope: Interest, sinceOf: (CellRef) -> TagFrontier?) {
        shards.forEach { shard ->
            if (!shard.interest.overlaps(scope)) return@forEach
            if (registry.isHeld(shard.cell.ref)) return@forEach // migrating — its leg defers
            // Fan the StateRequest over the wire (the write path's transport), not
            // a direct object read: replyTo names the requester's inlet, since is
            // this instance's retained currency, scope rides Total (the shard
            // already holds only its slice — see StateRequest.scope).
            Protocols.sendUpstream(
                pullEdge(shard.cell.ref, replyTo),
                Protocols.StateRequest,
                StateRequest(replyTo, sinceOf(shard.cell.ref)),
            )
        }
    }

    /**
     * A one-shot bridged link carrying a pull `StateRequest` upstream to a shard's
     * outlet (PN-5). Its `protocolBridge` routes the frame to `fromAddr` (the
     * shard outlet) through [registry] — resolving in-process or across a bridge
     * transparently, the same resolution the write proxy uses. The shard's
     * handler replies `baselineTo(replyTo)`, which reaches the requester over the
     * reverse data path its subscription established (never this link), so this
     * link needs no registration and lives only for the one send.
     */
    private fun pullEdge(shardRef: CellRef, replyTo: PortRef): WireEdgeLink =
        WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.of(shardRef, "outlet"),
            to = replyTo,
            fromAddr = PortAddress(shardRef, "outlet"),
            toAddr = PortAddress(replyTo.cell ?: shardRef, "inlet"),
            protocolCapabilities = setOf(Protocols.StateRequest),
            sink = InvocationSink(registry::deliver),
        )

    /**
     * Control (b): answer the whole pull from the router's own total-interest
     * [ledger] in one reply. Functionally green — the assembled union is
     * identical — but it materializes O(total state) at a single node, which is
     * exactly what the fan-out [pull] avoids (a partitioned cell exists so no one
     * node holds the whole board).
     */
    fun pullFromLedger(scope: Interest, since: TagFrontier?): List<PullReply<E>> {
        val admit: (E) -> Boolean =
            if (scope is Interest.Total) { _ -> true } else { e -> scope.admits(keyFn(e)) }
        // control b: answer the whole board from the router's own ledger in one
        // reply — O(total) at one node, exactly what the fan-out [pull] avoids.
        val adds = ledger.elements.filter(admit).associateWith { e ->
            ledger.tags(e).filterTo(mutableSetOf()) { since == null || (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }
        if (adds.isEmpty()) return emptyList()
        val frontier = mutableMapOf<UUID, Long>()
        adds.forEach { (_, tags) -> tags.forEach { t -> frontier.merge(t.sourceId, t.counter, ::maxOf) } }
        return listOf(PullReply(ledgerRef, SetDelta(adds = adds), TagFrontier(frontier)))
    }

    /**
     * Recover the router from its (already-recovered) shards (PN-4). The router
     * holds no durable state of its own — the routing table *is* the shards'
     * interests and the epoch *is* their max-register — so after a crash the
     * table is recomputed by asking each restored shard what interest and epoch
     * it holds. Reading each shard's *current* [ShardCell.interest] (restored
     * from its checkpoint / replayed frames under its recovered interest) is what
     * keeps a shed range shed; recomputing from the constructor `initialInterest`
     * instead resurrects it and double-counts (the PN-4 control).
     */
    fun rebuildFrom(restored: List<ShardCell<E>>) {
        shards.clear()
        ledger = TagState()
        var maxEpoch = 0L
        restored.forEach { cell ->
            addShard(cell, cell.interest)
            ledger.apply(cell.contents())
            maxEpoch = maxOf(maxEpoch, cell.assignedEpoch)
        }
        routingEpoch = maxEpoch
    }

    companion object {
        private fun unionInterest(a: Interest, b: Interest): Interest = Interest.Union(listOf(a, b))

        private fun complement(interest: Interest): Interest = Interest.Complement(interest)
    }
}
