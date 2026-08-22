package civictech.demo.beadsmirror.baseline

import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCheckpoint
import civictech.demo.beadsmirror.feed.FeedCondition
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorCellRefs
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
 * Sealed so a consumer's `when` stays exhaustive as more outcomes are added;
 * today re-baselining is the only one — an ordinary incremental resume is
 * deliberately *not* an event, because the whole point of rule 5 is that a
 * rebuild is distinguishable from a resume, and a resume that also emitted an
 * event would blur exactly that line.
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

    /**
     * The mirror rebuilt its whole state from `bd export` at [headCommit],
     * which is now also its persisted checkpoint, holding [issueCount] issues.
     */
    data class Rebaselined(
        val reason: RebaselineReason,
        val headCommit: String,
        val issueCount: Int,
        override val workspaceIdentity: String,
    ) : MirrorEvent
}

/**
 * Raised by [Rebaseline.run] when `bd export` **succeeds and yields zero
 * rows** on a workspace this mirror has baselined before — i.e. on any
 * [RebaselineReason] other than [RebaselineReason.FirstStart].
 *
 * ## The hazard (computenet-dqj.13)
 *
 * Every start rebuilds the whole mirror from `bd export` (see
 * [RebaselineReason.Restart]), so a single successful-but-empty export is
 * sufficient to replace the entire fold and checkpoint the empty state as
 * current — no failure, no warning, and the HTTP surface then answers `200`
 * with nothing in it. A *failing* export already aborts the start; this is the
 * successful-and-empty case, which did not.
 *
 * Verified 2026-08-16 on a scratch workspace: `bd --sandbox export` on a
 * freshly-initialised workspace exits `0` and writes zero rows, so the shape
 * is real. (The epic reviewer's trigger for it — removing
 * `.beads/config.yaml` — did **not** reproduce on this `bd` build: export
 * still returned every issue. The trigger is therefore unconfirmed; the
 * zero-row-exit-zero *shape* is not.)
 *
 * Measured during this task's review, 2026-08-16, three real paths to a
 * zero-row-exit-zero export on a workspace that was **not** always empty —
 * two benign, one not:
 *
 * - `bd delete`-ing every issue. The tracker really is empty; a restart is
 *   then a *false* refusal (see below).
 * - Wiping `.beads` and re-running `bd --sandbox init` at the same path. Again
 *   genuinely empty — and the Dolt database is a new one, so the old fold was
 *   not the workspace's state either way.
 * - **`BEADS_DIR` set in the mirror process's environment, pointing at a
 *   different (empty) bd workspace.** `--sandbox` does not stop it: with cwd a
 *   populated workspace and `BEADS_DIR` at an empty one, `bd --sandbox export`
 *   exited `0` with zero rows. [DoltCommitFeed] resolves its database *by
 *   path*, not through `BEADS_DIR`, so in that configuration the export and
 *   the feed disagree and the export is simply wrong. This is the case the
 *   guard exists for: an operator-environment mistake, not a `bd` failure.
 *
 * And the replacement itself was observed, not inferred: with the guard below
 * removed, a real zero-row `bd export` swapped an empty projector over a
 * populated fold, advanced the checkpoint to the new head and emitted
 * [MirrorEvent.Rebaselined] with `issueCount = 0` — the whole hazard, silently.
 *
 * ## Why the rule is "zero rows and not a first start", and nothing cleverer
 *
 * The obvious guard — refuse a re-baseline that is *materially smaller* than
 * what the mirror already held — cannot be written here, and it is worth being
 * blunt about why rather than approximating it. **The mirror keeps no state
 * across processes.** At a start-time re-baseline the previous fold is gone:
 * [BeadsMirrorApp][civictech.demo.beadsmirror.BeadsMirrorApp] hands [Rebaseline]
 * a brand-new empty [MirrorProjector], so `state.current` holds zero issues no
 * matter how many the previous run served. The only thing that survives a
 * restart is [FeedCheckpoint]'s file, and it is a **bare commit hash** — it
 * says where the previous run stopped, not how much it held. So the previous
 * size is not merely expensive to obtain, it is *not recorded anywhere*, and
 * any "materially smaller" threshold would be a number compared against
 * nothing. A threshold nobody can justify is worse than an honest narrow rule,
 * so the narrow rule is what this is.
 *
 * `zero rows` is the one magnitude that needs no comparand. `not a first
 * start` is the one discriminator that *is* persisted: a checkpoint file
 * exists iff this workspace has been baselined before, which is exactly
 * [RebaselineReason.FirstStart]'s absence. Together they close the hazard's
 * total-blast-radius case while keeping a genuinely empty tracker's first
 * start working, which is a legitimate state.
 *
 * ## What this does NOT protect against
 *
 * - **A partial export.** 1 row where 598 are expected passes this guard
 *   untouched. Closing that needs the previous fold's size persisted next to
 *   the checkpoint, which is a change to [FeedCheckpoint]'s on-disk format and
 *   is not attempted here.
 * - **A zero-row export on a genuine first start.** Indistinguishable from an
 *   empty tracker by construction, and accepted on purpose.
 * - **A wrong-but-full export.** Right row count, wrong content: invisible here.
 * - **A false refusal.** A workspace that really is empty and has been
 *   mirrored before *will* be refused on restart. That is the deliberate cost
 *   of the rule; it is resolved by either override below, and it is why the
 *   overrides exist rather than being a formality.
 *
 * ## The two overrides
 *
 * - **In code:** construct [Rebaseline] with `acceptEmptyExport = true`.
 * - **By operator, no code:** delete the persisted `checkpoint` file under the
 *   run directory. The next start then reports [RebaselineReason.FirstStart]
 *   and accepts the empty export. This is safe precisely because of design
 *   amendment 2: every start re-baselines from `bd export` and re-captures the
 *   head anyway, so a deleted checkpoint costs nothing — its only cross-process
 *   job is choosing the reason. (Confirmed by reading [FeedCheckpoint] and
 *   [BeadsMirrorApp][civictech.demo.beadsmirror.BeadsMirrorApp]: `read()` is
 *   `null` when the file is absent, that `null` is the *only* input to the
 *   [RebaselineReason.FirstStart]/[RebaselineReason.Restart] choice, and the
 *   checkpoint file is the module's only persisted state at all.)
 *
 * **Both overrides are start-time.** The refusal can also fire on the
 * [RebaselineReason.CheckpointGone] path, which runs on the poller thread of a
 * *running* mirror: there the throw kills the poll loop, so the fold freezes
 * and every route answers `503`
 * ([civictech.demo.beadsmirror.http.MirrorRoutes], via `PollLoopDied`) rather
 * than a start aborting. Deleting the checkpoint file does not rescue that
 * process — it takes effect on the next start.
 *
 * This is a demo module, not a production guard; the guard is sized to match.
 *
 * @param reason the re-baseline that was refused.
 * @param foldSize the number of issues in [MirrorState.current] at the moment
 *   of refusal. Meaningful only on the [RebaselineReason.CheckpointGone] path,
 *   where the running mirror's populated projector is still in memory; on
 *   [RebaselineReason.Restart] it is `0` because nothing persisted the previous
 *   run's projector — which is the whole reason the rule cannot compare sizes.
 */
