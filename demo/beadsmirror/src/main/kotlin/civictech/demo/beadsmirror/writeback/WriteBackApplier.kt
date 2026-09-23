package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.demo.beadsmirror.doltRootFor
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
    /**
     * Issues the plan said to impose but that were left alone this pass
     * because the destination's working set held an uncommitted write to them
     * at the moment of the import decision (computenet-oagbm; see
     * [WriteBackApplier]'s "In-flight local writes are deferred"). No event,
     * no pre-flight, no echo expectation and no import for them; the next
     * pass re-plans them from a fresh export.
     */
    val deferred: List<String> = emptyList(),
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
 * | [PlanOutcome.Impose], named by [inFlight] | added to [ApplyReport.deferred] — no event, no import (computenet-oagbm) |
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
 *
 * **In-flight local writes are deferred (computenet-oagbm).** [inFlight]
 * names every issue whose row in the destination's Dolt WORKING set differs
 * from its HEAD commit -- a write some process has made and not yet
 * committed. For each [PlanOutcome.Impose] that is not already
 * [SkipReason.PreviouslyFailed], the applier calls [inFlight] immediately
 * before the loss record and the import, strictly AFTER the pass-start
 * [export]; an issue it names is added to [ApplyReport.deferred] and nothing
 * else happens to it this pass.
 *
 * Why: bd 1.1.2 writes the shared working set and THEN commits the whole
 * working set, as two steps that are not atomic across processes. A `bd
 * update` caught between them is visible to [export], so the planner sees
 * the edit as a divergence from the fold and would impose the fold's value
 * over it. The import's commit then sweeps the row in as fold-value ->
 * fold-value plus a fresh `cn_echo` (classified ECHO), the update finds
 * nothing left to commit and exits 0, and no commit anywhere records the
 * edit -- it is lost to bd and to the mirror alike (measured on
 * computenet-oagbm: 2 of 32 raced iterations under reader load). A deferred
 * row costs one pass of latency and no data: once the writer commits, the
 * edit is an ordinary LOCAL commit the mirror ingests (epic computenet-6wc:
 * never-gossiped local edits survive; correctness outranks commit thrift).
 *
 * Ordering matters: querying [inFlight] BEFORE [export] would let a write
 * land between the two, be seen by the export, and not be named as in
 * flight. After the export, a write that is visible to it is either still
 * uncommitted at the [inFlight] query (deferred) or already committed (a
 * LOCAL commit exists, so an overwrite is re-adjudicated by the mirror, not
 * lost -- see below).
 *
 * **Residual limits, stated where the claim is made.**
 * - A write that lands AFTER the [inFlight] query and before the
 *   [importer]'s own write is still overwritten and swept. The planner only
 *   imposes a row the fold disagrees with, and a write landing that late was
 *   not visible to the export, so this needs a genuine imposition (a peer's
 *   winner) racing a local edit of the SAME row within one import's runtime.
 *   It is narrowed, not closed: closing it needs a cross-process lock bd
 *   does not offer (the non-atomic write/commit is upstream bd's).
 * - A local edit that is already COMMITTED but not yet ingested by the
 *   poller is not deferred. It is overwritten, then re-adjudicated when its
 *   LOCAL commit is ingested -- the self-healing path that predates this
 *   guard -- at the cost of a transient revert in bd. Deferring it too would
 *   need the feed checkpoint, which is not one of this class's seams.
 * - The pre-flight loss record for every row is still computed against the
 *   ONE pass-start [export] (computenet-uv65o clause 4). A local edit
 *   COMMITTED between that export and the row's import is overwritten
 *   without a [WriteBackEvent.PreFlight] loss naming it (it is not lost: its
 *   commit is LOCAL, per the previous point). An edit still UNCOMMITTED at
 *   that point is deferred by the guard above, which is what uv65o's
 *   accepted-limit note did not anticipate: that window did lose edits.
 * - The default [inFlight] names nothing, i.e. no guard. Only [forWorkspace]
 *   (the production wiring) supplies the real query; a caller constructing
 *   this class directly against a live workspace must supply it too.
 */
class WriteBackApplier(
    private val export: () -> List<ExportRow>,
    private val importer: (JsonObject) -> ImportResult,
    private val winner: () -> Map<String, Map<String, String>>,
    private val onEvent: (WriteBackEvent) -> Unit = {},
    private val cnDot: (issueId: String) -> String? = { null },
    private val expectEcho: (issueId: String, token: String) -> Unit = { _, _ -> },
    private val cancelEcho: (issueId: String, token: String) -> Unit = { _, _ -> },
    private val inFlight: () -> Set<String> = { emptySet() },
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
        val deferred = mutableListOf<String>()

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

                    // computenet-oagbm: never impose over a write the destination
                    // has made and not yet committed. Queried here -- after the
                    // pass-start export, immediately before the import -- per row.
                    if (imposition.issueId in inFlight()) {
                        deferred += imposition.issueId
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

        return ApplyReport(imposed, skipped, failed, invocations, events, deferred)
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
         * which workspace seams go together: `bd export`, `bd import`, and
         * (computenet-oagbm) the [uncommittedIssueIds] in-flight guard.
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
            val dolt = DoltSql(doltRootFor(workspaceRoot))
            return WriteBackApplier(
                reader::read,
                bdImport::importRow,
                winner,
                onEvent,
                cnDot,
                expectEcho,
                cancelEcho,
                inFlight = { uncommittedIssueIds(dolt) },
            )
        }

        /**
         * The production [inFlight] query: every issue id whose `issues` row
         * differs between the Dolt HEAD commit and the WORKING set, read with
         * `dolt_diff('HEAD','WORKING','issues')` (both sides' ids, so an
         * uncommitted insert or delete counts too). Measured on bd 1.1.2 /
         * dolt 2.2.3 (computenet-oagbm): an uncommitted write made by one
         * process is visible to another process's `dolt sql` and `bd export`
         * alike, and a workspace with no pending write returns no rows.
         *
         * A failure propagates, exactly as an [export] failure does: guessing
         * "nothing in flight" would re-open the loss this query exists to
         * prevent.
         */
        internal fun uncommittedIssueIds(dolt: DoltSql): Set<String> =
            dolt.query("select from_id, to_id from dolt_diff('HEAD','WORKING','issues')")
                .flatMap { row -> listOf(row["from_id"], row["to_id"]) }
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .toSet()
    }
}
