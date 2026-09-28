package civictech.deliberate

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the wire JSON of [GraphDto] and every DTO it nests, against
 * `demo/deliberate/ui/test/fixtures/golden.json` — the UI contract test
 * shares the same file (computenet-dq2fy.24.2). A changed value, a renamed
 * field or a dropped/added field on any DTO reddens this test; regenerate the
 * fixture with `DELIBERATE_GOLDEN_REGEN=1`.
 *
 * [GOLDEN] is a hand-built literal (no engine, no UUIDs, no timing) chosen so
 * that every field of every DTO below appears at least once with a value,
 * and every nullable field is absent at least once — see the per-DTO
 * key-set assertions below.
 */
class DtoGoldenTest {

    private val strict = Json { ignoreUnknownKeys = false }

    /** Repo-root-relative; the Gradle `test` task's working directory is this module's directory. */
    private fun resolve(relative: String): File {
        val cwd = File(System.getProperty("user.dir"))
        val direct = File(cwd, relative)
        if (direct.parentFile?.isDirectory == true) return direct
        // Fallback: anchor at the repo root and resolve from there, in case a
        // future convention plugin changes the test task's working directory.
        var dir: File? = cwd
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val root = dir ?: error("cannot locate repo root (settings.gradle.kts) from $cwd")
        return File(root, "demo/deliberate/$relative")
    }

    private val goldenFile = resolve("ui/test/fixtures/golden.json")
    private val deepFile = resolve("ui/test/fixtures/deep.json")
    private val flatFile = resolve("ui/test/fixtures/flat.json")

    @Test
    fun `encodes to the golden fixture`() {
        val encoded = DeliberateApp.JSON.encodeToString(GraphDto.serializer(), GOLDEN)
        if (System.getenv("DELIBERATE_GOLDEN_REGEN") == "1") {
            val pretty = Json(DeliberateApp.JSON) { prettyPrint = true }
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(pretty.encodeToString(GraphDto.serializer(), GOLDEN))
            return
        }
        assertTrue(goldenFile.isFile, "golden fixture missing at $goldenFile — run with DELIBERATE_GOLDEN_REGEN=1")
        val expected = Json.parseToJsonElement(goldenFile.readText())
        val actual = Json.parseToJsonElement(encoded)
        assertEquals(expected, actual, "GraphDto encoding no longer matches ui/test/fixtures/golden.json")
    }

    @Test
    fun `every DTO field is covered by the golden fixture`() {
        val root = Json.parseToJsonElement(goldenFile.readText()).jsonObject
        assertKeySet(setOf(root), elementNamesOf(GraphDto.serializer()), "GraphDto")

        val questions = root["questions"]!!.jsonArray().map { it.jsonObject }
        assertKeySet(questions.toSet(), elementNamesOf(QuestionDto.serializer()), "QuestionDto")

        val framings = questions.mapNotNull { it["framing"]?.jsonObject }
        assertKeySet(framings.toSet(), elementNamesOf(FramingDto.serializer()), "FramingDto")

        val positions = framings.flatMap { it["positions"]!!.jsonArray().map { p -> p.jsonObject } }
        assertKeySet(positions.toSet(), elementNamesOf(PositionDto.serializer()), "PositionDto")

        val costs = questions.mapNotNull { it["cost"]?.jsonObject }
        assertKeySet(costs.toSet(), elementNamesOf(CostDto.serializer()), "CostDto")

        val backends = costs.flatMap { it["backends"]?.jsonArray()?.map { b -> b.jsonObject } ?: emptyList() }
        assertKeySet(backends.toSet(), elementNamesOf(BackendCostDto.serializer()), "BackendCostDto")

        val nodes = root["nodes"]!!.jsonArray().map { it.jsonObject }
        assertKeySet(nodes.toSet(), elementNamesOf(NodeDto.serializer()), "NodeDto")

        // Every nullable element must be absent from at least one object of its DTO.
        assertAbsentSomewhere(questions, QUESTION_NULLABLE, "QuestionDto")
        assertAbsentSomewhere(framings, setOf("term"), "FramingDto")
        assertAbsentSomewhere(positions, setOf("firstImpression", "neutralCredence", "share"), "PositionDto")
        assertAbsentSomewhere(costs, setOf("perRoundUsd"), "CostDto")
        assertAbsentSomewhere(backends, setOf("usd", "rateDate", "note"), "BackendCostDto")
        assertAbsentSomewhere(nodes, NODE_NULLABLE, "NodeDto")
    }

    @Test
    fun `deep and flat UI fixtures decode strictly as GraphDto`() {
        strict.decodeFromString(GraphDto.serializer(), deepFile.readText())
        strict.decodeFromString(GraphDto.serializer(), flatFile.readText())
    }

