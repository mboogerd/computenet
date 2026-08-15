package civictech.demo.beadsmirror.feed

import civictech.demo.beadsmirror.BdScratchWorkspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.1.2: the feed reader emits one ordered [ChangeRecord] per
 * (commit, issue) from `dolt_diff_issues` and `dolt_diff_dependencies`.
 *
 * Two halves, deliberately:
 * - [AgainstAScratchWorkspace] drives real `bd` mutations through real `dolt`,
 *   which is the only way to know the queries and column names are right. It
 *   guards with JUnit assumptions because CI installs neither binary
 *   (.github/workflows/ci.yml), so there it is green-but-skipped and a real
 *   gate only on a developer machine.
 * - [OverSyntheticRows] drives the assembly and shape-failure rules through
 *   [DiffQuery] over hand-built rows. Those rules must hold for shapes no `bd`
 *   sequence produces (an edge diff whose issue has no `dolt_diff_issues` row;
 *   an unknown `diff_type`), and this half runs everywhere, CI included.
 *
 * Every workspace here is a throwaway `bd --sandbox init` directory; nothing
 * touches this repository's live `.beads` (epic computenet-dqj §4).
 */
class DoltCommitFeedTest {

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

        /** Feature design example (genesis): create A, update A.status, create B. */
        @Test
        fun `reads three mutations from genesis as three ordered records`() {
            val a = workspace.createIssue("Issue A")
            workspace.run("update", a, "--status", "in_progress")
            val b = workspace.createIssue("Issue B")

            val records = DoltCommitFeed(workspace.doltRoot).readFrom()

            records.map { it.issueId to it.diffType } shouldContainExactly listOf(
                a to DiffType.ADDED,
                a to DiffType.MODIFIED,
                b to DiffType.ADDED,
            )
            // Strictly increasing, and derived from the commit graph: each
            // record's hash is the commit dolt_log shows at its height.
            records.map { it.position }.zipWithNext().forEach { (earlier, later) ->
                (earlier < later) shouldBe true
            }
            val history = DoltCommitFeed(workspace.doltRoot).history()
            records.forEach { history[it.position.commitHeight.toInt()] shouldBe it.commitHash }

            val statusChange = records[1].fieldDiff("status").shouldNotBeNull()
            statusChange.old?.jsonPrimitive?.content shouldBe "open"
            statusChange.new?.jsonPrimitive?.content shouldBe "in_progress"
        }

        /** Feature design example (atomicity): one `bd update`, two fields, ONE record. */
        @Test
        fun `one update changing two fields is one record carrying both field diffs`() {
            val a = workspace.createIssue("Issue A")
            val afterCreate = DoltCommitFeed(workspace.doltRoot).readFrom().last().commitHash

            workspace.run("update", a, "--status", "in_progress", "--priority", "0")

            val records = DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = afterCreate)

