package civictech.bench.series

import civictech.bench.COMBINED_ERROR_MARGIN
import civictech.bench.EffectResolution
import civictech.bench.resolveEffect

/**
 * The smallest number of QUIESCED entries a [HistoricalBand] may be formed from
 * (`computenet-b7k4`).
 *
 * **Three, and the value was fixed before any series entry existed** — this repository's
 * series file is empty at the commit that introduces it (see
 * `doc/bench/regression-series.md` for why), so there is no number this threshold could
 * have been fitted to. The reasoning is structural, not empirical:
 *
 * - **One** entry has no run-to-run spread at all. Its band half-width would be the
 *   single run's own within-run error, which is systematically the *narrower* of the two
 *   quantities (`doc/bench/findings.md`'s 2026-08-18 entry measured them as the same
 *   order for the quietest possible benchmark on a deliberately idle host; for anything
 *   real the run-to-run term is larger). A band that narrow flags ordinary re-runs as
 *   movement.
 * - **Two** entries have a spread, but it is the distance between two points, which
 *   estimates nothing: two runs that happen to land close produce an arbitrarily tight
 *   band, and two that happen to land apart produce one nothing will ever escape.
 * - **Three** is the first count at which the observed spread has a middle value to be a
 *   spread *around*, and it is what `doc/bench/findings.md`'s own `NOISE_FLOOR`
 *   derivation used for the same purpose.
 *
 * Three is a floor, not a target. A band over three entries is weak evidence and
 * [BandComparison] says so on its face by reporting [HistoricalBand.sampleCount] beside
 * every verdict.
 */
const val MIN_BAND_ENTRIES: Int = 3

/**
 * The tolerance band one measured quantity has established over its own history
 * (`computenet-b7k4`).
 *
 * A band is **not** an absolute floor and it is not a target. It is the spread this
 * benchmark has actually shown on the pinned machine, and the only claim it supports is
 * "a fresh run does / does not sit outside what this benchmark has already done here".
 *
 * @param key the measurement this band describes.
 * @param unit the unit every entry in the band agreed on.
 * @param centre the median of the contributing entries' scores. Median rather than mean
 *   because a series accumulates over months and a single pathological run — a thermal
 *   event, a scan that started mid-sweep despite the attestation — should move the centre
 *   by one position, not by its own magnitude.
 * @param runToRunHalfWidth half the observed peak-to-peak spread of the contributing
 *   scores: `(max - min) / 2`.
 * @param worstWithinRunError the largest 99.9% error bar any contributing entry reported.
 * @param sampleCount how many QUIESCED entries formed the band. Reported with every
 *   verdict, because a band over three runs and a band over thirty support very different
 *   claims and the verdict alone does not distinguish them.
 */
