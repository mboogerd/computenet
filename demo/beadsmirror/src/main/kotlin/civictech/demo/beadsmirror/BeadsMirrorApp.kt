package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.PollLoopDied
import civictech.demo.beadsmirror.baseline.Rebaseline
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCheckpoint
import civictech.demo.beadsmirror.feed.FeedCondition
import civictech.demo.beadsmirror.http.MirrorRoutes
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/**
 * computenet-dqj.4.2: the runnable entry point that wires [DoltCommitFeed] ->
 * [MirrorProjector] -> [MirrorRoutes] against a `--workspace` path, and
 * refuses to run against this repository's own live `.beads` (epic
 * computenet-dqj §4 — every read/write in this module targets a throwaway
 * scratch workspace, never the repository's own issue tracker).
 *
 * **The Dolt database root.** A bd workspace's embedded Dolt database lives
 * at `<workspace>/.beads/embeddeddolt/<sanitized-name>/`, where
 * `<sanitized-name>` is the workspace directory's basename with every
 * character that is not a letter, digit or underscore replaced by an
 * underscore (verified against a live scratch workspace by
 * [civictech.demo.beadsmirror.BdScratchWorkspace], which now calls
 * [doltRootFor] rather than duplicating the rule). [sanitizedDoltDatabaseName]
 * doubles as this process's [DotMinter] source identity: it is a pure
 * function of the workspace path, so it is the same across a restart against
 * the same `--workspace` argument — the one property [DotMinter] requires of
 * its `workspaceIdentity`.
 *
 * **N workspaces, one process** (task computenet-3bso.1.1, feature
 * computenet-3bso.1). This class is now a *coordinator*: the per-workspace
 * wiring lives in [WorkspaceMirror], one instance per
 * [BeadsMirrorConfig.workspaces] entry, and what they share is exactly the
 * process, the [DemoShell] and the [BeadsMirrorConfig.onEvent] sink — which is
 * why every [MirrorEvent] names its workspace. A single-workspace run is the
 * same run it always was: one mirror, `--run-dir` used verbatim, the same
 * baseline-then-socket-then-poller order, and [state]/[peering]/[pollerFailure]
 * still reading it directly.
 */
