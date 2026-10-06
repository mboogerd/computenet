package civictech.demo.backlogtriage

import civictech.testkit.HttpProbe
import civictech.testkit.JvmPeer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriageServerTest {

    /** Poll `/features` (or [path]) until [predicate] matches; hard-fails via [HttpProbe.await] on timeout. */
    private fun HttpProbe.awaitFeatures(path: String = "/features", predicate: (String) -> Boolean): String =
        await(path = path, predicate = predicate)

    /** Order of ids as they appear in the /features array. */
    private fun order(json: String): List<String> =
        Regex(""""id":"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()

    private fun assertOnlyHostJournal(dir: Path) {
        val files = Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf(TRIAGE_JOURNAL_FILE), files)
    }

    @Test
    fun `preferences fold into a collective ranking that reorders live`() {
        val app = TriageApp(port = 0).start()
        try {
            assertEquals(
                mapOf(
                    "features" to "features",
                    "prefs" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "score" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "votes" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "rating:elo" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "rating:bt" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "rating:trueskill" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "rating:glicko" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "rating:wenglin" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                    "rating:wilson" to "prefs+score+votes+rating:elo+rating:bt+rating:trueskill+rating:glicko+rating:wenglin+rating:wilson",
                ),
                app.observationGroups,
            )
            assertEquals(
                mapOf("rating:meta" to "rating:meta"),
                app.metaObservationGroups,
            )
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

    @Tag("multi-jvm")
    @Test
    fun `a kill -9 restart rebuilds edited features preferences and ranking from host journal`() {
        val journal = kotlin.io.path.createTempDirectory("triage")
        val peers = mutableListOf<JvmPeer.Peer>()
        var peer = JvmPeer.launch(
            "civictech.demo.backlogtriage.TriageAppKt",
            "0",
            "--journal",
            journal.toString(),
        ).also(peers::add)
        try {
            val firstPort = peer.port("http")
            lateinit var before: String
            lateinit var alphaBefore: String
            HttpProbe("http://localhost:$firstPort").use { probe ->
                assertEquals(200, probe.postJson("""{"id":"alpha","title":"Alpha","body":"# Alpha"}""", "/features").statusCode())
                assertEquals(
                    200,
                    probe.postJson(
                        """{"id":"alpha","title":"Alpha revised","body":"# Alpha revised\nDurable body"}""",
                        "/features",
                    ).statusCode(),
                )
                assertEquals(200, probe.postJson("""{"id":"beta","title":"Beta"}""", "/features").statusCode())
                assertEquals(200, probe.postJson("""{"id":"gamma","title":"Gamma"}""", "/features").statusCode())
                assertEquals(
                    200,
                    probe.postJson("""{"agent":"ada","winner":"alpha","loser":"beta"}""", "/prefer").statusCode(),
                )
                assertEquals(
                    200,
                    probe.postJson("""{"agent":"bo","winner":"alpha","loser":"gamma"}""", "/prefer").statusCode(),
                )
                assertEquals(200, probe.delete("/features/beta").statusCode())

                before = probe.await(path = "/state") { state ->
                    order(state) == listOf("alpha", "gamma") &&
                        """"id":"alpha","title":"Alpha revised","score":1.0000,"wins":1""" in state &&
                        """"prefs":[{"agent":"bo","winner":"alpha","loser":"gamma"}]""" in state
                }
                alphaBefore = probe.get("/features/alpha").body()
                assertTrue("Durable body" in alphaBefore, alphaBefore)
                assertEquals(404, probe.get("/features/beta").statusCode())
            }

            peer.kill()
            assertTrue(peer.process.waitFor(10, TimeUnit.SECONDS), "the first backlog-triage JVM did not die")

            peer = JvmPeer.launch(
                "civictech.demo.backlogtriage.TriageAppKt",
                "0",
                "--journal",
                journal.toString(),
            ).also(peers::add)
            val secondPort = peer.port("http")
            HttpProbe("http://localhost:$secondPort").use { probe ->
                val after = probe.await(path = "/state") { it == before }
                assertEquals(before, after)
                assertEquals(alphaBefore, probe.get("/features/alpha").body())
                assertEquals(404, probe.get("/features/beta").statusCode())

                // The recovered synchronous preference mirror still enforces one direction per pair.
                assertEquals(
                    200,
                    probe.postJson("""{"agent":"bo","winner":"gamma","loser":"alpha"}""", "/prefer").statusCode(),
                )
                val flipped = probe.await(path = "/state") {
                    """"prefs":[{"agent":"bo","winner":"gamma","loser":"alpha"}]""" in it
                }
                assertTrue(""""winner":"alpha","loser":"gamma""" !in flipped, flipped)
            }
            assertOnlyHostJournal(journal)
        } catch (failure: Throwable) {
            throw AssertionError(
                "${failure.message}\n\n${peers.joinToString("\n\n") { it.report() }}",
                failure,
            )
        } finally {
            JvmPeer.destroy(peers)
        }
    }
}
