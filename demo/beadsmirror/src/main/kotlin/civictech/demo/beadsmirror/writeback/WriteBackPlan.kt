package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.baseline.ExportRow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
 * The PURE half of the write-back applier (feature computenet-6wc.1, task
 * computenet-6wc.1.1): turns the mirror's fold (`MirrorProjector.view()`) and
 * the destination workspace's current `bd export` into a plan of single-row
 * impositions, with the loss each one would cause. No subprocess, no
 * `bd`/`dolt` invocation — that is the sibling applier
 * (computenet-6wc.1.3), which reads this file's [PlanOutcome]s and executes
 * them.
 *
 * **Why an allowlist, not a denylist.** The fold holds keys that are not
 * importable issue fields at all (`content_hash`, `is_blocked`, non-issue-kind
 * columns, the presence key), and `bd export` rows carry keys that are not
 * fold content either (`_type`, `labels`, `dependencies`, `comments`,
 * `*_count`). [ImposedFields] names exactly what both sides agree is a real,
 * importable issue field; anything else is neither compared nor imposed, and
 * widening the set is a deliberate edit to that one place.
 */
object ImposedFields {

    /** `bd import`-writable issue fields this applier compares and imposes. */
    val FIELDS: Set<String> = setOf(
        "title",
        "description",
        "design",
        "acceptance_criteria",
        "notes",
        "priority",
        "status",
        "issue_type",
        "assignee",
        "owner",
        "closed_at",
        "close_reason",
        "started_at",
        "external_ref",
        "spec_id",
        "estimated_minutes",
        "due_at",
        "defer_until",
        "metadata",
        "created_at",
        "created_by",
        "updated_at",
    )
}

/**
 * Raised by [renderForImport] when a fold rendering is not JSON at all — the
 * one way rendering a single field can fail. Carries [field] and [rendering]
 * so [WriteBackPlanner.plan] can turn it into a per-issue
 * [PlanOutcome.Unrenderable] rather than a partial row.
 */
class UnrenderableFieldException(
    val field: String,
    val rendering: String,
    cause: Throwable? = null,
) : RuntimeException("field \"$field\" rendering is not JSON: $rendering", cause)

/**
 * The Dolt-diff-feed rendering of a UTC timestamp: `"2026-09-12 07:17:03"` —
 * no `T`, no zone. Duplicated from
 * [civictech.demo.beadsmirror.equality.MirrorExportEquality]'s own
 * `DOLT_DATETIME` on purpose: that normalizer lives in this module's TEST
 * source set, unreachable from `main` (see that object's KDoc), so the rule
 * has to be re-stated here rather than shared.
 */
