package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

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
     * Invokes the [block] on the [Api] instance, but only if the underlying implementation
     * matches the specified [portRef]. This is primarily used for targeted (unicast)
     * invocations, such as sending initial state to a specific new subscriber.
     */
    fun use(portRef: PortRef, block: Api.() -> Any?)

    /**
     * Invokes the [block] on the [Api] instance(s).
     * For point-to-point ports, this invokes the block on the single connected implementation.
     * For fan-out ports, this broadcasts the invocation to all connected implementations.
     */
    fun use(block: Api.() -> Any?)

    companion object Companion {
        /**
         * Creates a [Use] implementation that always returns the provided [api].
         * @param api The API instance to use.
         * @param fixedPortRef An optional [PortRef] for this consumer.
         */
        fun <Api> fixed(api: Api, fixedPortRef: PortRef? = null): Use<Api> = object : Use<Api> {
            override val ref: PortRef
                get() = fixedPortRef ?: throw IllegalArgumentException("Port has not been initialized")

            override fun use(portRef: PortRef, block: Api.() -> Any?) {
                api.takeIf { fixedPortRef == null || portRef == fixedPortRef }?.block()
            }

            override fun use(block: Api.() -> Any?) {
                api.block()
            }

            override fun linkFrom(portOut: LinkTo<Api>) {
                portOut.linkTo(this)
            }
        }
    }
}
