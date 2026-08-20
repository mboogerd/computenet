package civictech.bench.micro

import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The `@Tag("bench")` entry point that renders `FanOutScalingBenchmark`'s fixed-state
 * sweep (`simFixedState`/`realFixedState`, computenet-252t) into a `doc/bench/findings.md`
 * entry, and states whether the 2026-08-19 fan-out entry's "linear in degree, not worse"
 * reading survives once the source's element count is held FIXED across every
 * [FanDegree] — the question computenet-252t exists to answer.
 *
 * ## Why this is a separate entry point, not a flag on [ThroughputReportRenderTest]
 *
 * [ThroughputReportRenderTest] renders any registered sweep's PRIMARY metric under
 * [TriggerClaim.None] and nothing more. This one differs the same way
 * [CellFootprintAllocRenderTest] differs from it: it states a machine-checked reading of
 * the numbers ([verdictOf]) that a caller-supplied string could not be trusted the same
 * way, and [criterionInputs] prints the affine fit and segment marginals the reading
 * rests on so a later reader can re-derive the word from the numbers beside it.
 *
 * This entry carries **no G-id trigger**. computenet-252t's own non-goals are explicit
 * that this task is "no re-litigation of the G-43 verdict, which rests on BS-9's
 * occupancy pairing and not on the degree curve" — G-43 already FIRES on BS-9 alone, and
 * nothing here reopens that. [TriggerClaim.None] is passed to `Findings.entry`
 * accordingly; this is a methodological correction to BS-8's OWN curve-shape reading, not
 * a new trigger evaluation.
 *
 * ## Why the criterion was fixed before the numbers were known
 *
 * Same discipline [CellFootprintAllocRenderTest]'s KDoc states for its own criterion, and
 * for the same reason: [MARGINAL_GROWTH_FACTOR] is a constant fixed against the ORIGINAL
 * (confounded) sweep's own SIM segment marginals — which climbed 1.95x across a 64-fold
 * range of degree and were themselves called "linear... at this sweep's resolution" — not
 * against this run's numbers.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * <toolchain-21>/bin/java -jar bench/build/libs/bench-jmh.jar \
 *      'FanOutScalingBenchmark.(sim|real)FixedState' \
 *      -rf csv -rff /abs/path/fanout-fixed-state.csv \
 *      2>&1 | tee /abs/path/fanout-fixed-state.log
 *
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.FanOutFixedStateRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/fanout-fixed-state.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * The measuring JVM, the JMH knobs the run resolved, and the CPU/core/OS of the measuring
 * host all come from the teed run log beside the results file, exactly as
 * [ThroughputReportRenderTest] and [CellFootprintAllocRenderTest] read them — this test
 * adds no environment fallback of its own and fails rather than answering for the
 * rendering process.
 *
 * **Invoke the Gradle toolchain's own JDK 21 by absolute path, never a bare `java`** — see
 * [FanOutScalingBenchmark]'s KDoc for the measured consequence of not doing so.
 */
@Tag("bench")
class FanOutFixedStateRenderTest {