class BeadsMirrorApp private constructor(
    private val shell: DemoShell,
    /**
     * One [WorkspaceMirror] per configured workspace, in configuration order,
     * each with its own feed, checkpoint, baseline, projector, dot identity
     * and poller thread (task computenet-3bso.1.1). Never empty:
     * [BeadsMirrorConfig] refuses a configuration naming no workspace.
     *
     * Keyed reads go through [mirror]; the `single()`-delegating conveniences
     * below ([state], [peering], [pollerFailure]) are the single-workspace
     * shorthand every pre-N caller uses.
     */
    val mirrors: List<WorkspaceMirror>,
) : AutoCloseable {

    /** The port the shell actually bound — meaningful once [start] has run. */
    val boundPort: Int get() = shell.boundPort

    /**
     * The mirror hosting the workspace whose [WorkspaceMirror.identity] is
     * [identity], or `null` if this process hosts no such workspace. Identity
     * is a usable key precisely because [start] refuses a configuration in
     * which two workspaces share one ([DuplicateWorkspaceIdentityException]).
     */
    fun mirror(identity: String): WorkspaceMirror? = mirrors.firstOrNull { it.identity == identity }

    /**
     * The live projector handle, swapped wholesale by a re-baseline. Exposed
     * so a test can read [MirrorProjector.view]/`edgeView` and
     * [MirrorState.rebaselineCount] directly instead of only through HTTP.
     *
     * **Single-workspace shorthand.** Throws when this process hosts more than
     * one workspace, because there is then no such thing as "the" fold — read
     * [mirrors] or [mirror] instead. Deliberately not a silent "first one":
     * every caller of this predates multi-workspace mode and means the only
     * one.
     */
    val state: MirrorState get() = mirrors.single().state

    /**
     * The two-node replica mesh, or `null` in solo mode — which is every run
     * with no [BeadsMirrorConfig.peering], and is why nothing in a solo run
     * loads a `:wire` or `civictech.cell.replication` class (see
     * [MirrorPeering]).
     *
     * Always the single workspace's: peering with N > 1 is refused at startup
     * (decision 3bso.1-D3, [MultiWorkspacePeeringException]).
     */
    val peering: MirrorPeering? get() = mirrors.single().peering

    /**
     * The peering port this node is listening on, or `null` in dial/solo mode
     * — the port it **bound**, not the one it asked for, so `--listen 0` is
     * announceable (computenet-dqy.25).
     */
    val boundWsPort: Int? get() = peering?.boundWsPort

    /**
     * Set if the background poll loop died — most usefully, when a
     * re-baseline triggered by [FeedCondition.CheckpointGone] failed, since
     * that runs on the poller thread and has nowhere else to surface.
     *
     * This is a convenience for a test holding the app object, **not** the
     * mechanism by which the failure is made observable: nothing in a real
     * run polls it (which is the whole of computenet-dqj.12). The operator
     * learns through [BeadsMirrorConfig.onEvent] receiving a [PollLoopDied]
     * — which names the workspace it came from — and a consumer of the HTTP
     * surface learns because every route switches to `503` — see
     * [MirrorRoutes].
     *
     * Single-workspace shorthand, like [state]: with N workspaces each
     * poller's state is read per workspace at
     * [WorkspaceMirror.pollLoopStopped], since one dead loop leaves the others
     * running.
     */
    val pollerFailure: Throwable? get() = mirrors.single().pollerFailure

    fun stop() {
        mirrors.forEach { it.stop() }
        shell.stop()
    }

    override fun close() = stop()

    companion object {

        /**
         * Builds and starts one [WorkspaceMirror] per
         * [BeadsMirrorConfig.workspaces] entry — each its own [DoltCommitFeed],
         * [FeedCheckpoint], [MirrorState] over a [MirrorProjector],
         * [Rebaseline] and [DoltFeedPoller] — registers [MirrorRoutes] on one
         * shared started [DemoShell], starts every poll loop, and returns the
         * running app.
         *
         * **Three refusals, all before any component of any workspace starts**:
         *
         * - [LiveBeadsWorkspaceException] ([refuseIfLiveBeads]), run for EACH
         *   configured workspace, when one resolves to this repository's own
         *   live `.beads`. It stays the very first thing here specifically so
         *   it precedes the `bd export` subprocess a first-start baseline would
         *   otherwise spawn against the live tracker.
         * - [DuplicateWorkspaceIdentityException] (decision 3bso.1-D2) when two
         *   configured workspaces share a [sanitizedDoltDatabaseName].
         * - [MultiWorkspacePeeringException] (decision 3bso.1-D3) when a
         *   configuration names N > 1 workspaces and two-node peering.
         *
         * **Three paths reach [Rebaseline], and none is the genesis walk**
         * (feature computenet-dqj.3 rules 1 and 3; the restart path is
         * feature computenet-dqj.5's design amendment 2):
         *
         * - **First start** — no persisted checkpoint. The baseline runs
         *   *before* [DoltFeedPoller.start], so the poller's first tick already
         *   reads from the baselined head rather than replaying the whole
         *   commit graph from genesis. (`DoltCommitFeed.readFrom(null)` still
         *   walks from genesis; it just is not this app's first-run path any
         *   more, and remains available to tests and library callers.)
         * - **[RebaselineReason.Restart]** — a checkpoint *is* persisted, and
         *   the baseline runs anyway, at the same point in `start`. Nothing
         *   persists the projector across processes, so this start begins with
         *   an empty one; resuming the feed strictly after the checkpoint
         *   would replay only the commits made while the mirror was down and
         *   drop every pre-checkpoint issue permanently (measured during
         *   computenet-dqj.5's breakdown, when this branch read
         *   `if (checkpoint.read() == null)`). The checkpoint keeps its job
         *   *while running* — incremental resume and truncation detection —
         *   and is simply not a substitute for state no one kept.
         * - **[FeedCondition.CheckpointGone]** — the checkpoint fell out of
         *   `dolt_log`. The baseline runs synchronously inside
         *   `DoltFeedPoller.pollOnce`'s `onCondition` call, on the poller
         *   thread, and that tick emits nothing, so no post-gap record can be
         *   applied before the rebuild completes. See [Rebaseline]'s class doc
         *   for why that removes the need for a lock rather than merely hiding
         *   it.
         */
        fun start(config: BeadsMirrorConfig): BeadsMirrorApp {
            // Every refusal precedes every component, and the live-.beads one
            // precedes the rest: it is what stops a `bd export` subprocess
            // being spawned against the repository's own tracker, and it runs
            // for EACH configured workspace — one safe workspace in the list
            // does not make an unsafe sibling acceptable.
            config.workspaces.forEach { refuseIfLiveBeads(it, config.repoSearchRoot) }
            refuseCollidingIdentities(config.workspaces)
            refuseMultiWorkspacePeering(config)

            val mirrors = config.workspaces.map { workspace ->
                WorkspaceMirror.start(
                    workspace = workspace,
                    runDir = config.runDirFor(workspace),
                    pollInterval = config.pollInterval,
                    onEvent = config.onEvent,
                    peeringSettings = config.peering,
                    peeringTransport = config.peeringTransport,
                )
            }

            // The socket opens after every workspace's start-time baseline has
            // swapped its projector in and `rebind` has re-pointed the mesh —
            // so a peer's first announcement lands on cells that are already
            // the live ones. (`rebind` is correct under concurrent gossip; this
            // simply means the start-time swap never has to rely on that.)
            //
            // Routes are addressed per workspace (task computenet-3bso.1.2):
            // `GET /workspaces` lists every configured identity, and
            // `/workspaces/{identity}/beads/issues[/{id}]` serves that
            // workspace's fold, 503-with-stale-envelope on ITS routes alone
            // once ITS poll loop has died — a sibling workspace's routes stay
            // 200. The legacy unsegmented `/beads/issues` path is registered
            // too, bound to the sole workspace, exactly when this process
            // hosts exactly one (MirrorRoutes.register's size == 1 case) — the
            // shape every caller before N-workspace mode used, kept working
            // unchanged.
            val shell = DemoShell(config.port)
            val routeWorkspaces = mirrors.map { mirror ->
                MirrorRoutes.Workspace(mirror.identity, mirror.state) { mirror.pollLoopStopped }
            }
            MirrorRoutes(routeWorkspaces).register(shell)
            shell.start()
            mirrors.forEach { it.startPolling() }

            return BeadsMirrorApp(shell, mirrors)
        }

        /**
         * Decision 3bso.1-D2: two configured workspaces whose sanitized
         * identities collide are refused before anything starts.
         *
         * The identity is [sanitizedDoltDatabaseName] — the workspace
         * directory's *basename* with non-`[A-Za-z0-9_]` characters replaced by
         * `_` — so two genuinely different workspaces collide easily and
         * silently: `/a/ws` and `/b/ws` (same basename, different parents), or
         * `/tmp/a-b` and `/tmp/a_b` (different basenames, one sanitization).
         * A collision is not cosmetic. The identity is the [DotMinter] source
         * identity, so two colliding mirrors would mint dots from one source
         * and their last-writer-wins ordering would interleave; it is the
         * attribution on every [MirrorEvent], so an operator could not tell
         * which fold froze; and it is the key [mirror] resolves, so a lookup
         * would answer with whichever came first. Refusing is the only honest
         * option that does not invent a second identity scheme, which
         * 3bso.1-D2 explicitly rules out.
         */
        private fun refuseCollidingIdentities(workspaces: List<Path>) {
            val byIdentity = workspaces.groupBy(::sanitizedDoltDatabaseName)
            val collision = byIdentity.entries.firstOrNull { it.value.size > 1 } ?: return
            throw DuplicateWorkspaceIdentityException(
                "refusing to mirror ${collision.value.joinToString(", ")} in one process: they share the " +
                    "workspace identity '${collision.key}'. That identity is the dot-minting source, the " +
                    "attribution on every MirrorEvent and the per-workspace key, so two workspaces cannot " +
                    "hold it at once. Rehome one of them under a directory whose basename sanitizes to " +
                    "something else.",
            )
        }

        /**
         * Decision 3bso.1-D3: peering (two-node gossip) is refused outright
         * when more than one workspace is configured.
         *
         * [MirrorPeeringSettings] names ONE rig and ONE endpoint, and the rig
         * name is hashed into the shared logical `CellRef`s both nodes derive
         * ([MirrorPeering]). N workspaces in one process would therefore have
         * to share one rig's refs — which would gossip N unrelated folds into
         * one logical cell — or else the settings would have to become
         * per-workspace, which is a config, CLI and rig-protocol change that
         * generalizing gossip was explicitly excluded from this feature.
         * Refusing is the cheaper honest option 3bso.1-D3 permits: a
         * single-workspace process keeps two-node mode exactly as it was, and
         * nothing silently half-works.
         */
        private fun refuseMultiWorkspacePeering(config: BeadsMirrorConfig) {
            if (config.peering == null || config.workspaces.size == 1) return
            throw MultiWorkspacePeeringException(
                "refusing to start ${config.workspaces.size} workspaces with peering: --rig names one rig, " +
                    "whose name is hashed into the shared logical CellRefs both nodes derive, so N " +
                    "workspaces in one process would gossip N unrelated folds into one logical cell. " +
                    "Two-node mode is single-workspace only (decision 3bso.1-D3); run one process per " +
                    "workspace to peer, or drop --rig/--listen/--peer to mirror several workspaces.",
            )
        }
    }
}

