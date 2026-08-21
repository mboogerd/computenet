package civictech.bench.micro

import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The `@Tag("bench")` entry point that renders `FanOutScalingBenchmark`'s BATCH
 * fixed-state sweep (`simBatchFixedState`/`realBatchFixedState`, computenet-2scd) into a
 * `doc/bench/findings.md` entry, states how many segments per drive resolve against their
 * own 99.9% error bar, and states whether the 2026-08-19 fan-out entry's "linear in
 * degree, not worse" reading survives at fixed state size.
 *
 * ## Why a second driver rather than a flag on [FanOutFixedStateRenderTest]
 *
 * [FanOutFixedStateRenderTest] is the artifact by which the 2026-08-20 entry's numbers
 * remain re-derivable: anyone re-running it against that entry's retained results file
 * must get that entry's table back. Editing it to serve a second sweep — even additively —
 * puts that re-derivability at the mercy of a later refactor, for no gain. The fit and
 * segment arithmetic below is therefore deliberately duplicated rather than extracted, and
 * is byte-for-byte the same arithmetic, so the two sweeps' tables are comparable by
 * construction and not merely by intention.
 *
 * ## When this criterion was authored — the whole point of this driver's commit order
 *
 * computenet-252t's verdict gate was tightened AFTER its numbers were visible (`7eda317c`),
 * and an earlier cut read a different verdict word on the same data. That was disclosed in
 * the entry and judged non-disqualifying only because the replacement carried no tunable
 * parameter and moved to a weaker claim. **This driver — every constant and every branch of
 * [verdictOf] and [recoveryOf] — is committed BEFORE the batch sweep it grades has been
 * run**, in its own commit, so the commit timestamp against the results file's birth time
 * is checkable evidence rather than an assurance. If a defect in the criterion is found
 * later it is corrected in a SEPARATE commit and the entry says so, what it was before, and
 * whether the verdict word changed.
 *
 * ## The two questions, and the two constants that answer them
 *
 * - **Did the batch shape recover the precision `Mode.SingleShotTime` gave up?**
 *   [recoveryOf] against [BASELINE_RESOLVED_SEGMENTS] — the count computenet-252t's own
 *   published entry recorded (ONE of four segments per drive). Not a threshold anyone
 *   chose: it is the prior measurement's own result, and the bead's stated minimum for a
 *   shape comparison is strictly more than it, on BOTH drives.
 * - **Does the linear-in-degree reading survive?** [verdictOf] against
 *   [MARGINAL_GROWTH_FACTOR], carried over UNCHANGED at 3.0 from
 *   [FanOutFixedStateRenderTest], where it was fixed against the ORIGINAL (confounded)
 *   2026-08-19 sweep's own SIM segment marginals (0.0538 -> 0.0782 -> 0.0882 -> 0.1051
 *   us/subscriber, a 1.95x ratio across a 64-fold range of degree, and still called
 *   "linear at this sweep's resolution"). Changing it here would make the two sweeps'
 *   verdicts incomparable, which is the one thing this task exists to do.
 *
 * A segment is [Segment.resolvable] only when `|marginal| >` its own two-endpoint 99.9%
 * error bar, summed conservatively — the discipline [FanOutFixedStateRenderTest] settled
 * on, reused verbatim. The data supplies that bar; no one tunes it.
 *
 * This entry carries **no G-id trigger**, for the reason [FanOutFixedStateRenderTest]'s
 * KDoc gives: G-43 rests on BS-9's occupancy pairing, not the degree curve, and this is a
 * methodological re-reading of BS-8's own curve shape. [TriggerClaim.None] is passed to
 * `Findings.entry` accordingly.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * <toolchain-21>/bin/java -jar bench/build/libs/bench-jmh.jar \
 *      'FanOutScalingBenchmark\.(sim|real)BatchFixedState' \
 *      -rf csv -rff /abs/path/fanout-batch-fixed-state.csv \
 *      2>&1 | tee /abs/path/fanout-batch-fixed-state.log
 *
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.FanOutBatchFixedStateRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/fanout-batch-fixed-state.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * **Invoke the Gradle toolchain's own JDK 21 by absolute path, never a bare `java`** — see
 * `FanOutScalingBenchmark`'s KDoc for the measured consequence of not doing so. The
 * measuring JVM, the resolved JMH knobs and the CPU/core/OS all come from the teed run log
 * beside the results file; this test adds no environment fallback of its own.
 */
