package civictech.cell.port

/**
 * Represents a consumer side of a connection that can receive a link from a [LinkTo] producer.
 *
 * This interface acts as the universal negotiation layer for external wiring.
 * Implementations may validate or approve the link before establishing the connection.
 */
interface LinkFrom<Api> : Port {
    /**
     * Establishes a link from the provided [portOut] producer to this port.
     * This is typically called by [LinkTo.linkTo] during the wiring process.
     */
    fun linkFrom(portOut: LinkTo<Api>)
}