/**
 * The arguments [BeadsMirrorApp.Companion.start] needs, separated from
 * command-line parsing so tests can drive both the refusal path and the
 * end-to-end path in-process without a subprocess.
 *
 * @param workspaces the bd workspace roots to mirror, in the order they were
 *   configured — one [WorkspaceMirror] each, all behind one [DemoShell] (task
 *   computenet-3bso.1.1). Must name at least one; there is no default, because
 *   there is no scratch path that is always safe to pick for the caller. Two
 *   workspaces sharing a [sanitizedDoltDatabaseName] are refused at startup
 *   (decision 3bso.1-D2). The single-workspace secondary constructor takes a
 *   `workspace: Path` and is what every pre-N caller uses unchanged.
 * @param port the [DemoShell] port; `0` (the default) binds an ephemeral
 *   loopback port, matching every other demo's ephemeral-port convention.
 * @param pollInterval [DoltFeedPoller]'s poll cadence, per workspace; defaults
 *   to 1 second.
 * @param runDir [FeedCheckpoint]'s persistence directory; defaults to
 *   `<workspace>/.beadsmirror` per workspace when `null`. When given
 *   explicitly it is used **verbatim for a single workspace** — which is what
 *   `--run-dir` has always meant — and as a *parent* for N > 1, each workspace
 *   getting `<runDir>/<identity>`. Sharing one directory between N checkpoints
 *   is not an option: [FeedCheckpoint] writes a fixed `checkpoint` filename, so
 *   N mirrors would overwrite each other's feed position. See [runDirFor].
 * @param repoSearchRoot where [refuseIfLiveBeads] starts walking upward to
 *   find the repository's own `.beads` directory; defaults to the process's
 *   working directory. Overridable so a test can point the search at a
 *   throwaway directory tree instead of this repository's real layout.
 * @param onEvent receives every [MirrorEvent] the running mirror produces —
 *   today only [MirrorEvent.Rebaselined]. The default prints one line to
 *   stdout, which is what makes a rebuild visible in a demo run; a test
 *   supplies a collector and asserts the typed value rather than parsing that
 *   line. Called on whichever thread produced the event (the poller thread for
 *   a [RebaselineReason.CheckpointGone] rebuild, the caller of
 *   [BeadsMirrorApp.Companion.start] for a start-time [RebaselineReason.FirstStart]
 *   or [RebaselineReason.Restart] one),
 *   synchronously, so an implementation that blocks stalls polling.
 * @param peering opt-in two-node mode (task computenet-7em.1.2), **single
 *   workspace only** — a configuration naming N > 1 workspaces and peering is
 *   refused by [MultiWorkspacePeeringException] (decision 3bso.1-D3): the mirror's
 *   two cells become replicas of one logical cell and gossip their deltas to
 *   one peer over `:wire`. **`null` — the default — is solo mode, and solo
 *   mode is exactly the app that existed before this parameter did**: no
 *   registry, no host, no [MirrorPeering], no `:wire` class loaded, and the
 *   projector keeps its random-`CellRef` default.
 * @param peeringTransport the transport binding [peering] establishes its end
 *   through (task computenet-7em.2.1); `null` — the default — means the
 *   production [WsMirrorTransport], constructed lazily *inside* the
 *   [peering]-only branch so a solo run still loads no `:wire` class. Supplied
 *   only by a rig that must hold the two nodes' peering as one object, because
 *   partition and heal are properties of the peering rather than of a node.
 */
