package civictech.bench.tpch

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import kotlin.math.roundToLong

/** The exact specification text carried by every TPC-H record. */
const val SPEC_REVISION = "TPC Benchmark H Standard Specification Revision 3.0.1"

/** The honest scope of this benchmark slice; it is not a TPC-H result claim. */
const val FRAMING =
    "incremental view maintenance (DBToaster framing); not TPC-H's audited execution model; " +
        "no QphH@Size, no price/performance, no compliance claim"

/** A relative spread above this boundary is not reportable as one representative figure. */
const val VARIANCE_THRESHOLD = 0.10

/** The load durability and ingress labels recorded with a run. */
data class LoadModeLabel(
    val durability: String,
    val ingress: String,
) {
    init {
        require(durability.isNotBlank()) { "durability must not be blank" }
        require(ingress.isNotBlank()) { "ingress must not be blank" }
    }
}

/** RF1 inserts and RF2 deletes are separate cost streams in the record. */
enum class RefreshKind {
    RF1,
    RF2,
}

/**
 * The refresh schedule carried by a record.
 *
 * [batches] is intentionally a sequence, not just a count: alternating RF1/RF2
 * has different semantics from a schedule that happens to contain the same number
 * of each kind.
 */
data class RefreshPlan(
    val seed: Long,
    val sf: Double,
    val ordersPerRefresh: Int = (sf * 1_500.0).roundToLong().toInt().coerceAtLeast(1),
    val batches: List<RefreshKind>,
) {
    init {
        require(sf.isFinite() && sf > 0.0) { "sf must be finite and positive" }
        require(ordersPerRefresh > 0) { "ordersPerRefresh must be positive" }
    }

    val batchCount: Int
        get() = batches.size

    companion object {
        fun alternating(
            seed: Long,
            sf: Double,
            batches: Int = 20,
        ): RefreshPlan {
            require(batches >= 0) { "batches must be non-negative" }
            return RefreshPlan(
                seed = seed,
                sf = sf,
                batches = List(batches) { index ->
                    if (index % 2 == 0) RefreshKind.RF1 else RefreshKind.RF2
                },
            )
        }
    }
}

/** A phase cost is either deterministic SIM work or REAL elapsed time. */
sealed interface Figure {
    data class Work(
        val steps: Long,
        val dispersionSteps: Long? = null,
    ) : Figure {
        init {
            require(steps >= 0) { "simulation work steps must be non-negative" }
            require(dispersionSteps == null || dispersionSteps >= 0) {
                "simulation work dispersionSteps must be non-negative"
            }
        }
    }

    data class Duration(
        val nanos: Long,
        val dispersionNanos: Long? = null,
    ) : Figure {
        init {
            require(nanos >= 0) { "duration nanos must be non-negative" }
            require(dispersionNanos == null || dispersionNanos >= 0) {
                "duration dispersionNanos must be non-negative"
            }
        }
    }
}

/** A cost for one numbered refresh batch; RF1 and RF2 never share a list. */
data class BatchFigure(
    val kind: RefreshKind,
    val index: Int,
    val figure: Figure,
) {
    init {
        require(index >= 0) { "batch index must be non-negative" }
    }
}

/** A row whose reference comparison differed, retained as a typed comparison detail. */
data class RowDiff(
    val key: List<Any>,
    val column: String,
    val expected: Long?,
    val actual: Long?,
)

/** Exact comparison results shared by the reference and run-record layers. */
sealed interface Comparison {
    data object Equal : Comparison

    data class Differs(val rows: List<RowDiff>) : Comparison
}

/** The comparison state that is measurable but not a proof about operator internals. */
sealed interface Incrementality {
    data object NotMeasured : Incrementality

    data class Measured(
        val incremental: Boolean,
        val operator: String?,
        val k: Int,
        val note: String,
    ) : Incrementality {
        init {
            require(k > 0) { "incrementality k must be positive" }
            require(note.isNotBlank()) { "incrementality note must not be blank" }
            if (!incremental) {
                require(!operator.isNullOrBlank()) {
                    "a non-incremental measurement must name its operator"
                }
            }
        }
    }
}

/** A note about live state after the balanced refresh stream. */
sealed interface GrowthNote {
    data object Flat : GrowthNote

    data class Growing(val structure: String) : GrowthNote {
        init {
            require(structure.isNotBlank()) { "growth structure must not be blank" }
        }
    }
}

