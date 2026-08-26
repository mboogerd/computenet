package civictech.bench.micro

import civictech.bench.TriggerClaim
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `@Tag("bench")` entry point that renders a `-prof gc` allocation sweep of
 * `OperatorThroughputBenchmark`'s SET-SHAPED subjects into a `doc/bench/findings.md`
 * entry, and states a verdict on `[BEN1-28]`'s operator-level question (computenet-i61m).
 *
 * ## The question, and why an allocation sweep is the instrument
 *
 * `[BEN1-28]` asks whether `TagState` tag-map growth dominates the set-shaped subjects'
 * delta-application cost. `computenet-x9e.14` settled the HARNESS half of that — the
 * INSERT/RETRACT dispersion split came from unbounded live state in the benchmark's own
 * `@Setup(Level.Invocation)`, not from the operators — but nothing under `kernel/src/main`
 * differed between its two arms, so it drew no operator-level conclusion and neither of
 * its arms attached a profiler (`# VM options: <none>` in both retained banners). This
 * test is the profile that was missing.
 *
 * ## What the criterion turns on, stated as a bound rather than a guess
 *
 * One measured operation here is ONE DELTA (`@OperationsPerInvocation` is
 * [ThroughputReport.DELTAS_PER_BATCH]). Each delta adds at most a bounded, O(1) amount to
 * the operator's `TagState` map: one live tag on insert, one tombstone on retract, per
 * source. [TAG_ENTRY_BUDGET_BYTES] is a deliberately GENEROUS upper bound on what that
 * addition can allocate on a 64-bit HotSpot with compressed oops — a `HashMap.Node` is
 * 32 B, its amortized share of table-array doubling is ~16 B, and a tag plus a boxed
 * element plus a per-source duplicate for the two-source subjects still leaves 512 B
 * several times larger than any layout that could actually be built.
 *
 * So `TAG_ENTRY_BUDGET_BYTES / measured B/op` is an UPPER BOUND on tag-map growth's share
 * of a delta's allocation. That is the quantity the criterion is stated over, and it is
 * one-sided on purpose: it can prove growth is a small fraction, and it can show that
 * growth is large enough to be a candidate, but it cannot prove growth IS the cost.
 *
 * **The limitation that goes with it, and it is not small.** Allocation is a proxy for
 * cost, not cost. A `TagState` map that grows without allocating — rehash probes, cache
 * misses walking a larger table — costs wall clock this instrument cannot see. A RETIRES
 * verdict from this criterion therefore retires the ALLOCATION channel of `[BEN1-28]`'s
 * suspicion and nothing else; the entry says so in as many words.
 *
 * ## The criterion was fixed BEFORE the numbers were known
 *
 * [CRITERION] is a committed constant, written and committed while the sweep that feeds
 * it was still running, for the same reason `CellFootprintAllocRenderTest`'s is: a
 * criterion chosen after the fact is a criterion chosen to fit. [verdictOf] is the whole
 * of the decision in code, so re-running the sweep and re-reading this function is a
 * complete audit of how the word in the entry was reached.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :bench:jmhJar
 * <toolchain-21>/bin/java -jar bench/build/libs/bench-jmh.jar \
 *      'OperatorThroughputBenchmark.real' \
 *      -p subject=TAGGED_SET,FILTER,UNION,INTERSECT,COUNT,FLAT_MAP,PRESENCE_COUNT,QUORUM \
 *      -prof gc -rf csv -rff /abs/path/setshaped-gc.csv > /abs/path/setshaped-gc.log 2>&1
 *
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.TagMapGrowthAllocRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/setshaped-gc.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * `-prof gc` is not optional and its absence is not silent: `ThroughputReport.parseCsv`
 * refuses a results file holding no `gc.alloc.rate.norm` row, naming the metrics it does
 * hold. **Invoke the JMH jar through the toolchain's own JDK 21 by absolute path** — a
 * bare `java` on this host is JBR 25 (`computenet-dbqt`), and the run's own
 * `# VM version:` banner, retained in the `.log`, is the check.
 */