@Tag("bench")
class FanOutBatchFixedStateRenderTest {

    @Test
    fun `renders the batch fixed-state fan-out sweep and states resolvability and whether the linear reading survives`() {
        val path = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(path?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<path to a JMH -rf csv results file over " +
                "FanOutScalingBenchmark.simBatchFixedState/realBatchFixedState>"
        }
        val sha = System.getProperty("civictech.bench.harnessSha")
        requireNotNull(sha?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse --short HEAD>; the results " +
                "file does not record which harness commit produced it, and " +
                "RunEnvironment refuses to exist without it"
        }
        val file = File(path!!)
        require(file.isFile) { "no JMH results file at ${file.absolutePath}" }

        val rows = ThroughputReport.parseCsv(file.readText())
        val fits = fitsByDrive(rows)

        val report = ThroughputReport.renderRun(
            results = file,
            harnessCommitSha = sha!!,
            date = System.getProperty("civictech.bench.date")
                ?: java.time.LocalDate.now().toString(),
            subject = System.getProperty("civictech.bench.subject") ?: SUBJECT,
            trigger = TriggerClaim.None,
        )
        // Printed, never written: appending an entry to doc/bench/findings.md is the
        // measurement task's hand step, performed by whoever can vouch for the run.
        println(report.text())

        println()
        println("Batch fixed-state fit inputs (degree, per-delta score in each row's own unit):")
        println("| drive | a (intercept) | b (least-squares marginal/degree) | max |residual|/score |")
        println("| --- | --- | --- | --- |")
        fits.sortedBy { it.drive }.forEach { fit ->
            println("| ${fit.drive} | ${fit.a} | ${fit.b} | ${fit.maxRelResidual} |")
        }
        println()
        println(
            "Per-segment marginals (± combined 99.9% error, conservative sum), " +
                "resolvable = |marginal| > combined error:"
        )
        println("| drive | segment | marginal | combined error | resolvable |")
        println("| --- | --- | --- | --- | --- |")
        fits.sortedBy { it.drive }.forEach { fit ->
            fit.segments.forEach { seg ->
                println(
                    "| ${fit.drive} | D${seg.d1}->D${seg.d2} | ${seg.marginal} | " +
                        "${seg.combinedError} | ${seg.resolvable} |"
                )
            }
        }
        println()
        println("Resolved segments per drive (batch shape) against computenet-252t's SingleShotTime baseline:")
        println("| drive | resolvable | of | baseline (252t) |")
        println("| --- | --- | --- | --- |")
        fits.sortedBy { it.drive }.forEach { fit ->
            println(
                "| ${fit.drive} | ${fit.resolvableSegments.size} | ${fit.segments.size} | " +
                    "$BASELINE_RESOLVED_SEGMENTS |"
            )
        }
        println()
        println("BASELINE_RESOLVED_SEGMENTS=$BASELINE_RESOLVED_SEGMENTS")
        println("MARGINAL_GROWTH_FACTOR=$MARGINAL_GROWTH_FACTOR")
        println("precision=${recoveryOf(fits)}")
        println("reading=${verdictOf(fits)}")
    }

    /** One degree's measured point: the score and its 99.9% confidence half-width. */
    data class Point(val degree: Int, val score: Double, val error: Double)

    /** One segment between two consecutive degrees — the per-additional-subscriber marginal. */
    data class Segment(val d1: Int, val d2: Int, val marginal: Double, val combinedError: Double) {
        /**
         * Whether this segment's sign AND rough magnitude are established against its own
         * noise — [combinedError] is the two endpoints' 99.9% half-widths summed
         * conservatively, the convention [FanOutFixedStateRenderTest] and the 2026-08-19
         * fan-out entry both use.
         */
        val resolvable: Boolean get() = abs(marginal) > combinedError
    }

