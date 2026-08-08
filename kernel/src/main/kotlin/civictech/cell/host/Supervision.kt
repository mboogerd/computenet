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
 * than silently dropped, how many RESTART cycles ran, and how many invocations
 * the `Effectful` processed-frontier suppressed as already-acted. A snapshot,
 * not a live handle — read via [ManagedHost.supervisionAccounting].
 */
data class SupervisionAccounting(
    val deadLetters: Long,
    val parkedDrainedOnTeardown: Long,
    val restarts: Long,
    /**
     * KFX-20: suppressions at an `Effectful` inlet's processed-frontier
     * (G-59, C-9) — an invocation at or behind the frontier the sink had
     * already acted on, dropped rather than re-driven. Each one explicitly
     * discharged the dropped invocation's exclusive payloads (`Owned.take` /
     * `Leased.release`), so this is equally the count of discharges on that
     * path: a suppression is a drop, and spec 23 §Ownership forbids a silent
     * one. Default-valued, so the counter is purely additive.
     */
    val effectfulSuppressionsDischarged: Long = 0,
)
