package civictech.bench.macro.journal

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.HostFacts
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.RunKnobs
import civictech.bench.cost.CalibrationConstant
import civictech.bench.cost.CostModel
import civictech.bench.macro.CalibrationExport
import civictech.bench.micro.ThroughputReport
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** CSV/log admission and export calculations for the REAL journal study. */
object JournalCalibration {
    const val JOURNAL_BYTES_PER_RECORD: String = "journalBytesPerRecord"
    const val REPLAY_NANOS_PER_RECORD: String = "replayNanosPerRecord"
    const val CHECKPOINT_NANOS_PER_ELEMENT: String = "checkpointNanosPerElement"
    const val CHECKPOINT_BYTES_PER_ELEMENT: String = "checkpointBytesPerElement"

    const val LOG_PREFIX: String = "# Macro journal:"

    const val METHOD_FILE_JOURNAL_POINT: String = "realFileJournalPoint"
    const val METHOD_MEMORY_JOURNAL_POINT: String = "realMemoryJournalPoint"
    const val METHOD_REPLAY: String = "realReplay"
    const val METHOD_CHECKPOINT: String = "realCheckpoint"
    const val METHOD_FILE: String = METHOD_FILE_JOURNAL_POINT
    const val METHOD_MEMORY: String = METHOD_MEMORY_JOURNAL_POINT

    // Short aliases keep the mapping readable at call sites and make the four CSV names
    // part of the same reviewed vocabulary as the exported constant names.
    const val REAL_FILE_JOURNAL_POINT: String = METHOD_FILE_JOURNAL_POINT
    const val REAL_MEMORY_JOURNAL_POINT: String = METHOD_MEMORY_JOURNAL_POINT
    const val REAL_REPLAY: String = METHOD_REPLAY
    const val REAL_CHECKPOINT: String = METHOD_CHECKPOINT

    val NAMES: List<String> = listOf(
        JOURNAL_BYTES_PER_RECORD,
        REPLAY_NANOS_PER_RECORD,
        CHECKPOINT_NANOS_PER_ELEMENT,
        CHECKPOINT_BYTES_PER_ELEMENT,
    )

    private val METHODS: List<String> = listOf(
        METHOD_FILE_JOURNAL_POINT,
        METHOD_MEMORY_JOURNAL_POINT,
        METHOD_REPLAY,
        METHOD_CHECKPOINT,
    )

    private val FACTS = Regex(
        "^${Regex.escape(LOG_PREFIX)} cardinality=(\\d+) records=(\\d+) " +
            "fileBytes=(\\d+) checkpointBytes=(\\d+)$",
    )

    data class JournalFacts(
        val cardinality: Long,
        val records: Long,
        val fileBytes: Long,
        val checkpointBytes: Long,
    )