class EmptyExportRefused(
    val reason: RebaselineReason,
    val foldSize: Int,
) : RuntimeException(
    "refusing to re-baseline onto an empty `bd export`: the export succeeded and yielded 0 issues, " +
        "but this workspace has been baselined before ($reason), so replacing the fold with nothing " +
        "would discard every mirrored issue and checkpoint the empty state as current. " +
        "In-memory fold at refusal: $foldSize issue(s)" +
        (
            if (reason is RebaselineReason.Restart) {
                " — 0 on a restart because no projector survives a process, so the previous run's " +
                    "issue count is not recorded anywhere and cannot be named here"
            } else {
                ""
            }
            ) +
        ". The previous fold and the previous checkpoint are untouched. " +
        "If the workspace really is empty, delete the run directory's `checkpoint` file (the next " +
        "start is then a first start and accepts it), or construct Rebaseline with " +
        "acceptEmptyExport = true.",
)

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
 * @param acceptEmptyExport disables the [EmptyExportRefused] guard, letting a
 *   zero-row export re-baseline a previously-mirrored workspace down to
 *   nothing. This is the guard's explicit operator override; see
 *   [EmptyExportRefused] for the other one, and for what the guard does and
 *   does not protect against.
 * @param refs when non-null (task computenet-7em.1.1), every rebuilt
 *   [MirrorProjector] this instance produces is built under [refs]' shared
 *   logical `CellRef`s rather than random ones — so the swap-in projector
 *   reuses the SAME `CellRef`s as the one it replaces, which is what keeps it
 *   the same logical cell across a re-baseline (see [BaselineBuilder.build]).
 *   `null` (the default) preserves this class's exact prior behaviour.
 */
class Rebaseline(
    private val export: () -> List<ExportRow>,
    private val feed: DoltCommitFeed,
    private val checkpoint: FeedCheckpoint,
    private val state: MirrorState,
    private val workspaceIdentity: String,
    private val onEvent: (MirrorEvent) -> Unit,
    private val acceptEmptyExport: Boolean = false,
    private val refs: MirrorCellRefs? = null,
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
     *
     * One thing IS decided here rather than propagated: a zero-row export on
     * any reason other than [RebaselineReason.FirstStart] raises
     * [EmptyExportRefused] before the swap, so the previous fold and the
     * previous checkpoint survive. Read [EmptyExportRefused] before changing
     * this — the rule is deliberately narrower than the hazard.
     */
    fun run(reason: RebaselineReason) {
        val (headCommit, headHeight) = BaselineBuilder.captureHead(feed)
        val rows = export()
        if (rows.isEmpty() && reason !is RebaselineReason.FirstStart && !acceptEmptyExport) {
            throw EmptyExportRefused(reason, foldSize = state.current.view().size)
        }
        val rebuilt = BaselineBuilder(DotMinter(workspaceIdentity)).build(rows, headCommit, headHeight, refs)

        state.swap(rebuilt)
        checkpoint.write(headCommit)

        // [workspaceIdentity] is already this instance's own — the identity it
        // rebuilds under — so the event's attribution cannot drift from the
        // projector it describes.
        onEvent(MirrorEvent.Rebaselined(reason, headCommit, rows.size, workspaceIdentity))
    }
}
