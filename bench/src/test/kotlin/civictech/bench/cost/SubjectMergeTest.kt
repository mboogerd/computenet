package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SubjectMergeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `replaces only the named subject and re-derives the envelope`() {
        val existing = existingModel()
        val replacement = replacementModel()

        val merged = SubjectMerge.replaceSubject(existing, CostSubject.REBASELINE, replacement)

        merged.structural.filter { it.subject == CostSubject.PROPAGATION } shouldBe
            existing.structural.filter { it.subject == CostSubject.PROPAGATION }
        merged.structural.filter { it.subject == CostSubject.REBASELINE } shouldBe
            replacement.structural
        merged.structural.none { it.at.shape == "old" } shouldBe true
        merged.calibration shouldBe existing.calibration
        merged.exclusions shouldBe
            existing.exclusions.filter { it.subject == CostSubject.PROPAGATION } + replacement.exclusions
        merged.laws shouldBe
            existing.laws.filter { it.subject == CostSubject.PROPAGATION } + replacement.laws
        merged.envelope.categorical.getValue(Dimensions.SHAPE) shouldBe setOf(
            "chain",
            "diamond",
            "REBASELINE_LOCAL_TAPS",
        )
        merged.envelope.numeric.getValue(Dimensions.FAN_OUT) shouldBe Envelope.Range(1.0, 256.0)
        merged.harnessSha shouldBe existing.harnessSha
        merged.structural.filter { it.subject == CostSubject.REBASELINE }
            .map { it.harnessSha }.toSet() shouldBe setOf(REPLACEMENT_SHA)

        CostSchema.parse(CostSchema.encode(merged)) shouldBe merged
    }

    @Test
    fun `the file round trip validates`() {
        val file = tempDir.resolve("cost-model.json")
        val existing = existingModel()
        val replacement = replacementModel()
        Files.writeString(file, CostSchema.encode(existing))

        val returned = SubjectMerge.replaceSubject(file, CostSubject.REBASELINE, replacement)

        CostSchema.parse(Files.readString(file)) shouldBe returned
    }

    @Test
    fun `refuses a replacement carrying another subject and leaves the file unchanged`() {
        val file = tempDir.resolve("cost-model.json")
        val existing = existingModel()
        val invalid = modelWithOneEntry(CostSubject.PROPAGATION, "wrong-subject")
        Files.writeString(file, CostSchema.encode(existing))
        val before = Files.readAllBytes(file)

        val failure = shouldThrow<IllegalArgumentException> {
            SubjectMerge.replaceSubject(file, CostSubject.REBASELINE, invalid)
        }

        failure.message shouldContain "PROPAGATION"
        Files.readAllBytes(file) shouldBe before
    }

    @Test
    fun `refuses a replacement exclusion carrying another subject`() {
        val replacement = replacementModel()
        val invalid = replacement.copy(
            exclusions = replacement.exclusions + replacement.exclusions.first()
                .copy(subject = CostSubject.PROPAGATION),
        )

        val failure = shouldThrow<IllegalArgumentException> {
            SubjectMerge.replaceSubject(existingModel(), CostSubject.REBASELINE, invalid)
        }

        failure.message shouldContain "PROPAGATION"
    }

    @Test
    fun `refuses a replacement law carrying another subject`() {
        val replacement = replacementModel()
        val invalid = replacement.copy(
            laws = replacement.laws + replacement.laws.first().copy(subject = CostSubject.PROPAGATION),
        )

        val failure = shouldThrow<IllegalArgumentException> {
            SubjectMerge.replaceSubject(existingModel(), CostSubject.REBASELINE, invalid)
        }

        failure.message shouldContain "PROPAGATION"
    }

    @Test
    fun `applying twice is idempotent`() {
        val existing = existingModel()
        val replacement = replacementModel()

        val once = SubjectMerge.replaceSubject(existing, CostSubject.REBASELINE, replacement)
        val twice = SubjectMerge.replaceSubject(once, CostSubject.REBASELINE, replacement)

        twice shouldBe once
    }

    @Test
    fun `allows subject-qualified coordinates across kept exclusions`() {
        val shared = coordinate(shape = "shared", fanOut = 4)
        val existingWriter = CostWriter(EXISTING_SHA)
        existingWriter.admit(
            PointOutcome.Failed(
                at = shared,
                subject = CostSubject.PROPAGATION,
                cause = "synthetic exclusion",
                deadLetters = listOf("one"),
            ),
        )
        existingWriter.admit(
            PointOutcome.Ok(
                at = coordinate(shape = "chain"),
                subject = CostSubject.PROPAGATION,
                counts = counts(messages = 1),
                wall = null,
            ),
        )
        val replacementWriter = CostWriter(REPLACEMENT_SHA)
        replacementWriter.admit(
            PointOutcome.Ok(
                at = shared,
                subject = CostSubject.REBASELINE,
                counts = counts(messages = 4),
                wall = null,
            ),
        )

        val merged = SubjectMerge.replaceSubject(
            existingWriter.model(),
            CostSubject.REBASELINE,
            replacementWriter.model(),
        )

        CostSchema.parse(CostSchema.encode(merged)) shouldBe merged
        merged.exclusions.single().subject shouldBe CostSubject.PROPAGATION
        merged.structural.single { it.subject == CostSubject.REBASELINE }.at shouldBe shared
    }

    private fun existingModel(): CostModel {
        val writer = CostWriter(EXISTING_SHA)
        val propagation = listOf(
            coordinate(shape = "chain", hostCount = 1, fanOut = 1),
            coordinate(shape = "chain", hostCount = 2, fanOut = 4),
            coordinate(shape = "diamond", hostCount = 3, fanOut = 4),
            coordinate(shape = "diamond", hostCount = 4, fanOut = 4),
        )
        propagation.forEachIndexed { index, at ->
            writer.admit(
                PointOutcome.Ok(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    counts = counts(messages = index.toLong() + 1),
                    wall = null,
                ),
            )
        }
        val old = listOf(1, 2, 3, 4).map { fanOut -> coordinate(shape = "old", fanOut = fanOut) }
        old.forEach { at ->
            writer.admit(
                PointOutcome.Ok(
                    at = at,
                    subject = CostSubject.REBASELINE,
                    counts = counts(messages = at.fanOut.toLong()),
                    wall = null,
                ),
            )
        }
        writer.admit(
            PointOutcome.Failed(
                at = coordinate(shape = "chain", hostCount = 5),
                subject = CostSubject.PROPAGATION,
                cause = "synthetic kept exclusion",
                deadLetters = listOf("one"),
            ),
        )
        writer.admit(
            PointOutcome.Failed(
                at = coordinate(shape = "old", fanOut = 8),
                subject = CostSubject.REBASELINE,
                cause = "synthetic replaced exclusion",
                deadLetters = listOf("one"),
            ),
        )
        writer.fit(
            dimension = Dimensions.HOST_COUNT,
            subject = CostSubject.PROPAGATION,
            metric = "messages",
            form = GrowthForm.LINEAR,
            points = propagation,
        )
        writer.fit(
            dimension = Dimensions.FAN_OUT,
            subject = CostSubject.REBASELINE,
            metric = "messages",
            form = GrowthForm.LINEAR,
            points = old,
        )
        check(writer.model().laws.size == 2) { "existing fixture must carry one law per subject" }
        writer.calibrate(calibration("first", 10.0))
        writer.calibrate(calibration("second", 20.0))
        return writer.model()
    }

    private fun replacementModel(): CostModel {
        val writer = CostWriter(REPLACEMENT_SHA)
        val points = listOf(1, 4, 16, 256).map { fanOut ->
            coordinate(shape = "REBASELINE_LOCAL_TAPS", fanOut = fanOut)
        }
        points.forEach { at ->
            writer.admit(
                PointOutcome.Ok(
                    at = at,
                    subject = CostSubject.REBASELINE,
                    counts = counts(messages = at.fanOut.toLong()),
                    wall = null,
                ),
            )
        }
        writer.admit(
            PointOutcome.Failed(
                at = coordinate(shape = "REBASELINE_LOCAL_TAPS", fanOut = 64),
                subject = CostSubject.REBASELINE,
                cause = "synthetic exclusion",
                deadLetters = listOf("one"),
            ),
        )
        writer.fit(
            dimension = Dimensions.FAN_OUT,
            subject = CostSubject.REBASELINE,
            metric = "messages",
            form = GrowthForm.LINEAR,
            points = points,
        )
        writer.calibrate(calibration("replacement-only", 99.0))
        return writer.model()
    }

    private fun modelWithOneEntry(subject: CostSubject, shape: String): CostModel {
        val writer = CostWriter("invalid-sha")
        writer.admit(
            PointOutcome.Ok(
                at = coordinate(shape = shape),
                subject = subject,
                counts = counts(messages = 1),
                wall = null,
            ),
        )
        return writer.model()
    }

    private fun coordinate(
        shape: String,
        hostCount: Int = 1,
        fanOut: Int = 1,
    ): Coordinate = Coordinate(
        shape = shape,
        shapeSeed = 0L,
        cardinality = 1_000,
        batchSize = 1,
        hostCount = hostCount,
        fanOut = fanOut,
        replication = false,
        durability = false,
        drive = Drive.SIM,
    )

    private fun counts(messages: Long): StructuralCounts = StructuralCounts(
        messages = messages,
        messagesNotMeasured = null,
        crossHostFrames = 0,
        wireBytes = 0,
        journalRecords = 0,
        simSteps = 1,
    )

    private fun calibration(name: String, value: Double): CalibrationConstant =
        CalibrationConstant.from(
            name = name,
            result = BenchResult(
                value = value,
                unit = "ns",
                dispersion = 1.0,
                drive = Drive.REAL,
                env = environment(),
            ),
        )

    private fun environment(): RunEnvironment = RunEnvironment(
        jvmVendor = "test-vendor",
        jvmVersion = "test-version",
        heapSettings = "test-heap",
        cpuModel = "test-cpu",
        coreCount = 1,
        os = "test-os",
        jmhMode = "test-mode",
        forkCount = 1,
        warmupIterations = 1,
        measurementIterations = 1,
        harnessCommitSha = "calibration-harness",
    )

    private companion object {
        const val EXISTING_SHA = "existing-harness-sha"
        const val REPLACEMENT_SHA = "replacement-harness-sha"
    }
}
