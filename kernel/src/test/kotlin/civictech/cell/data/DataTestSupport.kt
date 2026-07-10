package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.util.*

/** Records tagged set deltas in arrival order. */
class CollectorCell(
    val arrivals: MutableList<SetDelta<String>> = mutableListOf(),
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<String>>>))

    init {
        inlet.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                arrivals += value
            }
        })
    }
}

interface DeltaInletProxy {
    val inlet: Use<Propagate<SetDelta<String>>>
}

/** Fold a single-writer MapDelta stream: puts then removals, in arrival order. */
fun <K, V> mapFold(deltas: List<MapDelta<K, V>>): Map<K, V> {
    val out = mutableMapOf<K, V>()
    deltas.forEach { d ->
        out.putAll(d.puts)
        d.removals.forEach { out.remove(it) }
    }
    return out
}

/** Membership under the tag algebra: an element is live iff an add-tag is uncovered. */
fun <E> tagFold(deltas: List<SetDelta<E>>): Set<E> {
    val all = deltas.fold(SetDelta<E>()) { acc, d -> acc.merge(d) }
    return all.adds.filter { (e, tags) -> (tags - (all.dels[e] ?: emptySet())).isNotEmpty() }.keys
}
