package civictech.cell.port

import civictech.cell.port.PortRef

/**
 * A Port is a uniquely identified entry or exit point of a [Cell].
 *
 * Ports provide a type-safe API for inter-cell communication, separating the
 * logical implementation from the physical wiring of the dataflow graph.
 */
interface Port {
    /**
     * The unique identifier of this port.
     */
    val ref: PortRef
}