package civictech.testkit

import org.opentest4j.AssertionFailedError
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * The bounds every `java.net.http` request issued from test or testkit code
 * carries (computenet-dqy.72). Neither is a tuning knob: they exist so that a
 * response which never arrives becomes a fast, attributable failure instead of a
 * thread parked in `HttpClient.send` until JUnit's 5-minute timeout — which is
 * how computenet-dqy.71 presented, and why its console stack named nothing but
 * `ArrayList.forEach` (Gradle drops the suppressed `InterruptedException` that
 * carries the blocked frame).
 */
object HttpBounds {
    /**
     * Bound on a whole synchronous exchange. The JDK arms the response timer
     * before connection setup, so this covers connect *and* response for a
     * `send` with a non-streaming body handler.
     *
     * 20s is chosen for attributability, not to make any run pass: healthy
     * responses in these suites are loopback and sub-millisecond, and whole
     * modules (`:inspect`, ~265 tests) finish in seconds, so 20s is orders of
     * magnitude above any plausible GC or scheduling stall on a loaded CI box —
     * while sitting 15x under JUnit's 300s, so exceeding it can only mean the
     * endpoint, never the suite.
     */
    val REQUEST: Duration = Duration.ofSeconds(20)

    /**
     * Bound on connection establishment only. Set on *every* client, including
     * the SSE readers, because a `text/event-stream` subscription is meant to
     * outlive any request timeout: a `.timeout(...)` there would abort a healthy
     * stream, so `connectTimeout` is the only bound the streaming path can
     * honestly carry *at the request level*. A loopback handshake against a bound
     * listener takes microseconds; 10s is pure headroom.
     *
     * computenet-o7c3 made this rule true of the code as well as of this comment.
     * Until then four SSE call sites (agora, shopping x2, slotfinder) called
     * [bounded] anyway, and a request timeout is not merely wrong there — it is
     * also useless: measured on Temurin 21.0.11, the JDK releases the response
     * timer once `send` returns, so the bound covered the handshake and left the
     * body read unbounded (still parked 15000ms after a 3s timeout), while on
     * OpenJDK 26.0.1 the timer stayed armed and killed the stream mid-read with a
     * bare `IOException: closed`. What a streaming read needs is a bound of its
     * own, which is [awaitSseData]: it runs the whole subscription on a thread the
     * caller joins with a deadline. No SSE call site uses [bounded] now.
     */
    val CONNECT: Duration = Duration.ofSeconds(10)
}

/** An [HttpClient] whose connection phase is bounded by [HttpBounds.CONNECT]. */
fun boundedHttpClient(): HttpClient = HttpClient.newBuilder().connectTimeout(HttpBounds.CONNECT).build()

/**
 * Bound this request by [timeout], defaulting to [HttpBounds.REQUEST]. Use on
 * every synchronous `send` with a non-streaming body handler; do **not** use on
 * an SSE subscription, which is long-lived by design and whose *body* read this
 * bound does not reach anyway — use [awaitSseData] there (see
 * [HttpBounds.CONNECT] for both halves of that, and computenet-o7c3 for the
 * per-JDK measurement).
 */
fun HttpRequest.Builder.bounded(timeout: Duration = HttpBounds.REQUEST): HttpRequest.Builder = timeout(timeout)

/**
 * A thin client for the demos' identical HTTP shell (`POST /op`, `GET /state`,
 * plus the `GET`/`DELETE`/JSON-`POST` shapes backlog-triage's `/features` and
 * agora's `/graph` need), bound to one [baseUrl]. Canonical form taken from
 * `TieringServerTest` / `SkillMatchServerTest` (byte-identical `post`/`state`/
 * `await` bodies) plus the T12 migration of `AgoraServerTest`/`TriageServerTest`/
 * shopping's `DemoServerTest` off their hand-rolled copies; see [SimWorld]'s
 * sibling helpers for the non-HTTP polling form ([awaitUntil]).
 *
 * [AutoCloseable] since computenet-4vh: the JDK `HttpClient` a probe owns is a
 * real resource (its own selector thread plus an executor pool), and JUnit's
 * PER_METHOD lifecycle builds one probe per test *method*, so a suite that never
 * closes them holds every client of a `setForkEvery(80)` fork at once, released
 * only if and when the collector clears their weak referents. Closing is
 * therefore worth doing; not closing is not a correctness bug for any single
 * test, which is why every existing caller still compiles unchanged.
 *
 * **Every request this probe issues is bounded** (computenet-dqy.72). Before that
 * fix the class only *looked* bounded: [await]'s `timeoutMs` governed the retry
 * loop while the single `client.send` underneath it was untimed, so a send that
 * never returned never got back to the loop and the documented 5s deadline never
 * fired. What is guaranteed now:
 *
 *  - a one-shot call ([post], [get], [state], [delete], [postForm], [postJson])
 *    fails within [HttpBounds.REQUEST] with a message naming method and URI;
 *  - [await] returns or throws within its own `timeoutMs`, because each send is
 *    bounded by the time *remaining* on the await deadline rather than by the
 *    default — the advertised bound is the real one.
 *
 * A timeout surfaces as an [AssertionFailedError] naming the endpoint, not as a
 * bare `HttpTimeoutException` ("request timed out"), so the failure is
 * attributable from the console line alone.
 */