data class BeadsMirrorConfig(
    val workspaces: List<Path>,
    val port: Int = 0,
    val pollInterval: Duration = Duration.ofMillis(1000),
    val runDir: Path? = null,
    val repoSearchRoot: Path = Path.of("").toAbsolutePath(),
    val onEvent: (MirrorEvent) -> Unit = ::printMirrorEvent,
    val peering: MirrorPeeringSettings? = null,
    val peeringTransport: MirrorTransport? = null,
) {

    init {
        require(workspaces.isNotEmpty()) {
            "a beadsmirror configuration must name at least one workspace: there is no scratch path " +
                "that is always safe to pick for the caller"
        }
    }

    /**
     * The single-workspace form, unchanged from before this config could name
     * more than one: `BeadsMirrorConfig(workspace = ..., ...)` is exactly
     * `BeadsMirrorConfig(workspaces = listOf(...), ...)`. Kept as a
     * constructor rather than a factory so every existing named-argument call
     * site compiles untouched.
     */
    constructor(
        workspace: Path,
        port: Int = 0,
        pollInterval: Duration = Duration.ofMillis(1000),
        runDir: Path? = null,
        repoSearchRoot: Path = Path.of("").toAbsolutePath(),
        onEvent: (MirrorEvent) -> Unit = ::printMirrorEvent,
        peering: MirrorPeeringSettings? = null,
        peeringTransport: MirrorTransport? = null,
    ) : this(listOf(workspace), port, pollInterval, runDir, repoSearchRoot, onEvent, peering, peeringTransport)

    /**
     * The one workspace this configuration names, for a caller that knows it
     * names exactly one. Throws when it names several — the same deliberate
     * refusal to guess as [BeadsMirrorApp.state].
     */
    val workspace: Path get() = workspaces.single()

    /**
     * [FeedCheckpoint]'s directory for [workspace]: the configured [runDir]
     * verbatim when this configuration names one workspace, `<runDir>/<its
     * sanitized identity>` when it names several, and
     * `<workspace>/.beadsmirror` when no [runDir] was configured at all.
     *
     * The single-workspace case is verbatim on purpose: `--run-dir` has always
     * meant "put the checkpoint here", and a test that reads
     * `runDir/checkpoint` is reading the documented contract, not an
     * implementation detail. The N > 1 case cannot honour that reading for
     * every workspace at once — one `checkpoint` filename, N feed positions —
     * so it segments by the identity that is already unique by
     * [BeadsMirrorApp.Companion.start]'s own refusal.
     */
    fun runDirFor(workspace: Path): Path = when {
        runDir == null -> workspace.resolve(".beadsmirror")
        workspaces.size == 1 -> runDir
        else -> runDir.resolve(sanitizedDoltDatabaseName(workspace))
    }
}

