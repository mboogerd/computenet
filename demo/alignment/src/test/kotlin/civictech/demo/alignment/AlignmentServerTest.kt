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
            """"dimensions":[{"id":"effort","name":"Effort","weight":1.0000},{"id":"impact","name":"Impact","weight":2.0000}]""" in topics,
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
        val before = probe.state()

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

        assertEquals(409, probe.postJson("""{"participant":"ann","title":"A!"}""", "/topics/t/ideas").statusCode())
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
        assertEquals(404, probe.get("/nope").statusCode())
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
            assertEquals(200, probe.delete("/topics/t/ideas/c").statusCode())
            assertEquals(200, probe.delete("/topics/t/dimensions/cost?creator=cat").statusCode())
            before = probe.await { s ->
                val agg = parse(s)["aggregates"]!!.jsonObject["t"].toString()
                near((2.0 * 8 + 4.0 * 3) / 6.0, row(agg, "a")?.num("score")) && near(7.0, row(agg, "b")?.num("score")) &&
                    row(agg, "c") == null
            }
        }
        val lines = Files.readAllLines(journal)
        for (op in listOf("topic", "dimension", "idea", "rate", "weight", "unrate", "unidea", "undimension")) {
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
}
