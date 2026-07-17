package civictech.agora.cell

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-model hub (the demo `SetHubCell` idiom): folds every claim's credence
 * stream into one map and notifies the app layer (SSE broadcast). Removed
 * claims are filtered against the service index at read time, never pruned
 * here.
 */
class GraphHubCell(
    private val onUpdate: (CellRef, Double) -> Unit = { _, _ -> },
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {

    val inlet = registerPort("inlet", FanInlet.create<Propagate<CredenceUpdate>>())

    private val credences = ConcurrentHashMap<CellRef, Double>()

    init {
        inlet.serve(object : Propagate<CredenceUpdate> {
            override fun propagate(value: CredenceUpdate) {
                credences[value.source] = value.credence
                onUpdate(value.source, value.credence)
            }
        })
    }

    fun credenceOf(ref: CellRef): Double? = credences[ref]

    fun current(): Map<CellRef, Double> = HashMap(credences)
}
