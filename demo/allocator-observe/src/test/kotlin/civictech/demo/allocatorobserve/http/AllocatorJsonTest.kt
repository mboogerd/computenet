package civictech.demo.allocatorobserve.http

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.view.AllocatorReportViews
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * [AllocatorJson]'s DTOs and mapping functions (task `computenet-fpml.4.1`,
 * feature `computenet-fpml.4`). The fixture is the feature's own "example 1"
 * (`AllocatorReportViewsTest`, `computenet-fpml.3.3`): two projects fed
 * through the real cells and `AllocatorReportViews.publish()`, so the report
 * half of the document is real end-to-end output, not a hand-built value.
 */
class AllocatorJsonTest {

    private companion object {
        const val CN = "computenet"
        const val GF = "glass-factory"

        val NOW: Instant = Instant.parse("2026-08-15T12:00:00Z")
        val WINDOW: Duration = Duration.ofHours(24)
        val EARLY: Instant = Instant.parse("2026-08-01T00:00:00Z")
        const val TOLERANCE = 1e-9
    }

    private fun declaration(computenet: Double, glassFactory: Double, capHours: Double = 100.0) =
        AllocationDeclaration(weights = mapOf(CN to computenet, GF to glassFactory), monthlyCapHours = capHours, window = null)

    private fun record(project: String, started: String, ended: String, workItem: String) =
        SpendRecord(v = 1, project = project, machine = "m1", workItem = workItem, started = started, ended = ended)

    /** Builds a real [ServedState] the way task `computenet-fpml.4.2`'s poll driver will. */
    private fun servedState(): ServedState {
        val records = SetCell<SpendRecord>()
        val declarations = SetCell<DeclarationEvent>()
        val views = AllocatorReportViews.derivedFrom(records, declarations, WINDOW, now = { NOW })

        val declEvent = DeclarationEvent(EARLY, declaration(60.0, 40.0))
        declarations.inlet.call.add(declEvent)
        // Added out of sorted order so the JSON array's order is provably the
        // encoder's doing, not an accident of insertion order.
        records.inlet.call.add(record(GF, "2026-08-14T20:00:00Z", "2026-08-15T00:00:00Z", "w2"))
        records.inlet.call.add(record(CN, "2026-08-14T13:00:00Z", "2026-08-14T19:00:00Z", "w1"))

        val report = views.publish()

        val ingest = IngestHealth(
            recordCount = 2,
            checkpointOffset = 128L,
            reBaselineCount = 0L,
            polls = 1L,
            lastPollAt = NOW,
            failures = IngestFailureCounts(malformed = 1L, unknownVersion = 2L, declarationParseFailed = 3L),
            declarationEvents = 1,
        )

        return ServedState(
            report = report,
            ingest = ingest,
            records = records.membership(),
            declarations = listOf(declEvent),
        )
    }

    @Test
    fun `toJson carries the documented ingest and report sections`() {
        val state = servedState()
        val json = Json.parseToJsonElement(state.toJson()).jsonObject

        val ingest = json.getValue("ingest").jsonObject
        ingest.getValue("recordCount").jsonPrimitive.int shouldBe 2
        ingest.getValue("checkpointOffset").jsonPrimitive.long shouldBe 128L
        ingest.getValue("reBaselineCount").jsonPrimitive.long shouldBe 0L
        ingest.getValue("polls").jsonPrimitive.long shouldBe 1L
        ingest.getValue("lastPollAt").jsonPrimitive.content shouldBe NOW.toString()
        ingest.getValue("declarationEvents").jsonPrimitive.int shouldBe 1

        val failures = ingest.getValue("failures").jsonObject
        failures.getValue("malformed").jsonPrimitive.long shouldBe 1L
        failures.getValue("unknownVersion").jsonPrimitive.long shouldBe 2L
        failures.getValue("declarationParseFailed").jsonPrimitive.long shouldBe 3L

        val recordsArray = ingest.getValue("records").jsonArray
        recordsArray.size shouldBe 2
        // Sort order (project, started, ended, machine, workItem): "computenet" < "glass-factory".
        recordsArray[0].jsonObject.getValue("project").jsonPrimitive.content shouldBe CN
        recordsArray[0].jsonObject.getValue("workItem").jsonPrimitive.content shouldBe "w1"
        recordsArray[1].jsonObject.getValue("project").jsonPrimitive.content shouldBe GF
        recordsArray[1].jsonObject.getValue("workItem").jsonPrimitive.content shouldBe "w2"

        val report = json.getValue("report").jsonObject
        report.getValue("publishedAt").jsonPrimitive.content shouldBe NOW.toString()

        val window = report.getValue("window").jsonObject
        val perProject = window.getValue("perProject").jsonObject
        // Sorted key order.
        perProject.keys.toList() shouldBe listOf(CN, GF)

        val cn = perProject.getValue(CN).jsonObject
        cn.getValue("enactedShare").jsonPrimitive.double shouldBe (0.6 plusOrMinus TOLERANCE)
        cn.getValue("declaredShare").jsonPrimitive.double shouldBe (0.6 plusOrMinus TOLERANCE)

        val gf = perProject.getValue(GF).jsonObject
        gf.getValue("enactedShare").jsonPrimitive.double shouldBe (0.4 plusOrMinus TOLERANCE)

        val cap = report.getValue("cap").jsonObject
        cap.getValue("projectionRule").jsonPrimitive.content shouldBe "linear"
        cap.getValue("capHours").jsonPrimitive.double shouldBe (100.0 plusOrMinus TOLERANCE)

        // No unattributable records in this fixture.
        report.getValue("unattributable").jsonObject.keys.isEmpty() shouldBe true
        report.getValue("unattributableRecords").jsonArray.size shouldBe 0
    }

    @Test
    fun `ingestJson and reportJson are exactly the ingest and report members of toJson`() {
        val state = servedState()
        val whole = Json.parseToJsonElement(state.toJson()).jsonObject

        Json.parseToJsonElement(state.ingestJson()) shouldBe whole.getValue("ingest")
        Json.parseToJsonElement(state.reportJson()) shouldBe whole.getValue("report")
    }
}
