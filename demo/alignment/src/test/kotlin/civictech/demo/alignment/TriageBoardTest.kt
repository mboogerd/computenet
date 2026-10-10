package civictech.demo.alignment

import civictech.cell.durability.FileJournal
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
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Eisenhower triage board end to end (feature computenet-i00bh): seeding a
 * standing round from a [CandidateSource], [BeadsHeuristic] rating into the AI
 * score beside a human and an agent in the human one, the quadrant on `/aggregate`, the bias-safe `/worklist`, and journal
 * replay of a seeded round.
 *
 * No `bd` subprocess runs here — the seam takes a fixture source, which is the
 * point of it ([BdCandidateSource]'s own parsing is [TriageTest]'s). The read
 * model is async, so every assertion on it is an [HttpProbe.await] on the
 * asserted state itself.
 */
class TriageBoardTest {

    /** Both axes maxed: P0, dependents at the cap, stale past the cap → 9.0/9.0. */
    private val top = Candidate("computenet-top", "Top epic", "the top one", "epic", 0, 5, 60)

    /** Both axes floored: P3, no dependents, fresh → 1.0/1.0. */
    private val bottom = Candidate("computenet-bot", "Bottom epic", "the low one", "epic", 3, 0, 0)

    /** No priority → the heuristic abstains on both axes, so this one arrives unrated. */
    private val blind = Candidate("computenet-bld", "Blind epic", "", "epic", null, 5, 60)

    private fun source(vararg c: Candidate) = CandidateSource { c.toList() }

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun row(aggregate: String, id: String): JsonObject? =
        parse(aggregate)["ideas"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["id"]!!.jsonPrimitive.content == id }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDouble()

    /** The row's AI side, or null while the AI population has not rated it. */
    private fun JsonObject.ai(): JsonObject? = this["ai"] as? JsonObject

    private fun HttpProbe.awaitRow(id: String, predicate: (JsonObject) -> Boolean): JsonObject {
        val body = await(path = "/topics/triage/aggregate") { b -> row(b, id)?.let(predicate) == true }
        return row(body, id)!!
    }

    private fun withApp(journal: Path? = null, body: (AlignmentApp, HttpProbe) -> Unit) {
        val app = AlignmentApp(port = 0, journalPath = journal).start()
        try {
            HttpProbe("http://localhost:${app.boundPort}").use { body(app, it) }
        } finally {
            app.stop()
        }
    }

    private fun tmpJournal(): Path = createTempDirectory("triage")

    private fun journalRecords(dir: Path): Int =
        FileJournal(dir.resolve(ALIGNMENT_JOURNAL_FILE).toFile()).replay().size

    private fun worklist(probe: HttpProbe, who: String, dim: String = Eisenhower.IMPORTANCE): JsonObject =
        parse(probe.get("/topics/triage/worklist?participant=$who&dim=$dim").body())

