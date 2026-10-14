package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Admission, envelope derivation, and fit-honesty proofs (BS-9, BS-20). */
class CostEnvelopeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `only an Ok SIM point is admitted and every other outcome is explained`() {
        val writer = CostWriter("abc1234")
        val admittedAt = coordinate(hostCount = 1)
        writer.admit(
            PointOutcome.Ok(
                at = admittedAt,
                subject = CostSubject.PROPAGATION,
                counts = counts(messages = 10),
                wall = null,
            ),
        ).shouldBeInstanceOf<Admission.Admitted>()

        val excluded = listOf(
            PointOutcome.Unreportable(
                at = coordinate(hostCount = 2),
                subject = CostSubject.PROPAGATION,
                result = result(value = 100.0, dispersion = 7.0),
            ),
            PointOutcome.NonConvergent(
                at = coordinate(hostCount = 3),
                subject = CostSubject.PROPAGATION,
                budget = "10_000 steps",
            ),
            PointOutcome.NonDeterministic(
                at = coordinate(hostCount = 4),
                subject = CostSubject.PROPAGATION,
                first = counts(messages = 20),
                second = counts(messages = 21),
            ),
            PointOutcome.Unschedulable(
                at = coordinate(hostCount = 5),
                subject = CostSubject.PROPAGATION,
                reason = "thread budget exhausted",
                observedLimit = "4 hosts",
            ),
            PointOutcome.Failed(
                at = coordinate(hostCount = 6),
                subject = CostSubject.PROPAGATION,
                cause = "scheduler loop exception",
                deadLetters = listOf("lost-a", "lost-b"),
            ),
        )

        excluded.forEach { outcome ->
            writer.admit(outcome).shouldBeInstanceOf<Admission.Excluded>()
        }

