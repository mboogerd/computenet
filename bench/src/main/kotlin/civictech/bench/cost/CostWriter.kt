package civictech.bench.cost

import civictech.bench.Drive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** The result of routing one point through the structural-cost admission boundary. */
sealed interface Admission {
    /** A SIM point admitted to the structural section. */
    data class Admitted(val entry: StructuralCost) : Admission

    /** A point retained only as an explained exclusion. */
    data class Excluded(val exclusion: ExcludedPoint) : Admission
}

/** The result of attempting to fit a growth law over admitted structural points. */
sealed interface FitResult {
    /** A law whose sample size and goodness of fit meet the writer's thresholds. */
    data class Law(val law: GrowthLaw) : FitResult

    /** A proposed law that could not be stated honestly. */
    data class Unfitted(
        val reason: String,
        val goodnessOfFit: Double?,
    ) : FitResult
}

/**
 * Accumulates the admitted evidence for one versioned cost-model export.
 *
 * The exhaustive [admit] branch is the honesty boundary: [PointOutcome.Ok] under SIM
 * is the only outcome that can reach [StructuralCost]. All other outcomes become
 * explicit exclusions, and [model] derives coverage from the admitted entries alone.
 */
class CostWriter(private val harnessSha: String) {
    private data class StructuralKey(
        val subject: CostSubject,
        val at: Coordinate,
    )

    private val structural = linkedMapOf<StructuralKey, StructuralCost>()
    private val calibration = linkedMapOf<String, CalibrationConstant>()
    private val laws = mutableListOf<GrowthLaw>()
    private val exclusions = mutableListOf<ExcludedPoint>()

    init {
        require(harnessSha.isNotBlank()) { "harnessSha must not be blank" }
    }

    /**
     * Admits one outcome, with exactly one structural branch: an [PointOutcome.Ok]
     * measured under [Drive.SIM].
     */
    fun admit(outcome: PointOutcome): Admission = when (outcome) {
        is PointOutcome.Ok -> admitOk(outcome)
        is PointOutcome.Unreportable -> exclude(
            outcome,
            kind = "Unreportable",
            reason = "relative dispersion ${outcome.result.relativeDispersion}",
        )
        is PointOutcome.NonConvergent -> exclude(
            outcome,
            kind = "NonConvergent",
            reason = "declared budget exhausted: ${outcome.budget}",
        )
        is PointOutcome.NonDeterministic -> exclude(
            outcome,
            kind = "NonDeterministic",
            reason = nonDeterministicReason(outcome.first, outcome.second),
        )
        is PointOutcome.Unschedulable -> exclude(
            outcome,
            kind = "Unschedulable",
            reason = "${outcome.reason}; observed limit: ${outcome.observedLimit}",
        )
        is PointOutcome.Failed -> exclude(
            outcome,
            kind = "Failed",
            reason = "${outcome.cause}; ${outcome.deadLetters.size} dead letters",
        )
    }

    /** Adds one REAL-derived calibration constant, refusing an ambiguous duplicate. */
    fun calibrate(constant: CalibrationConstant) {
        require(constant.name !in calibration) {
            "duplicate calibration constant '${constant.name}'"
        }
        calibration[constant.name] = constant
    }

