package civictech.cell.port

/**
 * Represents the production side of an input port (Inlet).
 *
 * This interface is internal to the Cell, allowing it to provide its logic by
 * serving a concrete [Api] implementation or delegating to another port.
 */
interface Serve<Api> : LinkTo<Api> {
    /**
     * Serves the port with a concrete [api] implementation.
     * This establishes the logic that will be invoked when external clients call [Use.call].
     */
    fun serve(api: Api)

    /**
     * Delegates method calls on this port to another [port].
     */
    fun delegate(port: Use<Api>)
}