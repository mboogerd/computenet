package civictech.demo.beadsmirror.baseline

import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.3.1: a `bd export` snapshot becomes projector state through
 * synthetic [DiffType.ADDED] records minted at the head-commit position.
 *
 * Two halves, matching `DoltCommitFeedTest`'s split:
 * - [OverHandBuiltRows] drives every translation rule (field exclusion, edge
 *   key names, metadata/cn_dot rebuild, ordinals, determinism) over
 *   hand-written export lines — no `bd`, no `dolt`, so it is a real CI gate.
 * - [AgainstAScratchWorkspace] runs the whole path against a real
 *   `bd --sandbox init` workspace, which is the only way to know the export's
 *   actual key names are the ones this builder reads. It guards with JUnit
 *   assumptions because CI installs neither binary.
 */
class BaselineBuilderTest {

    @Nested
    inner class OverHandBuiltRows {

        private val minter = DotMinter("baseline-scratch")
        private val builder = BaselineBuilder(minter)

        private val alpha = """
            {"_type":"issue","id":"ws-a","title":"Alpha","status":"open","priority":1,
             "dependency_count":1,"dependent_count":0,"comment_count":3,
             "dependencies":[{"issue_id":"ws-a","depends_on_id":"ws-b","type":"blocks"}]}
        """.trimIndent().replace("\n", "")
        private val beta = """{"_type":"issue","id":"ws-b","title":"Beta","status":"closed","priority":2}"""

        private fun rows(vararg lines: String) = BdExportReader.parse(lines.toList())

        @Test
        fun `every row becomes one ADDED record at the head commit and height`() {
            val records = builder.records(rows(alpha, beta), "headhash", 7)

            records.map { it.issueId } shouldContainExactly listOf("ws-a", "ws-b")
            records.forEach {
                it.commitHash shouldBe "headhash"
                it.diffType shouldBe DiffType.ADDED
                it.position.commitHeight shouldBe 7L
            }
        }

        @Test
        fun `ordinals follow sorted issue id, not the export's line order`() {
            val records = builder.records(rows(beta, alpha), "headhash", 0)

            records.map { it.issueId to it.position.ordinal } shouldContainExactly
                listOf("ws-a" to 0, "ws-b" to 1)
        }

        @Test
        fun `derived and nested export keys are excluded from the field diffs`() {
            val record = builder.records(rows(alpha), "headhash", 0).single()

            record.fieldDiffs.map { it.column } shouldContainExactly
                listOf("id", "priority", "status", "title")
            record.fieldDiffs.forEach { it.old shouldBe null }
            BaselineBuilder.EXCLUDED_FIELDS shouldBe
                setOf("_type", "dependencies", "dependency_count", "dependent_count", "comment_count")
        }

        @Test
        fun `a JSON-null field is treated as absent, as the feed treats SQL NULL`() {
            val record = builder.records(
                rows("""{"id":"ws-a","title":"Alpha","design":null}"""), "headhash", 0,
            ).single()

            record.fieldDiffs.map { it.column } shouldContainExactly listOf("id", "title")
        }

        @Test
        fun `dependencies translate to ADDED edges, read off depends_on_id`() {
            val record = builder.records(rows(alpha), "headhash", 0).first()

            record.edgeDiffs shouldContainExactly
                listOf(EdgeDiff(DiffType.ADDED, "ws-a", "ws-b", "blocks"))
        }

        /**
         * Pins the export/table spelling asymmetry: reading the *table*'s
         * `depends_on_issue_id` here would silently produce zero edges on every
         * baseline, which no view-shape assertion elsewhere would catch.
         */
        @Test
        fun `the table's depends_on_issue_id spelling is not accepted silently`() {
            val line = """{"id":"ws-a","dependencies":[""" +
                """{"issue_id":"ws-a","depends_on_issue_id":"ws-b","type":"blocks"}]}"""

            val failure = shouldThrow<BdExportException> { builder.records(rows(line), "headhash", 0) }

            failure.message!! shouldContain "depends_on_id"
        }

        @Test
        fun `a malformed dependency entry fails loudly rather than dropping the edge`() {
            shouldThrow<BdExportException> {
                builder.records(rows("""{"id":"ws-a","dependencies":["ws-b"]}"""), "headhash", 0)
            }.message!! shouldContain "not a JSON object"

            shouldThrow<BdExportException> {
                builder.records(rows("""{"id":"ws-a","dependencies":{"a":1}}"""), "headhash", 0)
            }.message!! shouldContain "not a JSON array"
        }

        @Test
        fun `building yields presence keys, field values in export JSON form, and edges`() {
            val projector = builder.build(rows(alpha, beta), "headhash", 7)

            projector.view() shouldBe mapOf(
                "ws-a" to mapOf(
                    "id" to "\"ws-a\"", "priority" to "1", "status" to "\"open\"", "title" to "\"Alpha\"",
                ),
                "ws-b" to mapOf(
                    "id" to "\"ws-b\"", "priority" to "2", "status" to "\"closed\"", "title" to "\"Beta\"",
                ),
            )
            projector.rawValue(MirrorKey.presence("ws-a")) shouldBe MirrorKey.PRESENT_VALUE
            projector.edgeView() shouldBe setOf(MirrorEdge("ws-a", "ws-b", "blocks"))
        }

        /**
         * The export's `metadata` rides both ways: as an ordinary field key
         * (the feed carries a `metadata` column too) and as the record's
         * `newMetadata`, which is what rebuilds the projector's held-dot
         * registry through its normal admits path. The observable proof is that
         * a later feed record carrying that same cn_dot is echo-dropped.
         */
        @Test
        fun `metadata rebuilds the cn_dot registry through the projector's own admits path`() {
            val withDot = """{"id":"ws-a","status":"open","metadata":{"cn_dot":"src-9:41"}}"""

            val projector = builder.build(rows(withDot), "headhash", 7)

            projector.view().getValue("ws-a").keys shouldContainExactly setOf("id", "metadata", "status")
            projector.echoDropCount shouldBe 0

            projector.apply(echoRecord("ws-a", "src-9:41", height = 8))

            projector.echoDropCount shouldBe 1
            projector.view().getValue("ws-a")["status"] shouldBe "\"open\"" // unchanged by the echo
        }

        @Test
        fun `a record whose cn_dot the baseline did not hold is still applied`() {
            val projector = builder.build(rows(beta), "headhash", 7)

            projector.apply(echoRecord("ws-b", "someone-else:1", height = 8))

            projector.echoDropCount shouldBe 0
            projector.view().getValue("ws-b")["status"] shouldBe "\"in_progress\""
        }

        /**
         * Feature computenet-dqj.3's dot-determinism assumption: same export at
         * the same head commit produces byte-identical dots, so re-running a
         * re-baseline neither resurrects removed keys nor churns the map.
         */
        @Test
        fun `the same export at the same head builds byte-identical dots`() {
            val first = buildCapturingEdges(rows(alpha, beta))
            val second = buildCapturingEdges(rows(alpha, beta))

            first.first shouldBe second.first // OrMapCell state: keys -> dots -> values
            first.second shouldBe second.second // SetDeltas: edges -> add tags
            builder.records(rows(alpha, beta), "headhash", 7) shouldBe
                builder.records(rows(alpha, beta), "headhash", 7)
        }

        @Test
        fun `a different head height mints different dots`() {
            val atSeven = builder.build(rows(alpha), "headhash", 7).cell.state()
            val atEight = BaselineBuilder(minter).build(rows(alpha), "headhash", 8).cell.state()

            (atSeven == atEight) shouldBe false
        }

        @Test
        fun `a blank head commit or negative height is refused`() {
            shouldThrow<IllegalArgumentException> { builder.records(rows(alpha), "  ", 0) }
            shouldThrow<IllegalArgumentException> { builder.records(rows(alpha), "headhash", -1) }
        }

        /**
         * The documented ceiling: ordinals are [DotMinter]'s 10-bit slot, so an
         * export of more than 1024 issues fails loudly in `DotMinter.counter`
         * rather than aliasing two issues onto one dot.
         */
        @Test
        fun `an export beyond the ordinal budget fails loudly`() {
            val many = (0..DotMinter.MAX_ORDINAL + 1).map { """{"id":"ws-${it.toString().padStart(5, '0')}"}""" }

            shouldThrow<IllegalArgumentException> { builder.build(rows(*many.toTypedArray()), "headhash", 0) }
                .message!! shouldContain "ordinal"
        }

        /** A feed-shaped record carrying [cnDot] as its provenance — the echo the baseline should already hold. */
        private fun echoRecord(issueId: String, cnDot: String, height: Long) = ChangeRecord(
            commitHash = "commit-$height",
            position = FeedPosition(height, 0),
            issueId = issueId,
            diffType = DiffType.MODIFIED,
            fieldDiffs = listOf(FieldDiff("status", old = null, new = JsonPrimitive("in_progress"))),
            edgeDiffs = emptyList(),
            newMetadata = JsonObject(mapOf("cn_dot" to JsonPrimitive(cnDot))),
        )

        /** Builds with a fresh [DotMinter] of the same identity, capturing the edge cell's emissions. */
        private fun buildCapturingEdges(rows: List<ExportRow>): Pair<Any, List<SetDelta<MirrorEdge>>> {
            val edges = SetCell<MirrorEdge>()
            val emitted = mutableListOf<SetDelta<MirrorEdge>>()
            edges.outlet.subscribe(
                Use.fixed(
                    object : Propagate<SetDelta<MirrorEdge>> {
                        override fun propagate(value: SetDelta<MirrorEdge>) {
                            emitted += value
                        }
                    },
                    PortRef.generate(),
                )
            )
            val projector = MirrorProjector(DotMinter("baseline-scratch"), edges = edges)
            projector.applyAll(BaselineBuilder(DotMinter("baseline-scratch")).records(rows, "headhash", 7))
            return projector.cell.state() to emitted
        }
    }

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
         * The whole baseline path against real `bd`: export first, head second
         * (the documented order), then build and compare the projection to the
         * workspace's content.
         */
        @Test
        fun `builds the workspace's issues and dependency edge from a real bd export`() {
            val a = workspace.createIssue("Issue A")
            val b = workspace.createIssue("Issue B")
            workspace.run("dep", "add", a, b, "--type", "blocks")

            val rows = BdExportReader(workspace.root).read()
            val (head, height) = BaselineBuilder.captureHead(DoltCommitFeed(workspace.doltRoot))

            val projector = BaselineBuilder(DotMinter("scratch-live")).build(rows, head, height)

            projector.view().keys shouldBe setOf(a, b)
            projector.view().getValue(a)["title"] shouldBe "\"Issue A\""
            projector.view().getValue(a)["status"] shouldBe "\"open\""
            projector.edgeView() shouldBe setOf(MirrorEdge(a, b, "blocks"))
        }

        /** The head this baseline mints at is the feed's own head: resuming after it reads nothing new. */
        @Test
        fun `the captured head is the feed's head, so the feed resumes empty after it`() {
            workspace.createIssue("Issue A")
            val feed = DoltCommitFeed(workspace.doltRoot)

            val (head, height) = BaselineBuilder.captureHead(feed)

            height shouldBe (feed.history().size - 1).toLong()
            feed.history().last() shouldBe head
            feed.readFrom(head) shouldBe emptyList()
        }

        private fun BdScratchWorkspace.createIssue(title: String): String {
            val output = run("create", title, "--json")
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