/**
 * The default [BeadsMirrorConfig.onEvent]: one line per event on stdout, plus
 * — for a [PollLoopDied] — the throwable's stack trace on stderr.
 *
 * The extra stack trace is the difference between "the mirror stopped" and
 * "the mirror stopped *here*". The event line already names the throwable and
 * the frozen checkpoint, which is what the criterion requires; the trace is
 * what makes the next such failure diagnosable without reproducing it, and a
 * dead poll loop is rare enough that the noise costs nothing.
 */
fun printMirrorEvent(event: MirrorEvent) {
    println("beadsmirror: $event")
    if (event is PollLoopDied) {
        System.err.println(
            "beadsmirror: workspace '${event.workspaceIdentity}': the poll loop has stopped for good; " +
                "its fold is frozen at ${event.checkpoint ?: "(no checkpoint)"} and its routes now answer " +
                "503. Any other workspace this process mirrors is unaffected — each has its own poll loop.",
        )
        event.failure.printStackTrace()
    }
}

/**
 * Raised by [refuseIfLiveBeads] when a workspace resolves to the repository's
 * own live `.beads`. A subtype of [IllegalArgumentException] so a caller that
 * only wants "bad input" handling need not know this type exists; [main]
 * catches it specifically to print a clean refusal message instead of a
 * stack trace.
 */
class LiveBeadsWorkspaceException(message: String) : IllegalArgumentException(message)

/**
 * Raised by [BeadsMirrorApp.Companion.start] when two configured workspaces
 * share one [sanitizedDoltDatabaseName] (decision 3bso.1-D2). A named subtype
 * of [IllegalArgumentException], following [LiveBeadsWorkspaceException]: a
 * caller that only wants "bad input" handling need not know this type exists,
 * a test can name it, and [main] catches it to print a clean refusal instead
 * of a stack trace. Thrown before any component of any workspace starts.
 */
class DuplicateWorkspaceIdentityException(message: String) : IllegalArgumentException(message)

/**
 * Raised by [BeadsMirrorApp.Companion.start] when a configuration names more
 * than one workspace *and* two-node peering (decision 3bso.1-D3 — gossip is
 * not generalized to N workspaces; see `refuseMultiWorkspacePeering` for the
 * mechanism, and [BeadsMirrorConfig.peering] for the rule). Same named-subtype
 * shape as [LiveBeadsWorkspaceException], for the same reasons.
 */
class MultiWorkspacePeeringException(message: String) : IllegalArgumentException(message)

/**
 * The bd/Dolt embedded-database directory name for a bd workspace whose root
 * is [workspace] — [workspace]'s basename with every character that is not a
 * letter, digit or underscore replaced by an underscore. Promoted out of test
 * scope (originally [civictech.demo.beadsmirror.BdScratchWorkspace] alone)
 * because this app needs the exact same rule to resolve [doltRootFor] and to
 * derive a stable [DotMinter] workspace identity.
 */
fun sanitizedDoltDatabaseName(workspace: Path): String =
    workspace.fileName.toString().replace(Regex("[^A-Za-z0-9_]"), "_")

/**
 * The Dolt database root for the bd workspace at [workspace]:
 * `<workspace>/.beads/embeddeddolt/<sanitized-name>/` — NOT the `.dolt/`
 * directory beneath it. See [sanitizedDoltDatabaseName] for the sanitization
 * rule.
 */
