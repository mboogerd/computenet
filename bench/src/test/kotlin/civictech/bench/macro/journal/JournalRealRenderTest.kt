package civictech.bench.macro.journal

import civictech.bench.BenchResult
import civictech.bench.ComparisonClaim
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsRefusalException
import civictech.bench.FindingsTable
import civictech.bench.HostFacts
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.TriggerClaim
import civictech.bench.cost.CalibrationConstant
import civictech.bench.cost.CostSchema
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
import kotlin.math.abs

/** Explicit render step for the REAL G-25 journal entry. */
@Tag("bench")
class JournalRealRenderTest {

    @Test
    fun `renders the REAL journal entry with the G-25 verdict and the fsync-share comparisons`() {
        val resultsPath = requireNotNull(System.getProperty("civictech.bench.jmhResults")) {
            "set -Dcivictech.bench.jmhResults=<journal JMH CSV>"
        }
        val harnessSha = requireNotNull(System.getProperty("civictech.bench.harnessSha")) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse --short HEAD>"
        }
        val repoRoot = requireNotNull(System.getProperty("computenet.repo.root")) {
            "system property computenet.repo.root must name the export checkout"
        }
        val statement = requireNotNull(System.getenv(JournalRealReport.STATEMENT_ENV)) {
            "set ${JournalRealReport.STATEMENT_ENV} to the G-25 verdict statement"
        }
        val rendered = JournalRealReport.render(
            results = File(resultsPath),
            export = File(repoRoot, CostSchema.STABLE_PATH),
            harnessSha = harnessSha,
            date = System.getProperty("civictech.bench.date")
                ?.takeIf { it.isNotBlank() }
                ?: LocalDate.now().toString(),
            statement = statement,
        )
        println(rendered)
    }
}

/** Parsing, validation, disclosure, and export merge for the REAL journal entry. */
internal object JournalRealReport {
    const val STATEMENT_ENV: String = "CIVICTECH_BENCH_G25_STATEMENT"

    private const val BENCHMARK_CLASS = "JournalCostBenchmark"
    private const val SUBJECT =
        "durable DIAMOND point, journal replay and checkpoint wall time per cardinality " +
            "on ONE host, REAL drive, SingleShotTime"
    private const val ABSENT_REASON = "fork produced no row; see the run log"
    private val METHODS = listOf(
        JournalCalibration.METHOD_FILE_JOURNAL_POINT,
        JournalCalibration.METHOD_MEMORY_JOURNAL_POINT,
        JournalCalibration.METHOD_REPLAY,
        JournalCalibration.METHOD_CHECKPOINT,
    )

