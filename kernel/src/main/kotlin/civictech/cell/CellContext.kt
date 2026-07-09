package civictech.cell

/**
 * Provides access to the hosting environment (Host) for a [Cell].
 */
interface CellContext {
    // For now, we keep it minimal. Future additions:
    // val hostRef: CellRef
    // fun <T> resolve(port: Port): T
}
