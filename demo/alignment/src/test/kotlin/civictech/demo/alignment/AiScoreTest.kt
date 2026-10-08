package civictech.demo.alignment

import civictech.testkit.HttpProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AI score over HTTP: AI raters ([AiRater]) rate under `ai:<model-version>` ([RaterClass]), and
 * the one fusion chain scores that population apart from the people's, triggered only by the
 * facilitator's `POST /ai-rate`. Fake raters stand in for Jev, whose wire shape is
 * [TypeSafeJevRaterTest]'s.
 */
class AiScoreTest {

    /** A rater answering [answer] per (idea, dims) as model [model], counting its calls. */
    private class FakeRater(
        @Volatile var model: String = "fake-1",
        val answer: (Idea, Map<String, Dimension>) -> Map<String, Double>,
    ) : AiRater {
        override val name = "fake-latest"
        val calls = AtomicInteger()
        override fun rate(topicTitle: String, idea: Idea, dims: Map<String, Dimension>): AiAnswer {
            calls.incrementAndGet()
            return AiAnswer(model, answer(idea, dims))
        }
    }

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun row(aggregate: String, id: String): JsonObject? =
        parse(aggregate)["ideas"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"]!!.jsonPrimitive.content == id }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDouble()

    private fun JsonObject.ai(): JsonObject? = this["ai"] as? JsonObject

    private fun JsonObject.aiMean(dim: String): Double? = ai()?.get("byDim")?.jsonObject?.get(dim)?.jsonObject?.num("mean")

    private fun HttpProbe.awaitRow(id: String, predicate: (JsonObject) -> Boolean): JsonObject {
        val body = await(path = "/topics/t/aggregate") { b -> row(b, id)?.let(predicate) == true }
        return row(body, id)!!
    }

    /** Waits until no AI run is in flight on topic `t`. */
    private fun HttpProbe.awaitIdle() =
        await(path = "/topics") { it.contains("\"aiRunning\":false") }

    private fun aiRate(probe: HttpProbe, creator: String = "cat") =
        probe.postJson("""{"creator":"$creator"}""", "/topics/t/ai-rate")

    /** Topic `t` by `cat`: impact (value), effort (cost, described); ideas `a`, `b`. */
    private fun seed(probe: HttpProbe) {
        assertEquals(
            200,
            probe.postJson(
                """{"creator":"cat","title":"T","dimensions":[{"name":"Impact"},""" +
                    """{"name":"Effort","direction":"cost","description":"team-weeks to ship"}]}""",
                "/topics",
            ).statusCode(),
        )
        assertEquals(200, probe.postJson("""{"participant":"ann","title":"A"}""", "/topics/t/ideas").statusCode())
        assertEquals(200, probe.postJson("""{"participant":"bob","title":"B"}""", "/topics/t/ideas").statusCode())
    }

    private fun withApp(journal: Path? = null, raters: List<AiRater>, body: (HttpProbe) -> Unit) {
        val app = AlignmentApp(port = 0, journalPath = journal, aiRaters = raters).start()
        try {
            HttpProbe("http://localhost:${app.boundPort}").use(body)
        } finally {
            app.stop()
        }
    }

    /** Rates every idea 8 on impact and 2 on effort, abstaining on idea `b`'s effort. */
    private fun jev() = FakeRater { idea, dims ->
        dims.keys.associateWith { if (it == "impact") 8.0 else 2.0 }.filterKeys { !(idea.id == "b" && it == "effort") }
    }

    @Test
    fun `ai-rate is facilitator-only and refused with no rater configured`() {
        withApp(raters = listOf(jev())) { probe ->
            seed(probe)
            assertEquals(403, aiRate(probe, creator = "ann").statusCode())
        }
        withApp(raters = emptyList()) { probe ->
            seed(probe)
            assertEquals(409, aiRate(probe).statusCode())
            assertTrue("\"aiRaters\":[]" in probe.get("/topics").body())
        }
    }

    /** The `ai:` prefix is what makes a rating an AI rating, so no person may take it. */
    @Test
    fun `a person cannot rate, judge or create under an ai name`() = withApp(raters = emptyList()) { probe ->
        seed(probe)
        val rate = probe.postJson("""{"participant":"ai:jev-1.13.0","idea":"a","dim":"impact","value":9}""", "/topics/t/rate")
        assertEquals(400, rate.statusCode(), rate.body())
        assertTrue("reserved for AI raters" in rate.body())
        assertEquals(
            400,
            probe.postJson("""{"participant":"ai:x","dim":"impact","a":"a","b":"b","outcome":"a"}""", "/topics/t/judge").statusCode(),
        )
        assertEquals(400, probe.postJson("""{"creator":"ai:x","title":"U","dimensions":[{"name":"D"}]}""", "/topics").statusCode())
        assertTrue("ai:" !in probe.get("/state").body(), "nothing was written")
    }

    @Test
    fun `an AI run fills the AI score beside the human one, abstentions stay unrated, and disagreement diverges`() {
        val rater = jev()
        withApp(raters = listOf(rater)) { probe ->
            seed(probe)
            assertEquals(200, probe.postJson("""{"participant":"ann","idea":"a","dim":"impact","value":3}""", "/topics/t/rate").statusCode())
            assertEquals(200, probe.postJson("""{"participant":"ann","idea":"a","dim":"effort","value":2}""", "/topics/t/rate").statusCode())

            assertEquals(200, aiRate(probe).statusCode())
            val a = probe.awaitRow("a") { it.ai()?.num("score") != null }
            assertEquals(4.0, a.ai()!!.num("score"), "AI: impact 8 ÷ effort 2")
            assertEquals(1.5, a.num("score"), "human: impact 3 ÷ effort 2, untouched by the AI")
            assertEquals(1, a["raters"]!!.jsonPrimitive.content.toInt(), "the AI is not a human rater: $a")
            assertEquals(1, a.ai()!!["raters"]!!.jsonPrimitive.content.toInt())
            assertEquals(listOf("impact"), a["diverges"]!!.jsonArray.map { it.jsonPrimitive.content }, "|3 - 8| >= 2: $a")
            assertEquals("false", a["split"]!!.jsonPrimitive.content)

            // b: the abstention on effort is absence, so its AI score names the missing cost side
            val b = probe.awaitRow("b") { it.aiMean("impact") != null }
            assertEquals(null, b.aiMean("effort"), "$b")
            assertEquals(JsonNull, b.ai()!!["score"], "a cost topic with no cost rating has no score: $b")

            assertEquals(listOf("fake-1"), a.ai()!!["models"]!!.jsonArray.map { it.jsonPrimitive.content })
            val state = parse(probe.get("/state").body())
            val stored = state["ratings"]!!.jsonArray.map { it.jsonObject["participant"]!!.jsonPrimitive.content }
            assertEquals(3, stored.count { it == "ai:fake-1" }, "a×2 + b×1, stored under the versioned name: $state")
            assertEquals(2, stored.count { it == "ann" })
            assertEquals(1, parse(probe.get("/topics/t/aggregate").body())["participants"]!!.jsonPrimitive.content.toInt(), "people only")

            // a second run asks its first idea (a) to learn the serving version, then only ideas
            // with a hole: b still has its abstained effort slot
            probe.awaitIdle()
            val callsBefore = rater.calls.get()
            assertEquals(200, aiRate(probe).statusCode())
            probe.awaitIdle()
            assertEquals(callsBefore + 2, rater.calls.get(), "the version probe on a, then b")
        }
    }

    @Test
    fun `editing an idea or a dimension's description clears exactly the AI ratings it invalidated`() {
        var impact = 8.0
        val rater = FakeRater { _, dims -> dims.keys.associateWith { if (it == "impact") impact else 2.0 } }
        withApp(raters = listOf(rater)) { probe ->
            seed(probe)
            aiRate(probe)
            probe.awaitRow("b") { it.aiMean("effort") != null }
            probe.awaitIdle()

            // an idea edit clears that idea's AI side only
            assertEquals(200, probe.putJson("""{"creator":"cat","description":"now with detail"}""", "/topics/t/ideas/a").statusCode())
            probe.awaitRow("a") { it.ai() == null }
            assertEquals(8.0, probe.awaitRow("b") { true }.aiMean("impact"), "b was not edited")

            // a description edit clears that dimension on every idea
            assertEquals(
                200,
                probe.putJson("""{"creator":"cat","description":"people helped per month"}""", "/topics/t/dimensions/impact").statusCode(),
            )
            probe.awaitRow("b") { it.aiMean("impact") == null && it.aiMean("effort") == 2.0 }
            assertTrue("\"description\":\"people helped per month\"" in probe.get("/topics").body())

            // the next run re-asks with the new question
            impact = 6.0
            aiRate(probe)
            probe.awaitRow("a") { it.aiMean("impact") == 6.0 && it.aiMean("effort") == 2.0 }
            probe.awaitRow("b") { it.aiMean("impact") == 6.0 }
        }
    }

    @Test
    fun `a failing rater writes nothing and the run carries on`() {
        val rater = FakeRater { idea, dims ->
            if (idea.id == "a") error("HTTP 500") else dims.keys.associateWith { 5.0 }
        }
        withApp(raters = listOf(rater)) { probe ->
            seed(probe)
            aiRate(probe)
            probe.awaitRow("b") { it.aiMean("impact") == 5.0 }
            probe.awaitIdle()
            assertEquals(null, row(probe.get("/topics/t/aggregate").body(), "a")!!.ai())
        }
    }

    @Test
    fun `AI ratings and dimension descriptions survive a restart, and the restart asks nothing`() {
        val journal = createTempDirectory("ai-score")
        withApp(journal, listOf(jev())) { probe ->
            seed(probe)
            aiRate(probe)
            probe.awaitRow("a") { it.ai()?.num("score") != null }
            probe.awaitIdle()
        }
        val rater = jev()
        withApp(journal, listOf(rater)) { probe ->
            val a = probe.awaitRow("a") { it.ai()?.num("score") != null }
            assertEquals(4.0, a.ai()!!.num("score"))
            assertTrue("\"description\":\"team-weeks to ship\"" in probe.get("/topics").body())
            assertEquals(0, rater.calls.get(), "a restart replays AI ratings, it does not re-ask")
        }
    }

    /**
     * The model version is part of the rater's identity: after an upgrade the new version rates
     * every idea afresh as a second rater, the old version's ratings stay, and the AI score
     * aggregates both.
     */
    @Test
    fun `two versions of one model are two concurrent raters, aggregated together`() {
        var impact = 8.0
        val rater = FakeRater(model = "fake-1") { _, dims -> dims.keys.associateWith { if (it == "impact") impact else 2.0 } }
        withApp(raters = listOf(rater)) { probe ->
            seed(probe)
            aiRate(probe)
            probe.awaitRow("b") { it.aiMean("impact") == 8.0 }
            probe.awaitIdle()
            assertEquals(2, rater.calls.get())

            // a re-run with the version unchanged asks only its version probe: every slot of fake-1
            // is filled, and the probe's answer re-rolls nothing
            aiRate(probe)
            probe.awaitIdle()
            assertEquals(3, rater.calls.get())

            // the provider upgrades: the same configured rater now answers as fake-2
            rater.model = "fake-2"
            impact = 4.0
            aiRate(probe)
            val a = probe.awaitRow("a") { it.ai()?.get("raters")?.jsonPrimitive?.content == "2" && it.aiMean("impact") == 6.0 }
            assertEquals(listOf("fake-1", "fake-2"), a.ai()!!["models"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(2L, a.ai()!!["byDim"]!!.jsonObject["impact"]!!.jsonObject["n"]!!.jsonPrimitive.content.toLong(), "(8 + 4) / 2")
            probe.awaitRow("b") { it.aiMean("impact") == 6.0 }
        }
    }
}
