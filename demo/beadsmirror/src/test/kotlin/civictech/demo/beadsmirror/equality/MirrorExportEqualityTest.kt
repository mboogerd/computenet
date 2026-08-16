package civictech.demo.beadsmirror.equality

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportException
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.5.1: unit coverage per [Divergence] class, entirely on
 * hand-built rows — no `bd`/`dolt` needed, matching the feature's own
 * non-goal ("tests run on hand-built rows"). [AgainstAScratchWorkspace] is
 * the one nested class that touches real `bd`/`dolt`: it re-verifies the
 * pinned constants ([MirrorExportEquality.FEED_ONLY], the two accepted
 * datetime renderings) against the live schema/output they were derived
 * from, so a `bd` upgrade that changes either is caught here rather than
 * silently drifting.
 */
class MirrorExportEqualityTest {

    private fun rows(vararg lines: String) = BdExportReader.parse(lines.toList())

    @Test
    fun `equal fold and export produce no divergences`() {
        val export = rows("""{"id":"ws-a","title":"Alpha","status":"open","priority":1}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\"", "status" to "\"open\"", "priority" to "1"))

        MirrorExportEquality.compare(view, emptySet(), export) shouldBe emptyList()
    }

    @Test
    fun `an issue export prints but the fold has no entry for is MissingIssue`() {
        val export = rows("""{"id":"ws-a","title":"Alpha"}""")

        val divergences = MirrorExportEquality.compare(emptyMap(), emptySet(), export)

        divergences shouldBe listOf(MissingIssue("ws-a"))
    }

    @Test
    fun `an issue the fold has that export does not print is UnexpectedIssue naming its surviving fields`() {
        val view = mapOf("ws-c" to mapOf("__present" to "true", "status" to "\"open\""))

        val divergences = MirrorExportEquality.compare(view, emptySet(), emptyList())

        divergences shouldBe listOf(UnexpectedIssue("ws-c", setOf("__present", "status")))
    }

    @Test
    fun `a differing field rendering is a FieldMismatch naming both renderings verbatim`() {
        val export = rows("""{"id":"ws-a","title":"Alpha","status":"closed"}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\"", "status" to "\"open\""))

        val divergences = MirrorExportEquality.compare(view, emptySet(), export)

