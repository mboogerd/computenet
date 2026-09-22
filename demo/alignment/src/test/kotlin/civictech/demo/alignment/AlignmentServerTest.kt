package civictech.demo.alignment

import civictech.testkit.HttpProbe
import civictech.testkit.boundedHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The alignment app over HTTP (computenet-sigl0.2): live weighted ranking,
 * input validation, creator-only weights, the bias-safe `/me` view, the page
 * smoke, `/events` parity and journal-replay restart. Worked numbers are the
 * feature's examples (computenet-sigl0 Design); the read model is async, so
 * every assertion on it is an [HttpProbe.await] on the asserted state itself.
 */
class AlignmentServerTest {

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** The `/aggregate` row for [id], or null while it is not listed. */
    private fun row(aggregate: String, id: String): JsonObject? =
        parse(aggregate)["ideas"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["id"]!!.jsonPrimitive.content == id }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDouble()

    private fun near(want: Double, got: Double?) = got != null && abs(want - got) < 1e-3

    private fun HttpProbe.awaitRow(id: String, predicate: (JsonObject) -> Boolean): JsonObject {
        val body = await(path = "/topics/t/aggregate") { b -> row(b, id)?.let(predicate) == true }
        return row(body, id)!!
    }

    private fun order(aggregate: String): List<String> =
        parse(aggregate)["ideas"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun rate(probe: HttpProbe, who: String, idea: String, dim: String, value: String) =
        probe.postJson(
            """{"participant":"$who","idea":"$idea","dim":"$dim","value":$value}""",
            "/topics/t/rate",
        )

    private fun judge(probe: HttpProbe, who: String, dim: String, a: String, b: String, outcome: String): String {
        val r = probe.postJson(
            """{"participant":"$who","dim":"$dim","a":"$a","b":"$b","outcome":"$outcome"}""",
            "/topics/t/judge",
        )
        assertEquals(200, r.statusCode(), r.body())
        return r.body()
    }

    /** [who]'s own rating of [idea] on [dim] in their `/me` view, null when unrated. */
    private fun myRating(me: String, idea: String, dim: String): Double? =
        parse(me)["ideas"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == idea }
            .let { it["ratings"]!!.jsonObject.num(dim) }

    /** Topic `t` by `cat`: impact (w 2.0), effort (w 1.0); ideas `a` and `b`. */
    private fun seed(probe: HttpProbe) {
        val created = probe.postJson(
            """{"creator":"cat","title":"T","dimensions":[{"name":"Impact","weight":2.0},{"name":"Effort"}]}""",
            "/topics",
        )
        assertEquals(200, created.statusCode(), created.body())
        assertEquals("""{"id":"t"}""", created.body())
        assertEquals(200, probe.postJson("""{"participant":"ann","title":"A"}""", "/topics/t/ideas").statusCode())
        assertEquals(
            200,
            probe.postJson("""{"participant":"bob","title":"B","description":"the b idea"}""", "/topics/t/ideas").statusCode(),
        )
    }

    private fun withApp(journal: Path? = null, body: (AlignmentApp, HttpProbe) -> Unit) {
        val app = AlignmentApp(port = 0, journalPath = journal).start()
        try {
            HttpProbe("http://localhost:${app.boundPort}").use { body(app, it) }
        } finally {
            app.stop()
        }
    }

    private fun tmpJournal(): Path = createTempDirectory("alignment").resolve("journal.jsonl")

    @Test
    fun `ratings and creator weights move the live ranking through the worked examples`() = withApp(tmpJournal()) { _, probe ->
        seed(probe)
        val topics = probe.get("/topics").body()
        assertTrue(
            """"ideas":"everyone","boardVisibility":"after-rating","revealed":false,"gutCheck":false,"dotBudget":3,"dimensions":[""" +
                """{"id":"effort","name":"Effort","weight":1.0000,"direction":"value","lowLabel":"","highLabel":""},""" +
                """{"id":"impact","name":"Impact","weight":2.0000,"direction":"value","lowLabel":"","highLabel":""}]""" in topics,
            topics,
        )

        // ann a/impact 8: one rated dimension scores alone (no other dim rated)
        assertEquals(200, rate(probe, "ann", "a", "impact", "8").statusCode())
        var a = probe.awaitRow("a") { near(8.0, it.num("score")) }
        assertEquals(1, a["rank"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", a["split"]!!.jsonPrimitive.content)
        val impact = a["byDim"]!!.jsonObject["impact"]!!.jsonObject
        assertEquals("1", impact["n"]!!.jsonPrimitive.content)
        assertTrue(near(8.0, impact.num("mean")) && near(0.0, impact.num("stdev")) && near(8.0, impact.num("contribution")), "$a")
        assertEquals(setOf("impact"), a["byDim"]!!.jsonObject.keys, "only the rated dimension: $a")
        val b0 = row(probe.get("/topics/t/aggregate").body(), "b")!!
        assertEquals(JsonNull, b0["score"], "unrated b: $b0")
        assertEquals(JsonNull, b0["rank"], "unrated b: $b0")

        // bob a/impact 2: mean 5.0, stdev 4.243 → split
        rate(probe, "bob", "a", "impact", "2")
        a = probe.awaitRow("a") { near(5.0, it.num("score")) }
        assertEquals("true", a["split"]!!.jsonPrimitive.content, "$a")
        assertTrue(near(4.2426, a["byDim"]!!.jsonObject["impact"]!!.jsonObject.num("stdev")), "$a")

        // ann a/effort 3: (2·5 + 1·3)/3
        rate(probe, "ann", "a", "effort", "3")
        a = probe.awaitRow("a") { near(13.0 / 3.0, it.num("score")) }
        assertTrue(near(10.0 / 3.0, a["byDim"]!!.jsonObject["impact"]!!.jsonObject.num("contribution")), "$a")
        assertTrue(near(1.0, a["byDim"]!!.jsonObject["effort"]!!.jsonObject.num("contribution")), "$a")

        // carl b/impact 4 → b 4.0 ranks below a 4.333
        rate(probe, "carl", "b", "impact", "4")
        var agg = probe.await(path = "/topics/t/aggregate") { near(4.0, row(it, "b")?.num("score")) }
        assertEquals(listOf("a", "b"), order(agg), agg)

        // creator re-weights effort 4.0: a = (2·5 + 4·3)/6 = 3.667 < b → b overtakes in the next frame
        val put = probe.putJson("""{"creator":"cat","dim":"effort","weight":4.0}""", "/topics/t/weights")
        assertEquals(200, put.statusCode(), put.body())
        agg = probe.await(path = "/topics/t/aggregate") { near(11.0 / 3.0, row(it, "a")?.num("score")) }
        assertEquals(listOf("b", "a"), order(agg), agg)
        a = row(agg, "a")!!
        assertTrue(near(10.0 / 6.0, a["byDim"]!!.jsonObject["impact"]!!.jsonObject.num("contribution")), "$a")
        assertTrue(near(2.0, a["byDim"]!!.jsonObject["effort"]!!.jsonObject.num("contribution")), "$a")
        assertEquals(1, row(agg, "b")!!["rank"]!!.jsonPrimitive.content.toInt())
        // the /events frame carries the same ranking (the SSE frame is stateJson, as /state)
        val state = probe.await { s -> near(11.0 / 3.0, row(parse(s)["aggregates"]!!.jsonObject["t"].toString(), "a")?.num("score")) }
        assertEquals(listOf("b", "a"), order(parse(state)["aggregates"]!!.jsonObject["t"].toString()))

        // non-creator PUT → 403 and nothing moves
        val denied = probe.putJson("""{"creator":"ann","dim":"effort","weight":1.0}""", "/topics/t/weights")
        assertEquals(403, denied.statusCode(), denied.body())
        assertEquals(403, probe.putJson("""{"dim":"effort","weight":1.0}""", "/topics/t/weights").statusCode())
        val after = probe.get("/topics/t/aggregate").body()
        assertTrue(near(11.0 / 3.0, row(after, "a")?.num("score")), after)
        assertTrue(""""weights":{"effort":4.0000,"impact":2.0000}""" in after, after)

        // retractions: value null and "retract": true both remove → back to ann's impact alone
        assertEquals(200, rate(probe, "ann", "a", "effort", "null").statusCode())
        assertEquals(
            200,
            probe.postJson("""{"participant":"bob","idea":"a","dim":"impact","retract":true}""", "/topics/t/rate").statusCode(),
        )
        a = probe.awaitRow("a") { near(8.0, it.num("score")) && it["split"]!!.jsonPrimitive.content == "false" }
        assertEquals(1, a["rank"]!!.jsonPrimitive.content.toInt())

        // ann's last rating goes → a is unscored, listed after the ranked b
        rate(probe, "ann", "a", "impact", "null")
        agg = probe.await(path = "/topics/t/aggregate") { row(it, "a")?.get("score") == JsonNull }
        assertEquals(listOf("b", "a"), order(agg), agg)
        assertTrue(
            """"rank":null,"id":"a","title":"A","score":null,"override":null,"split":false,"ratings":0,"byDim":{}""" in agg,
            agg,
        )
    }

    @Test
    fun `invalid ratings, non-creators and unknown topics are refused and change nothing`() = withApp { _, probe ->
        seed(probe)
        rate(probe, "ann", "a", "impact", "8")
        probe.awaitRow("a") { near(8.0, it.num("score")) }
        // set BEFORE the snapshot, so the block below stays pure refusals
        assertEquals(200, probe.putJson("""{"creator":"cat","ideas":"facilitator"}""", "/topics/t/policy").statusCode())
        val before = probe.state()
        assertTrue(""""ideas":"facilitator"""" in before, before)

        for (bad in listOf("0", "10", "0.99", "9.01", "\"x\"", "\"5\"", "true")) {
            val r = rate(probe, "ann", "a", "impact", bad)
            assertEquals(400, r.statusCode(), "value $bad: ${r.body()}")
            assertTrue(""""error":""" in r.body(), r.body())
        }
        assertEquals(400, probe.postJson("""{"participant":"ann","idea":"a","dim":"impact"}""", "/topics/t/rate").statusCode())
        assertEquals(400, rate(probe, "ann", "ghost", "impact", "5").statusCode(), "unknown idea")
        assertEquals(400, rate(probe, "ann", "a", "ghost", "5").statusCode(), "unknown dim")
        assertEquals(400, rate(probe, "", "a", "impact", "5").statusCode(), "empty participant")
        assertEquals(400, rate(probe, "x".repeat(41), "a", "impact", "5").statusCode(), "participant > 40 chars")
        assertEquals(400, probe.postJson("not json", "/topics/t/rate").statusCode())

        assertEquals(403, probe.putJson("""{"creator":"ann","dim":"impact","weight":3.0}""", "/topics/t/weights").statusCode())
        assertEquals(403, probe.postJson("""{"creator":"ann","name":"Cost"}""", "/topics/t/dimensions").statusCode())
        assertEquals(403, probe.delete("/topics/t/dimensions/impact?creator=ann").statusCode())
        for (w in listOf("0", "-1", "\"2\"", "null")) {
            assertEquals(
                400,
                probe.putJson("""{"creator":"cat","dim":"impact","weight":$w}""", "/topics/t/weights").statusCode(),
                "weight $w",
            )
        }
        assertEquals(404, probe.putJson("""{"creator":"cat","dim":"ghost","weight":3.0}""", "/topics/t/weights").statusCode())

        assertEquals(
            404,
            probe.postJson("""{"participant":"ann","idea":"a","dim":"impact","value":5}""", "/topics/nope/rate").statusCode(),
        )
        assertEquals(404, probe.get("/topics/nope/aggregate").statusCode())
        assertEquals(404, probe.get("/topics/nope/me?participant=ann").statusCode())
        assertEquals(404, probe.delete("/topics/t/ideas/ghost").statusCode())

        // facilitator-only settings and idea edits (computenet-k1d4g-D6): 403 for anyone but the creator
        for (body in listOf(
            """{"creator":"ann","weight":3.0}""",
            """{"creator":"ann","direction":"cost"}""",
            """{"creator":"ann","lowLabel":"tiny"}""",
            """{"weight":3.0}""",
        )) {
            assertEquals(403, probe.putJson(body, "/topics/t/dimensions/impact").statusCode(), body)
        }
        assertEquals(403, probe.putJson("""{"creator":"ann","ideas":"everyone"}""", "/topics/t/policy").statusCode())
        assertEquals(403, probe.putJson("""{"creator":"ann","boardVisibility":"after-reveal"}""", "/topics/t/policy").statusCode())
        assertEquals(403, probe.postJson("""{"creator":"ann"}""", "/topics/t/reveal").statusCode())
        assertEquals(403, probe.postJson("""{}""", "/topics/t/reveal").statusCode())
        assertEquals(403, probe.putJson("""{"creator":"ann","title":"A2"}""", "/topics/t/ideas/a").statusCode())
        assertEquals(403, probe.delete("/topics/t/ideas/a?creator=ann").statusCode())
        assertEquals(403, probe.delete("/topics/t/ideas/a").statusCode())
        // ann proposed `a` but is not the facilitator: under the facilitator policy she may not add
        assertEquals(403, probe.postJson("""{"participant":"ann","title":"Z"}""", "/topics/t/ideas").statusCode())
        // malformed facilitator bodies: 400
        for (body in listOf(
            """{"creator":"cat","direction":"sideways"}""",
            """{"creator":"cat","direction":1}""",
            """{"creator":"cat","weight":0}""",
            """{"creator":"cat","lowLabel":"${"x".repeat(81)}"}""",
            """{"creator":"cat","highLabel":7}""",
            """{"creator":"cat"}""",
        )) {
            assertEquals(400, probe.putJson(body, "/topics/t/dimensions/impact").statusCode(), body)
        }
        assertEquals(404, probe.putJson("""{"creator":"cat","weight":3.0}""", "/topics/t/dimensions/ghost").statusCode())
        for (body in listOf(
            """{"creator":"cat","ideas":"anyone"}""",
            """{"creator":"cat","boardVisibility":"never"}""",
            """{"creator":"cat"}""",
        )) {
            assertEquals(400, probe.putJson(body, "/topics/t/policy").statusCode(), body)
        }
        assertEquals(400, probe.putJson("""{"creator":"cat"}""", "/topics/t/ideas/a").statusCode(), "no field")
        assertEquals(400, probe.putJson("""{"creator":"cat","title":""}""", "/topics/t/ideas/a").statusCode(), "empty title")
        assertEquals(404, probe.putJson("""{"creator":"cat","title":"G"}""", "/topics/t/ideas/ghost").statusCode())
        for (bad in listOf(
            """"ideas":"anyone","dimensions":[{"name":"d"}]""",
            """"boardVisibility":"later","dimensions":[{"name":"d"}]""",
            """"dimensions":[{"name":"d","direction":"sideways"}]""",
            """"dimensions":[{"name":"d","lowLabel":"${"x".repeat(81)}"}]""",
        )) {
            assertEquals(400, probe.postJson("""{"creator":"x","title":"u",$bad}""", "/topics").statusCode(), bad)
        }
        assertEquals(
            400,
            probe.postJson("""{"creator":"cat","name":"Cost","direction":"down"}""", "/topics/t/dimensions").statusCode(),
        )

        assertEquals(409, probe.postJson("""{"participant":"cat","title":"A!"}""", "/topics/t/ideas").statusCode())
        assertEquals(409, probe.postJson("""{"creator":"x","title":"t","dimensions":[{"name":"d"}]}""", "/topics").statusCode())
        assertEquals(400, probe.postJson("""{"creator":"x","title":"u","dimensions":[]}""", "/topics").statusCode())
        assertEquals(
            400,
            probe.postJson("""{"creator":"x","title":"u","dimensions":[{"name":"d","weight":0}]}""", "/topics").statusCode(),
        )

        // every request above was refused: the whole public state is unchanged
        assertEquals(before, probe.state())
    }

    @Test
    fun `ratings are continuous in 1 to 9 inclusive and anything else is refused`() {
        val journal = tmpJournal()
        lateinit var state: String
        withApp(journal) { _, probe -> state = continuousRatings(probe) }
        // a non-integer rating round-trips through the journal line it writes
        assertTrue(Files.readAllLines(journal).any { """"value":6.37}""" in it }, "journal carries 6.37")
        withApp(journal) { _, probe -> assertEquals(state, probe.await { it == state }) }
    }

    private fun continuousRatings(probe: HttpProbe): String {
        // epic computenet-9y79n: a rating is a finite number with 1 <= value <= 9 (held as thousandths)
        seed(probe)
        for ((v, who) in listOf("1.0" to "ann", "9.0" to "bob", "6.37" to "cy")) {
            val r = rate(probe, who, "a", "impact", v)
            assertEquals(200, r.statusCode(), "value $v: ${r.body()}")
        }
        probe.awaitRow("a") { near((1.0 + 9.0 + 6.37) / 3.0, it.num("score")) }
        // the caller's own view speaks plain numbers: 9.0 -> 9, 6.37 -> 6.37
        assertTrue(""""impact":6.37""" in probe.get("/topics/t/me?participant=cy").body())
        assertTrue(""""impact":9""" in probe.get("/topics/t/me?participant=bob").body())

        val before = probe.state()
        for (bad in listOf("0.99", "9.01", "0.9999", "9.0001", "NaN", "Infinity", "-Infinity", "1e400", "\"6.37\"", "true")) {
            val r = rate(probe, "ann", "a", "impact", bad)
            assertEquals(400, r.statusCode(), "value $bad: ${r.body()}")
        }
        assertEquals(before, probe.state(), "every refused rating changed nothing")
        return before
    }

    @Test
    fun `the me view carries only the caller's own ratings and nothing aggregate`() = withApp { _, probe ->
        seed(probe)
        rate(probe, "ann", "a", "impact", "8")
        rate(probe, "bob", "a", "impact", "2")
        rate(probe, "bob", "b", "effort", "6")
        // both participants' ratings are in: the aggregate is non-trivial
        probe.awaitRow("a") { near(5.0, it.num("score")) && it["split"]!!.jsonPrimitive.content == "true" }

        val ann = probe.get("/topics/t/me?participant=ann").body()
        assertEquals(
            """{"topic":"t","participant":"ann","ideas":[""" +
                """{"id":"a","title":"A","description":"","ratings":{"effort":null,"impact":8},"rated":1,"total":2,"dots":0},""" +
                """{"id":"b","title":"B","description":"the b idea","ratings":{"effort":null,"impact":null},"rated":0,"total":2,"dots":0}],"judgements":[]}""",
            ann,
        )
        for (leak in listOf(""""score"""", """"mean"""", """"stdev"""", """"n":""", """"contribution"""", """"split"""", "bob", "cat")) {
            assertTrue(leak !in ann, "/me must not leak $leak: $ann")
        }
        // not vacuous: bob's own view does carry his values, and ann's name does not appear in it
        val bob = probe.get("/topics/t/me?participant=bob").body()
        assertTrue(""""ratings":{"effort":null,"impact":2}""" in bob && """"ratings":{"effort":6,"impact":null}""" in bob, bob)
        assertTrue("ann" !in bob, bob)
        assertEquals(400, probe.get("/topics/t/me").statusCode(), "participant is required")
    }

    @Test
    fun `pairwise judgements derive the judge's own ratings through the rate op and stay private`() {
        val journal = tmpJournal()
        withApp(journal) { _, probe ->
            seed(probe)
            assertEquals(200, probe.postJson("""{"participant":"ann","title":"C"}""", "/topics/t/ideas").statusCode())
            rate(probe, "bob", "a", "impact", "2") // bob's slider rating: must be untouched by ann's judgements
            fun lines(op: String) = Files.readAllLines(journal).count { """"op":"$op"""" in it }

            // a > b, b > c (sent reversed: normalized to a=b, b=c, outcome "a"), a > c
            judge(probe, "ann", "impact", "a", "b", "a")
            judge(probe, "ann", "impact", "c", "b", "b")
            val third = judge(probe, "ann", "impact", "a", "c", "a")
            val me = probe.get("/topics/t/me?participant=ann").body()
            val (va, vb, vc) = listOf("a", "b", "c").map { myRating(me, it, "impact")!! }
            assertTrue(va > vb && vb > vc, "a > b > c: $me")
            assertEquals(5.0, vb, "the chain's middle idea sits at exactly 5: $me")
            assertEquals(
                """{"judged":3,"ratings":{"a":${RatingScale.format(RatingScale.toMilli(va))},"b":5,""" +
                    """"c":${RatingScale.format(RatingScale.toMilli(vc))}}}""",
                third,
                "the response's ratings are the ones written",
            )
            assertTrue(
                """"judgements":[{"dim":"impact","a":"a","b":"b","outcome":"a"},""" +
                    """{"dim":"impact","a":"a","b":"c","outcome":"a"},{"dim":"impact","a":"b","b":"c","outcome":"a"}]}""" in me,
                me,
            )
            assertEquals(null, myRating(me, "a", "effort"), "only the judged dimension is rated")
            // journaled: one judge line per judgement, each followed by the derived ratings as ordinary rate lines
            val journalLines = Files.readAllLines(journal)
            assertEquals(3, lines("judge"))
            val lastJudge = journalLines.indexOfLast { """"op":"judge"""" in it }
            assertTrue(
                journalLines.drop(lastJudge + 1).any { """"op":"rate"""" in it && """"participant":"ann"""" in it },
                "$journalLines",
            )

            // bob: ratings untouched, no judgements, no trace of ann
            val bob = probe.get("/topics/t/me?participant=bob").body()
            assertEquals(2.0, myRating(bob, "a", "impact"), bob)
            assertTrue(""""judgements":[]}""" in bob && "ann" !in bob, bob)

            // the aggregate sees ann's derived rating like any slider rating (the dataflow got the delta)
            probe.awaitRow("a") { r ->
                near((va + 2.0) / 2, r["byDim"]!!.jsonObject["impact"]?.jsonObject?.num("mean"))
            }

            // an identical judgement is a no-op: no journal line
            judge(probe, "ann", "impact", "b", "a", "b")
            assertEquals(3, lines("judge"), "an identical judgement journals nothing")

            // re-judging a pair replaces its judgement and moves the ratings
            val rejudged = judge(probe, "ann", "impact", "b", "a", "equal")
            assertTrue(rejudged.startsWith("""{"judged":3,"""), rejudged)
            val me2 = probe.get("/topics/t/me?participant=ann").body()
            assertTrue("""{"dim":"impact","a":"a","b":"b","outcome":"equal"}""" in me2, me2)
            assertTrue(myRating(me2, "a", "impact")!! < va, "a no longer beats b: $me2")

            // refusals change nothing
            val judgesBefore = lines("judge")
            val meBefore = probe.get("/topics/t/me?participant=ann").body() // the write-side view: synchronous
            for ((body, error) in listOf(
                """{"participant":"ann","dim":"nope","a":"a","b":"b","outcome":"a"}""" to "no such dimension",
                """{"participant":"ann","a":"a","b":"b","outcome":"a"}""" to "no such dimension",
                """{"participant":"ann","dim":"impact","a":"a","b":"zz","outcome":"a"}""" to "no such idea",
                """{"participant":"ann","dim":"impact","b":"b","outcome":"a"}""" to "no such idea",
                """{"participant":"ann","dim":"impact","a":"a","b":"a","outcome":"a"}""" to "a and b must differ",
                """{"participant":"ann","dim":"impact","a":"a","b":"b","outcome":"maybe"}""" to "outcome must be one of",
                """{"participant":"ann","dim":"impact","a":"a","b":"b","outcome":1}""" to "outcome must be one of",
                """{"participant":"ann","dim":"impact","a":"a","b":"b"}""" to "outcome must be one of",
                """{"dim":"impact","a":"a","b":"b","outcome":"a"}""" to "participant must be",
            )) {
                val r = probe.postJson(body, "/topics/t/judge")
                assertEquals(400, r.statusCode(), body)
                assertTrue(error in r.body(), "$body -> ${r.body()}")
            }
            assertEquals(405, probe.putJson("""{"participant":"ann"}""", "/topics/t/judge").statusCode())
            assertEquals(405, probe.get("/topics/t/judge").statusCode())
            assertEquals(400, probe.delete("/topics/t/judge?participant=ann").statusCode(), "dim is required")
            assertEquals(400, probe.delete("/topics/t/judge?participant=ann&dim=nope").statusCode())
            assertEquals(judgesBefore, lines("judge"))
            assertEquals(meBefore, probe.get("/topics/t/me?participant=ann").body())

            // clearing keeps the derived ratings as ordinary ratings
            val me3 = probe.get("/topics/t/me?participant=ann").body()
            assertEquals("""{"cleared":3}""", probe.delete("/topics/t/judge?participant=ann&dim=impact").body())
            val cleared = probe.get("/topics/t/me?participant=ann").body()
            assertTrue(""""judgements":[]}""" in cleared, cleared)
            assertEquals(me3.substringBefore(""","judgements""""), cleared.substringBefore(""","judgements""""))
            assertEquals(1, lines("unjudge"))
            assertEquals("""{"cleared":0}""", probe.delete("/topics/t/judge?participant=ann&dim=impact").body())
            assertEquals(1, lines("unjudge"), "clearing an empty set journals nothing")

            // cascades drop the involved judgements without a line and without re-fitting the others
            judge(probe, "ann", "impact", "a", "c", "a")
            judge(probe, "ann", "impact", "a", "b", "equal")
            judge(probe, "ann", "effort", "a", "b", "a")
            val beforeCascade = probe.get("/topics/t/me?participant=ann").body()
            val (judges, unjudges) = lines("judge") to lines("unjudge")
            assertEquals(200, probe.delete("/topics/t/ideas/c?creator=cat").statusCode())
            val afterIdea = probe.get("/topics/t/me?participant=ann").body()
            assertTrue(
                """"judgements":[{"dim":"effort","a":"a","b":"b","outcome":"a"},{"dim":"impact","a":"a","b":"b","outcome":"equal"}]}""" in afterIdea,
                afterIdea,
            )
            assertEquals(myRating(beforeCascade, "a", "impact"), myRating(afterIdea, "a", "impact"), "no refit")
            assertEquals(myRating(beforeCascade, "b", "impact"), myRating(afterIdea, "b", "impact"), "no refit")
            assertEquals(200, probe.delete("/topics/t/dimensions/effort?creator=cat").statusCode())
            val afterDim = probe.get("/topics/t/me?participant=ann").body()
            assertTrue(""""judgements":[{"dim":"impact","a":"a","b":"b","outcome":"equal"}]}""" in afterDim, afterDim)
            assertEquals(judges to unjudges, lines("judge") to lines("unjudge"), "cascades journal no judge/unjudge line")
        }
    }

    @Test
    fun `the page serves the two section roots, a weights root and a range input`() = withApp { _, probe ->
        val page = probe.get("/")
        assertEquals(200, page.statusCode())
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "${page.headers()}")
        assertTrue("""id="rate"""" in page.body(), "rate section root")
        assertTrue("""id="board"""" in page.body(), "board section root")
        assertTrue("""id="weights"""" in page.body(), "weights root")
        assertTrue("""id="ranking"""" in page.body(), "ranking root")
        assertTrue("""type="range"""" in page.body(), "range inputs")
        // 10mvq: Rate's pager, Board's Discuss/mode-switch/scatter roots, continuous sliders
        for (id in listOf("pager", "discuss", "boardMode", "scatter")) {
            assertTrue("""id="$id"""" in page.body(), "$id root")
        }
        assertTrue("""step="any"""" in page.body(), "continuous slider step")
        // the v2 shell (computenet-0dvra.1): landing, Setup root, identity chip, phase indicator,
        // the Board's gate root, and the colour tokens with their dark override
        for (id in listOf("topics", "setup", "identity", "phase", "gate")) {
            assertTrue("""id="$id"""" in page.body(), "$id root")
        }
        for (token in listOf("@media (prefers-color-scheme: dark)", "--value-1:", "--cost-1:", "--unrated:")) {
            assertTrue(token in page.body(), token)
        }
        // the URL is the topic selector and the chip the only name control: no free-text name, no select
        assertTrue("""id="participant"""" !in page.body(), "no free-text participant field")
        assertTrue("""id="topicSel"""" !in page.body(), "no topic select")
        assertEquals(page.body(), probe.get("/t/anything").body(), "/t/anything serves the same page")
        // the per-topic URL serves the same page for any id, known or not (computenet-k1d4g-D8)
        for (path in listOf("/t/t", "/t/does-not-exist")) {
            val t = probe.get(path)
            assertEquals(200, t.statusCode(), path)
            assertTrue(t.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), path)
            assertTrue("""id="rate"""" in t.body(), "$path: rate section root")
            assertEquals(page.body(), t.body(), path)
        }
        assertEquals(404, probe.get("/nope").statusCode())
        // every slice shares one global scope, so a top-level name declared twice silently
        // replaces the earlier one (a Setup helper once shadowed the landing's paintProgress)
        val decls = Regex("""(?m)^(?:function|let|const|var)\s+([A-Za-z_]\w*)""")
            .findAll(page.body()).map { it.groupValues[1] }.toList()
        assertEquals(emptyList(), decls.groupBy { it }.filterValues { it.size > 1 }.keys.toList(),
            "top-level script names declared more than once")
    }

    @Test
    fun `the page serves the drill-down dialog roots and its slice has no dollar sign`() = withApp { _, probe ->
        // computenet-w0i5h.2 (w0i5h-D14): the Board's split drill-down is a native dialog in the
        // Board slice; its roots are served, the slice stays template-literal-free, and the
        // per-topic URL still serves the same bytes.
        val page = probe.get("/")
        assertEquals(200, page.statusCode())
        val body = page.body()
        assertTrue("""<dialog id="drill"""" in body, "drill dialog root")
        for (id in listOf(
            "drillClose", "drillTitle", "drillDims", "drillPlot", "drillStats",
            "drillOutlier", "drillNote", "drillNoteSave", "drillNoteBy",
        )) {
            assertTrue("""id="$id"""" in body, "$id root")
        }
        assertTrue(Regex("""<textarea id="drillNote"[^>]*maxlength="4000"""").containsMatchIn(body), "note textarea capped at 4000")
        assertTrue('$' !in DRILLDOWN_VIEW, "no dollar sign in DRILLDOWN_VIEW")
        assertTrue(DRILLDOWN_VIEW in BOARD_VIEW, "the drill-down is part of the Board slice")
        assertEquals(body, probe.get("/t/anything").body(), "/t/anything serves the same page")
    }

    @Test
    fun `the page serves the override control's route and class strings, and BOARD_MAIN has no dollar sign`() = withApp { _, probe ->
        // computenet-w61az.2 (w61az-D14): the Board row's facilitator-override control (set,
        // clear, badge) is part of BOARD_MAIN — its PUT route and CSS classes are in the served
        // bytes, the slice stays template-literal-free, and the per-topic URL still serves the
        // same bytes.
        val page = probe.get("/")
        assertEquals(200, page.statusCode())
        val body = page.body()
        assertTrue("/override'" in body, "override PUT route literal")
        assertTrue(".ovinput" in body, "override input class")
        assertTrue(".ovclear" in body, "override clear class")
        assertTrue(".ovbadge" in body, "override badge class")
        assertTrue('$' !in BOARD_MAIN, "no dollar sign in BOARD_MAIN")
        assertEquals(body, probe.get("/t/anything").body(), "/t/anything serves the same page")
    }

    @Test
    fun `the page serves the Compare roots and an experimental tab that is not the default`() = withApp { _, probe ->
        val page = probe.get("/")
        assertEquals(200, page.statusCode())
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "${page.headers()}")
        val body = page.body()
        assertTrue("""id="compare"""" in body, "compare section root")
        assertTrue("""id="tabCompare"""" in body, "compare tab button")
        for (root in listOf("cmpPicker", "cmpAxis", "cmpTray", "cmpLow", "cmpHigh", "cmpDirection", "cmpOthersWrap", "cmpOthers", "cmpDesc")) {
            assertTrue("""id="$root"""" in body, "$root root")
        }
        // the experimental badge sits inside the compare tab button, not merely somewhere on the page
        val tabStart = body.indexOf("""id="tabCompare"""")
        assertTrue(tabStart >= 0, "compare tab button")
        val tabButton = body.substring(tabStart, body.indexOf("</button>", tabStart))
        assertTrue("experimental" in tabButton, "compare tab reads experimental: $tabButton")
        assertTrue('$' !in COMPARE_VIEW, "COMPARE_VIEW is a plain raw string")
        // Rate stays the default tab: the served activeTab() still falls through to 'rate'
        val fnStart = body.indexOf("function activeTab()")
        assertTrue(fnStart >= 0, "activeTab() is served")
        val fnEnd = body.indexOf("\n}", fnStart)
        assertTrue(body.substring(fnStart, fnEnd).trimEnd().endsWith("return 'rate';"), body.substring(fnStart, fnEnd))
        assertEquals(page.body(), probe.get("/t/anything").body(), "/t/anything serves the same page")
    }

    @Test
    fun `the page serves the Gut check roots and an experimental tab, and DOTS_VIEW has no dollar sign`() = withApp { _, probe ->
        // teu97-D7/D12: the experimental Gut check dot-voting round's tab, view roots, Setup
        // controls and the Board row's dot-total class are all in the served bytes; the slice
        // stays template-literal-free and activeTab() still falls through to 'rate'.
        val page = probe.get("/")
        assertEquals(200, page.statusCode())
        val body = page.body()
        assertTrue("""id="dots"""" in body, "dots section root")
        assertTrue("""id="tabDots"""" in body, "gut check tab button")
        for (root in listOf("dotsBudget", "dotsList")) {
            assertTrue("""id="$root"""" in body, "$root root")
        }
        // the experimental badge sits inside the Gut check tab button, not merely somewhere on the page
        val tabStart = body.indexOf("""id="tabDots"""")
        assertTrue(tabStart >= 0, "gut check tab button")
        val tabButton = body.substring(tabStart, body.indexOf("</button>", tabStart))
        assertTrue("experimental" in tabButton, "gut check tab reads experimental: $tabButton")
        assertTrue("""id="setupGutCheck"""" in body, "setup gut check checkbox")
        assertTrue("""id="setupDotBudget"""" in body, "setup dot budget input")
        assertTrue("""class="dots"""" in body, "board row dots class")
        assertTrue('$' !in DOTS_VIEW, "DOTS_VIEW is a plain raw string")
        // Rate stays the default tab: the served activeTab() still falls through to 'rate'
        val fnStart = body.indexOf("function activeTab()")
        assertTrue(fnStart >= 0, "activeTab() is served")
        val fnEnd = body.indexOf("\n}", fnStart)
        assertTrue(body.substring(fnStart, fnEnd).trimEnd().endsWith("return 'rate';"), body.substring(fnStart, fnEnd))
        assertEquals(page.body(), probe.get("/t/anything").body(), "/t/anything serves the same page")
    }

    @Test
    fun `the page serves the Compare pairs mode roots and the duplicate-name check stays green`() = withApp { _, probe ->
        // k6rrk-D7…D11 (ALN2.7): the pairwise-judgement panel inside the Compare view's #cmpMode
        // toggle, served alongside place mode's roots and kept out of COMPARE_VIEW's dollar-sign ban.
        val page = probe.get("/")
        assertEquals(200, page.statusCode())
        val body = page.body()
        for (root in listOf(
            "cmpMode", "cmpPairs", "cmpPairPrompt", "cmpPairA", "cmpPairB",
            "cmpPickA", "cmpPickEqual", "cmpPickB", "cmpPairProgress", "cmpPairReset",
        )) {
            assertTrue("""id="$root"""" in body, "$root root")
        }
        // the "pairs · experimental" label sits on the #cmpMode pairs button, not merely somewhere on the page
        val modeStart = body.indexOf("""id="cmpMode"""")
        assertTrue(modeStart >= 0, "cmpMode root")
        val modeBox = body.substring(modeStart, body.indexOf("</div>", modeStart))
        assertTrue("experimental" in modeBox, "pairs button reads experimental: $modeBox")
        assertTrue('$' !in COMPARE_VIEW, "COMPARE_VIEW is a plain raw string")
        assertEquals(page.body(), probe.get("/t/anything").body(), "/t/anything serves the same page")
        // duplicate-top-level-name check (the same regex the earlier test runs), re-run here so a
        // pairs-mode name that shadows an existing shell/view global fails this test directly
        val decls = Regex("""(?m)^(?:function|let|const|var)\s+([A-Za-z_]\w*)""")
            .findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(emptyList(), decls.groupBy { it }.filterValues { it.size > 1 }.keys.toList(),
            "top-level script names declared more than once")
    }

    @Test
    fun `the events stream opens on the same frame as state`() = withApp { app, probe ->
        seed(probe)
        rate(probe, "ann", "a", "impact", "8")
        val state = probe.await { near(8.0, row(parse(it)["aggregates"]!!.jsonObject["t"].toString(), "a")?.num("score")) }
        val client = boundedHttpClient()
        try {
            // a text/event-stream response never completes: read its first frame on a bounded future
            val first = CompletableFuture.supplyAsync {
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${app.boundPort}/events")).build(),
                    HttpResponse.BodyHandlers.ofLines(),
                ).body().filter { it.startsWith("data: ") }.findFirst().get().removePrefix("data: ")
            }.get(10, TimeUnit.SECONDS)
            assertEquals(state, first)
        } finally {
            client.shutdownNow()
        }
    }

    /**
     * A write that touches only the write-side indices (a new topic has no
     * scored idea, so the fusion hub stays silent) must still push a frame:
     * otherwise an open tab never sees the topic until some later write.
     */
    @Test
    fun `creating a topic pushes an events frame to a connected client`() = withApp { app, probe ->
        val client = boundedHttpClient()
        val frames = LinkedBlockingQueue<String>()
        try {
            CompletableFuture.runAsync {
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${app.boundPort}/events")).build(),
                    HttpResponse.BodyHandlers.ofLines(),
                ).body().filter { it.startsWith("data: ") }.forEach { frames.put(it.removePrefix("data: ")) }
            }
            assertEquals(probe.state(), frames.poll(10, TimeUnit.SECONDS), "initial frame")
            assertEquals(
                200,
                probe.postJson("""{"creator":"cat","title":"T","dimensions":[{"name":"Impact"}]}""", "/topics").statusCode(),
            )
            val frame = frames.poll(10, TimeUnit.SECONDS)
            assertTrue(frame != null && """"id":"t"""" in frame, "no frame carried the new topic: $frame")
            assertEquals(probe.state(), frame)
        } finally {
            client.shutdownNow()
        }
    }

    @Test
    fun `a discussion note is created, updated, cleared and gated by the idea policy`() = withApp { app, probe ->
        seed(probe)

        val client = boundedHttpClient()
        val frames = LinkedBlockingQueue<String>()
        try {
            CompletableFuture.runAsync {
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${app.boundPort}/events")).build(),
                    HttpResponse.BodyHandlers.ofLines(),
                ).body().filter { it.startsWith("data: ") }.forEach { frames.put(it.removePrefix("data: ")) }
            }
            assertEquals(probe.state(), frames.poll(10, TimeUnit.SECONDS), "initial frame")

            val put1 = probe.putJson("""{"participant":"bob","text":"ship A first"}""", "/topics/t/ideas/a/note")
            assertEquals(200, put1.statusCode(), put1.body())
            assertEquals("""{"id":"a","note":"ship A first","noteBy":"bob"}""", put1.body())
            probe.await { """"id":"a"""" in it && """"note":"ship A first","noteBy":"bob"}""" in it }
            val frame1 = frames.poll(10, TimeUnit.SECONDS)
            assertTrue(frame1 != null && """"note":"ship A first","noteBy":"bob"}""" in frame1, "note frame: $frame1")
        } finally {
            client.shutdownNow()
        }

        // a second author overwrites: last write wins, noteBy tracks the last author
        val put2 = probe.putJson("""{"participant":"ann","text":"actually B"}""", "/topics/t/ideas/a/note")
        assertEquals(200, put2.statusCode(), put2.body())
        assertEquals("""{"id":"a","note":"actually B","noteBy":"ann"}""", put2.body())
        probe.await { """"note":"actually B","noteBy":"ann"}""" in it }

        // the facilitator policy gates the note the same way it gates idea authoring
        assertEquals(200, probe.putJson("""{"creator":"cat","ideas":"facilitator"}""", "/topics/t/policy").statusCode())
        val denied = probe.putJson("""{"participant":"bob","text":"nope"}""", "/topics/t/ideas/a/note")
        assertEquals(403, denied.statusCode(), denied.body())
        assertTrue(""""note":"actually B","noteBy":"ann"}""" in probe.state(), "unchanged after the 403: ${probe.state()}")
        // the creator may still write under the facilitator policy
        val byCreator = probe.putJson("""{"participant":"cat","text":"final call"}""", "/topics/t/ideas/a/note")
        assertEquals(200, byCreator.statusCode(), byCreator.body())
        probe.await { """"note":"final call","noteBy":"cat"}""" in it }

        // text: "" clears the note
        val clear = probe.putJson("""{"participant":"cat","text":""}""", "/topics/t/ideas/a/note")
        assertEquals(200, clear.statusCode(), clear.body())
        assertEquals("""{"id":"a","note":"","noteBy":""}""", clear.body())
        probe.await { """"note":"","noteBy":""}""" in it }

        assertEquals(404, probe.putJson("""{"participant":"cat","text":"x"}""", "/topics/t/ideas/zzz/note").statusCode())
        assertEquals(400, probe.putJson("""{"participant":"cat"}""", "/topics/t/ideas/a/note").statusCode(), "missing text")
        assertEquals(
            400,
            probe.putJson("""{"participant":"cat","text":5}""", "/topics/t/ideas/a/note").statusCode(),
            "text not a string",
        )
        assertEquals(
            400,
            probe.putJson("""{"participant":"cat","text":"${"x".repeat(4001)}"}""", "/topics/t/ideas/a/note").statusCode(),
            "text over 4000 chars",
        )
        assertEquals(405, probe.get("/topics/t/ideas/a/note").statusCode())
        assertEquals(
            400,
            probe.putJson("""{"participant":"${"x".repeat(41)}","text":"y"}""", "/topics/t/ideas/a/note").statusCode(),
            "participant over 40 chars",
        )
    }

    @Test
    fun `an identical note PUT is idempotent and journals no line`() {
        val journal = tmpJournal()
        withApp(journal) { _, probe ->
            seed(probe)
            val put = probe.putJson("""{"participant":"bob","text":"ship A first"}""", "/topics/t/ideas/a/note")
            assertEquals(200, put.statusCode(), put.body())
            probe.await { """"note":"ship A first","noteBy":"bob"}""" in it }
            val linesAfterFirst = Files.readAllLines(journal).count { """"op":"note"""" in it }
            assertEquals(1, linesAfterFirst, "one note line after the first write")

            val again = probe.putJson("""{"participant":"bob","text":"ship A first"}""", "/topics/t/ideas/a/note")
            assertEquals(200, again.statusCode(), again.body())
            assertEquals("""{"id":"a","note":"ship A first","noteBy":"bob"}""", again.body())
        }
        val linesAfterRepeat = Files.readAllLines(journal).count { """"op":"note"""" in it }
        assertEquals(1, linesAfterRepeat, "an identical PUT journals no additional line")
    }

    @Test
    fun `a facilitator override sets, re-ranks, reaches the events frame, and clears`() = withApp { app, probe ->
        // ann rates a/impact 8 (score 8, rank 1); carl rates b/impact 4 (score 4, rank 2)
        seed(probe)
        rate(probe, "ann", "a", "impact", "8")
        rate(probe, "carl", "b", "impact", "4")
        var agg = probe.await(path = "/topics/t/aggregate") {
            near(8.0, row(it, "a")?.num("score")) && near(4.0, row(it, "b")?.num("score"))
        }
        assertEquals(listOf("a", "b"), order(agg), agg)

        val client = boundedHttpClient()
        val frames = LinkedBlockingQueue<String>()
        try {
            CompletableFuture.runAsync {
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${app.boundPort}/events")).build(),
                    HttpResponse.BodyHandlers.ofLines(),
                ).body().filter { it.startsWith("data: ") }.forEach { frames.put(it.removePrefix("data: ")) }
            }
            assertEquals(probe.state(), frames.poll(10, TimeUnit.SECONDS), "initial frame")

            val put = probe.putJson("""{"creator":"cat","score":8.5}""", "/topics/t/ideas/b/override")
            assertEquals(200, put.statusCode(), put.body())
            assertEquals("""{"id":"b","override":8.5000}""", put.body())

            val b = probe.awaitRow("b") { near(8.5, it.num("override")) }
            assertEquals(1, b["rank"]!!.jsonPrimitive.content.toInt(), "$b")
            assertTrue(near(4.0, b.num("score")), "score keeps its computed meaning: $b")
            val aggAfter = probe.get("/topics/t/aggregate").body()
            val a = row(aggAfter, "a")!!
            assertEquals(2, a["rank"]!!.jsonPrimitive.content.toInt(), aggAfter)
            assertEquals(JsonNull, a["override"], aggAfter)

            val frame = frames.poll(10, TimeUnit.SECONDS)
            assertTrue(frame != null && """"score":4.0000,"override":8.5000""" in frame, "override reaches /events: $frame")
        } finally {
            client.shutdownNow()
        }

        // clearing restores computed ranking with both overrides null
        val clear = probe.putJson("""{"creator":"cat","score":null}""", "/topics/t/ideas/b/override")
        assertEquals(200, clear.statusCode(), clear.body())
        assertEquals("""{"id":"b","override":null}""", clear.body())
        agg = probe.await(path = "/topics/t/aggregate") { row(it, "b")?.get("override") == JsonNull }
        assertEquals(listOf("a", "b"), order(agg), agg)
        assertEquals(JsonNull, row(agg, "a")!!["override"], agg)
        assertEquals(JsonNull, row(agg, "b")!!["override"], agg)
    }

    @Test
    fun `an identical override PUT is idempotent and journals no line`() {
        val journal = tmpJournal()
        withApp(journal) { _, probe ->
            seed(probe)
            val put = probe.putJson("""{"creator":"cat","score":8.5}""", "/topics/t/ideas/b/override")
            assertEquals(200, put.statusCode(), put.body())
            probe.awaitRow("b") { near(8.5, it.num("override")) }
            assertEquals(1, Files.readAllLines(journal).count { """"op":"override"""" in it }, "one override line after the first write")

            val again = probe.putJson("""{"creator":"cat","score":8.5}""", "/topics/t/ideas/b/override")
            assertEquals(200, again.statusCode(), again.body())
            assertEquals("""{"id":"b","override":8.5000}""", again.body())
            assertEquals(
                1,
                Files.readAllLines(journal).count { """"op":"override"""" in it },
                "an identical PUT journals no additional line",
            )

            val clear1 = probe.putJson("""{"creator":"cat","score":null}""", "/topics/t/ideas/b/override")
            assertEquals(200, clear1.statusCode(), clear1.body())
            probe.await { row(parse(it)["aggregates"]!!.jsonObject["t"].toString(), "b")?.get("override") == JsonNull }
            assertEquals(1, Files.readAllLines(journal).count { """"op":"unoverride"""" in it }, "one unoverride line after the first clear")

            val clear2 = probe.putJson("""{"creator":"cat","score":null}""", "/topics/t/ideas/b/override")
            assertEquals(200, clear2.statusCode(), clear2.body())
            assertEquals("""{"id":"b","override":null}""", clear2.body())
            assertEquals(
                1,
                Files.readAllLines(journal).count { """"op":"unoverride"""" in it },
                "a repeat clear journals no additional line",
            )
        }
    }

    @Test
    fun `an override on an unrated idea ranks it, and invalid or refused overrides change nothing`() = withApp { _, probe ->
        seed(probe)
        // b has no ratings at all: an override still ranks it, with score null and an empty byDim
        val put = probe.putJson("""{"creator":"cat","score":5}""", "/topics/t/ideas/b/override")
        assertEquals(200, put.statusCode(), put.body())
        assertEquals("""{"id":"b","override":5.0000}""", put.body())
        val b = probe.awaitRow("b") { it["rank"] != JsonNull }
        assertEquals(JsonNull, b["score"], "$b")
        assertTrue(near(5.0, b.num("override")), "$b")
        assertEquals(0, b["byDim"]!!.jsonObject.size, "$b")
        assertTrue(b["rank"] != JsonNull, "$b")

        val before = probe.state()
        // non-creator, or no creator field at all: 403, nothing changes
        assertEquals(403, probe.putJson("""{"creator":"ann","score":3}""", "/topics/t/ideas/a/override").statusCode())
        assertEquals(403, probe.putJson("""{"score":3}""", "/topics/t/ideas/a/override").statusCode())
        // unknown idea: 404 before the creator check, even with a non-creator body
        assertEquals(404, probe.putJson("""{"creator":"ann","score":3}""", "/topics/t/ideas/ghost/override").statusCode())
        // bad scores: 400
        for (bad in listOf("\"5\"", "0", "-1", "9.01", "true")) {
            assertEquals(400, probe.putJson("""{"creator":"cat","score":$bad}""", "/topics/t/ideas/a/override").statusCode(), "score $bad")
        }
        assertEquals(400, probe.putJson("""{"creator":"cat"}""", "/topics/t/ideas/a/override").statusCode(), "missing score")
        // wrong methods: 405
        assertEquals(405, probe.get("/topics/t/ideas/a/override").statusCode())
        assertEquals(405, probe.postJson("""{"creator":"cat","score":3}""", "/topics/t/ideas/a/override").statusCode())
        assertEquals(405, probe.delete("/topics/t/ideas/a/override").statusCode())
        assertEquals(before, probe.state(), "every refused override changed nothing")
    }

    @Test
    fun `a restarted app replays its journal to a byte-equal state`() {
        val journal = tmpJournal()
        lateinit var before: String
        lateinit var deeBefore: String
        lateinit var annBefore: String
        withApp(journal) { _, probe ->
            seed(probe)
            probe.postJson("""{"participant":"ann","title":"C"}""", "/topics/t/ideas")
            rate(probe, "ann", "a", "impact", "8")
            rate(probe, "bob", "a", "impact", "3")
            rate(probe, "ann", "a", "effort", "3")
            rate(probe, "bob", "b", "effort", "7")
            rate(probe, "bob", "c", "impact", "9")
            // k6rrk-D6: dee judges on impact, all "equal" → dee rates a, b, c at exactly 5 (k6rrk.1 property 3);
            // the a|c judgement is later dropped by the unidea cascade, without a line and without a refit
            judge(probe, "dee", "impact", "a", "b", "equal")
            judge(probe, "dee", "impact", "c", "a", "equal")
            probe.putJson("""{"creator":"cat","dim":"effort","weight":4.0}""", "/topics/t/weights")
            assertEquals(200, probe.postJson("""{"creator":"cat","name":"Cost","weight":0.5}""", "/topics/t/dimensions").statusCode())
            rate(probe, "ann", "b", "cost", "2")
            // an unjudge on another dimension, and a judgement the undimension cascade drops
            judge(probe, "dee", "cost", "a", "b", "a")
            assertEquals("""{"cleared":1}""", probe.delete("/topics/t/judge?participant=dee&dim=cost").body())
            judge(probe, "ann", "cost", "b", "a", "b")
            rate(probe, "bob", "a", "impact", "null")
            assertEquals(200, probe.delete("/topics/t/ideas/c?creator=cat").statusCode())
            assertEquals(200, probe.delete("/topics/t/dimensions/cost?creator=cat").statusCode())
            // the ALN2 ops: direction there and back, labels, idea edit, policy, visibility, reveal
            for (body in listOf(
                """{"creator":"cat","direction":"cost"}""",
                """{"creator":"cat","direction":"value","lowLabel":"none","highLabel":"a lot"}""",
            )) {
                assertEquals(200, probe.putJson(body, "/topics/t/dimensions/effort").statusCode(), body)
            }
            // the note and an override are both written BEFORE the idea edit so replay exercises the
            // D2/D1 hazard: an `idea` line replaces topic.ideas[id] wholesale, and must not touch the
            // separately-keyed note or override
            assertEquals(
                200,
                probe.putJson("""{"participant":"bob","text":"decided: ship it"}""", "/topics/t/ideas/b/note").statusCode(),
            )
            assertEquals(200, probe.putJson("""{"creator":"cat","score":6.5}""", "/topics/t/ideas/b/override").statusCode())
            assertEquals(200, probe.putJson("""{"creator":"cat","title":"B edited","description":"new"}""", "/topics/t/ideas/b").statusCode())
            // a set-then-cleared override on another idea: replays with no override at all
            assertEquals(200, probe.putJson("""{"creator":"cat","score":3}""", "/topics/t/ideas/a/override").statusCode())
            assertEquals(200, probe.putJson("""{"creator":"cat","score":null}""", "/topics/t/ideas/a/override").statusCode())
            assertEquals(
                200,
                probe.putJson("""{"creator":"cat","ideas":"facilitator","boardVisibility":"after-reveal"}""", "/topics/t/policy").statusCode(),
            )
            // the gut check: enable with a budget, place dots, then remove one (dots line + a
            // count-0 removal are both journaled ops; the removal is exercised so the journal carries
            // a dots line whose count is 0, same as unrate's shape)
            assertEquals(200, probe.putJson("""{"creator":"cat","gutCheck":true,"dotBudget":2}""", "/topics/t/policy").statusCode())
            assertEquals(200, probe.postJson("""{"participant":"ann","idea":"a","count":2}""", "/topics/t/dots").statusCode())
            assertEquals(200, probe.postJson("""{"participant":"ann","idea":"a","count":0}""", "/topics/t/dots").statusCode())
            assertEquals(200, probe.postJson("""{"participant":"bob","idea":"b","count":1}""", "/topics/t/dots").statusCode())
            assertEquals(200, probe.postJson("""{"creator":"cat"}""", "/topics/t/reveal").statusCode())
            before = probe.await { s ->
                val agg = parse(s)["aggregates"]!!.jsonObject["t"].toString()
                // impact: a = mean(ann 8, dee 5), b = dee 5; effort: a = ann 3, b = bob 7
                near((2.0 * 6.5 + 4.0 * 3) / 6.0, row(agg, "a")?.num("score")) &&
                    near((2.0 * 5 + 4.0 * 7) / 6.0, row(agg, "b")?.num("score")) &&
                    near(6.5, row(agg, "b")?.num("override")) && row(agg, "a")?.get("override") == JsonNull &&
                    row(agg, "c") == null && """"revealed":true""" in s
            }
            for (want in listOf(
                """"ideas":"facilitator","boardVisibility":"after-reveal","revealed":true,"gutCheck":true,"dotBudget":2""",
                """{"id":"effort","name":"Effort","weight":4.0000,"direction":"value","lowLabel":"none","highLabel":"a lot"}""",
                """"id":"b","title":"B edited","description":"new","proposer":"bob","note":"decided: ship it","noteBy":"bob"""",
                """"override":6.5000""",
            )) {
                assertTrue(want in before, "$want in $before")
            }
            // ann's a-dots (2 then removed) and bob's b-dot (1) are both journaled and replay: only
            // bob's placement survives, so the aggregate carries "dots":1 on b and 0 on a
            val agg0 = parse(before)["aggregates"]!!.jsonObject["t"].toString()
            assertEquals(1, row(agg0, "b")!!["dots"]!!.jsonPrimitive.content.toInt(), agg0)
            assertEquals(0, row(agg0, "a")!!["dots"]!!.jsonPrimitive.content.toInt(), agg0)
            deeBefore = probe.get("/topics/t/me?participant=dee").body()
            assertTrue(""""judgements":[{"dim":"impact","a":"a","b":"b","outcome":"equal"}]}""" in deeBefore, deeBefore)
            annBefore = probe.get("/topics/t/me?participant=ann").body()
            assertTrue(""""judgements":[]}""" in annBefore, "the undimension cascade dropped ann's cost judgement: $annBefore")
        }
        val lines = Files.readAllLines(journal)
        for (op in listOf(
            "topic", "dimension", "idea", "note", "rate", "weight", "unrate", "unidea", "undimension",
            "direction", "labels", "policy", "visibility", "reveal", "judge", "unjudge",
            "override", "unoverride", "gutcheck", "dots",
        )) {
            assertTrue(lines.any { """"op":"$op"""" in it }, "journal records $op: $lines")
        }
        // cascades are journaled as unrate lines before the removal line
        assertTrue(lines.indexOfFirst { """"op":"unrate"""" in it && """"idea":"c"""" in it } <
            lines.indexOfFirst { """"op":"unidea"""" in it }, "$lines")

        withApp(journal) { _, probe ->
            val after = probe.await { it == before }
            assertEquals(before, after)
            assertEquals(deeBefore, probe.get("/topics/t/me?participant=dee").body())
            assertEquals(annBefore, probe.get("/topics/t/me?participant=ann").body())
        }
    }

    /** Topic `t` by `cat`: impact (w 2, value), ease (w 1, value), effort (w 1, COST); ideas a, b, c, d. */
    private fun seedValueCost(probe: HttpProbe) {
        val created = probe.postJson(
            """{"creator":"cat","title":"T","dimensions":[{"name":"Impact","weight":2},{"name":"Ease"},""" +
                """{"name":"Effort","direction":"cost","lowLabel":"an afternoon","highLabel":"a quarter"}]}""",
            "/topics",
        )
        assertEquals(200, created.statusCode(), created.body())
        for (title in listOf("A", "B", "C", "D")) {
            assertEquals(200, probe.postJson("""{"participant":"cat","title":"$title"}""", "/topics/t/ideas").statusCode())
        }
    }

    private fun contribution(row: JsonObject, dim: String) = row["byDim"]!!.jsonObject[dim]!!.jsonObject["contribution"]

    @Test
    fun `cost dimensions divide the score and an idea missing a side is listed unranked`() = withApp { _, probe ->
        seedValueCost(probe)
        val topics = probe.get("/topics").body()
        assertTrue(
            """{"id":"effort","name":"Effort","weight":1.0000,"direction":"cost","lowLabel":"an afternoon","highLabel":"a quarter"}""" in topics,
            topics,
        )

        // rule 2: value (2·8 + 1·5)/3 = 7, cost 4 → 1.75; contributions over value dims scaled by 1/cost
        rate(probe, "zed", "a", "impact", "8")
        rate(probe, "zed", "a", "ease", "5")
        rate(probe, "zed", "a", "effort", "4")
        var a = probe.awaitRow("a") { near(1.75, it.num("score")) }
        assertTrue(near(7.0, a.num("value")) && near(4.0, a.num("cost")), "$a")
        assertTrue(near(16.0 / 3.0 / 4.0, contribution(a, "impact")?.jsonPrimitive?.content?.toDouble()), "$a")
        assertTrue(near(5.0 / 3.0 / 4.0, contribution(a, "ease")?.jsonPrimitive?.content?.toDouble()), "$a")
        assertEquals(JsonNull, contribution(a, "effort"), "a cost dimension contributes no value share: $a")
        assertEquals("1", a["byDim"]!!.jsonObject["effort"]!!.jsonObject["n"]!!.jsonPrimitive.content, "$a")

        // raising a cost rating lowers the score: effort 8 → 7/8
        rate(probe, "zed", "a", "effort", "8")
        a = probe.awaitRow("a") { near(0.875, it.num("score")) }
        assertTrue(near(8.0, a.num("cost")), "$a")

        // back to effort 4 (1.75), then redirect effort to VALUE: no cost dim left → v1 mean (2·8+5+4)/4 = 6.25
        rate(probe, "zed", "a", "effort", "4")
        probe.awaitRow("a") { near(1.75, it.num("score")) }
        val redirect = probe.putJson("""{"creator":"cat","direction":"value"}""", "/topics/t/dimensions/effort")
        assertEquals(200, redirect.statusCode(), redirect.body())
        a = probe.awaitRow("a") { near(6.25, it.num("score")) }
        assertEquals(JsonNull, a["cost"], "$a")
        assertTrue(near(4.0 / 4.0, contribution(a, "effort")?.jsonPrimitive?.content?.toDouble()), "$a")
        assertTrue(""""id":"effort","name":"Effort","weight":1.0000,"direction":"value"""" in probe.get("/topics").body())

        // rule 3: effort back to cost; b rated on impact only → unranked, value side present, cost side null
        assertEquals(200, probe.putJson("""{"creator":"cat","direction":"cost"}""", "/topics/t/dimensions/effort").statusCode())
        rate(probe, "zed", "b", "impact", "6")
        rate(probe, "quinn", "b", "impact", "6")
        // d: value (2·9 + 9)/3 = 9, cost 1 → 9, ranked above a
        rate(probe, "quinn", "d", "impact", "9")
        rate(probe, "quinn", "d", "ease", "9")
        rate(probe, "quinn", "d", "effort", "1")
        val agg = probe.await(path = "/topics/t/aggregate") { body ->
            near(9.0, row(body, "d")?.num("score")) && near(1.75, row(body, "a")?.num("score")) &&
                row(body, "b")?.get("byDim")?.jsonObject?.get("impact")?.jsonObject?.get("n")?.jsonPrimitive?.content == "2"
        }
        assertEquals(listOf("d", "a", "b", "c"), order(agg), "ranked by score, then unranked by id: $agg")
        val b = row(agg, "b")!!
        assertEquals(JsonNull, b["rank"], "$b")
        assertEquals(JsonNull, b["score"], "$b")
        assertEquals(JsonNull, b["cost"], "cost not rated yet: $b")
        assertTrue(near(6.0, b.num("value")), "$b")
        val impact = b["byDim"]!!.jsonObject["impact"]!!.jsonObject
        assertTrue(near(6.0, impact.num("mean")) && near(0.0, impact.num("stdev")), "$b")
        assertEquals(JsonNull, impact["contribution"], "$b")
        assertEquals(2, row(agg, "a")!!["rank"]!!.jsonPrimitive.content.toInt(), agg)

        // reweighting a cost dimension through the v1 weights route keeps its direction
        val reweigh = probe.putJson("""{"creator":"cat","dim":"effort","weight":2}""", "/topics/t/weights")
        assertEquals(200, reweigh.statusCode(), reweigh.body())
        assertTrue(""""id":"effort","name":"Effort","weight":2.0000,"direction":"cost"""" in probe.get("/topics").body())

        // rule 8: counts, never names — in the aggregate route and the same object under /state
        val parsed = parse(agg)
        assertEquals("2", parsed["participants"]!!.jsonPrimitive.content, agg)
        for ((id, n) in listOf("a" to 1, "b" to 2, "c" to 0, "d" to 1)) {
            assertEquals(n, row(agg, id)!!["raters"]!!.jsonPrimitive.content.toInt(), "raters of $id: $agg")
        }
        val stateAgg = parse(probe.state())["aggregates"]!!.jsonObject["t"].toString()
        for (name in listOf("zed", "quinn")) {
            assertTrue(name !in agg, "the aggregate must not name $name: $agg")
            assertTrue(name !in stateAgg, "the /state aggregate must not name $name: $stateAgg")
        }
        // not vacuous: the names ARE in /state's ratings list
        assertTrue("zed" in probe.state() && "quinn" in probe.state())
    }

    @Test
    fun `a literal v1 journal replays with the v1 defaults`() {
        val journal = tmpJournal()
        // byte-for-byte what the v1 record() calls emitted at 9b5bb520: no direction, no policy, no labels
        Files.write(
            journal,
            listOf(
                """{"op":"topic","id":"t","title":"T","creator":"cat"}""",
                """{"op":"dimension","topic":"t","id":"impact","name":"Impact","weight":2.0}""",
                """{"op":"dimension","topic":"t","id":"effort","name":"Effort","weight":1.0}""",
                """{"op":"idea","topic":"t","id":"a","title":"A","description":"","proposer":"ann"}""",
                """{"op":"rate","topic":"t","idea":"a","dim":"impact","participant":"ann","value":8}""",
                """{"op":"rate","topic":"t","idea":"a","dim":"effort","participant":"ann","value":3}""",
            ),
        )
        withApp(journal) { _, probe ->
            val a = probe.awaitRow("a") { near((2.0 * 8 + 1.0 * 3) / 3.0, it.num("score")) }
            assertEquals(JsonNull, a["cost"], "$a")
            val topics = probe.get("/topics").body()
            assertTrue(""""ideas":"everyone","boardVisibility":"after-rating","revealed":false""" in topics, topics)
            for (dim in listOf("effort", "impact")) {
                assertTrue(
                    Regex(""""id":"$dim","name":"[A-Za-z]+","weight":[0-9.]+,"direction":"value","lowLabel":"","highLabel":""""")
                        .containsMatchIn(topics),
                    "$dim: $topics",
                )
            }
            // v1 idea policy is everyone: a non-creator may still add
            assertEquals(200, probe.postJson("""{"participant":"ann","title":"E"}""", "/topics/t/ideas").statusCode())
        }
    }

    @Test
    fun `board visibility and reveal are facilitator topic state and the aggregate is never withheld`() =
        withApp { _, probe ->
            seed(probe)
            rate(probe, "ann", "a", "impact", "8")
            val put = probe.putJson("""{"creator":"cat","boardVisibility":"after-reveal"}""", "/topics/t/policy")
            assertEquals(200, put.statusCode(), put.body())
            // the page gates the Board; the shared frame still carries the aggregate before any reveal
            var state = probe.await { s ->
                """"boardVisibility":"after-reveal","revealed":false""" in s &&
                    near(8.0, row(parse(s)["aggregates"]!!.jsonObject["t"].toString(), "a")?.num("score"))
            }
            assertTrue(near(8.0, row(probe.get("/topics/t/aggregate").body(), "a")?.num("score")))

            assertEquals(200, probe.postJson("""{"creator":"cat"}""", "/topics/t/reveal").statusCode())
            state = probe.await { """"revealed":true""" in it }
            assertTrue(""""boardVisibility":"after-reveal","revealed":true""" in state, state)
            // idempotent: a second reveal changes nothing
            assertEquals(200, probe.postJson("""{"creator":"cat"}""", "/topics/t/reveal").statusCode())
            assertEquals(state, probe.state())
        }

    private fun dot(probe: HttpProbe, who: String, idea: String, count: Int) =
        probe.postJson("""{"participant":"$who","idea":"$idea","count":$count}""", "/topics/t/dots")

    @Test
    fun `a facilitator-enabled gut check round is a separate dot signal that never moves the score`() = withApp { _, probe ->
        seed(probe)

        // the round is off by default: placing dots is refused (409), and unrelated to the 404s/403s
        // and validation already covered by "facilitator-only settings and idea edits ..."
        assertEquals(409, dot(probe, "ann", "a", 1).statusCode())

        // only the creator may switch it on or set the budget; malformed bodies are 400 and change nothing
        assertEquals(403, probe.putJson("""{"creator":"bob","gutCheck":true}""", "/topics/t/policy").statusCode())
        val beforeEnable = probe.state()
        for (body in listOf(
            """{"creator":"cat","dotBudget":0}""",
            """{"creator":"cat","dotBudget":21}""",
            """{"creator":"cat","dotBudget":"3"}""",
            """{"creator":"cat","dotBudget":2.5}""",
        )) {
            assertEquals(400, probe.putJson(body, "/topics/t/policy").statusCode(), body)
        }
        assertEquals(beforeEnable, probe.state(), "every refused policy PUT changed nothing")

        // gutCheck:true alone leaves the budget at its default (3)
        val enabled = probe.putJson("""{"creator":"cat","gutCheck":true}""", "/topics/t/policy")
        assertEquals(200, enabled.statusCode(), enabled.body())
        assertEquals(
            """{"ideas":"everyone","boardVisibility":"after-rating","gutCheck":true,"dotBudget":3}""",
            enabled.body(),
        )

        // placing within budget: response carries idea, count, used and budget
        val placed = dot(probe, "ann", "a", 2)
        assertEquals(200, placed.statusCode(), placed.body())
        assertEquals("""{"idea":"a","count":2,"used":2,"budget":3}""", placed.body())
        // /me carries the caller's own dots after "total" and nothing else changes
        assertTrue(""""id":"a","title":"A","description":"","ratings":{"effort":null,"impact":null},"rated":0,"total":2,"dots":2""" in
            probe.get("/topics/t/me?participant=ann").body())

        // over budget: 1 more on b would total 3 (still within 3) — push past it with a second idea
        assertEquals(200, probe.postJson("""{"participant":"ann","title":"C"}""", "/topics/t/ideas").statusCode())
        val overBudget = dot(probe, "ann", "b", 2) // 2 (a) + 2 (b) = 4 > 3
        assertEquals(400, overBudget.statusCode(), overBudget.body())
        val meUnchanged = probe.get("/topics/t/me?participant=ann").body()
        assertTrue(""""id":"b","title":"B","description":"the b idea","ratings":{"effort":null,"impact":null},"rated":0,"total":2,"dots":0""" in meUnchanged, meUnchanged)

        // exactly at budget is accepted (2 + 1 = 3)
        assertEquals(200, dot(probe, "ann", "b", 1).statusCode())

        // shrink the budget below what ann has placed (3): while she is over the shrunk budget (2), a
        // further increase is refused, but a decrease is still accepted (it climbs her back under it)
        val shrunk = probe.putJson("""{"creator":"cat","dotBudget":2}""", "/topics/t/policy")
        assertEquals(200, shrunk.statusCode(), shrunk.body())
        assertEquals(400, dot(probe, "ann", "a", 3).statusCode(), "an increase while over a shrunk budget is refused")
        val climbBack = dot(probe, "ann", "a", 1) // 1 (a) + 1 (b) = 2, at the shrunk budget
        assertEquals(200, climbBack.statusCode(), climbBack.body())
        assertEquals("""{"idea":"a","count":1,"used":2,"budget":2}""", climbBack.body())

        // count:0 removes the entry
        val removed = dot(probe, "ann", "b", 0)
        assertEquals(200, removed.statusCode(), removed.body())
        assertEquals("""{"idea":"b","count":0,"used":1,"budget":2}""", removed.body())
        assertTrue(""""id":"b","title":"B","description":"the b idea","ratings":{"effort":null,"impact":null},"rated":0,"total":2,"dots":0""" in
            probe.get("/topics/t/me?participant=ann").body())

        // an unknown idea and a bad count are both 400, before any write
        assertEquals(400, dot(probe, "ann", "ghost", 1).statusCode())
        assertEquals(400, probe.postJson("""{"participant":"ann","idea":"a","count":-1}""", "/topics/t/dots").statusCode())
        assertEquals(400, probe.postJson("""{"participant":"ann","idea":"a","count":1.5}""", "/topics/t/dots").statusCode())
        assertEquals(400, probe.postJson("""{"participant":"ann","idea":"a","count":"1"}""", "/topics/t/dots").statusCode())
        assertEquals(405, probe.get("/topics/t/dots").statusCode())

        // bob places his own dots on a: the aggregate's "dots" is a count over ALL participants
        assertEquals(200, dot(probe, "bob", "a", 1).statusCode())
        val agg = probe.get("/topics/t/aggregate").body()
        assertEquals(2, row(agg, "a")!!["dots"]!!.jsonPrimitive.content.toInt(), agg) // ann 1 + bob 1
        assertEquals(0, row(agg, "b")!!["dots"]!!.jsonPrimitive.content.toInt(), agg)
        for (name in listOf("ann", "bob")) assertTrue(name !in agg, "the aggregate must not name $name: $agg")

        // disabling keeps the stored dots (teu97-D4)
        assertEquals(200, probe.putJson("""{"creator":"cat","gutCheck":false}""", "/topics/t/policy").statusCode())
        assertEquals(409, dot(probe, "ann", "a", 3).statusCode())
        val aggDisabled = probe.get("/topics/t/aggregate").body()
        assertEquals(2, row(aggDisabled, "a")!!["dots"]!!.jsonPrimitive.content.toInt(), aggDisabled)
        assertEquals(200, probe.putJson("""{"creator":"cat","gutCheck":true}""", "/topics/t/policy").statusCode())
        val aggReenabled = probe.get("/topics/t/aggregate").body()
        assertEquals(2, row(aggReenabled, "a")!!["dots"]!!.jsonPrimitive.content.toInt(), aggReenabled)

        // REGRESSION (the feature's hard rule): rate every idea, read the scores and row order, place
        // and remove dots, then assert both are byte-identical — dots never enter the score
        rate(probe, "cy", "a", "impact", "8")
        rate(probe, "cy", "a", "effort", "3")
        rate(probe, "cy", "b", "impact", "4")
        rate(probe, "cy", "b", "effort", "9")
        probe.awaitRow("a") { it["score"] != JsonNull }
        probe.awaitRow("b") { it["score"] != JsonNull }
        val beforeScores = probe.get("/topics/t/aggregate").body()
        val beforeOrder = order(beforeScores)
        val beforeRows = parse(beforeScores)["ideas"]!!.jsonArray.map { it.jsonObject.filterKeys { k -> k != "dots" } }
        // dotBudget is 2 here (shrunk earlier in this test): stay within it
        assertEquals(200, dot(probe, "cy", "a", 1).statusCode())
        assertEquals(200, dot(probe, "cy", "b", 1).statusCode())
        assertEquals(200, dot(probe, "ann", "a", 0).statusCode())
        val afterScores = probe.get("/topics/t/aggregate").body()
        assertEquals(beforeOrder, order(afterScores), "placing dots must not reorder the ranking")
        val afterRows = parse(afterScores)["ideas"]!!.jsonArray.map { it.jsonObject.filterKeys { k -> k != "dots" } }
        assertEquals(beforeRows, afterRows, "every scoring field (except dots itself) is byte-identical before and after dots")
    }
}
