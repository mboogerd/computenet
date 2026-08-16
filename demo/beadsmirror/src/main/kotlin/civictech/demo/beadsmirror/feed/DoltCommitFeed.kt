package civictech.demo.beadsmirror.feed

import civictech.demo.beadsmirror.dolt.DoltSql
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/**
 * Raised when a `dolt_diff_*` row has a shape the [ChangeRecord] envelope
 * cannot express — a missing expected column, an unknown `diff_type`, two rows
 * claiming the same (commit, issue). Carries the query that produced the row so
 * the failure names the question as well as the answer.
 *
 * This is the envelope-shape half of feature computenet-dqj.1's fail-loudly
 * criterion; the subprocess/parse half is
 * [civictech.demo.beadsmirror.dolt.DoltSqlException], raised a layer below.
 * Both are thrown before any record of the affected pass is returned:
 * [DoltCommitFeed.readFrom] builds its whole result before handing it back, so
 * a caller never observes a partial read.
 */
class FeedShapeException(
    val query: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("unmappable dolt diff row for query [$query]: $message", cause)

/**
 * Raised by [DoltCommitFeed.readFrom] when [checkpoint] — the caller's
 * `afterCommit` — is not present in `dolt_log`: history compaction/truncation
 * (`bd compact`/`gc`, retention, or a `bd flatten`) has moved the checkpoint
 * out from under the reader. Extends [IllegalArgumentException] so existing
 * callers that treat a bad `afterCommit` as bad input keep working unchanged;
 * [DoltFeedPoller] catches this exact type (not the broader
 * [IllegalArgumentException]) to convert it into the feature's typed
 * [FeedCondition.CheckpointGone] — any other [IllegalArgumentException]
 * raised under [DoltCommitFeed.readFrom] propagates instead of being folded
 * into that condition.
 */
class CheckpointNotInHistoryException(val checkpoint: String) :
    IllegalArgumentException("commit $checkpoint is not in dolt_log (history truncated?)")

/**
 * The one thing [DoltCommitFeed] needs from the world: run a SQL query, get
 * rows. In production this is [DoltSql.query]; keeping it an interface lets the
 * feed's assembly rules (record-per-(commit, issue), ordinals, synthesis of
 * edge-only records) be exercised on rows that no `bd` sequence produces, and
 * without `bd`/`dolt` on PATH.
 */
fun interface DiffQuery {
    fun query(sql: String): List<Map<String, JsonElement>>
}

/**
 * Reads a bd workspace's Dolt commit graph as an ordered change feed: one
 * [ChangeRecord] per (commit, issue), in `dolt_log` order, carrying all of that
 * issue's field diffs and dependency-edge diffs for that commit as one unit.
 *
 * This is the single-pass read half only (computenet-dqj.1.2). Checkpoint
 * persistence, resume, polling cadence and the history-truncation condition
 * belong to computenet-dqj.1.3 ([DoltFeedPoller]) and drive this class through
 * [readFrom]/[history].
 *
 * RESOLVED (computenet-dqj.6; previously a known limitation flagged in
 * computenet-dqj.1.2's review and left open by computenet-dqj.1.3 as a
 * judgment call): when [readFrom] resumes from a non-null `afterCommit`,
 * [readIssueDiffs]/[readEdgeDiffs] add a `to_commit IN (...)` filter over the
 * wanted commit set, so the query itself — not just the in-memory filter —
 * is bounded by the number of new commits. A genesis read (`afterCommit ==
 * null`, or the cold-start case where every commit is wanted) keeps issuing
 * the unfiltered [ISSUE_QUERY]/[EDGE_QUERY] text unchanged, since there is
 * nothing to narrow by. A steady-state [DoltFeedPoller] tick against a deep
 * history now costs O(new commits), not O(history).
 *
 * Access is the `dolt` CLI via [DoltSql] (a `bd sql` connection is unusable in
 * embedded mode — BDS0). All timestamps in Dolt are UTC; this reader never
 * filters by time, only by commit hash, so the BDS0 local-time trap (a
 * local-time predicate silently returns zero rows rather than erroring) cannot
 * bite it.
 */
class DoltCommitFeed(private val sql: DiffQuery) {

    /** @param doltRoot the workspace's `.beads/embeddeddolt/<name>/` directory, not `.dolt/`. */
    constructor(doltRoot: Path) : this(DoltSql(doltRoot))

    constructor(dolt: DoltSql) : this(DiffQuery(dolt::query))

    /**
     * Commit hashes of HEAD's ancestry, genesis-first — `dolt_log` prints
     * newest-first, so this is its reverse. A commit's index here is its
     * [FeedPosition.commitHeight]; because bd's history is linear and
     * append-only under auto-commit, a commit's height does not move as later
     * commits arrive, which is what makes positions replay-stable.
     */
    fun history(): List<String> = sql.query(LOG_QUERY)
        .map { it.requiredString("commit_hash", LOG_QUERY) }
        .asReversed()

    /**
     * Reads every change after [afterCommit] (or from genesis when it is
     * `null`), in commit order, with strictly increasing positions.
     *
     * The whole result is materialised before returning: a row that cannot be
     * mapped fails the entire pass with [FeedShapeException] rather than
     * yielding the records read so far.
     *
     * @throws CheckpointNotInHistoryException if [afterCommit] is not in
     *   `dolt_log`. That is the history-truncation case; [DoltFeedPoller] owns
     *   turning it into the feature's typed compaction condition
     *   ([FeedCondition.CheckpointGone]), and this reader states it only as a
     *   precondition so a checkpoint gap can never be read as "no new
     *   commits". [CheckpointNotInHistoryException] extends
     *   [IllegalArgumentException], so it is still catchable as one.
     */
    fun readFrom(afterCommit: String? = null): List<ChangeRecord> {
        val commits = history()
        val startIndex = when (afterCommit) {
            null -> 0
            else -> commits.indexOf(afterCommit).let { found ->
                if (found < 0) throw CheckpointNotInHistoryException(afterCommit)
                found + 1
            }
        }
        if (startIndex >= commits.size) return emptyList()

        val heights = commits.withIndex().associate { (index, hash) -> hash to index.toLong() }
        val wanted = commits.subList(startIndex, commits.size).toSet()
        // A genesis read (afterCommit == null) wants every commit already, so
        // there is nothing to narrow by — keep issuing the plain, unfiltered
        // query text in that case rather than an IN(...) clause that would
        // just list the whole history back to itself.
        val full = startIndex == 0

        // Two table scans rather than two queries per commit: the diff tables
        // span the whole commit graph, the scratch databases this feeds off are
        // small, and a single scan keeps the pass internally consistent (a
        // concurrent bd mutation cannot land between per-commit queries).
        val issueDiffs = readIssueDiffs(wanted, full)
        val edgeDiffs = readEdgeDiffs(wanted, full)

        return commits.subList(startIndex, commits.size).flatMap { hash ->
            val issues = issueDiffs[hash].orEmpty().associateBy { it.issueId }
            val edges = edgeDiffs[hash].orEmpty().groupBy { it.issueId }
            // Sorted so the within-commit ordinal is a function of the commit's
            // contents alone, never of row or map ordering.
            val touched = (issues.keys + edges.keys).sorted()
            touched.mapIndexed { ordinal, issueId ->
                val issue = issues[issueId]
                ChangeRecord(
                    commitHash = hash,
                    position = FeedPosition(heights.getValue(hash), ordinal),
                    issueId = issueId,
                    diffType = issue?.diffType,
                    fieldDiffs = issue?.fieldDiffs.orEmpty(),
                    edgeDiffs = edges[issueId].orEmpty(),
                    oldMetadata = issue?.oldMetadata,
                    newMetadata = issue?.newMetadata,
                )
            }
        }
    }

    private fun readIssueDiffs(wanted: Set<String>, full: Boolean): Map<String, List<IssueDiff>> {
        val query = narrowedQuery(ISSUE_QUERY, wanted, full)
        val perCommit = mutableMapOf<String, MutableMap<String, IssueDiff>>()
        for (row in sql.query(query)) {
            val commit = row.requiredString("to_commit", ISSUE_QUERY)
            if (commit !in wanted) continue
            val diff = issueDiff(row, commit, ISSUE_QUERY)
            val forCommit = perCommit.getOrPut(commit) { mutableMapOf() }
            val clash = forCommit.put(diff.issueId, diff)
            if (clash != null) {
                throw FeedShapeException(
                    ISSUE_QUERY,
                    "two dolt_diff_issues rows for issue ${diff.issueId} in commit $commit; " +
                        "the envelope carries one record per (commit, issue)",
                )
            }
        }
        return perCommit.mapValues { (_, byIssue) -> byIssue.values.toList() }
    }

    private fun readEdgeDiffs(wanted: Set<String>, full: Boolean): Map<String, List<EdgeDiff>> =
        sql.query(narrowedQuery(EDGE_QUERY, wanted, full))
            .mapNotNull { row ->
                val commit = row.requiredString("to_commit", EDGE_QUERY)
                if (commit !in wanted) null else commit to edgeDiff(row, EDGE_QUERY)
            }
            .groupBy({ it.first }, { it.second })

    internal companion object {
        internal const val LOG_QUERY = "select commit_hash from dolt_log"
        internal const val ISSUE_QUERY = "select * from dolt_diff_issues"
        internal const val EDGE_QUERY = "select * from dolt_diff_dependencies"

        /**
         * Diff-table bookkeeping columns that are not issue fields: they exist
         * on every `dolt_diff_*` row and would otherwise be reported as field
         * changes on every record.
         */
        private val NON_FIELD_COLUMNS = setOf("commit", "commit_date")

        /**
         * Narrows [base] (one of [ISSUE_QUERY]/[EDGE_QUERY]) to a `WHERE
         * to_commit IN (...)` scan of exactly [wanted] when [full] is false —
         * a resumed read scans only the commits new since the checkpoint,
         * not the whole diff table. A genesis read ([full] true) has nothing
         * to narrow by and returns [base] unchanged, byte-for-byte, so a cold
         * start keeps issuing the plain unfiltered scan it always has.
         *
         * Commit hashes come from this same connection's own `dolt_log`
         * ([LOG_QUERY]) — never external input — but they are still
         * single-quote-escaped before being spliced into the SQL text, on
         * the general principle that no value assembled into a query string
         * should assume its own shape.
         */
        internal fun narrowedQuery(base: String, wanted: Set<String>, full: Boolean): String {
            if (full) return base
            val inList = wanted.sorted().joinToString(", ") { "'" + it.replace("'", "''") + "'" }
            return "$base where to_commit in ($inList)"
        }

        /**
         * Maps one `dolt_diff_issues` row. Exposed to tests so the shape-failure
         * paths can be exercised on hand-built rows without a doctored database.
         */
        internal fun issueDiff(
            row: Map<String, JsonElement>,
            commitHash: String,
            query: String,
        ): IssueDiff {
            val diffType = DiffType.parse(row.requiredString("diff_type", query), query)
            // A removal has no `to_` side at all, so its identity lives on the
            // `from_` side; everything else identifies by `to_id`.
            val idColumn = if (diffType == DiffType.REMOVED) "from_id" else "to_id"
            val issueId = row.requiredString(idColumn, query)

            val columns = row.keys
                .mapNotNull { key ->
                    when {
                        key.startsWith("to_") -> key.removePrefix("to_")
                        key.startsWith("from_") -> key.removePrefix("from_")
                        else -> null
                    }
                }
                .filterNot { it in NON_FIELD_COLUMNS }
                .distinct()
                .sorted()

            val fieldDiffs = columns.mapNotNull { column ->
                val old = row.valueOrNull("from_$column")
                val new = row.valueOrNull("to_$column")
                if (old == new) null else FieldDiff(column, old, new)
            }

            return IssueDiff(
                commitHash = commitHash,
                issueId = issueId,
                diffType = diffType,
                fieldDiffs = fieldDiffs,
                oldMetadata = row.valueOrNull("from_metadata").asJsonObjectOrNull(query, "from_metadata"),
                newMetadata = row.valueOrNull("to_metadata").asJsonObjectOrNull(query, "to_metadata"),
            )
        }

        /** Maps one `dolt_diff_dependencies` row. Exposed to tests for the same reason as [issueDiff]. */
        internal fun edgeDiff(row: Map<String, JsonElement>, query: String): EdgeDiff {
            val diffType = DiffType.parse(row.requiredString("diff_type", query), query)
            val side = if (diffType == DiffType.REMOVED) "from" else "to"
            return EdgeDiff(
                diffType = diffType,
                issueId = row.requiredString("${side}_issue_id", query),
                dependsOnIssueId = row.requiredString("${side}_depends_on_issue_id", query),
                type = row.requiredString("${side}_type", query),
                // MODIFIED is the only diff type with both a from_ and a to_
                // side present, so it is the only one that can carry the prior
                // type; ADDED/REMOVED have no "before" or "after" to name.
                oldType = if (diffType == DiffType.MODIFIED) row.requiredString("from_type", query) else null,
            )
        }

        private fun Map<String, JsonElement>.valueOrNull(key: String): JsonElement? =
            this[key]?.takeUnless { it is JsonNull }

        private fun Map<String, JsonElement>.requiredString(key: String, query: String): String {
            val value = valueOrNull(key)
                ?: throw FeedShapeException(query, "row is missing expected column \"$key\": $this")
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw FeedShapeException(query, "column \"$key\" is not a string: $value")
            if (text.isBlank()) {
                throw FeedShapeException(query, "column \"$key\" is blank")
            }
            return text
        }

        private fun JsonElement?.asJsonObjectOrNull(query: String, key: String): JsonObject? = when (this) {
            null -> null
            is JsonObject -> this
            else -> throw FeedShapeException(query, "column \"$key\" is not a JSON object: $this")
        }
    }
}

/** One issue's whole change within one commit, before edge diffs are attached. */
internal data class IssueDiff(
    val commitHash: String,
    val issueId: String,
    val diffType: DiffType,
    val fieldDiffs: List<FieldDiff>,
    val oldMetadata: JsonObject?,
    val newMetadata: JsonObject?,
)
