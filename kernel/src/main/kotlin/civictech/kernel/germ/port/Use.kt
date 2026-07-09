package civictech.kernel.germ.port

import civictech.kernel.germ.port.PortRef

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
         * @param api The API instance to use.
         * @param fixedPortRef An optional [PortRef] for this consumer.
         */
        fun <Api : Any> fixed(api: Api, fixedPortRef: PortRef? = null): Use<Api> = object : Use<Api> {
            override val ref: PortRef
                get() = fixedPortRef ?: throw IllegalArgumentException("Port has not been initialized")

            override val call: Api = api

            override fun at(portRef: PortRef): Api = api

            override fun linkFrom(portOut: LinkTo<Api>) {
                portOut.linkTo(this)
            }
        }
    }
}

/**
 * Shorthand for [Use.call] that allows using a block to invoke methods on the port.
 */
inline fun <Api, R> Use<Api>.use(block: Api.() -> R): R = call.block()