    fun render(
        results: File,
        export: File,
        harnessSha: String,
        date: String,
        statement: String,
    ): String {
        validateBasicStatement(statement)
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
        val rows = ThroughputReport.parseCsv(results.readText())
        val cells = rows.map { cell(it, environment) }
        require(cells.isNotEmpty()) { "results carry no $BENCHMARK_CLASS rows" }
        val labels = cells.map { it.labelled.label }
        require(labels.size == labels.toSet().size) {
            "journal results carry duplicate row labels: $labels"
        }
        val table = FindingsTable(
            results = cells.map { it.labelled.result },
            labels = labels,
        )
        val shares = JournalCalibration.fsyncShares(results)
        val accepted = mutableListOf<ComparisonClaim>()
        val unresolved = mutableListOf<Long>()
        JournalPlan.REAL_CARDINALITIES.forEach { cardinality ->
            val file = cells.singleOrNull { it.method == JournalCalibration.METHOD_FILE_JOURNAL_POINT && it.cardinality == cardinality }
            val memory = cells.singleOrNull { it.method == JournalCalibration.METHOD_MEMORY_JOURNAL_POINT && it.cardinality == cardinality }
            if (file == null || memory == null || cardinality !in shares) {
                unresolved += cardinality
                return@forEach
            }
            val claim = ComparisonClaim(
                leftLabel = file.labelled.label,
                rightLabel = memory.labelled.label,
                statement = "fsync share ${percent(shares.getValue(cardinality))}% at C=$cardinality",
            )
            try {
                // Test each claim in isolation. A single noisy cardinality is disclosed,
                // not allowed to suppress the comparisons the other rows support.
                Findings.entry(date, SUBJECT, table, comparisons = listOf(claim))
                accepted += claim
            } catch (_: FindingsRefusalException) {
                unresolved += cardinality
            }
        }

        val resolvableCardinalities = accepted.mapNotNull { claim ->
            cells.firstOrNull { it.labelled.label == claim.leftLabel }?.cardinality
        }.toSet()
        validateStatement(
            statement = statement,
            shares = shares,
            resolvableCardinalities = resolvableCardinalities,
        )
        val entry = Findings.entry(
            date = date,
            subject = SUBJECT,
            results = table,
            trigger = TriggerClaim.Cited("G-25", statement),
            comparisons = accepted,
        )

        val complete = cells.map { it.method to it.cardinality }.toSet() ==
            JournalPlan.REAL_CARDINALITIES.flatMap { cardinality ->
                METHODS.map { method -> method to cardinality }
            }.toSet()
        val constants = if (complete) {
            JournalCalibration.constantsFromJmh(results, harnessSha)
        } else {
            emptyList()
        }
        val facts = if (complete) JournalCalibration.factsFromLog(logText) else emptyMap()
        val rendered = buildString {
            appendLine(entry)
            appendLine()
            unresolved.forEach { cardinality ->
                appendLine("fsync share not resolvable at C=$cardinality")
            }
            if (unresolved.isNotEmpty()) appendLine()
            appendLine("Not measured")
            METHODS.forEach { method ->
                JournalPlan.REAL_CARDINALITIES.forEach { cardinality ->
                    if (cells.none { it.method == method && it.cardinality == cardinality }) {
                        appendLine(
                            "- method=$method cardinality=$cardinality — reason: $ABSENT_REASON",
                        )
                    }
                }
            }
            JournalPlan.omissions().filter { it.what.startsWith("REAL") }.forEach { omission ->
                appendLine("- ${omission.what} — ${omission.reason}")
            }
            appendLine()
            appendLine("Constants")
            if (constants.isEmpty()) {
                appendLine("- not measured: complete method×cardinality rows are required")
            } else {
                constants.forEach { constant ->
                    appendLine(
                        "- ${constant.name} = ${constant.value} ± " +
                            "${constant.dispersion} ${constant.unit}",
                    )
                }
                facts.toSortedMap().forEach { (cardinality, entries) ->
                    entries.forEach { fact ->
                        appendLine(
                            "- facts: cardinality=$cardinality records=${fact.records} " +
                                "fileBytes=${fact.fileBytes} checkpointBytes=${fact.checkpointBytes}",
                        )
                    }
                }
            }
            appendLine()
            appendLine("D13 arm (iii)")
            appendLine(armThree(cells))
            appendLine()
            append(
                "SIM counts, file sizes, retained bytes and the recovery check: see the " +
                    "\"journal growth\" SIM entry above",
            )
        }

        if (constants.isNotEmpty()) {
            JournalCalibration.merge(export.toPath(), constants)
            CostSchema.parse(export.readText())
        }
        return rendered
    }

    internal fun validateStatement(
        statement: String,
        shares: Map<Long, Double>,
        resolvableCardinalities: Set<Long>,
    ) {
        validateBasicStatement(statement)
        val majority = resolvableCardinalities.any { cardinality ->
            shares[cardinality]?.let { it > 0.5 } == true
        }
        if (majority) {
            require("dominated by journal sync" in statement && "KBLK" in statement) {
                "$STATEMENT_ENV must contain 'dominated by journal sync' and 'KBLK' " +
                    "when a resolved fsync share exceeds 0.5"
            }
        } else {
            require("not dominated by journal sync" in statement) {
                "$STATEMENT_ENV must contain 'not dominated by journal sync' when no " +
                    "resolved fsync share exceeds 0.5"
            }
        }
    }

    private fun validateBasicStatement(statement: String) {
        require(statement.isNotBlank()) { "$STATEMENT_ENV must not be blank" }
        require("G-25" in statement) { "$STATEMENT_ENV must name G-25" }
    }

