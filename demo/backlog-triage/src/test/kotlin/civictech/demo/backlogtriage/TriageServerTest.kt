package civictech.demo.backlogtriage

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriageServerTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun post(base: String, path: String, json: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(base: String, path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI("$base$path")).build(), HttpResponse.BodyHandlers.ofString())

    private fun delete(base: String, path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI("$base$path")).DELETE().build(), HttpResponse.BodyHandlers.ofString())

    private fun await(base: String, path: String = "/features", predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 5_000
        var json = ""
        while (System.currentTimeMillis() < deadline) {
            json = get(base, path).body()
            if (predicate(json)) return json
            Thread.sleep(50)
        }
        return json
    }

    /** Order of ids as they appear in the /features array. */
    private fun order(json: String): List<String> =
        Regex(""""id":"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()

    @Test
    fun `preferences fold into a collective ranking that reorders live`() {
        val app = TriageApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"

            // submit: explicit id, and a slug derived from the title
            assertEquals(200, post(base, "/features", """{"id":"bucket-cell","title":"BucketCell","body":"# BucketCell\nthreshold quantization"}""").statusCode())
            val slugged = post(base, "/features", """{"title":"Typed Graph Wiring!"}""")
            assertTrue(""""id":"typed-graph-wiring"""" in slugged.body(), "id should be slugged: ${slugged.body()}")
            post(base, "/features", """{"title":"Relational View DSL","id":"relational-view-dsl"}""")

            // all three unranked until a preference arrives
            var json = await(base) { order(it).size == 3 }
            assertTrue(""""rank":null""" in json, "features should start unranked: $json")

            // detail endpoint serves the body
            assertTrue("threshold quantization" in get(base, "/features/bucket-cell").body())

            // ada: bucket>typed, bo: bucket>relational, cy: typed>relational
            // scores: bucket +1.0, typed 0.0, relational -1.0
            post(base, "/prefer", """{"agent":"ada","winner":"bucket-cell","loser":"typed-graph-wiring"}""")
            post(base, "/prefer", """{"agent":"bo","winner":"bucket-cell","loser":"relational-view-dsl"}""")
            post(base, "/prefer", """{"agent":"cy","winner":"typed-graph-wiring","loser":"relational-view-dsl"}""")
            json = await(base) { order(it) == listOf("bucket-cell", "typed-graph-wiring", "relational-view-dsl") }
            assertEquals(listOf("bucket-cell", "typed-graph-wiring", "relational-view-dsl"), order(json), json)
            assertTrue(""""rank":1,"id":"bucket-cell","title":"BucketCell","score":1.0000""" in json, json)

            // ada flips: typed>bucket auto-retracts her reverse vote →
            // typed climbs to the top (score 1.0), bucket drops to 0.0
            post(base, "/prefer", """{"agent":"ada","winner":"typed-graph-wiring","loser":"bucket-cell"}""")
            json = await(base) { order(it).firstOrNull() == "typed-graph-wiring" }
            assertEquals(listOf("typed-graph-wiring", "bucket-cell", "relational-view-dsl"), order(json), json)

            // retraction: cy withdraws → relational loses one loss
            post(base, "/prefer", """{"agent":"cy","winner":"typed-graph-wiring","loser":"relational-view-dsl","retract":"true"}""")
            json = await(base) { """"id":"relational-view-dsl","score":-1.0000,"wins":0,"losses":1""" in it }
            assertTrue(""""losses":1""" in json, json)

            // removal cascades the feature's preferences out of the ranking
            delete(base, "/features/typed-graph-wiring")
            json = await(base) { order(it) == listOf("bucket-cell", "relational-view-dsl") }
            assertEquals(listOf("bucket-cell", "relational-view-dsl"), order(json), json)

            // /triage: bias-safe worklist. Current state: features bucket-cell
            // + relational-view-dsl (+ typed-graph-wiring re-added below);
            // surviving prefs: only bo's bucket>relational.
            post(base, "/features", """{"id":"typed-graph-wiring","title":"Typed Graph Wiring!"}""")
            await(base) { order(it).size == 3 }
            val bo = get(base, "/triage?agent=bo").body()
            for (leak in listOf(""""rank"""", """"score"""", """"wins"""", """"losses"""")) {
                assertTrue(leak !in bo, "/triage must not leak $leak: $bo")
            }
            // only bo's own prefs come back
            assertTrue(""""prefs":[{"winner":"bucket-cell","loser":"relational-view-dsl"}]""" in bo, bo)
            // typed-graph-wiring is uncovered by bo → sorts first, and the
            // suggested pair involves it (never bo's already-voted pair)
            assertTrue(""""features":[{"id":"typed-graph-wiring","title":"Typed Graph Wiring!","comparisons":0,"mine":0}""" in bo, bo)
            assertTrue(""""next":{"a":""" in bo && "typed-graph-wiring" in bo.substringAfter(""""next":"""), bo)
            assertTrue(""""phase1Complete":false""" in bo, bo)
            // without ?agent=: randomized, zero personal coverage
            val anon = get(base, "/triage").body()
            assertTrue(""""prefs":[]""" in anon && """"score"""" !in anon, anon)

            // alternative ranking algorithms, computed by the RatingCell /
            // MetaRankCell dataflow (async — poll each folded read model)
            val pipeline = get(base, "/features").body()
            val meanEngine = get(base, "/features?algo=mean").body()
            assertEquals(order(pipeline), order(meanEngine), "algo=mean must serve the cell pipeline")
            for (algo in listOf("elo", "bt", "trueskill", "glicko", "wenglin", "wilson", "meta")) {
                val body = await(base, "/features?algo=$algo") {
                    """"algo":"$algo"""" in it && order(it).firstOrNull() == "bucket-cell"
                }
                assertTrue(order(body).first() == "bucket-cell", "$algo: $body")
            }
            assertEquals(400, get(base, "/features?algo=nope").statusCode())

            assertEquals(400, post(base, "/features", """{"body":"no title"}""").statusCode())
            assertEquals(400, post(base, "/prefer", """{"agent":"a","winner":"bucket-cell","loser":"bucket-cell"}""").statusCode())
            assertEquals(400, post(base, "/prefer", """{"agent":"a","winner":"bucket-cell","loser":"ghost"}""").statusCode())
            assertEquals(404, get(base, "/features/ghost").statusCode())
        } finally {
            app.stop()
        }
    }

    @Test
    fun `journalled features and preferences survive a restart`() {
        val journal = kotlin.io.path.createTempDirectory("triage").resolve("triage.jsonl")

        val first = TriageApp(port = 0, journalPath = journal).start()
        try {
            val base = "http://localhost:${first.boundPort}"
            post(base, "/features", """{"id":"alpha","title":"Alpha","body":"# Alpha"}""")
            post(base, "/features", """{"id":"beta","title":"Beta"}""")
            post(base, "/features", """{"id":"gamma","title":"Gamma"}""")
            post(base, "/prefer", """{"agent":"ada","winner":"alpha","loser":"beta"}""")
            post(base, "/prefer", """{"agent":"bo","winner":"alpha","loser":"gamma"}""")
            await(base) { order(it).firstOrNull() == "alpha" }
        } finally {
            first.stop()
        }

        val second = TriageApp(port = 0, journalPath = journal).start()
        try {
            val base = "http://localhost:${second.boundPort}"
            // features, meta, prefs, and the derived ranking all recover
            // (poll on the full condition — the independent hub folds can be
            // momentarily torn across views, the F-5 observation-edge glitch)
            var json = await(base) {
                order(it) == listOf("alpha", "beta", "gamma") &&
                        """"rank":1,"id":"alpha","title":"Alpha","score":1.0000,"wins":2""" in it
            }
            assertTrue(""""rank":1,"id":"alpha","title":"Alpha","score":1.0000,"wins":2""" in json, json)
            assertTrue("# Alpha" in get(base, "/features/alpha").body())
            // the rating cells were rebuilt by journal replay through the prefs cell
            val bt = await(base, "/features?algo=bt") { order(it).firstOrNull() == "alpha" }
            assertEquals("alpha", order(bt).first(), bt)

            // the write-side pref index recovered too: ada's reverse vote
            // still replaces her journalled original instead of stacking
            post(base, "/prefer", """{"agent":"ada","winner":"beta","loser":"alpha"}""")
            json = await(base) { """"id":"alpha","title":"Alpha","score":0.0000""" in it }
            assertTrue(""""id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in json, json)
        } finally {
            second.stop()
        }

        // and the flip itself was journalled: a third boot sees the final state
        val third = TriageApp(port = 0, journalPath = journal).start()
        try {
            val base = "http://localhost:${third.boundPort}"
            val json = await(base) { """"id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in it }
            assertTrue(""""id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in json, json)
        } finally {
            third.stop()
        }
    }
}
