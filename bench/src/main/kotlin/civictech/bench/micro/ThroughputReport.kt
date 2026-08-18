package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.NOISE_FLOOR
import civictech.bench.Reportability
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import civictech.bench.classify

/**
 * Thrown when a JMH results file cannot be turned into [BenchResult] rows honestly.
 *
 * This is NOT one of F3's refusals and deliberately does not overlap them: everything
 * about drive homogeneity, environment homogeneity, per-row labels and dispersion is
 * decided by [FindingsTable] and [Findings.entry], which this renderer *calls* rather
 * than reimplements. What this exception covers is strictly upstream of them — a
 * results file whose columns are missing, whose confidence level is not the 99.9% that
 * [BenchResult.dispersion] is defined as, whose benchmark name does not say which
 * [Drive] produced it, or which carries no `subject`/`direction` parameter to label a
 * row with. Those rows cannot become a `BenchResult` at all, so there is nothing for
 * F3 to refuse.
 */
class ThroughputReportException(message: String) : IllegalArgumentException(message)

/**
 * One parsed record of a JMH CSV results file — the raw columns, before any
 * interpretation.
 *
 * @param benchmark the fully qualified `Class.method` name JMH reported.
 * @param mode JMH's mode column (`thrpt`, `avgt`, ...), carried through untouched.
 * @param score the primary metric's score.
 * @param scoreError the primary metric's error at 99.9% confidence — the column whose
 *   header the parser verifies actually says 99.9%, because [BenchResult.dispersion] is
 *   *defined* as that statistic and a silently different confidence level would make
 *   every dispersion classification wrong by an unstated factor.
 * @param unit the score's unit, e.g. `ops/s`.
 * @param params the `Param: <name>` columns, keyed by name without the prefix.
 */
data class JmhRow(
    val benchmark: String,
    val mode: String,
    val score: Double,
    val scoreError: Double,
    val unit: String,
    val params: Map<String, String>,
) {

    /** The benchmark's simple method name — the last dot-separated segment. */
    val method: String get() = benchmark.substringAfterLast('.')
}

/** A [BenchResult] together with the per-row label [FindingsTable] will carry it under. */
data class LabelledResult(val label: String, val result: BenchResult)

/**
 * A row that was NOT rendered, and why.
 *
 * The omission list is the point of this type. A renderer that dropped a too-dispersed
 * row and rendered the rest would produce a table that looks complete and is not — the
 * reader cannot see that the noisiest measurements are the missing ones, which is
 * precisely the shape `[BEN1-25]` exists to prevent. So every excluded row is carried
 * here and named in [Report.text], next to the table it is missing from.
 */
data class Omission(
    val label: String,
    val drive: Drive,
    val result: BenchResult,
) {
    fun describe(): String =
        "$label (drive=$drive): relative dispersion ${result.relativeDispersion} exceeds " +
            "NOISE_FLOOR $NOISE_FLOOR — value=${result.value} ± ${result.dispersion} " +
            "${result.unit}; Unreportable, excluded from the table"
}

/**
 * One drive's rendered findings entry, plus that drive's omissions.
 *
 * [entry] is `null` when every row of this drive classified [Reportability.Unreportable]:
 * there is then no table to render (a [FindingsTable] cannot be empty), and inventing
 * one would be the exact dishonesty this chain refuses. The drive still appears in the
 * report — as a stated absence with its omissions named — rather than vanishing.
 */
data class DriveReport(
    val drive: Drive,
    val entry: String?,
    val omitted: List<Omission>,
)

/** Every drive's report, in [Drive] declaration order, plus the flattened omission list. */
data class Report(val perDrive: List<DriveReport>, val omissions: List<Omission>) {

    /**
     * The renderer's output: each drive's findings entry (or the stated absence of one),
     * each followed by that drive's omission list — including an explicit "none" line,
     * so a reader can tell "nothing was omitted" apart from "the omission list was not
     * rendered".
     */
    fun text(): String = perDrive.joinToString(separator = "\n\n") { report ->
        buildString {
            appendLine(
                report.entry
                    ?: "## (no entry for drive=${report.drive}) — every row classified " +
                    "Unreportable against NOISE_FLOOR $NOISE_FLOOR; see the omissions below"
            )
            appendLine()
            appendLine("Omitted rows (drive=${report.drive}):")
            if (report.omitted.isEmpty()) {
                append("- none")
            } else {
                append(report.omitted.joinToString(separator = "\n") { "- ${it.describe()}" })
            }
        }
    }
}

