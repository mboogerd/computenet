package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import java.util.UUID

/**
 * Sink cell folding a [SetDelta] stream into live membership via [SetView];
 * [onUpdate] fires on effective membership change only (tag churn is silent).
 */
class SetHubCell<E>(
    private val onUpdate: (Set<E>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    private val view = SetView<E>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())

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
) : Cell {
    private val view = MapView<K, V>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, V>>>())

    init {
        inlet.onEach { if (view.apply(it)) onUpdate(view.current()) }
    }
}
