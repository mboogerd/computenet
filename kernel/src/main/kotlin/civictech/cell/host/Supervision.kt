package civictech.cell.host

/**
 * Per-cell failure policy (G-26 remainder), consulted when a hosted invocation
 * throws. Every policy still emits the [DeadLetter] — observability is not a
 * policy. Configured via `HostManagementApi.supervise`.
 */
enum class SupervisionPolicy {
    /** Default: dead-letter only; the cell keeps processing subsequent invocations. */
    PROPAGATE,

    /** Deactivate → re-activate → restore the spawn-time [civictech.cell.Stateful] checkpoint. */
    RESTART,

    /** Park the cell: subsequent invocations buffer per-cell, in order, until `resume(ref)` replays them. */
    SUSPEND,
}

/**
 * Per-host counters for supervision events off the happy path (G-46):
 * how many invocations were dead-lettered, how many parked (SUSPEND or
 * attention) invocations were drained into a dead letter at teardown rather
 * than silently dropped, and how many RESTART cycles ran. A snapshot, not a
 * live handle — read via [ManagedHost.supervisionAccounting].
 */
data class SupervisionAccounting(
    val deadLetters: Long,
    val parkedDrainedOnTeardown: Long,
    val restarts: Long,
)