data class HistoricalBand(
    val key: SeriesKey,
    val unit: String,
    val centre: Double,
    val runToRunHalfWidth: Double,
    val worstWithinRunError: Double,
    val sampleCount: Int,
) {
    init {
        require(unit.isNotBlank()) { "unit must not be blank" }
        require(centre.isFinite()) { "centre must be finite, was $centre" }
        require(runToRunHalfWidth.isFinite() && runToRunHalfWidth >= 0.0) {
            "runToRunHalfWidth must be finite and non-negative, was $runToRunHalfWidth"
        }
        require(worstWithinRunError.isFinite() && worstWithinRunError >= 0.0) {
            "worstWithinRunError must be finite and non-negative, was $worstWithinRunError"
        }
        require(sampleCount >= MIN_BAND_ENTRIES) {
            "a band needs at least $MIN_BAND_ENTRIES entries, was given $sampleCount"
        }
    }

    /**
     * The band's half-width: the LARGER of [runToRunHalfWidth] and [worstWithinRunError].
     *
     * Taking the maximum rather than the sum or the run-to-run term alone is the
     * conservative choice in the direction that matters here. The two quantities measure
     * overlapping things — a run's own error bar already contains some of the variation
     * that shows up between runs — so summing them would double-count. Using only the
     * run-to-run term would make a band formed from three unusually consistent runs
     * narrower than a single run's own stated precision, which is a band claiming more
     * resolution than any of its members had.
     */
    val halfWidth: Double get() = maxOf(runToRunHalfWidth, worstWithinRunError)

    companion object {

        /**
         * Forms a band from [entries], or returns `null` when they cannot support one.
         *
         * @param entries every series entry for ONE [SeriesKey] under ONE
         *   [EnvironmentFingerprint]. Non-QUIESCED entries are filtered out here — see
         *   [HostState] for why they are recorded but do not contribute.
         * @return the band, or `null` if fewer than [MIN_BAND_ENTRIES] QUIESCED entries
         *   remain. `null` is the honest answer for a young series and is rendered as
         *   [BandVerdict.InsufficientHistory], never as "within band".
         * @throws IllegalArgumentException if [entries] disagree about the unit or the
         *   key. Both are caller errors — [SeriesComparator.compare] filters by key and
         *   fingerprint before calling — and both would produce a meaningless band rather than a
         *   wrong-looking one.
         */
        fun of(entries: List<SeriesEntry>): HistoricalBand? {
            if (entries.isEmpty()) return null
            val keys = entries.map { it.key }.distinct()
            require(keys.size == 1) {
                "a band describes one measurement; was given ${keys.size}: " +
                    keys.map { it.describe() }
            }
            val units = entries.map { it.unit }.distinct()
            require(units.size == 1) {
                "a band's entries must share one unit; was given $units for " +
                    keys.single().describe()
            }

            val contributing = entries.filter { it.hostState == HostState.QUIESCED }
            if (contributing.size < MIN_BAND_ENTRIES) return null

            val values = contributing.map { it.value }.sorted()
            return HistoricalBand(
                key = keys.single(),
                unit = units.single(),
                centre = median(values),
                runToRunHalfWidth = (values.last() - values.first()) / 2.0,
                worstWithinRunError = contributing.maxOf { it.dispersion },
                sampleCount = contributing.size,
            )
        }

        /** The middle value, or the mean of the two middle values for an even count. */
        private fun median(sorted: List<Double>): Double {
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}

/** What a fresh run's score did relative to its own history (`computenet-b7k4`). */
enum class BandVerdict {
    /**
     * There is no band yet: fewer than [MIN_BAND_ENTRIES] QUIESCED entries exist for this
     * measurement under this environment fingerprint.
     *
     * **This is not a pass.** A young series says nothing about whether a run regressed,
     * and a comparator that reported "within band" here would manufacture reassurance out
     * of an absence of data.
     */
    InsufficientHistory,

    /**
     * The difference between the run and the band's centre does not exceed their combined
     * error bars.
     *
     * What this establishes is narrow, and it is worth stating in full because the
     * temptation is to read it as "no regression": these measurements do not resolve a
     * difference. A real regression smaller than the band is invisible to it, and saying
     * otherwise would be the absolute-floor mistake `computenet-785b` corrected, in the
     * other direction.
     */
    WithinBand,

    /** The run's score is resolvably ABOVE the band. Whether that is good depends on the unit. */
    MovedHigher,

    /** The run's score is resolvably BELOW the band. Whether that is good depends on the unit. */
    MovedLower,
}

/**
 * One fresh run's result set beside the band it was judged against (`computenet-b7k4`).
 *
 * @param key the measurement.
 * @param unit the unit of [currentValue], [currentDispersion] and the band.
 * @param currentValue the fresh run's score.
 * @param currentDispersion the fresh run's 99.9% error bar.
 * @param band the band it was compared against, or `null` for
 *   [BandVerdict.InsufficientHistory].
 * @param verdict the classification.
 * @param fingerprint the environment both sides were required to share.
 */
data class BandComparison(
    val key: SeriesKey,
    val unit: String,
    val currentValue: Double,
    val currentDispersion: Double,
    val band: HistoricalBand?,
    val verdict: BandVerdict,
    val fingerprint: EnvironmentFingerprint,
) {
    /** Signed distance from the band's centre, or `null` when there is no band. */
    val delta: Double? get() = band?.let { currentValue - it.centre }

    /** The bar the [delta]'s magnitude had to exceed, or `null` when there is no band. */
    val bar: Double?
        get() = band?.let { COMBINED_ERROR_MARGIN * (currentDispersion + it.halfWidth) }

    /**
     * A one-line rendering that states the numbers the verdict was drawn from, not only
     * the verdict.
     */
    fun describe(): String {
        val current = "${currentValue} ± ${currentDispersion} $unit"
        return when (verdict) {
            BandVerdict.InsufficientHistory ->
                "${key.describe()}: INSUFFICIENT HISTORY — $current; " +
                    "fewer than $MIN_BAND_ENTRIES quiesced entries under this environment. " +
                    "No claim is made about movement."
            else -> {
                val b = band!!
                "${key.describe()}: ${verdict.name} — $current vs band " +
                    "${b.centre} ± ${b.halfWidth} $unit over ${b.sampleCount} run(s); " +
                    "delta ${delta} vs bar ${bar}"
            }
        }
    }
}

/**
 * Compares a fresh run against a benchmark's own history (`computenet-b7k4`).
 *
 * ## The criterion, and why it is not a second one
 *
 * The comparison is **claim-relative**, exactly as `computenet-785b` made every other
 * comparison in this module: the claim "this run differs from this benchmark's history"
 * is reported only when the effect exceeds the combined error bars of the two things
 * being compared. The two sides are the fresh run (with its own 99.9% error bar) and the
 * band (with [HistoricalBand.halfWidth]).
 *
 * The arithmetic is not restated here. It is
 * [civictech.bench.resolveEffect]`(effect, combinedError)` — the same function, the same
 * [COMBINED_ERROR_MARGIN], the same strictness — reached through the magnitude overload
 * because a band has no single [civictech.bench.RunEnvironment] to make it a
 * [civictech.bench.BenchResult]. "Beyond the band" is therefore *defined as* the
 * effect-size criterion applied with history on one side, rather than sitting alongside
 * it as a competing rule. If the margin is ever changed, this comparator changes with it.
 *
 * ## What it does NOT do
 *
 * It does not say whether movement is good or bad. `ops/s` moving higher is an
 * improvement; `ns/op` moving higher is a regression; and this function is not told which
 * it is holding. The verdicts are directional and unit-agnostic on purpose, and the
 * reader — or the entry appended to `doc/bench/findings.md` — supplies the interpretation.
 */
object SeriesComparator {

    /**
     * Compares [current] against the band [history] establishes for the same measurement
     * under the same environment fingerprint.
     *
     * @param current the fresh run's entry. Its own [SeriesEntry.hostState] is NOT
     *   consulted here: a run measured on a shared host is still compared, and is simply
     *   excluded from the bands later runs are judged against.
     * @param history every entry already in the series file. Entries for other
     *   measurements, or for the same measurement under a different environment, are
     *   filtered out — never averaged in.
     */
    fun compare(current: SeriesEntry, history: List<SeriesEntry>): BandComparison {
        val fingerprint = current.environmentFingerprint
        val comparable = history.filter {
            it.key == current.key &&
                it.environmentFingerprint == fingerprint &&
                it.unit == current.unit &&
                it.runId != current.runId
        }
        val band = HistoricalBand.of(comparable)
        val verdict = when {
            band == null -> BandVerdict.InsufficientHistory
            else -> {
                val effect = kotlin.math.abs(current.value - band.centre)
                val combined = current.dispersion + band.halfWidth
                when (resolveEffect(effect, combined)) {
                    EffectResolution.Unresolved -> BandVerdict.WithinBand
                    EffectResolution.Resolved ->
                        if (current.value > band.centre) BandVerdict.MovedHigher
                        else BandVerdict.MovedLower
                }
            }
        }
        return BandComparison(
            key = current.key,
            unit = current.unit,
            currentValue = current.value,
            currentDispersion = current.dispersion,
            band = band,
            verdict = verdict,
            fingerprint = fingerprint,
        )
    }

    /** [compare] over a whole run's worth of entries, in the order they were measured. */
    fun compareAll(current: List<SeriesEntry>, history: List<SeriesEntry>): List<BandComparison> =
        current.map { compare(it, history) }

    /**
     * A whole run's comparison rendered as text, movement first.
     *
     * Movement first because that is what a scheduled run exists to surface, and a report
     * that buries two moved rows under sixty unmoved ones is a report nobody reads to the
     * end. The summary line states all four counts, so an empty movement section is
     * visibly "nothing moved" rather than possibly "nothing ran".
     */
    fun report(comparisons: List<BandComparison>): String {
        val moved = comparisons.filter {
            it.verdict == BandVerdict.MovedHigher || it.verdict == BandVerdict.MovedLower
        }
        val within = comparisons.filter { it.verdict == BandVerdict.WithinBand }
        val insufficient = comparisons.filter { it.verdict == BandVerdict.InsufficientHistory }

        val lines = mutableListOf<String>()
        lines += "Series comparison: ${comparisons.size} row(s) — " +
            "${moved.size} moved, ${within.size} within band, " +
            "${insufficient.size} without sufficient history."
        if (comparisons.isNotEmpty()) {
            lines += "Environment: ${comparisons.first().fingerprint.describe()}"
        }
        lines += ""
        lines += "MOVED BEYOND BAND (${moved.size}):"
        lines += if (moved.isEmpty()) listOf("  (none)") else moved.map { "  " + it.describe() }
        lines += ""
        lines += "WITHIN BAND (${within.size}) — no difference resolved; a regression " +
            "smaller than the band is not excluded:"
        lines += if (within.isEmpty()) listOf("  (none)") else within.map { "  " + it.describe() }
        lines += ""
        lines += "NO BAND YET (${insufficient.size}) — not a pass:"
        lines += if (insufficient.isEmpty()) {
            listOf("  (none)")
        } else {
            insufficient.map { "  " + it.describe() }
        }
        return lines.joinToString("\n")
    }
}
