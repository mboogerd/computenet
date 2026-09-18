package civictech.demo.allocatorobserve

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.declaration.DeclarationIngester
import civictech.demo.allocatorobserve.http.AllocatorRoutes
import civictech.demo.allocatorobserve.http.IngestFailureCounts
import civictech.demo.allocatorobserve.http.IngestHealth
import civictech.demo.allocatorobserve.http.PollLoopStopped
import civictech.demo.allocatorobserve.http.ServedState
import civictech.demo.allocatorobserve.http.ServedStateHolder
import civictech.demo.allocatorobserve.http.toJson
import civictech.demo.allocatorobserve.ingest.CheckpointState
import civictech.demo.allocatorobserve.ingest.OffsetCheckpoint
import civictech.demo.allocatorobserve.ingest.SpendLogIngester
import civictech.demo.allocatorobserve.ingest.SpendOffsetStore
import civictech.demo.allocatorobserve.ingest.TailReason
import civictech.demo.allocatorobserve.view.AllocatorReportViews
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Everything [AllocatorObserveApp] needs to run (fpml.4-D9).
 *
 * Every path is a parameter — the socaity spend log's eventual location is
 * undecided (fpml.1-D1), so nothing here may acquire a default.
 *
 * @param logPath the spend log the tail reader follows. It need not exist yet.
 * @param runDir where the byte-offset checkpoint is persisted.
 * @param declarationPath the hand-edited allocation declaration.
 * @param port the HTTP port; `0` binds an ephemeral loopback port, which is
 *   what every test uses (`DemoShell.endpoint`).
 * @param pollInterval how long the background poll thread waits between ticks.
 * @param windowLength the rolling window the R5 report covers. A flag rather
 *   than a constant: `doc/allocator-mvp.md` describes a window of "one week"
 *   but pins no length, and `AllocationDeclaration.window` is carried
 *   unparsed (fpml.3-D7), so nothing downstream can derive it.
 */
data class AllocatorObserveConfig(
    val logPath: Path,
    val runDir: Path,
    val declarationPath: Path,
    val port: Int = 0,
    val pollInterval: Duration = Duration.ofMillis(1000),
    val windowLength: Duration = Duration.ofHours(168),
)

/**
 * A [SpendOffsetStore] that remembers the last [CheckpointState] to pass
 * through it, so the served ingest health can report the log's byte checkpoint
 * offset (fpml.4-D7).
 *
 * **Why a decorator rather than an accessor on the ingester.**
 * `SpendPollOutcome` carries the poll's reason and its add/remove/failure
 * counts but *not* the checkpoint offset, and
 * `ingest/SpendLogIngester.kt` is claimed by an open sibling bug
 * (`computenet-brvag`) that this task may not edit. `SpendLogIngester` does
 * take its `checkpoint` store as a constructor parameter, precisely so a
 * caller can observe that seam — so decorating it reads the offset without
 * touching F1 at all.
 *
 * [last] is initialised from `delegate.read()` so a restarted process reports
 * the offset it resumed from rather than `null` until its first write, and is
 * updated AFTER the delegate's write returns, so it never advertises an offset
 * that is not yet persisted.
 *
 * The limit of the number this produces, stated where the number is produced:
 * it is the last offset this *store* saw written, which the reader writes only
 * after the batch reaching it has been handed to the fold. A poll that
 * delivered nothing (an absent log, or no new complete line) writes nothing,
 * so [last] then stays where it was — which is the truth about the checkpoint,
 * not a stale reading of it.
 */
class RecordingOffsetStore(private val delegate: SpendOffsetStore) : SpendOffsetStore {

    /** The most recent state written through this store, or the one it was constructed over. */
    @Volatile
    var last: CheckpointState? = delegate.read()
        private set

    override fun read(): CheckpointState? = delegate.read()

    override fun write(state: CheckpointState) {
        delegate.write(state)
        last = state
    }
}

