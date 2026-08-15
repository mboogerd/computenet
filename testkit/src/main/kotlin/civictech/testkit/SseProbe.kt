package civictech.testkit

import org.opentest4j.AssertionFailedError
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A bounded read over a `text/event-stream` subscription (computenet-o7c3).
 *
 * The bound `java.net.http` can offer does not reach this far. Measured on the
 * toolchain these suites run on:
 *
 *  - **Temurin 21.0.11** (`jvmToolchain(21)`): a `send(request.timeout(3s),
 *    BodyHandlers.ofInputStream())` against a listener that writes SSE headers and
 *    then nothing returned 200 in 26ms, and the *body* read was still parked
 *    15000ms later — the JDK releases the response timer once `send` returns, so
 *    `.timeout(...)` bounds the handshake and nothing after it.
 *  - **OpenJDK 26.0.1**: the same read threw `IOException: closed` at 2977ms — the
 *    timer stays armed and kills the exchange mid-stream, which is exactly what a
 *    long-lived subscription must not have happen to it.
 *
 * Neither behaviour is one a test can build a deadline on, which is why this
 * helper carries its own: the whole subscription — handshake *and* body read —
 * runs on a daemon thread the caller joins with [timeoutMs], and a thread still
 * alive at the deadline is reported as a failure naming the endpoint rather than
 * left to park until JUnit's 300s timeout (the signature of computenet-dqy.71,
 * whose console stack named nothing useful).
 *
 * Consistent with [HttpBounds.CONNECT]'s rule, the request itself is **not**
 * [bounded]: a subscription is long-lived by design, so a request timeout could
 * only abort a healthy stream. `connectTimeout` bounds the handshake; this
 * function's own deadline bounds everything after it.
 *
 * Returns the first `data:` line satisfying [predicate]. Throws an
 * [AssertionFailedError] naming [url] if the deadline expires, if the stream ends
 * first, or if the subscription is answered with a non-200 status.
 */
fun awaitSseData(
    url: String,
    timeoutMs: Long = 5_000,
    predicate: (String) -> Boolean = { true },
): String {
    val client = boundedHttpClient()
    val seen = CopyOnWriteArrayList<String>()
    val result = AtomicReference<String>()
    val failure = AtomicReference<Throwable>()
    val stream = AtomicReference<InputStream>()
    val worker = Thread {
        try {
            val response = client.send(
                HttpRequest.newBuilder(URI(url)).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            stream.set(response.body())
            if (response.statusCode() != 200) {
                throw AssertionFailedError("SSE subscription to $url answered ${response.statusCode()}, not 200")
            }
            response.body().bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine()
                        ?: throw AssertionFailedError(
                            "SSE stream $url ended after ${seen.size} data frames without a matching one" +
                                lastSeen(seen),
                        )
                    if (!line.startsWith("data:")) continue
                    seen += line
                    if (predicate(line)) {
                        result.set(line)
                        return@Thread
                    }
                }
            }
        } catch (t: Throwable) {
            failure.set(t)
        }
    }
    worker.isDaemon = true
    worker.name = "sse-read"
    try {
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            // unblock the parked reader before reporting, so the thread does not
            // outlive the test holding a socket; then fail attributably
            runCatching { stream.get()?.close() }
            client.shutdownNow()
            val frames = worker.stackTrace.take(4).joinToString("\n\t") { it.toString() }
            throw AssertionFailedError(
                "SSE read from $url did not produce a matching data frame within ${timeoutMs}ms. " +
                    "The read was bounded, so this is an unresponsive stream — not a suite timeout." +
                    lastSeen(seen) + "\nParked at:\n\t" + frames,
            )
        }
        failure.get()?.let { throw it }
        return result.get()
            ?: throw AssertionFailedError("SSE read from $url returned neither a data frame nor a failure")
    } finally {
        client.shutdownNow()
    }
}

private fun lastSeen(seen: List<String>): String =
    if (seen.isEmpty()) "; no data frame was ever delivered" else "; last-seen frame: ${seen.last()}"