/** Public structural state; private operator ledgers do not get smuggled into it. */
data class RetainedState(
    val liveRows: Map<String, Long>,
    val foldKeys: Map<String, Long>,
    val growth: GrowthNote,
    val snapshotBytes: Long? = null,
    val ledgerProbe: String = "none",
) {
    init {
        require(liveRows.keys.all { it.isNotBlank() }) {
            "live-row outlet names must not be blank"
        }
        require(foldKeys.keys.all { it.isNotBlank() }) {
            "fold names must not be blank"
        }
        require(liveRows.values.all { it >= 0 }) {
            "live-row counts must be non-negative"
        }
        require(foldKeys.values.all { it >= 0 }) {
            "fold key counts must be non-negative"
        }
        require(snapshotBytes == null || snapshotBytes >= 0) {
            "snapshot bytes must be non-negative when present"
        }
        require(ledgerProbe.isNotBlank()) { "ledgerProbe must not be blank" }
    }
}

/** Complete context for one query run. */
data class RunHeader(
    val seed: Long,
    val sf: Double,
    val query: TpchQuery,
    val refreshPlan: RefreshPlan,
    val drive: Drive,
    val scheduler: String,
    val loadMode: LoadModeLabel,
    val env: RunEnvironment,
    val retainedState: RetainedState,
    val stateSeries: List<RetainedState> = listOf(retainedState),
    val specRevision: String = SPEC_REVISION,
    val framing: String = FRAMING,
    val tier: TpchTier = query.tier,
    val route: String = "hand",
    val generatorDeviations: List<String> = listOf(
        "comment columns: v-strings, not §4.2.2.14 pseudo text",
    ),
    val incremental: Incrementality = Incrementality.NotMeasured,
    val variance: Double? = null,
) {
    init {
        require(specRevision == SPEC_REVISION) {
            "specRevision must be '$SPEC_REVISION', was '$specRevision'"
        }
        require(framing == FRAMING) {
            "framing must be '$FRAMING', was '$framing'"
        }
        require(sf.isFinite() && sf > 0.0) { "sf must be finite and positive" }
        require(tier == query.tier) {
            "tier $tier does not match ${query.name}'s tier ${query.tier}"
        }
        require(route == "hand") {
            "route must be 'hand' in this slice, was '$route'"
        }
        require(scheduler.isNotBlank()) { "scheduler must not be blank" }
        require(generatorDeviations.isNotEmpty()) {
            "generatorDeviations must name at least one deviation"
        }
        require(generatorDeviations.all { it.isNotBlank() }) {
            "generatorDeviations must not contain blank entries"
        }
        require(variance == null || variance.isFinite() && variance >= 0.0) {
            "variance must be finite and non-negative when present"
        }
        require(stateSeries.isNotEmpty()) { "stateSeries must include the post-LOAD sample" }
        require(stateSeries.last() == retainedState) {
            "retainedState must be the terminal stateSeries sample"
        }
    }

    /** The harness identity is carried by the measured environment, not duplicated. */
    val harnessSha: String
        get() = env.harnessCommitSha
}

/** The mutually exclusive states of a TPC-H run or coverage row. */
enum class Status {
    verified,
    blocked,
    not_expressible,
    failed,
    unreportable,
    unverified,
}

/** A record keeps validation state and cost figures in disjoint variants. */
sealed interface TpchRecord {
    val query: TpchQuery
    val status: Status

    data class Verified(
        val header: RunHeader,
        val load: Figure,
        val rf1: List<BatchFigure>,
        val rf2: List<BatchFigure>,
        val finalDrift: Comparison.Equal,
    ) : TpchRecord {
        override val query: TpchQuery
            get() = header.query
        override val status: Status
            get() = Status.verified

        init {
            require(rf1.all { it.kind == RefreshKind.RF1 }) {
                "rf1 may contain only RF1 figures"
            }
            require(rf2.all { it.kind == RefreshKind.RF2 }) {
                "rf2 may contain only RF2 figures"
            }
        }
    }

    data class Failed(
        val header: RunHeader,
        val batchIndex: Int,
        val differingRows: List<RowDiff>,
    ) : TpchRecord {
        override val query: TpchQuery
            get() = header.query
        override val status: Status
            get() = Status.failed

        init {
            require(batchIndex >= 0) { "batch index must be non-negative" }
        }
    }

    data class Unverified(
        val header: RunHeader,
        val reason: String,
    ) : TpchRecord {
        override val query: TpchQuery
            get() = header.query
        override val status: Status
            get() = Status.unverified

        init {
            require(reason.isNotBlank()) { "unverified reason must not be blank" }
        }
    }

    data class Unreportable(
        val header: RunHeader,
        val variance: Double,
        val threshold: Double = VARIANCE_THRESHOLD,
    ) : TpchRecord {
        override val query: TpchQuery
            get() = header.query
        override val status: Status
            get() = Status.unreportable

        init {
            require(variance.isFinite() && variance >= 0.0) {
                "variance must be finite and non-negative"
            }
            require(threshold == VARIANCE_THRESHOLD) {
                "threshold must be VARIANCE_THRESHOLD ($VARIANCE_THRESHOLD)"
            }
            require(variance > threshold) {
                "variance must exceed threshold ($threshold)"
            }
        }
    }