    /** One drive's degree->score points, the affine fit over them, and the segments between them. */
    data class DriveFit(
        val drive: String,
        val points: List<Point>,
        val a: Double,
        val b: Double,
        val maxRelResidual: Double,
        val segments: List<Segment>,
    ) {
        /** Segments whose sign and magnitude are established against their own error bar. */
        val resolvableSegments: List<Segment> get() = segments.filter { it.resolvable }

        /**
         * Ratio of the largest to the smallest RESOLVABLE segment marginal — the shape
         * signal once noise-dominated segments are excluded rather than averaged in. `NaN`
         * when fewer than two segments are resolvable.
         */
        val marginalRatio: Double
            get() {
                val r = resolvableSegments
                return if (r.size < 2) Double.NaN else r.maxOf { it.marginal } / r.minOf { it.marginal }
            }
    }

    private fun fitsByDrive(rows: List<JmhRow>): List<DriveFit> =
        rows.groupBy { ThroughputReport.driveOf(it).name }.map { (drive, driveRows) ->
            val points = driveRows.map { row ->
                val degreeName = row.params[DEGREE_PARAM] ?: throw ThroughputReportException(
                    "a batch fixed-state fan-out row carries no '$DEGREE_PARAM' parameter " +
                        "(found ${row.params}); the fit is stated per degree and cannot " +
                        "be computed over a row that does not say which degree it measured"
                )
                Point(FanDegree.valueOf(degreeName).subscribers, row.score, row.scoreError)
            }.sortedBy { it.degree }
            require(points.size >= 2) {
                "drive=$drive carries only ${points.size} degree(s); an affine fit needs " +
                    "at least two"
            }

            val n = points.size.toDouble()
            val sumX = points.sumOf { it.degree.toDouble() }
            val sumY = points.sumOf { it.score }
            val sumXY = points.sumOf { it.degree * it.score }
            val sumXX = points.sumOf { it.degree.toDouble() * it.degree }
            val denom = n * sumXX - sumX * sumX
            val b = if (denom == 0.0) 0.0 else (n * sumXY - sumX * sumY) / denom
            val a = (sumY - b * sumX) / n

            val maxRelResidual = points.maxOf { (degree, score, _) ->
                val predicted = a + b * degree
                if (score == 0.0) Double.NaN else abs(score - predicted) / abs(score)
            }

            val segments = (1 until points.size).map { i ->
                val p1 = points[i - 1]
                val p2 = points[i]
                val span = (p2.degree - p1.degree).toDouble()
                Segment(
                    d1 = p1.degree,
                    d2 = p2.degree,
                    marginal = (p2.score - p1.score) / span,
                    combinedError = (p1.error + p2.error) / span,
                )
            }

            DriveFit(drive, points, a, b, maxRelResidual, segments)
        }

    /**
     * Whether the batch shape recovered the precision `Mode.SingleShotTime` gave up — the
     * shape-comparison question this task exists to answer, decided ONLY by the count of
     * segments each drive resolves against its own error bar, against
     * [BASELINE_RESOLVED_SEGMENTS] (computenet-252t's published count).
     *
     * "RECOVERED" requires strictly more than the baseline on EVERY drive — the bead's
     * stated minimum, "resolve MORE THAN ONE segment per drive". "PARTIALLY RECOVERED"
     * when some drive improves and some does not. "NOT RECOVERED" when no drive beats the
     * baseline, which is a completely legitimate answer to the shape comparison and is
     * reported as such, not tuned away.
     */
    private fun recoveryOf(fits: List<DriveFit>): String {
        if (fits.isEmpty()) return "NOT RECOVERED — no drives in the sweep"
        val counts = fits.sortedBy { it.drive }
            .joinToString { "${it.drive}=${it.resolvableSegments.size}/${it.segments.size}" }
        val improved = fits.filter { it.resolvableSegments.size > BASELINE_RESOLVED_SEGMENTS }
        return when {
            improved.size == fits.size ->
                "RECOVERED — every drive resolves more than the $BASELINE_RESOLVED_SEGMENTS " +
                    "segment(s) computenet-252t's SingleShotTime sweep resolved ($counts)"

            improved.isEmpty() ->
                "NOT RECOVERED — no drive resolves more than the " +
                    "$BASELINE_RESOLVED_SEGMENTS segment(s) computenet-252t's " +
                    "SingleShotTime sweep resolved ($counts)"

            else ->
                "PARTIALLY RECOVERED — " +
                    improved.joinToString { it.drive } +
                    " beat the baseline of $BASELINE_RESOLVED_SEGMENTS, the other drive(s) " +
                    "did not ($counts)"
        }
    }