/**
 * Turns a JMH results file into findings entries, through F3's writer.
 *
 * ## The exact command that produces the file this parses
 *
 * ```
 * ./gradlew :bench:jmhJar
 * java -jar bench/build/libs/bench-jmh.jar OperatorThroughputBenchmark \
 *      -rf csv -rff /abs/path/throughput.csv
 * ```
 *
 * CSV, not JSON, and for a reason worth stating: `:bench` depends on `:kernel` and
 * `:testkit` and nothing else (`[BEN1-03]`), so there is no JSON parser on this
 * module's classpath and adding one to read a results file would be a dependency
 * bought for a formatting choice. JMH's CSV format carries everything a
 * [BenchResult] needs — score, error at 99.9% confidence, unit — plus one
 * `Param: <name>` column per `@Param`, which is where the subject and direction of a
 * row come from.
 *
 * ## What this object does NOT do
 *
 * It never writes `doc/bench/findings.md`, or any other file. [Findings] validates and
 * renders entry text only; appending a rendered entry to that document is the
 * measurement task's hand step, performed by whoever ran the sweep and can vouch for
 * the machine it ran on.
 *
 * It also does not decide any of F3's refusals for itself. Mixed drive and mixed
 * environment are refused by [FindingsTable]'s constructor; a missing date, subject or
 * per-row label, and any [Reportability.Unreportable] result that reaches it, are
 * refused by [Findings.entry]. This renderer groups by [Drive] so a mixed table is
 * never constructed, and excludes-and-names Unreportable rows so an entry is never
 * refused for containing one — but the refusals themselves stay where F3 put them, and
 * a caller that builds a table by hand still meets every one of them.
 *
 * ## Runnable without a benchmark
 *
 * `ThroughputReportRenderTest` in `bench/src/test/kotlin/civictech/bench/micro` is the
 * `@Tag("bench")` entry point: it reads the results file path from the
 * `civictech.bench.jmhResults` system property, which `bench/build.gradle.kts`
 * forwards to the test JVM, and prints [Report.text].
 *
 * ```
 * ./gradlew :bench:test -PbenchOnly=true --rerun \
 *   --tests 'civictech.bench.micro.ThroughputReportRenderTest' \
 *   -Dcivictech.bench.jmhResults=/abs/path/throughput.csv \
 *   -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
 * ```
 *
 * `-PbenchOnly=true` is required: `@Tag("bench")` is excluded unconditionally from the
 * default test task (`[BEN1-09]`..`[BEN1-11]`), which is what keeps `:bench:test`
 * sub-second in the six required checks.
 */
object ThroughputReport {

    /** Header prefix of JMH's per-`@Param` columns. */
    private const val PARAM_PREFIX = "Param: "

    /** The `@Param` name whose value names the operator a row measured. */
    const val SUBJECT_PARAM: String = "subject"

    /** The `@Param` name whose value names the delta direction a row measured. */
    const val DIRECTION_PARAM: String = "direction"

    // ---------------------------------------------------------------------------------
    // The JMH configuration `OperatorThroughputBenchmark` runs under, held HERE rather
    // than only on that class.
    //
    // The benchmark lives in the `jmh` source set, which `test` cannot see (both compile
    // against `main`, neither against the other). `RunEnvironment` requires the mode,
    // fork count and iteration counts as required fields, so the rendering entry point in
    // `bench/src/test` has to be able to name them. Declaring them here as `const val`,
    // and having the benchmark's own annotations reference these constants, keeps one
    // definition instead of two that could silently disagree — a recorded
    // `RunEnvironment` claiming `forks=2` for a run that used a different number is
    // exactly the unre-derivable mislabelling `[BEN1-23]` exists to prevent.
    // ---------------------------------------------------------------------------------

    /** `Mode.Throughput`'s name, as `RunEnvironment.jmhMode` records it. */
    const val JMH_MODE: String = "Throughput"

    /** `@Fork` count. */
    const val FORKS: Int = 2

    /** `@Warmup` iteration count. */
    const val WARMUP_ITERATIONS: Int = 5

    /** `@Measurement` iteration count. */
    const val MEASUREMENT_ITERATIONS: Int = 10

    /** `@Warmup`/`@Measurement` per-iteration time, in seconds. */
    const val ITERATION_SECONDS: Int = 1

    /**
     * Deltas per measured invocation — the benchmark's `@OperationsPerInvocation`, so a
     * reported `ops/s` is deltas per second and not batches per second.
     */
    const val DELTAS_PER_BATCH: Int = 512

