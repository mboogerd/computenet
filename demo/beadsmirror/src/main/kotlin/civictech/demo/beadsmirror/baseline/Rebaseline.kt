package civictech.demo.beadsmirror.baseline

import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.host.DurableInput
import civictech.demo.beadsmirror.MirrorGraph
import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.projector.Classification
import civictech.demo.beadsmirror.projector.DotMinter

/** Why a full snapshot replaced the mirror rather than an incremental resume. */
sealed interface RebaselineReason {

    /** No journal was recovered, so the new hosted graph needs its first snapshot. */
    data object FirstStart : RebaselineReason

    /** The durable input cursor fell out of `dolt_log` after history compaction. */
    data class CheckpointGone(val checkpoint: String) : RebaselineReason

    /** A merge commit after the cursor made an incremental walk ambiguous. */
    data class HistoryMerged(val mergeCommit: String) : RebaselineReason
}

/** Something the mirror did that an operator or a test needs to observe. */
sealed interface MirrorEvent {

    /** Stable identity of the workspace that produced the event. */
    val workspaceIdentity: String

    /** A snapshot at [headCommit] replaced or initialized the hosted fold. */
    data class Rebaselined(
        val reason: RebaselineReason,
        val headCommit: String,
        val issueCount: Int,
        override val workspaceIdentity: String,
    ) : MirrorEvent

    /** The echo gate's classification of one feed record. */
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
 * Raised when a non-initial snapshot unexpectedly contains no rows.
 *
 * A successful empty export during a history-gap rebuild would otherwise
 * replace the recovered fold with nothing. The guard deliberately covers only
 * the total-loss case; deciding whether a smaller non-empty export is valid
 * needs policy this demo does not define. An operator who has intentionally
 * emptied the workspace may either enable the constructor override or remove
 * the mirror's `main/` journal before starting again.
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
 * Applies a `bd export` snapshot to the hosted mirror and atomically commits
 * its feed cursor through one [DurableInput] event.
 *
 * Head is captured before export so a concurrent commit is, at worst, folded
 * once by the snapshot and once by the subsequent incremental read. Reversing
 * those reads could checkpoint content absent from the snapshot and skip it
 * forever.
 *
 * First start uses the graph Runtime already spawned. A history-gap rebuild
 * applies one [GraphSpec] that despawns and respawns both cells under the same
 * exact refs, then captures the fresh hosted handles. Applying the snapshot
 * and returning the captured head from one durable input commit makes the
 * baseline batch and cursor one journaled `RECORD_INPUT`. Host quiescence is
 * fenced before the observable event is emitted.
 *
 * History-gap calls run synchronously inside [DoltFeedPoller.pollOnce], so no
 * later batch can reach the old projector while replacement is in progress.
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

    /** Replaces or initializes the fold and reports the completed snapshot. */
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
            graph.projector(DotMinter(workspaceIdentity), applied).also(state::swap)
        }
        input().commit {
            target.applyAll(records)
            headCommit
        }
        graph.host.quiescence().await(30_000, "beadsmirror $workspaceIdentity rebaseline")
        onEvent(MirrorEvent.Rebaselined(reason, headCommit, rows.size, workspaceIdentity))
    }
}
