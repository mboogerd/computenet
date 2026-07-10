package civictech.cell

/**
 * Color markers (spec 32, G-3). A cell declares 🔵/🟣 so spawn can validate
 * placement; an unmarked cell is 🟢 pure and spawns on any host color — its
 * "coercion" is placement, not an adapter.
 */

/** May block (file IO, JDBC, locks); only spawns on a [civictech.cell.host.HostColor.BLOCKING] host. */
interface BlockingCell : Cell

/** May suspend (Kotlin coroutines); only spawns on a [civictech.cell.host.HostColor.SUSPENDING] host. */
interface SuspendingCell : Cell
