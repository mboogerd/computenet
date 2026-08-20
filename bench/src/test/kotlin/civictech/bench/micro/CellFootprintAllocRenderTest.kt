package civictech.bench.micro

import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `@Tag("bench")` entry point that renders a `-prof gc` allocation sweep of
 * `CellFootprintBenchmark` into a `doc/bench/findings.md` entry (computenet-6zqz).
 *
 * ## Why this is a second entry point and not a flag on the first
 *
 * `ThroughputReportRenderTest` renders any registered sweep's PRIMARY metric under
 * [TriggerClaim.None]. This one differs in two ways that are both decisions rather than
 * parameters, and neither belongs on a command line:
 *
 * 1. **It reads [Metric.GC_ALLOC_RATE_NORM]**, not the primary `Score` column. The two are
 *    different quantities in different units — wall clock in `us/op` against bytes
 *    allocated per `snapshot()` call in `B/op` — and this is the one G-21 phase 3's
 *    trigger names.
 * 2. **It states a verdict on that trigger**, which [civictech.bench.Findings.entry]
 *    requires to be exactly one of FIRES / RETIRES / INCONCLUSIVE. A verdict passed in as
 *    a system property is a verdict nobody can re-derive; [CRITERION] and [verdictOf]
 *    below are committed source, so a later reader can run the same sweep and check that
 *    the same numbers still produce the same word.
 *
 * ## The criterion was fixed BEFORE the numbers were known
 *
 * That ordering is the whole reason [CRITERION] is a constant rather than prose written
 * into the entry afterwards, and it mirrors how `NOISE_FLOOR`'s 2x margin was fixed on
 * `computenet-x9e.3.3` before the first run reported anything. A criterion chosen after
 * the fact is a criterion chosen to fit.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * <toolchain-21>/bin/java -jar bench/build/libs/bench-jmh.jar CellFootprintBenchmark \
 *      -prof gc -rf csv -rff /abs/path/footprint-alloc.csv \
 *      2>&1 | tee /abs/path/footprint-alloc.log
 *
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.CellFootprintAllocRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/footprint-alloc.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * `-prof gc` is not optional and its absence is not silent: a sweep run without it writes
 * a complete, parseable results file holding only wall clock, and
 * [ThroughputReport.parseCsv] refuses it here naming the metrics the file does hold rather
 * than rendering `us/op` under a subject line promising `B/op`.
 *
 * The measuring JVM, the JMH knobs the run resolved, and the CPU/core/OS of the measuring
 * host all come from the teed run log beside the results file, exactly as they do for
 * `ThroughputReportRenderTest` — this test adds no environment fallback of its own, and
 * fails rather than answering for the rendering process.
 *
 * **Invoke the JMH jar through the toolchain's own JDK 21 by absolute path.** A bare
 * `java` on a developer machine is whatever is first on `PATH` — JBR 25 on the host this
 * file was written on, Homebrew JDK 26 on the host `computenet-dbqt` was filed from — and
 * a run on either completes successfully while producing figures incomparable to
 * `NOISE_FLOOR` and to every other entry in `doc/bench/findings.md`. The run's own
 * `# VM version:` banner, retained in the `.log`, is the check.
 */
@Tag("bench")
class CellFootprintAllocRenderTest {

