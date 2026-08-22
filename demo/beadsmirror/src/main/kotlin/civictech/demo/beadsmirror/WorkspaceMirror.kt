package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.PollLoopDied
import civictech.demo.beadsmirror.baseline.Rebaseline
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCheckpoint
import civictech.demo.beadsmirror.feed.FeedCondition
import civictech.demo.beadsmirror.feed.PollLoopStopped
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import java.nio.file.Path
import java.time.Duration

/**
 * One workspace's whole mirror: its own [DoltCommitFeed], [FeedCheckpoint],
 * [DotMinter] identity, [MirrorProjector]/[MirrorState], [Rebaseline] and
 * [DoltFeedPoller] — everything [BeadsMirrorApp] used to hold singly (task
 * computenet-3bso.1.1, feature computenet-3bso.1).
 *
 * **Why this class exists.** Feature computenet-3bso.1 needs one process to
 * hold live folds for N workspaces so a later cross-workspace join
 * (computenet-3bso.2/.3) can read across them. Everything above was wired
 * inline in `BeadsMirrorApp.start` against a single
 * [BeadsMirrorConfig.workspace]; extracting it whole is what makes "one of
 * each, per workspace" a structural property rather than a convention. Nothing
 * is shared between two instances: not the poller thread, not the checkpoint
 * file, not the projector, not the dot source. The only thing N mirrors share
 * is the process, its [civictech.demo.shell.DemoShell], and the
 * [BeadsMirrorConfig.onEvent] sink — which is why every [MirrorEvent] this
 * class delivers carries [identity] (see [MirrorEvent.workspaceIdentity]).
 *
 * **Failure isolation is therefore structural, not policy.** Each instance's
 * [DoltFeedPoller] runs its own daemon thread, so a tick that throws kills
 * exactly one loop; the siblings never learn of it and go on polling. The dead
 * one is readable at [pollLoopStopped], per workspace, and reported once as a
 * [PollLoopDied] naming [identity].
 *
 * **Lifecycle, in three parts, because the order is load-bearing.** [start]
 * builds the components, runs the start-time re-baseline and connects peering;
 * [startPolling] then starts the poller thread — deliberately separate, so the
 * coordinator can open the shared HTTP socket between the two exactly as
 * `BeadsMirrorApp.start` did when there was one workspace (baseline before the
 * socket, socket before the poller). [stop] stops the poller and closes
 * peering; the shell belongs to the coordinator and is not touched here.
 */
