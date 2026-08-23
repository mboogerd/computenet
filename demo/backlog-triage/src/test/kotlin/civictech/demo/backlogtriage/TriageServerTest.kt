package civictech.demo.backlogtriage

import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriageServerTest {

    /** Poll `/features` (or [path]) until [predicate] matches; hard-fails via [HttpProbe.await] on timeout. */
    private fun HttpProbe.awaitFeatures(path: String = "/features", predicate: (String) -> Boolean): String =
        await(path = path, predicate = predicate)

    /** Order of ids as they appear in the /features array. */
    private fun order(json: String): List<String> =
        Regex(""""id":"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()

    @Test
    fun `preferences fold into a collective ranking that reorders live`() {
        val app = TriageApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            // submit: explicit id, and a slug derived from the title
            assertEquals(200, probe.postJson("""{"id":"bucket-cell","title":"BucketCell","body":"# BucketCell\nthreshold quantization"}""", "/features").statusCode())
            val slugged = probe.postJson("""{"title":"Typed Graph Wiring!"}""", "/features")
            assertTrue(""""id":"typed-graph-wiring"""" in slugged.body(), "id should be slugged: ${slugged.body()}")
            probe.postJson("""{"title":"Relational View DSL","id":"relational-view-dsl"}""", "/features")

            // all three unranked until a preference arrives
            // gate on the asserted state, not a proxy for it (computenet-i6vx):
            // `rank` is a different fold from the feature list itself.
            var json = probe.awaitFeatures { order(it).size == 3 && """"rank":null""" in it }
            assertTrue(""""rank":null""" in json, "features should start unranked: $json")

            // detail endpoint serves the body
            assertTrue("threshold quantization" in probe.get("/features/bucket-cell").body())

            // ada: bucket>typed, bo: bucket>relational, cy: typed>relational
            // scores: bucket +1.0, typed 0.0, relational -1.0
            probe.postJson("""{"agent":"ada","winner":"bucket-cell","loser":"typed-graph-wiring"}""", "/prefer")
            probe.postJson("""{"agent":"bo","winner":"bucket-cell","loser":"relational-view-dsl"}""", "/prefer")
            probe.postJson("""{"agent":"cy","winner":"typed-graph-wiring","loser":"relational-view-dsl"}""", "/prefer")
            json = probe.awaitFeatures {
                order(it) == listOf("bucket-cell", "typed-graph-wiring", "relational-view-dsl") &&
                    """"rank":1,"id":"bucket-cell","title":"BucketCell","score":1.0000""" in it
            }
            assertEquals(listOf("bucket-cell", "typed-graph-wiring", "relational-view-dsl"), order(json), json)
            assertTrue(""""rank":1,"id":"bucket-cell","title":"BucketCell","score":1.0000""" in json, json)

            // ada flips: typed>bucket auto-retracts her reverse vote →
            // typed climbs to the top (score 1.0), bucket drops to 0.0
            probe.postJson("""{"agent":"ada","winner":"typed-graph-wiring","loser":"bucket-cell"}""", "/prefer")
            json = probe.awaitFeatures {
                order(it) == listOf("typed-graph-wiring", "bucket-cell", "relational-view-dsl")
            }
            assertEquals(listOf("typed-graph-wiring", "bucket-cell", "relational-view-dsl"), order(json), json)

            // retraction: cy withdraws → relational loses one loss
            probe.postJson("""{"agent":"cy","winner":"typed-graph-wiring","loser":"relational-view-dsl","retract":"true"}""", "/prefer")
            // T12 finding 2: the predicate below used to omit the "title" field that sits
            // between "id" and "score" in the real payload, so it never actually matched —
            // masked until now by HttpProbe.await's soft timeout silently returning the
            // last-seen body instead of failing (the subsequent assert was weak enough to
            // still pass on it). Corrected to match the real shape.
            json = probe.awaitFeatures {
                """"id":"relational-view-dsl","title":"Relational View DSL","score":-1.0000,"wins":0,"losses":1""" in it
            }
            assertTrue(""""losses":1""" in json, json)

            // removal cascades the feature's preferences out of the ranking
            probe.delete("/features/typed-graph-wiring")
            json = probe.awaitFeatures { order(it) == listOf("bucket-cell", "relational-view-dsl") }
            assertEquals(listOf("bucket-cell", "relational-view-dsl"), order(json), json)

            // /triage: bias-safe worklist. Current state: features bucket-cell
            // + relational-view-dsl (+ typed-graph-wiring re-added below);
            // surviving prefs: only bo's bucket>relational.
            probe.postJson("""{"id":"typed-graph-wiring","title":"Typed Graph Wiring!"}""", "/features")
            probe.awaitFeatures { order(it).size == 3 }
            // /triage is its own read model: gating on /features says nothing about
            // when it settles (computenet-i6vx). Await the state asserted below.
            val bo = probe.await(path = "/triage?agent=bo") {
                """"prefs":[{"winner":"bucket-cell","loser":"relational-view-dsl"}]""" in it &&
                    """"features":[{"id":"typed-graph-wiring","title":"Typed Graph Wiring!","comparisons":0,"mine":0}""" in it
            }
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
            val anon = probe.get("/triage").body()
            assertTrue(""""prefs":[]""" in anon && """"score"""" !in anon, anon)

            // alternative ranking algorithms, computed by the RatingCell /
            // MetaRankCell dataflow (async — poll each folded read model)
            val pipeline = probe.get("/features").body()
            val meanEngine = probe.get("/features?algo=mean").body()
            assertEquals(order(pipeline), order(meanEngine), "algo=mean must serve the cell pipeline")
            for (algo in listOf("elo", "bt", "trueskill", "glicko", "wenglin", "wilson", "meta")) {
                val body = probe.awaitFeatures(path = "/features?algo=$algo") {
                    """"algo":"$algo"""" in it && order(it).firstOrNull() == "bucket-cell"
                }
                assertTrue(order(body).first() == "bucket-cell", "$algo: $body")
            }
            assertEquals(400, probe.get("/features?algo=nope").statusCode())

            assertEquals(400, probe.postJson("""{"body":"no title"}""", "/features").statusCode())
            assertEquals(400, probe.postJson("""{"agent":"a","winner":"bucket-cell","loser":"bucket-cell"}""", "/prefer").statusCode())
            assertEquals(400, probe.postJson("""{"agent":"a","winner":"bucket-cell","loser":"ghost"}""", "/prefer").statusCode())
            assertEquals(404, probe.get("/features/ghost").statusCode())
        } finally {
            app.stop()
        }
    }

    @Test
    fun `journalled features and preferences survive a restart`() {
        val journal = kotlin.io.path.createTempDirectory("triage").resolve("triage.jsonl")

        val first = TriageApp(port = 0, journalPath = journal).start()
        try {
            val probe = HttpProbe("http://localhost:${first.boundPort}")
            probe.postJson("""{"id":"alpha","title":"Alpha","body":"# Alpha"}""", "/features")
            probe.postJson("""{"id":"beta","title":"Beta"}""", "/features")
            probe.postJson("""{"id":"gamma","title":"Gamma"}""", "/features")
            probe.postJson("""{"agent":"ada","winner":"alpha","loser":"beta"}""", "/prefer")
            probe.postJson("""{"agent":"bo","winner":"alpha","loser":"gamma"}""", "/prefer")
            probe.awaitFeatures { order(it).firstOrNull() == "alpha" }
        } finally {
            first.stop()
        }

        val second = TriageApp(port = 0, journalPath = journal).start()
        try {
            val probe = HttpProbe("http://localhost:${second.boundPort}")
            // features, meta, prefs, and the derived ranking all recover
            // (poll on the full condition — the independent hub folds can be
            // momentarily torn across views, the F-5 observation-edge glitch)
            var json = probe.awaitFeatures {
                order(it) == listOf("alpha", "beta", "gamma") &&
                        """"rank":1,"id":"alpha","title":"Alpha","score":1.0000,"wins":2""" in it
            }
            assertTrue(""""rank":1,"id":"alpha","title":"Alpha","score":1.0000,"wins":2""" in json, json)
            assertTrue("# Alpha" in probe.get("/features/alpha").body())
            // the rating cells were rebuilt by journal replay through the prefs cell
            val bt = probe.awaitFeatures(path = "/features?algo=bt") { order(it).firstOrNull() == "alpha" }
            assertEquals("alpha", order(bt).first(), bt)

            // the write-side pref index recovered too: ada's reverse vote
            // still replaces her journalled original instead of stacking
            probe.postJson("""{"agent":"ada","winner":"beta","loser":"alpha"}""", "/prefer")
            // poll the full condition (as above): score and the wins/losses
            // stats are independent folds and can be momentarily torn (F-5)
            json = probe.awaitFeatures { """"id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in it }
            assertTrue(""""id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in json, json)
        } finally {
            second.stop()
        }

        // and the flip itself was journalled: a third boot sees the final state
        val third = TriageApp(port = 0, journalPath = journal).start()
        try {
            val probe = HttpProbe("http://localhost:${third.boundPort}")
            val json = probe.awaitFeatures { """"id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in it }
            assertTrue(""""id":"alpha","title":"Alpha","score":0.0000,"wins":1,"losses":1""" in json, json)
        } finally {
            third.stop()
        }
    }
}
