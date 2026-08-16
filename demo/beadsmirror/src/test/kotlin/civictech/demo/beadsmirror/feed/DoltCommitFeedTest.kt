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

        /**
         * The `removed` branch identifies by `from_id`, because a removal row has
         * no `to_` side at all (only `to_commit`/`to_commit_date` survive) — the
         * one production path whose column choice differs, and the only other
         * test of it asserts a *failure*. Probed live 2026-08-15: `bd delete`
         * writes exactly such a row.
         */
        @Test
        fun `a deleted issue arrives as a removed record identified by its from side`() {
            val a = workspace.createIssue("Issue A")
            val beforeDelete = DoltCommitFeed(workspace.doltRoot).readFrom().last().commitHash

            workspace.run("delete", a, "--force")

            val records = DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = beforeDelete)
            val removal = records.single { it.issueId == a }

            removal.diffType shouldBe DiffType.REMOVED
            val title = removal.fieldDiff("title").shouldNotBeNull()
            title.old?.jsonPrimitive?.content shouldBe "Issue A"
            title.new shouldBe null
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

        /**
         * computenet-dqj.11, measured at epic review: `bd dep add <native>
         * <foreign-id>` writes the far side into `depends_on_external`, leaving
         * `depends_on_issue_id` NULL — and the JSON `dolt` prints omits NULL
         * columns entirely, so the row simply has no `to_depends_on_issue_id`
         * key. Before the fix this raised [FeedShapeException] ("row is missing
         * expected column \"to_depends_on_issue_id\""), which killed the whole
         * poll pass and, through [DoltFeedPoller], the poll loop.
         *
         * The far side is carried verbatim, which is what makes the mirror
         * equal to `bd export`: export renders an external edge as an ordinary
         * `dependencies` entry with `depends_on_id` naming the foreign id
         * (verified live 2026-08-16 on a scratch workspace), exactly as it
         * renders a native one.
         *
         * The removal half is here too because it reads the `from_` side, the
         * one column-choice branch that differs.
         */
        @Test
        fun `an edge whose far side is external rides the record like any other`() {
            val a = workspace.createIssue("Issue A")
            val beforeDep = DoltCommitFeed(workspace.doltRoot).readFrom().last().commitHash

            workspace.run("dep", "add", a, EXTERNAL_TARGET, "--type", EXTERNAL_DEP_TYPE)

            val added = DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = beforeDep)
            added.single { it.issueId == a }.edgeDiffs shouldContainExactly listOf(
                EdgeDiff(DiffType.ADDED, issueId = a, dependsOnIssueId = EXTERNAL_TARGET, type = EXTERNAL_DEP_TYPE),
            )

            val afterDepAdd = added.last().commitHash
            workspace.run("dep", "remove", a, EXTERNAL_TARGET)
            DoltCommitFeed(workspace.doltRoot).readFrom(afterCommit = afterDepAdd)
                .single { it.issueId == a }.edgeDiffs shouldContainExactly listOf(
                    EdgeDiff(
                        DiffType.REMOVED,
                        issueId = a,
                        dependsOnIssueId = EXTERNAL_TARGET,
                        type = EXTERNAL_DEP_TYPE,
                    ),
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

        /**
         * computenet-dqj.6: a resumed read must narrow the SQL text itself, not
         * just filter an unbounded scan's results in memory — the residual
         * documented in [DoltCommitFeed]'s class KDoc before this fix. Observed
         * through the [DiffQuery] seam, as the KDoc there prescribes.
         */
        @Test
        fun `a resumed read narrows the diff-table queries to the wanted commits, not the whole history`() {
            val observedQueries = mutableListOf<String>()
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    observedQueries += sql
                    when {
                        sql == DoltCommitFeed.LOG_QUERY ->
                            listOf("c3", "c2", "c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        sql.startsWith(DoltCommitFeed.ISSUE_QUERY) -> listOf(
                            row("diff_type" to "added", "to_commit" to "c2", "to_id" to "a"),
                            row("diff_type" to "added", "to_commit" to "c3", "to_id" to "b"),
                        )
                        sql.startsWith(DoltCommitFeed.EDGE_QUERY) -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            val records = feed.readFrom(afterCommit = "c1")

            records.map { it.issueId } shouldContainExactly listOf("a", "b")
            observedQueries.single { it.startsWith(DoltCommitFeed.ISSUE_QUERY) } shouldBe
                "${DoltCommitFeed.ISSUE_QUERY} where to_commit in ('c2', 'c3')"
            observedQueries.single { it.startsWith(DoltCommitFeed.EDGE_QUERY) } shouldBe
                "${DoltCommitFeed.EDGE_QUERY} where to_commit in ('c2', 'c3')"

            // A genesis read (no checkpoint yet) has nothing to narrow by, so it
            // keeps issuing the plain, unfiltered query text unchanged — the cold
            // start this module was always cheap for (class KDoc).
            observedQueries.clear()
            feed.readFrom()
            observedQueries shouldContainExactly listOf(
                DoltCommitFeed.LOG_QUERY,
                DoltCommitFeed.ISSUE_QUERY,
                DoltCommitFeed.EDGE_QUERY,
            )
        }

        /**
         * The third far-side column of `dependencies` (computenet-dqj.11).
         * `depends_on_wisp_id` was *not* reachable through `bd` on this
         * machine — a `--wisp-type` issue landed in the ordinary `issues`
         * table and an edge onto it used `depends_on_issue_id` (probed
         * 2026-08-16) — so it is exercised here on a synthetic row rather
         * than claimed as measured against `bd`.
         */
        @Test
        fun `an edge diff whose far side is a wisp reference carries the wisp id`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    edgeRows = listOf(
                        edgeRow(commit = "c1", issueId = "b", dependsOn = "w-1", farSideColumn = "depends_on_wisp_id"),
                    ),
                ),
            )

            feed.readFrom().single().edgeDiffs.single().dependsOnIssueId shouldBe "w-1"
        }

        @Test
        fun `an edge row with no far side at all fails loudly naming every column it looked for`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    edgeRows = listOf(
                        row("diff_type" to "added", "to_commit" to "c1", "to_issue_id" to "b", "to_type" to "blocks"),
                    ),
                ),
            )

            val failure = shouldThrow<FeedShapeException> { feed.readFrom() }

            failure.query shouldBe DoltCommitFeed.EDGE_QUERY
            failure.message!!.contains("to_depends_on_issue_id") shouldBe true
            failure.message!!.contains("to_depends_on_wisp_id") shouldBe true
            failure.message!!.contains("to_depends_on_external") shouldBe true
        }

        /**
         * `dependencies` holds exactly one of the three far-side columns
         * non-NULL per row; two would mean the table's shape has changed under
         * the reader, and picking one silently would mirror an edge whose far
         * side the reader guessed. Loud failure is the epic's rule (§3 leaves
         * schema-drift hardening *beyond* a loud failure out of scope).
         */
        @Test
        fun `an edge row carrying two far sides fails rather than picking one`() {
            val feed = DoltCommitFeed(
                rowsFor(
                    commits = listOf("c1"),
                    edgeRows = listOf(
                        row(
                            "diff_type" to "added",
                            "to_commit" to "c1",
                            "to_issue_id" to "b",
                            "to_depends_on_issue_id" to "a",
                            "to_depends_on_external" to "elsewhere-1",
                            "to_type" to "blocks",
                        ),
                    ),
                ),
            )

            val failure = shouldThrow<FeedShapeException> { feed.readFrom() }

            failure.query shouldBe DoltCommitFeed.EDGE_QUERY
            failure.message!!.contains("to_depends_on_issue_id") shouldBe true
            failure.message!!.contains("to_depends_on_external") shouldBe true
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

        private fun edgeRow(
            commit: String,
            issueId: String,
            dependsOn: String,
            farSideColumn: String = "depends_on_issue_id",
        ) = row(
            "diff_type" to "added",
            "to_commit" to commit,
            "to_issue_id" to issueId,
            "to_$farSideColumn" to dependsOn,
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

/**
 * An id that no scratch workspace contains, so `bd dep add` resolves it as
 * *external* and writes it into `depends_on_external` (verified 2026-08-16:
 * `bd dep add` accepts an arbitrary unknown id and reports the edge added).
 * Deliberately not a real tracker id — the reader treats it as an opaque
 * string, and a real one would suggest a coupling that does not exist.
 */
private const val EXTERNAL_TARGET = "elsewhere-x7q"

private const val EXTERNAL_DEP_TYPE = "discovered-from"
