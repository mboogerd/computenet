package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * PLC1-shaped consumer checks over synthetic cost observations (BS-19, [BEN2-45]).
 *
 * Every number in this fixture is synthetic. Each model is derived by [CostWriter],
 * encoded to a file below [tempDir], and parsed through [CostSchema] before querying.
 */
class CostConsumerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `prices the four consumer costs with dispersion and provenance`() {
        val query = CostQuery(syntheticModel())
        val at = coordinate()

        query.coLocatedEdge(at) shouldBe CostAnswer.Priced(
            value = 50_000.0,
            unit = "ns",
            dispersion = 5_000.0,
            from = provenance(
                subject = CostSubject.PROPAGATION,
                at = at,
                calibration = listOf(localDispatchRef()),
                dominant = Calibration.LOCAL_DISPATCH_NANOS,
            ),
        )
        query.crossHostEdge(at) shouldBe CostAnswer.Priced(
            value = 94_000.0,
            unit = "ns",
            dispersion = 7_400.0,
            from = provenance(
                subject = CostSubject.PROPAGATION,
                at = at,
                calibration = listOf(localDispatchRef(), crossHostFrameRef(), wireByteRef()),
                dominant = Calibration.LOCAL_DISPATCH_NANOS,
            ),
        )
        query.cellResidency(at) shouldBe CostAnswer.Priced(
            value = 64_000.0,
            unit = "bytes",
            dispersion = 2_000.0,
            from = provenance(
                subject = CostSubject.PROPAGATION,
                at = at,
                calibration = listOf(retainedBytesRef()),
                dominant = Calibration.RETAINED_BYTES_PER_ELEMENT,
            ),
        )
        query.reBaseline(at) shouldBe CostAnswer.Priced(
            value = 47_000.0,
            unit = "ns",
            dispersion = 3_700.0,
            from = provenance(
                subject = CostSubject.REBASELINE,
                at = at,
                calibration = listOf(localDispatchRef(), crossHostFrameRef(), wireByteRef()),
                dominant = Calibration.LOCAL_DISPATCH_NANOS,
            ),
        )

