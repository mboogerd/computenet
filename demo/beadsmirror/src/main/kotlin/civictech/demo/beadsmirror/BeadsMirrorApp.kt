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
 */
class BeadsMirrorApp private constructor(
    private val shell: DemoShell,
    private val poller: DoltFeedPoller,
    /**
     * The live projector handle, swapped wholesale by a re-baseline. Exposed
     * so a test can read [MirrorProjector.view]/`edgeView` and
     * [MirrorState.rebaselineCount] directly instead of only through HTTP.
     */
    val state: MirrorState,
) : AutoCloseable {

    /** The port the shell actually bound — meaningful once [start] has run. */
    val boundPort: Int get() = shell.boundPort

    /**
     * Set if the background poll loop died — most usefully, when a
     * re-baseline triggered by [FeedCondition.CheckpointGone] failed, since
     * that runs on the poller thread and has nowhere else to surface.
     *
     * This is a convenience for a test holding the app object, **not** the
     * mechanism by which the failure is made observable: nothing in a real
     * run polls it (which is the whole of computenet-dqj.12). The operator
     * learns through [BeadsMirrorConfig.onEvent] receiving a [PollLoopDied],
     * and a consumer of the HTTP surface learns because every route switches
     * to `503` — see [MirrorRoutes].
     */
    val pollerFailure: Throwable? get() = poller.failure

    fun stop() {
        poller.stop()
        shell.stop()
    }

    override fun close() = stop()

    companion object {

        /**
         * Builds and starts every component — [DoltCommitFeed], [FeedCheckpoint],
         * [MirrorState] over a [MirrorProjector], [Rebaseline], [DoltFeedPoller]
         * (started immediately), [MirrorRoutes] on a started [DemoShell] — and
         * returns the running app.
         *
         * Refuses via [LiveBeadsWorkspaceException] before starting anything
         * ([refuseIfLiveBeads]) when [BeadsMirrorConfig.workspace] resolves to
         * this repository's own live `.beads`. That refusal is the very first
         * statement here specifically so it precedes the `bd export`
         * subprocess a first-start baseline would otherwise spawn against the
         * live tracker.
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
            refuseIfLiveBeads(config.workspace, config.repoSearchRoot)

            val doltRoot = doltRootFor(config.workspace)
            val runDir = config.runDir ?: config.workspace.resolve(".beadsmirror")
            val workspaceIdentity = sanitizedDoltDatabaseName(config.workspace)

            val feed = DoltCommitFeed(doltRoot)
            val checkpoint = FeedCheckpoint(runDir)
            val state = MirrorState(MirrorProjector(DotMinter(workspaceIdentity)))

            val rebaseline = Rebaseline(
                export = BdExportReader(config.workspace)::read,
                feed = feed,
                checkpoint = checkpoint,
                state = state,
                workspaceIdentity = workspaceIdentity,
                onEvent = config.onEvent,
            )

            val poller = DoltFeedPoller(
                feed = feed,
                checkpoint = checkpoint,
                interval = config.pollInterval,
                // Re-read the handle per batch: a re-baseline earlier in this
                // very tick may have replaced the projector.
                onBatch = { records -> state.current.applyAll(records) },
                onCondition = { condition ->
                    when (condition) {
                        is FeedCondition.CheckpointGone ->
                            rebaseline.run(RebaselineReason.CheckpointGone(condition.checkpoint))
                    }
                },
                // computenet-dqj.12: the loop dying is the one thing this
                // process cannot keep to itself. It reports through the same
                // channel as every other MirrorEvent, so an operator who
                // wired up `onEvent` at all hears it without wiring anything
                // else, and the default handler prints it.
                onStopped = { config.onEvent(PollLoopDied(it.failure, it.checkpoint)) },
            )

            // Before the socket: a start-time baseline is part of "started",
            // so the very first request is answered from complete state rather
            // than from an empty projector that fills in moments later. It
            // runs on EVERY start, checkpoint or not — see the class doc.
            val persisted = checkpoint.read()
            rebaseline.run(
                if (persisted == null) RebaselineReason.FirstStart else RebaselineReason.Restart(persisted),
            )

            val shell = DemoShell(config.port)
            MirrorRoutes(state, poller::stopped).register(shell)
            shell.start()
            poller.start()

            return BeadsMirrorApp(shell, poller, state)
        }
    }
}

/**
 * The arguments [BeadsMirrorApp.Companion.start] needs, separated from
 * command-line parsing so tests can drive both the refusal path and the
 * end-to-end path in-process without a subprocess.
 *
 * @param workspace the bd workspace root to mirror. Required by [main]; there
 *   is no default, because there is no scratch path that is always safe to
 *   pick for the caller.
 * @param port the [DemoShell] port; `0` (the default) binds an ephemeral
 *   loopback port, matching every other demo's ephemeral-port convention.
 * @param pollInterval [DoltFeedPoller]'s poll cadence; defaults to 1 second.
 * @param runDir [FeedCheckpoint]'s persistence directory; defaults to
 *   `<workspace>/.beadsmirror` when `null`.
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
 */
data class BeadsMirrorConfig(
    val workspace: Path,
    val port: Int = 0,
    val pollInterval: Duration = Duration.ofMillis(1000),
    val runDir: Path? = null,
    val repoSearchRoot: Path = Path.of("").toAbsolutePath(),
    val onEvent: (MirrorEvent) -> Unit = ::printMirrorEvent,
)

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
            "beadsmirror: the poll loop has stopped for good; the served fold is frozen at " +
                "${event.checkpoint ?: "(no checkpoint)"} and every route now answers 503.",
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

fun main(args: Array<String>) {
    val (workspaceArg, afterWorkspace) = args.extractFlag("--workspace")
    if (workspaceArg == null) {
        System.err.println(
            "usage: beadsmirror --workspace <path> [--poll-interval-ms <ms>] " +
                "[--run-dir <path>] [port]",
        )
        exitProcess(1)
    }
    val (pollIntervalArg, afterPollInterval) = afterWorkspace.extractFlag("--poll-interval-ms")
    val (runDirArg, remaining) = afterPollInterval.extractFlag("--run-dir")

    val config = BeadsMirrorConfig(
        workspace = Path.of(workspaceArg).toAbsolutePath().normalize(),
        port = demoPort(remaining),
        pollInterval = Duration.ofMillis(pollIntervalArg?.toLongOrNull() ?: 1000L),
        runDir = runDirArg?.let { Path.of(it) },
    )

    val app = try {
        BeadsMirrorApp.start(config)
    } catch (e: LiveBeadsWorkspaceException) {
        System.err.println("beadsmirror: ${e.message}")
        exitProcess(1)
    }

    println("computenet beadsmirror: http://localhost:${app.boundPort}")
    announcePort("http", app.boundPort)
}
