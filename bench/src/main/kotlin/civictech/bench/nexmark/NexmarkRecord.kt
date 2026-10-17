package civictech.bench.nexmark

import civictech.bench.RunEnvironment
import civictech.bench.micro.FanDegree
import civictech.cell.durability.BatchedFileJournal
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.Journal
import kotlin.math.roundToLong

typealias Drive = civictech.bench.Drive

/** The exact specification text carried by every Nexmark record. */
const val SPEC_REVISION: String = NexmarkSpec.SPEC_REVISION

/** The framing carried by every Nexmark record. */
const val FRAMING: String = NexmarkSpec.FRAMING

/** A relative spread above this boundary is not reportable as one figure. */
const val VARIANCE_THRESHOLD = 0.10

/** The durability and ingress labels recorded with a run. */
data class LoadModeLabel(
    val durability: String,
    val ingress: String,
) {
    init {
        require(durability.isNotBlank()) { "durability must not be blank" }
        require(ingress.isNotBlank()) { "ingress must not be blank" }
    }
}

/** Convert the selected journal and dispatch settings to the record vocabulary. */
fun loadModeLabel(journal: Journal?, dispatchBatch: Int): LoadModeLabel {
    require(dispatchBatch >= 1) { "dispatchBatch must be >= 1, was $dispatchBatch" }
    val durability = when (journal?.durability) {
        null -> "volatile"
        DurabilityClass.SYNCHRONOUS -> "WAL-bound"
        DurabilityClass.IN_MEMORY -> "in-memory"
        DurabilityClass.BATCHED -> {
            require(journal is BatchedFileJournal) {
                "BATCHED durability requires BatchedFileJournal so syncEvery can be recorded"
            }
            "batched(syncEvery=${journal.syncEvery})"
        }
    }
    val ingress = if (dispatchBatch == 1) "per-message" else "batched($dispatchBatch)"
    return LoadModeLabel(durability, ingress)
}

/** Insert and retraction costs remain separate streams in a Nexmark record. */
enum class Phase {
    INSERT,
    RETRACT,
}

/** Units used by deterministic simulation work figures. */
enum class WorkUnit {
    SCHEDULER_TASKS,
    COLLECTOR_APPLIED_DELTA_ELEMENTS,
}

/** A phase cost is either deterministic simulation work or real elapsed time. */
sealed interface Figure {
    data class Work(
        val steps: Long,
        val dispersionSteps: Long? = null,
        val unit: WorkUnit,
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

/** A cost for one numbered insert or retract batch. */
data class BatchFigure(
    val phase: Phase,
    val index: Int,
    val figure: Figure,
) {
    init {
        require(index >= 0) { "batch index must be non-negative" }
    }
}

/** Whether event generation is outside or inside the measured interval. */
enum class GenerationPlacement {
    OUTSIDE,
    INSIDE,
}

/** The state label attached to a query's retained-state series. */
sealed interface StateLabel {
    data object NOT_WINDOWED : StateLabel

    data object UNBOUNDED_STATE : StateLabel

    data class Evicting(val lateness: Long) : StateLabel {
        init {
            require(lateness >= 0) { "lateness must be non-negative" }
        }
    }
}

/** Accounting emitted by the lateness-aware path. */
data class LatenessAccounting(
    val floor: Long?,
    val droppedLate: Long,
    val refusedEvictions: Long,
    val evicted: Long,
) {
    init {
        require(droppedLate >= 0) { "droppedLate must be non-negative" }
        require(refusedEvictions >= 0) { "refusedEvictions must be non-negative" }
        require(evicted >= 0) { "evicted must be non-negative" }
    }
}

/** Public structural state; private operator ledgers are intentionally not exposed. */
data class RetainedState(
    val liveRows: Map<String, Long>,
    val foldKeys: Map<String, Long>,
    val ledgerProbe: String = "none — operator refs are not exposed by MaintainedQuery",
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
        require(ledgerProbe.isNotBlank()) { "ledgerProbe must not be blank" }
    }
}

/** Complete context for one Nexmark query run. */
data class RunHeader(
    val seed: Long,
    val scale: NexmarkScale,
    val generatorConfig: GeneratorConfig,
    val query: NexmarkQuery,
    val drive: Drive,
    val scheduler: String,
    val loadMode: LoadModeLabel,
    val env: RunEnvironment,
    val policy: LatenessPolicy?,
    val stateLabel: StateLabel,
    val stateSeries: List<RetainedState>,
    val peak: RetainedState,
    val lateness: LatenessAccounting?,
    val maxFanOut: Int,
    val deviations: List<String>,
    val specRevision: String = SPEC_REVISION,
    val framing: String = FRAMING,
    val comparedAtQuiescence: Boolean = true,
    val variance: Double? = null,
) {
    init {
        require(specRevision == SPEC_REVISION) {
            "specRevision must be '$SPEC_REVISION', was '$specRevision'"
        }
        require(framing == FRAMING) {
            "framing must be '$FRAMING', was '$framing'"
        }
        require(scheduler.isNotBlank()) { "scheduler must not be blank" }
        require(deviations.isNotEmpty()) { "deviations must not be empty" }
        require(deviations.all { it.isNotBlank() }) {
            "deviations must not contain blank entries"
        }
        require(stateSeries.isNotEmpty()) { "stateSeries must not be empty" }
        require(peak == StateAccounting.peak(stateSeries)) {
            "peak must equal StateAccounting.peak(stateSeries)"
        }
        require((lateness != null) == (policy != null)) {
            "lateness must be present exactly when policy is present"
        }
        require((stateLabel is StateLabel.Evicting) == (policy != null)) {
            "StateLabel.Evicting must be present exactly when policy is present"
        }
        if (stateLabel is StateLabel.Evicting && policy != null) {
            require(stateLabel.lateness == policy.lateness) {
                "evicting state label must carry the policy lateness"
            }
        }
        if (stateLabel == StateLabel.UNBOUNDED_STATE) {
            require(query.windowed) { "UNBOUNDED_STATE is only valid for a windowed query" }
        }
        if (stateLabel == StateLabel.NOT_WINDOWED) {
            require(!query.windowed) { "NOT_WINDOWED is only valid for a non-windowed query" }
        }
        require(maxFanOut >= 1) { "maxFanOut must be at least one" }
        require(comparedAtQuiescence) { "comparisons must be recorded at quiescence" }
        require(variance == null || variance.isFinite() && variance >= 0.0) {
            "variance must be finite and non-negative when present"
        }
    }

    val retainedState: RetainedState
        get() = stateSeries.last()

    val harnessSha: String
        get() = env.harnessCommitSha

    val fanOutCovered: Boolean
        get() = FanDegree.values().any { it.subscribers == maxFanOut }
}

/** The mutually exclusive states of a Nexmark run or coverage row. */
enum class Status {
    verified,
    failed,
    unverified,
    unreportable,
    not_expressible,
    state_bound,
}

/** A run record keeps validation state and cost figures in disjoint variants. */
sealed interface NexmarkRecord {
    val query: NexmarkQuery
    val status: Status

