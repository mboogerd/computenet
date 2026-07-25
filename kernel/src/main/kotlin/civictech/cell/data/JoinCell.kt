package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface JoinApi<K, V, W> {
    val left: Serve<Propagate<MapDelta<K, V>>>
    val right: Serve<Propagate<MapDelta<K, W>>>
    val outlet: Subscribe<Propagate<MapDelta<K, Pair<V, W>>>>
}

/**
 * Incremental keyed inner join over two map streams: a key appears downstream
 * while both sides hold it; either side's put refreshes the pair, either
 * side's removal retracts it. Inherits [MapDelta]'s documented convergence
 * limit (G-23): untagged, so concurrent same-key puts resolve by arrival
 * order — single-writer-per-key or single-stream inputs converge.
 */
class JoinCell<K, V, W>(ref: CellRef = CellRef(UUID.randomUUID())) : JoinCellBase<K, V, W>(ref), Stateful {
    private val leftMap = mutableMapOf<K, V>()
    private val rightMap = mutableMapOf<K, W>()

    init {
        // late-join catch-up (G-22): the current join as a delta-from-empty
        outlet.catchUpOnLinked {
            joined().takeIf { it.isNotEmpty() }?.let { MapDelta(it, emptySet()) }
        }
    }

    override fun onLeft(value: MapDelta<K, V>) {
        val puts = mutableMapOf<K, Pair<V, W>>()
        val removals = mutableSetOf<K>()
        value.puts.forEach { (k, v) ->
            leftMap[k] = v
            rightMap[k]?.let { w -> puts[k] = v to w }
        }
        value.removals.forEach { k ->
            if (leftMap.remove(k) != null && k in rightMap) removals += k
        }
        emit(puts, removals)
    }

    override fun onRight(value: MapDelta<K, W>) {
        val puts = mutableMapOf<K, Pair<V, W>>()
        val removals = mutableSetOf<K>()
        value.puts.forEach { (k, w) ->
            rightMap[k] = w
            leftMap[k]?.let { v -> puts[k] = v to w }
        }
        value.removals.forEach { k ->
            if (rightMap.remove(k) != null && k in leftMap) removals += k
        }
        emit(puts, removals)
    }

    private fun joined(): Map<K, Pair<V, W>> =
        leftMap.mapNotNull { (k, v) -> rightMap[k]?.let { w -> k to (v to w) } }.toMap()

    private fun emit(puts: Map<K, Pair<V, W>>, removals: Set<K>) {
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        } else {
            outlet.absorbAck() // a key present on only one side — ack the swallowed wave (CP-A3)
        }
    }

    override fun snapshot(): Serializable = arrayListOf(HashMap(leftMap), HashMap(rightMap))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (l, r) = state as ArrayList<Serializable>
        leftMap.clear(); leftMap.putAll(l as Map<K, V>)
        rightMap.clear(); rightMap.putAll(r as Map<K, W>)
    }
}
