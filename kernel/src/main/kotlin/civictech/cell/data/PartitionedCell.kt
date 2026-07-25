package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.membrane.CompositeCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.replication.Interest
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

    /** Router's own tag ledger (spec 20/24): the union of every shard's live membership, tags preserved verbatim. */
    private val routed = TagState<E>()

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
        // our own ledger, purely for repartition-time replay; the incoming
        // delta is still forwarded to shards below regardless of novelty here
        routed.apply(value)

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
        shards = List(newShardCount) { newShard() }
        routingEpoch++
        routed.elements.forEach { e ->
            val tags = routed.tags(e)
            if (tags.isNotEmpty()) {
                shards[shardFor(keyFn(e))].inlet.call.propagate(SetDelta(adds = mapOf(e to tags)))
            }
        }
    }
}

/**
 * A routed shard command (spec 20/24 §Partitioned state, 40/42 §Interest-scoped
 * instance sets, CP-D3): a key-range slice of a [SetDelta] plus the versioned
 * interest-assignment [epoch] it was routed under. Crosses the wire as an
 * ordinary polymorphic argument, and its [epoch] is lifted to the
 * `WireFrame.routingEpoch` additive field so the routing epoch is observable at
 * the transport boundary. A shard whose interest no longer admits a key drops
 * the slice (a stale-epoch command re-routes), so an in-flight command crossing
 * a repartition flip neither loses nor double-counts.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("RoutedCommand")
data class RoutedCommand<E>(
    val epoch: Long,
    val delta: SetDelta<E>,
) : Serializable

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
) : Cell {

    private val state = TagState<E>()

    @Volatile
    private var interest: Interest = initialInterest

    @Volatile
    private var assignedEpoch: Long = 0L

    /** The disjoint-interest linker's receiving end — routed [RoutedCommand] slices merge here. */
    val routeInlet = registerPort("routeInlet", FanInlet.create<Propagate<RoutedCommand<E>>>())

    init {
        routeInlet.serve(Propagate<RoutedCommand<E>> { cmd -> onRouted(cmd) })
    }

    private fun onRouted(cmd: RoutedCommand<E>) {
        // Interest guard (CP-D3): re-filter the slice to the shard's CURRENT
        // interest before merging. A stale in-flight slice for a key this shard
        // no longer owns is dropped — the new owner already holds it (replay).
        val delta = if (epochAware) (cmd.delta.within(interest) { keyFn(it as E) } ?: return) else cmd.delta
        state.apply(delta)
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
            if (shed.isNotEmpty()) state.apply(SetDelta(dels = shed.associateWith { state.tags(it) }))
        }
        interest = newInterest
        assignedEpoch = maxOf(assignedEpoch, epoch)
    }

    /** This shard's live key range — its contribution to the scatter-gather board. */
    fun membership(): Set<E> = state.elements
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
) {

    /** The router's proxy view of one shard: the wire-crossing data plane plus the local control handle. */
    private class Shard<E>(
        val cell: ShardCell<E>,
        var interest: Interest,
        val route: Propagate<RoutedCommand<E>>,
    )

    /** Registry proxy shape for a shard's [ShardCell.routeInlet] (resolved by port name, in-process or bridged). */
    interface ShardRoute<E> {
        val routeInlet: Use<Propagate<RoutedCommand<E>>>
    }

    private val shards = mutableListOf<Shard<E>>()

    /** The router's total-interest state-as-delta source, for interest-reassignment replay (42). */
    private val ledger = TagState<E>()

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
        shards += Shard(cell, interest, route)
        registry.setInterest(cell.ref, interest)
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
            val slice = delta.within(shard.interest) { keyFn(it as E) } ?: return@forEach
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
            shard.cell.assign(newInterests[i], routingEpoch)
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
        val moving = predicateInterest { key -> ownerUnder(newInterests, key) != ownerUnder(old, key) }
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
            shard.cell.assign(unionInterest(old[i], newInterests[i]), routingEpoch) // widen guard only; sheds nothing
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
            shard.cell.assign(newInterests[i], routingEpoch)
        }
        flipping = false
        movingInterest = null
        pendingInterests = null
        val buffered = flipBuffer.toList()
        flipBuffer.clear()
        buffered.forEach { emit(it, routingEpoch) }
    }

    private var pendingInterests: List<Interest>? = null

    /** The index of the shard whose interest admits [key] under [interests], or null (unrouted). */
    private fun ownerUnder(interests: List<Interest>, key: Any?): Int? =
        interests.indexOfFirst { it.admits(key) }.takeIf { it >= 0 }

    /** The scatter-gather board: each shard's live key range (spec: "union of disjoint-key catch-ups"). */
    fun memberships(): List<Set<E>> = shards.map { it.cell.membership() }

    companion object {
        private fun predicateInterest(admit: (Any?) -> Boolean): Interest = object : Interest {
            override fun overlaps(other: Interest): Boolean = true
            override fun admits(key: Any?): Boolean = admit(key)
        }

        private fun unionInterest(a: Interest, b: Interest): Interest =
            predicateInterest { a.admits(it) || b.admits(it) }

        private fun complement(interest: Interest): Interest = predicateInterest { !interest.admits(it) }
    }
}
