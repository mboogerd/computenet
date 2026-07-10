package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.*
import java.io.Serializable
import java.util.*

interface MapOps<K, V> {
    fun put(key: K, value: V)
    fun remove(key: K)
}

/**
 * Convergence limit (G-23, documented): unlike [SetDelta], map deltas carry no
 * causal tags — concurrent puts to the same key resolve by arrival order and
 * are not replica-stable. Fine within one FIFO stream; multi-writer key
 * conflicts need last-writer-wins tags or per-key cells before replication (42).
 */
data class MapDelta<K, V>(
    val puts: Map<K, V>,
    val removals: Set<K>
) : Serializable

interface MapApi<K, V> {
    val inlet: Use<MapOps<K, V>>
    val outlet: Subscribe<Propagate<MapDelta<K, V>>>
}

class MapCell<K, V>(override val ref: CellRef = CellRef(UUID.randomUUID())) : MapApi<K, V>, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<MapOps<K, V>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<K, V>>>())

    private val state = mutableMapOf<K, V>()

    private val inletApi = object : MapOps<K, V> {
        override fun put(key: K, value: V) {
            state[key] = value
            outlet.call.propagate(MapDelta(mapOf(key to value), emptySet()))
        }

        override fun remove(key: K) {
            state.remove(key)
            outlet.call.propagate(MapDelta(emptyMap(), setOf(key)))
        }
    }

    init {
        inlet.serve(inletApi)
        // late-join catch-up (G-22): current entries as a delta-from-empty
        outlet.linking.onLinked = { link ->
            if (state.isNotEmpty()) {
                outlet.at(link.to).propagate(MapDelta(state.toMap(), emptySet()))
            }
        }
    }

    override fun snapshot(): Serializable = HashMap(state)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        this.state.clear()
        this.state.putAll(state as Map<K, V>)
    }

    companion object {
        fun <K, V> create(): MapApi<K, V> = MapCell()
    }
}
