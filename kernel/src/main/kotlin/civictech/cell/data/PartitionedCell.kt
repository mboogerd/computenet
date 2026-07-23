package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.membrane.CompositeCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
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
