package civictech.demo.skillmatch

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMatchServerTest {

    private fun post(client: HttpClient, base: String, body: String): Int =
        client.send(
            HttpRequest.newBuilder(URI("$base/op"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode()

    private fun state(client: HttpClient, base: String): String =
        client.send(
            HttpRequest.newBuilder(URI("$base/state")).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    private fun await(client: HttpClient, base: String, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 5_000
        var json = ""
        while (System.currentTimeMillis() < deadline) {
            json = state(client, base)
            if (predicate(json)) return json
            Thread.sleep(50)
        }
        return json
    }

    @Test
    fun `qualification appears with the last matching skill and revokes on retraction`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"

            // backend requires kotlin + sql; ada has kotlin only → 1/2, not qualified
            post(client, base, "action=jskill&job=backend&skill=kotlin")
            post(client, base, "action=jskill&job=backend&skill=sql")
            post(client, base, "action=cskill&candidate=ada&skill=kotlin")

            // each derived view updates asynchronously (separate outlets, no
            // glitch-free join at the edge — see F-5 in doc/demo-findings.md),
            // so await the JOINT condition, not the first view to move
            var json = await(client, base) {
                """"matched":1,"required":2,"qualified":false""" in it &&
                        """"gap":[{"job":"backend","skill":"sql"}]""" in it
            }
            assertTrue(""""qualified":false""" in json, "partial match should not qualify: $json")
            assertTrue(""""gap":[{"job":"backend","skill":"sql"}]""" in json, "sql should be the gap: $json")

            // the last matching skill flips qualification and empties the gap
            post(client, base, "action=cskill&candidate=ada&skill=sql")
            json = await(client, base) {
                """"matched":2,"required":2,"qualified":true""" in it && """"gap":[]""" in it
            }
            assertTrue(""""qualified":true""" in json, "full match should qualify: $json")
            assertTrue(""""gap":[]""" in json, "gap should be empty: $json")

            // retraction revokes: removing ada's sql demotes to 1/2 and restores the gap
            post(client, base, "action=uncskill&candidate=ada&skill=sql")
            json = await(client, base) { """"qualified":false""" in it && """"skill":"sql"""" in it.substringAfter("\"gap\"") }
            assertTrue(""""matched":1,"required":2,"qualified":false""" in json, "retraction should demote: $json")

            // boundary validation
            assertEquals(400, post(client, base, "action=cskill&candidate=ada"))
            assertEquals(400, post(client, base, "action=cskill&skill=kotlin"))
            assertEquals(400, post(client, base, "action=nonsense&candidate=a&skill=b"))
        } finally {
            app.stop()
        }
    }
}
