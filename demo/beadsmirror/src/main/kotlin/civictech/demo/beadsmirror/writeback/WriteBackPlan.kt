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

    /**
     * The subset of [FIELDS] `bd` treats as immutable or system-rounded once
     * an issue row exists, so a difference in one of them, ALONE, is never a
     * reason to import and never a reason to fail a post-import read-back.
     * They are still written into an [Imposition.row] when the winner
     * carries them — `bd import` silently keeps its own stored value rather
     * than rejecting the row — but [WriteBackPlanner.preflight] excludes them
     * from every comparison it makes, which is the ONE place both the
     * planner's Impose/NoOp decision ([WriteBackPlanner.plan]) and the
     * applier's post-import re-read ([WriteBackApplier]'s `readBackFailure`)
     * draw the set from (computenet-6wc.1.6 clause 3: defined once, used by
     * both).
     *
     * - `created_at`: `bd import` cannot overwrite it on a row that already
     *   exists — measured for computenet-6wc.1.6: a post-import re-read still
     *   shows the destination's own original value. Two independently
     *   `bd create`d rows for the same id (the two-node write-back setup)
     *   therefore always disagree on this field even once every other field
     *   converges, so comparing it made `Imposed` and `NoOp` alike
     *   unreachable for such a row.
     * - `updated_at`: an incoming sub-second value `>= .500` is rounded UP by
     *   bd on the way in (E4), so the stored value is bd's business rather
     *   than a failure of the imposition — computenet-6wc.1.3/.1.4. Reported
     *   inside [WriteBackEvent.Imposed.observed], not adjudicated.
     *
     * `metadata` is a DIFFERENT exclusion mechanism, not a member of this
     * set: it stays in [COMPARABLE] (a genuine user-metadata difference is
     * still a real loss), but [WriteBackPlanner.preflight] strips
     * [Provenance.STAMP_KEYS] from both sides of it before comparing
     * (6wc.3-D2, feature computenet-6wc.3) — a whole-field exclusion would
     * also hide a real user-metadata disagreement, which this task's stamp
     * keys must never do.
     */
    val NON_COMPARABLE: Set<String> = setOf("created_at", "updated_at")

    /** [FIELDS] minus [NON_COMPARABLE] — what [WriteBackPlanner.preflight] actually compares. */
    val COMPARABLE: Set<String> = FIELDS - NON_COMPARABLE
}

/**
 * The `metadata` field name, shared by [WriteBackPlanner] (which special-cases
 * it to weave in [Provenance.CN_DOT] and strip [Provenance.STAMP_KEYS] before
 * comparing) and [WriteBackApplier] (which merges [Provenance.CN_ECHO] into it
 * immediately before import). `internal` rather than `private`: both files
 * need the same literal, and a typo in a second copy would silently create a
 * field `bd import` never imposes.
 */
internal const val METADATA_FIELD: String = "metadata"

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
     *
     * [cnDot] supplies feature computenet-6wc.3's provenance stamp: the
     * `metadata.cn_dot` value to weave into each issue's row, or `null` to
     * stamp nothing (the default — every pre-6wc.3 caller keeps compiling and
     * every existing test's expectations hold unchanged, per 6wc.3-D1).
     */
    fun plan(
        view: Map<String, Map<String, String>>,
        export: List<ExportRow>,
        cnDot: (issueId: String) -> String? = { null },
    ): List<PlanOutcome> {
        val exportById = export.associateBy { it.id }
        return view.keys.sorted().map { issueId ->
            val foldFields = view.getValue(issueId)
            val exportRow = exportById[issueId]
            val row = try {
                buildRow(issueId, foldFields, exportRow, cnDot)
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
     *
     * [METADATA_FIELD] is built separately by [buildMetadata], not by the
     * generic loop below, because it alone gets a value woven in
     * ([cnDot]) rather than merely rendered.
     */
    private fun buildRow(
        issueId: String,
        foldFields: Map<String, String>,
        exportRow: ExportRow?,
        cnDot: (String) -> String?,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out[ID_FIELD] = JsonPrimitive(issueId)
        for (field in ImposedFields.FIELDS) {
            if (field == METADATA_FIELD) continue
            val foldRendering = foldFields[field] ?: continue
            out[field] = renderForImport(field, foldRendering)
        }
        val metadata = buildMetadata(issueId, foldFields[METADATA_FIELD], cnDot)
        if (metadata != null) out[METADATA_FIELD] = metadata
        return JsonObject(out)
    }

    /**
     * The imposed row's `metadata`: the fold's own metadata object with
     * [Provenance.STAMP_KEYS] stripped (6wc.3-D2 — a re-baselined fold's
     * stale stamp is never re-imposed verbatim), plus [Provenance.CN_DOT] set
     * to [cnDot]`(issueId)` when that is non-null. `null` — omitting the
     * field, exactly as before feature computenet-6wc.3 — when the fold
     * carries no metadata and [cnDot] returns `null`, so every planner test
     * that never touches `metadata` sees no behaviour change.
     */
    private fun buildMetadata(issueId: String, foldRendering: String?, cnDot: (String) -> String?): JsonObject? {
        val rendered = foldRendering?.let { renderForImport(METADATA_FIELD, it) }
        val stripped = Provenance.strip(rendered as? JsonObject)
        val dot = cnDot(issueId)
        if (stripped == null && dot == null) return null
        val out = LinkedHashMap<String, JsonElement>()
        stripped?.let { out.putAll(it) }
        if (dot != null) out[Provenance.CN_DOT] = JsonPrimitive(dot)
        return JsonObject(out)
    }

    /**
     * The losses a proposed [row] would cause against [exportRow], one per
     * allowlisted field where the two disagree. Exposed separately from
     * [plan] (rather than folded into its loop) so the applier
     * (computenet-6wc.1.3) can re-run this exact comparison against its
     * post-import re-read. Note what the applier does NOT do: it does not
     * re-read the export immediately before each import. The pre-flight loss
     * record for every row in a pass is computed against the ONE export taken
     * at the start of that pass, so a local `bd` edit landing between that
     * export and a row's import is overwritten without appearing in the loss
     * record.
     *
     * Because [ImposedFields.NON_COMPARABLE] is excluded here, and this is
     * also what fills [Imposition.losses], a loss record never names the
     * local `updated_at` an imposition overwrites, even though the imposed
     * row carries the winner's `updated_at` and `bd import` writes it.
     *
     * Compares [ImposedFields.COMPARABLE] only — [ImposedFields.NON_COMPARABLE]
     * fields (`created_at`, `updated_at`) are excluded from every comparison
     * this method makes, on both call sites (computenet-6wc.1.6 clause 3).
     *
     * [METADATA_FIELD] is compared through [Provenance.strip] on BOTH sides
     * first, and the stripped (never the raw) values are what a resulting
     * [FieldLoss] carries (6wc.3-D2/D3, feature computenet-6wc.3 clause 3): a
     * winner whose metadata differs from the export ONLY in
     * [Provenance.STAMP_KEYS] compares equal here, so it is a [PlanOutcome.NoOp]
     * or (from the applier's post-import re-read) a successful [WriteBackEvent.Imposed],
     * and no loss record this method produces ever names a stamp key.
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
        for (field in ImposedFields.COMPARABLE) {
            val rawNew = row[field]
            val rawOld = exportRow?.json?.get(field)?.takeUnless { it is JsonNull }
            val (newValue, oldValue) = if (field == METADATA_FIELD) {
                Provenance.strip(rawNew as? JsonObject) to Provenance.strip(rawOld as? JsonObject)
            } else {
                rawNew to rawOld
            }
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
