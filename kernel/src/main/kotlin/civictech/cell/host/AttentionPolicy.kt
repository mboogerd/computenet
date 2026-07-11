package civictech.cell.host

import civictech.cell.attention.AttentionBand

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
    /**
     * Magnitude-band dispatch (spec 34, M17): maps the largest staged
     * [civictech.cell.data.Magnitude] payload in a cell's queue to a band;
     * the cell's effective band is `max(attention band, magnitude band)` —
     * urgency joins interest at the dispatch max, a sub-priority within the
     * data region only. Boost lifetime is the pending queue (cleared when it
     * drains). Null = magnitude scheduling off (default, order identical to
     * pre-M17 hosts).
     */
    val magnitudeBands: ((Double) -> AttentionBand)? = null,
) {
    companion object {
        /** The default mapping: the attention quantizer applied to sizes. */
        val QUANTIZE: (Double) -> AttentionBand = { AttentionBand.quantize(it.toFloat()) }
    }
}
