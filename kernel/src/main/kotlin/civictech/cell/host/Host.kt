package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.LinkResult
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation

/**
 * Interface for interacting with a [Host].
 *
 * A Host is a [Cell] that hosts other cells and manages their connections.
 */
interface HostManagementApi {
    /**
     * Spawns a cell in the host and activates it.
     * The cell acts as its own specification.
     */
    fun spawn(cell: Cell): CellRef

    /**
     * Returns a managed reference to the API of a hosted cell.
     */
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T?

    /**
     * Unregisters a hosted cell and calls its [Cell.onDeactivate] on the
     * host's execution context. Subsequent invocations for the ref dead-letter.
     */
    fun despawn(ref: CellRef)

    /**
     * Connects an outlet of one hosted cell to an inlet of another hosted cell.
     *
     * @param from The reference to the source cell.
     * @param outletName The name or identifier of the outlet on the source cell.
     * @param to The reference to the target cell.
     * @param inletName The name or identifier of the inlet on the target cell.
     * @return the handshake outcome ([LinkResult.Rejected] is returned, not thrown)
     */
    fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String): LinkResult

    /**
     * Connects an outlet of a hosted cell to a remote inlet (represented by a [Use] instance).
     */
    fun connect(from: CellRef, outletName: String, to: Use<*>)
}

/**
 * Shorthand for [HostManagementApi.lookup] using reified types.
 */
inline fun <reified T : Any> HostManagementApi.lookup(ref: CellRef): T? = lookup(ref, T::class.java)

/**
 * Interface for routing API calls to inlets of hosted cells.
 */
interface HostRoutingApi {
    /**
     * Routes an [Invocation] to a specific inlet of a hosted cell.
     *
     * @param target The reference to the target cell.
     * @param inletName The name of the inlet on the target cell.
     * @param invocation The [Invocation] to apply to the inlet.
     */
    fun route(target: CellRef, inletName: String, invocation: Invocation)
}

/**
 * A Host is a computelet (Cell) that can host and execute other cells.
 */
interface Host : Cell {
    val managementInlet: Use<HostManagementApi>
    val routerInlet: Use<HostRoutingApi>
}
