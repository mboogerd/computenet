package civictech.demo.beadsmirror.projector

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.port.Use
import java.util.concurrent.ConcurrentHashMap

/** The two durable cells that make up a beadsmirror projector fold. */
internal data class MirrorCells(
    val cell: OrMapCell<MirrorKey, String>,
    val edges: SetCell<MirrorEdge>,
)

/** Selects the concrete cell a serialized [MirrorCellFactory] materializes. */
internal enum class MirrorCellKind { MAP, EDGES }

/**
 * Ref-aware, serializable construction data for beadsmirror's projector cells.
 *
 * Topology recovery deserializes this factory and constructs a new live cell;
 * [MirrorCellCapture] briefly retains that concrete instance so the composition
 * root can attach the projector's local read-side views.
 */
internal data class MirrorCellFactory(val kind: MirrorCellKind) : CellFactory {
    override fun create(ref: CellRef): Cell = when (kind) {
        MirrorCellKind.MAP -> OrMapCell<MirrorKey, String>(ref)
        MirrorCellKind.EDGES -> SetCell<MirrorEdge>(ref)
    }.also { MirrorCellCapture.record(ref, it) }
}

/**
 * Process-local handoff from [MirrorCellFactory] to the application composition
 * root. A respawn under the same ref replaces the stale instance, and [take]
 * removes the handoff once its owner has attached.
 */
internal object MirrorCellCapture {
    private val cells = ConcurrentHashMap<CellRef, Cell>()

    fun record(ref: CellRef, cell: Cell) {
        cells[ref] = cell
    }

    fun take(ref: CellRef): Cell =
        cells.remove(ref) ?: error("beadsmirror cell factory did not materialize $ref")

    @Suppress("UNCHECKED_CAST")
    fun take(mapRef: CellRef, edgeRef: CellRef): MirrorCells {
        val map = take(mapRef) as? OrMapCell<*, *>
            ?: error("beadsmirror map factory materialized the wrong cell for $mapRef")
        val edges = take(edgeRef) as? SetCell<*>
            ?: error("beadsmirror edge factory materialized the wrong cell for $edgeRef")
        return MirrorCells(
            map as OrMapCell<MirrorKey, String>,
            edges as SetCell<MirrorEdge>,
        )
    }
}

/** Port-name view used to obtain a typed hosted proxy for either delta inlet. */
internal interface MirrorDeltaInlet<D> {
    val deltaInlet: Use<Propagate<D>>
}