@Tag("bench")
class TagMapGrowthAllocRenderTest {

    @Test
    fun `renders the -prof gc allocation sweep of the set-shaped subjects`() {
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

        println()
        println(
            "Criterion inputs (bytes allocated per DELTA, ${Metric.GC_ALLOC_RATE_NORM.key}; " +
                "share = TAG_ENTRY_BUDGET_BYTES / B/op, an UPPER BOUND on tag-map growth's " +
                "share of a delta's allocation):"
        )
        println("| subject direction | B/op | upper-bound growth share |")
        println("| --- | --- | --- |")
        inputs.sortedBy { it.label }.forEach { row ->
            println("| ${row.label} | ${row.bytesPerOp} | ${row.growthShare} |")
        }
        println()
        println(
            "TAG_ENTRY_BUDGET_BYTES=$TAG_ENTRY_BUDGET_BYTES " +
                "CANDIDATE_SHARE=$CANDIDATE_SHARE NEGLIGIBLE_SHARE=$NEGLIGIBLE_SHARE"
        )
        println("verdict=$verdict")
    }

    /** One set-shaped (subject, direction) row's allocation per delta. */
    data class SubjectAllocation(
        val subject: String,
        val direction: String,
        val bytesPerOp: Double,
    ) {
        val label: String get() = "$subject ${direction.lowercase()}"

        /**
         * An UPPER BOUND on tag-map growth's share of this row's per-delta allocation.
         *
         * Not a measurement of that share — a bound on it, from [TAG_ENTRY_BUDGET_BYTES]
         * over what was measured. A row allocating nothing at all cannot be bounded, and
         * is reported as `NaN` rather than as a share of zero.
         */
        val growthShare: Double
            get() = if (bytesPerOp <= 0.0) Double.NaN else TAG_ENTRY_BUDGET_BYTES / bytesPerOp
    }

    private fun criterionInputs(rows: List<JmhRow>): List<SubjectAllocation> {
        val setShaped = Subject.setShaped().map { it.name }.toSet()
        return rows.map { row ->
            val subject = row.params[SUBJECT_PARAM] ?: throw ThroughputReportException(
                "a ${Metric.GC_ALLOC_RATE_NORM.key} row carries no '$SUBJECT_PARAM' " +
                    "parameter (found ${row.params}); the criterion is stated over the " +
                    "set-shaped subjects and cannot be evaluated over rows that do not " +
                    "say which subject they measured"
            )
            val direction = row.params[DIRECTION_PARAM] ?: throw ThroughputReportException(
                "a ${Metric.GC_ALLOC_RATE_NORM.key} row for subject '$subject' carries no " +
                    "'$DIRECTION_PARAM' parameter (found ${row.params}); insert and " +
                    "retract grow the tag map differently and a row that does not say " +
                    "which it measured cannot enter the criterion"
            )
            if (subject !in setShaped) {
                throw ThroughputReportException(
                    "row '$subject $direction' is not a set-shaped subject (set-shaped is " +
                        "${setShaped.sorted()}); this criterion is stated over the " +
                        "set-shaped family only, and silently averaging a join, grouped " +
                        "or combine subject into it would answer a different question"
                )
            }
            SubjectAllocation(subject = subject, direction = direction, bytesPerOp = row.score)
        }
    }

