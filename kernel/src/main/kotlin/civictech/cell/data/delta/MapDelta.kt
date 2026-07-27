package civictech.cell.data.delta

import java.io.Serializable

/**
 * Convergence limit (G-23, documented): unlike [SetDelta], map deltas carry no
 * causal tags — concurrent puts to the same key resolve by arrival order and
 * are not replica-stable. Fine within one FIFO stream; multi-writer key
 * conflicts need last-writer-wins tags or per-key cells before replication (42).
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("MapDelta")
data class MapDelta<K, V>(
    val puts: Map<K, V>,
    val removals: Set<K>
) : Serializable, civictech.cell.link.Scoped<MapDelta<K, V>> {
    /**
     * Aggregate merge path (CP-G1): fold two deltas per key with [combine]
     * instead of the single-writer last-writer-wins replace. A key present in
     * only one side carries through unchanged — **absent key = the operator's
     * identity** — and a key in both is combined. Removals union; a key both
     * put and removed resolves to removed on either side, so the fold is
     * order-independent exactly when [combine] is commutative-associative
     * (max/min/set-union — or a counted operator like `+` over disjoint keys).
     * This is what lets a [civictech.cell.replication.Replicable] group-by
     * gossip O(groups) aggregates and converge, where the plain
     * `MapCell`/`GroupByCell` `MapDelta` would lose a concurrent partial to
     * last-writer-wins.
     */
    fun merge(other: MapDelta<K, V>, combine: (V, V) -> V): MapDelta<K, V> {
        val puts = LinkedHashMap(this.puts)
        other.puts.forEach { (k, v) -> puts[k] = if (k in puts) combine(puts.getValue(k), v) else v }
        val removals = this.removals + other.removals
        return MapDelta(puts.filterKeys { it !in removals }, removals)
    }

    /**
     * Restrict this aggregate delta to the entries whose map key [interest]
     * admits (spec 42 §Interest-scoped instance sets, PN-3b): the same
     * per-emission filter [SetDelta.within] gives a set delta, now for the map
     * key space so a `Replicable` aggregate (`MergeableGroupByCell`) can be
     * interest-sliced instead of riding whole to a partial-interest peer.
     * [keyOf] projects the map key to the key the interest is scoped over
     * (identity for a replica mesh, the group key for a partitioned aggregate).
     * Returns `null` when nothing remains — the emission never rides the link.
     * Both the direct `MapCell` delta and CP-G1's [merge] path produce a
     * `MapDelta`, so both are sliceable through this one implementation.
     */
    override fun within(
        interest: civictech.cell.link.Interest,
        keyOf: (Any?) -> Any?,
    ): MapDelta<K, V>? {
        if (interest is civictech.cell.link.Interest.Total) return this
        val p = puts.filterKeys { interest.admits(keyOf(it)) }
        val r = removals.filterTo(mutableSetOf()) { interest.admits(keyOf(it)) }
        return if (p.isEmpty() && r.isEmpty()) null else MapDelta(p, r)
    }
}
