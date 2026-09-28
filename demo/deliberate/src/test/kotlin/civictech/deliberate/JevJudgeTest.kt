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
import java.net.SocketTimeoutException
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
    private val routed = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/systemone") { ex ->
            val request = Json.parseToJsonElement(ex.requestBody.readAllBytes().decodeToString()).jsonObject
            seen += Seen(ex.requestHeaders.getFirst("Authorization"), request)
            // Parallel requests (assess) are answered by their question keys, the rest in FIFO order.
            val keys = request["questions"]!!.jsonObject.keys
            val (status, body) = synchronized(replies) {
                routed.keys.firstOrNull { it in keys }?.let { 200 to routed.getValue(it) } ?: replies.removeFirst()
            }
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

    /** Model D: a plausibility request is answered with its score and its `knowledge` Choice. */
    private fun plausibilityAnswers(score: Double, knowledge: String = "WITHIN_MY_KNOWLEDGE") =
        """"plausibility":{"type":"score","score":$score},"knowledge":{"type":"choice","choice":"$knowledge"}"""

    private fun plausibilityReply(score: Double, knowledge: String = "WITHIN_MY_KNOWLEDGE") = reply(
        200,
        """{"model":"jev-1.13.0","answers":{${plausibilityAnswers(score, knowledge)}},"usage":{"input_tokens":300,"output_tokens":20}}""",
    )

    private fun noulReply(id: String, p: Double) =
        reply(200, """{"model":"jev-1.13.0","answers":{"$id":{"type":"noul","noul":$p}},"usage":{"input_tokens":1,"output_tokens":1}}""")

    private fun question(body: JsonObject, id: String) = body["questions"]!!.jsonObject[id]!!.jsonObject

    private val ctx = ClaimContext("Should cities ban cars?", listOf("Should cities ban cars?"), "Cars pollute.", listOf("p1"), listOf("c1", "c2"))

    @Test
    fun `plausibility sends a five-level score and maps it to unit range`() {
        plausibilityReply(3.0)
        assertEquals(0.75, judge.plausibility("Q?", "Claim."), 1e-9)
        val req = seen.single()
        assertEquals("Bearer test-key", req.auth)
        assertEquals("jev-latest", req.body["model"]!!.jsonPrimitive.content)
        val state = req.body["state"]!!.jsonObject
        // CRED-01: no path — it pulled the judgment towards the claim's role in the argument
        assertEquals(setOf("root_question", "claim"), state.keys)
        assertEquals("Q?", state["root_question"]!!.jsonPrimitive.content)
        assertEquals("Claim.", state["claim"]!!.jsonPrimitive.content)
        val q = question(req.body, "plausibility")
        assertTrue("Judge only what `claim` itself asserts" in q["instructions"]!!.jsonPrimitive.content)
        assertEquals("score", q["type"]!!.jsonPrimitive.content)
        val criteria = (q["criteria"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(5, criteria.size)
        assertTrue(criteria.first().startsWith("Almost certainly false:"))
        assertTrue("directly contradict" in criteria.first())
        assertTrue(criteria.last().startsWith("Almost certainly true:"))
        assertTrue("directly support" in criteria.last())
        // Model D: the same request offers an explicit "outside my knowledge" answer.
        assertEquals(setOf("plausibility", "knowledge"), req.body["questions"]!!.jsonObject.keys)
        val k = question(req.body, "knowledge")
        assertEquals("choice", k["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("WITHIN_MY_KNOWLEDGE", "OUTSIDE_MY_KNOWLEDGE"), k["criteria"]!!.jsonObject.keys)
        assertTrue("Outside my knowledge" in k["criteria"]!!.jsonObject["OUTSIDE_MY_KNOWLEDGE"]!!.jsonPrimitive.content)
    }

    @Test
    fun `outside my knowledge maps plausibility to one half whatever the score says`() {
        plausibilityReply(0.0, knowledge = "OUTSIDE_MY_KNOWLEDGE")
        assertEquals(Judge.OUTSIDE_KNOWLEDGE, judge.plausibility("Q?", "A bill passed last week does X."), 0.0)
        assertEquals(0.5, Judge.OUTSIDE_KNOWLEDGE)
        plausibilityReply(4.0, knowledge = "OUTSIDE_MY_KNOWLEDGE")
        assertEquals(0.5, judge.plausibility("Q?", "C."), 0.0)
        // Within its knowledge, the score is used as before.
        plausibilityReply(1.0)
        assertEquals(0.25, judge.plausibility("Q?", "C."), 1e-9)
        // assess takes the same path for its plausibility.
        routed["plausibility"] = """{"model":"jev-1.13.0","answers":{${plausibilityAnswers(4.0, "OUTSIDE_MY_KNOWLEDGE")}}}"""
        routed["strength"] = """{"model":"jev-1.13.0","answers":{
               "strength":{"type":"score","score":2.0},"quality":{"type":"noul","noul":0.9},
               "relevant":{"type":"noul","noul":0.4}}}"""
        assertEquals(0.5, judge.assess("Q?", listOf("Q?"), "Child.", Polarity.SUPPORT).plausibility, 0.0)
    }

    @Test
    fun `an unknown knowledge answer fails`() {
        plausibilityReply(3.0, knowledge = "MAYBE")
        val error = assertFailsWith<JevException> { judge.plausibility("Q?", "C.") }
        assertTrue("unknown knowledge answer" in error.message!!)
    }

    /** Every JSON object key under [e], at any depth. */
    private fun keysOf(e: kotlinx.serialization.json.JsonElement): List<String> = when (e) {
        is JsonObject -> e.keys.toList() + e.values.flatMap(::keysOf)
        is JsonArray -> e.flatMap(::keysOf)
        else -> emptyList()
    }

    @Test
    fun `no request carries today's date or any current-date field`() {
        // One request of every kind JevJudge sends: plausibility, assess (two requests), relation strength,
        // quality, triage, bearing, saturation, relevance.
        routed["plausibility"] = """{"answers":{${plausibilityAnswers(3.0)}}}"""
        routed["strength"] = """{"answers":{"strength":{"type":"score","score":2.0},"quality":{"type":"noul","noul":0.9},"relevant":{"type":"noul","noul":0.4}}}"""
        judge.plausibility("Q?", "C.")
        judge.assess("Q?", listOf("Q?", "Parent."), "Child.", Polarity.ATTACK)
        routed.clear()
        scoreReply("strength", 1.0)
        judge.relationStrength("Q?", "Parent.", "Child.", Polarity.SUPPORT)
        noulReply("quality", 0.9)
        judge.quality("Q?", "Parent.", "Child.", Polarity.SUPPORT)
        reply(200, """{"answers":{${choice("a0", "ADD")},${choice("t0", "none")}}}""")
        judge.triage(ctx, listOf(Candidate("new", Polarity.SUPPORT)))
        reply(200, """{"answers":{${choice("b0", "NEITHER")}}}""")
        judge.bearing(ctx, link, listOf("x"))
        noulReply("missing", 0.5)
        judge.saturation(ctx, Polarity.SUPPORT)
        noulReply("relevant", 0.5)
        judge.relevance(ctx)

        assertEquals(9, seen.size)
        val today = java.time.LocalDate.now()
        // Around midnight the clock may have moved on while the requests were sent.
        val dates = listOf(today.minusDays(1), today, today.plusDays(1)).map { it.toString() }
        val isoDate = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
        // A date written out ("Today is September 28, 2026") has no ISO form and may sit under any key.
        val years = (today.year - 1..today.year + 1).map { Regex("""\b$it\b""") }
        val dateWords = setOf("date", "today", "now", "time", "timestamp", "current", "year")
        seen.forEach { req ->
            val text = req.body.toString()
            dates.forEach { assertTrue(it !in text, "a request carries today's date: $text") }
            assertTrue(isoDate.find(text) == null, "a request carries a date: $text")
            years.forEach { assertTrue(it.find(text) == null, "a request carries the current year: $text") }
            keysOf(req.body).forEach { key ->
                val words = key.lowercase().split('_', '-', ' ').toSet()
                assertTrue(words.none { it in dateWords }, "a request carries a current-date field `$key`")
            }
        }
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

    private fun choice(id: String, choice: String) = """"$id":{"type":"choice","choice":"$choice","probabilities":{},"confidence":0.8}"""

    @Test
    fun `triage is one request with an action and a target choice per candidate`() {
        // ctx: pros [p1], cons [c1, c2]; target options 0..2 are existing, 3 is candidate 0.
        reply(
            200,
            """{"model":"jev-1.13.0","answers":{${choice("a0", "ADD")},${choice("t0", "none")},
               ${choice("a1", "REFINE")},${choice("t1", "0")},
               ${choice("a2", "DUPLICATE")},${choice("t2", "3")}},"usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val out = judge.triage(
            ctx,
            listOf(Candidate("new", Polarity.SUPPORT), Candidate("p1 instance", Polarity.SUPPORT), Candidate("new again", Polarity.ATTACK)),
        )
        assertEquals(listOf(Triage(TriageAction.ADD), Triage(TriageAction.REFINE, 0), Triage(TriageAction.DUPLICATE, 3)), out)
        val body = seen.single().body
        assertEquals(setOf("a0", "t0", "a1", "t1", "a2", "t2"), body["questions"]!!.jsonObject.keys)
        val state = body["state"]!!.jsonObject
        assertEquals(listOf("p1"), (state["existing_arguments_for"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("c1", "c2"), (state["existing_arguments_against"] as JsonArray).map { it.jsonPrimitive.content })
        val a2 = question(body, "a2")
        assertEquals("choice", a2["type"]!!.jsonPrimitive.content)
        assertEquals(TriageAction.entries.map { it.name }.toSet(), a2["criteria"]!!.jsonObject.keys)
        assertTrue("against" in a2["criteria"]!!.jsonObject["ADD"]!!.jsonPrimitive.content)
        assertTrue("for the claim" in a2["criteria"]!!.jsonObject["OTHER_SIDE"]!!.jsonPrimitive.content)
        assertEquals(2, (a2["instructions"]!!.jsonObject["earlier_new_arguments"] as JsonArray).size)
        // Later candidates may target earlier ones, labelled by side.
        val t2 = question(body, "t2")["criteria"]!!.jsonObject
        assertEquals(setOf("none", "0", "1", "2", "3", "4"), t2.keys)
        assertTrue(t2["0"]!!.jsonPrimitive.content.startsWith("(existing argument for the claim)"))
        assertTrue(t2["2"]!!.jsonPrimitive.content.startsWith("(existing argument against the claim)"))
        assertEquals("(another new argument for the claim) new", t2["3"]!!.jsonPrimitive.content)
    }

    @Test
    fun `UNDERCUT is offered against existing arguments and carries its target`() {
        reply(200, """{"model":"jev-1.13.0","answers":{${choice("a0", "UNDERCUT")},${choice("t0", "1")}}}""")
        val out = judge.triage(ctx, listOf(Candidate("c1 does not show that cars pollute", Polarity.ATTACK)))
        assertEquals(listOf(Triage(TriageAction.UNDERCUT, 1)), out)
        val a0 = question(seen.single().body, "a0")["criteria"]!!.jsonObject
        assertTrue("does not show what it is offered to show" in a0["UNDERCUT"]!!.jsonPrimitive.content)
    }

    @Test
    fun `link triage distinguishes an undercutter from a counter-argument against the parent`() {
        reply(200, """{"model":"jev-1.13.0","answers":{${choice("a0", "OTHER_SIDE")}}}""")
        val link = LinkContext("The path is wet.", "It rained.", Polarity.SUPPORT)
        val out = judge.triage(
            ClaimContext(
                "Why is the path wet?", listOf("Why is the path wet?"),
                "“The path is wet.” is a reason for “It rained.”", emptyList(), emptyList(), link,
            ),
            listOf(Candidate("The forecast predicted dry weather.", Polarity.ATTACK)),
        )
        assertEquals(listOf(Triage(TriageAction.OTHER_SIDE)), out)
        val body = seen.single().body
        val state = body["state"]!!.jsonObject
        assertEquals("The path is wet.", state["link_argument"]!!.jsonPrimitive.content)
        assertEquals("It rained.", state["parent_claim"]!!.jsonPrimitive.content)
        val action = question(body, "a0")
        val instructions = action["instructions"]!!.jsonObject["question"]!!.jsonPrimitive.content
        val criteria = action["criteria"]!!.jsonObject
        assertTrue("assume `link_argument` is true" in instructions, instructions)
        assertTrue("counter-argument, not an undercutter" in instructions, instructions)
        assertTrue("Move it to the parent claim" in criteria["OTHER_SIDE"]!!.jsonPrimitive.content)
        assertTrue("genuine counter-argument" in criteria["DROP"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a first candidate with nothing to compare against gets no target question`() {
        reply(
            200,
            """{"model":"jev-1.13.0","answers":{${choice("a0", "ADD")},${choice("a1", "REPLACE")},${choice("t1", "0")}},
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val empty = ClaimContext("Q?", listOf("Q?"), "Claim.", emptyList(), emptyList())
        val out = judge.triage(empty, listOf(Candidate("a", Polarity.SUPPORT), Candidate("a, better", Polarity.SUPPORT)))
        assertEquals(listOf(Triage(TriageAction.ADD), Triage(TriageAction.REPLACE, 0)), out)
        val body = seen.single().body
        assertEquals(setOf("a0", "a1", "t1"), body["questions"]!!.jsonObject.keys)
        // Without targets only ADD / OTHER_SIDE / DROP are offered.
        assertEquals(setOf("ADD", "OTHER_SIDE", "DROP"), question(body, "a0")["criteria"]!!.jsonObject.keys)
        assertEquals(setOf("none", "0"), question(body, "t1")["criteria"]!!.jsonObject.keys)
    }

    @Test
    fun `a triage target pointing at a later candidate is rejected`() {
        reply(200, """{"answers":{${choice("a0", "ADD")},${choice("a1", "DUPLICATE")},${choice("t1", "1")}}}""")
        val empty = ClaimContext("Q?", listOf("Q?"), "Claim.", emptyList(), emptyList())
        val error = assertFailsWith<JevException> {
            judge.triage(empty, listOf(Candidate("a", Polarity.SUPPORT), Candidate("b", Polarity.SUPPORT)))
        }
        assertTrue("unknown triage target" in error.message!!)
    }

    @Test
    fun `triage of nothing needs no call`() {
        assertEquals(emptyList(), judge.triage(ctx, emptyList()))
        assertTrue(seen.isEmpty())
    }

    private val link = LinkContext("Cars pollute.", "Cities should ban cars.", Polarity.SUPPORT)

    @Test
    fun `bearing is one request with a choice per candidate over the link state`() {
        reply(
            200,
            """{"model":"jev-1.13.0","answers":{${choice("b0", "DISPUTES_CLAIM")},${choice("b1", "DENIES_BEARING")}},
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val out = judge.bearing(
            ctx,
            link,
            listOf("Cars don't really pollute much.", "Even if cars pollute, banning them won't help the parent claim."),
        )
        assertEquals(listOf(Bearing.DISPUTES_CLAIM, Bearing.DENIES_BEARING), out)
        val body = seen.single().body
        assertEquals(setOf("b0", "b1"), body["questions"]!!.jsonObject.keys)
        val state = body["state"]!!.jsonObject
        assertEquals(setOf("root_question", "path_from_root", "parent_claim", "claim", "claim_direction"), state.keys)
        assertEquals(ctx.question, state["root_question"]!!.jsonPrimitive.content)
        assertEquals(ctx.path, (state["path_from_root"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(link.parent, state["parent_claim"]!!.jsonPrimitive.content)
        assertEquals(ctx.claim, state["claim"]!!.jsonPrimitive.content)
        assertEquals("supports", state["claim_direction"]!!.jsonPrimitive.content)
        val b0 = question(body, "b0")
        assertEquals("choice", b0["type"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(Bearing.DISPUTES_CLAIM.name, Bearing.DENIES_BEARING.name, Bearing.NEITHER.name),
            b0["criteria"]!!.jsonObject.keys,
        )
        assertEquals("Cars don't really pollute much.", b0["instructions"]!!.jsonObject["candidate_argument"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown bearing answer fails`() {
        reply(200, """{"answers":{${choice("b0", "MAYBE")}}}""")
        val error = assertFailsWith<JevException> { judge.bearing(ctx, link, listOf("x")) }
        assertEquals(null, error.status)
        assertTrue("unknown bearing answer" in error.message!!)
    }

    @Test
    fun `bearing of nothing needs no call`() {
        assertEquals(emptyList(), judge.bearing(ctx, link, emptyList()))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `assess asks plausibility alone and strength, quality and relevance together`() {
        routed["plausibility"] = """{"model":"jev-1.13.0","answers":{${plausibilityAnswers(3.0)}}}"""
        routed["strength"] = """{"model":"jev-1.13.0","answers":{
               "strength":{"type":"score","score":2.0},"quality":{"type":"noul","noul":0.9},
               "relevant":{"type":"noul","noul":0.4}},
               "usage":{"input_tokens":1,"output_tokens":1}}"""
        val a = judge.assess("Q?", listOf("Q?", "Parent."), "Child.", Polarity.ATTACK)
        // quality is the construction Noul alone: canonical form is not scored (iteration 5)
        assertEquals(Assessment(plausibility = 0.75, strength = 0.5, quality = 0.9, relevance = 0.4), a)
        assertEquals(2, seen.size)
        val plaus = seen.single { "plausibility" in it.body["questions"]!!.jsonObject }.body
        // CRED-01: plausibility sees the claim and the question only
        assertEquals(setOf("root_question", "claim"), plaus["state"]!!.jsonObject.keys)
        assertEquals("Child.", plaus["state"]!!.jsonObject["claim"]!!.jsonPrimitive.content)
        val body = seen.single { "strength" in it.body["questions"]!!.jsonObject }.body
        assertEquals(setOf("strength", "quality", "relevant"), body["questions"]!!.jsonObject.keys)
        val state = body["state"]!!.jsonObject
        assertEquals("Parent.", state["parent_claim"]!!.jsonPrimitive.content)
        assertEquals("Child.", state["claim"]!!.jsonPrimitive.content)
        assertEquals("attacks", state["direction"]!!.jsonPrimitive.content)
        assertTrue("today" !in state.keys)
        assertEquals("noul", question(body, "quality")["type"]!!.jsonPrimitive.content)
        assertTrue("well-constructed" in question(body, "quality")["instructions"]!!.jsonPrimitive.content)
        assertTrue("`claim`" in question(body, "strength")["instructions"]!!.jsonPrimitive.content)
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
    fun `unknown triage action fails`() {
        reply(200, """{"answers":{"a0":{"type":"choice","choice":"SPLIT"},"t0":{"type":"choice","choice":"none"}}}""")
        val error = assertFailsWith<JevException> { judge.triage(ctx, listOf(Candidate("candidate", Polarity.SUPPORT))) }
        assertEquals(null, error.status)
        assertTrue("unknown triage action" in error.message!!)
    }

    @Test
    fun `IO failure is not retried`() {
        // Bound but never accept()ed: the port stays reserved to this process for the whole
        // test, so it cannot be grabbed by another process the way a freed port could (seen
        // once locally). The connection sits in the backlog and the request times out instead
        // of being refused, which is still an IOException the client wraps the same way.
        val stub = ServerSocket(0)
        try {
            val delays = mutableListOf<Duration>()
            val offline = JevJudge(
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:${stub.localPort}",
                backoff = Duration.ZERO,
                requestTimeout = Duration.ofMillis(300),
                sleeper = { delays += it },
            )
            val error = assertFailsWith<JevException> { offline.relevance(ctx) }
            assertEquals(null, error.status)
            assertTrue(delays.isEmpty())
            // No backoff sleep alone does not prove one attempt: a retry that skips the sleeper
            // would pass the line above. Each attempt leaves one connection in the backlog.
            stub.soTimeout = 500
            var attempts = 0
            while (true) {
                try {
                    stub.accept().close()
                    attempts++
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            assertEquals(1, attempts, "IO failure must be attempted exactly once")
        } finally {
            stub.close()
        }
    }

    @Test
    fun `retry attempt cap cannot exceed four`() {
        assertFailsWith<IllegalArgumentException> { judge(maxAttempts = 0, sleeper = { _ -> }) }
        assertFailsWith<IllegalArgumentException> { judge(maxAttempts = 5, sleeper = { _ -> }) }
    }

    // ------------------------------------------------------------ SPEC §12 usage

    private fun <T> recording(block: () -> T): Pair<T, List<CallUsage>> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<CallUsage>()
        return Usage.within({ seen += it }, block) to seen
    }

    @Test
    fun `every successful request reports its usage, retried ones do not`() {
        reply(429, """{"error":"rate limited"}""")
        plausibilityReply(3.0)
        val (_, usage) = recording { judge(sleeper = {}).plausibility("Q?", "C.") }
        assertEquals(listOf(CallUsage("jev", listOf("jev-1.13.0"), inputTokens = 300, outputTokens = 20)), usage)
    }

    @Test
    fun `assess reports both of its parallel requests to the caller's sink`() {
        routed["plausibility"] = """{"model":"jev-1.13.0","answers":{${plausibilityAnswers(3.0)}},
               "usage":{"input_tokens":70,"output_tokens":2}}"""
        routed["strength"] = """{"model":"jev-1.13.0","answers":{
               "strength":{"type":"score","score":2.0},"quality":{"type":"noul","noul":0.9},
               "relevant":{"type":"noul","noul":0.4}},
               "usage":{"input_tokens":500,"output_tokens":9}}"""
        val (_, usage) = recording { judge.assess("Q?", listOf("Q?", "Parent."), "Child.", Polarity.SUPPORT) }
        assertEquals(setOf(70L, 500L), usage.map { it.inputTokens }.toSet())
        assertEquals(2, usage.size)
    }

    @Test
    fun `a response without usage still answers and reports nothing`() {
        routed["plausibility"] = """{"model":"jev-1.13.0","answers":{${plausibilityAnswers(4.0)}}}"""
        val (p, usage) = recording { judge.plausibility("Q?", "C.") }
        assertEquals(1.0, p, 1e-9)
        assertTrue(usage.isEmpty())
    }
}
