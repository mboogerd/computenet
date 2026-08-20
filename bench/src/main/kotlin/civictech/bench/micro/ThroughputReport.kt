package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.HostFacts
import civictech.bench.HostFactsUnknownException
import civictech.bench.MeasuringJvm
import civictech.bench.MeasuringJvmUnknownException
import civictech.bench.NOISE_FLOOR
import civictech.bench.Reportability
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.RunKnobsUnknownException
import civictech.bench.TriggerClaim
import civictech.bench.classify
import java.io.File

/**
 * Thrown when a JMH results file cannot be turned into [BenchResult] rows honestly.
 *
 * This is NOT one of F3's refusals and deliberately does not overlap them: everything
 * about drive homogeneity, environment homogeneity, per-row labels and dispersion is
 * decided by [FindingsTable] and [Findings.entry], which this renderer *calls* rather
 * than reimplements. What this exception covers is mostly upstream of them — a
 * results file whose columns are missing, whose confidence level is not the 99.9% that
 * [BenchResult.dispersion] is defined as, whose benchmark name does not say which
 * [Drive] produced it, or which carries no value for the `@Param` columns that label its
 * rows ([RowLabel]). Those rows cannot become a `BenchResult` at all, so there is nothing
 * for F3 to refuse.
 *
 * One case is NOT upstream of F3 but beside it: two rows of one drive that carry the same
 * label ([ThroughputReport.renderResults]). A duplicated label is perfectly well-formed to
 * [FindingsTable] — non-blank, one per result — so F3 cannot see it, and yet the table it
 * renders is one whose reader cannot tell two measurements apart. The refusal has to live
 * here because the label COLUMNS are chosen here.
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

    /**
     * The benchmark's simple CLASS name — the segment before [method].
     *
     * This is the key [RowLabel.forBenchmark] resolves a row's label columns under, so a
     * results file states which `@Param`s label its rows by naming its own benchmark, and
     * a caller does not have to.
     */
    val benchmarkClass: String get() = simpleClassOf(benchmark)
}

/** The `Class` half of JMH's `pkg.Class.method` benchmark name, without its package. */
private fun simpleClassOf(benchmark: String): String =
    benchmark.substringBeforeLast('.').substringAfterLast('.')

/**
 * Which `@Param` columns of a sweep label its rows, and how their values compose.
 *
 * The renderer used to hard-code `subject`/`direction` — `OperatorThroughputBenchmark`'s
 * two parameters — into [ThroughputReport.labelOf], which made [ThroughputReport.renderRun]
 * unusable for every other sweep in this module: `BoundedReadBenchmark` (`scale`),
 * `CellFootprintBenchmark` (`family`, `scale`), `FanOutScalingBenchmark` (`degree`). The
 * consequence was measured rather than predicted: `computenet-x9e.6.4` rendered the
 * V1C-BENCH E1 entry through a ~60-line throwaway `E1Render.java` driver against the JMH
 * fat jar, because the shipped entry point could not label its rows. Every honesty-bearing
 * STEP of that render was shipped code — the reviewer re-ran it and got a byte-identical
 * block — but the invocation was hand-written and uncommitted, which is a hand-written
 * step in the honesty path and an entry harder to re-derive than it needs to be
 * (computenet-x9e.10).
 *
 * This type is DATA, deliberately, and not a `(JmhRow) -> String` seam. A caller that
 * could supply the label TEXT could supply `"unknown"`, and [Findings.entry] accepts any
 * non-blank label perfectly happily — so the refusal of an unlabellable row has to stay
 * inside [ThroughputReport.labelOf], the only thing that reads a row's parameters. What a
 * caller chooses here is which COLUMNS name a row, never what the name says.
 *
 * @param params the `@Param` names whose values name a row, in the order they render.
 *   Every parameter a sweep varied belongs here: one left out is a dimension the table
 *   cannot show, which is how two distinct measurements end up under one label —
 *   [ThroughputReport.renderResults] refuses exactly that rather than rendering it.
 * @param lowercased the subset of [params] whose values render lowercased. Case is a
 *   display choice, not a measurement fact, and this is not cosmetic: the landed rows in
 *   `doc/bench/findings.md` read `FILTER insert`, from JMH's `INSERT`/`RETRACT` enum
 *   constants, so those entries stay re-derivable only while this stays expressible.
 * @param includeMethod whether the `@Benchmark` method's own name prefixes the label.
 *   Needed when one class's methods share a parameter set — `BoundedReadBenchmark` measures
 *   `realDirect` and `realHostedSnapshotOf` over the same three `scale`s, so `scale` alone
 *   names six rows with three names. (Those two were called `direct` and
 *   `hostedSnapshotOf` until computenet-7w4e, which is the name the already-published E1
 *   entries in `doc/bench/findings.md` carry.)
 */
