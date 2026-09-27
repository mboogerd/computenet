package civictech.deliberate

import civictech.agora.cell.Polarity
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [JevJudge] against a local stub replaying recorded-shape System One responses. */
class JevJudgeTest {

    private data class Seen(val auth: String?, val body: JsonObject)

    private val seen = ConcurrentLinkedQueue<Seen>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/systemone") { ex ->
            seen += Seen(ex.requestHeaders.getFirst("Authorization"), Json.parseToJsonElement(ex.requestBody.readAllBytes().decodeToString()).jsonObject)
            val (status, body) = synchronized(replies) { replies.removeFirst() }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val judge = JevJudge(
        apiKey = "test-key",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        backoff = Duration.ofMillis(1),
    )

    private fun judge(
        maxAttempts: Int = 4,
        sleeper: (Duration) -> Unit,
    ) = JevJudge(
        apiKey = "test-key",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        maxAttempts = maxAttempts,
        backoff = Duration.ofMillis(3),
        sleeper = sleeper,
    )

    @AfterTest
    fun stop() = server.stop(0)

    private fun reply(status: Int, body: String) = synchronized(replies) { replies.addLast(status to body) }

    private fun scoreReply(id: String, score: Double) = reply(
        200,
        """{"model":"jev-1.13.0","answers":{"$id":{"type":"score","score":$score,
           "legend":{"0":"a","1":"b","2":"c","3":"d","4":"e"},
           "probabilities":{"0":0.0,"1":0.05,"2":0.1,"3":0.6,"4":0.25},"confidence":0.7}},
           "usage":{"input_tokens":300,"output_tokens":20}}""",
    )

    private fun noulReply(id: String, p: Double) =
        reply(200, """{"model":"jev-1.13.0","answers":{"$id":{"type":"noul","noul":$p}},"usage":{"input_tokens":1,"output_tokens":1}}""")

    private fun question(body: JsonObject, id: String) = body["questions"]!!.jsonObject[id]!!.jsonObject

    private val ctx = ClaimContext("Should cities ban cars?", listOf("Should cities ban cars?"), "Cars pollute.", listOf("p1"), listOf("c1", "c2"))

