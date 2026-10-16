package civictech.bench.tpch

/** The declared fan-out multiplier for the BS-5 emission-volume measurement. */
const val INCREMENTALITY_K: Int = 8

/** The boundary of the claim: emitted volume is evidence, not an operator proof. */
const val INCREMENTALITY_NOTE: String =
    "emission-volume measurement from collector-applied delta elements; " +
        "not a proof of operator internals"

/** One quiescent RF1/RF2 interval's outlet-counter deltas. */
data class EmissionObservation(
    val batchIndex: Int,
    val kind: RefreshKind,
    val emittedElements: Map<String, Long>,
    val upstreamRowsDeleted: Long,
) {
    init {
        require(batchIndex >= 1) { "batchIndex must be positive" }
        require(emittedElements.keys.all { it.isNotBlank() }) { "outlet names must not be blank" }
        require(emittedElements.values.all { it >= 0 }) { "emitted counts must be non-negative" }
        require(upstreamRowsDeleted >= 0) { "upstreamRowsDeleted must be non-negative" }
    }
}

/** Pure classifier over counters owned by the query's outlet collectors. */
object IncrementalityAccounting {

    fun measure(
        observations: List<EmissionObservation>,
        k: Int = INCREMENTALITY_K,
    ): Incrementality {
        require(k > 0) { "k must be positive" }
        val rf2 = observations.filter { it.kind == RefreshKind.RF2 }
        if (rf2.isEmpty() || rf2.all { it.emittedElements.isEmpty() }) {
            return Incrementality.NotMeasured
        }
        rf2.forEach { observation ->
            val limit = Math.multiplyExact(k.toLong(), observation.upstreamRowsDeleted)
            observation.emittedElements.toSortedMap().forEach { (outlet, emitted) ->
                if (emitted > limit) {
                    return Incrementality.Measured(
                        incremental = false,
                        operator = outlet,
                        k = k,
                        note = INCREMENTALITY_NOTE,
                    )
                }
            }
        }
        return Incrementality.Measured(
            incremental = true,
            operator = null,
            k = k,
            note = INCREMENTALITY_NOTE,
        )
    }

    /** Difference two monotonically increasing collector-counter snapshots. */
    fun delta(
        before: Map<String, Long>,
        after: Map<String, Long>,
    ): Map<String, Long> = (before.keys + after.keys).sorted().associateWith { outlet ->
        val start = before[outlet] ?: 0L
        val end = after[outlet] ?: 0L
        require(end >= start) {
            "emission counter '$outlet' moved backwards from $start to $end"
        }
        end - start
    }
}
