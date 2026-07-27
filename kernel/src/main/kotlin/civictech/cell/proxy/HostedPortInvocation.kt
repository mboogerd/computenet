package civictech.cell.proxy

import civictech.cell.CellRef
import civictech.cell.TagFrontier
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
    /**
     * T04 finding 7 (extended, T06 §C1a): [civictech.cell.ReplayScope]
     * captured at STAGE time by [civictech.cell.host.HostDurability.recoverFrom]
     * for a replayed frame that carries no [Invocation.context] (a root
     * frame — a mid-graph frame gets its baseline stamped directly onto its
     * context instead, `HostDurability.baselined`). Staging and delivery are
     * decoupled (delivery runs on a later, independent scheduler task), so
     * the ambient `ReplayScope` thread-local at stage time is gone by
     * delivery time; this field carries it across that gap. Never
     * serialized (like [peer]) — a frame arriving fresh from the wire is
     * never mid-replay on this host by definition. [civictech.cell.host.ManagedHost.deliver]
     * re-installs it via `ReplayScope.withSuspending` around the handler
     * call, so a spontaneous emission from that handler — even after
     * suspending and resuming on a different worker — is still marked a
     * replay baseline, not a live wave.
     */
    val replayFrontier: TagFrontier? = null,
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
