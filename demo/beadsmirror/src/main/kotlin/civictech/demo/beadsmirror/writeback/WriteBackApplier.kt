package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.util.UUID

/** What one [WriteBackApplier.applyOnce] pass did. */
data class ApplyReport(
    /** Issues whose row landed and read back intact. */
    val imposed: Int,
    /** Issues no import was run for — [SkipReason.Equal] or [SkipReason.PreviouslyFailed]. */
    val skipped: Int,
    /** Issues that failed, for any [WriteBackFailure] reason. */
    val failed: Int,
    /**
     * How many times the injected importer was called during this pass.
     *
     * This — not a Dolt commit count — is what clause 5 is checked against:
     * re-importing a row identical to the current export exits zero and adds
     * NO Dolt commit (measured 2026-09-12), so a commit count cannot
     * distinguish "no import was run" from "an import was run and changed
     * nothing".
     */
    val importerInvocations: Int,
    /** Every event emitted during this pass, in emission order. */
    val events: List<WriteBackEvent>,
)

/**
 * The EXECUTING half of write-back (feature computenet-6wc.1, task
 * computenet-6wc.1.3): runs [WriteBackPlanner]'s plan against a real bd
 * workspace, ONE `bd import --allow-stale` per row, the loss record emitted
 * before each import, the outcome decided by a post-import re-read, and a
 * failure recorded per row without stopping the queue.
 *
 * Every seam is injected, because each one is a thing a test has to observe or
 * substitute:
 *
 * - [export] — the destination's own `bd export`. Called once at the start of
 *   the pass to plan against, and again after each successful import to
 *   re-read the imposed row.
 * - [importer] — one row in, one [ImportResult] out. Production passes
 *   [BdImport.importRow]; a test counts invocations through it, and injects a
 *   failing or write-nothing importer to reach the failure paths without
 *   needing a real bd refusal.
 * - [winner] — the mirror's fold (`MirrorProjector.view()`). The applier does
 *   not care where the map came from.
 * - [onEvent] — the [WriteBackEvent] sink; events are also collected into
 *   [ApplyReport.events].
 *
 * **The pass, decided (epic computenet-6wc §1).** One [export] at run start;
 * [WriteBackPlanner.plan]; then, per outcome in the planner's id order:
 *
 * | plan outcome | what happens |
 * |---|---|
 * | [PlanOutcome.NoOp] | [WriteBackEvent.Skipped] `(Equal)` — no import (clause 5) |
 * | [PlanOutcome.Unrenderable] | [WriteBackEvent.Failed] `(Unrenderable)` — no import |
 * | [PlanOutcome.Impose], already in the failed set | [WriteBackEvent.Skipped] `(PreviouslyFailed)` — no import (clause 6) |
 * | [PlanOutcome.Impose], otherwise | [WriteBackEvent.PreFlight], THEN exactly one [importer] call |
 *
 * A non-zero exit is [WriteBackFailure.ImportExited], recorded in the failed
 * set, and **the loop continues to the next issue** — clause 6's seam. A zero
 * exit is not yet a success: the row is re-read from [export] and compared
 * field by field against what was imposed, [ImposedFields.NON_COMPARABLE]
 * fields excluded (`created_at`, `updated_at`), using the
 * planner's own [WriteBackPlanner.preflight] comparison so that the
 * instant-equality rule is not re-implemented here. Absent row ->
 * [WriteBackFailure.ReadBackMissing]; disagreement ->
 * [WriteBackFailure.ReadBackMismatch]; agreement -> [WriteBackEvent.Imposed]
 * carrying the re-read row.
 *
 * **The failed set is keyed by issue id AND the imposed row's JSON text**, and
 * lives in memory for the applier's lifetime. So a failed row is never retried
 * while the winner is unchanged (clause 6 forbids a retry loop that
 * re-adjudicates), while a CHANGED winner produces a different row, misses the
 * set, and is a fresh imposition.
 *
 * **Provenance and the echo token (feature computenet-6wc.3, decisions
 * 6wc.3-D1..D3).** [cnDot] supplies `metadata.cn_dot` -- passed straight
 * through to [WriteBackPlanner.plan], which weaves it into the built row
 * before [previouslyFailed]'s key is computed, so a re-stamped-but-otherwise-
 * identical row still hashes to the same retry key. `cn_echo` is different:
 * it is minted HERE, immediately before each import, because it must be
 * unique to that one invocation -- [previouslyFailed]'s key is computed from
 * the token-LESS row precisely so a `cn_echo` mint never defeats clause 6's
 * "no retry while the winner is unchanged". Per Impose outcome: [expectEcho]
 * is called with the fresh token strictly before [importer] runs (so a
 * concurrent echo-recognizing reader can never observe the written token
 * before the expectation that explains it); [cancelEcho] is called with the
 * same token when the import exits non-zero (the token never reached bd, so
 * nothing will ever echo it back); a zero exit keeps the expectation
 * standing, whatever the read-back later decides -- a commit landed either
 * way.
 */
