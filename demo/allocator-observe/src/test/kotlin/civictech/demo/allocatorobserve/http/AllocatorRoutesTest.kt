package civictech.demo.allocatorobserve.http

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.view.AllocatorReportViews
import civictech.demo.shell.DemoShell
import civictech.testkit.HttpProbe
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * [AllocatorRoutes] (task `computenet-fpml.4.1`, feature `computenet-fpml.4`):
 * `GET /state`, `/state/ingest`, `/state/report`, read-only enforcement, the
 * frozen-fold 503 envelope, and read-after-swap — over a real loopback
 * [DemoShell], matching `MirrorRoutesTest`'s prescribed shape.
 */
class AllocatorRoutesTest {

    private lateinit var holder: ServedStateHolder
    private lateinit var shell: DemoShell
    private lateinit var probe: HttpProbe

    @BeforeEach
    fun start() {
        holder = ServedStateHolder()
        shell = DemoShell(0)
        AllocatorRoutes(holder).register(shell)
        shell.start()
        probe = HttpProbe("http://localhost:${shell.boundPort}")
    }

    @AfterEach
    fun stop() {
        probe.close()
        shell.stop()
    }

    /** A minimal but real [ServedState], distinguished by [now] and [recordCount]. */
    private fun servedState(now: Instant, recordCount: Int): ServedState {
        val records = SetCell<SpendRecord>()
        val declarations = SetCell<DeclarationEvent>()
        val views = AllocatorReportViews.derivedFrom(records, declarations, Duration.ofHours(1), now = { now })
        val report = views.publish()
        return ServedState(
            report = report,
            ingest = IngestHealth(
                recordCount = recordCount,
                checkpointOffset = null,
                reBaselineCount = 0L,
                polls = 1L,
                lastPollAt = now,
                failures = IngestFailureCounts(0L, 0L, 0L),
                declarationEvents = 0,
            ),
            records = emptySet(),
            declarations = emptyList(),
        )
    }

    // -----------------------------------------------------------------
    // holder empty
    // -----------------------------------------------------------------

    @Test
    fun `an empty holder answers 503 not yet polled on every state route`() {
        for (path in listOf("/state", "/state/ingest", "/state/report")) {
            val response = probe.get(path)
            response.statusCode() shouldBe 503
            Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonPrimitive.content shouldBe
                "not yet polled"
        }
    }

    // -----------------------------------------------------------------
    // (a) each route serves the corresponding encoder output
    // -----------------------------------------------------------------

    @Test
    fun `state, ingest and report routes each serve the matching encoder output`() {
        val state = servedState(Instant.parse("2026-09-18T00:00:00Z"), recordCount = 3)
        holder.swap(state)

        val stateResponse = probe.get("/state")
        stateResponse.statusCode() shouldBe 200
        stateResponse.body() shouldBe state.toJson()

        val ingestResponse = probe.get("/state/ingest")
        ingestResponse.statusCode() shouldBe 200
        ingestResponse.body() shouldBe state.ingestJson()

        val reportResponse = probe.get("/state/report")
        reportResponse.statusCode() shouldBe 200
        reportResponse.body() shouldBe state.reportJson()
    }

    // -----------------------------------------------------------------
    // (b) non-GET -> 405, state unchanged
    // -----------------------------------------------------------------

    @Test
    fun `a non-GET method answers 405 and leaves the served state unchanged`() {
        val state = servedState(Instant.parse("2026-09-18T00:00:00Z"), recordCount = 3)
        holder.swap(state)

        val posted = probe.post("hello", "/state")
        posted shouldBe 405

        val after = probe.get("/state")
        after.statusCode() shouldBe 200
        after.body() shouldBe state.toJson()
    }

    // -----------------------------------------------------------------
    // (c) unknown sub-path -> 404
    // -----------------------------------------------------------------

    @Test
    fun `an unknown state sub-path answers 404`() {
        holder.swap(servedState(Instant.parse("2026-09-18T00:00:00Z"), recordCount = 1))

        val response = probe.get("/state/nope")
        response.statusCode() shouldBe 404
        Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonPrimitive.content shouldBe
            "no such route"
    }

    // -----------------------------------------------------------------
    // (e) read-after-swap
    // -----------------------------------------------------------------

    @Test
    fun `after the holder is swapped a request serves the new state, never the old one`() {
        val s1 = servedState(Instant.parse("2026-09-18T00:00:00Z"), recordCount = 1)
        holder.swap(s1)
        probe.get("/state").body() shouldBe s1.toJson()

        val s2 = servedState(Instant.parse("2026-09-18T00:01:00Z"), recordCount = 2)
        holder.swap(s2)

        val after = probe.get("/state")
        after.body() shouldBe s2.toJson()
        after.body() shouldNotBe s1.toJson()
    }

    // -----------------------------------------------------------------
    // (f) frozen fold
    // -----------------------------------------------------------------

    @Test
    fun `a stopped poll loop answers 503 frozen with the failure and the last good state under stale`() {
        val s2 = servedState(Instant.parse("2026-09-18T00:01:00Z"), recordCount = 2)
        holder.swap(s2)
        holder.stop(PollLoopStopped(IllegalStateException("boom"), s2.ingest.lastPollAt))

        for ((path, encode) in listOf<Pair<String, (ServedState) -> String>>(
            "/state" to ServedState::toJson,
            "/state/ingest" to ServedState::ingestJson,
            "/state/report" to ServedState::reportJson,
        )) {
            val response = probe.get(path)
            response.statusCode() shouldBe 503
            val body = Json.parseToJsonElement(response.body()).jsonObject
            body.getValue("ingest").jsonPrimitive.content shouldBe "frozen"
            body.getValue("failure").jsonPrimitive.content shouldBe "java.lang.IllegalStateException: boom"
            body.getValue("stale_status").jsonPrimitive.content shouldBe "200"
            body.getValue("stale") shouldBe Json.parseToJsonElement(encode(s2))
        }
    }
}