fun doltRootFor(workspace: Path): Path =
    workspace.resolve(".beads").resolve("embeddeddolt").resolve(sanitizedDoltDatabaseName(workspace))

/**
 * Refuses to proceed when [workspace] resolves to the same `.beads` directory
 * as the repository containing [searchFrom] — feature rule 4: this mirror
 * must never read or write the repository's own live issue tracker.
 *
 * A live `.beads` is looked for from **three** starting points, and any match
 * refuses: upward from [searchFrom] to the first ancestor containing one
 * (matching how `bd`/`bv` themselves locate a workspace root); upward from
 * this class's own code source ([codeSourceDir]); and — when that code source
 * sits in a linked git worktree — the main checkout that worktree belongs to
 * ([mainCheckoutOf]), which is where `bd`'s Dolt database actually lives. The
 * [searchFrom] candidate alone is only as good as the process's working
 * directory, which the caller does not control; [codeSourceDir] and
 * [mainCheckoutOf] each carry a measured escape they close. If none finds
 * one, there is nothing to refuse against and this returns normally.
 * Both paths are canonicalized ([canonicalOrAbsolute]) before comparison so a
 * symlinked checkout or a `..`-laden `--workspace` argument cannot slip past
 * a naive string comparison.
 *
 * Pure path logic — no `bd`/`dolt` process is started, and nothing is
 * mutated — so this is testable without either binary on `PATH`, and runs
 * before [BeadsMirrorApp.Companion.start] touches the feed, the checkpoint,
 * or a socket.
 *
 * @throws LiveBeadsWorkspaceException when [workspace]'s `.beads` is the
 *   repository's own.
 */
fun refuseIfLiveBeads(workspace: Path, searchFrom: Path) {
    val workspaceBeads = canonicalOrAbsolute(workspace.resolve(".beads"))
    val ownBeads = codeSourceDir()?.let(::findAncestorBeadsDir)
    val liveCandidates = listOfNotNull(
        findAncestorBeadsDir(searchFrom),
        ownBeads,
        ownBeads?.parent?.let(::mainCheckoutOf)?.resolve(".beads")?.takeIf(Files::isDirectory),
    )
    if (liveCandidates.any { canonicalOrAbsolute(it) == workspaceBeads }) {
        throw LiveBeadsWorkspaceException(
            "refusing to mirror $workspaceBeads: it resolves to a live repository checkout's " +
                ".beads, not a throwaway workspace. Point --workspace at a scratch workspace " +
                "(bd --sandbox init), never at a repository checkout.",
        )
    }
}

/**
 * The main checkout of the git repository that [checkoutRoot] is a **linked
 * worktree** of, or `null` when [checkoutRoot] is not one (its `.git` is a
 * directory, or absent). A linked worktree's `.git` is a text file reading
 * `gitdir: <main>/.git/worktrees/<name>`, so the main checkout is two levels
 * above the `.git` component of that path — a plain file read, no `git`
 * process.
 *
 * This is the third [refuseIfLiveBeads] candidate and it matters here
 * specifically: a `/work` session builds and runs this app inside a linked
 * worktree, whose own `.beads` holds only the checked-in configuration, while
 * the **Dolt database that `bd` actually mutates lives in the main checkout**
 * (AGENTS.md: `bd` must be run with `-C <main checkout>`). Without this,
 * `--workspace=<main checkout>` from a worktree-built binary is the live
 * tracker and would not be refused — measured during this feature's review.
 */
internal fun mainCheckoutOf(checkoutRoot: Path): Path? {
    val dotGit = checkoutRoot.resolve(".git")
    if (!Files.isRegularFile(dotGit)) return null
    val gitDirLine = try {
        Files.readAllLines(dotGit).firstOrNull { it.startsWith("gitdir:") }
    } catch (_: IOException) {
        null
    } ?: return null
    var component: Path? = Path.of(gitDirLine.removePrefix("gitdir:").trim()).normalize()
    while (component != null && component.fileName?.toString() != ".git") component = component.parent
    return component?.parent
}

/**
 * The directory this class's own bytecode was loaded from — `build/classes/…`
 * under the checkout for a Gradle run, `build/install/beadsmirror/lib/` for
 * the installed distribution — or `null` when the code source is unavailable
 * (an exotic class loader) or not a filesystem path.
 *
 * [refuseIfLiveBeads] walks upward from here as its second candidate, and
 * that is what makes the refusal independent of the process's working
 * directory. Measured during this feature's review: with `searchFrom` alone,
 * running the installed `beadsmirror` from `/tmp` (no `.beads` anywhere above
 * it) against `--workspace=<the computenet checkout>` started normally and
 * wrote `.beadsmirror/checkpoint` into the live checkout — the exact thing
 * epic computenet-dqj §4 forbids. The code source cannot be pointed elsewhere
 * by the caller, so "this repository" means the checkout the running binary
 * was built in, which is the criterion's own wording.
 *
 * Note that a `.git` directory is *not* a usable discriminator here: `bd
 * --sandbox init` creates one inside the scratch workspace itself (measured —
 * it failed both scratch-workspace tests when tried).
 */