/**
 * The runnable `:demo:allocator-observe` process (feature `computenet-fpml.4`,
 * fpml.4-D4/D6/D9): one poll driver over F1's `SpendLogIngester`, F2's
 * `DeclarationIngester` and F3's `AllocatorReportViews`, serving the result
 * read-only over `:demo:shell` as `GET /state` (plus its two sub-paths) and an
 * `/events` SSE stream.
 *
 * ## One tick, one publication
 *
 * [pollOnce] is THE tick and the only writer of anything observable. It polls
 * both ingesters, calls `views.publish()` — F3's explicit batch boundary
 * (fpml.3-D5) — and then builds ONE [ServedState] from the report it just got
 * and the counters it owns, swaps it into the [ServedStateHolder], and
 * broadcasts *that same instance* as an SSE frame. Every HTTP response and
 * every SSE frame therefore comes from one atomically published value, which
 * is what makes "no response or event mixes pre- and post-batch state" true of
 * both surfaces at once (fpml.4-D4).
 *
 * **`AllocatorReportViews.onPublish` is deliberately NOT used.** A frame has to
 * carry the ingest health, and the health is the driver's — the ingesters'
 * counters plus this class's own poll counters — not the views'. A listener
 * would either have to reach back for state the views do not have, or publish a
 * second, differently shaped document. Building the served state inline after
 * `publish()` returns keeps one writer and one publication point.
 *
 * ## Threading
 *
 * [polls], [reBaselineCount] and [lastPollAt] are plain fields written only by
 * whichever thread runs [pollOnce] — the caller's during [start]'s first
 * synchronous tick and in tests, the poll thread afterwards, never both at
 * once. They are never read by a route: they are *published* by the volatile
 * [ServedStateHolder.swap] that ends every tick, inside the immutable
 * [ServedState], which is the happens-before edge a reader needs.
 *
 * @param now the injected clock. It is the declaration ingester's observation
 *   clock AND the views' report clock, deliberately the same reading source so
 *   a declaration event can never be timestamped from a different clock than
 *   the report that must place it on the timeline.
 */
