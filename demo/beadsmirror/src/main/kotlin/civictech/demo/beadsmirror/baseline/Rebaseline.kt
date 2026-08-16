package civictech.demo.beadsmirror.baseline

import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCheckpoint
import civictech.demo.beadsmirror.feed.FeedCondition
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector

/**
 * Why a re-baseline ran — feature computenet-dqj.3 rule 5's "distinguishable
 * from normal resume" carried as data rather than as log prose.
 */
sealed interface RebaselineReason {

    /** No checkpoint was persisted yet: the mirror is starting against a workspace for the first time. */
    data object FirstStart : RebaselineReason

    /**
     * The mirror is starting against a workspace it has mirrored before —
     * [checkpoint] was persisted by a previous run — and rebuilds from `bd
     * export` anyway (feature computenet-dqj.5 design amendment 2).
     *
     * **Why a restart cannot simply resume.** Nothing persists the projector:
     * a new process starts with an *empty* [MirrorProjector], so resuming the
     * feed strictly after [checkpoint] would replay only the commits made
     * while the mirror was down and leave every pre-checkpoint issue absent
     * forever. The checkpoint still earns its keep *while running* — it is
     * what makes incremental resume and [CheckpointGone] truncation detection
     * work — it just cannot stand in for state the process never kept.
     */
    data class Restart(val checkpoint: String) : RebaselineReason

    /**
     * The persisted [checkpoint] fell out of `dolt_log` (history compaction),
     * so the feed could not be resumed and [FeedCondition.CheckpointGone] was
     * raised.
     */
    data class CheckpointGone(val checkpoint: String) : RebaselineReason
}

/**
 * Something the mirror did that an operator or a test needs to observe, as a
 * typed value.
 *
 * Sealed so a consumer's `when` stays exhaustive as more outcomes are added;
 * today re-baselining is the only one — an ordinary incremental resume is
 * deliberately *not* an event, because the whole point of rule 5 is that a
 * rebuild is distinguishable from a resume, and a resume that also emitted an
 * event would blur exactly that line.
 */
sealed interface MirrorEvent {

    /**
     * The mirror rebuilt its whole state from `bd export` at [headCommit],
     * which is now also its persisted checkpoint, holding [issueCount] issues.
     */
    data class Rebaselined(
        val reason: RebaselineReason,
        val headCommit: String,
        val issueCount: Int,
    ) : MirrorEvent
}

/**
 * The re-baseline operation (computenet-dqj.3.3): rebuild the mirror's whole
 * state from a `bd export` snapshot and hand the feed a checkpoint it can
 * resume from.
 *
 * One [run] does exactly five things, and their order is load-bearing:
 *
 * 1. Capture the workspace's head commit and height ([BaselineBuilder.captureHead]).
 * 2. `bd export` the workspace ([BdExportReader]).
 *
 *    **Head first, export second** — see [BaselineBuilder.captureHead] for the
 *    concurrent-writer argument. In short (computenet-dqj.10): a commit landing
 *    between the two reads is then *missing from the checkpoint but present in
 *    the snapshot*, so the poller re-folds a commit whose content the baseline
 *    already holds — idempotent, because a replayed record mints at a strictly
 *    greater height than the baseline's and carries the same values, so
 *    last-writer-wins re-decides the same way. The reverse order makes the same
 *    commit *present in the checkpoint and missing from the snapshot*, and that
 *    one is unrecoverable: the poller resumes strictly after it and its content
 *    is never folded at all.
 * 3. Build a **fresh** [MirrorProjector] from the export ([BaselineBuilder.build]),
 *    under a fresh [DotMinter] of the *same* [workspaceIdentity]. Same identity,
 *    because the dot source must not change across a restart or a rebuild;
 *    fresh cells, because — see [MirrorState] — post-compaction commit heights
 *    restart lower than pre-gap ones, so folding baseline deltas into the old
 *    cell would mint dots sorting *below* tombstones and puts it already holds
 *    and lose last-writer-wins to dead state. The swap is correctness, not
 *    hygiene.
 * 4. Swap the fresh projector into [state].
 * 5. **Then** persist [headCommit] as the checkpoint.
 *
 * **Why 4 before 5.** A crash between them leaves a swapped-in projector and
 * the *old* checkpoint, so the next start re-runs the baseline — idempotent,
 * because the rebuild is a pure function of (export, head) and the previous
 * attempt's projector is discarded anyway. The reverse order leaves the new
 * checkpoint over the *old* projector, and the poller would then resume the
 * feed from post-baseline commits on top of pre-gap state, which is the one
 * outcome that cannot be repaired by re-running anything.
 *
 * **Threading: none needed.** On [RebaselineReason.CheckpointGone] this runs
 * synchronously inside [DoltFeedPoller.pollOnce]'s `onCondition` call, on the
 * poller's own thread, and `pollOnce` returns immediately afterwards having
 * emitted nothing for that tick — so no post-gap record can reach a projector
 * before the rebuild completes (feature rule 1). On
 * the two start-time reasons ([RebaselineReason.FirstStart] and
 * [RebaselineReason.Restart]) it runs before `DoltFeedPoller.start`, so the
 * poller thread does not exist yet. The poller thread is therefore the only
 * writer of [MirrorState.current] and the only caller of `applyAll`; HTTP
 * readers see either the old or the new projector through the volatile. No
 * lock is taken, and adding one would only hide that argument.
 *
 * @param export produces the snapshot rows. In the app this is
 *   [BdExportReader.read] against the mirrored workspace; it is a function
 *   rather than a [BdExportReader] for the same reason [BdExportReader.parse]
 *   exists — so this orchestration's ordering and swap rules are testable on
 *   hand-built rows, with no `bd` on PATH.
 * @param workspaceIdentity the stable [DotMinter] source identity for this
 *   workspace — the same value the app's original projector was built with.
 */
class Rebaseline(
    private val export: () -> List<ExportRow>,
    private val feed: DoltCommitFeed,
    private val checkpoint: FeedCheckpoint,
    private val state: MirrorState,
    private val workspaceIdentity: String,
    private val onEvent: (MirrorEvent) -> Unit,
) {

    /**
     * Runs the operation and reports it as [MirrorEvent.Rebaselined] with
     * [reason].
     *
     * Nothing is caught here: a failed export or an empty `dolt_log` leaves
     * the previous state and the previous checkpoint untouched and propagates
     * — on the [RebaselineReason.CheckpointGone] path out of `pollOnce` into
     * `DoltFeedPoller.failure`, on the start-time paths
     * ([RebaselineReason.FirstStart], [RebaselineReason.Restart]) out of
     * `BeadsMirrorApp.start`. Half-rebuilding on a broken export would be
     * strictly worse than not starting.
     *
     * Note what that means for a [RebaselineReason.Restart]: a `bd export`
     * that fails now aborts a start that, before design amendment 2, would
     * have come up on the persisted checkpoint. That is the intended trade —
     * a mirror that cannot rebuild its pre-checkpoint state would serve a
     * silently incomplete fold — but it is a real availability change, not
     * only a completeness one.
     */
    fun run(reason: RebaselineReason) {
        val (headCommit, headHeight) = BaselineBuilder.captureHead(feed)
        val rows = export()
        val rebuilt = BaselineBuilder(DotMinter(workspaceIdentity)).build(rows, headCommit, headHeight)

        state.swap(rebuilt)
        checkpoint.write(headCommit)

        onEvent(MirrorEvent.Rebaselined(reason, headCommit, rows.size))
    }
}