class WorkspaceMirror private constructor(
    /**
     * The sanitized workspace identity — [sanitizedDoltDatabaseName] of
     * [workspace]. Doubles as this mirror's [DotMinter] source identity, the
     * attribution on every [MirrorEvent] it emits, and the coordinator's key
     * for it. Two configured workspaces sharing one identity are refused at
     * startup by [BeadsMirrorApp] ([DuplicateWorkspaceIdentityException]),
     * which is what lets the rest of this module treat it as a key.
     */
    val identity: String,
    /** The bd workspace root this mirror reads. */
    val workspace: Path,
    /** The run directory holding this mirror's own [FeedCheckpoint]. */
    val runDir: Path,
    /**
     * The dot minter this workspace's first projector was built with — the one
     * object, not a re-derivation. Exposed so a test can assert that N mirrors
     * mint from N *distinct* [DotMinter.sourceId]s rather than trusting that
     * [start] passed the right identity: two workspaces sharing a dot source
     * would interleave their last-writer-wins orderings, which is the hazard
     * [BeadsMirrorApp]'s identity-collision refusal exists to prevent.
     *
     * A re-baseline builds a *fresh* [DotMinter] of the same identity (see
     * [Rebaseline]), so this instance is not necessarily the one currently
     * minting — its [DotMinter.workspaceIdentity] and [DotMinter.sourceId] are
     * invariant across those rebuilds, which is the property being asserted.
     */
    val minter: DotMinter,
    /** This workspace's live projector handle, swapped wholesale by a re-baseline. */
    val state: MirrorState,
    private val poller: DoltFeedPoller,
    /**
     * The two-node replica mesh, or `null` in solo mode. Only ever non-null in
     * a single-workspace process — [BeadsMirrorApp] refuses peering with N > 1
     * (design decision 3bso.1-D3; see its KDoc).
     */
    val peering: MirrorPeering?,
) : AutoCloseable {

    /**
     * This workspace's poller's terminal state — `null` while its feed is
     * live. Read per workspace precisely so that one frozen fold is
     * distinguishable from a frozen process: this is the value
     * [civictech.demo.beadsmirror.http.MirrorRoutes] consumes as
     * `pollLoopStopped`, and the value a sibling mirror answers `null` to
     * while this one answers non-null.
     */
    val pollLoopStopped: PollLoopStopped? get() = poller.stopped

    /** The throwable half of [pollLoopStopped]; `null` while this loop has not failed. */
    val pollerFailure: Throwable? get() = poller.failure

    /** Starts this workspace's poll loop on its own thread. Call once, after the shell is up. */
    fun startPolling() = poller.start()

    /** Stops this workspace's poll loop (joining its thread) and closes its peering, if any. */
    fun stop() {
        poller.stop()
        peering?.close()
    }

    override fun close() = stop()

    companion object {

        /**
         * Builds every component for [workspace] and runs its start-time
         * re-baseline, returning a mirror whose poll loop has **not** started
         * yet ([startPolling] does that).
         *
         * The body is `BeadsMirrorApp.start`'s original per-workspace wiring,
         * moved verbatim except for the identity now threaded onto
         * [PollLoopDied]: same construction order (peering before the projector,
         * so the replication registry's hooks precede every announcement), same
         * baseline-before-socket rule, same three re-baseline reasons. See
         * [BeadsMirrorApp.Companion.start]'s KDoc for why each of those is
         * ordered as it is — this class did not re-decide any of them.
         *
         * @param onEvent the process-wide sink. Every event handed to it from
         *   here carries [MirrorEvent.workspaceIdentity], so N mirrors can share
         *   one sink without their reports becoming unattributable.
         */
        fun start(
            workspace: Path,
            runDir: Path,
            pollInterval: Duration,
            onEvent: (MirrorEvent) -> Unit,
            peeringSettings: MirrorPeeringSettings? = null,
            peeringTransport: MirrorTransport? = null,
        ): WorkspaceMirror {
            val doltRoot = doltRootFor(workspace)
            val identity = sanitizedDoltDatabaseName(workspace)

            val feed = DoltCommitFeed(doltRoot)
            val checkpoint = FeedCheckpoint(runDir)

            // Two-node mode, and NOTHING of it in solo mode: with no peering
            // settings this stays null, `refs` stays null, the projector keeps
            // its random-ref default, MirrorState keeps its no-op swap hook,
            // and no `:wire`/replication class is loaded. Constructed before
            // the projector because Replication's registry hooks must precede
            // every announcement (see [MirrorPeering]).
            val peering = peeringSettings?.let { MirrorPeering(it, peeringTransport ?: WsMirrorTransport()) }
            val refs = peering?.refs

            val minter = DotMinter(identity)
            val initial = if (refs != null) MirrorProjector(minter, refs) else MirrorProjector(minter)
            val state = MirrorState(initial, onSwap = { next -> peering?.rebind(next) })
            peering?.attach(initial)

            val rebaseline = Rebaseline(
                export = BdExportReader(workspace)::read,
                feed = feed,
                checkpoint = checkpoint,
                state = state,
                workspaceIdentity = identity,
                onEvent = onEvent,
                refs = refs,
            )

            val poller = DoltFeedPoller(
                feed = feed,
                checkpoint = checkpoint,
                interval = pollInterval,
                // Re-read the handle per batch: a re-baseline earlier in this
                // very tick may have replaced the projector.
                onBatch = { records -> state.current.applyAll(records) },
                onCondition = { condition ->
                    when (condition) {
                        is FeedCondition.CheckpointGone ->
                            rebaseline.run(RebaselineReason.CheckpointGone(condition.checkpoint))
                        // A `bd dolt pull` merged peer history in. Same
                        // synchronous-on-the-poller-thread path: the tick that
                        // detected it emitted nothing, and returns straight
                        // after this call, so no record derived from merged
                        // history can reach a projector.
                        is FeedCondition.HistoryMerged ->
                            rebaseline.run(RebaselineReason.HistoryMerged(condition.mergeCommit))
                    }
                },
                // computenet-dqj.12: the loop dying is the one thing this
                // process cannot keep to itself. It reports through the same
                // channel as every other MirrorEvent, so an operator who wired
                // up `onEvent` at all hears it without wiring anything else,
                // and the default handler prints it. computenet-3bso.1.1 adds
                // the identity: with N loops on N threads, "a loop died" is
                // only actionable if it says which.
                onStopped = { onEvent(PollLoopDied(it.failure, it.checkpoint, identity)) },
            )

            // Before the socket: a start-time baseline is part of "started", so
            // the very first request is answered from complete state rather than
            // from an empty projector that fills in moments later. It runs on
            // EVERY start, checkpoint or not — see BeadsMirrorApp's class doc.
            val persisted = checkpoint.read()
            rebaseline.run(
                if (persisted == null) RebaselineReason.FirstStart else RebaselineReason.Restart(persisted),
            )

            // After the start-time baseline has swapped its projector in and
            // `rebind` has re-pointed the mesh — so the peer's first
            // announcement lands on cells that are already the live ones.
            peering?.connect()

            return WorkspaceMirror(identity, workspace, runDir, minter, state, poller, peering)
        }
    }
}
