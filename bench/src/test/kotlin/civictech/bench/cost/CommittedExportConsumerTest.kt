package civictech.bench.cost

import civictech.bench.Drive
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Consumer checks against the versioned export PLC1 will actually read ([BEN2-35], BS-19). */
class CommittedExportConsumerTest {
    private val model: CostModel by lazy {
        val root = requireNotNull(System.getProperty(REPO_ROOT_PROPERTY)) {
            "system property $REPO_ROOT_PROPERTY must name the export checkout"
        }
        CostSchema.parse(Files.readString(Path.of(root, CostSchema.STABLE_PATH)))
    }

    @Test
    fun `every admitted SIM propagation point carries a measured local delivery count`() {
        val propagation = model.structural.filter {
            it.subject == CostSubject.PROPAGATION && it.at.drive == Drive.SIM
        }

        propagation.isNotEmpty() shouldBe true
        propagation.forEach { entry ->
            checkNotNull(entry.counts.messages) {
                "PROPAGATION point has no local delivery count: ${entry.at}"
            }
            entry.counts.messagesNotMeasured shouldBe null
        }
    }

    @Test
    fun `committed baseline prices measured edges and refuses a cross-host price at h1`() {
        val query = CostQuery(model)

        assertPriced(query.coLocatedEdge(BASELINE), CostSubject.PROPAGATION, BASELINE)
        assertPriced(query.crossHostEdge(BASELINE), CostSubject.PROPAGATION, BASELINE)
        assertPriced(query.coLocatedEdge(HOST_ONE), CostSubject.PROPAGATION, HOST_ONE)

        val hostOneCrossHost = query.crossHostEdge(HOST_ONE)
            .shouldBeInstanceOf<CostAnswer.NotMeasured>()
        hostOneCrossHost.reason shouldContain "hostCount=1"
    }

    private fun assertPriced(answer: CostAnswer, subject: CostSubject, at: Coordinate) {
        val priced = answer.shouldBeInstanceOf<CostAnswer.Priced>()
        priced.dispersion.shouldBeGreaterThanOrEqual(0.0)
        priced.from.structural.subject shouldBe subject
        priced.from.structural.at shouldBe at
        priced.from.structural.harnessSha.isNotBlank() shouldBe true
        priced.from.calibration.isNotEmpty() shouldBe true
    }

    private companion object {
        const val REPO_ROOT_PROPERTY = "computenet.repo.root"

        val BASELINE = Coordinate(
            shape = "DIAMOND",
            shapeSeed = 1,
            cardinality = 10_000,
            batchSize = 10,
            hostCount = 3,
            fanOut = 4,
            replication = false,
            durability = false,
            drive = Drive.SIM,
        )
        val HOST_ONE = BASELINE.copy(hostCount = 1)
    }
}
