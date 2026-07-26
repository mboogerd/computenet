package civictech.cell.port

import civictech.cell.link.LinkResult

/**
 * Represents a consumer side of a connection that can receive a link from a [LinkTo] producer.
 *
 * This interface acts as the universal negotiation layer for external wiring.
 * Implementations may validate or approve the link before establishing the connection.
 */
interface LinkFrom<Api> : Port {
    /**
     * Establishes a link from the provided [portOut] producer to this port,
     * running the target-side handshake (policies → cardinality → onLink).
     * This is typically called by [LinkTo.linkTo] during the wiring process.
     *
     * Returns null when the outcome is unobservable (cross-host proxies) —
     * [LinkTo.linkTo] maps that to [LinkResult.Deferred].
     */
    fun linkFrom(portOut: LinkTo<Api>): LinkResult?
}