    /** Parses one facts record per benchmark fork, retaining fork order per cardinality. */
    fun factsFromLog(logText: String): Map<Long, List<JournalFacts>> {
        val facts = linkedMapOf<Long, MutableList<JournalFacts>>()
        logText.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith(LOG_PREFIX)) return@forEachIndexed
            val match = FACTS.matchEntire(line)
                ?: refuse("malformed journal facts line ${index + 1}: '$rawLine'")
            val fact = JournalFacts(
                cardinality = number(match.groupValues[1], "cardinality", index),
                records = number(match.groupValues[2], "records", index),
                fileBytes = number(match.groupValues[3], "fileBytes", index),
                checkpointBytes = number(match.groupValues[4], "checkpointBytes", index),
            )
            facts.getOrPut(fact.cardinality) { mutableListOf() } += fact
        }
        return facts
    }

    /**
     * Admits the complete method × cardinality CSV and derives the four constants at
     * [JournalPlan.CALIBRATION_CARDINALITY]. The two byte ratios use the mean of the
     * fork values and their half-range as dispersion; the two timing ratios retain JMH's
     * 99.9% error after conversion from milliseconds.
     */
    fun constantsFromJmh(results: File, harnessCommitSha: String): List<CalibrationConstant> {
        val logText = readLog(results)
        val rows = ThroughputReport.parseCsv(results.readText())
        val expected = JournalPlan.REAL_CARDINALITIES.flatMap { cardinality ->
            METHODS.map { method -> method to cardinality }
        }.toSet()
        val rowsByKey = linkedMapOf<Pair<String, Long>, civictech.bench.micro.JmhRow>()
        rows.forEach { row ->
            if (row.method !in METHODS) {
                refuse("unknown journal benchmark method '${row.method}'")
            }
            val drive = ThroughputReport.driveOf(row)
            if (drive != Drive.REAL) {
                refuse("journal benchmark '${row.benchmark}' names drive=$drive, expected REAL")
            }
            val cardinality = row.params["cardinality"]?.toLongOrNull()
                ?: refuse("${row.benchmark} has no numeric cardinality parameter")
            if (cardinality !in JournalPlan.REAL_CARDINALITIES) {
                refuse("${row.benchmark} carries undeclared cardinality=$cardinality")
            }
            val key = row.method to cardinality
            if (key in rowsByKey) refuse("duplicate journal result row for $key")
            rowsByKey[key] = row
        }
        val missing = expected - rowsByKey.keys
        if (missing.isNotEmpty()) {
            refuse("missing journal method×cardinality rows: ${missing.sortedWith(compareBy({ it.second }, { it.first }))}")
        }

        val facts = factsFromLog(logText)[JournalPlan.CALIBRATION_CARDINALITY]
            ?: refuse("run log has no '$LOG_PREFIX cardinality=${JournalPlan.CALIBRATION_CARDINALITY}' line")
        if (facts.isEmpty()) refuse("run log has no journal facts for calibration cardinality")
        if (facts.any { it.records <= 0L }) {
            refuse("journal facts at cardinality=${JournalPlan.CALIBRATION_CARDINALITY} contain zero records")
        }

        val environment = RunEnvironment.forRun(
            measuringJvm = MeasuringJvm.fromJmhLog(logText, results.absolutePath),
            knobs = RunKnobs.fromJmhLog(logText, results.absolutePath),
            hostFacts = HostFacts.fromJmhLog(logText, results.absolutePath),
            harnessCommitSha = harnessCommitSha,
        )
        val cardinality = JournalPlan.CALIBRATION_CARDINALITY
        val file = rowsByKey.getValue(METHOD_FILE_JOURNAL_POINT to cardinality)
        val memory = rowsByKey.getValue(METHOD_MEMORY_JOURNAL_POINT to cardinality)
        val replay = rowsByKey.getValue(METHOD_REPLAY to cardinality)
        val checkpoint = rowsByKey.getValue(METHOD_CHECKPOINT to cardinality)
        val fileBytes = ratio(facts.map { it.fileBytes.toDouble() / it.records }, "bytes/record")
        val checkpointBytes = ratio(
            facts.map { it.checkpointBytes.toDouble() / cardinality },
            "bytes/element",
        )

        return listOf(
            ratioConstant(JOURNAL_BYTES_PER_RECORD, fileBytes, "bytes/record", environment),
            timingConstant(
                REPLAY_NANOS_PER_RECORD,
                replay,
                facts.map { it.records }.average(),
                "ns/record",
                environment,
            ),
            timingConstant(
                CHECKPOINT_NANOS_PER_ELEMENT,
                checkpoint,
                cardinality.toDouble(),
                "ns/element",
                environment,
            ),
            ratioConstant(CHECKPOINT_BYTES_PER_ELEMENT, checkpointBytes, "bytes/element", environment),
        ).also {
            // Keep these reads explicit: every calibration method is required even though
            // only three of their rows contribute numerically to the four constants.
            check(file.score.isFinite() && memory.score.isFinite())
        }
    }

    /** Returns `(file - memory) / file` for every cardinality with both REAL rows. */
    fun fsyncShares(results: File): Map<Long, Double> {
        val rows = ThroughputReport.parseCsv(results.readText())
        val grouped = rows.filter { it.method == METHOD_FILE_JOURNAL_POINT || it.method == METHOD_MEMORY_JOURNAL_POINT }
            .groupBy { row ->
                ThroughputReport.driveOf(row).also { drive ->
                    if (drive != Drive.REAL) refuse("journal fsync comparison requires REAL, was $drive")
                }
                row.params["cardinality"]?.toLongOrNull()
                    ?: refuse("${row.benchmark} has no numeric cardinality parameter")
            }
        return grouped.entries.sortedBy { it.key }.mapNotNull { (cardinality, candidates) ->
            val file = candidates.filter { it.method == METHOD_FILE_JOURNAL_POINT }
            val memory = candidates.filter { it.method == METHOD_MEMORY_JOURNAL_POINT }
            if (file.isEmpty() || memory.isEmpty()) return@mapNotNull null
            if (file.size != 1 || memory.size != 1) {
                refuse("fsync comparison has duplicate rows at cardinality=$cardinality")
            }
            val fileScore = file.single().score
            require(fileScore > 0.0) { "file journal score must be positive at cardinality=$cardinality" }
            cardinality to ((fileScore - memory.single().score) / fileScore)
        }.toMap()
    }

    /** Replaces only these four names while retaining every other calibration constant. */
    fun merge(export: Path, constants: List<CalibrationConstant>): CostModel {
        require(constants.size == NAMES.size && constants.map { it.name }.toSet() == NAMES.toSet()) {
            "journal calibration must contain exactly $NAMES, got ${constants.map { it.name }}"
        }
        val existing = civictech.bench.cost.CostSchema.parse(Files.readString(export))
        return CalibrationExport.merge(
            export,
            existing.calibration.filterNot { it.name in NAMES } + constants,
        ).also {
            civictech.bench.cost.CostSchema.parse(Files.readString(export))
        }
    }

    private fun timingConstant(
        name: String,
        row: civictech.bench.micro.JmhRow,
        divisor: Double,
        unit: String,
        environment: RunEnvironment,
    ): CalibrationConstant {
        require(row.unit == "ms/op") {
            "journal timing method '${row.method}' reports '${row.unit}', expected ms/op"
        }
        return CalibrationConstant.from(
            name,
            BenchResult(
                value = row.score * NANOS_PER_MILLISECOND / divisor,
                unit = unit,
                dispersion = row.scoreError * NANOS_PER_MILLISECOND / divisor,
                drive = Drive.REAL,
                env = environment,
            ),
        )
    }

    private fun ratioConstant(
        name: String,
        ratio: Ratio,
        unit: String,
        environment: RunEnvironment,
    ): CalibrationConstant = CalibrationConstant.from(
        name,
        BenchResult(
            value = ratio.mean,
            unit = unit,
            dispersion = ratio.dispersion,
            drive = Drive.REAL,
            env = environment,
        ),
    )

    private data class Ratio(val mean: Double, val dispersion: Double)

    private fun ratio(values: List<Double>, unit: String): Ratio {
        require(values.isNotEmpty()) { "cannot derive $unit from no journal facts" }
        require(values.all { it.isFinite() }) { "journal $unit values must be finite" }
        return Ratio(
            mean = values.average(),
            dispersion = (values.maxOrNull()!! - values.minOrNull()!!) / 2.0,
        )
    }

    private fun readLog(results: File): String {
        if (!results.isFile) refuse("JMH results file does not exist at ${results.absolutePath}")
        val log = ThroughputReport.runLogFor(results)
        if (!log.isFile) refuse("run log does not exist beside ${results.absolutePath}")
        return log.readText()
    }

    private fun number(raw: String, field: String, line: Int): Long =
        raw.toLongOrNull()?.takeIf { it >= 0L }
            ?: refuse("malformed journal facts $field on line ${line + 1}: '$raw'")

    private fun refuse(detail: String): Nothing = throw IllegalArgumentException(detail)

    private const val NANOS_PER_MILLISECOND: Double = 1_000_000.0
}
