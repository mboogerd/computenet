package civictech.agora

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgoraServerTest {

    private fun HttpClient.post(base: String, body: String): HttpResponse<String> = send(
        HttpRequest.newBuilder(URI("$base/op"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun HttpClient.credences(base: String): Map<String, Double> = send(
        HttpRequest.newBuilder(URI("$base/graph")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body().let { json ->
        (Json.parseToJsonElement(json) as JsonArray).associate {
            it.jsonObject["ref"]!!.jsonPrimitive.content to it.jsonObject["credence"]!!.jsonPrimitive.content.toDouble()
        }
    }

    private fun HttpClient.await(base: String, deadlineMs: Long = 5_000, predicate: (Map<String, Double>) -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(credences(base))) return
            Thread.sleep(50)
        }
        assertTrue(false, "condition not reached before deadline; state: ${credences(base)}")
    }

    private fun HttpClient.ref(base: String, body: String): String {
        val response = post(base, body)
        assertEquals(200, response.statusCode(), response.body())
        return Json.parseToJsonElement(response.body()).jsonObject["ref"]!!.jsonPrimitive.content
    }

    @Test
    fun `rejects self-edges and de-duplicates identical relations`() {
        val app = AgoraApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"

            val a = client.ref(base, "action=claim&text=a")
            val b = client.ref(base, "action=claim&text=b")

            // a node cannot argue about itself
            val self = client.post(base, "action=edge&source=$a&target=$a&polarity=support")
            assertEquals(400, self.statusCode())
            assertTrue("itself" in self.body(), self.body())

            // re-asserting an identical relation returns the SAME edge (idempotent),
            // so influence is never stacked by re-posting
            val e1 = client.ref(base, "action=edge&source=$b&target=$a&polarity=support")
            val e2 = client.ref(base, "action=edge&source=$b&target=$a&polarity=support")
            assertEquals(e1, e2, "duplicate relation must resolve to the existing edge")
            assertEquals(
                1,
                client.credences(base).keys.count { it != a && it != b },
                "only one edge node should exist",
            )

            // opposite polarity is a genuinely different assertion — still allowed
            val e3 = client.ref(base, "action=edge&source=$b&target=$a&polarity=attack")
            assertTrue(e3 != e1, "opposite polarity is a distinct edge")

            // invalid polarity is a clean 400, not a leaked enum exception
            val badPol = client.post(base, "action=edge&source=$a&target=$b&polarity=maybe")
            assertEquals(400, badPol.statusCode())
            assertTrue("No enum constant" !in badPol.body(), "leaked internals: ${badPol.body()}")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `command errors stay clean (no leaked internals)`() {
        val app = AgoraApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"
            val a = client.ref(base, "action=claim&text=a")

            val badStance = client.post(base, "action=stance&id=$a&user=u&value=abc")
            assertEquals(400, badStance.statusCode())
            assertTrue("number" in badStance.body(), badStance.body())
            assertTrue("For input string" !in badStance.body(), "leaked exception: ${badStance.body()}")

            val bogus = "00000000-0000-0000-0000-000000000000"
            val badTarget = client.post(base, "action=edge&source=$a&target=$bogus&polarity=support")
            assertEquals(400, badTarget.statusCode())
            assertTrue("CellRef(" !in badTarget.body(), "leaked CellRef repr: ${badTarget.body()}")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `attack lowers a claim, attacking the attack restores it`() {
        val app = AgoraApp(port = 0).start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"

            fun ref(body: String): String {
                val response = client.post(base, body)
                assertEquals(200, response.statusCode(), response.body())
                return Json.parseToJsonElement(response.body()).jsonObject["ref"]!!.jsonPrimitive.content
            }

            val a = ref("action=claim&text=we+should+build+it")
            val b = ref("action=claim&text=it+is+too+expensive")

            // b attacks a, backed by a strong stance on b
            val attack = ref("action=edge&source=$b&target=$a&polarity=attack")
            assertEquals(200, client.post(base, "action=stance&id=$b&user=u1&value=0.95").statusCode())
            // fixpoint: energy 0.95×0.5 → a = 0.5×(1−0.475) = 0.2625
            client.await(base) { it.getValue(a) < 0.3 }

            // now attack the attack: the relation itself is a claim
            val counter = ref("action=claim&text=cost+estimate+is+outdated")
            ref("action=edge&source=$counter&target=$attack&polarity=attack")
            assertEquals(200, client.post(base, "action=stance&id=$counter&user=u2&value=0.95").statusCode())
            // weakened attack (0.2625) × b (0.95) → a recovers to 0.375
            client.await(base) { it.getValue(a) > 0.35 }

            // SSE delivers state
            val events = client.send(
                HttpRequest.newBuilder(URI("$base/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            assertEquals(200, events.statusCode())
            events.body().bufferedReader().use { reader ->
                val line = generateSequence { reader.readLine() }.first { it.startsWith("data:") }
                assertTrue("credence" in line, "SSE payload missing credences: $line")
            }
        } finally {
            app.stop()
        }
    }
}
