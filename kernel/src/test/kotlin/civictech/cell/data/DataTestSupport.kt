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

/** Membership under the tag algebra: an element is live iff an add-tag is uncovered. */
fun tagFold(deltas: List<SetDelta<String>>): Set<String> {
    val all = deltas.fold(SetDelta<String>()) { acc, d -> acc.merge(d) }
    return all.adds.filter { (e, tags) -> (tags - (all.dels[e] ?: emptySet())).isNotEmpty() }.keys
}