    private fun cell(row: JmhRow, environment: RunEnvironment): Cell {
        require(row.benchmarkClass == BENCHMARK_CLASS) {
            "${row.benchmark} is not a $BENCHMARK_CLASS row"
        }
        require(row.method in METHODS) { "unknown journal benchmark method '${row.method}'" }
        require(ThroughputReport.driveOf(row) == Drive.REAL) {
            "${row.benchmark} is not a REAL benchmark row"
        }
        val cardinality = row.params["cardinality"]?.toLongOrNull()
        require(cardinality in JournalPlan.REAL_CARDINALITIES) {
            "${row.benchmark} carries undeclared cardinality '${row.params["cardinality"]}'"
        }
        return Cell(
            method = row.method,
            cardinality = cardinality!!,
            labelled = LabelledResult(
                label = ThroughputReport.labelOf(row, RowLabel.forBenchmark(row.benchmark)),
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

    private fun armThree(cells: List<Cell>): String {
        val cardinality = 100_000L
        val replay = cells.singleOrNull { it.method == JournalCalibration.METHOD_REPLAY && it.cardinality == cardinality }
        val file = cells.singleOrNull { it.method == JournalCalibration.METHOD_FILE_JOURNAL_POINT && it.cardinality == cardinality }
        if (replay == null || file == null) {
            return "realReplay(1e5) vs realFileJournalPoint(1e5): |Δ| unresolved — missing row"
        }
        val delta = abs(replay.labelled.result.value - file.labelled.result.value)
        val threshold = 3.0 * (replay.labelled.result.dispersion + file.labelled.result.dispersion)
        val resolution = if (delta > threshold) "resolved" else "unresolved"
        return "realReplay(1e5)=${replay.labelled.result.value} ± " +
            "${replay.labelled.result.dispersion} ${replay.labelled.result.unit} vs " +
            "realFileJournalPoint(1e5)=${file.labelled.result.value} ± " +
            "${file.labelled.result.dispersion} ${file.labelled.result.unit}: |Δ|=$delta " +
            "against 3 × (err₁ + err₂)=$threshold ${replay.labelled.result.unit} — $resolution"
    }

    private fun percent(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value * 100.0)

    private data class Cell(
        val method: String,
        val cardinality: Long,
        val labelled: LabelledResult,
    )
}

/** Fast pins for statement validation and the disclosure of absent cells. */
class JournalRealReportTest {

    @Test
    fun `refuses a blank statement before reading artifacts`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            JournalRealReport.render(
                File("missing.csv"),
                File("missing.json"),
                "abc123",
                "2026-10-16",
                " ",
            )
        }
        assertTrue(failure.message!!.contains(JournalRealReport.STATEMENT_ENV))
    }

    @Test
    fun `refuses a statement without G-25 before reading artifacts`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            JournalRealReport.render(
                File("missing.csv"),
                File("missing.json"),
                "abc123",
                "2026-10-16",
                "INCONCLUSIVE because not dominated by journal sync",
            )
        }
        assertTrue(failure.message!!.contains("must name G-25"))
    }

    @Test
    fun `refuses a majority statement that omits KBLK`() {
        val results = File.createTempFile("journal-render-", ".csv")
        val export = File.createTempFile("journal-render-", ".json")
        try {
            results.writeText(
                """"Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit","Param: cardinality"
                    "civictech.bench.macro.JournalCostBenchmark.realFileJournalPoint","ss",1,15,10.0,0.1,"ms/op",1000
                    "civictech.bench.macro.JournalCostBenchmark.realMemoryJournalPoint","ss",1,15,1.0,0.1,"ms/op",1000""".trimIndent()
            )
            ThroughputReport.runLogFor(results).writeText(MINIMAL_LOG)
            export.writeText(repositoryExport().readText())
            val failure = assertThrows(IllegalArgumentException::class.java) {
                JournalRealReport.render(
                    results,
                    export,
                    "abc123",
                    "2026-10-16",
                    "G-25 FIRES because dominated by journal sync",
                )
            }
            assertTrue(failure.message!!.contains("KBLK"))
        } finally {
            results.delete()
            ThroughputReport.runLogFor(results).delete()
            export.delete()
        }
    }

    @Test
    fun `labels two synthetic rows and lists absent cells`(@TempDir dir: File) {
        val results = File(dir, "journal.csv").apply {
            writeText(
                """"Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit","Param: cardinality"
                    "civictech.bench.macro.JournalCostBenchmark.realFileJournalPoint","ss",1,15,10.0,0.1,"ms/op",1000
                    "civictech.bench.macro.JournalCostBenchmark.realMemoryJournalPoint","ss",1,15,9.5,0.1,"ms/op",1000""".trimIndent()
            )
        }
        ThroughputReport.runLogFor(results).writeText(MINIMAL_LOG)
        val export = File(dir, "cost-model.json").apply { writeText(repositoryExport().readText()) }
        val rendered = JournalRealReport.render(
            results = results,
            export = export,
            harnessSha = "abc123",
            date = "2026-10-16",
            statement = "G-25 INCONCLUSIVE because not dominated by journal sync at this partial envelope",
        )

        assertTrue(rendered.contains("| realFileJournalPoint 1000 | 10.0 ± 0.1 ms/op | |"))
        assertTrue(rendered.contains("| realMemoryJournalPoint 1000 | 9.5 ± 0.1 ms/op | |"))
        assertEquals(
            10,
            rendered.lineSequence().count {
                it.contains("reason: fork produced no row; see the run log")
            },
        )
        assertTrue(rendered.contains("realReplay(1e5) vs realFileJournalPoint(1e5): |Δ| unresolved"))
        assertTrue(rendered.contains("fsync share not resolvable at C=10000"))
    }

    companion object {
        private fun repositoryExport(): File = File(
            requireNotNull(System.getProperty("computenet.repo.root")),
            CostSchema.STABLE_PATH,
        )

        private val MINIMAL_LOG = """
            # JMH version: 1.37
            # VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11
            # VM options: <none>
            # Warmup: 2 iterations, 1 s each
            # Measurement: 5 iterations, 1 s each
            # Benchmark mode: Single shot invocation time
            # Benchmark: civictech.bench.macro.JournalCostBenchmark.realFileJournalPoint
            # Parameters: (cardinality = 1000)
            # Fork: 1 of 3
            # Host CPU model: Synthetic CPU
            # Host core count: 8
            # Host OS: Synthetic OS 1.0
        """.trimIndent()
    }
}
