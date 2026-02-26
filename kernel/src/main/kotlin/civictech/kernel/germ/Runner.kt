package civictech.kernel.germ

import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation

/**
 * Interface for interacting with a [Runner].
 *
 * A Runner is a [Cell] that hosts other cells and manages their connections.
 */
interface RunnerApi {
    /**
     * Spawns a cell in the runner and activates it.
     * The cell acts as its own specification.
     */
    fun spawn(cell: Cell): CellRef

    /**
     * Connects an outlet of one hosted cell to an inlet of another hosted cell.
     *
     * @param from The reference to the source cell.
     * @param outletName The name or identifier of the outlet on the source cell.
     * @param to The reference to the target cell.
     * @param inletName The name or identifier of the inlet on the target cell.
     */
    fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String)
}

/**
 * Interface for routing API calls to inlets of hosted cells.
 */
interface RouterApi {
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
 * A Runner is a computelet (Cell) that can host and execute other cells.
 */
interface Runner : Cell {
    val managementInlet: Use<RunnerApi>
    val routerInlet: Use<RouterApi>
}