    @Test
    fun `renders the -prof gc allocation sweep of CellFootprintBenchmark`() {
        val path = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(path?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<path to a `-prof gc -rf csv` results file>"
        }
        val sha = System.getProperty("civictech.bench.harnessSha")
        requireNotNull(sha?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse --short HEAD>; the results " +
                "file does not record which harness commit produced it, and " +
                "RunEnvironment refuses to exist without it"
        }
        val file = File(path!!)
        require(file.isFile) { "no JMH results file at ${file.absolutePath}" }

        val rows = ThroughputReport.parseCsv(file.readText(), Metric.GC_ALLOC_RATE_NORM)
        val inputs = criterionInputs(rows)
        val verdict = verdictOf(inputs)

        val report = ThroughputReport.renderRun(
            results = file,
            harnessCommitSha = sha!!,
            date = System.getProperty("civictech.bench.date")
                ?: java.time.LocalDate.now().toString(),
            subject = System.getProperty("civictech.bench.subject") ?: SUBJECT,
            trigger = TriggerClaim.Cited(
                gapId = GAP_ID,
                statement = "$verdict: $CRITERION; measured, ${measuredClause(inputs)}",
            ),
            metric = Metric.GC_ALLOC_RATE_NORM,
        )

        // Printed, never written: appending an entry to doc/bench/findings.md is the
        // measurement task's hand step, performed by whoever can vouch for the run.
        println(report.text())

        // The criterion's own inputs, printed beside the entry so the verdict word above
        // is checkable rather than merely asserted. EVERY family appears here, including
        // ones whose rows the NOISE_FLOOR gate kept out of the table — the verdict is a
        // statement about the sweep, so a reader has to see what the sweep held.
        println()
        println("Criterion inputs (bytes allocated per snapshot() call, ${Metric.GC_ALLOC_RATE_NORM.key}):")
        println("| family | at smallest scale | at largest scale | largest/smallest | max B/op |")
        println("| --- | --- | --- | --- | --- |")
        inputs.sortedBy { it.family }.forEach { row ->
            println(
                "| ${row.family} | ${row.smallest} | ${row.largest} | " +
                    "${row.growth} | ${row.max} |"
            )
        }
        println()
        println("PRESSURE_BAR_BYTES=$PRESSURE_BAR_BYTES FLAT_GROWTH_FACTOR=$FLAT_GROWTH_FACTOR FLOOR_BYTES=$FLOOR_BYTES")
        println("verdict=$verdict")
    }

    /** One family's allocation across the sweep's scales — the criterion's raw inputs. */
    data class FamilyAllocation(
        val family: String,
        val smallest: Double,
        val largest: Double,
        val max: Double,
    ) {
        /** How much allocation grew from the smallest scale to the largest. */
        val growth: Double get() = if (smallest == 0.0) Double.NaN else largest / smallest
    }

    private fun criterionInputs(rows: List<JmhRow>): List<FamilyAllocation> =
        rows.groupBy { row ->
            row.params[FAMILY_PARAM] ?: throw ThroughputReportException(
                "a ${Metric.GC_ALLOC_RATE_NORM.key} row carries no '$FAMILY_PARAM' " +
                    "parameter (found ${row.params}); the criterion is stated per family " +
                    "and cannot be evaluated over rows that do not say which family they " +
                    "measured"
            )
        }.map { (family, familyRows) ->
            val byScale = familyRows.sortedBy { row ->
                val scale = row.params[SCALE_PARAM] ?: throw ThroughputReportException(
                    "a ${Metric.GC_ALLOC_RATE_NORM.key} row for family '$family' carries " +
                        "no '$SCALE_PARAM' parameter (found ${row.params}); the criterion " +
                        "compares allocation ACROSS scales and cannot order rows that do " +
                        "not say which scale they measured"
                )
                Scale.valueOf(scale).elements
            }
            FamilyAllocation(
                family = family,
                smallest = byScale.first().score,
                largest = byScale.last().score,
                max = byScale.maxOf { it.score },
            )
        }

    /**
     * The measured half of the trigger statement — what [CRITERION] was applied TO.
     *
     * Generated from the same [inputs] the verdict is, so the sentence in the entry
     * cannot drift from the numbers beside it. It states the two quantities the criterion
     * turns on and nothing else: the largest per-call allocation any family reached, and
     * the range of growth factors across two decades of element count.
     */
    private fun measuredClause(inputs: List<FamilyAllocation>): String {
        val loudest = inputs.maxByOrNull { it.max }
            ?: return "the sweep held no rows at all"
        val growths = inputs.map { it.growth }.filter { it.isFinite() }.sorted()
        val flat = inputs.filter { it.growth.isFinite() && it.growth <= FLAT_GROWTH_FACTOR }
        return "the loudest family is ${loudest.family} at ${loudest.max} B/op, growth " +
            "from 1e3 to 1e5 ranges ${growths.firstOrNull()}x-${growths.lastOrNull()}x " +
            "across ${inputs.size} families, and the families flat in element count are " +
            (if (flat.isEmpty()) "none" else flat.joinToString { it.family }) +
            " — whose largest per-call allocation is " +
            (flat.maxByOrNull { it.max }?.max?.toString() ?: "n/a") + " B/op"
    }

