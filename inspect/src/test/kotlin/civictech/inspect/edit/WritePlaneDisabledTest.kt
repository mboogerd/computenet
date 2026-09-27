package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.inspect.InspectorServer
import civictech.testkit.bounded
import civictech.testkit.boundedHttpClient
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * B12 — an inspector constructed without a `writePlane` argument accepts no
 * edits and says so (`[WKB2-06]`), and the wake route it always had is left
 * exactly as it was (`[WKB2-50]`).
 *
 * Every server here is built through the **public** constructor with no
 * `writePlane` argument, so what is pinned is the default, not a value a test
 * chose.
 */
class WritePlaneDisabledTest {

    private val registry = LocationRegistry()

    /** Owned so [tearDown] can stop it (computenet-4vh; see `InspectorErrorsTest`). */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private var server: InspectorServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
        hostScheduler.shutdown()
    }

    @Test
    fun `the default server advertises no write plane`() {
        started()

        val response = send("GET", InspectorServer.CAPABILITIES_PATH)

        response.statusCode() shouldBe 200
        response.body() shouldBe """{"writePlane":false}"""
    }

    @Test
    fun `a precheck on the default server is a 404 even with a write header`() {
        started()

        val response = send("POST", PRECHECK, body = "{}", writeHeader = "anything")

        response.statusCode() shouldBe 404
        reasonOf(response) shouldBe "write plane disabled"
    }

    /**
     * Disabled is checked before the header: a server that was never opted in
     * answers as though the route did not exist, not "you forgot a header" —
     * which would tell a caller the plane is there to be unlocked.
     */
    @Test
    fun `a precheck on the default server is a 404 without a write header too`() {
        started()

        val response = send("POST", PRECHECK, body = "{}")

        response.statusCode() shouldBe 404
        reasonOf(response) shouldBe "write plane disabled"
    }

    /** `[WKB2-50]` — the wake route keeps its own header and does not adopt [WriteGate]. */
    @Test
    fun `the wake route still answers 202 with its header`() {
        pair(A, B)
        started()

        val response = send(
            "POST", "${InspectorServer.GRAPH_PATH}/g-$A/wake",
            headers = mapOf(InspectorServer.WAKE_HEADER to InspectorServer.WAKE_HEADER_VALUE),
        )

        response.statusCode() shouldBe 202
    }

    @Test
    fun `the wake route still answers 400 without its header`() {
        pair(A, B)
        started()

        send("POST", "${InspectorServer.GRAPH_PATH}/g-$A/wake").statusCode() shouldBe 400
    }

    // -------------------------------------------------------------- fixtures

    private fun started(): InspectorServer =
        InspectorServer(registry, mapOf("h" to host), port = 0).startUnscheduled().also { server = it }

    /** Two linked cells, as `InspectorColdTest`'s `pair` builds them: one hot component `g-$first`. */
    private fun pair(first: String, second: String) {
        val a = CellRef(UUID.fromString(first))
        val b = CellRef(UUID.fromString(second))
        host.managementInlet.call.spawn(SetCell<Any>(ref = a))
        host.managementInlet.call.spawn(SetCell<Any>(ref = b))
        host.managementInlet.call.connect(a, "outlet", b, "deltaInlet") as LinkResult.Connected
    }

    private fun send(
        method: String,
        path: String,
        body: String = "",
        writeHeader: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> = sendTo(server!!.boundPort, method, path, body, writeHeader, headers)

    private companion object {
        const val PRECHECK = "${InspectorServer.APPLY_PATH}/precheck"
        const val A = "0a000000-0000-4000-8000-000000000000"
        const val B = "0b000000-0000-4000-8000-000000000000"
    }
}

/**
 * One request through a plain [java.net.http.HttpClient] — `HttpProbe` has no
 * header-carrying POST (see `InspectorColdTest`'s `sendWake`, whose shape this
 * copies), and the write plane lives on a header.
 */
internal fun sendTo(
    port: Int,
    method: String,
    path: String,
    body: String = "",
    writeHeader: String? = null,
    headers: Map<String, String> = emptyMap(),
): HttpResponse<String> {
    val publisher = if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
    val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, publisher)
    writeHeader?.let { builder.header(WriteGate.WRITE_HEADER, it) }
    headers.forEach { (name, value) -> builder.header(name, value) }
    val client = boundedHttpClient()
    try {
        return client.send(builder.bounded().build(), HttpResponse.BodyHandlers.ofString())
    } finally {
        client.shutdownNow()
    }
}

/** The `reason` of an inspector problem body. */
internal fun reasonOf(response: HttpResponse<String>): String =
    Json.parseToJsonElement(response.body()).jsonObject["reason"]!!.jsonPrimitive.content
