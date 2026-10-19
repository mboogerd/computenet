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
     * captured sample and finishes above its post-LOAD baseline. For an alternating plan,
     * completed RF1/RF2 pair checkpoints are classified first: a population that does not
     * return to baseline is [GrowthNote.NotOutletBalanced], even when one outlet also grows
     * monotonically, unless [outletBalancedStream] declares the stream's RF2 exactly undoes its
     * RF1: then a shift is real leaked state, so [GrowthNote.Growing] is checked first and names
     * the monotone outlet. On the default stream this keeps a query-population shift under the default RF2 semantics
     * from being presented as an unqualified state-reclamation finding.
     *
     * A non-balanced plan whose rows only shrink or shift reports [GrowthNote.Flat], meaning
     * "no growth".
     */
    fun growthNote(
        series: List<RetainedState>,
        plan: RefreshPlan,
        outletBalancedStream: Boolean = false,
    ): GrowthNote {
        require(series.size == plan.batchCount + 1) {
            "state series has ${series.size} samples for ${plan.batchCount} batches"
        }
        val balanced = plan.batches.size % 2 == 0 &&
            plan.batches.chunked(2).all { it == listOf(RefreshKind.RF1, RefreshKind.RF2) }
        val outlets = series.flatMapTo(sortedSetOf()) { it.liveRows.keys }
        val growing = outlets.firstOrNull { outlet ->
            val counts = series.map { it.liveRows[outlet] ?: 0L }
            counts.last() > counts.first() &&
                counts.zipWithNext().all { (previous, current) -> previous <= current }
        }
        if (outletBalancedStream && growing != null) return GrowthNote.Growing(growing)
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
        growing?.let { return GrowthNote.Growing(it) }
        return GrowthNote.Flat
    }

    /**
     * True when [refreshes] is a strictly alternating RF1, RF2, RF1, RF2... stream in which each
     * RF2 deletes exactly the order keys of the RF1 just before it. Key equality implies the
     * pair is outlet-balanced for every query: [RefreshBatch.Rf1] requires every lineitem to
     * belong to an order of the same batch, and RF2 removes an order together with all of its
     * lineitems, so the pair restores the pre-RF1 population. An empty stream is not balanced.
     */
    fun isOutletBalanced(refreshes: List<RefreshBatch>): Boolean =
        refreshes.isNotEmpty() && refreshes.size % 2 == 0 &&
            refreshes.chunked(2).all { (insert, delete) ->
                insert is RefreshBatch.Rf1 && delete is RefreshBatch.Rf2 &&
                    insert.orders.map { it.orderKey }.sorted() == delete.orderKeys
            }

    /** Put one stream-level verdict on every sample so no detached sample loses it. */
    fun withGrowth(
        series: List<RetainedState>,
        plan: RefreshPlan,
        outletBalancedStream: Boolean = false,
    ): List<RetainedState> {
        val growth = growthNote(series, plan, outletBalancedStream)
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
