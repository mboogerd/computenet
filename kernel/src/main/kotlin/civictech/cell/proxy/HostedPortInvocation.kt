package civictech.cell.proxy

import civictech.cell.CellRef
import civictech.cell.link.Link
import civictech.cell.protocol.ProtocolId

/**
 * An invocation on a port of a hosted cell.
 */
data class HostedPortInvocation(
    val cellRef: CellRef,
    val portName: String,
    val type: Type,
    val invocation: Invocation,
    /** In-process metadata-plane envelope. Wire realization is W3.2. */
    val protocolId: ProtocolId? = null,
    val protocolLink: Link? = null,
    val protocolMessage: Any? = null,
    /**
     * Transport identity of the delivery (G-29 phase 1, M8.2): stamped by a
     * bridge ingress, never serialized into frames — the receiving transport
     * knows its peer. Null = local origin.
     */
    val peer: civictech.cell.link.PeerId? = null,
) {
    enum class Type {
        /**
         * Method call on the Port's management API (e.g. linkTo, linkFrom, serve, delegate).
         */
        PORT_MANAGEMENT,

        /**
         * Method call on the Port's functional API (e.g. provide(data) for a Consumer).
         */
        PORT_API,

        /** Framework metadata delivered through ProtocolSupport, never through the data API. */
        PORT_PROTOCOL
    }
}