    /**
     * Parses JMH's CSV results format into raw rows.
     *
     * Header-driven rather than positional: columns are located by name, so a JMH
     * release that adds a secondary-metric column, or reorders the `Param:` columns,
     * does not silently shift the score one field to the left. A missing required
     * column throws [ThroughputReportException] naming it, rather than producing a row
     * with a plausible wrong number in it.
     */
    fun parseCsv(csv: String): List<JmhRow> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            throw ThroughputReportException("JMH results file is empty — no header row")
        }
        val header = splitCsvLine(lines.first())

        fun require(name: String): Int = header.indexOf(name).also {
            if (it < 0) {
                throw ThroughputReportException(
                    "JMH results file has no '$name' column; header was $header"
                )
            }
        }

        val benchmarkIndex = require("Benchmark")
        val modeIndex = require("Mode")
        val scoreIndex = require("Score")
        val unitIndex = require("Unit")

        // The error column's header carries the confidence level, e.g.
        // "Score Error (99.9%)". BenchResult.dispersion is DEFINED as the error at
        // 99.9% confidence, so a file produced at some other confidence level must be
        // refused rather than read as though it were: the number would classify against
        // NOISE_FLOOR under a statistic it was never measured as.
        val errorIndex = header.indexOfFirst { it.startsWith("Score Error") }
        if (errorIndex < 0) {
            throw ThroughputReportException(
                "JMH results file has no 'Score Error (...)' column; header was $header"
            )
        }
        val errorHeader = header[errorIndex]
        if (!errorHeader.contains("99.9")) {
            throw ThroughputReportException(
                "JMH results file reports '$errorHeader', not the error at 99.9% " +
                    "confidence that BenchResult.dispersion is defined as"
            )
        }

        val paramIndices = header.withIndex()
            .filter { (_, name) -> name.startsWith(PARAM_PREFIX) }
            .associate { (index, name) -> name.removePrefix(PARAM_PREFIX) to index }

        return lines.drop(1).mapIndexed { offset, line ->
            val fields = splitCsvLine(line)
            if (fields.size < header.size) {
                throw ThroughputReportException(
                    "JMH results row ${offset + 1} has ${fields.size} fields, header " +
                        "declares ${header.size}: $line"
                )
            }
            fun number(index: Int, what: String): Double =
                fields[index].toDoubleOrNull() ?: throw ThroughputReportException(
                    "JMH results row ${offset + 1} has non-numeric $what " +
                        "'${fields[index]}': $line"
                )
            JmhRow(
                benchmark = fields[benchmarkIndex],
                mode = fields[modeIndex],
                score = number(scoreIndex, "score"),
                scoreError = number(errorIndex, "score error"),
                unit = fields[unitIndex],
                params = paramIndices.mapValues { (_, index) -> fields[index] },
            )
        }
    }

    /**
     * Which [Drive] a benchmark name says produced it.
     *
     * The drive is read off the benchmark's *name* because that is what makes it
     * legible in every artifact JMH produces — the console log, the CSV, a pasted
     * excerpt — and `[BEN1-26]`/`[BEN1-27]` turn on a result never losing it.
     * `OperatorThroughputBenchmark` therefore declares SIM and REAL as separately named
     * `@Benchmark` methods rather than one method with an ambient drive.
     *
     * The name is split on camel-case and non-alphanumeric boundaries and the resulting
     * tokens counted, exactly one of which must be `sim` or `real` — the same shape
     * [Findings]'s trigger line uses for its three verdict words, and for the same
     * reason: a benchmark named `simVersusReal` states two drives and therefore states
     * none, and must be refused rather than resolved by whichever branch the code
     * happens to test first.
     */
    fun driveOf(row: JmhRow): Drive {
        val tokens = tokenize(row.method)
        val sim = tokens.count { it == "sim" }
        val real = tokens.count { it == "real" }
        if (sim + real != 1) {
            throw ThroughputReportException(
                "benchmark '${row.benchmark}' does not name exactly one drive: its " +
                    "method name yields tokens $tokens, matching $sim 'sim' and $real " +
                    "'real'. SIM and REAL must be separately named benchmarks so a " +
                    "result cannot lose the regime that produced it [BEN1-26]"
            )
        }
        return if (sim == 1) Drive.SIM else Drive.REAL
    }

    private fun tokenize(name: String): List<String> =
        name.split(Regex("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }

    /**
     * The per-row label: the row's `subject` and `direction` parameters, which is what
     * distinguishes an insert row from a retract row of the same operator when both
     * carry the same unit.
     *
     * A row missing either parameter cannot be labelled, and is refused here rather
     * than labelled `"unknown"` — [Findings.entry] would accept a non-blank invented
     * label perfectly happily, so the honesty has to be upstream of it.
     */
    fun labelOf(row: JmhRow): String {
        val subject = row.params[SUBJECT_PARAM]
        val direction = row.params[DIRECTION_PARAM]
        if (subject.isNullOrBlank() || direction.isNullOrBlank()) {
            throw ThroughputReportException(
                "benchmark '${row.benchmark}' carries no usable '$SUBJECT_PARAM'/" +
                    "'$DIRECTION_PARAM' parameters (found ${row.params}); a row that " +
                    "cannot say which operator and direction it measured cannot be " +
                    "labelled [BEN1-30]"
            )
        }
        return "$subject ${direction.lowercase()}"
    }

    /**
     * Turns parsed rows into labelled [BenchResult]s under [env].
     *
     * [BenchResult.drive] is [driveOf]'s answer, and [env] is supplied by the caller —
     * the run's environment is a fact about the machine and the JMH configuration that
     * a results file does record in part, but the harness commit SHA it does not record
     * at all, so [RunEnvironment] stays the caller's to state (and [RunEnvironment]
     * itself refuses to exist with a field missing).
     */
    fun toResults(rows: List<JmhRow>, env: RunEnvironment): List<LabelledResult> =
        rows.map { row ->
            LabelledResult(
                label = labelOf(row),
                result = BenchResult(
                    value = row.score,
                    unit = row.unit,
                    dispersion = row.scoreError,
                    drive = driveOf(row),
                    env = env,
                ),
            )
        }

    /**
     * Renders one findings entry per [Drive] present in [csv].
     *
     * Grouping by drive is what makes the entries constructible at all: a single table
     * over a mixed-drive sweep is refused by [FindingsTable]'s constructor
     * (`[BEN1-27]`), and this renderer does not work around that refusal — it respects
     * it by never assembling such a table, and a caller who assembles one by hand still
     * gets refused.
     *
     * Rows classifying [Reportability.Unreportable] against [NOISE_FLOOR] are excluded
     * from their drive's table and returned in [Report.omissions], where [Report.text]
     * names each one. They are not quietly dropped and the threshold is not widened to
     * admit them: a measurement too dispersed to report is information about the
     * benchmark or the host, and the honest response is to say which rows those were.
     *
     * @param csv the contents of a JMH CSV results file.
     * @param env the environment every row was measured under. One [RunEnvironment] for
     *   the whole file, because one file is one run; a caller merging two runs' files
     *   would be constructing exactly the mixed-environment table [FindingsTable]
     *   refuses.
     * @param date the entry date, passed through to [Findings.entry], which refuses a
     *   blank one.
     * @param subject what was measured, passed through to [Findings.entry], which
     *   refuses a blank one. The drive is not the caller's to repeat here — each entry
     *   already renders `drive=` from its own table.
     * @param trigger what the entry claims about a cited gap's trigger question.
     *   Defaults to [TriggerClaim.None], which [Findings.entry] renders as an explicit
     *   MARKED INCOMPLETE line rather than as a finding.
     */
    fun render(
        csv: String,
        env: RunEnvironment,
        date: String,
        subject: String,
        trigger: TriggerClaim = TriggerClaim.None,
    ): Report = renderResults(toResults(parseCsv(csv), env), date, subject, trigger)

    /** [render]'s second half, exposed so a caller can render results it built itself. */
    fun renderResults(
        results: List<LabelledResult>,
        date: String,
        subject: String,
        trigger: TriggerClaim = TriggerClaim.None,
    ): Report {
        if (results.isEmpty()) {
            throw ThroughputReportException(
                "no results to render — a findings entry over zero rows would be a " +
                    "table asserting nothing"
            )
        }
        val byDrive = results.groupBy { it.result.drive }
        val perDrive = Drive.values().filter { byDrive.containsKey(it) }.map { drive ->
            val rows = byDrive.getValue(drive)
            val (reportable, unreportable) = rows.partition {
                classify(it.result) == Reportability.Reportable
            }
            val omitted = unreportable.map { Omission(it.label, drive, it.result) }
            val entry = if (reportable.isEmpty()) {
                null
            } else {
                Findings.entry(
                    date = date,
                    subject = subject,
                    results = FindingsTable(
                        results = reportable.map { it.result },
                        labels = reportable.map { it.label },
                    ),
                    trigger = trigger,
                )
            }
            DriveReport(drive = drive, entry = entry, omitted = omitted)
        }
        return Report(perDrive = perDrive, omissions = perDrive.flatMap { it.omitted })
    }

    /**
     * Splits one CSV line into unquoted fields, honouring `""` as an escaped quote.
     *
     * Hand-written rather than delegated, because `:bench` takes no dependency for a
     * results file and JMH's own writer emits exactly this dialect: every field either
     * bare (numbers) or double-quoted, `,` as the separator, `""` for an embedded
     * quote.
     */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                inQuotes && ch == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
