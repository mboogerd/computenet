package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.demo.shell.DemoShell
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpResponse
import java.util.UUID

/**
 * B13 — an **enabled** write plane: what it advertises (`[WKB2-51]`), the
 * order in which [WriteGate] refuses (`[WKB2-44]`, `[WKB2-43]`), that a refusal
 * never reads the body, that an admitted request does (`[WKB2-49]` gate half),
 * and that enabling it changes neither the loopback bind (`[WKB2-08]`) nor the
 * absence of an `OPTIONS` handler.
 *
 * **How "the body was not read" is observed.** Every refused request below is
 * sent with the body `not json{`. Had the route parsed it, the answer would be
 * `400 malformed body`; the gate's refusals carry different reasons (and, for
 * the capability, a different status), so asserting the reason string is the
 * proof.
 */
class WriteGateTest {

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
    fun `an enabled plane advertises its verbs and identity`() {
        started()

        val response = send("GET", InspectorServer.CAPABILITIES_PATH)

        response.statusCode() shouldBe 200
        response.body() shouldBe
            """{"writePlane":true,"verbs":["spawn","connect","despawn"],"identity":"capability-holder"}"""
    }

    @Test
    fun `a missing write header is a 400 and the body is never read`() {
        started()

        val response = send("POST", PRECHECK, body = MALFORMED)

        response.statusCode() shouldBe 400
        reasonOf(response) shouldBe "missing required header: X-Inspector-Write"
    }

    @Test
    fun `a wrong capability is a 403 and the body is never read`() {
        started()

        val response = send("POST", PRECHECK, body = MALFORMED, writeHeader = "not-the-capability")

        response.statusCode() shouldBe 403
        reasonOf(response) shouldBe "capability rejected"
    }

    /** A strict prefix of the capability is still a different value — no prefix match. */
    @Test
    fun `a prefix of the capability is rejected too`() {
        started()

        val response = send("POST", PRECHECK, body = "{}", writeHeader = CAPABILITY.dropLast(1))

        response.statusCode() shouldBe 403
        reasonOf(response) shouldBe "capability rejected"
    }

    @Test
    fun `an admitted request with a malformed body reaches the body`() {
        started()

        val response = send("POST", PRECHECK, body = MALFORMED, writeHeader = CAPABILITY)

        response.statusCode() shouldBe 400
        reasonOf(response) shouldBe "malformed body"
    }

    @Test
    fun `an admitted well-formed request reaches the placeholder`() {
        started()

        val response = send("POST", PRECHECK, body = "{}", writeHeader = CAPABILITY)

        response.statusCode() shouldBe 501
        reasonOf(response) shouldBe "precheck not implemented"
    }

    @Test
    fun `the gate sets the identity label on the exchange it admits`() {
        val exchange = RecordingExchange(mapOf(WriteGate.WRITE_HEADER to CAPABILITY))

        val admission = WriteGate(WritePlane.Enabled(Capability(CAPABILITY), identityLabel = "ops"))
            .admit(exchange)

        admission shouldBe WriteGate.Admission.Admitted("ops")
        exchange.getAttribute(WriteGate.IDENTITY_ATTRIBUTE) shouldBe "ops"
        exchange.bodyRead shouldBe false
    }

    @Test
    fun `a refusal sets no identity and reads no body`() {
        val exchange = RecordingExchange(mapOf(WriteGate.WRITE_HEADER to "wrong"))

        WriteGate(WritePlane.Enabled(Capability(CAPABILITY))).admit(exchange) shouldBe
            WriteGate.Admission.Refused(403, "capability rejected")
        exchange.getAttribute(WriteGate.IDENTITY_ATTRIBUTE) shouldBe null
        exchange.bodyRead shouldBe false
    }

    /**
     * No `OPTIONS` handler: a cross-origin browser `POST` carrying the
     * non-simple write header must preflight, and that preflight has to fail
     * (`[WKB2-44]`, see [WriteGate]'s KDoc).
     */
    @Test
    fun `an OPTIONS preflight on the write route is not answered with success`() {
        started()

        val response = send(
            "OPTIONS", PRECHECK,
            headers = mapOf(
                "Origin" to "http://evil.example",
                "Access-Control-Request-Method" to "POST",
                "Access-Control-Request-Headers" to WriteGate.WRITE_HEADER,
            ),
        )

        (response.statusCode() in 200..299) shouldBe false
        response.headers().firstValue("Access-Control-Allow-Headers").isPresent shouldBe false
    }

