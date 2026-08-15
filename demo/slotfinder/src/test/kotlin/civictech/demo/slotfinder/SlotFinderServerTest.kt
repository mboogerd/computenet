package civictech.demo.slotfinder

import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SlotFinderServerTest {

    @Test
    fun `a slot shared by all three flows through intersect, filter and count`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            // Tue-14 for everyone (business hours), Tue-19 for everyone (after hours)
            for (user in PARTICIPANTS) {
                for (hour in listOf(14, 19)) {
                    assertEquals(200, probe.post("action=add&user=$user&day=Tue&hour=$hour"))
                }
            }

            // ops flow writer → intersect chain → hubs asynchronously; poll /state
            var json = probe.await { "\"byDay\":{\"Tue\":1}" in it }
            assertTrue("\"common\":[\"Tue-14\",\"Tue-19\"]" in json, "common slots missing in $json")
            assertTrue("\"filtered\":[\"Tue-14\"]" in json, "business-hours filter wrong in $json")
            assertTrue("\"byDay\":{\"Tue\":1}" in json, "per-day count wrong in $json")

            // retraction: bob drops Tue-14 → it leaves every derived view
            assertEquals(200, probe.post("action=remove&user=bob&day=Tue&hour=14"))
            json = probe.await {
                "\"filtered\":[]" in it && "Tue-14" !in it.substringAfter("\"common\"")
            }
            assertTrue(
                "\"filtered\":[]" in json && "Tue-14" !in json.substringAfter("\"common\""),
                "retraction never reached the derived views: $json",
            )

            // input validation at the boundary
            assertEquals(400, probe.post("action=add&user=mallory&day=Tue&hour=14"))
            assertEquals(400, probe.post("action=add&user=alice&day=Sun&hour=14"))
            assertEquals(400, probe.post("action=add&user=alice&day=Tue&hour=23"))
        } finally {
            app.stop()
        }
    }

    @Test
    fun `SSE delivers state and a fresh subscriber catches up`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            HttpProbe(base).post("action=add&user=alice&day=Mon&hour=10")

            // the read itself is bounded, not just the request (computenet-o7c3)
            val line = awaitSseData("$base/events", timeoutMs = 5_000) { "Mon-10" in it }
            assertTrue("Mon-10" in line, "SSE stream never delivered alice's slot: $line")
        } finally {
            app.stop()
        }
    }

    /**
     * computenet-o7c3: an SSE subscription whose endpoint completes the handshake,
     * sends `text/event-stream` headers and then never writes another byte must
     * fail inside the read's own deadline, not park the test thread until JUnit's
     * 300s timeout.
     *
     * Measured on the toolchain these suites run on (Temurin 21): the JDK releases
     * the response timer once `send` returns, so a `.timeout(...)` on the request
     * bounds the handshake and nothing after it — the body read is unbounded. The
     * old call-site shape (`while (clock < deadline) { reader.readLine() }`) checks
     * its deadline only *between* lines, so a single `readLine` that never returns
     * never reaches the check.
     *
     * The thread must TERMINATE within [SILENT_BUDGET_MS], far below JUnit's 300s,
     * so a failure here is attributable to the read rather than to the suite.
     */
    @Test
    fun `an SSE read against a silent-after-headers endpoint fails inside its own deadline`() {
        silentSseEndpoint { base ->
            val url = "$base/events"
            val failure = assertFailsWithin(SILENT_BUDGET_MS, "an SSE read against a silent endpoint") {
                awaitSseData(url, timeoutMs = 2_000) { "Mon-10" in it }
            }
            val message = failure.message ?: ""
            assertTrue(url in message, "failure must name the endpoint it gave up on; got: $message")
        }
    }

    /**
     * Run [body] on a daemon thread and require it to *terminate* — normally or
     * exceptionally — within [budgetMs]; returns what it threw. Same shape as
     * `:inspect`'s `HttpSendBoundTest.assertFailsWithin` (computenet-dqy.72): a
     * thread still alive at the deadline IS the defect, and under JUnit it would
     * otherwise surface only as the suite's 5-minute timeout with the blocked frame
     * in a suppressed section Gradle's console output drops.
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
        thread.name = "sse-bound-probe"
        val started = System.currentTimeMillis()
        thread.start()
        thread.join(budgetMs)
        if (thread.isAlive) {
            val frames = thread.stackTrace.take(4).joinToString("\n\t") { it.toString() }
            fail("$what did not return within ${budgetMs}ms — the body read is unbounded. Parked at:\n\t$frames")
        }
        val elapsed = System.currentTimeMillis() - started
        return thrown
            ?: fail("$what returned successfully in ${elapsed}ms; the endpoint never sends an event, so it cannot have")
    }

    /**
     * A listener that accepts, reads the request line, answers with a complete
     * `text/event-stream` response head — and then never writes another byte. This
     * is the shape neither a connect timeout nor (on 21) a request timeout catches:
     * both are satisfied long before the first event fails to arrive.
     */
    private fun silentSseEndpoint(body: (String) -> Unit) {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val accepted = CopyOnWriteArrayList<Socket>()
        val acceptor = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    accepted += socket
                    socket.getInputStream().bufferedReader().readLine() // request line; the rest is ignored
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n"
                            ).toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    // ... and then silence, for as long as the client is willing to wait
                }
            } catch (_: IOException) {
                // server closed by the teardown below
            }
        }
        acceptor.isDaemon = true
        acceptor.name = "silent-sse-endpoint"
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
         * Wall clock the bounded read gets to give up in: 10x the 2s deadline under
         * test and 30x under JUnit's 300s, so exceeding it means unbounded, never a
         * loaded machine.
         */
        const val SILENT_BUDGET_MS = 20_000L
    }
}
