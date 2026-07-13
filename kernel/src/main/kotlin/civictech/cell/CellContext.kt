package civictech.cell

/**
 * Provides access to the hosting environment (Host) for a [Cell].
 */
interface CellContext {
    // For now, we keep it minimal. Future additions:
    // val hostRef: CellRef
    // fun <T> resolve(port: Port): T

    /**
     * Schedules [block] on the host queue (spec 21 §Fusion; cycle-head
     * re-origination barrier, 93 I-6) — the one decided fusion exception: a
     * `CycleHead`'s re-origination MUST enqueue even when co-hosted, instead
     * of nesting as a direct call, bounding stack depth to O(1) per lap.
     * Default runs [block] inline — correct for bare/unhosted cell tests;
     * [civictech.cell.host.ManagedHost] overrides with a genuine queue submit.
     */
    fun enqueueBarrier(block: () -> Unit) = block()
}