        divergences shouldBe listOf(FieldMismatch("ws-a", "status", "\"open\"", "\"closed\""))
    }

    @Test
    fun `a field export prints that the fold has no value for at all is a FieldMismatch with a null fold rendering`() {
        val export = rows("""{"id":"ws-a","title":"Alpha","status":"open"}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\""))

        val divergences = MirrorExportEquality.compare(view, emptySet(), export)

        divergences shouldBe listOf(FieldMismatch("ws-a", "status", null, "\"open\""))
    }

    @Test
    fun `a JSON-null export value counts as absent, so a fold with no value for it is not a divergence`() {
        val export = rows("""{"id":"ws-a","title":"Alpha","design":null}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\""))

        MirrorExportEquality.compare(view, emptySet(), export) shouldBe emptyList()
    }

    @Test
    fun `datetime renderings that name the same instant are not a divergence, despite differing text`() {
        val export = rows("""{"id":"ws-a","created_at":"2026-08-16T04:33:37Z"}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "created_at" to "\"2026-08-16 04:33:37\""))

        MirrorExportEquality.compare(view, emptySet(), export) shouldBe emptyList()
    }

    @Test
    fun `datetime renderings that name different instants are still a FieldMismatch`() {
        val export = rows("""{"id":"ws-a","created_at":"2026-08-16T04:33:37Z"}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "created_at" to "\"2026-08-16 04:33:38\""))

        val divergences = MirrorExportEquality.compare(view, emptySet(), export)

        divergences shouldBe listOf(
            FieldMismatch("ws-a", "created_at", "\"2026-08-16 04:33:38\"", "\"2026-08-16T04:33:37Z\""),
        )
    }

    @Test
    fun `a FEED_ONLY fold-only key is tolerated, not reported`() {
        val export = rows("""{"id":"ws-a","title":"Alpha"}""")
        val view = mapOf(
            "ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\"", "content_hash" to "\"deadbeef\""),
        )

        MirrorExportEquality.compare(view, emptySet(), export) shouldBe emptyList()
    }

    @Test
    fun `a fold-only key outside FEED_ONLY is an UnexpectedField divergence`() {
        val export = rows("""{"id":"ws-a","title":"Alpha"}""")
        val view = mapOf(
            "ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\"", "totally_unknown_column" to "\"x\""),
        )

        val divergences = MirrorExportEquality.compare(view, emptySet(), export)

        divergences shouldBe listOf(UnexpectedField("ws-a", "totally_unknown_column", "\"x\""))
    }

    @Test
    fun `excluded export keys are never compared, even when the fold's corresponding value would mismatch`() {
        val export = rows(
            """{"id":"ws-a","title":"Alpha","dependency_count":3,"comment_count":2,"labels":["urgent"]}""",
        )
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\""))

        MirrorExportEquality.compare(view, emptySet(), export) shouldBe emptyList()
    }

    @Test
    fun `a labels-only fold key that is not FEED_ONLY still divergences, exclusion is one-directional`() {
        // "labels" is excluded from the *export* universe, but a fold that somehow
        // carried a "labels" key would still need to be in FEED_ONLY to be tolerated —
        // it is not, so this is a real (if currently unreachable in practice) divergence.
        val export = rows("""{"id":"ws-a","title":"Alpha"}""")
        val view = mapOf("ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\"", "labels" to "[\"urgent\"]"))

        val divergences = MirrorExportEquality.compare(view, emptySet(), export)

        divergences shouldBe listOf(UnexpectedField("ws-a", "labels", "[\"urgent\"]"))
    }

    @Test
    fun `matching edges produce no divergence, and dependency created_at plays no part in the comparison`() {
        val export = rows(
            """{"id":"ws-a","dependencies":[{"issue_id":"ws-a","depends_on_id":"ws-b","type":"blocks",""" +
                """"created_at":"2026-08-16T00:00:00Z"}]}""",
        )
        val edgeView = setOf(MirrorEdge("ws-a", "ws-b", "blocks"))

        MirrorExportEquality.compare(mapOf("ws-a" to mapOf("id" to "\"ws-a\"")), edgeView, export) shouldBe emptyList()
    }

    @Test
    fun `an edge export names that edgeView lacks is MissingEdge`() {
        val export = rows(
            """{"id":"ws-a","dependencies":[{"issue_id":"ws-a","depends_on_id":"ws-b","type":"blocks"}]}""",
        )

        val divergences = MirrorExportEquality.compare(mapOf("ws-a" to mapOf("id" to "\"ws-a\"")), emptySet(), export)

        divergences shouldBe listOf(MissingEdge(MirrorEdge("ws-a", "ws-b", "blocks")))
    }

    @Test
    fun `an edge edgeView carries that no export row names is UnexpectedEdge`() {
        val export = rows("""{"id":"ws-a"}""")
        val edgeView = setOf(MirrorEdge("ws-a", "ws-b", "blocks"))

        val divergences = MirrorExportEquality.compare(mapOf("ws-a" to mapOf("id" to "\"ws-a\"")), edgeView, export)

        divergences shouldBe listOf(UnexpectedEdge(MirrorEdge("ws-a", "ws-b", "blocks")))
    }

    @Test
    fun `a malformed dependency entry fails loudly rather than silently dropping the edge`() {
        val export = rows("""{"id":"ws-a","dependencies":["not-an-object"]}""")

        shouldThrow<BdExportException> {
            MirrorExportEquality.compare(mapOf("ws-a" to mapOf("id" to "\"ws-a\"")), emptySet(), export)
        }
    }

    @Test
    fun `several divergences accumulate rather than short-circuiting on the first`() {
        val export = rows(
            """{"id":"ws-a","title":"Alpha","status":"open"}""",
            """{"id":"ws-b","title":"Beta"}""",
        )
        val view = mapOf(
            "ws-a" to mapOf("id" to "\"ws-a\"", "title" to "\"Alpha\"", "status" to "\"closed\""),
            "ws-c" to mapOf("id" to "\"ws-c\""),
        )

        val divergences = MirrorExportEquality.compare(view, emptySet(), export)

        divergences shouldContainExactlyInAnyOrder listOf(
            FieldMismatch("ws-a", "status", "\"closed\"", "\"open\""),
            MissingIssue("ws-b"),
            UnexpectedIssue("ws-c", setOf("id")),
        )
    }

    /**
     * The one nested class that runs real `bd`/`dolt`, guarded by JUnit
     * assumptions like [civictech.demo.beadsmirror.baseline.BaselineBuilderTest.AgainstAScratchWorkspace] —
     * green-but-SKIPPED in CI, a real gate on a developer machine. It exists
     * to re-verify the constants pinned above ([MirrorExportEquality.FEED_ONLY],
     * the two accepted datetime renderings, `content_hash`/`is_blocked` never
     * appearing in export) against the actual live behaviour they were
     * derived from, so a `bd`/`dolt` upgrade that changes either shows up
     * here rather than as a silent false pass/fail in the equivalence harness
     * (computenet-dqj.5.2/.5.3).
     */
    @Nested
    inner class AgainstAScratchWorkspace {

        private lateinit var workspace: BdScratchWorkspace

        @BeforeEach
        fun setUp() {
            assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
            assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
            workspace = BdScratchWorkspace.create()
        }

        @AfterEach
        fun tearDown() {
            if (::workspace.isInitialized) workspace.close()
        }

        /**
         * The real pipeline: [DoltCommitFeed] -> [MirrorProjector] (NOT
         * [civictech.demo.beadsmirror.baseline.BaselineBuilder], which only
         * ever carries export's own keys) against real `bd`/`dolt`, compared
         * to a real `bd export`. This is what actually exercises [FEED_ONLY]'s
         * tolerance: the feed-built fold carries every `issues` column, most
         * still at their default value, and none of that should surface as a
         * divergence once the field universe is genuinely empty of real
         * differences.
         */
        @Test
        fun `a freshly created issue's feed-built fold equals its export, up to FEED_ONLY tolerance`() {
            createIssue("Probe issue")

            val feed = DoltCommitFeed(workspace.doltRoot)
            val projector = MirrorProjector(DotMinter("equality-scratch"))
            projector.applyAll(feed.readFrom())

            val export = BdExportReader(workspace.root).read()

            MirrorExportEquality.compare(projector.view(), projector.edgeView(), export) shouldBe emptyList()
        }

        /**
         * Pins the two accepted datetime renderings against the real values
         * that produced them, rather than the hand-picked strings the
         * hand-built-row tests above use.
         */
        @Test
        fun `the export and feed-built datetime renderings for the same field normalize equal`() {
            val id = createIssue("Datetime probe")

            val feed = DoltCommitFeed(workspace.doltRoot)
            val projector = MirrorProjector(DotMinter("equality-scratch"))
            projector.applyAll(feed.readFrom())
            val export = BdExportReader(workspace.root).read()

            val exportRow = export.single { it.id == id }
            val exportCreatedAt = exportRow.json.getValue("created_at").toString()
            val foldCreatedAt = projector.view().getValue(id).getValue("created_at")

            // Genuinely different textual renderings — this pins the asymmetry, not just the equality.
            (exportCreatedAt == foldCreatedAt) shouldBe false
            exportCreatedAt.matches(Regex("""^"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"$""")) shouldBe true
            foldCreatedAt.matches(Regex("""^"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"$""")) shouldBe true

            // Isolate the one field this test is about, so the assertion is not
            // incidentally exercising every other FEED_ONLY-tolerated column too.
            val createdAtOnly = ExportRow(id, JsonObject(mapOf("created_at" to exportRow.json.getValue("created_at"))))

            MirrorExportEquality.compare(
                mapOf(id to mapOf("created_at" to foldCreatedAt)),
                emptySet(),
                listOf(createdAtOnly),
            ) shouldBe emptyList()
        }

        /**
         * `content_hash` (named in the feature description as the canonical
         * FEED_ONLY example) and `is_blocked` (verified live: stays absent
         * from export even for a genuinely blocked issue) are re-verified live
         * here rather than trusted from the breakdown probe alone.
         */
        @Test
        fun `content_hash and is_blocked never appear in export, blocked or not`() {
            val a = createIssue("Blocker")
            val b = createIssue("Blocked")
            workspace.run("dep", "add", b, a, "--type", "blocks")

            val export = BdExportReader(workspace.root).read()

            export.forEach { row ->
                row.json.keys.contains("content_hash") shouldBe false
                row.json.keys.contains("is_blocked") shouldBe false
            }
        }

        /**
         * Every `issues`-table column not printed unconditionally by export
         * ("core") must be in [MirrorExportEquality.FEED_ONLY] or a freshly
         * created issue (every optional column still at its default) would
         * spuriously diverge — which the first test in this class already
         * proves does not happen. This test pins the schema-level reasoning
         * directly: the live column set, minus the always-printed core, is a
         * subset of [MirrorExportEquality.FEED_ONLY].
         */
        @Test
        fun `every non-core issues column is covered by FEED_ONLY`() {
            val sql = DoltSql(workspace.doltRoot)
            val columns = sql.query("describe issues").map { it.getValue("Field").jsonPrimitive.content }
            val core = setOf(
                "id", "title", "status", "priority", "issue_type", "owner",
                "created_at", "created_by", "updated_at",
            )

            val nonCore = columns.toSet() - core

            (nonCore - MirrorExportEquality.FEED_ONLY) shouldBe emptySet()
        }

        private fun createIssue(title: String): String {
            val output = workspace.run("create", title, "--json")
            return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(output)!!.groupValues[1]
        }

        private fun commandAvailable(vararg command: String): Boolean = try {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
