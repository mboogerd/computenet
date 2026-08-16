package civictech.demo.beadsmirror.equality

import civictech.demo.beadsmirror.baseline.BaselineBuilder
import civictech.demo.beadsmirror.baseline.BdExportException
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.projector.MirrorEdge
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One typed way [civictech.demo.beadsmirror.projector.MirrorProjector.view]/
 * [civictech.demo.beadsmirror.projector.MirrorProjector.edgeView] can diverge
 * from `bd export` of the same workspace (computenet-dqj.5.1 — the
 * field-comparison half of the epic's mirror-vs-export equality gate).
 *
 * Every case names the issue (and, for fields, the field) it is about, and
 * every value-bearing case carries **both** renderings verbatim — that is
 * feature computenet-dqj.5's own reporting-shape requirement, and it is what
 * lets a divergence-control test (computenet-dqj.5.3) assert not just that
 * equality failed but *what* it failed on: "a removed issue present as a
 * partial record" is only checkable if [UnexpectedIssue] actually names the
 * surviving fields.
 */
sealed interface Divergence

/** `bd export` prints [issueId]; the fold's `view()` has no entry for it at all. */
data class MissingIssue(val issueId: String) : Divergence

/**
 * The fold's `view()` has an entry for [issueId] that `bd export` does not
 * print at all. [survivingFields] is the issue's *whole* field-key set in the
 * fold at the moment of comparison — not just the ones that would otherwise
 * be compared — because the whole point of this case (rules 3/4 of the
 * feature's acceptance) is to make a "removed issue resurrected as a partial
 * record" checkable: the reader needs to see exactly which fields survived.
 */
data class UnexpectedIssue(val issueId: String, val survivingFields: Set<String>) : Divergence

/**
 * [issueId]/[field] is in the compared universe on both sides, and the
 * renderings do not agree once normalized (§ [MirrorExportEquality.compare]).
 * [foldRendering] is `null` when the fold carries no value at all for a field
 * `bd export` did print — a value that should have been mirrored is missing
 * outright, which is as much a mismatch as two different strings.
 */
data class FieldMismatch(
    val issueId: String,
    val field: String,
    val foldRendering: String?,
    val exportRendering: String,
) : Divergence

/**
 * The fold carries [field] for [issueId] outside the compared universe (not
 * one of `bd export`'s keys for that issue, minus exclusions) and outside
 * [MirrorExportEquality.FEED_ONLY] — an extra key with no license to be
 * there.
 */
data class UnexpectedField(val issueId: String, val field: String, val foldRendering: String) : Divergence

/** `bd export`'s `dependencies` union names this edge; `edgeView()` does not carry it. */
data class MissingEdge(val edge: MirrorEdge) : Divergence

/** `edgeView()` carries this edge; no export row's `dependencies` names it. */
data class UnexpectedEdge(val edge: MirrorEdge) : Divergence

/**
 * Pure comparator: `(view, edgeView, bd export rows)` -> the list of ways they
 * disagree. An empty list means equal. No I/O, no `bd`/`dolt` on PATH needed —
 * every rule here is a pure function of its three arguments
 * ([MirrorExportEqualityTest] drives it entirely on hand-built rows); the
 * *values* pinned below (the excluded-field set, [FEED_ONLY], the two
 * accepted datetime renderings) were themselves derived from a live scratch
 * workspace, not guessed (probe: computenet-dqj.5's breakdown comment
 * thread, re-verified 2026-08-16 against `bd` 1.1.2 / `dolt` 2.2.3).
 */
object MirrorExportEquality {

    /**
     * The `issues`-table columns [civictech.demo.beadsmirror.feed.DoltCommitFeed]
     * always diffs (every column bar its own bookkeeping ones) but `bd
     * export`'s issue schema does not always print — either because it is a
     * purely internal/derived column `bd export` never prints regardless of
     * value (`content_hash`, `is_blocked` — verified live: `is_blocked` stays
     * absent from export even for a genuinely blocked issue), or because it
     * backs a *different* row "kind" sharing the same physical `issues` table
     * (events/hooks/roles/gates/wisps: `event_kind`, `actor`, `target`,
     * `payload`, `await_type`, `await_id`, `timeout_ns`, `waiters`,
     * `hook_bead`, `role_bead`, `role_type`, `rig`, `agent_state`, `sender`,
     * `wisp_type`, `mol_type`, `work_type`), or because it is a genuine
     * optional issue field `bd export` omits while unset/default and prints
     * once set (`description`, `design`, `acceptance_criteria`, `notes`,
     * `assignee`, `estimated_minutes`, `closed_at`, `external_ref`,
     * `spec_id`, `close_reason`, `metadata`, `due_at`, `defer_until`,
     * `no_history`, `started_at`, `owner` — each verified live to appear in
     * export once given a non-default value via `bd update`).
     *
     * A field in this set is only ever a *tolerated fold-only* key — one
     * whose absence from export for one issue does not mean it should be
     * absent from the fold too. It never suppresses a real comparison: the
     * moment a field in this set *does* appear in an export row (because bd
     * gave it a non-default value), that row's key is in the compared
     * universe like any other and is compared for value like any other —
     * this set only governs whether its *absence* from one issue's export
     * row is a divergence.
     *
     * Everything not in this set and not [BaselineBuilder.EXCLUDED_FIELDS] is
     * a "core" field `bd export` prints on every issue unconditionally: `id`,
     * `title`, `status`, `priority`, `issue_type`, `created_at`,
     * `created_by`, `updated_at` — pinned by the live-workspace half of
     * [MirrorExportEqualityTest].
     *
     * **`owner` used to be listed as core, and that was wrong**
     * (computenet-1anx). `bd` derives an issue's owner from git's configured
     * `user.email`; a workspace created where git has no identity — a stock
     * GitHub Actions runner — stores `owner = ''` and `bd export` then omits
     * the key, exactly like every other optional field above. The claim held
     * only because it was verified on developer machines, which always
     * resolve an owner, and its falsity was invisible until this module's
     * real-workspace suites first ran on `ubuntu-latest`, where the
     * feed-built fold's `owner` -> `""` was reported as an [UnexpectedField]
     * in 8 tests. `owner` is therefore a FEED_ONLY field, not a core one; it
     * is still compared for value on every workspace whose export does print
     * it, which is what
     * `MirrorExportEqualityTest.an owner export prints is still compared for value`
     * and the ownerless live probe in [MirrorExportEqualityTest] pin between
     * them.
     */
    val FEED_ONLY: Set<String> = setOf(
        "content_hash",
        "description",
        "design",
        "acceptance_criteria",
        "notes",
        "assignee",
        "owner",
        "estimated_minutes",
        "closed_at",
        "closed_by_session",
        "external_ref",
        "spec_id",
        "compaction_level",
        "compacted_at",
        "compacted_at_commit",
        "original_size",
        "sender",
        "ephemeral",
        "wisp_type",
        "pinned",
        "is_template",
        "mol_type",
        "work_type",
        "source_system",
        "metadata",
        "source_repo",
        "close_reason",
        "event_kind",
        "actor",
        "target",
        "payload",
        "await_type",
        "await_id",
        "timeout_ns",
        "waiters",
        "hook_bead",
        "role_bead",
        "agent_state",
        "last_activity",
        "role_type",
        "rig",
        "due_at",
        "defer_until",
        "no_history",
        "started_at",
        "is_blocked",
    )

    /**
     * Export keys that are never issue fields at all: [BaselineBuilder]'s own
     * exclusions (`_type`, the nested `dependencies` array, the three derived
     * `*_count`s), reused rather than copied, plus `labels` — `labels` lives
     * in its own Dolt table, not on `issues`, so a label-only `bd update
     * --add-label` commits zero `dolt_diff_issues` rows and the feed can
     * never carry it (probe: computenet-dqj.5's breakdown comment thread).
     * [BaselineBuilder.EXCLUDED_FIELDS] already carries `"labels"` — this
     * feature's other change is adding it there, so a re-baselined fold does
     * not carry a key a feed-built fold never has either.
     */
    private val EXCLUDED_FIELDS: Set<String> get() = BaselineBuilder.EXCLUDED_FIELDS

    /** The export's spelling of a dependency's far side — see [BaselineBuilder]'s note on the same asymmetry. */
    private const val DEPENDS_ON_ID_FIELD = "depends_on_id"
    private const val ISSUE_ID_FIELD = "issue_id"
    private const val TYPE_FIELD = "type"

    private val DOLT_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Compares [view]/[edgeView] (the fold, read via
     * [civictech.demo.beadsmirror.projector.MirrorProjector.view]/`edgeView()`)
     * against [export] (`bd export` of the same workspace, as [ExportRow]s).
     * Returns every [Divergence] found; an empty list means the two agree on
     * every compared field, on exactly the set of present issues, and on the
     * exact dependency-edge set.
     */
    fun compare(
        view: Map<String, Map<String, String>>,
        edgeView: Set<MirrorEdge>,
        export: List<ExportRow>,
    ): List<Divergence> {
        val divergences = mutableListOf<Divergence>()
        val exportById = export.associateBy { it.id }

        for ((issueId, row) in exportById) {
            val foldFields = view[issueId]
            if (foldFields == null) {
                divergences += MissingIssue(issueId)
                continue
            }
            divergences += compareFields(issueId, foldFields, row)
        }

        for (issueId in view.keys - exportById.keys) {
            divergences += UnexpectedIssue(issueId, view.getValue(issueId).keys)
        }

        divergences += compareEdges(export, edgeView)

        return divergences
    }

    /**
     * The compared field universe for one issue = [row]'s own JSON keys minus
     * [EXCLUDED_FIELDS] (rule 1 of the feature description). A JSON-null
     * export value counts as absent, the same rule [BaselineBuilder.fieldDiffs]
     * applies, so the two paths agree on which keys exist at all.
     */
    private fun compareFields(issueId: String, foldFields: Map<String, String>, row: ExportRow): List<Divergence> {
        val divergences = mutableListOf<Divergence>()
        val universe = row.json.keys - EXCLUDED_FIELDS

        for (field in universe) {
            val exportValue = row.json.getValue(field).takeUnless { it is JsonNull } ?: continue
            val foldRendering = foldFields[field]
            if (foldRendering == null || !valuesMatch(foldRendering, exportValue)) {
                divergences += FieldMismatch(issueId, field, foldRendering, exportValue.toString())
            }
        }

        for (field in foldFields.keys - universe) {
            if (field !in FEED_ONLY) {
                divergences += UnexpectedField(issueId, field, foldFields.getValue(field))
            }
        }

        return divergences
    }

    /**
     * Structural equality of the fold's stored rendering (a `JsonElement.toString()`,
     * per [civictech.demo.beadsmirror.projector.MirrorProjector]'s own storage
     * rule) against the export's [JsonElement], with one normalization: when
     * both sides are JSON strings that both parse as one of the two accepted
     * datetime renderings, compare as instants rather than text — `bd export`
     * prints `"2026-08-16T04:33:37Z"` while the diff-row rendering the
     * projector stores verbatim is `"2026-08-16 04:33:37"` (no `T`, no zone —
     * Dolt's own timestamps are UTC, never local; probed live 2026-08-16, see
     * [MirrorExportEqualityTest]).
     */
    private fun valuesMatch(foldRendering: String, exportValue: JsonElement): Boolean {
        val foldValue = try {
            Json.parseToJsonElement(foldRendering)
        } catch (e: SerializationException) {
            return false
        }
        if (foldValue == exportValue) return true

        val foldInstant = asInstant(foldValue) ?: return false
        val exportInstant = asInstant(exportValue) ?: return false
        return foldInstant == exportInstant
    }

    /** The value as an [Instant], if it is a JSON string in either accepted datetime rendering; `null` otherwise. */
    private fun asInstant(element: JsonElement): Instant? {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return try {
            Instant.parse(text) // "2026-08-16T04:33:37Z" — bd export's rendering
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(text, DOLT_DATETIME).toInstant(ZoneOffset.UTC) // "2026-08-16 04:33:37" — the diff-row rendering
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * Expected edges = the union over every export row's `dependencies` array
     * as `(issue_id, depends_on_id, type)` triples — note the export's field
     * spells the far side `depends_on_id` where the `dependencies` *table*
     * (what the feed actually diffs) spells it `depends_on_issue_id`
     * ([BaselineBuilder] pins the same asymmetry). Actual = [edgeView] as
     * [MirrorEdge] triples. Set equality; dependency `created_at` is never
     * compared at all — it is not part of [MirrorEdge], so there is nothing
     * to compare it against (known bd 1.1.2 defect, feature description).
     */
    private fun compareEdges(export: List<ExportRow>, edgeView: Set<MirrorEdge>): List<Divergence> {
        val expected = export.flatMapTo(mutableSetOf()) { expectedEdgesOf(it) }
        val divergences = mutableListOf<Divergence>()
        (expected - edgeView).forEach { divergences += MissingEdge(it) }
        (edgeView - expected).forEach { divergences += UnexpectedEdge(it) }
        return divergences
    }

    private fun expectedEdgesOf(row: ExportRow): Set<MirrorEdge> {
        val element = row.json[ExportRow.DEPENDENCIES_FIELD]?.takeUnless { it is JsonNull } ?: return emptySet()
        val array = element as? JsonArray
            ?: throw BdExportException(row.json.toString(), "\"${ExportRow.DEPENDENCIES_FIELD}\" of issue ${row.id} is not a JSON array")
        return array.mapTo(mutableSetOf()) { entry ->
            val dependency = entry as? JsonObject
                ?: throw BdExportException(row.json.toString(), "a \"${ExportRow.DEPENDENCIES_FIELD}\" entry of issue ${row.id} is not a JSON object")
            MirrorEdge(
                issueId = dependency.requiredString(ISSUE_ID_FIELD, row),
                dependsOnIssueId = dependency.requiredString(DEPENDS_ON_ID_FIELD, row),
                type = dependency.requiredString(TYPE_FIELD, row),
            )
        }
    }

    private fun JsonObject.requiredString(key: String, row: ExportRow): String {
        val value = this[key]?.takeUnless { it is JsonNull }
            ?: throw BdExportException(row.json.toString(), "dependency of issue ${row.id} is missing \"$key\": $this")
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BdExportException(row.json.toString(), "dependency field \"$key\" of issue ${row.id} is not a string: $value")
        if (text.isBlank()) {
            throw BdExportException(row.json.toString(), "dependency field \"$key\" of issue ${row.id} is blank")
        }
        return text
    }
}
