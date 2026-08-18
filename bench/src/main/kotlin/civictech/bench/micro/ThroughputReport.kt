package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.MeasuringJvm
import civictech.bench.MeasuringJvmUnknownException
import civictech.bench.NOISE_FLOOR
import civictech.bench.Reportability
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import civictech.bench.classify
import java.io.File

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
 *      -rf csv -rff /abs/path/throughput.csv 2>&1 | tee /abs/path/throughput.log
 * ```
 *
 * The `| tee` is not decoration and the log's name is not free — see [runLogFor]. JMH's
 * results file records no fact about the JVM that produced it; its stdout banner records
 * all three a findings entry needs (`[BEN1-23]`). Without that log beside the results
 * file, [renderRun] REFUSES, which is exactly the point: before `computenet-hqid` the
 * renderer silently reported its OWN JVM instead, and two entries shipped that way.
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
 * The run log is NOT a second property: it is found beside the results file, by
 * [runLogFor]'s convention. That keeps the honest path reachable from the entry point
 * that already exists, without a new system property that `bench/build.gradle.kts`
 * would have to forward and that would silently arrive unset if it ever stopped doing
 * so — an unforwarded property is exactly the kind of quiet failure this ticket is
 * about.
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
            val scoreError = number(errorIndex, "score error")
            // Measured 2026-08-18 (and confirmed against JMH 1.37's own
            // AbstractStatistics.getMeanErrorAt: it returns NaN whenever getN() <= 2 and
            // only computes a finite value via TDistribution(getN() - 1) once getN() > 2):
            // a run with two or fewer measurement samples reports the error as literally
            // `NaN`; three is the fewest that yields a finite one. That row carries no
            // dispersion at all, so it cannot be classified against NOISE_FLOOR — and
            // `BenchResult` would refuse it anyway, on the weaker message "dispersion must
            // be finite". Refusing it here says the useful thing: the RUN was too small,
            // not the number too wide.
            if (!scoreError.isFinite()) {
                throw ThroughputReportException(
                    "JMH results row ${offset + 1} reports error '${fields[errorIndex]}' " +
                        "for '${fields[benchmarkIndex]}': the run produced too few " +
                        "samples for a dispersion (JMH writes NaN at or below two " +
                        "measurement samples), so the row cannot be classified against " +
                        "NOISE_FLOOR $NOISE_FLOOR. Re-run with at least three measurement " +
                        "iterations"
                )
            }
            JmhRow(
                benchmark = fields[benchmarkIndex],
                mode = fields[modeIndex],
                score = number(scoreIndex, "score"),
                scoreError = scoreError,
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
     * @param env the environment every row was measured under, STATED by the caller.
     *   One [RunEnvironment] for the whole file, because one file is one run; a caller
     *   merging two runs' files would be constructing exactly the mixed-environment
     *   table [FindingsTable] refuses. This overload cannot check that [env] describes
     *   the run — it is for callers holding an environment they can already vouch for
     *   (a fixture, or one built by [RunEnvironment.forRun] from a [MeasuringJvm]).
     *   [renderRun] is the entry point that derives one from the run's artifacts and
     *   refuses when it cannot.
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

    /**
     * Where the run log of a results file must sit: beside it, same base name, `.log`.
     *
     * A convention rather than a second path argument, so that the pairing is a
     * property of the artifacts on disk — a results file whose log has been lost or was
     * never captured is *visibly* incomplete, and [renderRun] says so by name instead
     * of rendering something plausible.
     *
     * The pairing is by NAME ONLY, and nothing verifies it. Neither [renderRun] nor
     * [MeasuringJvm.fromJmhLog] cross-checks the log against the results file beside it —
     * not the benchmarks each names, not their modification times, not the row counts. A
     * log belonging to a different sweep, or one written by hand, is accepted as the
     * measuring JVM's record so long as it carries a well-formed banner. That is the
     * deliberate limit of an artifact-based check and it should be read as its scope, not
     * as a gap: it makes the honest path the easy one and makes a LOST log visible, but
     * it cannot make a SUBSTITUTED one detectable. What this closes is the accidental
     * failure — a renderer answering from its own process because no artifact recorded
     * the run — not a determined one.
     */
    fun runLogFor(results: File): File =
        File(results.absoluteFile.parentFile, results.nameWithoutExtension + ".log")

    /**
     * Renders a run's findings entries from the run's own artifacts — the honest entry
     * point, and the only one that derives a [RunEnvironment] rather than being handed
     * one (`[BEN1-23]`, `computenet-hqid`).
     *
     * Reads [results] for the numbers and [runLogFor]`(results)` for the JVM that
     * produced them, and REFUSES — [MeasuringJvmUnknownException] — when that log is
     * absent or carries no JMH banner. It does not fall back to this process's
     * `java.vendor`, `java.version` or heap; that fallback is what shipped a REAL-drive
     * entry reading `Eclipse Adoptium/21.0.11 · heap -Xmx2g` for a sweep measured on
     * Homebrew JDK 26.0.1 with no VM options, and the code that could produce it no
     * longer exists (see [RunEnvironment.forRun]).
     *
     * Refusing is a first-class outcome here, not a degraded one. This epic's governing
     * property is that a number nobody can stand behind is refused rather than
     * published; an environment line nobody can stand behind is the same defect one
     * level down, and is refused the same way.
     *
     * @param results the JMH `-rf csv` results file.
     * @param harnessCommitSha the harness commit that produced the run. No artifact
     *   records it, so it stays the caller's to state — [RunEnvironment] refuses to
     *   exist without it.
     * @throws MeasuringJvmUnknownException if the measuring JVM's vendor, version or
     *   heap cannot be established from the run's artifacts.
     * @throws ThroughputReportException if [results] is not a readable file, or its
     *   contents cannot honestly become rows.
     */
    fun renderRun(
        results: File,
        harnessCommitSha: String,
        date: String,
        subject: String,
        trigger: TriggerClaim = TriggerClaim.None,
    ): Report {
        if (!results.isFile) {
            throw ThroughputReportException(
                "no JMH results file at ${results.absolutePath}"
            )
        }
        val log = runLogFor(results)
        if (!log.isFile) {
            throw MeasuringJvmUnknownException(
                "cannot establish the measuring JVM: ${results.absolutePath} has no run " +
                    "log beside it at ${log.absolutePath}. A JMH results file records no " +
                    "JVM vendor, version or heap — only JMH's stdout banner does — so " +
                    "this entry would otherwise report the JVM of the process rendering " +
                    "it, which is not the one that measured. Re-run the sweep teeing its " +
                    "output to that path (`... -rf csv -rff ${results.absolutePath} " +
                    "2>&1 | tee ${log.absolutePath}`), or render a run that kept its log"
            )
        }
        val env = RunEnvironment.forRun(
            measuringJvm = MeasuringJvm.fromJmhLog(log.readText(), log.absolutePath),
            jmhMode = JMH_MODE,
            forkCount = FORKS,
            warmupIterations = WARMUP_ITERATIONS,
            measurementIterations = MEASUREMENT_ITERATIONS,
            harnessCommitSha = harnessCommitSha,
        )
        return render(results.readText(), env, date, subject, trigger)
    }

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
