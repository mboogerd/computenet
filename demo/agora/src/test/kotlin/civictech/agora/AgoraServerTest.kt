package civictech.agora

import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgoraServerTest {

    private fun parseCredences(json: String): Map<String, Double> =
        (Json.parseToJsonElement(json) as JsonArray).associate {
            it.jsonObject["ref"]!!.jsonPrimitive.content to it.jsonObject["credence"]!!.jsonPrimitive.content.toDouble()
        }

    private fun HttpProbe.credences(): Map<String, Double> = parseCredences(get("/graph").body())

    /** T12 finding 2: hard-fails (via [HttpProbe.await]'s throw) instead of a silent-pass soft timeout. */
    private fun HttpProbe.awaitCredences(deadlineMs: Long = 5_000, predicate: (Map<String, Double>) -> Boolean) {
        await(timeoutMs = deadlineMs, path = "/graph") { predicate(parseCredences(it)) }
    }

    private fun HttpProbe.ref(body: String): String {
        val response = postForm(body)
        assertEquals(200, response.statusCode(), response.body())
        return Json.parseToJsonElement(response.body()).jsonObject["ref"]!!.jsonPrimitive.content
    }

    @Test
    fun `rejects self-edges and de-duplicates identical relations`() {
        val app = AgoraApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            val a = probe.ref("action=claim&text=a")
            val b = probe.ref("action=claim&text=b")

            // a node cannot argue about itself
            val self = probe.postForm("action=edge&source=$a&target=$a&polarity=support")
            assertEquals(400, self.statusCode())
            assertTrue("itself" in self.body(), self.body())

            // re-asserting an identical relation returns the SAME edge (idempotent),
            // so influence is never stacked by re-posting
            val e1 = probe.ref("action=edge&source=$b&target=$a&polarity=support")
            val e2 = probe.ref("action=edge&source=$b&target=$a&polarity=support")
            assertEquals(e1, e2, "duplicate relation must resolve to the existing edge")
            assertEquals(
                1,
                probe.credences().keys.count { it != a && it != b },
                "only one edge node should exist",
            )

            // opposite polarity is a genuinely different assertion — still allowed
            val e3 = probe.ref("action=edge&source=$b&target=$a&polarity=attack")
            assertTrue(e3 != e1, "opposite polarity is a distinct edge")

            // invalid polarity is a clean 400, not a leaked enum exception
            val badPol = probe.postForm("action=edge&source=$a&target=$b&polarity=maybe")
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
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val a = probe.ref("action=claim&text=a")

            val badStance = probe.postForm("action=stance&id=$a&user=u&value=abc")
            assertEquals(400, badStance.statusCode())
            assertTrue("number" in badStance.body(), badStance.body())
            assertTrue("For input string" !in badStance.body(), "leaked exception: ${badStance.body()}")

            val bogus = "00000000-0000-0000-0000-000000000000"
            val badTarget = probe.postForm("action=edge&source=$a&target=$bogus&polarity=support")
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
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val base = "http://localhost:${app.boundPort}"

            val a = probe.ref("action=claim&text=we+should+build+it")
            val b = probe.ref("action=claim&text=it+is+too+expensive")

            // b attacks a, backed by a strong stance on b
            val attack = probe.ref("action=edge&source=$b&target=$a&polarity=attack")
            assertEquals(200, probe.postForm("action=stance&id=$b&user=u1&value=0.95").statusCode())
            // fixpoint: energy 0.95×0.5 → a = 0.5×(1−0.475) = 0.2625
            probe.awaitCredences { it.getValue(a) < 0.3 }

            // now attack the attack: the relation itself is a claim
            val counter = probe.ref("action=claim&text=cost+estimate+is+outdated")
            probe.ref("action=edge&source=$counter&target=$attack&polarity=attack")
            assertEquals(200, probe.postForm("action=stance&id=$counter&user=u2&value=0.95").statusCode())
            // weakened attack (0.2625) × b (0.95) → a recovers to 0.375
            probe.awaitCredences { it.getValue(a) > 0.35 }

            // SSE delivers state. The read carries its own deadline
            // (computenet-o7c3): the previous `generateSequence { readLine() }.first`
            // had none at all, so a stream that never delivered a data: frame parked
            // this thread until JUnit's 300s timeout.
            val line = awaitSseData("$base/events", timeoutMs = 5_000)
            assertTrue("credence" in line, "SSE payload missing credences: $line")
        } finally {
            app.stop()
        }
    }
}