            records.size shouldBe 1
            val record = records.single()
            record.issueId shouldBe a
            record.diffType shouldBe DiffType.MODIFIED
            record.fieldDiff("status").shouldNotBeNull().new?.jsonPrimitive?.content shouldBe "in_progress"
            record.fieldDiff("priority").shouldNotBeNull().new?.jsonPrimitive?.content shouldBe "0"
        }

        @Test
        fun `metadata travels so cn_dot is readable off the record`() {
            val a = workspace.createIssue("Issue A")
            workspace.run("update", a, "--set-metadata", "cn_dot=node7:42")

            val records = DoltCommitFeed(workspace.doltRoot).readFrom()
            val update = records.last { it.issueId == a }

            update.newMetadata.shouldNotBeNull()["cn_dot"]?.jsonPrimitive?.content shouldBe "node7:42"
            // ... and it is a field diff too, so a projector folding diffs sees the change.
            update.fieldDiff("metadata").shouldNotBeNull()
        }

        @Test
        fun `a dependency edge rides the record of the issue it belongs to`() {
            val a = workspace.createIssue("Issue A")
            val b = workspace.createIssue("Issue B")
            val beforeDep = DoltCommitFeed(workspace.doltRoot).readFrom().last().commitHash

            workspace.run("dep", "add", b, a)
            val added = DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = beforeDep)
            added.single { it.issueId == b }.edgeDiffs shouldContainExactly listOf(
                EdgeDiff(DiffType.ADDED, issueId = b, dependsOnIssueId = a, type = "blocks"),
            )

            val afterDepAdd = added.last().commitHash
            workspace.run("dep", "remove", b, a)
            val removed = DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = afterDepAdd)
            removed.single { it.issueId == b }.edgeDiffs shouldContainExactly listOf(
                EdgeDiff(DiffType.REMOVED, issueId = b, dependsOnIssueId = a, type = "blocks"),
            )
        }

        @Test
        fun `reading after an unknown commit refuses rather than reporting no changes`() {
            workspace.createIssue("Issue A")

            shouldThrow<IllegalArgumentException> {
                DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = "0123456789abcdefghijklmnopqrstuv")
            }
        }

        private fun BdScratchWorkspace.createIssue(title: String): String {
            val output = run("create", title, "--json")
            // bd --json prints the created issue (or a single-element list of one).
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

    @Nested
    inner class OverSyntheticRows {

        @Test
        fun `an edge diff whose issue has no issue row is synthesized, not dropped`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c2", "c1"),
                    issueRows = listOf(
                        row("diff_type" to "modified", "to_commit" to "c2", "to_id" to "a", "to_title" to "A"),
                    ),
                    edgeRows = listOf(
                        edgeRow(commit = "c2", issueId = "b", dependsOn = "a"),
                    ),
                ),
            )

            val records = feed.readFrom()

            records.map { it.issueId } shouldContainExactly listOf("a", "b")
            val synthesized = records.single { it.issueId == "b" }
            synthesized.diffType shouldBe null
            synthesized.fieldDiffs shouldBe emptyList()
            synthesized.edgeDiffs.single().dependsOnIssueId shouldBe "a"
            // Ordinals order the commit's issues by id, deterministically.
            records.map { it.position } shouldContainExactly listOf(FeedPosition(1, 0), FeedPosition(1, 1))
        }

        @Test
        fun `an unknown diff_type fails loudly with the query, and emits nothing`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    issueRows = listOf(
                        row("diff_type" to "added", "to_commit" to "c1", "to_id" to "a"),
                        row("diff_type" to "frobnicated", "to_commit" to "c1", "to_id" to "b"),
                    ),
                ),
            )

            val failure = shouldThrow<FeedShapeException> { feed.readFrom() }

            failure.query shouldBe DoltCommitFeed.ISSUE_QUERY
            failure.message!!.contains("frobnicated") shouldBe true
        }

        @Test
        fun `a row missing the column carrying the issue id fails loudly`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    // `removed` identifies by from_id; this row only has to_id.
                    issueRows = listOf(row("diff_type" to "removed", "to_commit" to "c1", "to_id" to "a")),
                ),
            )

            val failure = shouldThrow<FeedShapeException> { feed.readFrom() }

            failure.query shouldBe DoltCommitFeed.ISSUE_QUERY
            failure.message!!.contains("from_id") shouldBe true
        }

        @Test
        fun `two rows for one issue in one commit fail rather than silently collapsing`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    issueRows = listOf(
                        row("diff_type" to "added", "to_commit" to "c1", "to_id" to "a", "to_title" to "one"),
                        row("diff_type" to "modified", "to_commit" to "c1", "to_id" to "a", "to_title" to "two"),
                    ),
                ),
            )

            shouldThrow<FeedShapeException> { feed.readFrom() }
        }

        @Test
        fun `commit and commit_date are diff bookkeeping, not issue field changes`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    issueRows = listOf(
                        row(
                            "diff_type" to "modified",
                            "to_commit" to "c1",
                            "from_commit" to "c0",
                            "to_commit_date" to "2026-08-15 14:36:50.082000",
                            "from_commit_date" to "2026-08-15 14:36:49.166000",
                            "to_id" to "a",
                            "from_id" to "a",
                            "to_title" to "A",
                            "from_title" to "A",
                        ),
                    ),
                ),
            )

            feed.readFrom().single().fieldDiffs shouldBe emptyList()
        }

        private fun row(vararg columns: Pair<String, String>): Map<String, JsonElement> =
            columns.associate { (key, value) -> key to JsonPrimitive(value) }

        private fun edgeRow(commit: String, issueId: String, dependsOn: String) = row(
            "diff_type" to "added",
            "to_commit" to commit,
            "to_issue_id" to issueId,
            "to_depends_on_issue_id" to dependsOn,
            "to_type" to "blocks",
        )

        /**
         * A [DiffQuery] answering the feed's three queries from fixed rows.
         * [commits] is in `dolt_log` order (newest first), as Dolt prints it.
         */
        private fun rowsFor(
            commits: List<String>,
            issueRows: List<Map<String, JsonElement>> = emptyList(),
            edgeRows: List<Map<String, JsonElement>> = emptyList(),
        ) = DiffQuery { sql ->
            when (sql) {
                DoltCommitFeed.LOG_QUERY -> commits.map { mapOf("commit_hash" to JsonPrimitive(it)) }
                DoltCommitFeed.ISSUE_QUERY -> issueRows
                DoltCommitFeed.EDGE_QUERY -> edgeRows
                else -> error("unexpected query: $sql")
            }
        }
    }
}
