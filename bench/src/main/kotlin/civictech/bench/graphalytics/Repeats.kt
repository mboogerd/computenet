package civictech.bench.graphalytics

import kotlin.math.roundToLong

/**
 * Fold repeated runs of ONE configuration into one record ([BB-GRA-27]).
 *
 * Run-to-run variance is the relative spread `(max − min) / mean` of each phase
 * figure across [runs], the "spread of more than 10%" [VARIANCE_THRESHOLD] names;
 * the record's variance is the larger of the load and compute spreads.
 *
 * - Any [GraRecord.Failed] run makes the result that failure: a failed comparison
 *   carries no timing figure ([BB-GRA-13]), so there is nothing to average.
 * - Otherwise every run must be [GraRecord.Validated], or every run
 *   [GraRecord.MeasurementOnly], with one shared [RunHeader]. A variance above
 *   [VARIANCE_THRESHOLD] yields [GraRecord.Unreportable], recording the variance in
 *   place of a mean; at or below it, the same record kind with mean figures whose
 *   dispersion is the observed spread.
 */
fun summarise(runs: List<GraRecord>): GraRecord {
    require(runs.size >= 2) {
        "run-to-run variance needs at least two runs, got ${runs.size}"
    }
    runs.firstOrNull { it is GraRecord.Failed }?.let { return it }

    val phases = runs.map { run ->
        when (run) {
            is GraRecord.Validated -> Triple(run.header, run.load, run.compute)
            is GraRecord.MeasurementOnly -> Triple(run.header, run.load, run.compute)
            else -> throw IllegalArgumentException(
                "summarise folds Validated or MeasurementOnly runs, got ${run.status}",
            )
        }
    }
    require(runs.map { it::class }.distinct().size == 1) {
        "summarise folds runs of one record kind, got ${runs.map { it.status }.distinct()}"
    }
    val header = phases.first().first
    require(phases.all { it.first == header }) {
        "summarise folds repeats of one configuration; the run headers differ"
    }

    val load = fold(phases.map { it.second })
    val compute = fold(phases.map { it.third })
    val variance = maxOf(load.relativeSpread, compute.relativeSpread)
    if (variance > VARIANCE_THRESHOLD) return GraRecord.Unreportable(header, variance)

    return when (val first = runs.first()) {
        is GraRecord.Validated -> {
            require(runs.all { (it as GraRecord.Validated).validation == first.validation }) {
                "summarise folds runs validated under one comparison rule"
            }
            GraRecord.Validated(header, load.figure, compute.figure, first.validation)
        }
        else -> GraRecord.MeasurementOnly(header, load.figure, compute.figure)
    }
}

private class Folded(val figure: Figure, val relativeSpread: Double)

private fun fold(figures: List<Figure>): Folded {
    val values = when {
        figures.all { it is Figure.Work } -> figures.map { (it as Figure.Work).steps }
        figures.all { it is Figure.Duration } -> figures.map { (it as Figure.Duration).nanos }
        else -> throw IllegalArgumentException("cannot fold Work and Duration figures together")
    }
    val mean = values.average()
    val spread = values.max() - values.min()
    // Figures are non-negative, so a zero mean means every value is zero.
    val relativeSpread = if (mean == 0.0) 0.0 else spread / mean
    val figure = when (figures.first()) {
        is Figure.Work -> Figure.Work(steps = mean.roundToLong(), dispersionSteps = spread)
        is Figure.Duration -> Figure.Duration(nanos = mean.roundToLong(), dispersionNanos = spread)
    }
    return Folded(figure, relativeSpread)
}
