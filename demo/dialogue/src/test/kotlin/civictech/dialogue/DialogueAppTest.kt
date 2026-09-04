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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    /**
     * The `/graph` a finished `action=replay` leaves behind — a **converged**
     * graph, not merely one that mentions the edge (computenet-xqp9).
     *
     * `/graph` is a live read of `AgoraService.nodes`, and `createEdge`
     * publishes a node into that map *before* the propagation it starts has
     * reached anything. For this fixture, turn 3's edge is therefore visible
     * while "Proposition two holds." still reads its unattacked 0.5 rather
     * than the DF-QuAD 0.3 the 0.8 attacker settles it to; busy-polling
     * `/graph` across a `from=1&to=3` replay hits that state on every run on
     * darwin. So `await { it.contains(edgeRef) }` is a *structure* predicate,
     * and using it as a completeness predicate samples a mid-wave graph —
     * which is what reddened `build-test-fast` three times on 2026-09-04
     * (runs 33870008437, 33872830042, 33873071525, always the same one field
     * at 0.3 where 0.5 had been captured).
     *
     * The wait here is *positive*, not a settling heuristic: `replayInFlight`
     * is cleared in `DialogueApp.replay`'s `finally`, on the driver, after the
     * last `settle()` of that replay has returned — and since computenet-xqp9
     * a `settle()` returns only once the reconcile's own waves have drained.
     * `"replaying":false` therefore *implies* a converged graph. A
     * stability-based wait ("unchanged for N ms") deliberately is not used:
     * it passes by outlasting the window rather than by observing its close,
     * so a slower machine simply widens the window past N and the flake
     * returns.
     */
    private fun HttpProbe.replayedGraph(timeoutMs: Long = 20_000): String {
        await(path = "/transcript", timeoutMs = timeoutMs) { it.contains(""""replaying":false""") }
        return get("/graph").body()
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

            // The baseline has to be a *converged* graph, not the first one
            // that mentions the edge — see [replayedGraph] (computenet-xqp9).
            val first = probe.replayedGraph()
            assertContains(first, edgeRef, message = "the replay admitted all three turns")

            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            // A replay over an already-admitted range is every-utterance
            // no-ops, so nothing new settles; give it room to prove it by
            // waiting for a synchronous action to drain behind it.
            assertEquals(200, probe.postForm("action=step", "/transcript").statusCode())
            assertEquals(first, probe.get("/graph").body(), "the second replay changed nothing")
        }
    }

    /**
     * computenet-xqp9: the moment `GET /transcript` stops saying a replay is
     * in flight, `GET /graph` is **converged** — not merely structurally
     * complete.
     *
     * This is the invariant the flake violated, and the one [replayedGraph]
     * and every future `/graph` comparison rest on. It holds because of two
     * things together, and it goes red if either is removed: `settle()`
     * drains the reconcile's *own* waves before publishing (without that
     * second fence a settle returns with the new edge visible and its
     * target's credence not yet moved), and `GET /transcript` reports the
     * live `replayInFlight` rather than the last settle's snapshot copy
     * (without that it answers `false` for the whole window between the 202
     * and the first settle, and this loop exits immediately).
     *
     * Deliberately a busy poll with nothing between the two reads: any sleep
     * would let the wave land on its own and the test would pass for the
     * wrong reason. Demonstrated red on the pre-fix shape by dropping the
     * second `afterQuiescence` and delaying `AgoraService.routedInfluence` by
     * 300ms — "Proposition two holds." then reads 0.5, the CI signature.
     */
    @Test
    fun `computenet-xqp9 - a finished replay leaves a converged graph, not a mid-wave one`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { _, probe ->
            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            val deadline = System.currentTimeMillis() + 20_000
            var finished = false
            while (!finished && System.currentTimeMillis() < deadline) {
                finished = probe.get("/transcript").body().contains(""""replaying":false""")
            }
            assertTrue(finished, "the replay finished within the bound")

            val graph = probe.get("/graph").body()
            assertContains(graph, edgeRef, message = "the ATTACK edge is in the graph: $graph")
            // DF-QuAD: the 0.8 attacker takes the unattacked 0.5 to 0.3. The
            // whole failure mode is this field reading 0.5 — the edge already
            // published, its influence not yet applied.
            val two = graphNodes(graph).single { it.str("ref") == refTwo }
            assertEquals(0.3, two["credence"]!!.jsonPrimitive.content.toDouble(), 1e-9)
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

    // ------------------------------------------------------------------
    // GET /transcript — computenet-2aw.5.3, [AGO1-OBS-03]
    //
    // Eight utterances, one per outcome the bead's fixture names: (i) a clean
    // claim (extracted), (ii) a claim segment whose extraction yields one
    // blank-text item alongside a good one (rejected,
    // ExtractionGate.malformedReason "blank claim text"), (iii) a segment
    // whose content hash has no cassette entry (failed, CassetteMissException
    // / BS-15), (iv) two utterances asserting the same claim text (two-id
    // provenance, both extracted), one utterance loaded but never admitted
    // (pending) — plus, per computenet-5x1b, two MULTI-segment utterances
    // that pin foldStatus's failed > rejected > pending > extracted
    // precedence rather than merely exercising each branch in isolation:
    // obs03-u6 has one failed segment and one rejected segment (must fold to
    // "failed"), and obs03-u7 has one rejected segment and one extracted
    // segment (must fold to "rejected"). Permuting foldStatus's branch order
    // reddens these two folded-status assertions — see computenet-5x1b.
    // ------------------------------------------------------------------

    private val obs03SharedClaimText = "Shared proposition holds."
    private val obs03BadClaimText = "Second proposition maybe."

    // computenet-5x1b: the two segment texts of obs03-u6, one uncassetted
    // (failed) and one carrying a blank claim (rejected) — pins
    // foldStatus's failed > rejected precedence on a genuinely multi-segment
    // utterance, not a unanimous one.
    private val obs03U6FailingSegmentText = "Alpha will fail badly."
    private val obs03U6RejectedSegmentText = "Beta claim is blank text."

    // The two segment texts of obs03-u7: one rejected, one extracted — pins
    // foldStatus's rejected > extracted precedence the same way.
    private val obs03U7RejectedSegmentText = "Gamma claim is blank as well."
    private val obs03U7ExtractedSegmentText = "Delta proposition stands firmly."

    private fun obs03Utterance(turn: Int, speaker: String, text: String) =
        Utterance(id = "obs03-u$turn", turn = turn, speaker = speaker, tsMillis = 1_000L * turn, text = text)

    private val obs03Utterances = listOf(
        obs03Utterance(1, "alice", "First proposition stands."),
        obs03Utterance(2, "bob", "Second utterance body."),
        obs03Utterance(3, "carol", "Third utterance body."),
        obs03Utterance(4, "dave", "Fourth utterance body."),
        obs03Utterance(5, "erin", "Fifth utterance body."),
        obs03Utterance(6, "gina", "$obs03U6FailingSegmentText $obs03U6RejectedSegmentText"),
        obs03Utterance(7, "hank", "$obs03U7RejectedSegmentText $obs03U7ExtractedSegmentText"),
        obs03Utterance(8, "frank", "Eighth utterance, never admitted."),
    )

    private fun obs03Hash(text: String) =
        segmentContentHash(Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text))

    /**
     * Deliberately omits an entry for turn 3's segment hash — that absence
     * IS the fixture (a cassette miss, BS-15) — and for obs03-u6's first
     * segment, the second cassette miss that gives u6 its failed status.
     */
    private val obs03CassetteEntries: Map<String, List<ExtractedItem>> = mapOf(
        obs03Hash(obs03Utterances[0].text) to listOf(
            ExtractedClaim(text = "First proposition stands.", speaker = "alice", utteranceId = "obs03-u1"),
        ),
        obs03Hash(obs03Utterances[1].text) to listOf(
            ExtractedClaim(text = "", speaker = "bob", utteranceId = "obs03-u2"),
            ExtractedClaim(text = obs03BadClaimText, speaker = "bob", utteranceId = "obs03-u2"),
        ),
        obs03Hash(obs03Utterances[3].text) to listOf(
            ExtractedClaim(text = obs03SharedClaimText, speaker = "dave", utteranceId = "obs03-u4"),
        ),
        obs03Hash(obs03Utterances[4].text) to listOf(
            ExtractedClaim(text = obs03SharedClaimText, speaker = "erin", utteranceId = "obs03-u5"),
        ),
        obs03Hash(obs03U6RejectedSegmentText) to listOf(
            ExtractedClaim(text = "", speaker = "gina", utteranceId = "obs03-u6"),
        ),
        obs03Hash(obs03U7RejectedSegmentText) to listOf(
            ExtractedClaim(text = "", speaker = "hank", utteranceId = "obs03-u7"),
        ),
        obs03Hash(obs03U7ExtractedSegmentText) to listOf(
            ExtractedClaim(text = "Delta proposition stands firmly.", speaker = "hank", utteranceId = "obs03-u7"),
        ),
    )

    private fun obs03Cassette(): CassetteExtractor = CassetteExtractor.load(
        StringReader(
            Json.encodeToString(
                MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
                obs03CassetteEntries,
            ),
        ),
    )

    private fun obs03TranscriptFile(dir: File): File = File(dir, "obs03.jsonl").apply {
        writeText(obs03Utterances.joinToString("\n") { Json.encodeToString(Utterance.serializer(), it) })
    }

    private fun JsonObject.arr(field: String): JsonArray = this[field]!!.jsonArray

    @Test
    fun `AGO1-OBS-03 - GET transcript lists per-utterance extraction status with counts summing to loaded`(
        @TempDir dir: File,
    ) {
        serving(DialogueApp(port = 0, extractor = obs03Cassette(), transcriptFile = obs03TranscriptFile(dir))) {
                _, probe ->
            // Admit turns 1..7, leaving turn 8 loaded but never offered.
            repeat(7) { assertEquals(200, probe.postForm("action=step", "/transcript").statusCode()) }

            val body = probe.get("/transcript").body()
            val json = Json.parseToJsonElement(body).jsonObject

            assertEquals(8, json["loaded"]!!.jsonPrimitive.int, "all eight utterances are loaded: $body")
            assertEquals(7, json["admitted"]!!.jsonPrimitive.int, "seven were admitted: $body")

            val utterances = json.arr("utterances").map { it.jsonObject }
            assertEquals(8, utterances.size)
            val statuses = utterances.map { it.str("status") }
            assertEquals(
                listOf("extracted", "rejected", "failed", "extracted", "extracted", "failed", "rejected", "pending"),
                statuses,
                "statuses in transcript order: $body",
            )
            // obs03-u6 (index 5) folds a [failed, rejected] segment pair to
            // "failed" and obs03-u7 (index 6) folds a [rejected, extracted]
            // segment pair to "rejected" — the precedence pin itself.
            assertEquals("failed", statuses[5], "u6's failed segment must outrank its rejected one: $body")
            assertEquals("rejected", statuses[6], "u7's rejected segment must outrank its extracted one: $body")
            assertEquals(
                listOf(true, true, true, true, true, true, true, false),
                utterances.map { it["admitted"]!!.jsonPrimitive.boolean },
            )

            val counts = json["counts"]!!.jsonObject
            val countSum = listOf("pending", "extracted", "rejected", "failed")
                .sumOf { counts[it]!!.jsonPrimitive.int }
            assertEquals(8, countSum, "counts sum to loaded: $counts")
            assertEquals(3, counts["extracted"]!!.jsonPrimitive.int)
            assertEquals(2, counts["rejected"]!!.jsonPrimitive.int)
            assertEquals(2, counts["failed"]!!.jsonPrimitive.int)
            assertEquals(1, counts["pending"]!!.jsonPrimitive.int)

            // Three rejected ITEMS, not two: obs03-u6's second segment
            // ("Beta claim is blank text.") is itself a rejected item, on
            // top of the one that makes obs03-u2 rejected and the one that
            // makes obs03-u7's first segment rejected — a segment can be
            // "the" rejected segment of its utterance while still
            // contributing only one item to this item-level ledger.
            val rejected = json.arr("rejected").map { it.jsonObject }
            assertEquals(3, rejected.size)
            val rejectedBySegment = rejected.associateBy { it.str("segmentId") }
            assertEquals("blank claim text", rejectedBySegment.getValue("obs03-u2#0").str("reason"))
            assertEquals("blank claim text", rejectedBySegment.getValue("obs03-u6#1").str("reason"))
            assertEquals("blank claim text", rejectedBySegment.getValue("obs03-u7#0").str("reason"))

            val failed = json.arr("failed").map { it.jsonObject }
            assertEquals(2, failed.size)
            val failedBySegment = failed.associateBy { it.str("segmentId") }
            assertContains(failedBySegment.getValue("obs03-u3#0").str("reason")!!, "CassetteMissException")
            assertContains(failedBySegment.getValue("obs03-u6#0").str("reason")!!, "CassetteMissException")
        }
    }

    // ------------------------------------------------------------------
    // foldStatus's precedence, pinned entirely through HTTP — computenet-kygh,
    // computenet-miei, then computenet-if9j
    //
    // The obs03 fixture above genuinely pins failed > rejected (obs03-u6) and
    // rejected > extracted (obs03-u7), but on its own cannot reach the
    // `pending` rung or the empty-list branch: a `pending` segment status only
    // comes from `SegmentStatus.Unknown` (extraction never ran for that
    // segment), and every settle()-fenced action — step/reset, the boot load,
    // and each admission of a replay (afterAdmit settles every one) — drains
    // the whole host queue before `refreshSnapshot` ever runs, so every
    // segment of an admitted utterance reached that way is already
    // `Extracted` or `Failed` by the time a snapshot is rebuilt.
    //
    // computenet-kygh shipped that as a structural impossibility across
    // DialogueApp's WHOLE documented action surface. That claim was wrong,
    // and computenet-miei falsified it: `action=load` does NOT go through
    // `settle()` before refreshing — `DialogueApp.load` calls
    // `refreshSnapshot()` directly, and `TranscriptSource.load` leaves the
    // admitted ledger untouched by design ("Loading is therefore not a
    // reset"). So stepping to admit `u1`, then loading a second transcript in
    // which `u1` (same id, same turn) now carries additional text,
    // re-segments `u1` against a segment accounting never saw: that
    // segment's status reads `SegmentStatus.Unknown`, i.e. `pending`, with no
    // race and no settle involved. `computenet-miei`'s test below drives
    // exactly that sequence and asserts the folded `pending` through
    // `GET /transcript` — but it pins `pending > extracted` only, never
    // putting a `rejected` segment alongside the load-introduced `pending`
    // one in the same utterance.
    //
    // computenet-if9j closes the two rungs `computenet-miei` left unpinned,
    // both through the same HTTP surface:
    //
    // - `rejected > pending` from the *same* step-then-load shape, with a
    //   cassette whose only entry maps the first segment's text to a blank
    //   claim (`[rejected, pending]` -> `rejected`).
    // - the empty-list `-> extracted` branch from a single `step` on a
    //   blank-text utterance (zero segments -> `extracted`), exactly as
    //   [DialogueApp.refreshSnapshot]'s own KDoc predicted.
    //
    // With all four rungs (failed > rejected > pending > extracted) and the
    // empty-list branch now pinned at the HTTP level, the direct
    // `foldStatus` precedence test that used to require `internal`
    // visibility is redundant and is deleted here; `DialogueApp.foldStatus`
    // goes back to `private` — see its own KDoc for the full reasoning.
    // ------------------------------------------------------------------

    @Test
    fun `computenet-miei - step then load leaves an admitted utterance's new segment pending, folding to pending through GET transcript`(
        @TempDir dir: File,
    ) {
        val firstText = "Alpha proposition stands."
        val secondText = "Alpha proposition stands. Beta proposition stands."
        fun singleUtteranceFile(name: String, text: String): File {
            val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1_000L, text = text)
            return File(dir, name).apply { writeText(Json.encodeToString(Utterance.serializer(), u1)) }
        }
        val firstFile = singleUtteranceFile("miei-first.jsonl", firstText)
        val secondFile = singleUtteranceFile("miei-second.jsonl", secondText)

        serving(DialogueApp(port = 0, extractor = RuleExtractor, transcriptFile = firstFile)) { _, probe ->
            assertEquals(200, probe.postForm("action=step", "/transcript").statusCode())

            val afterStep = Json.parseToJsonElement(probe.get("/transcript").body()).jsonObject
            val stepUtterance = afterStep.arr("utterances").single().jsonObject
            assertEquals(
                "extracted",
                stepUtterance.str("status"),
                "u1 extracts cleanly from the first file before load: $afterStep",
            )

            val load = probe.postForm("action=load&path=${secondFile.absolutePath}", "/transcript")
            assertEquals(200, load.statusCode(), "load of the second file is accepted")

            val afterLoad = Json.parseToJsonElement(probe.get("/transcript").body()).jsonObject
            val utterance = afterLoad.arr("utterances").single().jsonObject
            assertEquals(
                true,
                utterance["admitted"]!!.jsonPrimitive.boolean,
                "u1 stays admitted across load — TranscriptSource.load leaves the admitted ledger untouched: $afterLoad",
            )
            val segmentStatuses = utterance.arr("segments").map { it.jsonObject.str("status") }
            assertEquals(
                listOf("extracted", "pending"),
                segmentStatuses,
                "u1's second segment is new text load never offered to extraction, so accounting has no " +
                    "record for it (SegmentStatus.Unknown -> pending) while the first segment's earlier " +
                    "extraction stands: $afterLoad",
            )
            assertEquals(
                "pending",
                utterance.str("status"),
                "foldStatus folds [extracted, pending] to pending — the pending > extracted rung, pinned " +
                    "through the HTTP surface: $afterLoad",
            )
        }
    }

    @Test
    fun `computenet-if9j - step then load leaves a rejected segment outranking a newly-pending one, folding to rejected`(
        @TempDir dir: File,
    ) {
        val firstText = "Alpha claim is blank text."
        val secondText = "Alpha claim is blank text. Beta proposition stands."

        fun singleUtteranceFile(name: String, text: String): File {
            val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1_000L, text = text)
            return File(dir, name).apply { writeText(Json.encodeToString(Utterance.serializer(), u1)) }
        }
        val firstFile = singleUtteranceFile("if9j-rejpend-first.jsonl", firstText)
        val secondFile = singleUtteranceFile("if9j-rejpend-second.jsonl", secondText)

        // The only cassette entry maps the first (and, on the first file,
        // only) segment's text to a blank claim, so it extracts to
        // ExtractionGate's "blank claim text" rejection.
        val firstSegmentHash = segmentContentHash(
            Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = firstText),
        )
        val rejectingCassette = CassetteExtractor.load(
            StringReader(
                Json.encodeToString(
                    MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
                    mapOf(firstSegmentHash to listOf(ExtractedClaim(text = "", speaker = "alice", utteranceId = "u1"))),
                ),
            ),
        )

        serving(DialogueApp(port = 0, extractor = rejectingCassette, transcriptFile = firstFile)) { _, probe ->
            assertEquals(200, probe.postForm("action=step", "/transcript").statusCode())

            val afterStep = Json.parseToJsonElement(probe.get("/transcript").body()).jsonObject
            val stepUtterance = afterStep.arr("utterances").single().jsonObject
            assertEquals(
                "rejected",
                stepUtterance.str("status"),
                "u1's single segment extracts to a blank claim, folding to rejected before load: $afterStep",
            )

            val load = probe.postForm("action=load&path=${secondFile.absolutePath}", "/transcript")
            assertEquals(200, load.statusCode(), "load of the second file is accepted")

            val afterLoad = Json.parseToJsonElement(probe.get("/transcript").body()).jsonObject
            val utterance = afterLoad.arr("utterances").single().jsonObject
            assertEquals(
                true,
                utterance["admitted"]!!.jsonPrimitive.boolean,
                "u1 stays admitted across load — TranscriptSource.load leaves the admitted ledger untouched: $afterLoad",
            )
            val segmentStatuses = utterance.arr("segments").map { it.jsonObject.str("status") }
            assertEquals(
                listOf("rejected", "pending"),
                segmentStatuses,
                "u1's first segment keeps its earlier rejection while its second segment is new text load never " +
                    "offered to extraction (SegmentStatus.Unknown -> pending): $afterLoad",
            )
            assertEquals(
                "rejected",
                utterance.str("status"),
                "foldStatus folds [rejected, pending] to rejected — the rejected > pending rung, pinned " +
                    "through the HTTP surface: $afterLoad",
            )
        }
    }

    @Test
    fun `computenet-if9j - step on a blank-text utterance segments to zero segments and folds to extracted`(
        @TempDir dir: File,
    ) {
        val blankFile = File(dir, "if9j-blank.jsonl").apply {
            val u1 = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1_000L, text = "   ")
            writeText(Json.encodeToString(Utterance.serializer(), u1))
        }

        serving(DialogueApp(port = 0, extractor = RuleExtractor, transcriptFile = blankFile)) { _, probe ->
            assertEquals(200, probe.postForm("action=step", "/transcript").statusCode())

            val body = probe.get("/transcript").body()
            val json = Json.parseToJsonElement(body).jsonObject
            val utterance = json.arr("utterances").single().jsonObject

            assertEquals(true, utterance["admitted"]!!.jsonPrimitive.boolean, "the blank utterance is admitted: $body")
            assertTrue(
                utterance.arr("segments").isEmpty(),
                "an all-whitespace utterance segments to zero segments (Segmentation trims and drops blanks): $body",
            )
            assertEquals(
                "extracted",
                utterance.str("status"),
                "foldStatus folds an empty segment list to extracted — the empty-list branch, pinned through " +
                    "the HTTP surface: $body",
            )
        }
    }

    // ------------------------------------------------------------------
    // GET /provenance — computenet-2aw.5.3, 2aw.F5-D1, [AGO1-PROV-02]/[AGO1-PROV-04]
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-PROV-02 - GET provenance by ref answers the justifying utterances for a two-id claim`(
        @TempDir dir: File,
    ) {
        serving(DialogueApp(port = 0, extractor = obs03Cassette(), transcriptFile = obs03TranscriptFile(dir))) {
                _, probe ->
            repeat(5) { assertEquals(200, probe.postForm("action=step", "/transcript").statusCode()) }

            val sharedKey = claimKey(obs03SharedClaimText)
            val sharedRef = BindingTable.refFor(sharedKey).id.toString()

            val body = probe.get("/provenance?ref=$sharedRef").body()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(true, json["bound"]!!.jsonPrimitive.boolean, body)
            assertEquals("claim", json.str("kind"))
            assertEquals(sharedKey.value, json.str("key"))

            val utterances = json.arr("utterances").map { it.jsonObject }
            assertEquals(listOf("obs03-u4", "obs03-u5"), utterances.map { it.str("id") }, "turn order: $body")
            assertEquals(listOf("dave", "erin"), utterances.map { it.str("speaker") })
            assertEquals(listOf(4, 5), utterances.map { it["turn"]!!.jsonPrimitive.int })
            utterances.forEach { assertNotNull(it.str("text")) }

            // ?key= is the secondary lookup and resolves the same answer, ref included.
            val encodedKey = java.net.URLEncoder.encode(sharedKey.value, Charsets.UTF_8)
            val byKey = Json.parseToJsonElement(probe.get("/provenance?key=$encodedKey").body()).jsonObject
            assertEquals(sharedRef, byKey.str("ref"))
            assertEquals("claim", byKey.str("kind"))
            assertEquals(
                utterances.map { it.str("id") },
                byKey.arr("utterances").map { it.jsonObject.str("id") },
            )
        }
    }

    @Test
    fun `AGO1-PROV-02 - GET provenance for a bound relation names its producing utterance`(@TempDir dir: File) {
        serving(DialogueApp(port = 0, extractor = cassette(), transcriptFile = transcriptFile(dir))) { _, probe ->
            assertEquals(202, probe.postForm("action=replay&from=1&to=3", "/transcript").statusCode())
            probe.await(path = "/graph", timeoutMs = 20_000) { it.contains(edgeRef) }

            val body = probe.get("/provenance?ref=$edgeRef").body()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(true, json["bound"]!!.jsonPrimitive.boolean, body)
            assertEquals("relation", json.str("kind"))
            val utterances = json.arr("utterances").map { it.jsonObject }
            assertEquals(listOf("u3"), utterances.map { it.str("id") }, "the relation's producing utterance: $body")
        }
    }

    @Test
    fun `AGO1-PROV-04 - an unbound ref is 200 bound false with no utterances field, distinct from a bound empty set`() {
        serving(DialogueApp(port = 0, extractor = RuleExtractor)) { _, probe ->
            val response = probe.get("/provenance?ref=${java.util.UUID.randomUUID()}")
            assertEquals(200, response.statusCode())
            val json = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals(false, json["bound"]!!.jsonPrimitive.boolean)
            assertFalse(json.containsKey("utterances"), "an unbound ref carries no utterances field: ${response.body()}")

            val badRef = probe.get("/provenance?ref=not-a-uuid")
            assertEquals(400, badRef.statusCode())
            assertNoInternals(badRef.body())

            val noParams = probe.get("/provenance")
            assertEquals(400, noParams.statusCode())
            assertNoInternals(noParams.body())

            val unboundKey = probe.get("/provenance?key=no-such-key")
            assertEquals(200, unboundKey.statusCode())
            val keyJson = Json.parseToJsonElement(unboundKey.body()).jsonObject
            assertEquals(false, keyJson["bound"]!!.jsonPrimitive.boolean)
            assertFalse(keyJson.containsKey("utterances"))
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
            // Not `await { contains(edgeRef) }`: that returns while turn 3's
            // `createEdge` is still running, and `serving` then stops the app
            // inside that window — computenet-t3sp's lost-EDGE signature.
            // `AgoraService.createEdge` now appends its structure op with
            // nothing interruptible between the publication into `nodes` and
            // the append (computenet-xqp9, computenet-f7y8), so the window no
            // longer loses the edge; leaving on a converged graph keeps this
            // test off that seam regardless, and is what makes the `/graph`
            // comparison after the restart a comparison of settled states.
            assertContains(probe.replayedGraph(), edgeRef)
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