private fun codeSourceDir(): Path? =
    try {
        val location = BeadsMirrorApp::class.java.protectionDomain?.codeSource?.location
        location?.let { Path.of(it.toURI()) }?.let { if (Files.isDirectory(it)) it else it.parent }
    } catch (_: Exception) {
        null
    }

/**
 * Walks upward from [start] to the first directory (inclusive) containing a
 * `.beads` subdirectory, returning that subdirectory. `null` if no ancestor
 * has one (e.g. run from outside any bd-tracked repository — a worktree with
 * no `.beads` export is exactly this case, per AGENTS.md's "Repository map").
 */
private fun findAncestorBeadsDir(start: Path): Path? {
    var dir: Path? = start.toAbsolutePath().normalize()
    while (dir != null) {
        val candidate = dir.resolve(".beads")
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    return null
}

/**
 * [Path.toRealPath] when [path] exists (resolves symlinks, `..`, `.`), else
 * the normalized absolute form — a workspace argument naming a not-yet-created
 * directory must still canonicalize to something comparable.
 */
private fun canonicalOrAbsolute(path: Path): Path =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        path.toAbsolutePath().normalize()
    }

/**
 * `--name value` or `--name=value` lookup, then strip the matched token(s)
 * from the returned array. The `=` form is required for `--workspace`
 * (ticket computenet-dqj.4.2's decided direction: "--workspace <path> (also
 * --workspace=<path>)"); applied to every flag here rather than only
 * `--workspace` so the three flags this app parses behave consistently.
 * `internal` (not `private`) so [BeadsMirrorAppTest] can exercise the parsing
 * directly instead of only through the process-exiting [main].
 */
internal fun Array<String>.extractFlag(name: String): Pair<String?, Array<String>> {
    val prefix = "$name="
    val inlineIndex = indexOfFirst { it.startsWith(prefix) }
    if (inlineIndex >= 0) {
        val value = this[inlineIndex].substring(prefix.length)
        val rest = toMutableList().apply { removeAt(inlineIndex) }
        return value to rest.toTypedArray()
    }
    val i = indexOf(name)
    if (i < 0 || i + 1 >= size) return null to this
    val value = this[i + 1]
    val rest = toMutableList().apply {
        removeAt(i + 1)
        removeAt(i)
    }
    return value to rest.toTypedArray()
}

/**
 * Every occurrence of `--name value` / `--name=value`, **in command-line
 * order**, with all matched tokens stripped.
 *
 * `--workspace` is repeatable as of task computenet-3bso.1.1 — one mirror per
 * occurrence — and one occurrence must behave exactly as it always did, which
 * it does: a single occurrence of either spelling yields the same value and
 * the same remaining array [extractFlag] would. An empty list means the flag
 * was absent, and a trailing `--name` with no value after it is left in the
 * remainder rather than consumed, matching [extractFlag]'s `i + 1 >= size`
 * case.
 *
 * A left-to-right scan rather than a loop over [extractFlag], because
 * [extractFlag] answers the inline `=` spelling *first* wherever it sits: over
 * `--workspace /a --workspace=/b` a loop would report `/b` before `/a`, and
 * the order is not cosmetic — the first configured workspace is the one served
 * on the legacy HTTP path until computenet-3bso.1.2 lands.
 */
internal fun Array<String>.extractFlagAll(name: String): Pair<List<String>, Array<String>> {
    val prefix = "$name="
    val values = mutableListOf<String>()
    val rest = mutableListOf<String>()
    var i = 0
    while (i < size) {
        val token = this[i]
        when {
            token.startsWith(prefix) -> {
                values += token.substring(prefix.length)
                i++
            }
            token == name && i + 1 < size -> {
                values += this[i + 1]
                i += 2
            }
            else -> {
                rest += token
                i++
            }
        }
    }
    return values to rest.toTypedArray()
}

