package civictech.kernel.data

import civictech.kernel.germ.port.*

interface MapOps<K, V> {
    fun put(key: K, value: V)
    fun remove(key: K)
}

data class MapDelta<K, V>(
    val puts: Map<K, V>,
    val removals: Set<K>
)

interface MapApi<K, V> {
    val inlet: Use<MapOps<K, V>>
    val outlet: Subscribe<Propagate<MapDelta<K, V>>>
}

class MapCell<K, V> : MapApi<K, V> {
    override val inlet = FanInlet.create<MapOps<K, V>>()
    override val outlet = FanOutlet.create<Propagate<MapDelta<K, V>>>()

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
    }

    companion object {
        fun <K, V> create(): MapApi<K, V> = MapCell()
    }
}