    /**
     * `[WKB2-08]` — enabling edits does not widen the bind. Asserted as the
     * *decision*, through the recording [InspectorServer.Shells] seam exactly as
     * `InspectorBindTest`'s named-port test does, for the same reason: NOTHING
     * here binds [NAMED_PORT]; the stand-in shell is ephemeral.
     */
    @Test
    fun `an enabled write plane still asks for a loopback bind`() {
        val asked = mutableListOf<Pair<Int, InetAddress?>>()
        val recording = object : InspectorServer.Shells {
            override fun open(port: Int, bindAddress: InetAddress?): DemoShell {
                asked += port to bindAddress
                return DemoShell(0)
            }
        }
        InspectorServer(
            registry,
            hosts = mapOf("h" to host),
            port = NAMED_PORT,
            writePlane = WritePlane.Enabled(Capability(CAPABILITY)),
            shells = recording,
        ).startUnscheduled().use { }

        val (port, bindAddress) = asked.single()
        port shouldBe NAMED_PORT
        bindAddress.shouldNotBeNull()
        bindAddress.isLoopbackAddress shouldBe true
        bindAddress.isAnyLocalAddress shouldBe false
    }

    @Test
    fun `a minted capability is 43 base64url characters and never printed by toString`() {
        val minted = Capability.mint()

        minted.value.length shouldBe 43
        minted.value.all { it.isLetterOrDigit() || it == '-' || it == '_' } shouldBe true
        minted.toString() shouldNotContain minted.value
        Capability.mint().value shouldNotBe minted.value
    }

    // -------------------------------------------------------------- fixtures

    private fun started(): InspectorServer =
        InspectorServer(
            registry, mapOf("h" to host), port = 0,
            writePlane = WritePlane.Enabled(Capability(CAPABILITY)),
        ).startUnscheduled().also { server = it }

    private fun send(
        method: String,
        path: String,
        body: String = "",
        writeHeader: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> = sendTo(server!!.boundPort, method, path, body, writeHeader, headers)

    private companion object {
        const val CAPABILITY = "test-capability"
        const val MALFORMED = "not json{"
        const val PRECHECK = "${InspectorServer.APPLY_PATH}/precheck"

        /** A plausible `--inspect-port`, never bound here — see the loopback test. */
        const val NAMED_PORT = 9000
    }
}

/**
 * An [HttpExchange] that carries [headers] and records whether anything
 * opened its request body — the direct form of "the gate reads no body",
 * beside the over-the-wire reason-string form the route tests use. Everything
 * the gate has no business calling throws.
 */
private class RecordingExchange(headers: Map<String, String>) : HttpExchange() {
    private val requestHeaders = Headers().apply { headers.forEach { (name, value) -> add(name, value) } }
    private val attributes = mutableMapOf<String, Any?>()
    var bodyRead = false
        private set

    override fun getRequestHeaders(): Headers = requestHeaders
    override fun getAttribute(name: String): Any? = attributes[name]
    override fun setAttribute(name: String, value: Any?) {
        attributes[name] = value
    }
    override fun getRequestBody(): InputStream {
        bodyRead = true
        return InputStream.nullInputStream()
    }

    override fun getResponseHeaders(): Headers = Headers()
    override fun getRequestURI(): URI = URI(InspectorServer.APPLY_PATH + "/precheck")
    override fun getRequestMethod(): String = "POST"
    override fun getHttpContext(): HttpContext = unexpected()
    override fun close() = Unit
    override fun getResponseBody(): OutputStream = ByteArrayOutputStream()
    override fun sendResponseHeaders(rCode: Int, responseLength: Long) = unexpected()
    override fun getRemoteAddress(): InetSocketAddress = unexpected()
    override fun getResponseCode(): Int = -1
    override fun getLocalAddress(): InetSocketAddress = unexpected()
    override fun getProtocol(): String = "HTTP/1.1"
    override fun setStreams(i: InputStream?, o: OutputStream?) = unexpected()
    override fun getPrincipal(): HttpPrincipal? = null

    private fun unexpected(): Nothing = throw UnsupportedOperationException("the gate must not call this")
}