    /**
     * The measured half of the trigger statement — what [CRITERION] was applied TO.
     *
     * Generated from the same [inputs] the verdict is, so the sentence in the entry cannot
     * drift from the numbers beside it.
     */
    private fun measuredClause(inputs: List<SubjectAllocation>): String {
        if (inputs.isEmpty()) return "the sweep held no rows at all"
        val shares = inputs.map { it.growthShare }.filter { it.isFinite() }.sorted()
        val loudest = inputs.maxByOrNull { it.bytesPerOp }!!
        val quietest = inputs.minByOrNull { it.bytesPerOp }!!
        val candidates = inputs.count { it.growthShare.isFinite() && it.growthShare >= CANDIDATE_SHARE }
        return "across ${inputs.size} set-shaped rows per-delta allocation ranges " +
            "${quietest.bytesPerOp} B/op (${quietest.label}) to ${loudest.bytesPerOp} B/op " +
            "(${loudest.label}), so the upper bound on tag-map growth's share of a delta's " +
            "allocation ranges ${shares.firstOrNull()}-${shares.lastOrNull()}, with " +
            "$candidates of ${inputs.size} rows at or above the $CANDIDATE_SHARE candidate share"
    }

    /**
     * The verdict [CRITERION] yields for [inputs] — the whole of the decision, in code.
     *
     * Total and dull on purpose: two thresholds over one derived quantity, three mutually
     * exclusive branches.
     */
    private fun verdictOf(inputs: List<SubjectAllocation>): String {
        if (inputs.isEmpty()) return "INCONCLUSIVE"
        val finite = inputs.filter { it.growthShare.isFinite() }
        val candidates = finite.count { it.growthShare >= CANDIDATE_SHARE }
        val fires = finite.isNotEmpty() && candidates * 2 > inputs.size
        val retires = finite.size == inputs.size && finite.all { it.growthShare < NEGLIGIBLE_SHARE }
        return when {
            fires -> "FIRES"
            retires -> "RETIRES"
            else -> "INCONCLUSIVE"
        }
    }

    private companion object {

        const val GAP_ID: String = "[BEN1-28]"

        const val SUBJECT_PARAM: String = "subject"

        const val DIRECTION_PARAM: String = "direction"

        const val SUBJECT: String =
            "allocation per delta (gc.alloc.rate.norm, -prof gc) for the eight set-shaped " +
                "subjects x both directions under drive=REAL — the operator-level half of " +
                "[BEN1-28], which computenet-x9e.14 left open because neither of its arms " +
                "attached a profiler"

        /**
         * A generous upper bound on what ONE delta's `TagState` map growth can allocate.
         *
         * Derived, not measured: a `HashMap.Node` is 32 B on 64-bit HotSpot with
         * compressed oops, amortized table-array doubling adds ~16 B per entry, and a tag
         * object plus a boxed element plus a per-source duplicate for the two-source
         * subjects is still comfortably under 512 B. Set high on purpose — the bound is
         * one-sided, so overstating it can only make the criterion HARDER to retire and
         * easier to call a candidate.
         */
        const val TAG_ENTRY_BUDGET_BYTES: Double = 512.0

        /**
         * The upper-bound share at which tag-map growth is a live candidate for dominating.
         *
         * Half. If growth could account for half or more of a delta's allocation, calling
         * it dominant is a claim the allocation data does not exclude.
         */
        const val CANDIDATE_SHARE: Double = 0.5

        /**
         * The upper-bound share below which tag-map growth cannot be the allocation story.
         *
         * A tenth. A row where growth is at most 10% of per-delta allocation has nine
         * tenths of its garbage coming from somewhere else, and a claim that growth
         * dominates its ALLOCATION is refuted for that row.
         */
        const val NEGLIGIBLE_SHARE: Double = 0.1

        /**
         * The criterion, fixed before the sweep reported anything.
         *
         * Lowercase "fires"/"retires" is load-bearing: `Findings.entry` counts whole-word
         * FIRES / RETIRES / INCONCLUSIVE case-sensitively and refuses a statement holding
         * other than exactly one.
         */
        const val CRITERION: String =
            "the criterion applied is that, with TAG_ENTRY_BUDGET_BYTES=512 a generous " +
                "derived upper bound on what one delta's TagState map growth can allocate, " +
                "the bound on growth's share of a row's per-delta allocation is 512/(B/op), " +
                "and the question fires only if a strict majority of the set-shaped rows " +
                "put that bound at 0.5 or above, retires only if every row puts it below " +
                "0.1, and is otherwise undecided"
    }
}
