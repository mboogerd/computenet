package civictech.kernel.germ

/**
 * Provides access to the hosting environment (Runner) for a [Cell].
 */
interface CellContext {
    // For now, we keep it minimal. Future additions:
    // val runnerRef: CellRef
    // fun <T> resolve(port: Port): T
}