    /**
     * Fits `y = a*x + b` by ordinary least squares over admitted points.
     *
     * A result below either evidence threshold remains [FitResult.Unfitted], so a poor
     * fit cannot enter the model's laws merely because its raw points were measured.
     */
    fun fit(
        dimension: String,
        subject: CostSubject,
        metric: String,
        form: GrowthForm,
        points: List<Coordinate>,
    ): FitResult {
        require(form == GrowthForm.LINEAR) { "unsupported growth form '$form'" }
        require(dimension in NUMERIC_DIMENSIONS) {
            "growth dimension '$dimension' must be one of ${NUMERIC_DIMENSIONS.joinToString()}"
        }
        require(metric in STRUCTURAL_METRICS) {
            "unknown structural metric '$metric'; expected ${STRUCTURAL_METRICS.joinToString()}"
        }

        val samples = points.map { at ->
            val entry = structural[StructuralKey(subject, at)]
                ?: throw IllegalArgumentException("no admitted entry at ($subject, $at)")
            numericDimension(at, dimension) to metric(entry.counts, metric)
        }
        if (samples.any { (_, y) -> y == null }) {
            return FitResult.Unfitted(
                reason = "metric '$metric' was not measured at every fitted coordinate",
                goodnessOfFit = null,
            )
        }
        if (samples.size < 2) {
            return FitResult.Unfitted(
                reason = "ordinary least squares requires at least two points",
                goodnessOfFit = null,
            )
        }

        val x = samples.map { it.first }
        val y = samples.map { it.second!! }
        val xMean = x.average()
        val yMean = y.average()
        val xVariation = x.sumOf { value -> squared(value - xMean) }
        if (xVariation == 0.0) {
            return FitResult.Unfitted(
                reason = "dimension '$dimension' does not vary across the fitted coordinates",
                goodnessOfFit = null,
            )
        }
        val totalVariation = y.sumOf { value -> squared(value - yMean) }
        if (totalVariation == 0.0) {
            return FitResult.Unfitted(
                reason = "metric '$metric' has no variance, so R² is undefined",
                goodnessOfFit = null,
            )
        }

        val slope = x.zip(y).sumOf { (xValue, yValue) ->
            (xValue - xMean) * (yValue - yMean)
        } / xVariation
        val intercept = yMean - slope * xMean
        val residualVariation = x.zip(y).sumOf { (xValue, yValue) ->
            squared(yValue - (slope * xValue + intercept))
        }
        val goodnessOfFit = normalizeUnitBoundary(1.0 - residualVariation / totalVariation)

        if (points.size < MIN_POINTS) {
            return FitResult.Unfitted(
                reason = "a law requires at least $MIN_POINTS points, found ${points.size}",
                goodnessOfFit = goodnessOfFit,
            )
        }
        if (goodnessOfFit < MIN_GOODNESS_OF_FIT) {
            return FitResult.Unfitted(
                reason = "R² $goodnessOfFit is below the $MIN_GOODNESS_OF_FIT threshold",
                goodnessOfFit = goodnessOfFit,
            )
        }

        val law = GrowthLaw(
            dimension = dimension,
            subject = subject,
            metric = metric,
            form = form,
            coefficients = listOf(slope, intercept),
            fittedOver = points.toList(),
            goodnessOfFit = goodnessOfFit,
        )
        laws += law
        return FitResult.Law(law)
    }

    /** Builds a snapshot whose envelope is derived solely from admitted SIM points. */
    fun model(): CostModel {
        check(structural.isNotEmpty()) { "cannot build a cost model with zero admitted points" }
        val entries = structural.values.toList()
        return CostModel(
            schemaVersion = CostSchema.VERSION,
            harnessSha = harnessSha,
            envelope = deriveEnvelope(entries),
            structural = entries,
            calibration = calibration.values.toList(),
            laws = laws.toList(),
            exclusions = exclusions.toList(),
        )
    }

