package civictech.bench.macro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.TriggerClaim
import civictech.bench.cost.Calibration
import civictech.bench.cost.CalibrationConstant
import civictech.bench.cost.CostSchema
import civictech.bench.micro.Footprint
import civictech.bench.micro.LabelledResult
import civictech.bench.micro.ThroughputReport
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/** The explicit, long-running render step for the five REAL calibration constants. */
@Tag("bench")
class MacroCalibrationRenderTest {

    @Test
    fun `merges macro calibration and prints the two provenance-homogeneous entries`() {
        val resultsPath = System.getProperty("civictech.bench.jmhResults")
        requireNotNull(resultsPath?.takeIf { it.isNotBlank() }) {
            "set -Dcivictech.bench.jmhResults=<macro calibration JMH CSV>"
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
        val recordedRetainedPath = System.getProperty(RECORDED_RETAINED_PROPERTY)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv(RECORDED_RETAINED_ENV)?.takeIf { it.isNotBlank() }
        // Unset: a fresh calibration runs the in-process probe here (mkyb1-D12). Set: a
        // re-merge reuses the recorded constant with its original measuring provenance.

        val results = File(resultsPath!!)
        val jmhConstants = CalibrationExport.constantsFromJmh(results, harnessSha!!)
        val recordedRetained = recordedRetainedPath?.let { File(it).toPath() }
        val retained = if (recordedRetained != null) {
            CalibrationExport.recordedRetainedBytesPerElement(recordedRetained)
        } else {
            CalibrationExport.retainedBytesPerElement(
                harnessCommitSha = harnessSha,
                elements = RETAINED_ELEMENTS,
            )
        }
        val export = File(repoRoot!!, CostSchema.STABLE_PATH).toPath()
        val merged = CalibrationExport.merge(export, jmhConstants + retained)

        merged.calibration.map { it.name }.toSet() shouldBe ALL_CONSTANTS

        val date = System.getProperty("civictech.bench.date")
            ?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().toString()
        val jmhReport = ThroughputReport.renderResults(
            results = jmhConstants.map(::labelledJmhConstant),
            date = date,
            subject = "macro REAL per-unit time calibration",
            trigger = TriggerClaim.None,
        )
        val retainedReport = ThroughputReport.renderResults(
            results = listOf(
                LabelledResult(
                    label = retained.name,
                    result = retained.toBenchResult(),
                ),
            ),
            date = date,
            subject = "SetCell retained bytes per element",
            trigger = TriggerClaim.None,
        )

        println(jmhReport.text())
        println()
        println(retainedReport.text())
        println()
        println("Calibration provenance:")
        println(
            "JMH command: <toolchain-21>/bin/java -jar bench/build/libs/bench-jmh.jar " +
                "'MacroCalibrationBenchmark' -rf csv -rff ${results.absolutePath} " +
                "2>&1 | tee ${ThroughputReport.runLogFor(results).absolutePath}",
        )
        println("largeFrameBytes=${CalibrationExport.largeFrameBytes(results)}")
        println("smallFrameBytes=${CalibrationExport.smallFrameBytes(results)}")
        if (recordedRetained != null) {
            println(
                "retained calibration reused from ${recordedRetained.toAbsolutePath()} " +
                    "(measured by harness ${retained.env.harnessCommitSha})",
            )
        } else {
            println(
                "retained footprint elements=$RETAINED_ELEMENTS " +
                    "replicates=${Footprint.DEFAULT_REPLICATES}",
            )
        }
    }

    private fun labelledJmhConstant(constant: CalibrationConstant): LabelledResult {
        val method = CalibrationExport.METHOD_TO_CONSTANT.entries
            .single { it.value == constant.name }
            .key
        return LabelledResult(
            label = constant.name,
            result = constant.toBenchResult(),
            benchmarkClass = "MacroCalibrationBenchmark",
            benchmarkMethod = method,
        )
    }

    private fun CalibrationConstant.toBenchResult(): BenchResult = BenchResult(
        value = value,
        unit = unit,
        dispersion = dispersion,
        drive = Drive.REAL,
        env = env,
    )

    companion object {
        private const val RETAINED_ELEMENTS = 100_000
        private const val RECORDED_RETAINED_PROPERTY =
            "civictech.bench.recordedRetainedCalibration"
        private const val RECORDED_RETAINED_ENV =
            "CIVICTECH_BENCH_RECORDED_RETAINED_CALIBRATION"
        private val ALL_CONSTANTS = setOf(
            Calibration.LOCAL_DISPATCH_NANOS,
            Calibration.CROSS_HOST_FRAME_NANOS,
            Calibration.WIRE_BYTE_NANOS,
            Calibration.JOURNAL_FSYNC_NANOS,
            Calibration.RETAINED_BYTES_PER_ELEMENT,
        )
    }
}
