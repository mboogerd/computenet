package civictech.demo.beadsmirror.baseline

import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.host.DurableInput
import civictech.demo.beadsmirror.MirrorGraph
import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCondition
import civictech.demo.beadsmirror.projector.Classification
import civictech.demo.beadsmirror.projector.DotMinter

/** Why a full snapshot replaced the mirror rather than an incremental resume. */
sealed interface RebaselineReason {

    /** No journal was recovered, so the new hosted graph needs its first snapshot. */
    data object FirstStart : RebaselineReason

    /** The durable input cursor fell out of `dolt_log` after history compaction. */
    data class CheckpointGone(val checkpoint: String) : RebaselineReason

    /**
     * A `bd dolt pull` merged a peer's history into the mirrored workspace:
     * [mergeCommit] lies strictly after the feed's checkpoint and has two or
     * more parents, so [FeedCondition.HistoryMerged] was raised and the
     * incremental walk refused (epic computenet-7em §2 bullet 4; task
     * computenet-7em.4.1).
     *
     * Runs on the poller thread, exactly like [CheckpointGone], and is subject
     * to the same [EmptyExportRefused] guard — the guard keys on `reason !is
     * FirstStart`, and a merged workspace is emphatically not a first start,
     * so a zero-row export here still refuses rather than replacing a
     * populated fold with nothing.
     */
    data class HistoryMerged(val mergeCommit: String) : RebaselineReason
}

/**
 * Something the mirror did that an operator or a test needs to observe, as a
 * typed value.
 *
 * Sealed so a consumer's `when` stays exhaustive as more outcomes are added.
 * Today there are three — a re-baseline ([Rebaselined]), a dead poll loop
 * ([PollLoopDied]) and one echo classification per feed record
 * ([RecordClassified]). An ordinary incremental resume is deliberately *not* an
 * event, because the whole point of rule 5 is that a rebuild is distinguishable
 * from a resume, and a resume that also emitted an event would blur exactly
 * that line.
 *
 * **Every event names the workspace it came from** ([workspaceIdentity], task
 * computenet-3bso.1.1). One process now hosts N workspace mirrors sharing one
 * `BeadsMirrorConfig.onEvent`
 * ([civictech.demo.beadsmirror.WorkspaceMirror]), so an event that did not say
 * which workspace produced it would be unattributable the moment N > 1 — and
 * "which mirror froze" is exactly what the surviving siblings' operator needs.
 * It is a member of the *interface*, not of one implementation, so no future
 * [MirrorEvent] can be added without an attribution; and it is a machine-
 * readable field rather than a substring of a printed line, so a test asserts
 * it instead of parsing prose.
 */
sealed interface MirrorEvent {

    /**
     * Which workspace produced this event: the sanitized identity from
     * [civictech.demo.beadsmirror.sanitizedDoltDatabaseName], which is also the
     * [DotMinter] source identity of the projector the event concerns and the
     * per-workspace key of the coordinator that hosts it. A pure function of
     * the workspace path, so it is stable across restarts.
     */
    val workspaceIdentity: String

    /** A snapshot at [headCommit] replaced or initialized the hosted fold. */
    data class Rebaselined(
        val reason: RebaselineReason,
        val headCommit: String,
        val issueCount: Int,
        override val workspaceIdentity: String,
    ) : MirrorEvent

    /**
     * One feed record was classified by the mirror's
     * [civictech.demo.beadsmirror.projector.EchoGate] (feature
     * computenet-6wc.3 clause 5, decision 6wc.3-D6) — emitted for **every**
     * record, echo and local alike, because a suppression nobody can observe
     * is a suppression nobody can debug.
     *
     * [cnDot] and [cnEcho] are the record's `metadata` provenance as it
     * arrived, whether or not it took part in the decision: the classification
     * turns on `cn_echo`'s two diff sides plus a pending expectation, so
     * reporting the raw values is what makes a misclassification readable
     * without re-querying Dolt. Both are `null` on an ordinary local edit that
     * never went through the applier.
     *
     * This is the module's highest-volume event by far — one per record, not
     * one per anomaly — which is why it carries no derived state and does no
     * work beyond naming what it saw.
     */
    data class RecordClassified(
        val commitHash: String,
        val issueId: String,
        val classification: Classification,
        val cnDot: String?,
        val cnEcho: String?,
        override val workspaceIdentity: String,
    ) : MirrorEvent
}