class HttpProbe(private val baseUrl: String) : AutoCloseable {
    private val client: HttpClient = boundedHttpClient()

    /** POST a form-urlencoded [body] to `$baseUrl$path`; returns the HTTP status code. */
    fun post(body: String, path: String = "/op"): Int = postForm(body, path).statusCode()

    /** POST a form-urlencoded [body] to `$baseUrl$path`; returns the full response. */
    fun postForm(body: String, path: String = "/op"): HttpResponse<String> =
        send(
            HttpRequest.newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

    /** POST a JSON [body] to `$baseUrl$path`; returns the full response. */
    fun postJson(body: String, path: String = "/op"): HttpResponse<String> =
        send(
            HttpRequest.newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

    /** GET `$baseUrl$path`; returns the full response. */
    fun get(path: String = "/state"): HttpResponse<String> = send(HttpRequest.newBuilder(URI("$baseUrl$path")))

    /** GET `$baseUrl$path` and return the response body (the demos' `/state` read model). */
    fun state(path: String = "/state"): String = get(path).body()

    /** DELETE `$baseUrl$path`; returns the full response. */
    fun delete(path: String): HttpResponse<String> =
        send(HttpRequest.newBuilder(URI("$baseUrl$path")).DELETE())

    /**
     * Issue [builder] bounded by [timeout], translating the JDK's
     * [HttpTimeoutException] — whose message is the endpoint-free "request timed
     * out" — into a failure that names what did not answer.
     */
    private fun send(
        builder: HttpRequest.Builder,
        timeout: Duration = HttpBounds.REQUEST,
    ): HttpResponse<String> {
        val request = builder.bounded(timeout).build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (timedOut: HttpTimeoutException) {
            throw AssertionFailedError(
                "HttpProbe: no response to ${request.method()} ${request.uri()} within ${timeout.toMillis()}ms. " +
                    "The request was bounded, so this is an unresponsive endpoint — not a suite timeout.",
                timedOut,
            )
        }
    }

    /** [send] without the message translation, for [await], which has its own. */
    private fun sendRaw(builder: HttpRequest.Builder, timeout: Duration): HttpResponse<String> =
        client.send(builder.bounded(timeout).build(), HttpResponse.BodyHandlers.ofString())

    /**
     * Poll [path] every 50ms until [predicate] matches the body. T12 finding 2:
     * previously returned the last-seen body on a [timeoutMs] timeout instead of
     * failing — a silent pass for any caller that forgot a subsequent assert.
     * Now throws, with the last-seen body in the message, so a stalled demo
     * fails loudly at the await site instead of downstream (or not at all).
     *
     * computenet-dqy.72: each poll is bounded by the time *remaining* on the
     * deadline, so [timeoutMs] bounds the whole call. Previously it bounded only
     * this loop, and a send that never returned never reached the loop again.
     */
    fun await(timeoutMs: Long = 5_000, path: String = "/state", predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var json = ""
        var unanswered = false
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            try {
                json = sendRaw(HttpRequest.newBuilder(URI("$baseUrl$path")), Duration.ofMillis(remaining)).body()
            } catch (_: HttpTimeoutException) {
                // the poll ran out the deadline itself; the loop's message below is the right one
                unanswered = true
                break
            }
            if (predicate(json)) return json
            Thread.sleep(50)
        }
        val why = if (unanswered) {
            "; the last request was still unanswered when the deadline expired (it was bounded by the remaining time, " +
                "so this is the await bound firing, not a suite timeout)"
        } else {
            ""
        }
        throw AssertionFailedError(
            "HttpProbe.await timed out after ${timeoutMs}ms on $baseUrl$path$why; last-seen body: $json",
        )
    }

    /**
     * Release the client this probe owns. [HttpClient.shutdownNow] and not
     * [HttpClient.close]: `close()` waits for in-flight exchanges to terminate,
     * and a teardown helper must never be the thing that introduces an unbounded
     * wait. Every request this class issues is synchronous and has already
     * completed by the time a caller can reach `close()`, so there is nothing
     * legitimate left to wait for anyway.
     */
    override fun close() {
        client.shutdownNow()
    }
}
