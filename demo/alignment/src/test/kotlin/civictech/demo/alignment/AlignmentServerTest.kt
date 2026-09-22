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
            """"ideas":"everyone","boardVisibility":"after-rating","revealed":false,"dimensions":[""" +
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
        assertTrue(""""rank":null,"id":"a","title":"A","score":null,"split":false,"ratings":0,"byDim":{}""" in agg, agg)
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
                """{"id":"a","title":"A","description":"","ratings":{"effort":null,"impact":8},"rated":1,"total":2},""" +
                """{"id":"b","title":"B","description":"the b idea","ratings":{"effort":null,"impact":null},"rated":0,"total":2}]}""",
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
    fun `a restarted app replays its journal to a byte-equal state`() {
        val journal = tmpJournal()
        lateinit var before: String
        withApp(journal) { _, probe ->
            seed(probe)
            probe.postJson("""{"participant":"ann","title":"C"}""", "/topics/t/ideas")
            rate(probe, "ann", "a", "impact", "8")
            rate(probe, "bob", "a", "impact", "3")
            rate(probe, "ann", "a", "effort", "3")
            rate(probe, "bob", "b", "effort", "7")
            rate(probe, "bob", "c", "impact", "9")
            probe.putJson("""{"creator":"cat","dim":"effort","weight":4.0}""", "/topics/t/weights")
            assertEquals(200, probe.postJson("""{"creator":"cat","name":"Cost","weight":0.5}""", "/topics/t/dimensions").statusCode())
            rate(probe, "ann", "b", "cost", "2")
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
            assertEquals(200, probe.putJson("""{"creator":"cat","title":"B edited","description":"new"}""", "/topics/t/ideas/b").statusCode())
            assertEquals(
                200,
                probe.putJson("""{"creator":"cat","ideas":"facilitator","boardVisibility":"after-reveal"}""", "/topics/t/policy").statusCode(),
            )
            assertEquals(200, probe.postJson("""{"creator":"cat"}""", "/topics/t/reveal").statusCode())
            before = probe.await { s ->
                val agg = parse(s)["aggregates"]!!.jsonObject["t"].toString()
                near((2.0 * 8 + 4.0 * 3) / 6.0, row(agg, "a")?.num("score")) && near(7.0, row(agg, "b")?.num("score")) &&
                    row(agg, "c") == null && """"revealed":true""" in s
            }
            for (want in listOf(
                """"ideas":"facilitator","boardVisibility":"after-reveal","revealed":true""",
                """{"id":"effort","name":"Effort","weight":4.0000,"direction":"value","lowLabel":"none","highLabel":"a lot"}""",
                """"id":"b","title":"B edited","description":"new","proposer":"bob"""",
            )) {
                assertTrue(want in before, "$want in $before")
            }
        }
        val lines = Files.readAllLines(journal)
        for (op in listOf(
            "topic", "dimension", "idea", "rate", "weight", "unrate", "unidea", "undimension",
            "direction", "labels", "policy", "visibility", "reveal",
        )) {
            assertTrue(lines.any { """"op":"$op"""" in it }, "journal records $op: $lines")
        }
        // cascades are journaled as unrate lines before the removal line
        assertTrue(lines.indexOfFirst { """"op":"unrate"""" in it && """"idea":"c"""" in it } <
            lines.indexOfFirst { """"op":"unidea"""" in it }, "$lines")

        withApp(journal) { _, probe ->
            val after = probe.await { it == before }
            assertEquals(before, after)
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
}
