package civictech.demo.alignment

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [TypeSafeJevRater]'s wire contract against a local stub of `/v1/systemone`: what the request
 * carries (and, as importantly, what it does not), how scores map onto `[1, 9]`, the knowledge
 * gate's abstention, and the 429 retry.
 */
class TypeSafeJevRaterTest {

    private val requests = CopyOnWriteArrayList<JsonObject>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/systemone") { ex ->
            requests += Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            val (status, body) = synchronized(replies) { replies.removeFirst() }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun rater() = TypeSafeJevRater(
        apiKey = "test-key",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        sleeper = {},
        backoff = Duration.ZERO,
    )

    private fun reply(status: Int, body: String) = synchronized(replies) { replies.addLast(status to body) }

    private val idea = Idea("a", "Bike lanes", "Protected lanes on the main road", "ann")
    private val dims = sortedMapOf(
        "impact" to Dimension("Impact", "nobody notices", "transforms the city", "people helped"),
        "effort" to Dimension("Effort"),
    )

    @Test
    fun `one request per idea carries only the idea and one score question per dimension`() {
        reply(200, """{"model":"jev-1.13.0","answers":{"d0":{"score":1.0},"d1":{"score":3.0},"knowledge":{"choice":"WITHIN_MY_KNOWLEDGE"}}}""")
        val got = rater().rate("Mobility", idea, dims)

        // dims iterate in id order: d0 = effort, d1 = impact; score s over 5 levels → 1 + 8·s/4
        assertEquals(mapOf("effort" to 3.0, "impact" to 7.0), got.ratings)
        // called as the alias, identified as the version that actually ran
        assertEquals("jev-latest", requests.single()["model"]!!.jsonPrimitive.content)
        assertEquals("ai:jev-1.13.0", got.participant)

        val req = requests.single()
        assertEquals(
            setOf("topic", "idea_title", "idea_description"),
            req["state"]!!.jsonObject.keys,
            "no date, no other idea, no human rating: $req",
        )
        val questions = req["questions"]!!.jsonObject
        assertEquals(setOf("d0", "d1", "knowledge"), questions.keys)
        val impact = questions["d1"]!!.jsonObject
        assertEquals("score", impact["type"]!!.jsonPrimitive.content)
        assertTrue("people helped" in impact["instructions"]!!.jsonPrimitive.content, "the description is the anchor: $impact")
        val levels = impact["criteria"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(5, levels.size)
        assertTrue("nobody notices" in levels.first() && "transforms the city" in levels.last(), "$levels")
    }

    @Test
    fun `outside its knowledge, Jev abstains on every dimension`() {
        reply(200, """{"answers":{"d0":{"score":4.0},"d1":{"score":4.0},"knowledge":{"choice":"OUTSIDE_MY_KNOWLEDGE"}}}""")
        assertEquals(emptyMap(), rater().rate("Mobility", idea, dims).ratings)
    }

    @Test
    fun `429 is retried and any other failure throws`() {
        reply(429, "slow down")
        reply(200, """{"answers":{"d0":{"score":0.0},"d1":{"score":4.0},"knowledge":{"choice":"WITHIN_MY_KNOWLEDGE"}}}""")
        val got = rater().rate("Mobility", idea, dims)
        assertEquals(mapOf("effort" to 1.0, "impact" to 9.0), got.ratings)
        assertEquals("jev-latest", got.model, "a response without `model` falls back to the requested one")
        assertEquals(2, requests.size)

        reply(500, "boom")
        assertFailsWith<IOException> { rater().rate("Mobility", idea, dims) }
        reply(200, """{"answers":{"d0":{"score":1.0}}}""")
        assertFailsWith<IOException> { rater().rate("Mobility", idea, dims) }
    }
}
