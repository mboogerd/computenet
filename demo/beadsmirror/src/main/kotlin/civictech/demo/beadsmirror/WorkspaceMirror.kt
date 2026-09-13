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
import civictech.demo.beadsmirror.writeback.WriteBackApplier
import civictech.demo.beadsmirror.writeback.WriteBackEvent
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread

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
    /**
     * This workspace's write-back applier, or `null` when write-back is off
     * (the default) — task computenet-6wc.1.5. Exposed so a test can read
     * [WriteBackApplier]'s last
     * [civictech.demo.beadsmirror.writeback.ApplyReport] without re-deriving
     * the wiring.
     *
     * **Not run on the poll thread — see [WriteBackScheduler]'s KDoc for the
     * measured reason the bead's originally decided "inside `onBatch`"
     * composition cannot satisfy this feature's own R1 example.** It is
     * scheduled on its own dedicated single thread ([writeBackScheduler]),
     * started and stopped alongside the poll loop.
     */
    val writeBackApplier: WriteBackApplier?,
    private val writeBackScheduler: WriteBackScheduler?,
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

    /**
     * `null` while write-back is off, or on and healthy; set if
     * [WriteBackScheduler]'s own loop died on an uncaught exception from
     * `applyOnce` — the write-back analogue of [pollerFailure]. An applier
     * that cannot run at all is a dead loop, not a swallowed error, exactly
     * as the bead's design decided for the poll-thread case; this is that
     * same decision, carried over to the thread it actually runs on.
     */
    val writeBackFailure: Throwable? get() = writeBackScheduler?.failure

    /** Starts this workspace's poll loop, and its write-back scheduler if write-back is on. Call once, after the shell is up. */
    fun startPolling() {
        poller.start()
        writeBackScheduler?.start()
    }

    /** Stops this workspace's poll loop and write-back scheduler (joining both threads) and closes its peering, if any. */
    fun stop() {
        poller.stop()
        writeBackScheduler?.stop()
        peering?.close()
    }

    override fun close() = stop()

    /**
     * Write-back's own scheduler (task computenet-6wc.1.5): runs [applier]'s
     * `applyOnce()` once per [interval], on its own single daemon thread,
     * started and stopped alongside — but independent of — [DoltFeedPoller].
     *
     * **Why this exists instead of composing inside `onBatch`, as the bead's
     * Design section decided.** [DoltFeedPoller.pollOnce] calls `onBatch`
     * ONLY when this workspace's OWN feed produced new records
     * (`if (records.isEmpty()) return`, before `onBatch`). A write-back-enabled
     * workspace's fold, though, changes on GOSSIP ALONE in two-node mode — a
     * peer's edit arrives as a `TaggedMapDelta` straight into the live
     * [MirrorProjector]'s cell over `:wire`, never touching this workspace's
     * own `bd`/Dolt data — so `onBatch` never fires for it at all. Composing
     * `applyOnce` inside `onBatch`, as originally decided, therefore NEVER
     * re-evaluates a peer-only winner change: measured directly by running
     * `WriteBackTwoNodeTest`'s R1 case against that composition — the dialer
     * received the listener's edit on its served fold immediately (gossip is
     * unconditional), while `dialer.writeBackEvents()` stayed the empty list
     * for the full 30s await bound, because its poller's own feed never
     * produced a batch to hang `applyOnce` off of. Feature computenet-6wc.1's
     * clause 1 (`bd show` on the dialer must actually report the imposed
     * value) is unsatisfiable under the original composition for exactly the
     * two-node scenario the bead's own R1 example names, so this scheduler
     * replaces it. Reported on the bead's own thread (task computenet-6wc.1.5)
     * as the divergence this is.
     *
     * **What survives from the original decision.** [applier] still has
     * exactly one caller at a time — this scheduler's own thread never runs
     * two ticks concurrently with itself, and nothing else calls `applyOnce`
     * — so clause 5's "one writer of `bd import`" property holds; only the
     * THREAD it runs on changed, from the shared poll thread to a dedicated
     * one. [MirrorState.current] is read the same way an HTTP handler thread
     * already does (`@Volatile`, see that class's KDoc), so a concurrent read
     * here is nothing a live server did not already have to tolerate.
     */
    private class WriteBackScheduler(
        private val applier: WriteBackApplier,
        private val interval: Duration,
    ) : AutoCloseable {

        /** `null` until this scheduler's loop dies on an uncaught exception from `applyOnce`. */
        @Volatile
        var failure: Throwable? = null
            private set

        private var runnerThread: Thread? = null

        fun start() {
            check(runnerThread == null) { "already started" }
            runnerThread = thread(name = "beadsmirror-writeback", isDaemon = true, start = false) {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        applier.applyOnce()
                        Thread.sleep(interval.toMillis())
                    }
                } catch (_: InterruptedException) {
                    // stop() requested — exit quietly, this is not a failure.
                } catch (t: Throwable) {
                    failure = t
                }
            }.apply { start() }
        }

        fun stop() {
            runnerThread?.interrupt()
            runnerThread?.join()
            runnerThread = null
        }

        override fun close() = stop()
    }

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
            /**
             * Opt-in (task computenet-6wc.1.5): when true, this workspace runs
             * a [WriteBackApplier] over its own `bd export`/`bd import`, one
             * `applyOnce` pass per poll batch. `false` — the default — is
             * exactly the mirror that existed before this parameter did: no
             * applier is constructed, no `bd import` is ever invoked, and
             * [writeBackApplier] reads `null`.
             */
            writeBack: Boolean = false,
            /**
             * Where this workspace's [WriteBackEvent]s go — deliberately not a
             * [MirrorEvent]; see [WriteBackEvent]'s KDoc for why the two
             * vocabularies stay separate. Ignored when [writeBack] is false.
             */
            onWriteBackEvent: (String, WriteBackEvent) -> Unit = ::printWriteBackEvent,
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

            // Each mirror writes only to its OWN workspace — that falls out of
            // this being constructed per-[WorkspaceMirror] rather than a
            // process-wide singleton (feature computenet-6wc.1's non-goal: no
            // multi-workspace write-back policy is needed beyond this).
            val writeBackApplier = if (writeBack) {
                WriteBackApplier.forWorkspace(
                    workspaceRoot = workspace,
                    winner = { state.current.view() },
                    onEvent = { event -> onWriteBackEvent(identity, event) },
                )
            } else {
                null
            }
            val writeBackScheduler = writeBackApplier?.let { WriteBackScheduler(it, pollInterval) }

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
                // Write-back's applyOnce does NOT run here — see
                // [WriteBackScheduler]'s KDoc for the measured reason a
                // purely onBatch-driven composition cannot observe a
                // gossip-only winner change at all (task computenet-6wc.1.5).
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

            return WorkspaceMirror(
                identity,
                workspace,
                runDir,
                minter,
                state,
                poller,
                peering,
                writeBackApplier,
                writeBackScheduler,
            )
        }
    }
}
