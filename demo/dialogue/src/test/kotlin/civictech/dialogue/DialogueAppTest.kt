package civictech.dialogue

import civictech.agora.cell.Polarity
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.RuleExtractor
import civictech.dialogue.extract.segmentContentHash
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [DialogueApp]'s HTTP/SSE surface (task computenet-2aw.5.2; epic
 * computenet-2aw §2.4, §3.6 [AGO1-OBS-01]/[AGO1-OBS-02], [AGO1-REPLAY-03]).
 *
 * Every world here is a three-utterance in-test cassette on the **production**
 * scheduler — this is the live-fence path, so a `SimulationController` is not
 * merely unnecessary but wrong ([DialogueRuntime.afterQuiescence]'s KDoc:
 * nothing would step that scheduler while the fence waits). Bounded waits
 * only ([HttpProbe.await], [SseTap.awaitMatching]); the class is seconds, not
 * the minutes [DialogueRuntimeTest]'s 30-turn fixture takes.
 */
class DialogueAppTest {

    // ------------------------------------------------------------------
    // Fixture — DialogueRuntimeSurfaceTest's cassette idiom, three utterances
    // ------------------------------------------------------------------

    private val claimOneText = "Proposition one holds."
    private val claimTwoText = "Proposition two holds."

    private val keyOne = claimKey(claimOneText)
    private val keyTwo = claimKey(claimTwoText)
    private val relationKey = RelationMint.relationKey(keyOne, keyTwo, Polarity.ATTACK)

    private val refOne = BindingTable.refFor(keyOne).id.toString()
    private val refTwo = BindingTable.refFor(keyTwo).id.toString()
    private val edgeRef = BindingTable.refFor(relationKey).id.toString()

    private fun utteranceText(turn: Int) = "Turn $turn speaks."

