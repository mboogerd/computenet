package civictech.bench.macro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.HostFacts
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.cost.Calibration
import civictech.bench.cost.CalibrationConstant
import civictech.bench.cost.CostModel
import civictech.bench.cost.CostSchema
import civictech.bench.micro.ThroughputReport
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Converts REAL macro measurements into cost-model calibration constants. */
object CalibrationExport {
    const val FRAME_BYTES_PREFIX: String = "# Macro frame bytes:"
    const val SMALL_FRAME_BYTES_PREFIX: String = "# Macro small frame bytes:"

    const val METHOD_LOCAL_DISPATCH: String = "realLocalDispatch"
    const val METHOD_CROSS_HOST_FRAME: String = "realCrossHostFrame"
    const val METHOD_WIRE_BYTE: String = "realWireByte"
    const val METHOD_JOURNAL_FSYNC: String = "realJournalFsync"

    /** The complete JMH-method to exported-constant mapping (mkyb1-D12). */
    val METHOD_TO_CONSTANT: Map<String, String> = linkedMapOf(
        METHOD_LOCAL_DISPATCH to Calibration.LOCAL_DISPATCH_NANOS,
        METHOD_CROSS_HOST_FRAME to Calibration.CROSS_HOST_FRAME_NANOS,
        METHOD_WIRE_BYTE to Calibration.WIRE_BYTE_NANOS,
        METHOD_JOURNAL_FSYNC to Calibration.JOURNAL_FSYNC_NANOS,
    )

    /**
     * Reads one calibration JMH CSV and its same-stem run log.
     *
     * All four expected methods must occur exactly once. The wire-byte row is divided by
     * the large frame's encoded size from the measuring fork; its score and 99.9% error
     * are scaled by the same factor.
     */
    fun constantsFromJmh(
        results: File,
        harnessCommitSha: String,
    ): List<CalibrationConstant> {
        val logText = readLog(results)
        val rows = ThroughputReport.parseCsv(results.readText())
        rows.firstOrNull { it.method !in METHOD_TO_CONSTANT }?.let { row ->
            refuse(results, "unknown calibration benchmark method '${row.method}'")
        }

        val rowsByMethod = rows.groupBy { it.method }
        METHOD_TO_CONSTANT.keys.forEach { method ->
            val count = rowsByMethod[method]?.size ?: 0
            if (count != 1) {
                refuse(
                    results,
                    if (count == 0) {
                        "missing calibration benchmark method '$method'"
                    } else {
                        "calibration benchmark method '$method' occurs $count times; expected once"
                    },
                )
            }
        }

        val environment = RunEnvironment.forRun(
            measuringJvm = MeasuringJvm.fromJmhLog(logText, results.absolutePath),
            knobs = RunKnobs.fromJmhLog(logText, results.absolutePath),
            hostFacts = HostFacts.fromJmhLog(logText, results.absolutePath),
            harnessCommitSha = harnessCommitSha,
        )
        val largeFrameBytes = loggedBytes(results, logText, FRAME_BYTES_PREFIX)

        return METHOD_TO_CONSTANT.map { (method, name) ->
            val row = rowsByMethod.getValue(method).single()
            val drive = ThroughputReport.driveOf(row)
            if (drive != Drive.REAL) {
                refuse(results, "calibration method '$method' names drive=$drive, expected REAL")
            }
            val divisor = if (method == METHOD_WIRE_BYTE) largeFrameBytes.toDouble() else 1.0
            CalibrationConstant.from(
                name,
                BenchResult(
                    value = row.score / divisor,
                    unit = if (method == METHOD_WIRE_BYTE) "ns/byte" else row.unit,
                    dispersion = row.scoreError / divisor,
                    drive = drive,
                    env = environment,
                ),
            )
        }
    }

    /** Reuses the retained-size probe result already recorded in a prior export. */
    fun recordedRetainedBytesPerElement(recordedExport: Path): CalibrationConstant {
        val matches = CostSchema.parse(Files.readString(recordedExport)).calibration.filter {
            it.name == Calibration.RETAINED_BYTES_PER_ELEMENT
        }
        require(matches.size == 1) {
            "$recordedExport: expected exactly one recorded " +
                "${Calibration.RETAINED_BYTES_PER_ELEMENT} constant, found ${matches.size}"
        }
        return matches.single()
    }

    /**
     * Replaces an existing export's calibration section and validates before writing.
     *
     * Structural points, exclusions, laws, envelope, schema version and harness SHA are
     * copied untouched. Re-parsing the encoded replacement applies every schema invariant
     * (including duplicate calibration-name refusal) before the original file is changed.
     */
    fun merge(export: Path, constants: List<CalibrationConstant>): CostModel {
        require(constants.isNotEmpty()) { "calibration constants must not be empty" }
        val existing = CostSchema.parse(Files.readString(export))
        val encoded = CostSchema.encode(existing.copy(calibration = constants.toList()))
        val validated = CostSchema.parse(encoded)
        Files.writeString(export, encoded)
        return validated
    }

    /** The large encoded frame size recorded by the measuring fork. */
    internal fun largeFrameBytes(results: File): Long =
        loggedBytes(results, readLog(results), FRAME_BYTES_PREFIX)

    /** The small encoded frame size recorded by the measuring fork. */
    internal fun smallFrameBytes(results: File): Long =
        loggedBytes(results, readLog(results), SMALL_FRAME_BYTES_PREFIX)

    private fun readLog(results: File): String {
        if (!results.isFile) refuse(results, "JMH results file does not exist")
        val log = ThroughputReport.runLogFor(results)
        if (!log.isFile) {
            refuse(results, "run log does not exist beside it at ${log.absolutePath}")
        }
        return log.readText()
    }

    private fun loggedBytes(results: File, logText: String, prefix: String): Long {
        val values = logText.lineSequence().mapNotNull { line ->
            val marker = line.indexOf(prefix)
            if (marker < 0) return@mapNotNull null
            val raw = line.substring(marker + prefix.length).trim()
            raw.toLongOrNull() ?: refuse(
                results,
                "run log has non-integer '$prefix' value '$raw'",
            )
        }.distinct().toList()
        if (values.isEmpty()) refuse(results, "run log has no '$prefix <n>' line")
        if (values.size > 1) {
            refuse(results, "run log states '$prefix' two distinct ways: $values")
        }
        val value = values.single()
        if (value <= 0L) refuse(results, "run log states non-positive '$prefix' value $value")
        return value
    }

    private fun refuse(results: File, detail: String): Nothing =
        throw IllegalArgumentException("${results.absolutePath}: $detail")
}