    /**
     * The reading [MARGINAL_GROWTH_FACTOR] yields for [fits] — identical in every branch to
     * [FanOutFixedStateRenderTest]'s own `verdictOf`, so the two sweeps' verdict words are
     * produced by the same decision and are directly comparable.
     */
    private fun verdictOf(fits: List<DriveFit>): String {
        if (fits.isEmpty()) return "INCONCLUSIVE — no drives in the sweep"
        val underResolved = fits.filter { it.resolvableSegments.size < 2 }
        if (underResolved.isNotEmpty()) {
            return "INCONCLUSIVE — " + underResolved.joinToString {
                "${it.drive} has only ${it.resolvableSegments.size} segment(s) resolvable " +
                    "against its own error bar (of ${it.segments.size} total), which is " +
                    "fewer than the two a shape comparison needs"
            }
        }
        val worst = fits.maxByOrNull { it.marginalRatio }!!
        return if (worst.marginalRatio <= MARGINAL_GROWTH_FACTOR) {
            "SURVIVES — every drive's RESOLVABLE segment-marginal ratio is at or under " +
                "$MARGINAL_GROWTH_FACTOR (worst: ${worst.drive} at ${worst.marginalRatio})"
        } else {
            "DOES NOT SURVIVE AS STATED — ${worst.drive}'s resolvable segment-marginal " +
                "ratio ${worst.marginalRatio} exceeds $MARGINAL_GROWTH_FACTOR"
        }
    }

    private companion object {

        const val DEGREE_PARAM: String = "degree"

        const val SUBJECT: String =
            "fan-out per-delta cost over FanOutlet at degrees {1, 4, 16, 64, 256}, source " +
                "held at a FIXED ${FanOutFixtures.FIXED_STATE_ELEMENTS}-element pre-seed at " +
                "every degree with ${FanOutFixtures.BATCH_OPS} measured deltas per " +
                "invocation-batch (Mode.AverageTime, @OperationsPerInvocation, rig rebuilt " +
                "and re-seeded per invocation-BATCH) — the second candidate shape " +
                "computenet-252t named, testing whether keeping JIT warmup across a batch " +
                "recovers the precision Mode.SingleShotTime gave up"

        /**
         * Segments per drive computenet-252t's `Mode.SingleShotTime` sweep resolved against
         * their own 99.9% error bar, of four — the published count in `doc/bench/findings.md`'s
         * 2026-08-20 fan-out entry (only D64->D256, on both drives).
         *
         * Not a threshold this task chose: it is the prior measurement's own result, and
         * the bar the bead sets ("resolve MORE THAN ONE segment per drive") is strictly
         * above it.
         */
        const val BASELINE_RESOLVED_SEGMENTS: Int = 1

        /**
         * How much a resolvable segment marginal may grow across the sweep before the
         * linear-in-degree reading is called into question.
         *
         * Carried over UNCHANGED from [FanOutFixedStateRenderTest], where it was fixed
         * against the ORIGINAL (confounded) 2026-08-19 entry's own SIM segment marginals —
         * 0.0538 -> 0.0782 -> 0.0882 -> 0.1051 us/subscriber, a 1.95x ratio across a
         * 64-fold range of degree, still called "linear in degree at this sweep's
         * resolution". Retuning it for this sweep would make the two verdicts
         * incomparable, which is the one thing this task exists to do.
         */
        const val MARGINAL_GROWTH_FACTOR: Double = 3.0
    }
}
