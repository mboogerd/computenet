package civictech.testkit

import org.opentest4j.AssertionFailedError
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A thin client for the demos' identical HTTP shell (`POST /op`, `GET /state`,
 * plus the `GET`/`DELETE`/JSON-`POST` shapes backlog-triage's `/features` and
 * agora's `/graph` need), bound to one [baseUrl]. Canonical form taken from
 * `TieringServerTest` / `SkillMatchServerTest` (byte-identical `post`/`state`/
 * `await` bodies) plus the T12 migration of `AgoraServerTest`/`TriageServerTest`/
 * shopping's `DemoServerTest` off their hand-rolled copies; see [SimWorld]'s
 * sibling helpers for the non-HTTP polling form ([awaitUntil]).
 */
class HttpProbe(private val baseUrl: String) {
    private val client: HttpClient = HttpClient.newHttpClient()

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

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

    /**
     * Poll [path] every 50ms until [predicate] matches the body. T12 finding 2:
     * previously returned the last-seen body on a [timeoutMs] timeout instead of
     * failing — a silent pass for any caller that forgot a subsequent assert.
     * Now throws, with the last-seen body in the message, so a stalled demo
     * fails loudly at the await site instead of downstream (or not at all).
     */
    fun await(timeoutMs: Long = 5_000, path: String = "/state", predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var json = ""
        while (System.currentTimeMillis() < deadline) {
            json = state(path)
            if (predicate(json)) return json
            Thread.sleep(50)
        }
        throw AssertionFailedError("HttpProbe.await timed out after ${timeoutMs}ms on $path; last-seen body: $json")
    }
}