    @Test
    fun `plausibility sends a five-level score and maps it to unit range`() {
        scoreReply("plausibility", 3.0)
        assertEquals(0.75, judge.plausibility("Q?", listOf("Q?"), "Claim."), 1e-9)
        val req = seen.single()
        assertEquals("Bearer test-key", req.auth)
        assertEquals("jev-latest", req.body["model"]!!.jsonPrimitive.content)
        val state = req.body["state"]!!.jsonObject
        assertEquals(setOf("root_question", "path_from_root", "claim"), state.keys)
        assertEquals("Q?", state["root_question"]!!.jsonPrimitive.content)
        assertEquals("Claim.", state["claim"]!!.jsonPrimitive.content)
        val q = question(req.body, "plausibility")
        assertEquals("score", q["type"]!!.jsonPrimitive.content)
        val criteria = (q["criteria"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(5, criteria.size)
        assertTrue(criteria.first().startsWith("Almost certainly false:"))
        assertTrue("directly contradict" in criteria.first())
        assertTrue(criteria.last().startsWith("Almost certainly true:"))
        assertTrue("directly support" in criteria.last())
    }

    @Test
    fun `relation strength states the direction and maps the score`() {
        scoreReply("strength", 1.0)
        assertEquals(0.25, judge.relationStrength("Q?", "Parent.", "Child.", Polarity.ATTACK), 1e-9)
        val body = seen.single().body
        val state = body["state"]!!.jsonObject
        assertEquals(setOf("root_question", "parent_claim", "child_claim", "direction"), state.keys)
        assertEquals("attacks", state["direction"]!!.jsonPrimitive.content)
        val criteria = (question(body, "strength")["criteria"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(5, criteria.size)
        assertTrue("opposite direction" in criteria.first())
        assertTrue("settled" in criteria.last())
    }

    @Test
    fun `duplicates is one request with one choice per candidate`() {
        reply(
            200,
            """{"model":"jev-1.13.0","answers":{
               "c0":{"type":"choice","choice":"none","probabilities":{"none":0.9,"0":0.05,"1":0.05},"confidence":0.8},
               "c1":{"type":"choice","choice":"1","probabilities":{"none":0.1,"0":0.0,"1":0.9},"confidence":0.8}},
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        assertEquals(listOf(null, 1), judge.duplicates("Claim.", Polarity.SUPPORT, listOf("e0", "e1"), listOf("new", "e1 again")))
        val body = seen.single().body
        assertEquals(setOf("c0", "c1"), body["questions"]!!.jsonObject.keys)
        val q = question(body, "c1")
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("none", "0", "1"), q["criteria"]!!.jsonObject.keys)
        assertTrue("substantively new point" in q["criteria"]!!.jsonObject["none"]!!.jsonPrimitive.content)
        assertEquals("e1", q["criteria"]!!.jsonObject["1"]!!.jsonPrimitive.content)
        assertEquals("e1 again", q["instructions"]!!.jsonObject["candidate_argument"]!!.jsonPrimitive.content)
        assertTrue("same reason" in q["instructions"]!!.jsonObject["question"]!!.jsonPrimitive.content)
    }

    @Test
    fun `duplicates against nothing needs no call`() {
        assertEquals(listOf<Int?>(null), judge.duplicates("Claim.", Polarity.ATTACK, emptyList(), listOf("x")))
        assertEquals(emptyList(), judge.duplicates("Claim.", Polarity.ATTACK, listOf("e"), emptyList()))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `saturation sends the side's arguments with explicit criteria`() {
        // EXP-04: Jev is asked whether something is still MISSING; saturation is the complement.
        noulReply("missing", 0.82)
        assertEquals(0.18, judge.saturation(ctx, Polarity.ATTACK), 1e-9)
        val body = seen.single().body
        assertEquals(listOf("c1", "c2"), (body["state"]!!.jsonObject["existing_arguments"] as JsonArray).map { it.jsonPrimitive.content })
        val q = question(body, "missing")
        assertTrue("still missing" in q["instructions"]!!.jsonPrimitive.content)
        assertEquals("noul", q["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("true", "false"), q["criteria"]!!.jsonObject.keys)
        assertTrue("not yet represented" in q["criteria"]!!.jsonObject["true"]!!.jsonPrimitive.content)
        assertTrue("would mostly restate" in q["criteria"]!!.jsonObject["false"]!!.jsonPrimitive.content)
    }

    @Test
    fun `relevance is a noul over question and path`() {
        noulReply("relevant", 0.3)
        assertEquals(0.3, judge.relevance(ctx), 1e-9)
        val body = seen.single().body
        val state = body["state"]!!.jsonObject
        assertEquals(setOf("root_question", "path_from_root", "claim"), state.keys)
        val criteria = question(body, "relevant")["criteria"]!!.jsonObject
        assertTrue("alter at least one important reason" in criteria["true"]!!.jsonPrimitive.content)
        assertTrue("remain effectively the same" in criteria["false"]!!.jsonPrimitive.content)
    }

    @Test
    fun `retries 429 and 529 with exponential backoff then succeeds`() {
        val delays = mutableListOf<Duration>()
        reply(429, """{"error":"rate limited"}""")
        reply(529, """{"error":"overloaded"}""")
        noulReply("relevant", 0.6)
        assertEquals(0.6, judge(sleeper = { delays += it }).relevance(ctx), 1e-9)
        assertEquals(3, seen.size)
        assertEquals(listOf(Duration.ofMillis(3), Duration.ofMillis(6)), delays)
    }

    @Test
    fun `gives up after four attempts`() {
        repeat(4) { reply(529, "down") }
        val e = assertFailsWith<JevException> { judge(sleeper = { _ -> }).relevance(ctx) }
        assertEquals(529, e.status)
        assertEquals(4, seen.size)
    }

    @Test
    fun `non-retryable HTTP statuses fail once`() {
        reply(422, """{"detail":"criteria: field required"}""")
        assertEquals(422, assertFailsWith<JevException> { judge.relevance(ctx) }.status)
        reply(503, """{"detail":"unavailable"}""")
        assertEquals(503, assertFailsWith<JevException> { judge.relevance(ctx) }.status)
        assertEquals(2, seen.size)
    }

    @Test
    fun `malformed successful response fails without retry`() {
        reply(200, """{"model":"jev-1.13.0"}""")
        val error = assertFailsWith<JevException> { judge.relevance(ctx) }
        assertEquals(200, error.status)
        assertEquals(1, seen.size)
    }

    @Test
    fun `unknown duplicate choice fails`() {
        reply(200, """{"answers":{"c0":{"type":"choice","choice":"99"}}}""")
        val error = assertFailsWith<JevException> {
            judge.duplicates("Claim.", Polarity.SUPPORT, listOf("existing"), listOf("candidate"))
        }
        assertEquals(null, error.status)
        assertTrue("unknown duplicate option" in error.message!!)
    }

    @Test
    fun `IO failure is not retried`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val delays = mutableListOf<Duration>()
        val offline = JevJudge(
            apiKey = "test-key",
            baseUrl = "http://127.0.0.1:$unusedPort",
            backoff = Duration.ZERO,
            requestTimeout = Duration.ofSeconds(1),
            sleeper = { delays += it },
        )
        val error = assertFailsWith<JevException> { offline.relevance(ctx) }
        assertEquals(null, error.status)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `retry attempt cap cannot exceed four`() {
        assertFailsWith<IllegalArgumentException> { judge(maxAttempts = 0, sleeper = { _ -> }) }
        assertFailsWith<IllegalArgumentException> { judge(maxAttempts = 5, sleeper = { _ -> }) }
    }
}
