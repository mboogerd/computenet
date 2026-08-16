package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCheckpoint
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
) : AutoCloseable {

    /** The port the shell actually bound — meaningful once [start] has run. */
    val boundPort: Int get() = shell.boundPort

    fun stop() {
        poller.stop()
        shell.stop()
    }

    override fun close() = stop()

    companion object {

        /**
         * Builds and starts every component — [DoltCommitFeed], [FeedCheckpoint],
         * [MirrorProjector], [DoltFeedPoller] (started immediately), [MirrorRoutes]
         * on a started [DemoShell] — and returns the running app.
         *
         * Refuses via [LiveBeadsWorkspaceException] before starting anything
         * ([refuseIfLiveBeads]) when [BeadsMirrorConfig.workspace] resolves to
         * this repository's own live `.beads`.
         */
        fun start(config: BeadsMirrorConfig): BeadsMirrorApp {
            refuseIfLiveBeads(config.workspace, config.repoSearchRoot)

            val doltRoot = doltRootFor(config.workspace)
            val runDir = config.runDir ?: config.workspace.resolve(".beadsmirror")
            val minter = DotMinter(sanitizedDoltDatabaseName(config.workspace))

            val feed = DoltCommitFeed(doltRoot)
            val checkpoint = FeedCheckpoint(runDir)
            val projector = MirrorProjector(minter)

            // The poller's default onCondition (throw FeedConditionException) is
            // kept: re-baselining past a gone checkpoint is computenet-dqj.3's
            // job, not this task's — a loud crash on CheckpointGone is correct
            // here.
            val poller = DoltFeedPoller(
                feed = feed,
                checkpoint = checkpoint,
                interval = config.pollInterval,
                onBatch = projector::applyAll,
            )

            val shell = DemoShell(config.port)
            MirrorRoutes(projector).register(shell)
            shell.start()
            poller.start()

            return BeadsMirrorApp(shell, poller)
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
 */
data class BeadsMirrorConfig(
    val workspace: Path,
    val port: Int = 0,
    val pollInterval: Duration = Duration.ofMillis(1000),
    val runDir: Path? = null,
    val repoSearchRoot: Path = Path.of("").toAbsolutePath(),
)

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
 * The repository's `.beads` is found by walking upward from [searchFrom] to
 * the first ancestor directory that contains one (matching how `bd`/`bv`
 * themselves locate a workspace root); if no ancestor has one, there is
 * nothing to refuse against and this returns normally. Both paths are
 * canonicalized ([canonicalOrAbsolute]) before comparison so a symlinked
 * checkout or a `..`-laden `--workspace` argument cannot slip past a naive
 * string comparison.
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
    val repoBeads = findAncestorBeadsDir(searchFrom) ?: return
    val workspaceBeads = canonicalOrAbsolute(workspace.resolve(".beads"))
    val repoBeadsCanonical = canonicalOrAbsolute(repoBeads)
    if (workspaceBeads == repoBeadsCanonical) {
        throw LiveBeadsWorkspaceException(
            "refusing to mirror $workspaceBeads: it resolves to this repository's own live " +
                ".beads. Point --workspace at a throwaway bd workspace (bd --sandbox init), " +
                "never at the repository you are running from.",
        )
    }
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