/**
 * Parses the three two-node flags out of [args] and returns the settings they
 * describe together with the remaining arguments — `--rig <name>` plus exactly
 * one of `--listen <wsPort>` (listener) or `--peer <ws-uri>` (dialer). The
 * role is never given directly: it *is* which of the two endpoint flags was
 * used, so there is no third flag that can disagree with them.
 *
 * `null` settings means solo mode, and solo mode is what no flags at all
 * means. Every other combination is a usage error rather than a guess, since
 * each guess would be silently wrong in a different way: a rig name with no
 * endpoint peers with nobody, an endpoint with no rig name mints a logical
 * cell no peer can derive, and both endpoints at once names two roles.
 *
 * `internal` so the parsing is testable directly rather than only through the
 * process-exiting [main] — the same reason [extractFlag] is.
 *
 * @throws IllegalArgumentException on any partial or contradictory combination.
 */
internal fun Array<String>.extractPeering(): Pair<MirrorPeeringSettings?, Array<String>> {
    val (rig, afterRig) = extractFlag("--rig")
    val (listen, afterListen) = afterRig.extractFlag("--listen")
    val (peer, rest) = afterListen.extractFlag("--peer")

    if (rig == null && listen == null && peer == null) return null to rest
    require(listen == null || peer == null) {
        "--listen and --peer name opposite roles; give exactly one (--listen <wsPort> to be the " +
            "listener, --peer <ws-uri> to be the dialer)"
    }
    require(rig != null) {
        "two-node mode needs --rig <name>: the rig name is hashed into the shared logical CellRefs " +
            "both nodes must derive, so there is no default that could ever match a peer's"
    }
    val wire = when {
        listen != null -> MirrorWire.Listen(
            requireNotNull(listen.toIntOrNull()) { "--listen takes a port number (0 = any free port), not '$listen'" },
        )
        peer != null -> MirrorWire.Dial(peer)
        else -> throw IllegalArgumentException(
            "--rig $rig names a rig but no endpoint; add --listen <wsPort> or --peer <ws-uri>",
        )
    }
    return MirrorPeeringSettings(rig, wire) to rest
}

fun main(args: Array<String>) {
    val (workspaceArgs, afterWorkspace) = args.extractFlagAll("--workspace")
    if (workspaceArgs.isEmpty()) {
        System.err.println(
            "usage: beadsmirror --workspace <path> [--workspace <path> ...] [--poll-interval-ms <ms>] " +
                "[--run-dir <path>] [--rig <name> (--listen <wsPort> | --peer <ws-uri>)] [port]\n" +
                "  --workspace may repeat: one mirror per workspace, all on one HTTP port. Two-node " +
                "mode (--rig) is single-workspace only.",
        )
        exitProcess(1)
    }
    val (pollIntervalArg, afterPollInterval) = afterWorkspace.extractFlag("--poll-interval-ms")
    val (runDirArg, afterRunDir) = afterPollInterval.extractFlag("--run-dir")
    val (peering, remaining) = try {
        afterRunDir.extractPeering()
    } catch (e: IllegalArgumentException) {
        System.err.println("beadsmirror: ${e.message}")
        exitProcess(1)
    }

    val config = BeadsMirrorConfig(
        workspaces = workspaceArgs.map { Path.of(it).toAbsolutePath().normalize() },
        port = demoPort(remaining),
        pollInterval = Duration.ofMillis(pollIntervalArg?.toLongOrNull() ?: 1000L),
        runDir = runDirArg?.let { Path.of(it) },
        peering = peering,
    )

    val app = try {
        BeadsMirrorApp.start(config)
    } catch (e: LiveBeadsWorkspaceException) {
        System.err.println("beadsmirror: ${e.message}")
        exitProcess(1)
    } catch (e: DuplicateWorkspaceIdentityException) {
        System.err.println("beadsmirror: ${e.message}")
        exitProcess(1)
    } catch (e: MultiWorkspacePeeringException) {
        System.err.println("beadsmirror: ${e.message}")
        exitProcess(1)
    }

    println("computenet beadsmirror: http://localhost:${app.boundPort}")
    app.mirrors.forEach { println("  mirroring ${it.workspace} as '${it.identity}' (run dir ${it.runDir})") }
    announcePort("http", app.boundPort)
    when (val wire = peering?.wire) {
        is MirrorWire.Listen -> {
            // the BOUND port, not `wire.wsPort`: `--listen 0` asks for any free
            // one, and only this process knows which it got (computenet-dqy.25)
            val wsPort = checkNotNull(app.boundWsPort) { "a listening node must have a bound ws port" }
            println("  rig '${peering.rigName}' as ${peering.role}; awaiting a peer on ws://localhost:$wsPort")
            announcePort("ws", wsPort)
        }
        is MirrorWire.Dial -> println("  rig '${peering.rigName}' as ${peering.role}; peered with ${wire.uri}")
        null -> println("  single-node mode; add --rig <name> with --listen <wsPort> or --peer <ws-uri> to span two JVMs")
    }
}
