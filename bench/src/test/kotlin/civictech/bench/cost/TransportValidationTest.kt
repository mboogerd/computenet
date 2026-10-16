package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TransportValidationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `marks every multi-host subject and resets single-host points`() {
        val model = fixture()

        val marked = TransportValidation.mark(model, true)

        marked.structural.filter { it.at.hostCount >= 2 }
            .all { it.transportValidated == true } shouldBe true
        marked.structural.filter { it.at.hostCount == 1 }
            .all { it.transportValidated == null } shouldBe true
        marked shouldBe model.copy(structural = marked.structural)
    }

    @Test
    fun `marks disagreement and clear removes every marker`() {
        val model = fixture()

        val marked = TransportValidation.mark(model, false)

        marked.structural.filter { it.at.hostCount >= 2 }
            .all { it.transportValidated == false } shouldBe true
        marked.structural.filter { it.at.hostCount == 1 }
            .all { it.transportValidated == null } shouldBe true
        TransportValidation.clear(marked).structural
            .all { it.transportValidated == null } shouldBe true
    }

    @Test
    fun `path marking validates and persists only the marked entries`() {
        val file = tempDir.resolve("cost-model.json")
        Files.writeString(file, CostSchema.encode(fixture()))

        val returned = TransportValidation.mark(file, validated = false)
        val encoded = Files.readString(file)

        CostSchema.parse(encoded) shouldBe returned
        returned.structural.filter { it.at.hostCount >= 2 }
            .all { it.transportValidated == false } shouldBe true
        encoded.split("\"transportValidated\": false").size - 1 shouldBe
            returned.structural.count { it.at.hostCount >= 2 }
        encoded.contains("\"transportValidated\": true") shouldBe false
    }

    @Test
    fun `invalid existing export is rejected without rewriting it`() {
        val file = tempDir.resolve("invalid-cost-model.json")
        val invalid = CostSchema.encode(fixture()).replace(
            "\"schemaVersion\": ${CostSchema.VERSION}",
            "\"schemaVersion\": 1",
        )
        Files.writeString(file, invalid)
        val before = Files.readAllBytes(file)

        shouldThrow<UnknownSchemaVersionException> {
            TransportValidation.mark(file, validated = true)
        }

        Files.readAllBytes(file) shouldBe before
    }

    private fun fixture(): CostModel {
        val writer = CostWriter("0123456789abcdef0123456789abcdef01234567")
        writer.admit(ok(coordinate(hostCount = 3), CostSubject.PROPAGATION, 30))
        writer.admit(ok(coordinate(hostCount = 2), CostSubject.REBASELINE, 20))
        writer.admit(ok(coordinate(hostCount = 1), CostSubject.PROPAGATION, 10))
        writer.admit(
            PointOutcome.NonConvergent(
                at = coordinate(hostCount = 4),
                subject = CostSubject.JOURNAL,
                budget = "fixture budget",
            ),
        )
        writer.calibrate(
            CalibrationConstant.from(
                name = Calibration.LOCAL_DISPATCH_NANOS,
                result = BenchResult(
                    value = 10.0,
                    unit = "ns",
                    dispersion = 1.0,
                    drive = Drive.REAL,
                    env = environment(),
                ),
            ),
        )
        val model = writer.model()
        return CostSchema.parse(CostSchema.encode(model))
    }

    private fun ok(
        at: Coordinate,
        subject: CostSubject,
        messages: Long,
    ): PointOutcome.Ok = PointOutcome.Ok(
        at = at,
        subject = subject,
        counts = StructuralCounts(
            messages = messages,
            messagesNotMeasured = null,
            crossHostFrames = messages * 2,
            wireBytes = messages * 8,
            journalRecords = messages,
            simSteps = messages * 3,
        ),
        wall = null,
    )

    private fun coordinate(hostCount: Int): Coordinate = Coordinate(
        shape = "chain",
        shapeSeed = 42L,
        cardinality = hostCount.toLong() * 100,
        batchSize = 4,
        hostCount = hostCount,
        fanOut = 2,
        replication = false,
        durability = false,
        drive = Drive.SIM,
    )

    private fun environment(): RunEnvironment = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.4",
        heapSettings = "-Xms1g -Xmx4g",
        cpuModel = "fixture CPU",
        coreCount = 4,
        os = "fixture OS",
        jmhMode = "Throughput",
        forkCount = 1,
        warmupIterations = 1,
        measurementIterations = 1,
        harnessCommitSha = "0123456789abcdef0123456789abcdef01234567",
    )
}
