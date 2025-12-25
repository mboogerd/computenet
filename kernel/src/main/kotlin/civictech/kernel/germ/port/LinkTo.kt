package civictech.kernel.germ.port

/**
 * Represents a producer side of a connection that can be linked to a [LinkFrom] consumer.
 *
 * This interface acts as the universal negotiation layer for external wiring.
 * Implementations may validate or approve the link based on internal constraints
 * (e.g., cardinality or security) before establishing the connection.
 */
interface LinkTo<Api> : Port {
    /**
     * Links this port to the provided [useApi] consumer.
     */
    fun linkTo(useApi: Use<Api>)

    /**
     * Links this port to the provided [linkFrom] consumer.
     * This is the entry point for external wiring between two ports.
     */
    fun linkTo(linkFrom: LinkFrom<Api>) = linkFrom.linkFrom(this)
}