        query.crossHostEdge(at.copy(drive = Drive.REAL)) shouldBe query.crossHostEdge(at)
    }

    @Test
    fun `refuses every dimension beyond the derived envelope`() {
        val query = CostQuery(syntheticModel())
        val at = coordinate()

        query.crossHostEdge(at.copy(hostCount = 16)) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.HOST_COUNT,
            "16",
            Envelope.Range(1.0, 8.0),
        )
        query.cellResidency(at.copy(cardinality = 10)) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.CARDINALITY,
            "10",
            Envelope.Range(1_000.0, 100_000.0),
        )
        query.coLocatedEdge(at.copy(batchSize = 2)) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.BATCH_SIZE,
            "2",
            Envelope.Range(1.0, 1.0),
        )
        query.coLocatedEdge(at.copy(fanOut = 256)) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.FAN_OUT,
            "256",
            Envelope.Range(4.0, 4.0),
        )
        query.coLocatedEdge(at.copy(shape = "torus")) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.SHAPE,
            "torus",
            Measured.Values(setOf("chain")),
        )
        query.coLocatedEdge(at.copy(replication = true)) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.REPLICATION,
            "true",
            Measured.Values(setOf("false")),
        )
        query.coLocatedEdge(at.copy(durability = true)) shouldBe CostAnswer.OutOfEnvelope(
            Dimensions.DURABILITY,
            "true",
            Measured.Values(setOf("false")),
        )

        query.crossHostEdge(at.copy(cardinality = 10, hostCount = 16)) shouldBe
            CostAnswer.OutOfEnvelope(
                Dimensions.CARDINALITY,
                "10",
                Envelope.Range(1_000.0, 100_000.0),
            )
    }

    @Test
    fun `refuses missing structural points constants and message counts`() {
        val query = CostQuery(syntheticModel())

        val unsweptCardinality = query.cellResidency(coordinate(cardinality = 10_000))
            .shouldBeInstanceOf<CostAnswer.NotMeasured>()
        unsweptCardinality.reason shouldContain "10000"

        val absentReBaseline = query.reBaseline(coordinate(hostCount = 8))
            .shouldBeInstanceOf<CostAnswer.NotMeasured>()
        absentReBaseline.reason shouldContain "REBASELINE"

        CostQuery(syntheticModel(includeRetainedBytes = false, fileName = "no-retained.json"))
            .cellResidency(coordinate()) shouldBe CostAnswer.NotMeasured(
            "no calibration constant '${Calibration.RETAINED_BYTES_PER_ELEMENT}'",
        )

        CostQuery(syntheticModel(fileName = "unmeasured-messages.json"))
            .coLocatedEdge(coordinate(hostCount = 1)) shouldBe CostAnswer.NotMeasured(
            MESSAGES_NOT_MEASURED,
        )
    }

    private fun syntheticModel(
        includeRetainedBytes: Boolean = true,
        fileName: String = "synthetic-cost-model.json",
    ): CostModel {
        val writer = CostWriter(STRUCTURAL_HARNESS_SHA)
        listOf(1, 3, 8).forEach { hostCount ->
            listOf(1_000L, 100_000L).forEach { cardinality ->
                val counts = when {
                    hostCount == 3 && cardinality == 1_000L -> structuralCounts(
                        messages = 1_000,
                        crossHostFrames = 20,
                        wireBytes = 4_000,
                    )
                    hostCount == 1 && cardinality == 1_000L -> structuralCounts(
                        messages = null,
                        messagesNotMeasured = MESSAGES_NOT_MEASURED,
                    )
                    else -> structuralCounts(messages = cardinality)
                }
                writer.admit(
                    PointOutcome.Ok(
                        at = coordinate(hostCount = hostCount, cardinality = cardinality),
                        subject = CostSubject.PROPAGATION,
                        counts = counts,
                        wall = null,
                    ),
                )
            }
        }
        writer.admit(
            PointOutcome.Ok(
                at = coordinate(),
                subject = CostSubject.REBASELINE,
                counts = structuralCounts(
                    messages = 500,
                    crossHostFrames = 10,
                    wireBytes = 2_000,
                ),
                wall = null,
            ),
        )
        writer.calibrate(calibration(Calibration.LOCAL_DISPATCH_NANOS, 50.0, "ns", 5.0))
        writer.calibrate(calibration(Calibration.CROSS_HOST_FRAME_NANOS, 2_000.0, "ns", 100.0))
        writer.calibrate(calibration(Calibration.WIRE_BYTE_NANOS, 1.0, "ns", 0.1))
        if (includeRetainedBytes) {
            writer.calibrate(
                calibration(Calibration.RETAINED_BYTES_PER_ELEMENT, 64.0, "bytes", 2.0),
            )
        }

        val file = tempDir.resolve(fileName)
        Files.writeString(file, CostSchema.encode(writer.model()))
        return CostSchema.parse(Files.readString(file))
    }

    private fun coordinate(
        hostCount: Int = 3,
        cardinality: Long = 1_000,
        drive: Drive = Drive.SIM,
    ): Coordinate = Coordinate(
        shape = "chain",
        shapeSeed = 42L,
        cardinality = cardinality,
        batchSize = 1,
        hostCount = hostCount,
        fanOut = 4,
        replication = false,
        durability = false,
        drive = drive,
    )

    private fun structuralCounts(
        messages: Long?,
        messagesNotMeasured: String? = null,
        crossHostFrames: Long = 0,
        wireBytes: Long = 0,
    ): StructuralCounts = StructuralCounts(
        messages = messages,
        messagesNotMeasured = messagesNotMeasured,
        crossHostFrames = crossHostFrames,
        wireBytes = wireBytes,
        journalRecords = 0,
        simSteps = 1,
    )

    private fun calibration(
        name: String,
        value: Double,
        unit: String,
        dispersion: Double,
    ): CalibrationConstant = CalibrationConstant.from(
        name,
        BenchResult(
            value = value,
            unit = unit,
            dispersion = dispersion,
            drive = Drive.REAL,
            env = runEnvironment(),
        ),
    )

    private fun provenance(
        subject: CostSubject,
        at: Coordinate,
        calibration: List<CalibrationRef>,
        dominant: String,
    ): Provenance = Provenance(
        structural = StructuralRef(subject, at.copy(drive = Drive.SIM), STRUCTURAL_HARNESS_SHA),
        calibration = calibration,
        dominant = dominant,
    )

    private fun localDispatchRef(): CalibrationRef = calibrationRef(
        Calibration.LOCAL_DISPATCH_NANOS,
        50.0,
        "ns",
        5.0,
    )

    private fun crossHostFrameRef(): CalibrationRef = calibrationRef(
        Calibration.CROSS_HOST_FRAME_NANOS,
        2_000.0,
        "ns",
        100.0,
    )

    private fun wireByteRef(): CalibrationRef = calibrationRef(
        Calibration.WIRE_BYTE_NANOS,
        1.0,
        "ns",
        0.1,
    )

    private fun retainedBytesRef(): CalibrationRef = calibrationRef(
        Calibration.RETAINED_BYTES_PER_ELEMENT,
        64.0,
        "bytes",
        2.0,
    )

    private fun calibrationRef(
        name: String,
        value: Double,
        unit: String,
        dispersion: Double,
    ): CalibrationRef = CalibrationRef(
        name = name,
        value = value,
        unit = unit,
        dispersion = dispersion,
        harnessCommitSha = CALIBRATION_HARNESS_SHA,
    )

    private fun runEnvironment(): RunEnvironment = RunEnvironment(
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
        harnessCommitSha = CALIBRATION_HARNESS_SHA,
    )

    private companion object {
        const val STRUCTURAL_HARNESS_SHA = "structural-harness-sha"
        const val CALIBRATION_HARNESS_SHA = "calibration-harness-sha"
        const val MESSAGES_NOT_MEASURED = "harness cannot count local dispatches"
    }
}
