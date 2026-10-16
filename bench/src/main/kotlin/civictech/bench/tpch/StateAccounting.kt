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
     * Compare balanced RF1/RF2 checkpoints with the post-LOAD baseline.
     *
     * A non-balanced plan is a control: its terminal sample is compared with LOAD, so
     * the insert-only stream used by BS-6 can prove this verdict is not vacuous.
     */
    fun growthNote(
        series: List<RetainedState>,
        plan: RefreshPlan,
    ): GrowthNote {
        require(series.size == plan.batchCount + 1) {
            "state series has ${series.size} samples for ${plan.batchCount} batches"
        }
        if (series.isEmpty()) return GrowthNote.Flat

        val balanced = plan.batches.size % 2 == 0 &&
            plan.batches.chunked(2).all { it == listOf(RefreshKind.RF1, RefreshKind.RF2) }
        val checkpoints = if (balanced) {
            (2 until series.size step 2).map(series::get)
        } else {
            listOf(series.last())
        }
        val baseline = series.first().liveRows
        checkpoints.forEach { sample ->
            (baseline.keys + sample.liveRows.keys).sorted().forEach { outlet ->
                if (baseline[outlet] != sample.liveRows[outlet]) {
                    return GrowthNote.Growing(outlet)
                }
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

/** Apply BS-6's least-squares slope and first-window spread decision rule. */
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
