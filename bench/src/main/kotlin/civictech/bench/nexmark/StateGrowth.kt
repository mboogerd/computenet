package civictech.bench.nexmark

/** The materialized Q7 fold whose size is the number of live windows. */
const val Q7_WINDOW_OUTLET: String = "q7-windows"

/** One post-quiescence live-window observation from a Nexmark run. */
data class WindowGrowthSample(
    val batches: Int,
    val liveWindows: Long,
    val floor: Long?,
) {
    init {
        require(batches >= 0) { "batch count must be non-negative" }
        require(liveWindows >= 0) { "live-window count must be non-negative" }
    }
}

/** The state-growth conclusion used by the Q7 findings trigger. */
enum class GrowthVerdict {
    FIRES,
    RETIRES,
    INCONCLUSIVE,
}

/** Evidence and the rule that produced the Q7 state-growth conclusion. */
data class GrowthFinding(
    val verdict: GrowthVerdict,
    val unboundedSeries: List<WindowGrowthSample>,
    val evictingSeries: List<WindowGrowthSample>,
    val bound: Long?,
    val note: String,
)

/**
 * Turn the Q7 state samples in a run header into the growth-series shape.
 *
 * The run records one state sample immediately after ATTACH, then one after each
 * insert/retract batch. The Q7 fold collector's [Q7_WINDOW_OUTLET] live-row entry
 * is therefore the live-window count. [RunHeader] exposes only the final lateness
 * accounting floor, so a policy run carries that final floor on each sample rather
 * than inventing per-batch floor values that the protocol does not record.
 */
fun seriesFrom(header: RunHeader): List<WindowGrowthSample> {
    val floor = header.lateness?.floor
    return header.stateSeries.mapIndexed { index, sample ->
        WindowGrowthSample(
            batches = index,
            liveWindows = sample.liveRows.getValue(Q7_WINDOW_OUTLET),
            floor = floor,
        )
    }
}

/**
 * Classify Q7's unbounded and evicting window series.
 *
 * The envelope is `(lateness / WINDOW_MS) + 2`: the lateness horizon contributes
 * the windows whose ends have not passed the floor, one extra window contains the
 * floor, and one extra accounts for the single-batch clock lag between the newest
 * admitted event and the floor sample. This is a bound on windows, not on the
 * number of elements held inside one window, matching [24-WL-19].
 */
fun windowGrowthFinding(
    unbounded: List<WindowGrowthSample>,
    evicting: List<WindowGrowthSample>,
    policy: LatenessPolicy,
): GrowthFinding {
    val bound = policy.lateness / NexmarkSpec.WINDOW_MS + 2L

    if (unbounded.size < 3 || evicting.size < 3) {
        return GrowthFinding(
            verdict = GrowthVerdict.INCONCLUSIVE,
            unboundedSeries = unbounded,
            evictingSeries = evicting,
            bound = bound,
            note = "INCONCLUSIVE: both series need at least three samples",
        )
    }

    val unboundedGrows = unbounded.last().liveWindows > unbounded.first().liveWindows &&
        unbounded.zipWithNext().all { (previous, current) ->
            current.liveWindows >= previous.liveWindows
        }
    if (!unboundedGrows) {
        return GrowthFinding(
            verdict = GrowthVerdict.INCONCLUSIVE,
            unboundedSeries = unbounded,
            evictingSeries = evicting,
            bound = bound,
            note = "INCONCLUSIVE: unbounded series is not non-decreasing growth",
        )
    }

    val breach = evicting.firstOrNull { it.liveWindows > bound }
    if (breach != null) {
        return GrowthFinding(
            verdict = GrowthVerdict.FIRES,
            unboundedSeries = unbounded,
            evictingSeries = evicting,
            bound = bound,
            note = "FIRES: evicting series exceeds bound $bound at batch ${breach.batches}",
        )
    }

    return GrowthFinding(
        verdict = GrowthVerdict.RETIRES,
        unboundedSeries = unbounded,
        evictingSeries = evicting,
        bound = bound,
        note = "RETIRES: unbounded series grows while evicting series stays within bound $bound",
    )
}
