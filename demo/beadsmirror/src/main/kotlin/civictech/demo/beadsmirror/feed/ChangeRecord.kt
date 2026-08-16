package civictech.demo.beadsmirror.feed

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How a diff row relates the two sides of a commit, mapped from the
 * `diff_type` column of `dolt_diff_issues` / `dolt_diff_dependencies`.
 */
enum class DiffType {
    ADDED,
    MODIFIED,
    REMOVED,
    ;

    companion object {
        /**
         * Maps Dolt's wire spelling. An unrecognised value is a shape the
         * envelope cannot express, so it fails loudly rather than being
         * coerced into the nearest neighbour (computenet-dqj.1.2's shape
         * clause).
         */
        fun parse(raw: String, query: String): DiffType = when (raw) {
            "added" -> ADDED
            "modified" -> MODIFIED
            "removed" -> REMOVED
            else -> throw FeedShapeException(query, "unknown diff_type \"$raw\"")
        }
    }
}

/**
 * One column's change within a commit. [old] and [new] are the raw JSON values
 * `dolt sql -r json` printed for the `from_`/`to_` sides; `null` means the
 * column was SQL NULL or absent on that side (Dolt omits null keys), which is
 * the normal shape of the `from_` side of an [DiffType.ADDED] row and of the
 * `to_` side of a [DiffType.REMOVED] one.
 */
data class FieldDiff(val column: String, val old: JsonElement?, val new: JsonElement?)

/**
 * One dependency-edge change from `dolt_diff_dependencies`, attached to the
 * record of the issue it belongs to ([issueId], the edge's *owning* side).
 *
 * Note that the diff table's bare `to_id`/`from_id` is a UUID row key, not an
 * issue id — the issue ids live in `to_issue_id`/`to_depends_on_issue_id` (and
 * their `from_` counterparts for removals), verified on a live scratch
 * workspace during this feature's breakdown.
 *
 * [oldType] is the `from_type` side of a [DiffType.MODIFIED] row — the
 * relation type this (issueId, dependsOnIssueId) pair carried *before* the
 * commit — and is `null` for every other [diffType]. `bd`'s own schema proves
 * a pair holds at most one live type at a time: `dependencies` has
 * `UNIQUE KEY uk_dep_issue_target (issue_id, depends_on_issue_id)` (verified
 * on a live scratch workspace, computenet-dqj.7), and `bd dep add` itself
 * refuses to add a second type over an existing pair without a `dep remove`
 * first. A `MODIFIED` row is therefore always a type *replacement*, never a
 * second live type — carrying the prior type is what lets a consumer retract
 * the stale (issueId, dependsOnIssueId, oldType) triple instead of leaving it
 * alongside the new one forever (computenet-dqj.7).
 */
data class EdgeDiff(
    val diffType: DiffType,
    val issueId: String,
    val dependsOnIssueId: String,
    val type: String,
    val oldType: String? = null,
)

/**
 * Everything one commit did to one issue, as a single unit.
 *
 * Atomicity is the point: a single `bd update --status --priority` produces one
 * commit whose `dolt_diff_issues` row carries both changes, and this record
 * keeps them together so the projector (computenet-dqj.2) can fold them into
 * ONE `TaggedMapDelta`. Splitting them here would be unrecoverable downstream.
 *
 * [diffType] is `null` for a record that exists only because the commit touched
 * this issue's dependency edges without leaving a `dolt_diff_issues` row for
 * it — an edge-carrying, field-quiet record. (The converse also occurs: a
 * `bd dep add` commit usually *does* write a modified issue row for updated_at
 * churn, so a record can be edge-carrying with an ordinary [DiffType.MODIFIED].)
 *
 * [oldMetadata] / [newMetadata] carry the issue's `metadata` JSON column on
 * both sides whether or not it changed, so echo-drop provenance
 * (`metadata.cn_dot`, epic computenet-dqj acceptance) is readable off any
 * record that had an issue row, not only off the commits that rewrote it.
 */
data class ChangeRecord(
    val commitHash: String,
    val position: FeedPosition,
    val issueId: String,
    val diffType: DiffType?,
    val fieldDiffs: List<FieldDiff>,
    val edgeDiffs: List<EdgeDiff>,
    val oldMetadata: JsonObject? = null,
    val newMetadata: JsonObject? = null,
) {
    /** The change to [column] in this commit, or `null` if the column did not change. */
    fun fieldDiff(column: String): FieldDiff? = fieldDiffs.firstOrNull { it.column == column }
}