    private val cassetteEntries: Map<String, List<ExtractedItem>> = buildMap {
        put(
            utteranceText(1),
            listOf(
                ExtractedClaim(text = claimOneText, speaker = "alice", utteranceId = "u1"),
                ExtractedStance(claimText = claimOneText, speaker = "alice", value = 0.8, utteranceId = "u1"),
            ),
        )
        put(
            utteranceText(2),
            listOf(ExtractedClaim(text = claimTwoText, speaker = "bob", utteranceId = "u2")),
        )
        put(
            utteranceText(3),
            listOf(
                ExtractedRelation(
                    sourceText = claimOneText,
                    targetText = claimTwoText,
                    polarity = Polarity.ATTACK.name,
                    utteranceId = "u3",
                ),
            ),
        )
    }.mapKeys { (text, _) ->
        segmentContentHash(Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text))
    }

    private fun cassette(): CassetteExtractor = CassetteExtractor.load(
        StringReader(
            Json.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
                cassetteEntries,
            ),
        ),
    )

    private fun utterance(turn: Int) = Utterance(
        id = "u$turn",
        turn = turn,
        speaker = if (turn == 1) "alice" else "bob",
        tsMillis = 1_000L * turn,
        text = utteranceText(turn),
    )

    /** The three-turn transcript, written where the app can `--transcript` it. */
    private fun transcriptFile(dir: File): File = File(dir, "transcript.jsonl").apply {
        writeText((1..3).joinToString("\n") { Json.encodeToString(Utterance.serializer(), utterance(it)) })
    }

    private fun resource(name: String): File = File(javaClass.getResource("/$name")!!.toURI())

    /**
     * Run [body] against a started app and a probe bound to it, closing both.
     * `port = 0` so concurrent suites on this machine cannot collide.
     */
    private fun <T> serving(app: DialogueApp, body: (DialogueApp, HttpProbe) -> T): T {
        app.start()
        return try {
            HttpProbe("http://localhost:${app.boundPort}").use { body(app, it) }
        } finally {
            app.stop()
        }
    }

    private fun graphNodes(body: String): List<JsonObject> =
        (Json.parseToJsonElement(body) as JsonArray).map { it as JsonObject }

    private fun JsonObject.str(field: String): String? = this[field]?.jsonPrimitive?.content

    // ------------------------------------------------------------------
    // [AGO1-OBS-01] — /graph serves agora's NodeDto, encoded by agora's encoder
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-OBS-01 - GET graph serves the agora NodeDto shape for claims and edges`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { _, probe ->
            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            val body = probe.await(path = "/graph", timeoutMs = 20_000) { it.contains(edgeRef) }

            val nodes = graphNodes(body)
            val refs = nodes.mapNotNull { it.str("ref") }.toSet()

            val claims = nodes.filter { it.str("kind") == "CLAIM" }
            assertEquals(setOf(refOne, refTwo), claims.mapNotNull { it.str("ref") }.toSet())
            claims.forEach { claim ->
                assertNotNull(claim.str("text"), "every CLAIM carries text: $claim")
                assertNotNull(claim["credence"], "every CLAIM carries credence: $claim")
            }
            assertEquals(
                setOf(claimOneText, claimTwoText),
                claims.mapNotNull { it.str("text") }.toSet(),
            )

            val edges = nodes.filter { it.str("kind") == "EDGE" }
            assertEquals(setOf(edgeRef), edges.mapNotNull { it.str("ref") }.toSet())
            edges.forEach { edge ->
                assertEquals(Polarity.ATTACK.name, edge.str("polarity"))
                // the endpoint-closure property the frontend's diff layer needs
                assertContains(refs, edge.str("source")!!, "edge source is a node in the same array")
                assertContains(refs, edge.str("target")!!, "edge target is a node in the same array")
                assertNotNull(edge["credence"], "every EDGE carries credence: $edge")
            }
            assertEquals(refOne, edges.single().str("source"))
            assertEquals(refTwo, edges.single().str("target"))
        }
    }

    // ------------------------------------------------------------------
    // [AGO1-OBS-02] — the SSE frame after a step is POST-reconciliation
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-OBS-02 - the frame after a step carries the post-reconciliation graph`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { app, probe ->
            SseTap("http://localhost:${app.boundPort}/events") { it }.use { tap ->
                // The connect-time frame is the current /graph: empty, because
                // nothing has been admitted yet.
                val first = tap.awaitAtLeast(1, "the connect-time frame").first()
                assertEquals(probe.get("/graph").body(), first, "the tap's first frame is the current /graph")
                assertEquals("[]", first)

                assertEquals(200, probe.postForm("action=step", "/transcript").statusCode())

                // The ordering half, and the one that is mutation-killed: `step`
                // is synchronous through the fence, so by the time it has
                // answered, reconcile() has already run and /graph carries the
                // bound ref with NO waiting at all.
                //
                // Measured in review, so the bound is exact rather than
                // asserted. Replacing `runtime.afterQuiescence { … }` in
                // `settle()` with a bare `run { … }` — reconcile outside the
                // fence, applying mid-wave — reddens THIS line:
                // "CharSequence <[]>, substring <09651f3e-…>", 5 of 10 red.
                // The other variant, deferring the fence with
                // `driver.execute { runtime.afterQuiescence { … } }`, does NOT
                // redden this line: the driver is idle, so the deferred fence
                // usually beats the /graph round trip. It reddens the reset
                // assertion in [AGO1-REPLAY-03] instead ("expected: <[]>"),
                // which pins the same synchronous-through-the-fence property.
                assertContains(probe.get("/graph").body(), refOne)

                // The frame half. Note what this alone cannot discriminate: a
                // newly created claim cell also moves the hub's credence, so
                // `onCredence` would push an equally post-reconciliation frame
                // even with the settle broadcast deleted. Both broadcasts are
                // post-reconciliation by construction — which is the property
                // [AGO1-OBS-02] states — and the assertion above is what pins
                // the construction.
                val frames = tap.awaitMatching("a frame holding the applied claim ref", timeoutMs = 20_000) {
                    it.contains(refOne)
                }
                assertTrue(frames.isNotEmpty())
                assertFalse(frames.first().contains(refTwo), "turn 2's claim is not in it yet")
            }
        }
    }

    // ------------------------------------------------------------------
    // POST /transcript — replay, and [AGO1-REPLAY-02] over HTTP
    // ------------------------------------------------------------------

    @Test
    fun `replay answers 202 and a second identical replay leaves the graph byte-identical`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { _, probe ->
            val accepted = probe.postForm("action=replay&from=1&to=3", "/transcript")
            assertEquals(202, accepted.statusCode())
            assertContains(accepted.body(), """"accepted":true""")
            assertContains(accepted.body(), """"from":1""")
            assertContains(accepted.body(), """"to":3""")

            val first = probe.await(path = "/graph", timeoutMs = 20_000) { it.contains(edgeRef) }

            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            // A replay over an already-admitted range is every-utterance
            // no-ops, so nothing new settles; give it room to prove it by
            // waiting for a synchronous action to drain behind it.
            assertEquals(200, probe.postForm("action=step", "/transcript").statusCode())
            assertEquals(first, probe.get("/graph").body(), "the second replay changed nothing")
        }
    }

    // ------------------------------------------------------------------
    // [AGO1-REPLAY-03] — reset empties the graph, and stepping refills it
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-REPLAY-03 - reset empties the graph and a subsequent step readmits turn 1`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { _, probe ->
            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            probe.await(path = "/graph", timeoutMs = 20_000) { it.contains(edgeRef) }

            val reset = probe.postForm("action=reset", "/transcript")
            assertEquals(200, reset.statusCode())
            assertEquals("""{"reset":true}""", reset.body())
            assertEquals("[]", probe.get("/graph").body(), "[AGO1-REPLAY-03]: the graph is empty after a reset")

            val step = probe.postForm("action=step", "/transcript")
            assertEquals(200, step.statusCode())
            assertContains(step.body(), """"turn":1""")
            probe.await(path = "/graph", timeoutMs = 20_000) { it.contains(refOne) }
        }
    }

    @Test
    fun `step answers admitted null once the transcript is exhausted`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { _, probe ->
            repeat(3) { assertEquals(200, probe.postForm("action=step", "/transcript").statusCode()) }
            assertEquals("""{"admitted":null}""", probe.postForm("action=step", "/transcript").body())
        }
    }

    // ------------------------------------------------------------------
    // POST /transcript — load, and the failure paths
    // ------------------------------------------------------------------

    @Test
    fun `load reports the parsed and rejected counts of the load report`() {
        val fixture = resource("bs16-one-bad-line.jsonl")
        val expected = TranscriptLoader.load(fixture).report

        serving(DialogueApp(port = 0, extractor = RuleExtractor)) { _, probe ->
            val response = probe.postForm("action=load&path=${fixture.absolutePath}", "/transcript")
            assertEquals(200, response.statusCode())
            assertContains(response.body(), """"loaded":${expected.parsedCount}""")
            assertContains(response.body(), """"rejected":${expected.rejectedCount}""")
            assertContains(response.body(), """"lineNumber":${expected.issues.single().lineNumber}""")
        }
    }

    @Test
    fun `a missing file and an unknown action are 400 with no leaked stack trace`() {
        serving(DialogueApp(port = 0, extractor = RuleExtractor)) { _, probe ->
            val missing = probe.postForm("action=load&path=/no/such/transcript.jsonl", "/transcript")
            assertEquals(400, missing.statusCode())
            assertContains(missing.body(), "/no/such/transcript.jsonl")
            assertNoInternals(missing.body())

            val unknown = probe.postForm("action=frobnicate", "/transcript")
            assertEquals(400, unknown.statusCode())
            assertContains(unknown.body(), "frobnicate")
            assertNoInternals(unknown.body())

            val none = probe.postForm("nothing=here", "/transcript")
            assertEquals(400, none.statusCode())
        }
    }

    /**
     * [AGO1-SRC-04]/BS-17 surfaced over HTTP.
     *
     * The bead's parenthetical route — "after `load` of a transcript whose
     * next turn is ≤ the last admitted" — became unreachable when
     * computenet-2aw.5.1 gave `TranscriptSource.load` the constructor's
     * `seekPastAdmitted`: a freshly loaded transcript always resumes *after*
     * the last admitted turn, so `step` skips rather than offers. The
     * remaining honest route to the same rejection is a transcript whose own
     * turns descend, which `descending-turns.jsonl` is.
     */
    @Test
    fun `an out-of-order offer is 400 naming the turn`() {
        val fixture = resource("descending-turns.jsonl")
        serving(DialogueApp(port = 0, extractor = RuleExtractor)) { _, probe ->
            assertEquals(200, probe.postForm("action=load&path=${fixture.absolutePath}", "/transcript").statusCode())

            val admitted = probe.postForm("action=step", "/transcript")
            assertEquals(200, admitted.statusCode())
            assertContains(admitted.body(), """"turn":5""")

            val rejected = probe.postForm("action=step", "/transcript")
            assertEquals(400, rejected.statusCode())
            assertContains(rejected.body(), "turn 3")
            assertContains(rejected.body(), "5")
            assertNoInternals(rejected.body())
        }
    }

    @Test
    fun `GET transcript is 501 until computenet-2aw_5_3 lands`() {
        serving(DialogueApp(port = 0, extractor = RuleExtractor)) { _, probe ->
            assertEquals(501, probe.get("/transcript").statusCode())
        }
    }

    // ------------------------------------------------------------------
    // --journal: a boot on an existing directory reconciles nothing
    // ------------------------------------------------------------------

    @Test
    fun `a journalled boot on an existing directory reconciles with zero structure ops`(@TempDir dir: File) {
        val transcript = transcriptFile(dir)
        val journal = File(dir, "journal").apply { mkdirs() }

        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcript, journalDir = journal)) {
                _, probe ->
            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            probe.await(path = "/graph", timeoutMs = 20_000) { it.contains(edgeRef) }
        }

        val restarted = DialogueApp(
            port = 0,
            extractor = cassette(),
            transcriptFile = transcript,
            journalDir = journal,
        )
        serving(restarted) { app, probe ->
            val boot = assertNotNull(app.bootReconcile, "the boot settle recorded its report")
            assertEquals(
                0,
                boot.structureOps,
                "recovery rebuilt the graph under its recorded refs, so the applier had nothing to create: ${boot.ops}",
            )
            assertTrue(boot.failures.isEmpty(), "no apply failures on recovery: ${boot.failures}")
            // and the recovered graph is the one the first process left behind
            assertContains(probe.get("/graph").body(), edgeRef)
        }
    }

    /**
     * A 400 body names what the caller did wrong and nothing else — the
     * "no leaked internals" standard `AgoraServerTest` holds `/op` to.
     */
    private fun assertNoInternals(body: String) {
        listOf("civictech.", "java.", "\tat ", "Exception:").forEach {
            assertFalse(body.contains(it), "400 body leaked an internal ('$it'): $body")
        }
    }
}