    @Test
    fun `renders the fixed-state fan-out sweep and states whether the linear-in-degree reading survives`() {
        val path = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(path?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<path to a JMH -rf csv results file over " +
                "FanOutScalingBenchmark.simFixedState/realFixedState>"
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
        val verdict = verdictOf(fits)

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

        // The reading's own inputs, printed beside the entry so the word below is
        // checkable rather than merely asserted — the same discipline
        // CellFootprintAllocRenderTest's "Criterion inputs" block follows.
        println()
        println("Fixed-state fit inputs (degree, per-delta score in each row's own unit):")
        println("| drive | a (intercept) | b (marginal/degree) | max |residual|/score | segment marginals | ratio max/min |")
        println("| --- | --- | --- | --- | --- | --- |")
        fits.sortedBy { it.drive }.forEach { fit ->
            println(
                "| ${fit.drive} | ${fit.a} | ${fit.b} | ${fit.maxRelResidual} | " +
                    "${fit.marginals} | ${fit.marginalRatio} |"
            )
        }
        println()
        println("MARGINAL_GROWTH_FACTOR=$MARGINAL_GROWTH_FACTOR")
        println("reading=$verdict")
    }

    /** One drive's degree->score points and the affine fit over them. */
    data class DriveFit(
        val drive: String,
        val points: List<Pair<Int, Double>>,
        val a: Double,
        val b: Double,
        val maxRelResidual: Double,
        val marginals: List<Double>,
    ) {
        /** Ratio of the largest to the smallest resolvable segment marginal — the shape signal. */
        val marginalRatio: Double
            get() {
                val finite = marginals.filter { it.isFinite() && it > 0.0 }
                return if (finite.size < 2) Double.NaN else finite.max() / finite.min()
            }
    }

    private fun fitsByDrive(rows: List<JmhRow>): List<DriveFit> =
        rows.groupBy { ThroughputReport.driveOf(it).name }.map { (drive, driveRows) ->
            val points = driveRows.map { row ->
                val degreeName = row.params[DEGREE_PARAM] ?: throw ThroughputReportException(
                    "a fixed-state fan-out row carries no '$DEGREE_PARAM' parameter " +
                        "(found ${row.params}); the fit is stated per degree and cannot " +
                        "be computed over a row that does not say which degree it measured"
                )
                FanDegree.valueOf(degreeName).subscribers to row.score
            }.sortedBy { it.first }
            require(points.size >= 2) {
                "drive=$drive carries only ${points.size} degree(s); an affine fit needs " +
                    "at least two"
            }

            val n = points.size.toDouble()
            val sumX = points.sumOf { it.first.toDouble() }
            val sumY = points.sumOf { it.second }
            val sumXY = points.sumOf { it.first * it.second }
            val sumXX = points.sumOf { it.first.toDouble() * it.first }
            val denom = n * sumXX - sumX * sumX
            val b = if (denom == 0.0) 0.0 else (n * sumXY - sumX * sumY) / denom
            val a = (sumY - b * sumX) / n

            val maxRelResidual = points.maxOf { (degree, score) ->
                val predicted = a + b * degree
                if (score == 0.0) Double.NaN else abs(score - predicted) / abs(score)
            }

            val marginals = (1 until points.size).map { i ->
                val (d1, y1) = points[i - 1]
                val (d2, y2) = points[i]
                (y2 - y1) / (d2 - d1)
            }

            DriveFit(drive, points, a, b, maxRelResidual, marginals)
        }

    /**
     * The reading [MARGINAL_GROWTH_FACTOR] yields for [fits] — the whole of the decision,
     * in code, deliberately total over three mutually exclusive branches.
     *
     * "SURVIVES" only when every drive's segment marginals stay within the pre-declared
     * growth factor of one another (the shape a linear-in-degree cost has); "DOES NOT
     * SURVIVE" when some drive's marginals clearly exceed it; "INCONCLUSIVE" when a drive
     * has too few resolvable segments to say either way (fewer than two finite positive
     * marginals — e.g. every consecutive pair happened to tie or invert at this sweep's
     * resolution).
     */
    private fun verdictOf(fits: List<DriveFit>): String {
        if (fits.isEmpty()) return "INCONCLUSIVE — no drives in the sweep"
        val resolvable = fits.filter { it.marginalRatio.isFinite() }
        if (resolvable.size < fits.size) {
            return "INCONCLUSIVE — " + fits.filter { !it.marginalRatio.isFinite() }
                .joinToString { "${it.drive} has too few resolvable segments" }
        }
        val worst = resolvable.maxByOrNull { it.marginalRatio }!!
        return if (worst.marginalRatio <= MARGINAL_GROWTH_FACTOR) {
            "SURVIVES — every drive's segment-marginal ratio is at or under " +
                "$MARGINAL_GROWTH_FACTOR (worst: ${worst.drive} at ${worst.marginalRatio})"
        } else {
            "DOES NOT SURVIVE AS STATED — ${worst.drive}'s segment-marginal ratio " +
                "${worst.marginalRatio} exceeds $MARGINAL_GROWTH_FACTOR"
        }
    }

    private companion object {

        const val DEGREE_PARAM: String = "degree"

        const val SUBJECT: String =
            "fan-out per-delta cost over FanOutlet at degrees {1, 4, 16, 64, 256}, source " +
                "held at a FIXED ${FanOutFixtures.FIXED_STATE_ELEMENTS}-element size across " +
                "every degree (Mode.SingleShotTime, rig rebuilt and re-seeded per " +
                "invocation) — re-reading BS-8's degree curve with the per-iteration " +
                "state-size confound (computenet-252t) closed by construction"

        /**
         * How much a resolvable segment marginal may grow across the sweep before the
         * linear-in-degree reading is called into question.
         *
         * Fixed against the ORIGINAL (confounded) 2026-08-19 entry's own SIM segment
         * marginals — which climbed 0.0538 -> 0.0782 -> 0.0882 -> 0.1051 us/subscriber, a
         * 1.95x ratio across a 64-fold range of degree — and were themselves still called
         * "linear in degree at this sweep's resolution". 3.0x gives that same reading room
         * for a fixed-state sweep expected to be noisier (Mode.SingleShotTime, 3 forks,
         * no per-invocation JIT warmup) while still catching a clearly super-linear curve,
         * which would show segment marginals growing severalfold rather than doubling.
         */
        const val MARGINAL_GROWTH_FACTOR: Double = 3.0
    }
}
