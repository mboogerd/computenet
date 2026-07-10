package civictech.cell.host

/**
 * Host mapping of attention to resources (spec 34, M6.3). Absent (null on
 * [ManagedHost]) the host schedules data strictly FIFO, exactly as before M6.
 */
data class AttentionPolicy(
    /**
     * Dispatch steps a cell may sit at band NONE before its pending traffic
     * parks (spec 34 decision 2 — park, never drop). Null = never park.
     * Measured in scheduling steps, not wall time, so the simulated host
     * stays deterministic.
     */
    val suspendAfter: Long? = null,
    /**
     * Fairness floor (spec 34 decision 2): after this many consecutive
     * dispatches that passed over lower-band work, the oldest lower-band
     * task runs. [Int.MAX_VALUE] disables the floor.
     */
    val stride: Int = 16,
)
