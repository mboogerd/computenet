package civictech.demo.slotfinder

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotFinderServerTest {

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

    @Test
    fun `a slot shared by all three flows through intersect, filter and count`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"

            // Tue-14 for everyone (business hours), Tue-19 for everyone (after hours)
            for (user in PARTICIPANTS) {
                for (hour in listOf(14, 19)) {
                    assertEquals(200, post(client, base, "action=add&user=$user&day=Tue&hour=$hour"))
                }
            }

            // ops flow writer → intersect chain → hubs asynchronously; poll /state
            val deadline = System.currentTimeMillis() + 5_000
            var json = ""
            while (System.currentTimeMillis() < deadline) {
                json = state(client, base)
                if ("\"byDay\":{\"Tue\":1}" in json) break
                Thread.sleep(50)
            }
            assertTrue("\"common\":[\"Tue-14\",\"Tue-19\"]" in json, "common slots missing in $json")
            assertTrue("\"filtered\":[\"Tue-14\"]" in json, "business-hours filter wrong in $json")
            assertTrue("\"byDay\":{\"Tue\":1}" in json, "per-day count wrong in $json")

            // retraction: bob drops Tue-14 → it leaves every derived view
            assertEquals(200, post(client, base, "action=remove&user=bob&day=Tue&hour=14"))
            val gone = System.currentTimeMillis() + 5_000
            var retracted = false
            while (System.currentTimeMillis() < gone) {
                json = state(client, base)
                if ("\"filtered\":[]" in json && "Tue-14" !in json.substringAfter("\"common\"")) {
                    retracted = true; break
                }
                Thread.sleep(50)
            }
            assertTrue(retracted, "retraction never reached the derived views: $json")

            // input validation at the boundary
            assertEquals(400, post(client, base, "action=add&user=mallory&day=Tue&hour=14"))
            assertEquals(400, post(client, base, "action=add&user=alice&day=Sun&hour=14"))
            assertEquals(400, post(client, base, "action=add&user=alice&day=Tue&hour=23"))
        } finally {
            app.stop()
        }
    }

    @Test
    fun `SSE delivers state and a fresh subscriber catches up`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"
            post(client, base, "action=add&user=alice&day=Mon&hour=10")

            val events = client.send(
                HttpRequest.newBuilder(URI("$base/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            assertEquals(200, events.statusCode())
            val deadline = System.currentTimeMillis() + 5_000
            var seen = false
            events.body().bufferedReader().use { reader ->
                while (!seen && System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:") && "Mon-10" in line) seen = true
                }
            }
            assertTrue(seen, "SSE stream never delivered alice's slot")
        } finally {
            app.stop()
        }
    }
}