    /** Causes that make a numeric result unreportable. */
    sealed interface UnreportableCause {
        data class Variance(
            val variance: Double,
            val threshold: Double = VARIANCE_THRESHOLD,
        ) : UnreportableCause {
            init {
                require(variance.isFinite()) { "variance must be finite" }
                require(threshold == VARIANCE_THRESHOLD) {
                    "threshold must be VARIANCE_THRESHOLD ($VARIANCE_THRESHOLD)"
                }
                require(variance > threshold) {
                    "variance must exceed threshold ($threshold)"
                }
            }
        }

        data class GenerationOnMeasuredPath(val share: Double) : UnreportableCause {
            init {
                require(share.isFinite() && share > 0.0 && share <= 1.0) {
                    "generation share must satisfy 0 < share <= 1"
                }
            }
        }
    }

    data class Verified(
        val header: RunHeader,
        val insert: List<BatchFigure>,
        val retract: List<BatchFigure>,
        val finalDrift: Comparison.Equal,
    ) : NexmarkRecord {
        override val query: NexmarkQuery
            get() = header.query
        override val status: Status
            get() = Status.verified

        init {
            require(insert.all { it.phase == Phase.INSERT }) {
                "insert may contain only INSERT figures"
            }
            require(retract.all { it.phase == Phase.RETRACT }) {
                "retract may contain only RETRACT figures"
            }
        }
    }

    data class Failed(
        val header: RunHeader,
        val batchIndex: Int,
        val differingRows: List<RowDiff>,
    ) : NexmarkRecord {
        override val query: NexmarkQuery
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
    ) : NexmarkRecord {
        override val query: NexmarkQuery
            get() = header.query
        override val status: Status
            get() = Status.unverified

        init {
            require(reason.isNotBlank()) { "unverified reason must not be blank" }
        }
    }

    data class Unreportable(
        val header: RunHeader,
        val cause: UnreportableCause,
    ) : NexmarkRecord {
        override val query: NexmarkQuery
            get() = header.query
        override val status: Status
            get() = Status.unreportable
    }

