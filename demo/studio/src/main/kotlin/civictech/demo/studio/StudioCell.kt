package civictech.demo.studio

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.Use
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.UUID

sealed interface StudioMsg : Serializable {
    data class Input(val fromNodeId: String, val change: String) : StudioMsg
    data class Emit(val change: String) : StudioMsg
}

@CellBase
interface StudioCellApi {
    val inlet: Use<Propagate<StudioMsg>>
    val outlet: Subscribe<Propagate<StudioMsg>>
}

/** The only node-to-node delivery path. The callback only enqueues work. */
internal class StudioCell(
    private val nodeId: String,
    initialOutput: String?,
    private val enqueue: (StudioMsg.Input) -> Unit,
) : StudioCellCellBase(CellRef(UUID.randomUUID())) {
    private var latest = initialOutput

    init {
        outlet.catchUpOnLinked { latest?.let { StudioMsg.Input(nodeId, it) } }
    }

    override fun onInlet(value: StudioMsg) {
        when (value) {
            is StudioMsg.Emit -> {
                latest = value.change
                outlet.call.propagate(StudioMsg.Input(nodeId, value.change))
            }
            is StudioMsg.Input -> enqueue(value)
        }
    }
}
