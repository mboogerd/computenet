package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

/**
 * Represents the production side of an output port (Outlet) that allows external consumers
 * to subscribe to its method calls.
 *
 * This interface is visible to external clients who wish to receive data emitted
 * by the Cell's internal logic.
 */
interface Subscribe<Api> : LinkTo<Api> {
    /**
     * Subscribes the provided [port] to receive method calls from this port.
     */
    fun subscribe(port: Use<Api>)

    /**
     * Unsubscribes the port identified by [portRef] from this port.
     */
    fun unsubscribe(portRef: PortRef)
}