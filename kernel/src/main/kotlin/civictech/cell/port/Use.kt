package civictech.cell.port

import civictech.cell.link.LinkResult
import civictech.cell.link.PortLink
import civictech.cell.port.PortRef

/**
 * Represents the consumption side of a port.
 *
 * For an **Inlet**, this interface is mostly visible to external clients who wish to
 * push data into the Cell.
 * For an **Outlet**, this interface is internal to the Cell, providing the mechanism
 * to emit data to downstream subscribers.
 */
interface Use<Api> : LinkFrom<Api> {
    /**
     * Provides static method access to the port's API.
     * Calls on this object are dispatched according to the port's connectivity
     * (e.g. broadcast for fan-out ports).
     */
    val call: Api

    /**
     * Returns an [Api] instance that targets a specific [portRef].
     * Useful for unicast messages in a fan-out scenario.
     */
    fun at(portRef: PortRef): Api

    companion object {
        /**
         * Creates a [Use] implementation that always returns the provided [api].
         * The endpoint retains [api]'s identity internally so an outlet that
         * attaches it without a link handshake can mark the target as
         * bypass-fed for source-provenance classification.
         * @param api The API instance to use.
         * @param fixedPortRef An optional [PortRef] for this consumer.
         */
        fun <Api : Any> fixed(api: Api, fixedPortRef: PortRef? = null): Use<Api> = object : FixedUse<Api> {
            override val fixedApi: Api = api

            override val ref: PortRef
                get() = fixedPortRef ?: throw IllegalArgumentException("Port has not been initialized")

            override val call: Api = fixedApi

            override fun at(portRef: PortRef): Api = api

            // ad-hoc endpoint: no handshake state; installs and accepts
            override fun linkFrom(portOut: LinkTo<Api>): LinkResult {
                portOut.linkTo(this)
                return LinkResult.Connected(PortLink(portOut.ref, ref) {
                    (portOut as? Subscribe<Api>)?.unsubscribe(ref)
                })
            }
        }
    }
}

/** Internal witness that an ad-hoc [Use.fixed] endpoint delegates to [fixedApi]. */
internal interface FixedUse<Api : Any> : Use<Api> {
    val fixedApi: Api
}

/**
 * Shorthand for [Use.call] that allows using a block to invoke methods on the port.
 */
inline fun <Api, R> Use<Api>.use(block: Api.() -> R): R = call.block()