data class RowLabel(
    val params: List<String>,
    val lowercased: Set<String> = emptySet(),
    val includeMethod: Boolean = false,
) {

    init {
        require(params.isNotEmpty()) {
            "RowLabel requires at least one @Param column: a row labelled by nothing " +
                "cannot say what it measured"
        }
        require(params.none { it.isBlank() }) {
            "RowLabel params must all be non-blank, found $params"
        }
        require(params.distinct().size == params.size) {
            "RowLabel params must be distinct, found $params"
        }
        require(params.containsAll(lowercased)) {
            "RowLabel lowercased names ${lowercased - params.toSet()}, which is not in " +
                "params $params"
        }
    }

    companion object {

        /**
         * `OperatorThroughputBenchmark`'s columns, and the renderer's historical
         * behaviour: `subject` verbatim, `direction` lowercased, rendering `FILTER insert`.
         *
         * Held as a named value rather than as a default buried in a signature because
         * three landed entries in `doc/bench/findings.md` are labelled this way, and this
         * is the thing that has to keep producing those labels.
         */
        val SUBJECT_DIRECTION: RowLabel = RowLabel(
            params = listOf(ThroughputReport.SUBJECT_PARAM, ThroughputReport.DIRECTION_PARAM),
            lowercased = setOf(ThroughputReport.DIRECTION_PARAM),
        )

        /**
         * The label columns of every `@Param`-bearing benchmark in `bench/src/jmh/kotlin`,
         * by [JmhRow.benchmarkClass].
         *
         * This is what makes the shipped entry point usable for a sweep nobody has
         * rendered before WITHOUT a new invocation surface: `ThroughputReportRenderTest`
         * names a results file, the results file names its benchmark, and the benchmark
         * names its columns here. No hand-written driver, and no per-sweep system property
         * that `bench/build.gradle.kts` would have to forward and that would silently
         * arrive unset if it ever stopped — the same reasoning [ThroughputReport.runLogFor]
         * gives for finding the run log beside the results file instead.
         *
         * Held HERE rather than on the benchmark classes for the reason the constants
         * further down this file are: the `jmh` source set is invisible to `main` and
         * `test` (all three compile against `main`, none against each other), so a fact
         * anything else needs to name has to live in `main`.
         *
         * A class absent from this map is REFUSED by [forBenchmark], not guessed at.
         * `ThroughputReportTest` pins the map against the benchmark sources themselves —
         * every `@Param`-bearing benchmark file present, and its entry naming exactly the
         * parameters that file declares — so a sweep that gains a dimension cannot render
         * under a label that silently omits it.
         *
         * `SmokeBenchmark` is absent because it declares no `@Param` at all: its rows
         * cannot be labelled from parameters, and the noise-floor entry it produced was
         * built by hand through [ThroughputReport.renderResults] (`run 1`/`run 2`/`run 3`),
         * which is the documented route for a sweep whose rows are not a parameter cross
         * product.
         *
         * Registration is not on its own sufficient to render a sweep, and this is where
         * the rest is named. THREE things have to hold, of which a registration here is
         * only the first:
         *
         * 1. **The label columns are declared** — registered here, or passed as an
         *    explicit [RowLabel] at the call site. [forBenchmark] REFUSES an unregistered
         *    class rather than guessing which columns name a row (`[BEN1-30]`).
         * 2. **The benchmark prints a host-facts banner** from inside the measuring fork,
         *    from a `@Setup(Level.Trial)` hook. [HostFacts.fromJmhLog] is the only source
         *    `RunEnvironment.forRun`'s JMH-sweep overload accepts, and it refuses a log
         *    that carries no such line rather than answering with the RENDERING host
         *    (computenet-yhbd).
         * 3. **Each `@Benchmark` method names its regime.** [ThroughputReport.driveOf]
         *    tokenizes the method name and requires exactly one `sim`/`real` token,
         *    because a result must never lose the drive that produced it (`[BEN1-26]`,
         *    `[BEN1-27]`); stating the drive at the render site instead is the exact
         *    substitution those requirements exist to prevent.
         *
         * As of computenet-7w4e all four classes registered below satisfy all three: it
         * added the `@Setup(Level.Trial)` banner hook to `CellFootprintBenchmark` and to
         * both of `BoundedReadBenchmark`'s state classes, and renamed the latter's methods
         * to `realDirect`/`realHostedSnapshotOf` (from `direct`/`hostedSnapshotOf`, which
         * is the name the already-published E1 entries in `doc/bench/findings.md` carry).
         * That is a fact about those four classes and NOT a property of this type — a
         * benchmark added tomorrow that is registered here and does neither of (2) nor (3)
         * still renders nothing, and should. `ThroughputReportTest` pins (2) and (3)
         * against the sources under `bench/src/jmh/kotlin` for every benchmark, present
         * and future, so that failure lands at `:bench:test` speed rather than after a JMH
         * sweep has been paid for.
         *
         * A FOURTH obstacle is still open, and none of the three closes it: this renderer
         * parses the primary `Score` column, so a benchmark whose answer is a JMH
         * SECONDARY metric cannot be rendered at all. `CellFootprintBenchmark` under
         * `-prof gc` is the live case — its `gc.alloc.rate.norm` figures are read by hand
         * off stdout, deliberately. That is a renderer gap, tracked as `computenet-6zqz`
         * and not closed here; the same class's plain time-per-`snapshot()` sweep, run
         * without `-prof gc`, renders normally.
         *
         * Every failure named above is a REFUSAL rather than a wrong entry, which is what
         * makes it safe to leave each standing until it is closed.
         */
        val REGISTERED: Map<String, RowLabel> = mapOf(
            "OperatorThroughputBenchmark" to SUBJECT_DIRECTION,
            "CellFootprintBenchmark" to RowLabel(params = listOf("family", "scale")),
            // Two @Benchmark methods over one `scale`: without the method name, three
            // labels for six rows.
            "BoundedReadBenchmark" to RowLabel(params = listOf("scale"), includeMethod = true),
            "FanOutScalingBenchmark" to RowLabel(params = listOf("degree")),
        )

        /**
         * The label columns [REGISTERED] for the class of a JMH benchmark name, or a
         * refusal naming the class.
         *
         * Refusing is the point: a renderer that fell back to "label by whatever params
         * are present, in map order" would render a plausible table for a sweep nobody
         * decided the labels of, and the order would come from a `LinkedHashMap` of CSV
         * columns rather than from a reviewed choice.
         */
        fun forBenchmark(benchmark: String): RowLabel {
            val simple = simpleClassOf(benchmark)
            return REGISTERED[simple] ?: throw ThroughputReportException(
                "no label columns are registered for benchmark class '$simple' (from " +
                    "'$benchmark'); RowLabel.REGISTERED names ${REGISTERED.keys.sorted()}. " +
                    "Add an entry naming every @Param that benchmark declares, or pass a " +
                    "RowLabel explicitly — which columns name a row is a reviewed choice " +
                    "and the renderer will not guess it [BEN1-30]"
            )
        }
    }
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
 * The same banner is also the only record of the knobs the run RESOLVED — mode, forks,
 * warmup and measurement iterations — because `-f`/`-wi`/`-i` override the benchmark
 * class's annotations and the results file carries no such columns. `RunKnobs.fromJmhLog`
 * reads them and [renderRun] refuses without them, for the same reason and by the same
 * posture (computenet-x9e.8).
 *
 * CSV, not JSON, and for a reason worth stating: `:bench` depends on `:kernel` and
 * `:testkit` and nothing else (`[BEN1-03]`), so there is no JSON parser on this
 * module's classpath and adding one to read a results file would be a dependency
 * bought for a formatting choice. JMH's CSV format carries everything a
 * [BenchResult] needs — score, error at 99.9% confidence, unit — plus one
 * `Param: <name>` column per `@Param`, which is where a row's label comes from.
 *
 * WHICH of those columns label a row is [RowLabel]'s, not this object's. It was once
 * `subject`/`direction` hard-coded — `OperatorThroughputBenchmark`'s two parameters — which
 * made [renderRun] unusable for every other sweep in the module and cost one entry a
 * hand-written driver (computenet-x9e.10). A results file now says which columns label it
 * by naming its own benchmark: [RowLabel.REGISTERED].
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
 * The label columns are found the same way, and for the same reason: not by a property,
 * but by the benchmark the results file itself names ([RowLabel.REGISTERED]). So that one
 * command renders any registered sweep — `CellFootprintBenchmark`'s `family`/`scale` as
 * readily as `OperatorThroughputBenchmark`'s `subject`/`direction` — and refuses, naming
 * the class, for one nobody has chosen the columns of.
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
    // The JMH configuration `OperatorThroughputBenchmark` DECLARES, held HERE rather
    // than only on that class.
    //
    // The benchmark lives in the `jmh` source set, which `test` cannot see (both compile
    // against `main`, neither against the other), so a constant its annotations reference
    // has to live in `main` for anything else to name it.
    //
    // READ THESE AS THE DECLARATION, NEVER AS THE RUN (computenet-x9e.8). They were once
    // also what [renderRun] recorded into a `RunEnvironment`, on the reasoning that one
    // definition cannot disagree with itself. That reasoning is wrong, because the two
    // things were never one: JMH's `-f`/`-wi`/`-i` flags OVERRIDE the annotations, so a
    // sweep run at `-f 1` — the smoke invocation `OperatorThroughputBenchmark`'s own KDoc
    // documents — genuinely measures a configuration these constants do not describe,
    // while the entry went on claiming `forks=2 warmup=5 iters=10`. The knobs a
    // `RunEnvironment` records now come from [RunKnobs.fromJmhLog], off the run's own
    // banner, and `renderRun` REFUSES when that banner is absent. Nothing in the render
    // path reads the four constants below; `ThroughputReportTest` asserts their values
    // are absent from an entry whose run used different ones.
    // ---------------------------------------------------------------------------------

    /**
     * `Mode.Throughput`'s name — the mode `@BenchmarkMode(Mode.Throughput)` declares.
     *
     * Not referenced by that annotation (an annotation argument must be the `Mode`
     * constant itself) and deliberately not referenced by the renderer either: it exists
     * so a reader and a test can name the declared mode, and a run's own
     * `# Benchmark mode:` line is what an entry states.
     */
    const val JMH_MODE: String = "Throughput"

    /** The `@Fork` count the benchmark declares. `-f` overrides it. */
    const val FORKS: Int = 2

    /** The `@Warmup` iteration count the benchmark declares. `-wi` overrides it. */
    const val WARMUP_ITERATIONS: Int = 5

    /** The `@Measurement` iteration count the benchmark declares. `-i` overrides it. */
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
     * The per-row label: the values of the `@Param` columns [label] names, which is what
     * distinguishes an insert row from a retract row of the same operator, or a `1e3` row
     * from a `1e5` row of the same method, when both carry the same unit.
     *
     * A row missing any of those parameters cannot be labelled, and is refused here rather
     * than labelled `"unknown"` — [Findings.entry] would accept a non-blank invented
     * label perfectly happily, so the honesty has to be upstream of it.
     *
     * @param label which columns name the row. Defaults to the columns
     *   [RowLabel.forBenchmark] has registered for the row's own benchmark class, so a
     *   caller rendering a whole results file does not have to know — and cannot
     *   misdeclare — which sweep it holds.
     */
    fun labelOf(row: JmhRow, label: RowLabel = RowLabel.forBenchmark(row.benchmark)): String {
        val values = label.params.map { name ->
            val value = row.params[name]
            if (value.isNullOrBlank()) {
                throw ThroughputReportException(
                    "benchmark '${row.benchmark}' carries no usable '$name' parameter " +
                        "(found ${row.params}, label columns ${label.params}); a row that " +
                        "cannot say what it measured cannot be labelled [BEN1-30]"
                )
            }
            if (name in label.lowercased) value.lowercase() else value
        }
        val parts = if (label.includeMethod) listOf(row.method) + values else values
        return parts.joinToString(separator = " ")
    }

    /**
     * Turns parsed rows into labelled [BenchResult]s under [env].
     *
     * [BenchResult.drive] is [driveOf]'s answer, and [env] is supplied by the caller —
     * the run's environment is a fact about the machine and the JMH configuration that
     * a results file does record in part, but the harness commit SHA it does not record
     * at all, so [RunEnvironment] stays the caller's to state (and [RunEnvironment]
     * itself refuses to exist with a field missing).
     *
     * @param label which `@Param` columns label every row, or `null` — the default — to
     *   resolve each row's columns from [RowLabel.forBenchmark].
     */
    fun toResults(
        rows: List<JmhRow>,
        env: RunEnvironment,
        label: RowLabel? = null,
    ): List<LabelledResult> =
        rows.map { row ->
            LabelledResult(
                label = labelOf(row, label ?: RowLabel.forBenchmark(row.benchmark)),
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
     * @param label which `@Param` columns label every row, or `null` — the default — to
     *   resolve each row's columns from [RowLabel.forBenchmark].
     */
    fun render(
        csv: String,
        env: RunEnvironment,
        date: String,
        subject: String,
        trigger: TriggerClaim = TriggerClaim.None,
        label: RowLabel? = null,
    ): Report = renderResults(toResults(parseCsv(csv), env, label), date, subject, trigger)

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
     * Reads [results] for the numbers and [runLogFor]`(results)` for the JVM, the JMH
     * knobs, and the host that produced them, and REFUSES — [MeasuringJvmUnknownException],
     * [RunKnobsUnknownException], [HostFactsUnknownException] respectively — when that log
     * is absent or carries no matching banner. It does not fall back to this process's
     * `java.vendor`, `java.version`, heap, declared annotations, or `Runtime
     * .getRuntime()`/`sysctl` reads; that fallback is what shipped a REAL-drive entry
     * reading `Eclipse Adoptium/21.0.11 · heap -Xmx2g` for a sweep measured on Homebrew
     * JDK 26.0.1 with no VM options, and the code that could produce it no longer exists
     * (see [RunEnvironment.forRun]).
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
     * @throws RunKnobsUnknownException if the mode, fork count or iteration counts the
     *   run actually used cannot be established from its log (computenet-x9e.8). The
     *   benchmark class's `@Fork`/`@Warmup`/`@Measurement` values are NOT a fallback —
     *   `-f`/`-wi`/`-i` override them, so they state the declaration, not the run.
     * @throws HostFactsUnknownException if the CPU model, core count or OS of the
     *   measuring host cannot be established from its log (computenet-yhbd). No JMH
     *   artifact records host facts on its own; this process's own `Runtime
     *   .getRuntime()`/`sysctl` reads are NOT a fallback — they answer for the
     *   renderer, which may not be the machine that measured.
     * @param label which `@Param` columns label every row, or `null` — the default — to
     *   resolve each row's columns from [RowLabel.forBenchmark]. This is what makes the
     *   entry point usable for a sweep whose parameters are not `subject`/`direction`
     *   (computenet-x9e.10): pass a [RowLabel] for a results file whose benchmark is not
     *   registered, and leave it `null` for one that is.
     * @throws ThroughputReportException if [results] is not a readable file, its contents
     *   cannot honestly become rows, or one drive's rows do not carry distinct labels.
     */
    fun renderRun(
        results: File,
        harnessCommitSha: String,
        date: String,
        subject: String,
        trigger: TriggerClaim = TriggerClaim.None,
        label: RowLabel? = null,
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
        val logText = log.readText()
        val env = RunEnvironment.forRun(
            measuringJvm = MeasuringJvm.fromJmhLog(logText, log.absolutePath),
            // NOT [JMH_MODE]/[FORKS]/[WARMUP_ITERATIONS]/[MEASUREMENT_ITERATIONS]: those
            // are what the benchmark class DECLARES, and `-f`/`-wi`/`-i` override them
            // (computenet-x9e.8). The run's own banner states what it resolved.
            knobs = RunKnobs.fromJmhLog(logText, log.absolutePath),
            // NOT [HostFacts.captureCurrent]: that answers for THIS process, the
            // renderer, which may be running long after the forks exited on a different
            // machine entirely. The measuring fork prints its own host facts onto this
            // same log (computenet-yhbd) and this reads them back.
            hostFacts = HostFacts.fromJmhLog(logText, log.absolutePath),
            harnessCommitSha = harnessCommitSha,
        )
        return render(results.readText(), env, date, subject, trigger, label)
    }

    /**
     * [render]'s second half, exposed so a caller can render results it built itself.
     *
     * Refuses a drive whose rows do not carry DISTINCT labels. [FindingsTable] cannot make
     * that refusal — a duplicated label is non-blank and there is still one per result, so
     * it is well-formed by every rule F3 has — and yet the table it renders shows two
     * measurements the reader cannot tell apart, and the omission list beside it names a
     * row ambiguously. The check belongs here because this is where the label columns are
     * chosen: it is what turns "I picked too few columns" from a plausible-looking table
     * into a refusal that names the collision (`RowLabel.includeMethod` is usually the
     * answer). It runs BEFORE the dispersion partition on purpose, so whether it fires
     * does not depend on which rows happened to clear [NOISE_FLOOR].
     */
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
            val duplicated = rows.groupingBy { it.label }.eachCount().filterValues { it > 1 }
            if (duplicated.isNotEmpty()) {
                throw ThroughputReportException(
                    "drive=$drive carries rows that share a label: " +
                        duplicated.entries.sortedBy { it.key }
                            .joinToString { "'${it.key}' x ${it.value}" } +
                        ". Two measurements under one label is a table whose reader cannot " +
                        "tell them apart, and an omission list that names an ambiguous " +
                        "row. Label by every @Param the sweep varied, and set " +
                        "RowLabel.includeMethod when one benchmark class's @Benchmark " +
                        "methods share a parameter set"
                )
            }
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
