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
