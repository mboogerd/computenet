package civictech.demo.tiering

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TieringServerTest {

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
    fun `valuations and preferences fuse into the board and re-tier on change`() {
        val app = TieringApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"

            post(client, base, "action=item&name=pizza")
            post(client, base, "action=item&name=sushi")

            // an unrated item sits in the unrated bucket
            var json = await(client, base) { """"unrated":["pizza","sushi"]""" in it }
            assertTrue(""""unrated":["pizza","sushi"]""" in json, "new items should be unrated: $json")

            // two S valuations put pizza in S (tierAvg 6 → score 1.0)
            post(client, base, "action=tier&agent=ada&item=pizza&tier=S")
            post(client, base, "action=tier&agent=bo&item=pizza&tier=S")
            json = await(client, base) { """"S":[{"item":"pizza"""" in it }
            assertTrue(""""S":[{"item":"pizza","score":1.0000}]""" in json, "pizza should be tier S: $json")

            // a pairwise vote alone tiers sushi (pref-only signal: (1+1)/2 = 1.0 → S)
            // and drags pizza down (blend 0.7·1.0 + 0.3·0.0 = 0.7 → A)
            post(client, base, "action=pref&agent=cy&winner=sushi&loser=pizza")
            json = await(client, base) {
                """"item":"sushi","score":1.0000""" in it && """"A":[{"item":"pizza","score":0.7000}]""" in it
            }
            assertTrue(""""A":[{"item":"pizza","score":0.7000}]""" in json, "the lost pairwise should demote pizza: $json")

            // re-tiering replaces, never duplicates: ada drops pizza to C (score 3)
            // → tierAvg (6+3)/2 = 4.5 → 0.75 → blend 0.7·0.75 = 0.525 → tier C
            post(client, base, "action=tier&agent=ada&item=pizza&tier=C")
            json = await(client, base) { """"C":[{"item":"pizza","score":0.5250}]""" in it }
            assertTrue(""""C":[{"item":"pizza","score":0.5250}]""" in json, "re-tier should replace ada's S: $json")

            // full retraction: remove the pref → sushi has no signal left → unrated
            post(client, base, "action=unpref&agent=cy&winner=sushi&loser=pizza")
            json = await(client, base) { """"unrated":["sushi"]""" in it }
            assertTrue(""""unrated":["sushi"]""" in json, "sushi should fall back to unrated: $json")

            // boundary validation
            assertEquals(400, post(client, base, "action=tier&agent=ada&item=pizza&tier=Z"))
            assertEquals(400, post(client, base, "action=pref&agent=ada&winner=pizza&loser=pizza"))
            assertEquals(400, post(client, base, "action=item"))
        } finally {
            app.stop()
        }
    }
}
