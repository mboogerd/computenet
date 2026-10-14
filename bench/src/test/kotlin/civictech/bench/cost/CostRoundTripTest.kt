package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Round-trip and refusal proofs for the cost export (BS-17, BS-18, BS-8). */
class CostRoundTripTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a populated cost model round trips through a temp file`() {
        val model = populatedModel()
        val file = tempDir.resolve("cost-model.json")

        file.toFile().writeText(CostSchema.encode(model))

        CostSchema.parse(file.toFile().readText()) shouldBe model
    }

    @Test
    fun `an unknown schema version is rejected before entries are decoded`() {
        val original = CostSchema.encode(populatedModel())
        val replaced = original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        replaced shouldNotBe original

        val exception = shouldThrow<UnknownSchemaVersionException> {
            CostSchema.parse(replaced)
        }
        exception.message shouldContain "99"

        val corrupted = replaced.replaceFirst(
            Regex("\\\"structural\\\"\\s*:\\s*\\[(?s:.*?)\\n\\s*],"),
            "\"structural\": [{}],",
        )
        corrupted shouldNotBe replaced
        shouldThrow<UnknownSchemaVersionException> { CostSchema.parse(corrupted) }
    }

    @Test
    fun `a missing schema version is rejected`() {
        val withoutVersion = CostSchema.encode(populatedModel())
            .replace(Regex("\\s*\\\"schemaVersion\\\": 1,?\\s*"), "")

        val exception = shouldThrow<UnknownSchemaVersionException> {
            CostSchema.parse(withoutVersion)
        }
        exception.message shouldContain "missing"
    }

    @Test
    fun `calibration constants require a real result`() {
        shouldThrow<IllegalArgumentException> {
            CalibrationConstant.from(
                Calibration.LOCAL_DISPATCH_NANOS,
                result(drive = Drive.SIM),
            )
        }

        val constant = CalibrationConstant.from(
            Calibration.LOCAL_DISPATCH_NANOS,
            result(drive = Drive.REAL, value = 12.0, unit = "ns", dispersion = 0.5),
        )
        constant.value shouldBe 12.0
        constant.unit shouldBe "ns"
        constant.dispersion shouldBe 0.5
        constant.env shouldBe validEnvironment()
    }

    @Test
    fun `point outcomes and structural counts enforce admission invariants`() {
        val sim = coordinate(Drive.SIM)
        val real = coordinate(Drive.REAL)
        val counts = StructuralCounts(
            messages = 10,
            messagesNotMeasured = null,
            crossHostFrames = 2,
            wireBytes = 80,
            journalRecords = 1,
            simSteps = 20,
        )

        shouldThrow<IllegalArgumentException> {
            PointOutcome.Ok(sim, CostSubject.PROPAGATION, counts, result(drive = Drive.SIM))
        }
        PointOutcome.Ok(sim, CostSubject.PROPAGATION, counts, wall = null)
        PointOutcome.Ok(real, CostSubject.PROPAGATION, counts, result(drive = Drive.REAL))

        shouldThrow<IllegalArgumentException> {
            StructuralCounts(
                messages = null,
                messagesNotMeasured = null,
                crossHostFrames = 0,
                wireBytes = 0,
                journalRecords = 0,
                simSteps = 0,
            )
        }
        shouldThrow<IllegalArgumentException> {
            StructuralCounts(
                messages = 1,
                messagesNotMeasured = "not measured",
                crossHostFrames = 0,
                wireBytes = 0,
                journalRecords = 0,
                simSteps = 0,
            )
        }
    }

    @Test
    fun `growth laws require goodness of fit in the unit interval`() {
        shouldThrow<IllegalArgumentException> {
            GrowthLaw(
                dimension = Dimensions.FAN_OUT,
                subject = CostSubject.PROPAGATION,
                metric = "messages",
                form = GrowthForm.LINEAR,
                coefficients = listOf(1.0, 0.0),
                fittedOver = listOf(coordinate(Drive.SIM)),
                goodnessOfFit = 1.5,
            )
        }
    }

    private fun populatedModel(): CostModel {
        val envelope = Envelope(
            numeric = mapOf(
                Dimensions.CARDINALITY to Envelope.Range(1.0, 1_000.0),
                Dimensions.BATCH_SIZE to Envelope.Range(1.0, 32.0),
                Dimensions.HOST_COUNT to Envelope.Range(1.0, 8.0),
                Dimensions.FAN_OUT to Envelope.Range(1.0, 16.0),
            ),
            categorical = mapOf(
                Dimensions.SHAPE to setOf("chain", "diamond"),
                Dimensions.REPLICATION to setOf("false", "true"),
                Dimensions.DURABILITY to setOf("false", "true"),
            ),
        )
        val firstCounts = StructuralCounts(10, null, 2, 80, 1, 20)
        val secondCounts = StructuralCounts(20, null, 4, 160, 2, 40)
        return CostModel(
            schemaVersion = CostSchema.VERSION,
            harnessSha = "0123456789abcdef0123456789abcdef01234567",
            envelope = envelope,
            structural = listOf(
                StructuralCost(
                    at = coordinate(Drive.SIM),
                    subject = CostSubject.PROPAGATION,
                    counts = firstCounts,
                    harnessSha = "0123456789abcdef0123456789abcdef01234567",
                ),
                StructuralCost(
                    at = coordinate(Drive.SIM).copy(cardinality = 1_000),
                    subject = CostSubject.REBASELINE,
                    counts = secondCounts,
                    harnessSha = "fedcba9876543210fedcba9876543210fedcba98",
                    transportValidated = true,
                ),
            ),
            calibration = listOf(
                CalibrationConstant.from(
                    Calibration.LOCAL_DISPATCH_NANOS,
                    result(drive = Drive.REAL, value = 15.0, unit = "ns", dispersion = 0.25),
                ),
            ),
            laws = listOf(
                GrowthLaw(
                    dimension = Dimensions.FAN_OUT,
                    subject = CostSubject.PROPAGATION,
                    metric = "messages",
                    form = GrowthForm.LINEAR,
                    coefficients = listOf(2.0, 1.0),
                    fittedOver = listOf(coordinate(Drive.SIM)),
                    goodnessOfFit = 1.0,
                ),
            ),
            exclusions = listOf(
                ExcludedPoint(
                    at = coordinate(Drive.SIM).copy(shape = "randomDag"),
                    subject = CostSubject.JOURNAL,
                    kind = "NonConvergent",
                    reason = "budget exhausted",
                ),
            ),
        )
    }

    private fun coordinate(drive: Drive): Coordinate = Coordinate(
        shape = "chain",
        shapeSeed = 42L,
        cardinality = 100L,
        batchSize = 4,
        hostCount = 1,
        fanOut = 2,
        replication = false,
        durability = true,
        drive = drive,
    )

    private fun result(
        drive: Drive,
        value: Double = 100.0,
        unit: String = "ops/s",
        dispersion: Double = 1.0,
    ): BenchResult = BenchResult(
        value = value,
        unit = unit,
        dispersion = dispersion,
        drive = drive,
        env = validEnvironment(),
    )

    private fun validEnvironment(): RunEnvironment = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.4",
        heapSettings = "-Xms1g -Xmx4g",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 14.5",
        jmhMode = "Throughput",
        forkCount = 2,
        warmupIterations = 3,
        measurementIterations = 5,
        harnessCommitSha = "0123456789abcdef0123456789abcdef01234567",
    )
}