    /**
     * The verdict [CRITERION] yields for [inputs] — the whole of the decision, in code.
     *
     * Deliberately total and deliberately dull: three mutually exclusive branches over
     * two thresholds, so that re-running the sweep and re-reading this function is a
     * complete audit of how the word in the entry was reached.
     */
    private fun verdictOf(inputs: List<FamilyAllocation>): String {
        val fires = inputs.any { it.max >= PRESSURE_BAR_BYTES && it.growth <= FLAT_GROWTH_FACTOR }
        val retires = inputs.isNotEmpty() && inputs.all { it.max < FLOOR_BYTES }
        return when {
            fires -> "FIRES"
            retires -> "RETIRES"
            else -> "INCONCLUSIVE"
        }
    }

    private companion object {

        const val GAP_ID: String = "G-21 phase 3"

        const val FAMILY_PARAM: String = "family"

        const val SCALE_PARAM: String = "scale"

        const val SUBJECT: String =
            "allocation per Stateful.snapshot() call (gc.alloc.rate.norm, -prof gc) for " +
                "every data-cell family at 1e3/1e4/1e5 — G-21 phase 3's own quantity, " +
                "allocation pressure, not the retained occupancy computenet-x9e.6.2 measured"

        /**
         * "Large in absolute terms": 1 MiB allocated per single `snapshot()` call.
         *
         * A bar rather than a comparison, because G-21 phase 3's trigger has no baseline
         * to compare against — it says "profiling shows allocation pressure" and nothing
         * else. One mebibyte per call is a megabyte of garbage per read of one cell; a
         * system doing that at any rate is one where the phrase means something.
         */
        const val PRESSURE_BAR_BYTES: Double = 1024.0 * 1024.0

        /**
         * How flat allocation must be across two decades of element count to count as
         * O(1) — the shape a fixed-size pooled buffer could actually retire.
         *
         * A hundredfold more elements allocating at most twice as much is a per-call cost
         * that does not track the data. Anything above that is whole-state copy
         * allocation, which scales with the state and which pooling a lease does not
         * remove: the copy still has to exist.
         */
        const val FLAT_GROWTH_FACTOR: Double = 2.0

        /**
         * Below which allocation is not pressure at all: one 4 KiB page per call.
         *
         * A family whose every scale allocates less than a page per `snapshot()` has
         * nothing for a pool to reclaim, and a sweep where that held for EVERY family
         * would retire the trigger outright.
         */
        const val FLOOR_BYTES: Double = 4096.0

        /**
         * The trigger criterion, fixed before the sweep ran.
         *
         * Lowercase "fires"/"retires" is load-bearing, not a style choice:
         * `Findings.entry` counts whole-word occurrences of FIRES / RETIRES /
         * INCONCLUSIVE case-sensitively and refuses a statement containing other than
         * exactly one, so a criterion sentence that names the other two outcomes has to
         * name them in lower case. The landed G-21 entry of 2026-08-19 does the same.
         */
        const val CRITERION: String =
            "the criterion applied is that the trigger fires only if some family " +
                "allocates at least 1 MiB per snapshot() call AND that family's " +
                "allocation is flat in element count (at most 2x from 1e3 to 1e5) — the " +
                "fixed-size per-call shape a lease pool could actually retire — and " +
                "retires only if every family allocates under one 4 KiB page per call, " +
                "with anything else undecided"
    }
}