    /** Creates [path]'s parent directories, writes one model snapshot, and returns it. */
    fun write(path: Path): CostModel {
        val model = model()
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, CostSchema.encode(model))
        return model
    }

    private fun admitOk(outcome: PointOutcome.Ok): Admission.Admitted {
        require(outcome.at.drive == Drive.SIM) {
            "REAL point ${outcome.at} must enter through calibrate, not structural admission"
        }
        val key = StructuralKey(outcome.subject, outcome.at)
        require(key !in structural) { "duplicate structural point $key" }
        val entry = StructuralCost(
            at = outcome.at,
            subject = outcome.subject,
            counts = outcome.counts,
            harnessSha = harnessSha,
        )
        structural[key] = entry
        return Admission.Admitted(entry)
    }

    private fun exclude(
        outcome: PointOutcome,
        kind: String,
        reason: String,
    ): Admission.Excluded {
        val exclusion = ExcludedPoint(outcome.at, outcome.subject, kind, reason)
        exclusions += exclusion
        return Admission.Excluded(exclusion)
    }

    private fun deriveEnvelope(entries: List<StructuralCost>): Envelope = Envelope(
        numeric = linkedMapOf(
            Dimensions.CARDINALITY to entries.range { it.at.cardinality.toDouble() },
            Dimensions.BATCH_SIZE to entries.range { it.at.batchSize.toDouble() },
            Dimensions.HOST_COUNT to entries.range { it.at.hostCount.toDouble() },
            Dimensions.FAN_OUT to entries.range { it.at.fanOut.toDouble() },
        ),
        categorical = linkedMapOf(
            Dimensions.SHAPE to entries.mapTo(linkedSetOf()) { it.at.shape },
            Dimensions.REPLICATION to entries.mapTo(linkedSetOf()) { it.at.replication.toString() },
            Dimensions.DURABILITY to entries.mapTo(linkedSetOf()) { it.at.durability.toString() },
        ),
    )

    private fun List<StructuralCost>.range(selector: (StructuralCost) -> Double): Envelope.Range {
        val values = map(selector)
        return Envelope.Range(values.min(), values.max())
    }

    private fun numericDimension(at: Coordinate, dimension: String): Double = when (dimension) {
        Dimensions.CARDINALITY -> at.cardinality.toDouble()
        Dimensions.BATCH_SIZE -> at.batchSize.toDouble()
        Dimensions.HOST_COUNT -> at.hostCount.toDouble()
        Dimensions.FAN_OUT -> at.fanOut.toDouble()
        else -> error("dimension was validated before extraction: $dimension")
    }

    private fun metric(counts: StructuralCounts, metric: String): Double? = when (metric) {
        "messages" -> counts.messages?.toDouble()
        "crossHostFrames" -> counts.crossHostFrames.toDouble()
        "wireBytes" -> counts.wireBytes.toDouble()
        "journalRecords" -> counts.journalRecords.toDouble()
        "simSteps" -> counts.simSteps.toDouble()
        else -> error("metric was validated before extraction: $metric")
    }

    private fun nonDeterministicReason(
        first: StructuralCounts,
        second: StructuralCounts,
    ): String {
        val differences = buildList {
            addDifference("messages", first.messages, second.messages)
            addDifference(
                "messagesNotMeasured",
                first.messagesNotMeasured,
                second.messagesNotMeasured,
            )
            addDifference("crossHostFrames", first.crossHostFrames, second.crossHostFrames)
            addDifference("wireBytes", first.wireBytes, second.wireBytes)
            addDifference("journalRecords", first.journalRecords, second.journalRecords)
            addDifference("simSteps", first.simSteps, second.simSteps)
        }
        return if (differences.isEmpty()) {
            "point was marked non-deterministic, but its two structural counts were equal"
        } else {
            "differing structural counts: ${differences.joinToString()}"
        }
    }

    private fun MutableList<String>.addDifference(name: String, first: Any?, second: Any?) {
        if (first != second) add("$name ($first != $second)")
    }

    private fun squared(value: Double): Double = value * value

    private fun normalizeUnitBoundary(value: Double): Double = when {
        abs(value) < FLOATING_POINT_TOLERANCE -> 0.0
        abs(value - 1.0) < FLOATING_POINT_TOLERANCE -> 1.0
        else -> value
    }

    companion object {
        /**
         * A line has two fitted parameters; four points leave two residual degrees of
         * freedom, the minimum evidence required before recording that law.
         */
        const val MIN_POINTS = 4

        /**
         * With at least two residual degrees of freedom, R² ≥ 0.95 requires the fitted
         * line to explain at least 95% of the measured metric's variance.
         */
        const val MIN_GOODNESS_OF_FIT = 0.95

        private const val FLOATING_POINT_TOLERANCE = 1e-12
        private val NUMERIC_DIMENSIONS = setOf(
            Dimensions.CARDINALITY,
            Dimensions.BATCH_SIZE,
            Dimensions.HOST_COUNT,
            Dimensions.FAN_OUT,
        )
        private val STRUCTURAL_METRICS = setOf(
            "messages",
            "crossHostFrames",
            "wireBytes",
            "journalRecords",
            "simSteps",
        )
    }
}
