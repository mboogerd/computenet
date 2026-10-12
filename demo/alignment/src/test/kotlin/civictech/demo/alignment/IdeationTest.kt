package civictech.demo.alignment

import civictech.testkit.HttpProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LLM ideation (Ideation.kt): the answer parser, the prompt, the Claude→Codex
 * turn loop with Jev's gate and stop rules, and the HTTP surface. Proposers and
 * judge are fakes — no CLI, no TypeSafe call.
 */
class IdeationTest {

    // ── parser and prompt ─────────────────────────────────────────────────

    @Test
    fun `parseIdeas tolerates prose and fences, skips untitled entries and caps`() {
        val text = """
            Sure, here you go:
            ```json
            [{"title":" Bike racks ","description":" cheap "},{"title":""},{"description":"no title"},
             {"title":"Shuttle bus"},{"title":"Third"}]
            ```
        """.trimIndent()
        assertEquals(listOf(ProposedIdea("Bike racks", "cheap"), ProposedIdea("Shuttle bus", "")), parseIdeas(text, 2))
        // an array of strings (an argument-shaped answer) is not an idea list
        assertFailsWith<IllegalArgumentException> { parseIdeas("""["a", "b"]""", 3) }
        assertFailsWith<IllegalArgumentException> { parseIdeas("no json here", 3) }
    }

    @Test
    fun `the prompt carries the topic, dimension anchors, existing ideas and the bound`() {
        val ctx = IdeationContext(
            "Reduce office commute",
            listOf(Dimension("Impact", "none", "huge"), Dimension("Effort")),
            listOf(ProposedIdea("Bike racks", "covered parking")),
        )
        val p = CliIdeaProposer.prompt(ctx, 3)
        for (s in listOf("Reduce office commute", "Impact (1 = none, 9 = huge)", "- Effort", "Bike racks: covered parking", "at most 3")) {
            assertTrue(s in p, "missing '$s' in:\n$p")
        }
    }

    @Test
    fun `claudeResult reads the envelope and fails on is_error`() {
        assertEquals("[]", CliIdeaProposer.claudeResult("""{"type":"result","result":"[]","is_error":false}"""))
        assertEquals("plain", CliIdeaProposer.claudeResult("plain"))
        assertFailsWith<IllegalStateException> { CliIdeaProposer.claudeResult("""{"type":"result","result":"x","is_error":true}""") }
    }

    // ── Jev client retry (the HttpClient's config, not judge code) ────────

