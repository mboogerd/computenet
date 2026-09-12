package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

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
 * field by field against what was imposed, `updated_at` excluded, using the
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
 */
class WriteBackApplier(
    private val export: () -> List<ExportRow>,
    private val importer: (JsonObject) -> ImportResult,
    private val winner: () -> Map<String, Map<String, String>>,
    private val onEvent: (WriteBackEvent) -> Unit = {},
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

        for (outcome in WriteBackPlanner.plan(winner(), export())) {
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

                    val result = importer(imposition.row)
                    invocations++

                    if (!result.succeeded) {
                        failed++
                        previouslyFailed += key
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
                    // outcome — never the import report.
                    val observed = reRead(imposition.issueId)
                    val failure = readBackFailure(imposition, observed)
                    if (failure != null) {
                        failed++
                        previouslyFailed += key
                        emit(WriteBackEvent.Failed(imposition.issueId, failure))
                        continue
                    }

                    imposed++
                    emit(WriteBackEvent.Imposed(imposition.issueId, observed!!.json))
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
     * [imposition] on every imposed field except `updated_at`, and the failure
     * otherwise.
     */
    private fun readBackFailure(imposition: Imposition, observed: ExportRow?): WriteBackFailure? {
        if (observed == null) return WriteBackFailure.ReadBackMissing
        val mismatches = WriteBackPlanner.preflight(imposition.row, observed)
            .filterNot { it.field == UPDATED_AT }
        return if (mismatches.isEmpty()) null else WriteBackFailure.ReadBackMismatch(mismatches)
    }

    private fun reRead(issueId: String): ExportRow? = export().firstOrNull { it.id == issueId }

    companion object {

        /**
         * The field excluded from the post-import comparison, on purpose: an
         * incoming sub-second `updated_at` of `>= .500` is rounded UP by bd on
         * the way in (E4), so the stored value is bd's business rather than a
         * failure of the imposition. It is reported inside
         * [WriteBackEvent.Imposed.observed], not adjudicated.
         */
        const val UPDATED_AT: String = "updated_at"

        /**
         * Production wiring for one bd workspace at [workspaceRoot]: real
         * `bd export` reads, real single-row `bd import --allow-stale` writes.
         *
         * The app/CLI wiring that calls this — the `--write-back` opt-in and
         * the poll-thread scheduling — is the sibling app-wiring task's
         * (computenet-6wc.1.5); this factory only spares it from re-deriving
         * which three seams go together.
         */
        fun forWorkspace(
            workspaceRoot: Path,
            winner: () -> Map<String, Map<String, String>>,
            onEvent: (WriteBackEvent) -> Unit = {},
        ): WriteBackApplier {
            val reader = BdExportReader(workspaceRoot)
            val bdImport = BdImport(workspaceRoot)
            return WriteBackApplier(reader::read, bdImport::importRow, winner, onEvent)
        }
    }
}
