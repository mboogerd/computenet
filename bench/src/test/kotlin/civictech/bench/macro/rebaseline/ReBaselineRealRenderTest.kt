package civictech.bench.macro.rebaseline

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.HostFacts
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.TriggerClaim
import civictech.bench.cost.CostAnswer
import civictech.bench.cost.CostQuery
import civictech.bench.cost.CostSchema
import civictech.bench.cost.Envelope
import civictech.bench.cost.Measured
import civictech.bench.micro.JmhRow
import civictech.bench.micro.LabelledResult
import civictech.bench.micro.RowLabel
import civictech.bench.micro.ThroughputReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/** Explicit render step for the REAL, single-host G-43 re-baseline sweep. */
@Tag("bench")
class ReBaselineRealRenderTest {

    @Test
    fun `renders the REAL re-baseline entry with the G-43 verdict and the priced prediction`() {
        val resultsPath = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(resultsPath?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<re-baseline JMH CSV>"
        }
        val harnessSha = System.getProperty("civictech.bench.harnessSha")
        requireNotNull(harnessSha?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse --short HEAD>; the results " +
                "file does not record which harness commit produced it"
        }
        val repoRoot = System.getProperty("computenet.repo.root")
        requireNotNull(repoRoot?.takeIf { it.isNotBlank() }) {
            "system property computenet.repo.root must name the export checkout"
        }
        val statement = System.getenv(ReBaselineRealReport.STATEMENT_ENV)
        requireNotNull(statement?.takeIf { it.isNotBlank() }) {
            "set ${ReBaselineRealReport.STATEMENT_ENV} to the G-43 verdict statement"
        }

        val rendered = ReBaselineRealReport.render(
            results = File(resultsPath!!),
            export = File(repoRoot!!, CostSchema.STABLE_PATH),
            harnessSha = harnessSha!!,
            date = System.getProperty("civictech.bench.date")
                ?.takeIf { it.isNotBlank() }
                ?: LocalDate.now().toString(),
            statement = statement!!,
        )
        println(rendered)
    }
}

/** Parsing, validation, disclosure, and cost-model comparison shared with the fast test. */
internal object ReBaselineRealReport {
    const val STATEMENT_ENV: String = "CIVICTECH_BENCH_G43_STATEMENT"
    const val REQUIRED_CONTEXT: String =
        "This measures the RESTART re-baseline event itself; the 2026-08-19 and " +
            "2026-08-20 fan-out entries in this file measured ordinary delta propagation " +
            "and late-join catch-up"

    private const val BENCHMARK_CLASS = "ReBaselineFanOutBenchmark"
    private const val SUBJECT =
        "RESTART re-baseline wall time per (cardinality, fan-out) on ONE host, " +
            "REAL drive, SingleShotTime"
    private const val ABSENT_REASON = "fork produced no row; see the run log"
    private const val MULTI_HOST_REASON =
        "REAL wall time at hostCount > 1 is not measured: the REAL harness is " +
            "single-host (SweepRunner.kt, [BEN2-13]); H-growth is the SIM entry's frame " +
            "and byte counts"
    private val CARDINALITIES = listOf(1_000L, 10_000L, 100_000L)
    private val FAN_OUTS = listOf(1, 4, 16, 64, 256)

