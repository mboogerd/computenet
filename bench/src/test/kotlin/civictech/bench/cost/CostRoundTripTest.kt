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
    fun `the stable version two export round trips with migrated unfitted evidence`() {
        val export = Path.of("..").resolve(CostSchema.STABLE_PATH)
        val model = CostSchema.parse(export.toFile().readText())

        model.schemaVersion shouldBe CostSchema.VERSION
        model.unfitted.size shouldBe 30
        model.unfitted.groupingBy { it.dimension }.eachCount() shouldBe mapOf(
            Dimensions.FAN_OUT to 5,
            Dimensions.CARDINALITY to 10,
            Dimensions.HOST_COUNT to 15,
        )
        model.unfitted.filter { it.fittedOver.isEmpty() }.map { it.slice }.toSet() shouldBe setOf(
            "n=100000 f=64 REBASELINE_REMOTE_TAPS",
            "n=100000 f=256 REBASELINE_REMOTE_TAPS",
        )
        CostSchema.parse(CostSchema.encode(model)) shouldBe model
    }

    @Test
    fun `an unknown schema version is rejected before entries are decoded`() {
        val original = CostSchema.encode(populatedModel())
        val replaced = original.replace(
            "\"schemaVersion\": ${CostSchema.VERSION}",
            "\"schemaVersion\": 99",
        )
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
    fun `the version one export is rejected after the unfitted evidence migration`() {
        val versionOne = CostSchema.encode(populatedModel()).replace(
            "\"schemaVersion\": ${CostSchema.VERSION}",
            "\"schemaVersion\": 1",
        )

        val exception = shouldThrow<UnknownSchemaVersionException> {
            CostSchema.parse(versionOne)
        }

        exception.message shouldContain "1"
        exception.message shouldContain CostSchema.VERSION.toString()
    }

    @Test
    fun `a missing schema version is rejected`() {
        val withoutVersion = CostSchema.encode(populatedModel())
            .replace(
                Regex("\\s*\\\"schemaVersion\\\": ${CostSchema.VERSION},?\\s*"),
                "",
            )

        val exception = shouldThrow<UnknownSchemaVersionException> {
            CostSchema.parse(withoutVersion)
        }
        exception.message shouldContain "missing"
    }

    @Test
    fun `an envelope missing any dimension is rejected naming that dimension`() {
        val model = populatedModel()

        allDimensions.forEach { dimension ->
            val envelope = if (dimension in model.envelope.numeric) {
                model.envelope.copy(numeric = model.envelope.numeric - dimension)
            } else {
                model.envelope.copy(categorical = model.envelope.categorical - dimension)
            }

            parseFailure(model.copy(envelope = envelope)).message shouldContain dimension
        }
    }

    @Test
    fun `an envelope differing from admitted structural entries is rejected naming the dimension`() {
        val model = populatedModel()

        allDimensions.forEach { dimension ->
            val envelope = if (dimension in model.envelope.numeric) {
                val range = model.envelope.numeric.getValue(dimension)
                model.envelope.copy(
                    numeric = model.envelope.numeric +
                        (dimension to range.copy(max = range.max + 1.0)),
                )
            } else {
                val values = model.envelope.categorical.getValue(dimension)
                model.envelope.copy(
                    categorical = model.envelope.categorical +
                        (dimension to (values + "not-admitted")),
                )
            }

            parseFailure(model.copy(envelope = envelope)).message shouldContain dimension
        }
    }

    @Test
    fun `duplicate structural keys are rejected naming the key`() {
        val model = populatedModel()
        val duplicate = model.structural.first()

        val exception = parseFailure(model.copy(structural = model.structural + duplicate))

        exception.message shouldContain duplicate.subject.toString()
        exception.message shouldContain duplicate.at.toString()
    }

    @Test
    fun `duplicate calibration names are rejected naming the calibration`() {
        val model = populatedModel()
        val duplicate = model.calibration.single()

        parseFailure(model.copy(calibration = model.calibration + duplicate)).message shouldContain
            duplicate.name
    }

    @Test
    fun `duplicate exclusion keys are rejected naming the key`() {
        val model = populatedModel()
        val duplicate = model.exclusions.single()

        val exception = parseFailure(model.copy(exclusions = model.exclusions + duplicate))

        exception.message shouldContain duplicate.subject.toString()
        exception.message shouldContain duplicate.at.toString()
    }

    @Test
    fun `an exclusion sharing an admitted structural key is rejected naming the key`() {
        val model = populatedModel()
        val admitted = model.structural.first()
        val conflict = ExcludedPoint(
            at = admitted.at,
            subject = admitted.subject,
            kind = "Failed",
            reason = "synthetic conflict",
        )

        val exception = parseFailure(model.copy(exclusions = model.exclusions + conflict))

        exception.message shouldContain admitted.subject.toString()
        exception.message shouldContain admitted.at.toString()
    }

    @Test
    fun `a structural entry measured under REAL drive is rejected naming its key`() {
        val model = populatedModel()
        val structural = model.structural.first()
        val real = structural.copy(at = structural.at.copy(drive = Drive.REAL))

        val exception = parseFailure(
            model.copy(structural = listOf(real) + model.structural.drop(1)),
        )

        exception.message shouldContain "SIM"
        exception.message shouldContain real.subject.toString()
        exception.message shouldContain real.at.toString()
    }

    @Test
    fun `growth laws duplicated with fitted points in another order are rejected naming the key`() {
        val model = populatedModel()
        val points = model.structural.map { it.at }
        val law = model.laws.single().copy(fittedOver = points)
        val permutedDuplicate = law.copy(fittedOver = points.reversed())

        val exception = parseFailure(model.copy(laws = listOf(law, permutedDuplicate)))

        exception.message shouldContain law.dimension
        exception.message shouldContain law.subject.toString()
        exception.message shouldContain law.metric
    }

    @Test
    fun `duplicate unfitted slices are rejected naming the slice`() {
        val model = populatedModel()
        val fit = model.unfitted.single()

        val exception = parseFailure(model.copy(unfitted = listOf(fit, fit)))

        exception.message shouldContain fit.slice
        exception.message shouldContain fit.dimension
        exception.message shouldContain fit.subject.toString()
    }

    @Test
    fun `an unfitted slice cannot cite a point that was not admitted`() {
        val model = populatedModel()
        val fit = model.unfitted.single()
        val absent = fit.fittedOver.single().copy(fanOut = 99)

        val exception = parseFailure(
            model.copy(unfitted = listOf(fit.copy(fittedOver = listOf(absent)))),
        )

        exception.message shouldContain fit.slice
        exception.message shouldContain absent.toString()
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
                Dimensions.CARDINALITY to Envelope.Range(100.0, 1_000.0),
                Dimensions.BATCH_SIZE to Envelope.Range(4.0, 4.0),
                Dimensions.HOST_COUNT to Envelope.Range(1.0, 1.0),
                Dimensions.FAN_OUT to Envelope.Range(2.0, 2.0),
            ),
            categorical = mapOf(
                Dimensions.SHAPE to setOf("chain"),
                Dimensions.REPLICATION to setOf("false"),
                Dimensions.DURABILITY to setOf("true"),
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
            unfitted = listOf(
                UnfittedFit(
                    dimension = Dimensions.CARDINALITY,
                    slice = "single re-baseline point",
                    subject = CostSubject.REBASELINE,
                    metric = "wireBytes",
                    form = GrowthForm.LINEAR,
                    fittedOver = listOf(coordinate(Drive.SIM).copy(cardinality = 1_000)),
                    reason = "a law requires at least 4 points, found 1",
                    goodnessOfFit = null,
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

    private fun parseFailure(model: CostModel): IllegalArgumentException =
        shouldThrow { CostSchema.parse(CostSchema.encode(model)) }

    private val allDimensions = listOf(
        Dimensions.CARDINALITY,
        Dimensions.BATCH_SIZE,
        Dimensions.HOST_COUNT,
        Dimensions.FAN_OUT,
        Dimensions.SHAPE,
        Dimensions.REPLICATION,
        Dimensions.DURABILITY,
    )

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
