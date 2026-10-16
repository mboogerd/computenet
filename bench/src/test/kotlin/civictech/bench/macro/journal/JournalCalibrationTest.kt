package civictech.bench.macro.journal

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.cost.CalibrationConstant
import civictech.bench.cost.CostSchema
import civictech.bench.cost.CostSubject
import civictech.bench.cost.CostWriter
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.StructuralCounts
import civictech.bench.micro.ThroughputReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** Fast CSV/log pins for the four REAL journal calibration constants. */
class JournalCalibrationTest {

    @TempDir
    lateinit var temporaryRoot: File

    @Test
    fun `facts and constants use the calibration cardinality and fork dispersion`() {
        val results = File(temporaryRoot, "journal.csv").apply { writeText(SYNTHETIC_CSV) }
        ThroughputReport.runLogFor(results).writeText(SYNTHETIC_LOG)

        val facts = JournalCalibration.factsFromLog(SYNTHETIC_LOG)
        assertEquals(3, facts.getValue(10_000L).size)
        assertThrows(IllegalArgumentException::class.java) {
            JournalCalibration.factsFromLog("${JournalCalibration.LOG_PREFIX} malformed")
        }

        val constants = JournalCalibration.constantsFromJmh(results, "abc123")
        assertEquals(JournalCalibration.NAMES.toSet(), constants.map { it.name }.toSet())
        val byName = constants.associateBy { it.name }
        assertEquals(10.0 + 1.0 / 3.0, byName.getValue(JournalCalibration.JOURNAL_BYTES_PER_RECORD).value, 1e-12)
        assertEquals(0.5, byName.getValue(JournalCalibration.JOURNAL_BYTES_PER_RECORD).dispersion, 1e-12)
        assertEquals(4_000_000.0 / 10_007.0, byName.getValue(JournalCalibration.REPLAY_NANOS_PER_RECORD).value, 1e-12)
        assertEquals(10_000.0 / 10_007.0, byName.getValue(JournalCalibration.REPLAY_NANOS_PER_RECORD).dispersion, 1e-12)
        assertEquals(500.0, byName.getValue(JournalCalibration.CHECKPOINT_NANOS_PER_ELEMENT).value, 1e-12)
        assertEquals(2.0 + 1.0 / 15.0, byName.getValue(JournalCalibration.CHECKPOINT_BYTES_PER_ELEMENT).value, 1e-12)
        assertEquals(0.1, byName.getValue(JournalCalibration.CHECKPOINT_BYTES_PER_ELEMENT).dispersion, 1e-12)
        assertTrue(byName.values.all { it.env.harnessCommitSha == "abc123" })

        val shares = JournalCalibration.fsyncShares(results)
        assertEquals(0.5, shares.getValue(1_000L), 1e-12)
        assertEquals(0.5, shares.getValue(10_000L), 1e-12)
        assertEquals(0.5, shares.getValue(100_000L), 1e-12)
    }

    @Test
    fun `constants refuse missing method rows and missing facts`() {
        val results = File(temporaryRoot, "journal.csv").apply {
            writeText(SYNTHETIC_CSV.lineSequence().filterNot { "realReplay" in it }.joinToString("\n"))
        }
        ThroughputReport.runLogFor(results).writeText(SYNTHETIC_LOG)
        assertThrows(IllegalArgumentException::class.java) {
            JournalCalibration.constantsFromJmh(results, "abc123")
        }

        results.writeText(SYNTHETIC_CSV)
        ThroughputReport.runLogFor(results).writeText(
            SYNTHETIC_LOG.lineSequence().filterNot { "cardinality=10000" in it }.joinToString("\n"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            JournalCalibration.constantsFromJmh(results, "abc123")
        }
    }

    @Test
    fun `merge keeps five unrelated calibration names`() {
        val export = File(temporaryRoot, "cost-model.json").toPath()
        val env = environment("old")
        val writer = CostWriter("structural-sha")
        writer.admit(
            PointOutcome.Ok(
                at = JournalPlan.coordinate(1, 1),
                subject = CostSubject.JOURNAL,
                counts = StructuralCounts(
                    messages = null,
                    messagesNotMeasured = "not measured",
                    crossHostFrames = 0,
                    wireBytes = 0,
                    journalRecords = 1,
                    simSteps = 1,
                ),
                wall = null,
            ),
        )
        repeat(5) { index ->
            writer.calibrate(
                CalibrationConstant.from(
                    "existing$index",
                    BenchResult(1.0, "ns/op", 0.0, Drive.REAL, env),
                ),
            )
        }
        writer.write(export)

        val results = File(temporaryRoot, "journal.csv").apply { writeText(SYNTHETIC_CSV) }
        ThroughputReport.runLogFor(results).writeText(SYNTHETIC_LOG)
        val constants = JournalCalibration.constantsFromJmh(results, "abc123")
        val merged = JournalCalibration.merge(export, constants)

        assertEquals(
            (0 until 5).map { "existing$it" }.toSet() + JournalCalibration.NAMES,
            merged.calibration.map { it.name }.toSet(),
        )
        assertEquals(merged, CostSchema.parse(Files.readString(export)))
    }

    companion object {
        private val SYNTHETIC_CSV = buildString {
            appendLine("\"Benchmark\",\"Mode\",\"Threads\",\"Samples\",\"Score\",\"Score Error (99.9%)\",\"Unit\",\"Param: cardinality\"")
            JournalPlan.REAL_CARDINALITIES.forEach { cardinality ->
                val factor = cardinality / 1_000.0
                appendLine(row(JournalCalibration.METHOD_FILE_JOURNAL_POINT, cardinality, factor))
                appendLine(row(JournalCalibration.METHOD_MEMORY_JOURNAL_POINT, cardinality, factor / 2.0))
                appendLine(row(JournalCalibration.METHOD_REPLAY, cardinality, factor * 0.4))
                appendLine(row(JournalCalibration.METHOD_CHECKPOINT, cardinality, factor * 0.5))
            }
        }

        private val SYNTHETIC_LOG = """
            # JMH version: 1.37
            # VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11
            # VM options: <none>
            # Warmup: 2 iterations, 1 s each
            # Measurement: 5 iterations, 1 s each
            # Benchmark mode: Single shot invocation time
            # Benchmark: civictech.bench.macro.JournalCostBenchmark.realReplay
            # Parameters: (cardinality = 10000)
            # Fork: 1 of 3
            # Host CPU model: Synthetic CPU
            # Host core count: 8
            # Host OS: Synthetic OS 1.0
            # Macro journal: cardinality=10000 records=10007 fileBytes=100070 checkpointBytes=20000
            # Macro journal: cardinality=10000 records=10007 fileBytes=100070 checkpointBytes=20000
            # Macro journal: cardinality=10000 records=10007 fileBytes=110077 checkpointBytes=22000
        """.trimIndent()

        private fun row(method: String, cardinality: Long, score: Double): String =
            "\"civictech.bench.macro.JournalCostBenchmark.$method\",\"ss\",1,15,$score,0.01,\"ms/op\",$cardinality"

        private fun environment(sha: String): RunEnvironment = RunEnvironment(
            jvmVendor = "Synthetic",
            jvmVersion = "21",
            heapSettings = "<none>",
            cpuModel = "Synthetic CPU",
            coreCount = 8,
            os = "Synthetic OS",
            jmhMode = "Single shot invocation time",
            forkCount = 3,
            warmupIterations = 2,
            measurementIterations = 5,
            harnessCommitSha = sha,
        )
    }
}