    fun render(
        results: File,
        export: File,
        harnessSha: String,
        date: String,
        statement: String,
    ): String {
        validateStatement(statement)
        require(results.isFile) { "no JMH results file at ${results.absolutePath}" }
        require(export.isFile) { "no cost export at ${export.absolutePath}" }

        val log = ThroughputReport.runLogFor(results)
        require(log.isFile) { "no JMH run log at ${log.absolutePath}" }
        val logText = log.readText()
        val environment = RunEnvironment.forRun(
            measuringJvm = MeasuringJvm.fromJmhLog(logText, log.absolutePath),
            knobs = RunKnobs.fromJmhLog(logText, log.absolutePath),
            hostFacts = HostFacts.fromJmhLog(logText, log.absolutePath),
            harnessCommitSha = harnessSha,
        )

        val cells = ThroughputReport.parseCsv(results.readText())
            .filter { it.benchmarkClass == BENCHMARK_CLASS }
            .map { row -> cell(row, environment) }
            .sortedWith(
                compareBy<Cell> { CARDINALITIES.indexOf(it.cardinality) }
                    .thenBy { FAN_OUTS.indexOf(it.fanOut) },
            )
        require(cells.isNotEmpty()) { "results carry no $BENCHMARK_CLASS rows" }

        val entry = ThroughputReport.renderResults(
            results = cells.map { it.labelled },
            date = date,
            subject = SUBJECT,
            trigger = TriggerClaim.Cited("G-43", statement),
        ).text()
        val present = cells.mapTo(mutableSetOf()) { it.cardinality to it.fanOut }
        val query = CostQuery(CostSchema.parse(export.readText()))

        return buildString {
            appendLine(entry)
            appendLine()
            appendLine("Not measured")
            CARDINALITIES.forEach { cardinality ->
                FAN_OUTS.forEach { fanOut ->
                    if ((cardinality to fanOut) !in present) {
                        appendLine(
                            "- cardinality=$cardinality fanOut=$fanOut hostCount=1 — " +
                                "reason: $ABSENT_REASON",
                        )
                    }
                }
            }
            listOf(3, 8).forEach { hostCount ->
                CARDINALITIES.forEach { cardinality ->
                    FAN_OUTS.forEach { fanOut ->
                        appendLine(
                            "- cardinality=$cardinality fanOut=$fanOut hostCount=$hostCount — " +
                                MULTI_HOST_REASON,
                        )
                    }
                }
            }
            appendLine()
            appendLine("Predicted vs measured")
            cells.forEach { cell ->
                val answer = query.reBaseline(
                    ReBaselineProbe.coordinate(
                        ReBaselineProbe.LOCAL_TAPS,
                        cell.cardinality,
                        cell.fanOut,
                        1,
                    ),
                )
                appendLine(
                    "- ${cell.labelled.label}: measured ${cell.row.score} ± " +
                        "${cell.row.scoreError} ms; predicted ${prediction(answer)}",
                )
            }
            append(
                "SIM counts, laws and the H dimension: see the \"RESTART re-baseline " +
                    "structural counts\" entry above",
            )
        }
    }

    internal fun validateStatement(statement: String) {
        require(statement.isNotBlank()) {
            "$STATEMENT_ENV must not be blank"
        }
        require("G-43" in statement) {
            "$STATEMENT_ENV must name G-43"
        }
        require(REQUIRED_CONTEXT in statement) {
            "$STATEMENT_ENV must contain verbatim: $REQUIRED_CONTEXT"
        }
    }

    private fun cell(row: JmhRow, environment: RunEnvironment): Cell {
        require(ThroughputReport.driveOf(row) == Drive.REAL) {
            "${row.benchmark} is not a REAL benchmark row"
        }
        require(row.unit == "ms/op") {
            "${row.benchmark} reports '${row.unit}', expected ms/op from @OutputTimeUnit"
        }
        val cardinality = gridValue(row, "cardinality").toLongOrNull()
        require(cardinality in CARDINALITIES) {
            "${row.benchmark} carries undeclared cardinality '${row.params["cardinality"]}'"
        }
        val fanOut = gridValue(row, "fanOut").toIntOrNull()
        require(fanOut in FAN_OUTS) {
            "${row.benchmark} carries undeclared fanOut '${row.params["fanOut"]}'"
        }
        val label = ThroughputReport.labelOf(row, RowLabel.forBenchmark(row.benchmark))
        return Cell(
            cardinality = cardinality!!,
            fanOut = fanOut!!,
            row = row,
            labelled = LabelledResult(
                label = label,
                result = BenchResult(
                    value = row.score,
                    unit = row.unit,
                    dispersion = row.scoreError,
                    drive = Drive.REAL,
                    env = environment,
                ),
                benchmarkClass = row.benchmarkClass,
                benchmarkMethod = row.method,
            ),
        )
    }

    private fun gridValue(row: JmhRow, name: String): String =
        requireNotNull(row.params[name]?.takeIf { it.isNotBlank() }) {
            "${row.benchmark} carries no '$name' parameter"
        }

    private fun prediction(answer: CostAnswer): String = when (answer) {
        is CostAnswer.Priced ->
            "Priced ${answer.value} ± ${answer.dispersion} ns " +
                "(dominant ${answer.from.dominant}) = ${answer.value / 1_000_000.0} ± " +
                "${answer.dispersion / 1_000_000.0} ms"
        is CostAnswer.NotMeasured -> "NotMeasured(${answer.reason})"
        is CostAnswer.OutOfEnvelope ->
            "OutOfEnvelope(dimension=${answer.dimension}, requested=${answer.requested}, " +
                "measured=${measured(answer.measured)})"
    }