    data class NotExpressible(
        override val query: NexmarkQuery,
        val cause: String,
    ) : NexmarkRecord {
        override val status: Status
            get() = Status.not_expressible

        init {
            require(query.tier == Tier.NOT_EXPRESSIBLE) {
                "NotExpressible requires a NOT_EXPRESSIBLE query"
            }
            require(cause.isNotBlank()) { "not-expressible cause must not be blank" }
        }
    }

    data class StateBound(
        val header: RunHeader,
        val outlet: String,
        val ceiling: Long,
        val batchIndex: Int,
    ) : NexmarkRecord {
        override val query: NexmarkQuery
            get() = header.query
        override val status: Status
            get() = Status.state_bound

        init {
            require(outlet.isNotBlank()) { "state-bound outlet must not be blank" }
            require(ceiling >= 0) { "state ceiling must be non-negative" }
            require(batchIndex >= 0) { "batch index must be non-negative" }
        }
    }
}

/** Package-level spelling for callers that do not qualify the record cause. */
typealias UnreportableCause = NexmarkRecord.UnreportableCause

/** Fold repeated runs of one query to a representative record. */
fun summarise(runs: List<NexmarkRecord>): NexmarkRecord {
    require(runs.size >= 2) {
        "run-to-run variance needs at least two runs, got ${runs.size}"
    }
    runs.firstOrNull { it is NexmarkRecord.Failed }?.let { return it }
    runs.firstOrNull { it is NexmarkRecord.Unverified }?.let { return it }
    runs.firstOrNull { it is NexmarkRecord.StateBound }?.let { return it }
    runs.firstOrNull { it !is NexmarkRecord.Verified }?.let {
        throw IllegalArgumentException("summarise folds verified runs, got ${it.status}")
    }

    val verified = runs.map { it as NexmarkRecord.Verified }
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

    val insert = foldBatchFigures(verified.map { it.insert })
    val retract = foldBatchFigures(verified.map { it.retract })
    val variance = maxOf(insert.relativeSpread, retract.relativeSpread)
    if (variance > VARIANCE_THRESHOLD) {
        return NexmarkRecord.Unreportable(
            header = first.header.copy(variance = variance),
            cause = NexmarkRecord.UnreportableCause.Variance(variance),
        )
    }

    return NexmarkRecord.Verified(
        header = first.header.copy(variance = variance),
        insert = insert.figures,
        retract = retract.figures,
        finalDrift = first.finalDrift,
    )
}

private data class FoldedBatchFigures(
    val figures: List<BatchFigure>,
    val relativeSpread: Double,
)

private data class FoldedFigure(
    val figure: Figure,
    val relativeSpread: Double,
)

private fun foldBatchFigures(batches: List<List<BatchFigure>>): FoldedBatchFigures {
    if (batches.all { it.isEmpty() }) return FoldedBatchFigures(emptyList(), 0.0)
    require(batches.all { it.size == batches.first().size }) {
        "repeated runs must have the same number of batch figures"
    }
    val folded = batches.first().indices.map { index ->
        val entries = batches.map { it[index] }
        require(entries.map { it.phase }.distinct().size == 1) {
            "repeated batch figures must retain their phase"
        }
        require(entries.map { it.index }.distinct().size == 1) {
            "repeated batch figures must retain their index"
        }
        val figure = foldFigures(entries.map { it.figure })
        BatchFigure(entries.first().phase, entries.first().index, figure.figure) to figure.relativeSpread
    }
    return FoldedBatchFigures(
        figures = folded.map { it.first },
        relativeSpread = folded.maxOfOrNull { it.second } ?: 0.0,
    )
}

private fun foldFigures(figures: List<Figure>): FoldedFigure {
    require(figures.isNotEmpty()) { "cannot fold an empty figure list" }
    require(figures.map { it::class }.distinct().size == 1) {
        "cannot fold Work and Duration figures together"
    }
    val values = figures.map {
        when (it) {
            is Figure.Work -> it.steps.toDouble()
            is Figure.Duration -> it.nanos.toDouble()
        }
    }
    val mean = values.average()
    val spread = values.max() - values.min()
    val relativeSpread = when {
        mean != 0.0 -> spread / mean
        spread == 0.0 -> 0.0
        else -> Double.POSITIVE_INFINITY
    }
    val folded = when (val first = figures.first()) {
        is Figure.Work -> {
            require(figures.filterIsInstance<Figure.Work>().map { it.unit }.distinct().size == 1) {
                "cannot fold Work figures with different units"
            }
            Figure.Work(mean.roundToLong(), spread.roundToLong(), first.unit)
        }

        is Figure.Duration -> Figure.Duration(mean.roundToLong(), spread.roundToLong())
    }
    return FoldedFigure(folded, relativeSpread)
}

private fun RunHeader.compatibleForSummary(other: RunHeader): Boolean =
    copy(seed = other.seed, variance = other.variance) == other
