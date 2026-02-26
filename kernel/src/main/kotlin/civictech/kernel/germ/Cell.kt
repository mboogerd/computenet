package civictech.kernel.germ

interface Cell {
    val ref: CellRef

    /**
     * Called by a Host when this cell is hosted and ready to be activated.
     * Use this hook to establish internal logic and serve/subscribe to ports.
     */
    fun onActivate(ctx: CellContext) {}
}