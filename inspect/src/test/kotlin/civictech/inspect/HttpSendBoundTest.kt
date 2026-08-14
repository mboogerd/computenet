package civictech.inspect

import civictech.testkit.HttpProbe
import civictech.testkit.bounded
import civictech.testkit.boundedHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.fail

/**
 * computenet-dqy.72: no HTTP request issued from test or testkit code may park a
 * thread indefinitely in `HttpClient.send`.
 *
 * Lives in `:inspect` because this is the module whose 5-minute hangs produced the
 * evidence (computenet-dqy.71's artifact names `HttpClientImpl.send` under
 * `InspectorEventsTest.kt:112`), and because `:inspect` is where the fix has to
 * hold; the property it pins belongs to `:testkit`'s [HttpProbe] and to the
 * `java.net.http` call-site shape every one of these suites uses.
 */
class HttpSendBoundTest {
    @Test
    fun `a probe pointed at an endpoint that never responds fails inside its await bound`() {
        silentEndpoint { base ->
            val probe = HttpProbe(base)
            val failure = assertFailsWithin(BUDGET_MS, "HttpProbe.await against a silent endpoint") {
                probe.await(timeoutMs = 2_000) { true }
            }
            val message = failure.message ?: ""
            if (!message.contains(base)) {
                fail("failure must name the endpoint it gave up on; got: $message")
            }
        }
    }

    @Test
    fun `a bounded request to an endpoint that never responds fails in seconds`() {
        silentEndpoint { base ->
            val client = boundedHttpClient()
            try {
                val failure = assertFailsWithin(BUDGET_MS, "a bounded send against a silent endpoint") {
                    client.send(
                        HttpRequest.newBuilder(URI("$base/state")).bounded(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }
                if (failure !is HttpTimeoutException) {
                    fail("expected the JDK's own bound to fire; got ${failure::class.simpleName}: ${failure.message}")
                }
            } finally {
                client.shutdownNow()
            }
        }
    }

    /**
     * Run [body] on a daemon thread and require it to *terminate* — normally or
     * exceptionally — within [budgetMs]. A thread still alive at the deadline is
     * the defect itself: an unbounded park in `HttpClient.send`, which under JUnit
     * surfaces only as the suite's 5-minute timeout with the blocked frame in a
     * suppressed section Gradle's console output drops.
     *
     * Returns the throwable [body] failed with, so a caller can assert on the
     * message.
     */
    private fun assertFailsWithin(budgetMs: Long, what: String, body: () -> Unit): Throwable {
        var thrown: Throwable? = null
        val thread = Thread {
            try {
                body()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        thread.isDaemon = true
        thread.name = "bound-probe"
        val started = System.currentTimeMillis()
        thread.start()
        thread.join(budgetMs)
        if (thread.isAlive) {
            val frames = thread.stackTrace.take(4).joinToString("\n\t") { it.toString() }
            fail("$what did not return within ${budgetMs}ms — the request is unbounded. Parked at:\n\t$frames")
        }
        val elapsed = System.currentTimeMillis() - started
        return thrown ?: fail("$what returned successfully in ${elapsed}ms; the endpoint never responds, so it cannot have")
    }

    /**
     * Stand up a listener that completes the TCP handshake and then never writes a
     * byte: the connection succeeds, the request is accepted, and no response ever
     * arrives. This is the shape a connect-level timeout alone cannot catch.
     */
    private fun silentEndpoint(body: (String) -> Unit) {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val accepted = CopyOnWriteArrayList<Socket>()
        val acceptor = Thread {
            try {
                while (!server.isClosed) accepted += server.accept()
            } catch (_: IOException) {
                // server closed by the teardown below
            }
        }
        acceptor.isDaemon = true
        acceptor.name = "silent-endpoint"
        acceptor.start()
        try {
            body("http://${server.inetAddress.hostAddress}:${server.localPort}")
        } finally {
            server.close()
            accepted.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        /**
         * The wall clock a bounded request gets to give up in. Deliberately far
         * above the bounds under test (a 2s await, a 20s default request timeout)
         * and far below JUnit's 5-minute timeout, so a failure here is attributable
         * to the request rather than to the suite.
         */
        const val BUDGET_MS = 40_000L
    }
}