    private fun ids(worklist: JsonObject): List<String> =
        worklist["ideas"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun judge(probe: HttpProbe, who: String, a: String, b: String, outcome: String, dim: String = Eisenhower.IMPORTANCE) {
        val r = probe.postJson(
            """{"participant":"$who","dim":"$dim","a":"$a","b":"$b","outcome":"$outcome"}""",
            "/topics/triage/judge",
        )
        assertEquals(200, r.statusCode(), r.body())
    }

    // ── seeding ──────────────────────────────────────────────────────────

    /**
     * The whole phase-1 slice: real-shaped candidates in, a two-dimension
     * Eisenhower topic out, ideas keyed by BEAD ID, the heuristic's ratings in
     * the AI score and its abstention left genuinely unrated.
     */
    @Test
    fun `seeding builds the Eisenhower round, keyed by bead id, with the heuristic rating and abstaining`() =
        withApp(tmpJournal()) { app, probe ->
            assertEquals(3, seedBeadsTriage(app, source(top, bottom, blind)))

            val topic = parse(probe.get("/topics").body().removeSurrounding("[", "]"))
            assertEquals("triage", topic["id"]!!.jsonPrimitive.content)
            assertEquals("facilitator", topic["ideas"]!!.jsonPrimitive.content, "a seeded board is facilitator-only")
            assertEquals(
                listOf("importance", "urgency"),
                topic["dimensions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
            )
            assertTrue(
                topic["dimensions"]!!.jsonArray.all { it.jsonObject["direction"]!!.jsonPrimitive.content == "value" },
                "both Eisenhower axes are VALUE-direction: $topic",
            )

            // the idea id IS the bead id — no slug of the title, so Phase 2 needs no lookup table
            val t = probe.awaitRow("computenet-top") { it.ai()?.num("score") != null }
            assertEquals("Top epic", t["title"]!!.jsonPrimitive.content)
            assertEquals(9.0, t.ai()!!.num("score"), "importance 9 and urgency 9, equally weighted")
            assertEquals("do", t.ai()!!["quadrant"]!!.jsonPrimitive.content)
            // the heuristic is not a human: the human side is untouched and nothing is ranked
            assertEquals(JsonNull, t["score"], "$t")
            assertEquals(JsonNull, t["rank"], "$t")
            assertEquals(0, t["raters"]!!.jsonPrimitive.content.toInt())

            val b = probe.awaitRow("computenet-bot") { it.ai()?.num("score") != null }
            assertEquals(1.0, b.ai()!!.num("score"))
            assertEquals("drop", b.ai()!!["quadrant"]!!.jsonPrimitive.content)

            // the heuristic declined: absence, not a middling 5 — no AI side at all
            val board = probe.get("/topics/triage/aggregate").body()
            val blindRow = row(board, "computenet-bld")!!
            assertEquals(JsonNull, blindRow["ai"], "$blindRow")
            assertEquals(JsonNull, blindRow["score"], "$blindRow")
            assertEquals(JsonNull, blindRow["quadrant"], "$blindRow")
            // unranked rows read in the AI's order until a human rates
            assertEquals(
                listOf("computenet-top", "computenet-bot", "computenet-bld"),
                parse(board)["ideas"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
            )
        }

    /**
     * Before the AI score, the heuristic wrote as participant `jev` into the
     * HUMAN ratings. A re-seed over such a journal removes those rows, once.
     */
    @Test
    fun `a re-seed drops the heuristic's legacy human-side ratings`() = withApp(tmpJournal()) { app, probe ->
        seedBeadsTriage(app, source(top, bottom))
        app.seedRating(TRIAGE_TOPIC, "computenet-top", Eisenhower.IMPORTANCE, LEGACY_HEURISTIC_PARTICIPANT, 9.0)
        probe.awaitRow("computenet-top") { it["raters"]!!.jsonPrimitive.content == "1" }

        seedBeadsTriage(app, source(top, bottom))
        val t = probe.awaitRow("computenet-top") { it["raters"]!!.jsonPrimitive.content == "0" }
        assertEquals(1, t.ai()!!["raters"]!!.jsonPrimitive.content.toInt(), "the heuristic still rates, AI-side: $t")
    }

    /** A re-seed of an unchanged tracker writes no journal line: a standing round is re-seedable on every boot. */
    @Test
    fun `re-seeding an unchanged round is journal-silent`() {
        val journal = tmpJournal()
        withApp(journal) { app, _ ->
            seedBeadsTriage(app, source(top, bottom))
        }
        withApp(journal) { app, _ ->
            val beforeReseed = journalRecords(journal)
            seedBeadsTriage(app, source(top, bottom))
            seedBeadsTriage(app, source(top, bottom))
            assertEquals(beforeReseed, journalRecords(journal), "an unchanged re-seed appended frames")
        }
    }

    /** A candidate that has left `bd ready` keeps its row and its human ratings (removal would cascade them away). */
    @Test
    fun `a candidate that leaves the ready set keeps its row`() = withApp(tmpJournal()) { app, probe ->
        seedBeadsTriage(app, source(top, bottom))
        probe.postJson(
            """{"participant":"ann","idea":"computenet-bot","dim":"importance","value":7}""",
            "/topics/triage/rate",
        )
        probe.awaitRow("computenet-bot") { it["raters"]!!.jsonPrimitive.content.toInt() == 1 }

        seedBeadsTriage(app, source(top)) // bottom is gone from the tracker's ready set
        val b = row(probe.get("/topics/triage/aggregate").body(), "computenet-bot")!!
        assertEquals(1, b["raters"]!!.jsonPrimitive.content.toInt(), "ann's rating survived the re-seed: $b")
    }

    /**
     * A failed candidate fetch writes NOTHING (computenet-1f8b4): the fetch is
     * the step that can fail — a bad workspace path, no `bd` on PATH, a refused
     * export — and a failure after the topic was journaled would leave a
     * half-seeded round on disk for the next boot to inherit, serving an empty
     * board an operator cannot tell apart from a genuinely empty ready queue.
     */
    @Test
    fun `a failed candidate fetch leaves no topic and writes no application frames`() {
        val journal = tmpJournal()
        val boom = CandidateSource { error("bd ready exited 1 against /nope") }
        withApp(journal) { app, probe ->
            val recordsBeforeFetch = journalRecords(journal)
            val thrown = assertFailsWith<IllegalStateException> { seedBeadsTriage(app, boom) }
            assertTrue("bd ready exited 1" in (thrown.message ?: ""), thrown.message ?: "")
            assertEquals("[]", probe.get("/topics").body(), "no topic was created")
            assertEquals(recordsBeforeFetch, journalRecords(journal), "the failed fetch must not write an application frame")
        }
    }

    // ── the rater classes together ───────────────────────────────────

    /**
     * Human and agent are one population; the heuristic is the AI one. The agent
     * judges pairwise and [PairwiseFit] turns that into ratings on the human
     * side; a human disagreeing with the heuristic shows as `diverges`, not as
     * `split` — `split` is people disagreeing with people — and the
     * facilitator's override stays advisory on top.
     */
    @Test
    fun `human and agent rate as one population, the heuristic as the AI one, and disagreement diverges`() =
        withApp(tmpJournal()) { app, probe ->
            seedBeadsTriage(app, source(top, bottom))
            probe.awaitRow("computenet-top") { it.ai()?.num("score") != null }

            // a human disagrees hard with the heuristic's 9.0 on importance
            assertEquals(
                200,
                probe.postJson(
                    """{"participant":"ann","idea":"computenet-top","dim":"importance","value":2}""",
                    "/topics/triage/rate",
                ).statusCode(),
            )
            var t = probe.awaitRow("computenet-top") { it["raters"]!!.jsonPrimitive.content.toInt() == 1 }
            assertEquals("false", t["split"]!!.jsonPrimitive.content, "one human cannot split: $t")
            assertEquals(2.0, t["byDim"]!!.jsonObject["importance"]!!.jsonObject.num("mean"), "the human mean is ann's alone")
            assertEquals(listOf("importance"), t["diverges"]!!.jsonArray.map { it.jsonPrimitive.content }, "|2 - 9| >= 2: $t")

            // an agent judges pairwise on the same axis; its derived ratings join the HUMAN map
            judge(probe, "agent-1", "computenet-top", "computenet-bot", "a")
            t = probe.awaitRow("computenet-top") { it["raters"]!!.jsonPrimitive.content.toInt() == 2 }
            val importance = t["byDim"]!!.jsonObject["importance"]!!.jsonObject
            assertEquals(2L, importance["n"]!!.jsonPrimitive.content.toLong(), "ann and agent-1: $t")
            assertEquals(1, t.ai()!!["raters"]!!.jsonPrimitive.content.toInt(), "the AI side is the heuristic alone: $t")

            // the facilitator's final say rides on top, leaving the computed score visible
            assertEquals(
                200,
                probe.putJson(
                    """{"creator":"facilitator","score":8.5}""",
                    "/topics/triage/ideas/computenet-top/override",
                ).statusCode(),
            )
            t = probe.awaitRow("computenet-top") { it.num("override") != null }
            assertEquals(8.5, t.num("override"))
            assertEquals(1, t["rank"]!!.jsonPrimitive.content.toInt())
            assertTrue(t.num("score") != null && t.num("score") != 8.5, "the computed score is unchanged: $t")
        }

    // ── the worklist ─────────────────────────────────────────────────────

    /**
     * Bias-safety is the worklist's whole contract: an agent must not be able to
     * see the collective ranking it is about to contribute to. Asserted as the
     * ABSENCE of the aggregate's key names, the same way backlog-triage's
     * `TriageServerTest` does for `/triage`.
     */
    @Test
    fun `the worklist leaks no aggregate and no other participant`() = withApp(tmpJournal()) { app, probe ->
        seedBeadsTriage(app, source(top, bottom))
        probe.postJson(
            """{"participant":"ann","idea":"computenet-top","dim":"importance","value":2}""",
            "/topics/triage/rate",
        )
        probe.awaitRow("computenet-top") { it["raters"]!!.jsonPrimitive.content.toInt() == 1 }

        val body = probe.get("/topics/triage/worklist?participant=agent-1&dim=importance").body()
        for (leak in listOf("\"rank\"", "\"score\"", "\"quadrant\"", "\"mean\"", "\"stdev\"", "\"split\"", "\"raters\"", "\"byDim\"", "\"ann\"", "ai:beads-heuristic", "\"ai\"", "\"proposer\"")) {
            assertTrue(leak !in body, "the worklist leaked $leak: $body")
        }
        val w = parse(body)
        assertEquals("agent-1", w["participant"]!!.jsonPrimitive.content)
        assertEquals("importance", w["dim"]!!.jsonPrimitive.content)
        assertEquals(setOf("computenet-bot", "computenet-top"), ids(w).toSet())
        // its OWN rating is visible (a rater may slide instead of judge), and is null here
        assertTrue(w["ideas"]!!.jsonArray.all { it.jsonObject["rated"] == JsonNull }, "$w")
    }

    /**
     * Coverage ordering and the suggested pair: least-judged-by-me first, and
     * `next` is a pair this participant has not judged. The floor is two
     * judgements per idea.
     */
    @Test
    fun `the worklist orders by the caller's own coverage and suggests an unjudged pair`() =
        withApp(tmpJournal()) { app, probe ->
            val third = Candidate("computenet-mid", "Mid epic", "", "epic", 1, 2, 10)
            seedBeadsTriage(app, source(top, bottom, third))

            val fresh = worklist(probe, "agent-1")
            assertEquals("false", fresh["phase1Complete"]!!.jsonPrimitive.content)
            assertTrue(fresh["ideas"]!!.jsonArray.all { it.jsonObject["mine"]!!.jsonPrimitive.content == "0" })
            val suggested = fresh["next"]!!.jsonObject
            assertTrue(
                suggested["a"]!!.jsonPrimitive.content != suggested["b"]!!.jsonPrimitive.content,
                "a suggested pair names two different ideas: $suggested",
            )

            // judge one pair: those two are now covered once, the untouched third sorts first
            judge(probe, "agent-1", "computenet-top", "computenet-bot", "a")
            val after = worklist(probe, "agent-1")
            assertEquals("computenet-mid", ids(after).first(), "the uncovered idea leads: $after")
            val mine = after["ideas"]!!.jsonArray.associate {
                it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["mine"]!!.jsonPrimitive.content.toInt()
            }
            assertEquals(mapOf("computenet-mid" to 0, "computenet-top" to 1, "computenet-bot" to 1), mine)
            assertEquals(1, after["judgements"]!!.jsonArray.size)
            // the suggested pair is never one already judged
            val next = after["next"]!!.jsonObject
            assertTrue(
                setOf(next["a"]!!.jsonPrimitive.content, next["b"]!!.jsonPrimitive.content) !=
                    setOf("computenet-top", "computenet-bot"),
                "$after",
            )

            // another participant's coverage is their own: agent-2 still sees all zeroes
            assertTrue(
                worklist(probe, "agent-2")["ideas"]!!.jsonArray
                    .all { it.jsonObject["mine"]!!.jsonPrimitive.content == "0" },
                "coverage is per participant",
            )

            // cover every idea twice → the floor is met and next runs out
            judge(probe, "agent-1", "computenet-top", "computenet-mid", "a")
            judge(probe, "agent-1", "computenet-bot", "computenet-mid", "b")
            val complete = worklist(probe, "agent-1")
            assertEquals("true", complete["phase1Complete"]!!.jsonPrimitive.content, "$complete")
            assertEquals(JsonNull, complete["next"]!!, "every pair judged: $complete")
        }

    @Test
    fun `the worklist refuses a missing or unknown dimension`() = withApp { app, probe ->
        seedBeadsTriage(app, source(top, bottom))
        assertEquals(400, probe.get("/topics/triage/worklist?participant=agent-1").statusCode())
        assertEquals(400, probe.get("/topics/triage/worklist?participant=agent-1&dim=effort").statusCode())
        assertEquals(400, probe.get("/topics/triage/worklist?dim=importance").statusCode(), "no participant")
        assertEquals(404, probe.get("/topics/nope/worklist?participant=a&dim=importance").statusCode())
    }

    // ── durability ───────────────────────────────────────────────────────

    /** A seeded round, the heuristic's ratings and the human's all survive a restart over the same journal. */
    @Test
    fun `a seeded round survives a restart`() {
        val journal = tmpJournal()
        withApp(journal) { app, probe ->
            seedBeadsTriage(app, source(top, bottom))
            probe.postJson(
                """{"participant":"ann","idea":"computenet-bot","dim":"urgency","value":6}""",
                "/topics/triage/rate",
            )
            judge(probe, "agent-1", "computenet-top", "computenet-bot", "a")
            probe.awaitRow("computenet-bot") { it["raters"]!!.jsonPrimitive.content.toInt() == 2 }
        }
        withApp(journal) { _, probe ->
            val t = probe.awaitRow("computenet-top") { it.ai()?.num("score") != null }
            assertEquals("do", t.ai()!!["quadrant"]!!.jsonPrimitive.content, "$t")
            // ann's urgency for `bottom` is 6.0 and the heuristic's 1.0: both replayed, each on its own side
            val b = probe.awaitRow("computenet-bot") { it["raters"]!!.jsonPrimitive.content == "2" && it.ai() != null }
            assertEquals(6.0, b["byDim"]!!.jsonObject["urgency"]!!.jsonObject.num("mean"), "$b")
            assertEquals(1.0, b.ai()!!["byDim"]!!.jsonObject["urgency"]!!.jsonObject.num("mean"), "$b")
            assertTrue("urgency" in b["diverges"]!!.jsonArray.map { it.jsonPrimitive.content }, "$b")

            // the agent's judgements replayed too, so its derived rating is still there
            val w = worklist(probe, "agent-1")
            assertEquals(1, w["judgements"]!!.jsonArray.size, "$w")
            val rated = w["ideas"]!!.jsonArray.map { it.jsonObject }
                .first { it["id"]!!.jsonPrimitive.content == "computenet-top" }["rated"]!!
            assertTrue(rated != JsonNull, "the agent's derived rating replayed: $w")
        }
    }
}