private val DOLT_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Renders one allowlisted field's fold value ([foldRendering], a
 * `JsonElement.toString()` string as [civictech.demo.beadsmirror.projector.MirrorProjector]
 * stores it) into the JSON value `bd import` will accept.
 *
 * `bd import` rejects the Dolt-diff-feed datetime rendering outright (measured
 * fact 1 on this task's bead: `parsing time "2026-09-12 07:17:05" as
 * "2006-01-02T15:04:05Z07:00"` — a hard parse error, nothing written). So a
 * fold rendering that is a JSON string matching that shape is re-rendered as
 * an RFC3339 `Z` instant; a JSON string that already parses as an `Instant`
 * (bd-export's own rendering, sub-second parts included) passes through
 * unchanged; every other JSON value (numbers, booleans, objects such as
 * `metadata`, arrays) passes through structurally, untouched.
 *
 * @throws UnrenderableFieldException when [foldRendering] is not JSON at all
 *   — a planning failure for the whole issue, per [WriteBackPlanner.plan].
 */
fun renderForImport(field: String, foldRendering: String): JsonElement {
    val parsed = try {
        Json.parseToJsonElement(foldRendering)
    } catch (e: SerializationException) {
        throw UnrenderableFieldException(field, foldRendering, e)
    }

    val text = (parsed as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return parsed

    val doltRendering = try {
        LocalDateTime.parse(text, DOLT_DATETIME)
    } catch (e: DateTimeParseException) {
        null
    }
    if (doltRendering != null) {
        return JsonPrimitive(doltRendering.toInstant(ZoneOffset.UTC).toString())
    }

    // Not the Dolt rendering. Whether or not it happens to parse as an
    // Instant already (bd-export's own rendering), the text is unchanged
    // either way — this branch exists only to document that an
    // already-RFC3339 string is deliberately left alone, not coincidentally.
    return parsed
}

/**
 * One local field value an [Imposition] overwrites.
 *
 * [old] is the destination's current value (`bd export`'s JSON value for
 * [field]), or `null` when the destination carries no value for it. [new] is
 * the winner's rendered value, or `null` when the winner lacks the field
 * outright — the import will then clear it (measured fact 2: an omitted key
 * clears the field; `null` and `""` do the same).
 */
data class FieldLoss(val field: String, val old: JsonElement?, val new: JsonElement?)

/**
 * One issue's single-row imposition: the exact one-line JSONL [row] `bd
 * import` will receive for [issueId], and the [losses] it will cause.
 *
 * [row] carries no key outside [ImposedFields] plus `"id"` — every other key
 * the destination's export row held (`_type`, `labels`, `dependencies`,
 * `comments`, the `*_count` aggregates) is stripped before it ever reaches
 * this type.
 */
data class Imposition(val issueId: String, val row: JsonObject, val losses: List<FieldLoss>)

/** One issue's planning outcome. */
sealed interface PlanOutcome {

    /** The winner differs from the destination on at least one allowlisted field: import [imposition.row]. */
    data class Impose(val imposition: Imposition) : PlanOutcome

    /** The winner and the destination already agree on every allowlisted field: nothing to do. */
    data class NoOp(val issueId: String) : PlanOutcome

    /**
     * [field]'s fold [rendering] for [issueId] is not JSON at all, so no row
     * could be built for this issue. No other issue in the same [plan] call
     * is affected.
     */
    data class Unrenderable(val issueId: String, val field: String, val rendering: String) : PlanOutcome
}

/**
 * Turns a fold (`MirrorProjector.view()`) and a destination `bd export`
 * snapshot into a deterministic list of [PlanOutcome]s, one per issue the
 * fold holds. No I/O, no `bd`/`dolt` on PATH.
 */
object WriteBackPlanner {

    private const val ID_FIELD = "id"

    /**
     * Plans every issue [view] holds present, sorted by issue id for
     * determinism (equal inputs -> equal output lists, in the same order).
     *
     * An issue the fold holds but the export does not (never created in the
     * destination workspace) is still [PlanOutcome.Impose] — `bd import`
     * upserts, so the row creates it. An issue the export holds but the fold
     * does not is never visited here at all: a row absent from the fold is
     * never treated as a deletion (that is feature computenet-6wc.2's
     * boundary).
     */
    fun plan(view: Map<String, Map<String, String>>, export: List<ExportRow>): List<PlanOutcome> {
        val exportById = export.associateBy { it.id }
        return view.keys.sorted().map { issueId ->
            val foldFields = view.getValue(issueId)
            val exportRow = exportById[issueId]
            val row = try {
                buildRow(issueId, foldFields, exportRow)
            } catch (e: UnrenderableFieldException) {
                return@map PlanOutcome.Unrenderable(issueId, e.field, e.rendering)
            }
            val losses = preflight(row, exportRow)
            if (losses.isEmpty()) PlanOutcome.NoOp(issueId) else PlanOutcome.Impose(Imposition(issueId, row, losses))
        }
    }

    /**
     * The one-line JSONL row for [issueId]: `"id"` plus, for every field in
     * [ImposedFields], the winner's [renderForImport]ed value when [foldFields]
     * carries it, or nothing at all when it doesn't (whether or not the
     * destination's [exportRow] carries the field — its absence from [row]
     * clears it on import either way, per measured fact 2). No key outside
     * that set is ever written, regardless of what [exportRow] holds.
     */
    private fun buildRow(issueId: String, foldFields: Map<String, String>, exportRow: ExportRow?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out[ID_FIELD] = JsonPrimitive(issueId)
        for (field in ImposedFields.FIELDS) {
            val foldRendering = foldFields[field] ?: continue
            out[field] = renderForImport(field, foldRendering)
        }
        return JsonObject(out)
    }

    /**
     * The losses a proposed [row] would cause against [exportRow], one per
     * allowlisted field where the two disagree. Exposed separately from
     * [plan] (rather than folded into its loop) so the applier
     * (computenet-6wc.1.3) can re-run this exact comparison against a
     * freshly re-read export immediately before importing — the
     * pre-flight instrument the feature's clause 2 requires the loss record
     * to precede.
     *
     * [row]'s value for a field (or its absence) is compared against
     * [exportRow]'s (a JSON `null` on the export side counts as absent, the
     * same convention [civictech.demo.beadsmirror.equality.MirrorExportEquality]
     * and [civictech.demo.beadsmirror.baseline.BaselineBuilder] apply):
     * structurally equal values are equal outright; failing that, when BOTH
     * sides are JSON strings that parse as an RFC3339 [Instant], they are
     * compared as instants rather than text. [buildRow] already rendered
     * [row]'s datetime fields into RFC3339 `Z` (the same family `bd export`
     * prints), but review found that byte-for-byte comparison alone is
     * accidental string equality: an export value carrying an explicit zero
     * fractional part (`"...03.000Z"`) represents the same instant as a
     * bare-seconds rendering (`"...03Z"`) yet differs as text, which would
     * otherwise impose a phantom loss on an already-agreeing row (mirrors
     * [civictech.demo.beadsmirror.equality.MirrorExportEquality.asInstant]'s
     * reason for existing at all).
     */
    fun preflight(row: JsonObject, exportRow: ExportRow?): List<FieldLoss> {
        val losses = mutableListOf<FieldLoss>()
        for (field in ImposedFields.FIELDS) {
            val newValue = row[field]
            val oldValue = exportRow?.json?.get(field)?.takeUnless { it is JsonNull }
            if (!valuesAgree(newValue, oldValue)) {
                losses += FieldLoss(field, old = oldValue, new = newValue)
            }
        }
        return losses
    }

    /** Structural equality, falling back to instant equality when both sides are RFC3339 strings. */
    private fun valuesAgree(newValue: JsonElement?, oldValue: JsonElement?): Boolean {
        if (newValue == oldValue) return true
        val newInstant = newValue?.let(::asRfc3339Instant) ?: return false
        val oldInstant = oldValue?.let(::asRfc3339Instant) ?: return false
        return newInstant == oldInstant
    }

    /** The value as an [Instant] if it is a JSON string in RFC3339 form; `null` otherwise (including non-date fields). */
    private fun asRfc3339Instant(element: JsonElement): Instant? {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