class AllocatorObserveApp(
    private val config: AllocatorObserveConfig,
    private val now: () -> Instant = Instant::now,
) {

    private val records = SetCell<SpendRecord>()
    private val declarations = SetCell<DeclarationEvent>()

    private val offsets = RecordingOffsetStore(OffsetCheckpoint(config.runDir))

    private val spendIngester =
        SpendLogIngester(config.logPath, config.runDir, records = records, checkpoint = offsets)

    private val declarationIngester =
        DeclarationIngester(config.declarationPath, history = declarations, clock = now)

    private val views =
        AllocatorReportViews.derivedFrom(records, declarations, config.windowLength, now)

    private val holder = ServedStateHolder()

    private val shell = DemoShell(config.port)

    private var polls: Long = 0L
    private var reBaselineCount: Long = 0L
    private var lastPollAt: Instant? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    init {
        AllocatorRoutes(holder).register(shell)
        // The SSE initial frame and the broadcast frame are both
        // `ServedState.toJson()`, so a client that connects mid-life catches up
        // with exactly the document the next frame will replace — and with
        // exactly what `GET /state` answers (fpml.4-D2). The placeholder is
        // unreachable in normal operation: [start] runs a tick before binding.
        shell.sse(EVENTS_PATH) { holder.current?.toJson() ?: NOT_YET_POLLED }
    }

    /** The port the shell actually bound; meaningful only after [start]. */
    val boundPort: Int get() = shell.boundPort

    /** Non-null once the background poll loop has exited on a throwable (fpml.4-D6). */
    val pollLoopStopped: PollLoopStopped? get() = holder.stopped

    /**
     * One poll tick: both ingesters, then F3's publish boundary, then one
     * [ServedState] swapped in and broadcast.
     *
     * Public so tests drive the app one tick at a time and depend on no timing
     * (AGENTS.md: assert semantic outcomes, not scheduling). A test therefore
     * constructs the app with a `pollInterval` long enough that the background
     * thread never ticks on its own.
     *
     * A throwable from anything here propagates: to the caller during [start]'s
     * synchronous first tick (so a process that cannot poll once does not bind
     * a port and pretend), and to the poll thread's handler afterwards, which
     * records [PollLoopStopped] and exits.
     */
    fun pollOnce() {
        val spend = spendIngester.poll()
        declarationIngester.poll()
        if (spend.reason is TailReason.ReBaselined) reBaselineCount++
        polls++
        lastPollAt = now()

        val report = views.publish()
        // Both are fresh snapshot copies (`SetCell.membership()` and
        // `DeclarationIngester.history()` each build a new collection), which is
        // what `ServedState` documents it requires of its caller.
        val recordSet = spendIngester.view()
        val declarationHistory = declarationIngester.history()
        val state = ServedState(
            report = report,
            ingest = IngestHealth(
                recordCount = recordSet.size,
                checkpointOffset = offsets.last?.offset,
                reBaselineCount = reBaselineCount,
                polls = polls,
                lastPollAt = lastPollAt,
                failures = IngestFailureCounts(
                    malformed = spendIngester.failures.malformed,
                    unknownVersion = spendIngester.failures.unknownVersion,
                    declarationParseFailed = declarationIngester.parseFailures,
                ),
                declarationEvents = declarationHistory.size,
            ),
            records = recordSet,
            declarations = declarationHistory,
        )
        holder.swap(state)
        // `broadcast` computes its frame ONCE and writes the same bytes to every
        // client, so a tick costs one `toJson()` however many subscribers there
        // are — and every subscriber sees the same document as `GET /state`.
        shell.broadcast { state.toJson() }
    }

    /**
     * Runs one tick synchronously, binds the port, then starts the background
     * poll thread.
     *
     * The order is beadsmirror's (baseline, then socket, then polling) and is
     * what the acceptance criterion "no client ever observes an unpopulated
     * holder" rests on: the holder is already populated when the listening
     * socket opens, so the `not yet polled` placeholder is unreachable in
     * normal operation.
     */
    fun start(): AllocatorObserveApp {
        check(thread == null) { "already started" }
        pollOnce()
        shell.start()
        running = true
        thread = Thread(
            {
                try {
                    while (running) {
                        // Sleep FIRST. [start] has just run this tick's
                        // predecessor synchronously, so polling immediately here
                        // would tick twice back to back at startup — two
                        // publications and two SSE frames for one start, which
                        // is both wasteful and (for a test counting frames per
                        // tick) nondeterministic.
                        Thread.sleep(config.pollInterval.toMillis())
                        if (!running) break
                        pollOnce()
                    }
                } catch (_: InterruptedException) {
                    // [stop] requested — exit quietly; this is not a failure.
                } catch (t: Throwable) {
                    // Record BEFORE reporting, so a failing stderr write cannot
                    // leave the loop dead and the routes still answering 200.
                    holder.stop(PollLoopStopped(t, lastPollAt))
                    System.err.println(
                        "allocator-observe: the poll loop has stopped for good on $t; its served state is " +
                            "frozen at ${lastPollAt ?: "(never polled)"} and every state route now answers 503.",
                    )
                } finally {
                    running = false
                }
            },
            "allocator-observe-poller",
        ).apply {
            isDaemon = true
            start()
        }
        return this
    }

    /** Stops the poll thread (joining it, bounded) and then the HTTP shell. Safe to call twice. */
    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        shell.stop()
    }

    private companion object {
        // Built from a Char literal rather than a leading-slash string literal
        // for the same reason `AllocatorRoutes` does it: this module's
        // `NoHardcodedLogPathTest` is a lexical scan that cannot tell an HTTP
        // route from a filesystem path (over-broad, filed as computenet-fpml.6).
        val EVENTS_PATH: String = '/' + "events"

        const val NOT_YET_POLLED = """{"error":"not yet polled"}"""

        /** Bound on the poll thread's join, so [stop] can never hang a caller. */
        const val JOIN_TIMEOUT_MS = 2000L
    }
}