        val model = writer.model()
        model.structural.size shouldBe 1
        model.structural.single().at shouldBe admittedAt
        model.structural.single().harnessSha shouldBe "abc1234"
        model.exclusions.size shouldBe 5
        model.exclusions.map { it.kind }.toSet() shouldBe setOf(
            "Unreportable",
            "NonConvergent",
            "NonDeterministic",
            "Unschedulable",
            "Failed",
        )
        model.exclusions.all { it.reason.isNotBlank() } shouldBe true
        model.exclusions.single { it.kind == "Unreportable" }.reason shouldContain
            "relative dispersion 0.07"
        model.exclusions.single { it.kind == "NonDeterministic" }.reason shouldContain
            "messages"
        model.exclusions.single { it.kind == "Failed" }.reason shouldContain "2 dead letters"
    }

    @Test
    fun `real and duplicate structural points are refused as caller errors`() {
        val writer = CostWriter("abc1234")
        val realRefusal = shouldThrow<IllegalArgumentException> {
            writer.admit(
                PointOutcome.Ok(
                    at = coordinate(hostCount = 1, drive = Drive.REAL),
                    subject = CostSubject.PROPAGATION,
                    counts = counts(messages = 10),
                    wall = result(),
                ),
            )
        }
        realRefusal.message shouldContain "calibrate"

        val at = coordinate(hostCount = 2)
        val ok = PointOutcome.Ok(at, CostSubject.PROPAGATION, counts(10), wall = null)
        writer.admit(ok)
        val duplicate = shouldThrow<IllegalArgumentException> { writer.admit(ok) }
        duplicate.message shouldContain "PROPAGATION"
        duplicate.message shouldContain at.toString()
    }

    @Test
    fun `structural and excluded outcomes cannot coexist at one key`() {
        val at = coordinate(hostCount = 2)
        val ok = ok(at, messages = 10)
        val excluded = PointOutcome.NonConvergent(
            at = at,
            subject = CostSubject.PROPAGATION,
            budget = "10_000 steps",
        )

        val structuralFirst = CostWriter("abc1234")
        structuralFirst.admit(ok)
        val exclusionConflict = shouldThrow<IllegalArgumentException> {
            structuralFirst.admit(excluded)
        }
        exclusionConflict.message shouldContain "PROPAGATION"
        exclusionConflict.message shouldContain at.toString()
        structuralFirst.model().structural.map { it.at } shouldBe listOf(at)
        structuralFirst.model().exclusions shouldBe emptyList()

        val exclusionFirst = CostWriter("abc1234")
        exclusionFirst.admit(excluded)
        val structuralConflict = shouldThrow<IllegalArgumentException> {
            exclusionFirst.admit(ok)
        }
        structuralConflict.message shouldContain "PROPAGATION"
        structuralConflict.message shouldContain at.toString()

        val admittedAt = coordinate(hostCount = 3)
        exclusionFirst.admit(ok(admittedAt, messages = 11))
        val model = exclusionFirst.model()
        model.structural.map { it.at } shouldBe listOf(admittedAt)
        model.exclusions.map { it.at } shouldBe listOf(at)
    }

    @Test
    fun `calibration names and the writer sha are unique non-blank identities`() {
        shouldThrow<IllegalArgumentException> { CostWriter("  ") }

        val writer = CostWriter("abc1234")
        val constant = CalibrationConstant.from("dispatch", result())
        writer.calibrate(constant)

        val duplicate = shouldThrow<IllegalArgumentException> { writer.calibrate(constant) }
        duplicate.message shouldContain "dispatch"
    }

    @Test
    fun `the envelope is derived only from admitted points`() {
        val writer = CostWriter("abc1234")
        listOf(1, 3, 8).forEach { hostCount ->
            listOf(1_000L, 100_000L).forEach { cardinality ->
                writer.admit(
                    PointOutcome.Ok(
                        at = coordinate(hostCount = hostCount, cardinality = cardinality),
                        subject = CostSubject.PROPAGATION,
                        counts = counts(messages = cardinality),
                        wall = null,
                    ),
                )
            }
        }
        writer.admit(
            PointOutcome.NonConvergent(
                at = coordinate(hostCount = 64, cardinality = 1_000),
                subject = CostSubject.PROPAGATION,
                budget = "20_000 steps",
            ),
        )

        val envelope = writer.model().envelope
        envelope.numeric.getValue(Dimensions.HOST_COUNT) shouldBe Envelope.Range(1.0, 8.0)
        envelope.numeric.getValue(Dimensions.CARDINALITY) shouldBe
            Envelope.Range(1_000.0, 100_000.0)
        envelope.numeric.getValue(Dimensions.BATCH_SIZE) shouldBe Envelope.Range(64.0, 64.0)
        envelope.numeric.getValue(Dimensions.FAN_OUT) shouldBe Envelope.Range(4.0, 4.0)
        envelope.categorical.getValue(Dimensions.SHAPE) shouldBe setOf("chain")
        envelope.categorical.getValue(Dimensions.REPLICATION) shouldBe setOf("false")
        envelope.categorical.getValue(Dimensions.DURABILITY) shouldBe setOf("false")

        shouldThrow<IllegalStateException> { CostWriter("abc1234").model() }
    }

    @Test
    fun `a linear law is retained only above the sample and goodness thresholds`() {
        val fanOuts = listOf(1, 4, 16, 64, 256)
        val points = fanOuts.map { fanOut -> coordinate(fanOut = fanOut) }
        val fitted = CostWriter("abc1234")
        points.zip(fanOuts).forEach { (at, fanOut) ->
            fitted.admit(ok(at, messages = 3L * fanOut + 7L))
        }

        val law = fitted.fit(
            dimension = Dimensions.FAN_OUT,
            subject = CostSubject.PROPAGATION,
            metric = "messages",
            form = GrowthForm.LINEAR,
            points = points,
        ).shouldBeInstanceOf<FitResult.Law>().law
        law.coefficients[0] shouldBe (3.0 plusOrMinus 1e-9)
        law.coefficients[1] shouldBe (7.0 plusOrMinus 1e-9)
        law.goodnessOfFit shouldBe (1.0 plusOrMinus 1e-9)
        law.fittedOver shouldBe points
        fitted.model().laws shouldBe listOf(law)

        val noisyValues = listOf(7L, 3L, 9L, 2L, 8L)
        val noisy = CostWriter("abc1234")
        points.zip(noisyValues).forEach { (at, messages) -> noisy.admit(ok(at, messages)) }
        val unfitted = noisy.fit(
            Dimensions.FAN_OUT,
            CostSubject.PROPAGATION,
            "messages",
            GrowthForm.LINEAR,
            points,
        ).shouldBeInstanceOf<FitResult.Unfitted>()
        val expectedR2 = linearRSquared(fanOuts.map(Int::toDouble), noisyValues.map(Long::toDouble))
        unfitted.goodnessOfFit!! shouldBe (expectedR2 plusOrMinus 1e-9)
        (unfitted.goodnessOfFit!! < CostWriter.MIN_GOODNESS_OF_FIT) shouldBe true
        noisy.model().laws.size shouldBe 0

        val threePoints = points.take(3)
        val tooFew = CostWriter("abc1234")
        threePoints.forEach { at -> tooFew.admit(ok(at, messages = 3L * at.fanOut + 7L)) }
        tooFew.fit(
            Dimensions.FAN_OUT,
            CostSubject.PROPAGATION,
            "messages",
            GrowthForm.LINEAR,
            threePoints,
        ).shouldBeInstanceOf<FitResult.Unfitted>()
        tooFew.model().laws.size shouldBe 0

        val constant = CostWriter("abc1234")
        points.forEach { at -> constant.admit(ok(at, messages = 5)) }
        val undefined = constant.fit(
            Dimensions.FAN_OUT,
            CostSubject.PROPAGATION,
            "messages",
            GrowthForm.LINEAR,
            points,
        ).shouldBeInstanceOf<FitResult.Unfitted>()
        undefined.goodnessOfFit shouldBe null
        constant.model().laws.size shouldBe 0
    }

    @Test
    fun `repeated coordinates cannot satisfy the fit sample threshold`() {
        val first = coordinate(fanOut = 1)
        val second = coordinate(fanOut = 4)
        val writer = CostWriter("abc1234")
        writer.admit(ok(first, messages = 10))
        writer.admit(ok(second, messages = 99))

        val duplicate = shouldThrow<IllegalArgumentException> {
            writer.fit(
                Dimensions.FAN_OUT,
                CostSubject.PROPAGATION,
                "messages",
                GrowthForm.LINEAR,
                listOf(first, first, second, second),
            )
        }

        duplicate.message shouldContain "repeated coordinate"
        duplicate.message shouldContain first.toString()
        writer.model().laws shouldBe emptyList()
    }

    @Test
    fun `the same successful fit cannot append a duplicate law`() {
        val points = listOf(1, 4, 16, 64).map { coordinate(fanOut = it) }
        val writer = CostWriter("abc1234")
        points.forEach { at -> writer.admit(ok(at, messages = 3L * at.fanOut + 7L)) }

        writer.fit(
            Dimensions.FAN_OUT,
            CostSubject.PROPAGATION,
            "messages",
            GrowthForm.LINEAR,
            points,
        ).shouldBeInstanceOf<FitResult.Law>()
        val duplicate = shouldThrow<IllegalArgumentException> {
            writer.fit(
                Dimensions.FAN_OUT,
                CostSubject.PROPAGATION,
                "messages",
                GrowthForm.LINEAR,
                points,
            )
        }

        duplicate.message shouldContain "duplicate growth law"
        writer.model().laws.size shouldBe 1
    }

    @Test
    fun `fit refuses unsupported coordinates and leaves unmeasured messages unfitted`() {
        val points = listOf(1, 4, 16, 64).map { coordinate(fanOut = it) }
        val writer = CostWriter("abc1234")
        points.forEach { at -> writer.admit(ok(at, messages = at.fanOut.toLong())) }

        shouldThrow<IllegalArgumentException> {
            writer.fit(
                Dimensions.SHAPE,
                CostSubject.PROPAGATION,
                "messages",
                GrowthForm.LINEAR,
                points,
            )
        }
        shouldThrow<IllegalArgumentException> {
            writer.fit(
                Dimensions.FAN_OUT,
                CostSubject.PROPAGATION,
                "messages",
                GrowthForm.LINEAR,
                points + coordinate(fanOut = 256),
            )
        }

        val unmeasured = CostWriter("abc1234")
        points.forEach { at ->
            unmeasured.admit(
                ok(at, messages = null, messagesNotMeasured = "bridge not instrumented"),
            )
        }
        unmeasured.fit(
            Dimensions.FAN_OUT,
            CostSubject.PROPAGATION,
            "messages",
            GrowthForm.LINEAR,
            points,
        ).shouldBeInstanceOf<FitResult.Unfitted>().goodnessOfFit shouldBe null
        unmeasured.model().laws.size shouldBe 0
    }

    @Test
    fun `write creates parent directories and returns the encoded model`() {
        val writer = CostWriter("abc1234")
        writer.admit(ok(coordinate(), messages = 10))
        val path = tempDir.resolve("nested/cost-model.json")

        val model = writer.write(path)

        Files.exists(path) shouldBe true
        CostSchema.parse(Files.readString(path)) shouldBe model
    }

    private fun ok(
        at: Coordinate,
        messages: Long?,
        messagesNotMeasured: String? = null,
    ): PointOutcome.Ok = PointOutcome.Ok(
        at = at,
        subject = CostSubject.PROPAGATION,
        counts = counts(messages, messagesNotMeasured),
        wall = null,
    )

    private fun coordinate(
        hostCount: Int = 1,
        cardinality: Long = 1_000,
        fanOut: Int = 4,
        drive: Drive = Drive.SIM,
    ): Coordinate = Coordinate(
        shape = "chain",
        shapeSeed = 42L,
        cardinality = cardinality,
        batchSize = 64,
        hostCount = hostCount,
        fanOut = fanOut,
        replication = false,
        durability = false,
        drive = drive,
    )

    private fun counts(
        messages: Long?,
        messagesNotMeasured: String? = null,
    ): StructuralCounts = StructuralCounts(
        messages = messages,
        messagesNotMeasured = messagesNotMeasured,
        crossHostFrames = 2,
        wireBytes = 80,
        journalRecords = 1,
        simSteps = 20,
    )

    private fun result(
        value: Double = 100.0,
        dispersion: Double = 1.0,
    ): BenchResult = BenchResult(
        value = value,
        unit = "ns",
        dispersion = dispersion,
        drive = Drive.REAL,
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

    private fun linearRSquared(x: List<Double>, y: List<Double>): Double {
        val xMean = x.average()
        val yMean = y.average()
        val slope = x.zip(y).sumOf { (xi, yi) -> (xi - xMean) * (yi - yMean) } /
            x.sumOf { xi -> (xi - xMean) * (xi - xMean) }
        val intercept = yMean - slope * xMean
        val residual = x.zip(y).sumOf { (xi, yi) ->
            val error = yi - (slope * xi + intercept)
            error * error
        }
        val total = y.sumOf { yi ->
            val centered = yi - yMean
            centered * centered
        }
        return 1.0 - residual / total
    }
}