/**
 * Raised by [Rebaseline.run] when `bd export` succeeds with zero rows for a
 * workspace that has already been baselined (any reason except
 * [RebaselineReason.FirstStart]). The exception is raised before the graph
 * replacement and durable input commit, leaving the current fold and cursor
 * untouched.
 *
 * ## The measured hazard
 *
 * A zero-row, exit-zero export is a real `bd` outcome. Measured paths include
 * a genuinely empty workspace, and a populated workspace exported while
 * `BEADS_DIR` points at a different empty workspace. The latter also occurs
 * under `--sandbox`: with cwd in the populated workspace and `BEADS_DIR` set
 * to an empty one, `bd --sandbox export` succeeds with zero rows while
 * [DoltCommitFeed] continues reading the database selected by workspace path.
 * If accepted during a later baseline, the empty snapshot could replace the
 * populated fold and checkpoint that empty state. A failed export does not
 * produce this condition; its exception propagates from [Rebaseline.run].
 *
 * ## What this guard does not protect against
 *
 * - **A partial export.** Any non-empty row set passes this zero-row check.
 * - **A wrong-but-full export.** The guard does not validate exported content.
 * - **A false refusal.** A workspace intentionally emptied after a prior
 *   baseline is also refused; use `acceptEmptyExport = true` when that is
 *   intended.
 *
 * Empty exports are accepted on a genuine first start. This guard concerns
 * the snapshot's row count only; it makes no claim that all durable state is
 * absent across process restarts.
 *
 * @param reason the reason for the re-baseline that was refused.
 * @param foldSize the number of issues currently visible in the fold.
 */
class EmptyExportRefused(
    val reason: RebaselineReason,
    val foldSize: Int,
) : RuntimeException(
    "refusing to re-baseline onto an empty `bd export`: the export succeeded and yielded 0 issues, " +
        "but this workspace has been baselined before ($reason), so replacing the fold with nothing " +
        "would discard every mirrored issue and checkpoint the empty state as current. " +
        "In-memory fold at refusal: $foldSize issue(s)" +
        ". The previous fold and the previous checkpoint are untouched. " +
        "If the workspace really is empty, delete the run directory's journal (`main/`; the next " +
        "start is then a first start and accepts it), or construct Rebaseline with " +
        "acceptEmptyExport = true.",
)

/**
 * Applies a `bd export` snapshot to the hosted mirror and commits its feed
 * cursor through one [DurableInput] event.
 *
 * The feed head is captured before export. A concurrent commit can then be
 * folded by both the snapshot and the next incremental read, while reversing
 * the order could checkpoint content missing from the snapshot.
 *
 * First start applies records to the graph already spawned. A history-gap
 * rebuild applies one [GraphSpec] that despawns and respawns both cells under
 * the same refs, then applies the snapshot and cursor in one journaled input.
 * Host quiescence completes before the re-baseline event is emitted.
 *
 * After startup, history-gap re-baselines triggered from
 * [DoltFeedPoller.pollOnce] run synchronously on its poller thread. That
 * thread is the sole writer of [MirrorState.current] and sole caller of
 * `applyAll`; first-start initialization applies its baseline before the
 * poller starts. HTTP readers access the projector through the volatile
 * [MirrorState.current] reference and observe the old or new projector. No
 * lock is needed for this handoff.
 */
class Rebaseline(
    private val export: () -> List<ExportRow>,
    private val feed: DoltCommitFeed,
    private val graph: MirrorGraph,
    private val state: MirrorState,
    private val input: () -> DurableInput,
    private val workspaceIdentity: String,
    private val onEvent: (MirrorEvent) -> Unit,
    private val acceptEmptyExport: Boolean = false,
) {

    /**
     * Replaces or initializes the fold and reports the completed snapshot.
     * Nothing is caught here: export failures and an empty `dolt_log` from
     * [BaselineBuilder.captureHead] propagate before the graph replacement,
     * leaving the fold and durable cursor untouched. A zero-row export on any
     * non-first-start reason raises [EmptyExportRefused] before replacement
     * unless [acceptEmptyExport] is enabled. Successful baselines apply the
     * snapshot and head cursor in one durable input commit.
     */
    fun run(reason: RebaselineReason) {
        val (headCommit, headHeight) = BaselineBuilder.captureHead(feed)
        val rows = export()
        if (rows.isEmpty() && reason !is RebaselineReason.FirstStart && !acceptEmptyExport) {
            throw EmptyExportRefused(reason, foldSize = state.current.view().size)
        }
        val records = BaselineBuilder(DotMinter(workspaceIdentity)).records(rows, headCommit, headHeight)
        val target = if (reason is RebaselineReason.FirstStart) {
            state.current
        } else {
            val applied = graph.apply(
                GraphSpec(
                    listOf(
                        DespawnStep(MirrorGraph.MAP_HANDLE),
                        DespawnStep(MirrorGraph.EDGES_HANDLE),
                    ) + graph.spec().steps,
                ),
            )
            graph.projector(DotMinter(workspaceIdentity), applied)
        }
        input().commit {
            target.applyAll(records)
            headCommit
        }
        graph.host.quiescence().await(30_000, "beadsmirror $workspaceIdentity rebaseline")
        if (target !== state.current) state.swap(target)
        onEvent(MirrorEvent.Rebaselined(reason, headCommit, rows.size, workspaceIdentity))
    }
}
