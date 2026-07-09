package civictech.cell.port

/**
 * Represents a producer side of a connection that can be linked to a [LinkFrom] consumer.
 *
 * This interface acts as the universal negotiation layer for external wiring.
 * Implementations may validate or approve the link based on internal constraints
 * (e.g., cardinality or security) before establishing the connection.
 */
interface LinkTo<Api> : Port {
    /**
     * Links this port to the provided [useApi] consumer (ad-hoc style, no handshake).
     */
    fun linkTo(useApi: Use<Api>)

    /**
     * Links this port to the provided [linkFrom] consumer through the target-side
     * handshake (spec 13). The entry point for external wiring between two ports.
     * Returns [LinkResult.Deferred] when the target is a cross-host proxy.
     */
    fun linkTo(linkFrom: LinkFrom<Api>): LinkResult = linkFrom.linkFrom(this) ?: LinkResult.Deferred
}