    /** A local stand-in for `/v1/systemone` answering [statuses] in turn, then 200 with one `missing` noul. */
    private fun withJevStub(vararg statuses: Int, body: (url: String, hits: () -> Int) -> Unit) {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/systemone") { ex ->
            val n = hits.getAndIncrement()
            val status = statuses.getOrElse(n) { 200 }
            val reply = if (status == 200) """{"answers":{"missing":{"type":"noul","noul":0.25}}}"""
            else """{"detail":{"error_type":"model_unavailable","message":"x"}}"""
            ex.sendResponseHeaders(status, reply.length.toLong())
            ex.responseBody.use { it.write(reply.toByteArray()) }
        }
        server.start()
        try {
            body("http://127.0.0.1:${server.address.port}", hits::get)
        } finally {
            server.stop(0)
        }
    }

    private fun fastClient(maxRetries: Int) = jevHttpClient(
        maxRetries = maxRetries,
        baseDelay = java.time.Duration.ofMillis(5),
        maxDelay = java.time.Duration.ofMillis(20),
        jitter = java.time.Duration.ZERO,
    )

    private val ctx = IdeationContext("t", listOf(Dimension("Impact")), emptyList())

    @Test
    fun `the jev client retries transient statuses and gives up after maxRetries`() {
        withJevStub(503, 529) { url, hits ->
            val judge = JevIdeaJudge("key", baseUrl = url, http = fastClient(maxRetries = 3))
            assertEquals(0.75, judge.saturation(ctx), 1e-9)
            assertEquals(3, hits())
        }
        withJevStub(503, 503, 503, 503) { url, hits ->
            val judge = JevIdeaJudge("key", baseUrl = url, http = fastClient(maxRetries = 2))
            val e = assertFailsWith<JevException> { judge.saturation(ctx) }
            assertEquals(503, e.status)
            assertTrue("model_unavailable" in e.message!!, e.message)
            assertEquals(3, hits(), "one attempt plus maxRetries")
        }
        withJevStub(400) { url, hits ->
            val judge = JevIdeaJudge("key", baseUrl = url, http = fastClient(maxRetries = 3))
            assertEquals(400, assertFailsWith<JevException> { judge.saturation(ctx) }.status)
            assertEquals(1, hits(), "a client error is not retried")
        }
    }

    // ── the loop ──────────────────────────────────────────────────────────

    private class FakeProposer(override val id: String, val answer: (IdeationContext, Int) -> List<ProposedIdea>) : IdeaProposer {
        val seen = ArrayList<IdeationContext>()
        override fun propose(ctx: IdeationContext, max: Int): List<ProposedIdea> {
            seen += ctx
            return answer(ctx, max)
        }
    }

    private class FakeJudge(
        val verdict: (ProposedIdea) -> IdeaAction = { IdeaAction.ADD },
        val saturation: Double = 0.0,
        val triageFails: Boolean = false,
    ) : IdeaJudge {
        var triageCalls = 0
        override fun triage(ctx: IdeationContext, candidates: List<ProposedIdea>): List<IdeaVerdict> {
            triageCalls++
            if (triageFails) throw JevException(500, "boom")
            return candidates.map { IdeaVerdict(verdict(it), "because") }
        }
        override fun saturation(ctx: IdeationContext) = saturation
    }

    /** A board of (idea, proposer) pairs driven by [IdeationRun]. */
    private class Board {
        val ideas = ArrayList<Pair<ProposedIdea, String>>()
        fun ctx() = IdeationContext("topic", listOf(Dimension("Impact")), synchronized(ideas) { ideas.map { it.first } })
        fun add(i: ProposedIdea, p: String) = synchronized(ideas) {
            if (ideas.any { slug(it.first.title) == slug(i.title) }) false else ideas.add(i to p)
        }
    }

    private fun run(
        board: Board,
        proposers: List<IdeaProposer>,
        judge: IdeaJudge,
        budget: Int = 12,
        config: IdeationRun.Config = IdeationRun.Config(),
        onChange: () -> Unit = {},
    ): IdeationStatus {
        val status = IdeationStatus(budget)
        IdeationRun(proposers, judge, board::ctx, board::add, onChange, status, config).run()
        return status
    }

    /** A proposer that answers fresh numbered ideas every call. */
    private fun counting(id: String): FakeProposer {
        var n = 0
        return FakeProposer(id) { _, max -> List(max) { ProposedIdea("$id idea ${++n}") } }
    }

    @Test
    fun `claude goes first and codex sees what claude added`() {
        val board = Board()
        val claude = counting("claude")
        val codex = counting("codex")
        val status = run(board, listOf(claude, codex), FakeJudge(saturation = 0.9))
        assertEquals(1, status.round)
        assertEquals("Jev judged the topic covered", status.stopped)
        assertEquals(listOf("claude", "claude", "claude", "codex", "codex", "codex"), board.ideas.map { it.second })
        assertTrue(codex.seen.single().ideas.map { it.title }.containsAll(listOf("claude idea 1", "claude idea 3")))
        assertEquals(6, status.added)
        assertEquals(false, status.running)
    }

    @Test
    fun `jev's non-ADD verdicts and exact repeats are held back, not added`() {
        val board = Board()
        board.add(ProposedIdea("Existing"), "ann")
        val claude = FakeProposer("claude") { _, _ -> listOf(ProposedIdea("existing."), ProposedIdea("Good"), ProposedIdea("Vague")) }
        val judge = FakeJudge(verdict = { if (it.title == "Vague") IdeaAction.DROP else IdeaAction.ADD }, saturation = 1.0)
        val status = run(board, listOf(claude), judge)
        assertEquals(listOf("Existing", "Good"), board.ideas.map { it.first.title })
        assertEquals(
            listOf("existing." to IdeaAction.DUPLICATE, "Vague" to IdeaAction.DROP),
            status.held.map { it.title to it.action },
        )
        assertEquals("claude", status.held[1].proposer)
    }

    @Test
    fun `the run stops on budget, an empty round, and the round limit`() {
        val budget = run(Board(), listOf(counting("claude"), counting("codex")), FakeJudge(), budget = 4)
        assertEquals("idea budget spent", budget.stopped)
        assertEquals(4, budget.added)

        val empty = run(Board(), listOf(FakeProposer("claude") { _, _ -> emptyList() }), FakeJudge())
        assertEquals("the last round added nothing", empty.stopped)

        val limit = run(Board(), listOf(counting("claude")), FakeJudge(), budget = 30, config = IdeationRun.Config(ideasPerTurn = 1, maxRounds = 2))
        assertEquals("round limit reached", limit.stopped)
        assertEquals(2, limit.added)
    }

    @Test
    fun `cancel stops the run before the next turn`() {
        val status = IdeationStatus(30)
        val codex = counting("codex")
        val claude = FakeProposer("claude") { _, _ -> status.cancelled = true; listOf(ProposedIdea("one")) }
        val board = Board()
        IdeationRun(listOf(claude, codex), FakeJudge(), board::ctx, board::add, {}, status).run()
        assertEquals("stopped by you", status.stopped)
        assertTrue(codex.seen.isEmpty(), "codex must not take a turn after cancel")
        assertEquals(listOf("one"), board.ideas.map { it.first.title }, "the in-flight turn still lands")
    }

    @Test
    fun `a failing proposer is recorded and the other still takes its turn`() {
        val board = Board()
        val claude = FakeProposer("claude") { _, _ -> error("cli down") }
        val status = run(board, listOf(claude, counting("codex")), FakeJudge(saturation = 1.0))
        assertEquals(3, status.added)
        assertTrue(status.error!!.startsWith("claude:"), status.error)
    }

    @Test
    fun `a failing triage holds the batch back instead of adding unjudged ideas`() {
        val board = Board()
        val status = run(board, listOf(counting("claude")), FakeJudge(triageFails = true))
        assertTrue(board.ideas.isEmpty())
        assertEquals(3, status.held.size)
        assertEquals("the last round added nothing", status.stopped)
        assertTrue(status.error!!.startsWith("jev triage:"), status.error)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun withApp(proposers: List<IdeaProposer>, judge: IdeaJudge?, body: (HttpProbe) -> Unit) {
        val app = AlignmentApp(port = 0, ideaProposers = proposers, ideaJudge = judge).start()
        try {
            HttpProbe("http://localhost:${app.boundPort}").use { probe ->
                val created = probe.postJson("""{"creator":"cat","title":"T","dimensions":[{"name":"Impact"}]}""", "/topics")
                assertEquals(200, created.statusCode(), created.body())
                body(probe)
            }
        } finally {
            app.stop()
        }
    }

    private fun ideation(state: String): JsonObject? = parse(state)["ideation"]?.jsonObject?.get("t")?.jsonObject

    @Test
    fun `ideate is creator-only, needs a judge, and lands attributed ideas plus a held-back bucket`() {
        withApp(listOf(counting("claude")), null) { probe ->
            assertEquals(503, probe.postJson("""{"creator":"cat"}""", "/topics/t/ideate").statusCode())
        }
        val gate = CountDownLatch(1)
        val claude = FakeProposer("claude") { _, _ ->
            gate.await(5, TimeUnit.SECONDS)
            listOf(ProposedIdea("Good one", "why"), ProposedIdea("Weak one"))
        }
        val judge = FakeJudge(verdict = { if (it.title == "Weak one") IdeaAction.DROP else IdeaAction.ADD }, saturation = 1.0)
        withApp(listOf(claude), judge) { probe ->
            assertEquals(403, probe.postJson("""{"creator":"bob"}""", "/topics/t/ideate").statusCode())
            assertEquals(200, probe.postJson("""{"creator":"cat"}""", "/topics/t/ideate").statusCode())
            assertEquals(409, probe.postJson("""{"creator":"cat"}""", "/topics/t/ideate").statusCode())
            gate.countDown()

            val done = probe.await { s -> ideation(s)?.get("running")?.jsonPrimitive?.content == "false" }
            val ideas = parse(done)["ideas"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("good-one" to "claude"), ideas.map { it["id"]!!.jsonPrimitive.content to it["proposer"]!!.jsonPrimitive.content })
            val held = ideation(done)!!["held"]!!.jsonArray.single().jsonObject
            assertEquals("Weak one", held["title"]!!.jsonPrimitive.content)
            assertEquals("drop", held["verdict"]!!.jsonPrimitive.content)

            assertEquals(403, probe.postJson("""{"creator":"bob"}""", "/topics/t/ideate/held/0/accept").statusCode())
            val accepted = probe.postJson("""{"creator":"cat"}""", "/topics/t/ideate/held/0/accept")
            assertEquals(200, accepted.statusCode(), accepted.body())
            val after = parse(probe.state())
            val weak = after["ideas"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "weak-one" }
            assertEquals("claude", weak["proposer"]!!.jsonPrimitive.content)
            assertTrue(ideation(probe.state())!!["held"]!!.jsonArray.isEmpty())
            assertEquals(404, probe.postJson("""{"creator":"cat"}""", "/topics/t/ideate/held/0/accept").statusCode())
        }
    }
}