class WriteBackApplier(
    private val export: () -> List<ExportRow>,
    private val importer: (JsonObject) -> ImportResult,
    private val winner: () -> Map<String, Map<String, String>>,
    private val onEvent: (WriteBackEvent) -> Unit = {},
    private val cnDot: (issueId: String) -> String? = { null },
    private val expectEcho: (issueId: String, token: String) -> Unit = { _, _ -> },
    private val cancelEcho: (issueId: String, token: String) -> Unit = { _, _ -> },
) {

    /** `(issueId, imposed row JSON text)` pairs whose import has already failed once. */
    private val previouslyFailed = mutableSetOf<Pair<String, String>>()

    /** Runs one write-back pass over the current winner and the destination's current export. */
    fun applyOnce(): ApplyReport {
        val events = mutableListOf<WriteBackEvent>()
        var invocations = 0
        var imposed = 0
        var skipped = 0
        var failed = 0

        fun emit(event: WriteBackEvent) {
            events += event
            onEvent(event)
        }

        for (outcome in WriteBackPlanner.plan(winner(), export(), cnDot)) {
            when (outcome) {
                is PlanOutcome.NoOp -> {
                    skipped++
                    emit(WriteBackEvent.Skipped(outcome.issueId, SkipReason.Equal))
                }

                is PlanOutcome.Unrenderable -> {
                    failed++
                    emit(
                        WriteBackEvent.Failed(
                            outcome.issueId,
                            WriteBackFailure.Unrenderable(outcome.field, outcome.rendering),
                        ),
                    )
                }

                is PlanOutcome.Impose -> {
                    val imposition = outcome.imposition
                    val key = imposition.issueId to imposition.row.toString()
                    if (key in previouslyFailed) {
                        skipped++
                        emit(WriteBackEvent.Skipped(imposition.issueId, SkipReason.PreviouslyFailed))
                        continue
                    }

                    // Clause 2: the loss record is observable BEFORE the import runs.
                    emit(WriteBackEvent.PreFlight(imposition.issueId, imposition.losses))

                    // 6wc.3-D3: the echo token is minted per invocation and announced
                    // BEFORE the importer runs, strictly after `key` above (which stays
                    // token-less so clause 6's retry suppression is unaffected by it).
                    val token = UUID.randomUUID().toString()
                    expectEcho(imposition.issueId, token)
                    val stampedRow = withEcho(imposition.row, token)

                    val result = importer(stampedRow)
                    invocations++

                    if (!result.succeeded) {
                        failed++
                        previouslyFailed += key
                        cancelEcho(imposition.issueId, token)
                        emit(
                            WriteBackEvent.Failed(
                                imposition.issueId,
                                WriteBackFailure.ImportExited(result.exitCode, result.stdout, result.stderr),
                            ),
                        )
                        // Clause 6's seam: the queue continues with the next issue.
                        continue
                    }

                    // Clause 4's mechanism: ONE post-import re-read decides the
                    // outcome — never the import report. The expectation registered
                    // above is NOT cancelled here, whatever the read-back decides: a
                    // commit landed either way (6wc.3-D3).
                    val observed = reRead(imposition.issueId)
                    val failure = readBackFailure(imposition, observed)
                    if (failure != null) {
                        failed++
                        previouslyFailed += key
                        emit(WriteBackEvent.Failed(imposition.issueId, failure))
                        continue
                    }

                    imposed++
                    emit(WriteBackEvent.Imposed(imposition.issueId, observed!!.json, cnDotIn(stampedRow), token))
                }
            }
        }

        return ApplyReport(imposed, skipped, failed, invocations, events)
    }

    /**
     * Clause 4's mechanism: decide the outcome from a post-import `bd export`
     * RE-READ, never from the import report.
     *
     * Returns `null` when [observed] — the re-read row — agrees with
     * [imposition] on every [ImposedFields.COMPARABLE] field (which excludes
     * `created_at` and `updated_at` — see [ImposedFields.NON_COMPARABLE]), and
     * the failure otherwise. Reuses [WriteBackPlanner.preflight] rather than
     * re-implementing the exclusion here, so the planner's Impose/NoOp
     * decision and this post-import re-read draw the excluded-field set from
     * the same place (computenet-6wc.1.6 clause 3).
     */
    private fun readBackFailure(imposition: Imposition, observed: ExportRow?): WriteBackFailure? {
        if (observed == null) return WriteBackFailure.ReadBackMissing
        val mismatches = WriteBackPlanner.preflight(imposition.row, observed)
        return if (mismatches.isEmpty()) null else WriteBackFailure.ReadBackMismatch(mismatches)
    }

    private fun reRead(issueId: String): ExportRow? = export().firstOrNull { it.id == issueId }

    /**
     * [row] with [Provenance.CN_ECHO] set to [token] inside its `metadata`
     * object (creating that object when [row] carries none) — the row the
     * importer actually receives, minted fresh for every import call
     * (6wc.3-D1/D3). [row] itself is left untouched: `key` and
     * [Imposition.losses] both continue to read the token-less row.
     */
    private fun withEcho(row: JsonObject, token: String): JsonObject {
        val existingMetadata = row[METADATA_FIELD] as? JsonObject ?: JsonObject(emptyMap())
        val stampedMetadata = JsonObject(existingMetadata + (Provenance.CN_ECHO to JsonPrimitive(token)))
        return JsonObject(row + (METADATA_FIELD to stampedMetadata))
    }

    /** The `metadata.cn_dot` string [row] carries, or `null` when it carries none. */
    private fun cnDotIn(row: JsonObject): String? {
        val metadata = row[METADATA_FIELD] as? JsonObject ?: return null
        val value = metadata[Provenance.CN_DOT] as? JsonPrimitive ?: return null
        return value.takeIf { it.isString }?.content
    }

    companion object {

        /**
         * Production wiring for one bd workspace at [workspaceRoot]: real
         * `bd export` reads, real single-row `bd import --allow-stale` writes.
         *
         * The app/CLI wiring that calls this — the `--write-back` opt-in and
         * its dedicated scheduler thread (`WorkspaceMirror.WriteBackScheduler`,
         * not the poll thread) — is the sibling app-wiring task's
         * (computenet-6wc.1.5); this factory only spares it from re-deriving
         * which three seams go together.
         */
        fun forWorkspace(
            workspaceRoot: Path,
            winner: () -> Map<String, Map<String, String>>,
            onEvent: (WriteBackEvent) -> Unit = {},
            cnDot: (issueId: String) -> String? = { null },
            expectEcho: (issueId: String, token: String) -> Unit = { _, _ -> },
            cancelEcho: (issueId: String, token: String) -> Unit = { _, _ -> },
        ): WriteBackApplier {
            val reader = BdExportReader(workspaceRoot)
            val bdImport = BdImport(workspaceRoot)
            return WriteBackApplier(reader::read, bdImport::importRow, winner, onEvent, cnDot, expectEcho, cancelEcho)
        }
    }
}
