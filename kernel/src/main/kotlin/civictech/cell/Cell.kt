package civictech.cell

interface Cell {
    val ref: CellRef

    /**
     * Called by a Host when this cell is hosted and ready to be activated.
     * Use this hook to establish internal logic and serve/subscribe to ports.
     */
    fun onActivate(ctx: CellContext) {}

    /**
     * Called by a Host when this cell is despawned, on the host's execution
     * context, after the cell has been unregistered. Use this hook to release
     * external resources. (Unlink-before-deactivate ordering and state capture
     * arrive with the link and mobility work — 10/13, 30/33.)
     */
    fun onDeactivate(ctx: CellContext) {}
}