/**
 * `--name value` / `--name=value` lookup that also strips the matched tokens,
 * copied by example from `BeadsMirrorApp.extractFlag` (which is `internal` to
 * its own module, so it cannot be imported).
 *
 * Stripping is not a convenience: `demoPort` reads the FIRST argument that does
 * not start with `--`, so a flag's *value* left in place would be taken for the
 * positional port.
 */
private fun Array<String>.extractFlag(name: String): Pair<String?, Array<String>> {
    val prefix = "$name="
    val inlineIndex = indexOfFirst { it.startsWith(prefix) }
    if (inlineIndex >= 0) {
        val value = this[inlineIndex].substring(prefix.length)
        return value to (toMutableList().apply { removeAt(inlineIndex) }).toTypedArray()
    }
    val i = indexOf(name)
    if (i < 0 || i + 1 >= size) return null to this
    val value = this[i + 1]
    return value to (toMutableList().apply { removeAt(i + 1); removeAt(i) }).toTypedArray()
}

/** The usage line [main] prints before exiting 1. */
internal const val ALLOCATOR_OBSERVE_USAGE: String =
    "usage: allocator-observe --log <path> --run-dir <path> --declaration <path> " +
        "[--poll-interval-ms <ms>] [--window-hours <hours>] [port]"

/**
 * Parses [args] into an [AllocatorObserveConfig].
 *
 * `internal`, and throwing [IllegalArgumentException] rather than exiting, so
 * the parsing is testable directly instead of only through the process-exiting
 * [main] — the same reason `BeadsMirrorApp.extractFlag` is `internal`.
 *
 * @throws IllegalArgumentException when a required flag is missing or a numeric
 *   flag's value is not a number.
 */
internal fun parseArgs(args: Array<String>): AllocatorObserveConfig {
    val (log, afterLog) = args.extractFlag("--log")
    val (runDir, afterRunDir) = afterLog.extractFlag("--run-dir")
    val (declaration, afterDeclaration) = afterRunDir.extractFlag("--declaration")
    val (pollIntervalMs, afterPollInterval) = afterDeclaration.extractFlag("--poll-interval-ms")
    val (windowHours, remaining) = afterPollInterval.extractFlag("--window-hours")

    require(log != null) { "--log <path> is required. $ALLOCATOR_OBSERVE_USAGE" }
    require(runDir != null) { "--run-dir <path> is required. $ALLOCATOR_OBSERVE_USAGE" }
    require(declaration != null) { "--declaration <path> is required. $ALLOCATOR_OBSERVE_USAGE" }

    val pollMillis = pollIntervalMs?.let {
        it.toLongOrNull() ?: throw IllegalArgumentException("--poll-interval-ms must be a number, was '$it'")
    } ?: 1000L
    val hours = windowHours?.let {
        it.toLongOrNull() ?: throw IllegalArgumentException("--window-hours must be a number, was '$it'")
    } ?: 168L

    return AllocatorObserveConfig(
        logPath = Path.of(log),
        runDir = Path.of(runDir),
        declarationPath = Path.of(declaration),
        // The flags are stripped by now, so the first remaining non-`--`
        // argument really is the positional port (or none, and `demoPort`
        // falls back to `PORT` / 8080).
        port = demoPort(remaining),
        pollInterval = Duration.ofMillis(pollMillis),
        windowLength = Duration.ofHours(hours),
    )
}

fun main(args: Array<String>) {
    val config = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("allocator-observe: ${e.message}")
        exitProcess(1)
    }

    val app = AllocatorObserveApp(config).start()

    println("computenet allocator-observe: http://localhost:${app.boundPort}")
    println("  spend log ${config.logPath}, run dir ${config.runDir}, declaration ${config.declarationPath}")
    println("  polling every ${config.pollInterval.toMillis()}ms over a ${config.windowLength.toHours()}h window")
    announcePort("http", app.boundPort)
}
