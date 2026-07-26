package civictech.testkit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A thin client for the demos' identical HTTP shell (`POST /op`, `GET /state`),
 * bound to one [baseUrl]. Canonical form taken from `TieringServerTest` /
 * `SkillMatchServerTest` (byte-identical `post`/`state`/`await` bodies); see
 * [SimWorld]'s sibling helpers for the non-HTTP polling form ([awaitUntil]).
 */
class HttpProbe(private val baseUrl: String) {
    private val client: HttpClient = HttpClient.newHttpClient()

    /** POST a form-urlencoded [body] to `$baseUrl$path`; returns the HTTP status code. */
    fun post(body: String, path: String = "/op"): Int =
        client.send(
            HttpRequest.newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode()

    /** GET `$baseUrl$path` and return the response body. */
    fun state(path: String = "/state"): String =
        client.send(
            HttpRequest.newBuilder(URI("$baseUrl$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    /**
     * Poll [path] every 50ms until [predicate] matches the body, or [timeoutMs] elapses
     * (returning the last-seen body either way — callers assert on it themselves).
     */
    fun await(timeoutMs: Long = 5_000, path: String = "/state", predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var json = ""
        while (System.currentTimeMillis() < deadline) {
            json = state(path)
            if (predicate(json)) return json
            Thread.sleep(50)
        }
        return json
    }
}