    private fun elementNamesOf(serializer: KSerializer<*>): Set<String> {
        val d = serializer.descriptor
        return (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
    }

    private fun assertKeySet(objects: Set<JsonObject>, expected: Set<String>, dto: String) {
        val union = objects.flatMap { it.keys }.toSet()
        assertEquals(expected, union, "$dto: fixture keys (union across ${objects.size} object(s)) must equal every declared field")
    }

    private fun assertAbsentSomewhere(objects: List<JsonObject>, nullableFields: Set<String>, dto: String) {
        for (field in nullableFields) {
            assertTrue(objects.any { field !in it.keys }, "$dto.$field: never absent across ${objects.size} object(s) in the golden fixture")
        }
    }

    private fun JsonElement.jsonArray(): List<JsonElement> = (this as JsonArray).toList()

    companion object {
        /** Nullable [QuestionDto] fields (per Dto.kt: `?` type, no non-null default). */
        private val QUESTION_NULLABLE =
            setOf("yieldRecent", "yieldEarlier", "stoppedBy", "projectedUsd", "firstImpression", "neutralCredence", "framing")

        /** Nullable [NodeDto] fields. */
        private val NODE_NULLABLE = setOf(
            "text", "depth", "status", "override", "activity",
            "proposer", "alsoProposedBy", "merged", "evidence", "undercuts", "onLink", "positionOf",
            "plausibility", "relevance", "quality", "contribution", "sensitivity", "reach",
            "proSaturation", "conSaturation", "rounds", "duplicatesDropped", "triage", "error",
            "polarity", "source", "target", "strength",
        )

        // ---- q1: framed (READINGS, term set), two positions ----
        private val pos1 = PositionDto(
            ref = "pos1", text = "Reading A", credence = 0.65,
            firstImpression = 0.7, neutralCredence = 0.4, verdictsDisagree = true, share = 0.6,
        )
        private val pos2 = PositionDto(
            ref = "pos2", text = "Reading B", credence = 0.4,
            firstImpression = null, neutralCredence = null, verdictsDisagree = false, share = null,
        )
        private val q1 = QuestionDto(
            root = "q1", text = "What does ‘freedom’ mean here?", claims = 4, active = true,
            yieldRounds = 0, yieldRecent = null, yieldEarlier = null, stoppedBy = null, paused = false,
            costUsd = 0.0, projectedUsd = null, cost = CostDto(),
            cruxes = emptyList(), firstImpression = null, neutralCredence = null, verdictsDisagree = false,
            framing = FramingDto(mode = "READINGS", term = "freedom", positions = listOf(pos1, pos2)),
        )

        // ---- q3: framed (POSITIONS, term absent), one position ----
        private val pos3 = PositionDto(
            ref = "pos3", text = "Position C", credence = 0.5,
            firstImpression = null, neutralCredence = null, verdictsDisagree = false, share = 0.55,
        )
        private val q3 = QuestionDto(
            root = "q3", text = "Which option is best?", claims = 2, active = false,
            yieldRounds = 0, yieldRecent = null, yieldEarlier = null, stoppedBy = null, paused = false,
            costUsd = 0.0, projectedUsd = null, cost = CostDto(),
            cruxes = emptyList(), firstImpression = null, neutralCredence = null, verdictsDisagree = false,
            framing = FramingDto(mode = "POSITIONS", term = null, positions = listOf(pos3)),
        )

        // ---- q2: unframed, every model C/D field, cost with two backends ----
        private val backendClaude = BackendCostDto(
            backend = "claude", models = listOf("claude-4"), calls = 3,
            inputTokens = 1000, cachedInputTokens = 200, cacheWriteTokens = 50,
            outputTokens = 300, reasoningTokens = 0,
            usd = null, unpricedCalls = 3,
            rate = "\$4.00/1M input · \$20.00/1M output", rateSource = "anthropic-pricing-2026-01",
            rateDate = null, assumed = false, note = "Subscription usage — cost not billed per call.",
        )
        private val backendCodex = BackendCostDto(
            backend = "codex", models = listOf("codex-5"), calls = 2,
            inputTokens = 500, cachedInputTokens = 0, cacheWriteTokens = 0,
            outputTokens = 150, reasoningTokens = 40,
            usd = 1.23, unpricedCalls = 0,
            rate = "\$3.00/1M input · \$15.00/1M output", rateSource = "openai-pricing-2026-01",
            rateDate = "2026-01-15", assumed = true, note = null,
        )
        private val q2 = QuestionDto(
            root = "q2", text = "Should the city adopt X?", claims = 4, active = false,
            yieldRounds = 5, yieldRecent = 0.6, yieldEarlier = 0.4, stoppedBy = "voi", paused = true,
            costUsd = 1.23, projectedUsd = 2.5,
            cost = CostDto(backends = listOf(backendClaude, backendCodex), rounds = 5, queued = 1, perRoundUsd = 0.42),
            cruxes = listOf("a1", "e-a1"), firstImpression = 0.55, neutralCredence = 0.6, verdictsDisagree = true,
            framing = null,
        )

        // ---- nodes ----
        // SPEC FRA-02: a reading/position is a claim of its question (root = the question ref, proposer "reading"/"position").
        private val nQ1 = NodeDto(ref = "q1", kind = "CLAIM", credence = 0.5, root = "q1", text = "What does ‘freedom’ mean here?", depth = 0, status = Status.FRAMED, proposer = "question")
        private val nPos1 = NodeDto(ref = "pos1", kind = "CLAIM", credence = 0.65, root = "q1", text = "Reading A", depth = 0, status = Status.SATURATED, proposer = "reading", positionOf = "q1")
        private val nP1a = NodeDto(ref = "p1a", kind = "CLAIM", credence = 0.55, root = "q1", text = "An argument for reading A.", depth = 1, proposer = "claude")
        private val eP1a = NodeDto(ref = "e-p1a", kind = "EDGE", credence = 0.5, root = "q1", polarity = "SUPPORT", source = "p1a", target = "pos1", strength = 0.5)
        private val nPos2 = NodeDto(ref = "pos2", kind = "CLAIM", credence = 0.4, root = "q1", text = "Reading B", proposer = "reading", positionOf = "q1")

        private val nQ3 = NodeDto(ref = "q3", kind = "CLAIM", credence = 0.5, root = "q3", text = "Which option is best?", depth = 0, status = Status.FRAMED, proposer = "question")
        private val nPos3 = NodeDto(ref = "pos3", kind = "CLAIM", credence = 0.5, root = "q3", text = "Position C", proposer = "position", positionOf = "q3")

        private val nQ2 = NodeDto(
            ref = "q2", kind = "CLAIM", credence = 0.52, root = "q2",
            credences = mapOf("wlo" to 0.5, "jnb" to 0.55, "woe" to 0.51), consensus = 0.517, spreadLow = 0.5, spreadHigh = 0.55,
            text = "Should the city adopt X?", depth = 0, status = Status.EXPLORING, proposer = "question",
        )
        private val nA1 = NodeDto(
            ref = "a1", kind = "CLAIM", credence = 0.7, root = "q2", text = "An argument for X.", depth = 1,
            status = Status.SATURATED, proposer = "claude",
            alsoProposedBy = listOf("codex"), merged = true, evidence = listOf("Some evidence text."),
            plausibility = 0.7, relevance = 0.9, quality = 0.8, contribution = 0.55, sensitivity = 0.4, reach = 0.8,
            proSaturation = 0.6, conSaturation = 0.3, rounds = 3, duplicatesDropped = 1,
            triage = mapOf("ADD" to 2, "DUPLICATE" to 1), activity = "exploring",
        )
        private val eA1 = NodeDto(
            ref = "e-a1", kind = "EDGE", credence = 0.8, root = "q2",
            text = "“An argument for X.” is a reason for “Should the city adopt X?”",
            depth = 1, status = Status.SATURATED, override = Override.EXPAND,
            polarity = "SUPPORT", source = "a1", target = "q2", strength = 0.8,
        )
        private val nU1 = NodeDto(
            ref = "u1", kind = "CLAIM", credence = 0.3, root = "q2", text = "The link to X does not hold.", depth = 2,
            status = Status.QUEUED, proposer = "codex", undercuts = "e-a1", onLink = "e-a1",
        )
        private val eU1 = NodeDto(ref = "e-u1", kind = "EDGE", credence = 0.35, root = "q2", polarity = "ATTACK", source = "u1", target = "e-a1", strength = 0.35)
        private val nF1 = NodeDto(
            ref = "f1", kind = "CLAIM", credence = 0.5, root = "q2", text = "A claim whose calls all failed.", depth = 1,
            status = Status.FAILED, override = Override.STOP, proposer = "codex", error = "Every call for this claim failed.",
        )
        private val eF1 = NodeDto(ref = "e-plain", kind = "EDGE", credence = 0.5, root = "q2", polarity = "SUPPORT", source = "f1", target = "q2", strength = 0.5)

        val GOLDEN = GraphDto(
            questions = listOf(q1, q3, q2),
            nodes = listOf(nQ1, nPos1, nP1a, eP1a, nPos2, nQ3, nPos3, nQ2, nA1, eA1, nU1, eU1, nF1, eF1),
            consensusMembers = listOf("wlo", "jnb", "woe"),
        )
    }
}
