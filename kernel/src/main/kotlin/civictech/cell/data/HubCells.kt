package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.util.UUID

interface SetHubApi<E> {
    val inlet: Use<Propagate<SetDelta<E>>>
}

interface MapHubApi<K, V> {
    val inlet: Use<Propagate<MapDelta<K, V>>>
}

/**
 * Sink cell folding a [SetDelta] stream into live membership via [SetView];
 * [onUpdate] fires on effective membership change only (tag churn is silent).
 */
class SetHubCell<E>(
    private val onUpdate: (Set<E>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : SetHubApi<E>, Cell {
    private val view = SetView<E>()
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())

    init {
        inlet.onEach { if (view.apply(it)) onUpdate(view.current()) }
    }
}

/**
 * Sink cell folding a [MapDelta] stream into current entries via [MapView];
 * [onUpdate] fires on effective change only (restated puts are silent).
 */
class MapHubCell<K, V>(
    private val onUpdate: (Map<K, V>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : MapHubApi<K, V>, Cell {
    private val view = MapView<K, V>()
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, V>>>())

    init {
        inlet.onEach { if (view.apply(it)) onUpdate(view.current()) }
    }
}
