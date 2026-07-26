package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.Use
import civictech.gen.wire.CellBase
import java.util.UUID
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta

@CellBase
interface SetHubApi<E> {
    val inlet: Use<Propagate<SetDelta<E>>>
}

@CellBase
interface MapHubApi<K, V> {
    val inlet: Use<Propagate<MapDelta<K, V>>>
}

/**
 * Sink cell folding a [SetDelta] stream into live membership via [SetView];
 * [onUpdate] fires on effective membership change only (tag churn is silent).
 */
class SetHubCell<E>(
    private val onUpdate: (Set<E>) -> Unit,
    ref: CellRef = CellRef(UUID.randomUUID()),
) : SetHubCellBase<E>(ref) {
    private val view = SetView<E>()

    override fun onInlet(value: SetDelta<E>) {
        if (view.apply(value)) onUpdate(view.current())
    }
}

/**
 * Sink cell folding a [MapDelta] stream into current entries via [MapView];
 * [onUpdate] fires on effective change only (restated puts are silent).
 */
class MapHubCell<K, V>(
    private val onUpdate: (Map<K, V>) -> Unit,
    ref: CellRef = CellRef(UUID.randomUUID()),
) : MapHubCellBase<K, V>(ref) {
    private val view = MapView<K, V>()

    override fun onInlet(value: MapDelta<K, V>) {
        if (view.apply(value)) onUpdate(view.current())
    }
}
