package civictech.bench.tpch

import civictech.bench.tpch.queries.MaintainedQuery

/** Structural state accounting available without opening private operator ledgers. */
object StateAccounting {

    /** Capture live outlet rows and the key count of every exposed aggregate column. */
    fun capture(query: MaintainedQuery): RetainedState {
        val view = query.read()
        val prefix = query.query.name.lowercase()
        return RetainedState(
            liveRows = query.liveRows(),
            foldKeys = view.columns.associateTo(linkedMapOf()) { column ->
                "$prefix-${column.replace('_', '-')}" to view.rows.size.toLong()
            },
            growth = GrowthNote.Flat,
            ledgerProbe = "none — operator refs are not exposed by MaintainedQuery",
        )
    }

    /**
     * Classify live-row growth over the stream's relevant checkpoints.
     *
     * [GrowthNote.Growing] means that the named outlet is non-decreasing across every
     * captured sample and finishes above its post-LOAD baseline. For a balanced plan,
     * completed RF1/RF2 pair checkpoints distinguish a genuinely flat stream from one
     * whose outlet population merely shifts; the expected intra-pair rise and fall does
     * not make a paired stream non-flat.
     *
     * Caveat: under the default (not outlet-balanced) RF2, a monotone population shift of
     * correctly maintained rows also meets the [GrowthNote.Growing] rule and pre-empts
     * [GrowthNote.NotOutletBalanced] (Q3 SF 0.001 seed 7: `q03-order-lines` 32 -> 37), so a
     * per-record `Growing` is G-42 evidence only from a paired stream. A non-balanced plan
     * whose rows only shrink or shift reports [GrowthNote.Flat], meaning "no growth".
     */
    fun growthNote(
        series: List<RetainedState>,
        plan: RefreshPlan,
    ): GrowthNote {
        require(series.size == plan.batchCount + 1) {
            "state series has ${series.size} samples for ${plan.batchCount} batches"
        }
        val balanced = plan.batches.size % 2 == 0 &&
            plan.batches.chunked(2).all { it == listOf(RefreshKind.RF1, RefreshKind.RF2) }
        val outlets = series.flatMapTo(sortedSetOf()) { it.liveRows.keys }
        outlets.firstOrNull { outlet ->
            val counts = series.map { it.liveRows[outlet] ?: 0L }
            counts.last() > counts.first() &&
                counts.zipWithNext().all { (previous, current) -> previous <= current }
        }?.let { return GrowthNote.Growing(it) }

        if (balanced) {
            val baseline = series.first().liveRows
            val checkpoints = (2 until series.size step 2).map(series::get)
            outlets.firstOrNull { outlet ->
                checkpoints.any { sample ->
                    (sample.liveRows[outlet] ?: 0L) != (baseline[outlet] ?: 0L)
                }
            }?.let { outlet ->
                return GrowthNote.NotOutletBalanced(outlet)
            }
        }
        return GrowthNote.Flat
    }

    /** Put one stream-level verdict on every sample so no detached sample loses it. */
    fun withGrowth(
        series: List<RetainedState>,
        plan: RefreshPlan,
    ): List<RetainedState> {
        val growth = growthNote(series, plan)
        return series.map { it.copy(growth = growth) }
    }
}

/** One retained-heap sample from a balanced stream rebuilt to [refreshes]. */
data class HeapGrowthSample(
    val refreshes: Int,
    val retainedBytes: Long,
    val liveRowsFlat: Boolean,
)

enum class GrowthVerdict {
    FIRES,
    RETIRES,
    INCONCLUSIVE,
}

data class HeapGrowthFinding(
    val verdict: GrowthVerdict,
    val growth: GrowthNote,
    val slopeBytesPerRefresh: Double?,
    val firstWindowSpreadBytes: Long?,
)

/**
 * Apply BS-6's least-squares slope and first-window spread decision rule.
 *
 * Interpretation: the task text compares "slope > 2x spread", which mixes bytes/refresh
 * with bytes. This compares the slope projected over the fit window (bytes) with twice
 * the first-window spread (bytes). The literal per-refresh comparison would RETIRE far
 * more readily, so a verdict quoted from here carries this rule, not the literal one.
 */
fun heapGrowthFinding(samples: List<HeapGrowthSample>): HeapGrowthFinding {
    val ordered = samples.sortedBy(HeapGrowthSample::refreshes)
    val firstWindow = ordered.filter { it.refreshes <= 1_000 }
    val fit = ordered.filter { it.refreshes >= 1_000 }
    if (firstWindow.size < 2 || fit.size < 3 || ordered.any { !it.liveRowsFlat }) {
        return HeapGrowthFinding(GrowthVerdict.INCONCLUSIVE, GrowthNote.Flat, null, null)
    }
    val xMean = fit.map { it.refreshes.toDouble() }.average()
    val yMean = fit.map { it.retainedBytes.toDouble() }.average()
    val denominator = fit.sumOf { sample ->
        val dx = sample.refreshes - xMean
        dx * dx
    }
    if (denominator == 0.0) {
        return HeapGrowthFinding(GrowthVerdict.INCONCLUSIVE, GrowthNote.Flat, null, null)
    }
    val slope = fit.sumOf { sample ->
        (sample.refreshes - xMean) * (sample.retainedBytes - yMean)
    } / denominator
    val spread = firstWindow.maxOf(HeapGrowthSample::retainedBytes) -
        firstWindow.minOf(HeapGrowthSample::retainedBytes)
    val projectedGrowth = slope * (fit.last().refreshes - fit.first().refreshes)
    return if (slope > 0.0 && projectedGrowth > 2.0 * spread) {
        HeapGrowthFinding(
            GrowthVerdict.FIRES,
            GrowthNote.Growing("SetCell tombstones / tag state (G-42 territory)"),
            slope,
            spread,
        )
    } else {
        HeapGrowthFinding(GrowthVerdict.RETIRES, GrowthNote.Flat, slope, spread)
    }
}