    private fun measured(measured: Measured): String = when (measured) {
        is Envelope.Range -> "${measured.min}..${measured.max}"
        is Measured.Values -> measured.values.sorted().joinToString(prefix = "{", postfix = "}")
    }

    private data class Cell(
        val cardinality: Long,
        val fanOut: Int,
        val row: JmhRow,
        val labelled: LabelledResult,
    )
}

/** Fast pins for the report core; the real JMH render remains bench-tagged above. */
class ReBaselineRealReportTest {

    @Test
    fun `refuses a blank statement before reading artifacts`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ReBaselineRealReport.render(
                File("missing.csv"),
                File("missing.json"),
                "abc123",
                "2026-10-15",
                " ",
            )
        }
        assertTrue(failure.message!!.contains(ReBaselineRealReport.STATEMENT_ENV))
    }

    @Test
    fun `refuses a statement without the prior fan-out distinction before reading artifacts`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ReBaselineRealReport.render(
                File("missing.csv"),
                File("missing.json"),
                "abc123",
                "2026-10-15",
                "G-43 is INCONCLUSIVE because the grid is incomplete",
            )
        }
        assertTrue(failure.message!!.contains(ReBaselineRealReport.REQUIRED_CONTEXT))
    }

    @Test
    fun `refuses a statement that does not name G-43 before reading artifacts`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ReBaselineRealReport.render(
                File("missing.csv"),
                File("missing.json"),
                "abc123",
                "2026-10-15",
                "INCONCLUSIVE because the grid is incomplete. " +
                    ReBaselineRealReport.REQUIRED_CONTEXT,
            )
        }
        assertTrue(failure.message!!.contains("must name G-43"))
    }

    @Test
    fun `labels two synthetic rows and lists the thirteen absent cells`(@TempDir dir: File) {
        val results = File(dir, "rebaseline.csv").apply { writeText(SYNTHETIC_CSV) }
        ThroughputReport.runLogFor(results).writeText(MINIMAL_LOG)
        val root = File(requireNotNull(System.getProperty("computenet.repo.root")))
        val statement =
            "G-43 is INCONCLUSIVE because this synthetic fixture measures only two cells. " +
                ReBaselineRealReport.REQUIRED_CONTEXT

        val rendered = ReBaselineRealReport.render(
            results = results,
            export = File(root, CostSchema.STABLE_PATH),
            harnessSha = "abc123",
            date = "2026-10-15",
            statement = statement,
        )

        assertTrue(rendered.contains("| cardinality=1000 fanOut=1 | 1.25 ± 0.05 ms/op | |"))
        assertTrue(rendered.contains("| cardinality=10000 fanOut=4 | 2.5 ± 0.1 ms/op | |"))
        assertEquals(13, rendered.lineSequence().count { it.contains("reason: fork produced no row") })
        assertEquals(
            30,
            rendered.lineSequence().count {
                it.contains("REAL wall time at hostCount > 1 is not measured")
            },
        )
        assertTrue(rendered.contains("Predicted vs measured"))
        assertTrue(
            rendered.contains(
                "SIM counts, laws and the H dimension: see the \"RESTART re-baseline " +
                    "structural counts\" entry above",
            ),
        )
    }

    companion object {
        private val SYNTHETIC_CSV = """
            "Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit","Param: cardinality","Param: fanOut"
            "civictech.bench.macro.ReBaselineFanOutBenchmark.realRestart","ss",1,15,1.25,0.05,"ms/op",1000,1
            "civictech.bench.macro.ReBaselineFanOutBenchmark.realRestart","ss",1,15,2.5,0.1,"ms/op",10000,4
        """.trimIndent()

        private val MINIMAL_LOG = """
            # JMH version: 1.37
            # VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11
            # VM options: <none>
            # Warmup: 2 iterations, 1 s each
            # Measurement: 5 iterations, 1 s each
            # Benchmark mode: Single shot invocation time
            # Benchmark: civictech.bench.macro.ReBaselineFanOutBenchmark.realRestart
            # Parameters: (cardinality = 1000, fanOut = 1)
            # Fork: 1 of 3
            # Host CPU model: Synthetic CPU
            # Host core count: 8
            # Host OS: Synthetic OS 1.0
        """.trimIndent()
    }
}
