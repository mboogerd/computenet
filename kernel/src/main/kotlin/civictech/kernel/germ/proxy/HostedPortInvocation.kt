package civictech.kernel.germ.proxy

import civictech.kernel.germ.CellRef

/**
 * An invocation on a port of a hosted cell.
 */
data class HostedPortInvocation(
    val cellRef: CellRef,
    val portName: String,
    val type: Type,
    val invocation: Invocation
) {
    enum class Type {
        /**
         * Method call on the Port's management API (e.g. linkTo, linkFrom, serve, delegate).
         */
        PORT_MANAGEMENT,

        /**
         * Method call on the Port's functional API (e.g. provide(data) for a Consumer).
         */
        PORT_API
    }
}