    data class Blocked(
        override val query: TpchQuery,
        val cause: String,
    ) : TpchRecord {
        override val status: Status
            get() = Status.blocked

        init {
            require(cause.isNotBlank()) { "blocked cause must not be blank" }
        }
    }

    data class NotExpressible(
        override val query: TpchQuery,
        val cause: String,
    ) : TpchRecord {
        override val status: Status
            get() = Status.not_expressible

        init {
            require(cause.isNotBlank()) { "not-expressible cause must not be blank" }
        }
    }
}

/**
 * Fold repeated runs of one query. A failed or skipped comparison remains that
 * state; only verified runs can produce a figure-bearing summary.
 */
fun summarise(runs: List<TpchRecord>): TpchRecord {
    require(runs.size >= 2) {
        "run-to-run variance needs at least two runs, got ${runs.size}"
    }
    runs.firstOrNull { it is TpchRecord.Failed }?.let { return it }
    runs.firstOrNull { it is TpchRecord.Unverified }?.let { return it }
    runs.firstOrNull { it !is TpchRecord.Verified }?.let {
        throw IllegalArgumentException("summarise folds verified runs, got ${it.status}")
    }

    val verified = runs.map { it as TpchRecord.Verified }
    val first = verified.first()
    require(verified.all { it.query == first.query }) {
        "summarise folds repeats of one query"
    }
    require(verified.all { it.header.compatibleForSummary(first.header) }) {
        "summarise folds repeats of one configuration"
    }
    require(verified.all { it.finalDrift == first.finalDrift }) {
        "summarise folds repeats with one final comparison result"
    }

    val load = foldFigures(verified.map { it.load })
    val rf1 = foldBatchFigures(verified.map { it.rf1 })
    val rf2 = foldBatchFigures(verified.map { it.rf2 })
    val variance = maxOf(load.relativeSpread, rf1.relativeSpread, rf2.relativeSpread)
    if (variance > VARIANCE_THRESHOLD) {
        return TpchRecord.Unreportable(first.header, variance)
    }

    return TpchRecord.Verified(
        header = first.header,
        load = load.figure,
        rf1 = rf1.figures,
        rf2 = rf2.figures,
        finalDrift = first.finalDrift,
    )
}

private data class FoldedFigure(
    val figure: Figure,
    val relativeSpread: Double,
)

private data class FoldedBatchFigures(
    val figures: List<BatchFigure>,
    val relativeSpread: Double,
)

private fun foldFigures(figures: List<Figure>): FoldedFigure {
    require(figures.isNotEmpty()) { "cannot fold an empty figure list" }
    val values = figures.map { figure ->
        when (figure) {
            is Figure.Work -> figure.steps.toDouble()
            is Figure.Duration -> figure.nanos.toDouble()
        }
    }
    val kinds = figures.map { it::class }.distinct()
    require(kinds.size == 1) { "cannot fold Work and Duration figures together" }
    val mean = values.average()
    val spread = values.max() - values.min()
    val folded = when (figures.first()) {
        is Figure.Work -> Figure.Work(mean.roundToLong(), spread.roundToLong())
        is Figure.Duration -> Figure.Duration(mean.roundToLong(), spread.roundToLong())
    }
    return FoldedFigure(folded, if (mean == 0.0) 0.0 else spread / mean)
}

private fun foldBatchFigures(batches: List<List<BatchFigure>>): FoldedBatchFigures {
    if (batches.all { it.isEmpty() }) return FoldedBatchFigures(emptyList(), 0.0)
    require(batches.all { it.size == batches.first().size }) {
        "repeated runs must have the same number of batch figures"
    }
    val folded = batches.first().indices.map { index ->
        val entries = batches.map { it[index] }
        require(entries.map { it.kind }.distinct().size == 1) {
            "repeated batch figures must retain their refresh kind"
        }
        val figure = foldFigures(entries.map { it.figure })
        BatchFigure(entries.first().kind, entries.first().index, figure.figure) to figure.relativeSpread
    }
    return FoldedBatchFigures(
        figures = folded.map { it.first },
        relativeSpread = folded.maxOfOrNull { it.second } ?: 0.0,
    )
}

private fun RunHeader.compatibleForSummary(other: RunHeader): Boolean =
    copy(
        seed = other.seed,
        refreshPlan = refreshPlan.copy(seed = other.refreshPlan.seed),
        variance = other.variance,
    ) == other
