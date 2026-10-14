package civictech.bench.macro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.cost.Calibration
import civictech.bench.cost.CalibrationConstant
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSchema
import civictech.bench.cost.CostSubject
import civictech.bench.cost.CostWriter
import civictech.bench.cost.Dimensions
import civictech.bench.cost.GrowthForm
import civictech.bench.cost.InvalidCostModelException
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.StructuralCounts
import civictech.bench.micro.ThroughputReport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CalibrationExportTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `JMH rows become four real constants with logged environment and wire-byte scaling`() {
        val results = artifacts()

        val constants = CalibrationExport.constantsFromJmh(results, HARNESS_SHA)

        constants.map { it.name } shouldBe CalibrationExport.METHOD_TO_CONSTANT.values.toList()
        constants.map { it.env }.distinct().size shouldBe 1
        constants.all { it.unit == "ns/op" || it.unit == "ns/byte" } shouldBe true
        val wire = constants.single { it.name == Calibration.WIRE_BYTE_NANOS }
        wire.value shouldBe (65_536.0 / FRAME_BYTES)
        wire.dispersion shouldBe (6_553.6 / FRAME_BYTES)
        wire.unit shouldBe "ns/byte"
        wire.env.jvmVersion shouldBe "21.0.11"
        wire.env.forkCount shouldBe 3
        wire.env.cpuModel shouldBe "Fixture CPU"
    }

    @Test
    fun `missing calibration method is refused naming the results file`() {
        val results = artifacts(methods = EXPECTED_METHODS - CalibrationExport.METHOD_JOURNAL_FSYNC)

        val failure = shouldThrow<IllegalArgumentException> {
            CalibrationExport.constantsFromJmh(results, HARNESS_SHA)
        }

        failure.message shouldContain results.absolutePath
        failure.message shouldContain CalibrationExport.METHOD_JOURNAL_FSYNC
    }

    @Test
    fun `unknown calibration method is refused naming the results file`() {
        val results = artifacts(methods = EXPECTED_METHODS + "realUnknownCalibration")

        val failure = shouldThrow<IllegalArgumentException> {
            CalibrationExport.constantsFromJmh(results, HARNESS_SHA)
        }

        failure.message shouldContain results.absolutePath
        failure.message shouldContain "realUnknownCalibration"
    }

    @Test
    fun `missing frame bytes line is refused naming the results file`() {
        val results = artifacts(frameLines = emptyList())

        val failure = shouldThrow<IllegalArgumentException> {
            CalibrationExport.constantsFromJmh(results, HARNESS_SHA)
        }

        failure.message shouldContain results.absolutePath
        failure.message shouldContain CalibrationExport.FRAME_BYTES_PREFIX
    }

    @Test
    fun `two distinct frame byte values are refused naming the results file`() {
        val results = artifacts(frameLines = listOf(FRAME_BYTES, FRAME_BYTES + 1))

        val failure = shouldThrow<IllegalArgumentException> {
            CalibrationExport.constantsFromJmh(results, HARNESS_SHA)
        }

        failure.message shouldContain results.absolutePath
        failure.message shouldContain "two distinct ways"
    }

    @Test
    fun `merge replaces calibration and preserves every structural section`() {
        val export = tempDir.resolve("cost-model.json")
        val before = syntheticModel(export)
        val first = listOf(
            constant(Calibration.LOCAL_DISPATCH_NANOS, 11.0),
            constant(Calibration.CROSS_HOST_FRAME_NANOS, 22.0),
        )

        val merged = CalibrationExport.merge(export, first)

        CostSchema.parse(Files.readString(export)) shouldBe merged
        merged.calibration shouldBe first
        merged.structural shouldBe before.structural
        merged.exclusions shouldBe before.exclusions
        merged.laws shouldBe before.laws
        merged.envelope shouldBe before.envelope
        merged.harnessSha shouldBe before.harnessSha

        val replacement = listOf(constant(Calibration.JOURNAL_FSYNC_NANOS, 33.0))
        CalibrationExport.merge(export, replacement).calibration shouldBe replacement

        val validReplacement = Files.readString(export)
        shouldThrow<InvalidCostModelException> {
            CalibrationExport.merge(export, listOf(replacement.single(), replacement.single()))
        }
        Files.readString(export) shouldBe validReplacement
        shouldThrow<IllegalArgumentException> { CalibrationExport.merge(export, emptyList()) }
    }

    @Test
    fun `SIM result cannot become a calibration constant from this package`() {
        shouldThrow<IllegalArgumentException> {
            CalibrationConstant.from(
                Calibration.LOCAL_DISPATCH_NANOS,
                BenchResult(1.0, "ns/op", 0.1, Drive.SIM, environment()),
            )
        }
    }

    private fun artifacts(
        methods: List<String> = EXPECTED_METHODS,
        frameLines: List<Long> = listOf(FRAME_BYTES),
    ): File {
        val results = Files.createTempFile(tempDir, "macro-", ".csv").toFile()
        results.writeText(csv(methods))
        val log = ThroughputReport.runLogFor(results)
        log.writeText(
            buildString {
                appendLine("# JMH version: 1.37")
                appendLine("# VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+9")
                appendLine("# VM invoker: /nonexistent/fixture-jdk/bin/java")
                appendLine("# VM options: -Xms2g -Xmx2g")
                appendLine("# Benchmark mode: Average time, time/op")
                appendLine("# Warmup: 5 iterations, 1 s each")
                appendLine("# Measurement: 5 iterations, 1 s each")
                appendLine("# Fork: 1 of 3")
                appendLine("# Host CPU model: Fixture CPU")
                appendLine("# Host core count: 12")
                appendLine("# Host OS: Fixture OS")
                frameLines.forEach { appendLine("${CalibrationExport.FRAME_BYTES_PREFIX} $it") }
            },
        )
        return results
    }

    private fun csv(methods: List<String>): String = buildString {
        appendLine("Benchmark,Mode,Score,Score Error (99.9%),Unit")
        methods.forEach { method ->
            val (score, error) = when (method) {
                CalibrationExport.METHOD_LOCAL_DISPATCH -> 1_000.0 to 100.0
                CalibrationExport.METHOD_CROSS_HOST_FRAME -> 2_000.0 to 200.0
                CalibrationExport.METHOD_WIRE_BYTE -> 65_536.0 to 6_553.6
                CalibrationExport.METHOD_JOURNAL_FSYNC -> 4_000.0 to 400.0
                else -> 5_000.0 to 500.0
            }
            appendLine(
                "civictech.bench.macro.MacroCalibrationBenchmark.$method,avgt," +
                    "$score,$error,ns/op",
            )
        }
    }

    private fun syntheticModel(export: Path) = CostWriter(STRUCTURAL_SHA).run {
        val points = listOf(1, 4, 16, 64).map(::coordinate)
        points.forEach { at ->
            admit(
                PointOutcome.Ok(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    counts = counts(messages = 3L * at.fanOut + 7L),
                    wall = null,
                ),
            )
        }
        admit(
            PointOutcome.NonConvergent(
                at = coordinate(256),
                subject = CostSubject.PROPAGATION,
                budget = "60_000 steps",
            ),
        )
        fit(
            dimension = Dimensions.FAN_OUT,
            subject = CostSubject.PROPAGATION,
            metric = "messages",
            form = GrowthForm.LINEAR,
            points = points,
        )
        calibrate(constant("existingConstant", 99.0))
        write(export)
    }

    private fun coordinate(fanOut: Int): Coordinate = Coordinate(
        shape = "fanOutTree",
        shapeSeed = 1L,
        cardinality = 10_000,
        batchSize = 10,
        hostCount = 3,
        fanOut = fanOut,
        replication = false,
        durability = false,
        drive = Drive.SIM,
    )

    private fun counts(messages: Long): StructuralCounts = StructuralCounts(
        messages = messages,
        messagesNotMeasured = null,
        crossHostFrames = messages / 2,
        wireBytes = messages * 64,
        journalRecords = 0,
        simSteps = messages * 2,
    )

    private fun constant(name: String, value: Double): CalibrationConstant =
        CalibrationConstant.from(
            name,
            BenchResult(value, "ns/op", value / 10.0, Drive.REAL, environment()),
        )

    private fun environment(): RunEnvironment = RunEnvironment(
        jvmVendor = "Fixture VM",
        jvmVersion = "21",
        heapSettings = "-Xmx2g",
        cpuModel = "Fixture CPU",
        coreCount = 12,
        os = "Fixture OS",
        jmhMode = "AverageTime",
        forkCount = 3,
        warmupIterations = 5,
        measurementIterations = 5,
        harnessCommitSha = HARNESS_SHA,
    )

    companion object {
        private const val HARNESS_SHA = "abc1234"
        private const val STRUCTURAL_SHA = "def5678"
        private const val FRAME_BYTES = 65_536L
        private val EXPECTED_METHODS = CalibrationExport.METHOD_TO_CONSTANT.keys.toList()
    }
}
