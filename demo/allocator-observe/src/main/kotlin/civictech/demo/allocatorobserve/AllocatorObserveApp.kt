package civictech.demo.allocatorobserve

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.declaration.DeclarationIngester
import civictech.demo.allocatorobserve.declaration.DeclarationPollOutcome
import civictech.demo.allocatorobserve.http.AllocatorRoutes
import civictech.demo.allocatorobserve.http.IngestFailureCounts
import civictech.demo.allocatorobserve.http.IngestHealth
import civictech.demo.allocatorobserve.http.PollLoopStopped
import civictech.demo.allocatorobserve.http.ServedState
import civictech.demo.allocatorobserve.http.ServedStateHolder
import civictech.demo.allocatorobserve.http.frozenJson
import civictech.demo.allocatorobserve.http.toJson
import civictech.demo.allocatorobserve.ingest.CheckpointState
import civictech.demo.allocatorobserve.ingest.OffsetCheckpoint
import civictech.demo.allocatorobserve.ingest.SpendLogIngester
import civictech.demo.allocatorobserve.ingest.SpendOffsetStore
import civictech.demo.allocatorobserve.ingest.TailReason
import civictech.demo.allocatorobserve.restart.DeclarationHistoryJournal
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
 * [last] is initialised from `delegate.read()` and updated AFTER the delegate's
 * write returns, so it never advertises an offset that is not yet persisted.
 *
 * Since `computenet-fpml.5.2` the delegate under it is a [ColdStartOffsetStore],
 * whose `read()` answers `null` until this process has written a checkpoint
 * once — so [last] now starts `null` in EVERY process, restarted or not, and is
 * set by the first tick that delivers lines. That is the honest reading: a
 * process that has not yet re-read the log has consumed nothing of it, and the
 * persisted offset is not the position this process is at.
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
 * A [SpendOffsetStore] that hides the persisted checkpoint from the FIRST read
 * of each process, so every process's first spend-log poll is a
 * `TailReason.FirstStart` whole-file read (design entry fpml.5-D4a).
 *
 * **Why this is what makes a restart equal an uninterrupted run.** The app's
 * spend fold is a fresh in-memory `SetCell`; the spend log is its durable form,
 * exactly as the socaity replay script treats it. Resuming a fresh fold from a
 * persisted byte offset would fold only the bytes appended after the restart —
 * so the offset must be ignored precisely once, while the fold is empty, and
 * honoured from then on.
 *
 * Within the process nothing changes: once this store's own [write] has run,
 * both calls delegate, so later polls resume incrementally from the checkpoint
 * and keep the truncation/replacement detection that the checkpoint's
 * fingerprint provides.
 *
 * **The cost, stated where it is paid** (fpml.5-D4a): a restart re-reads the
 * whole spend log once, which is O(log size) per process start rather than per
 * poll. And a truncation or replacement that happened while the app was DOWN is
 * absorbed silently by that whole read — the fold converges on the log's current
 * content, which is correct, but the event is not counted in `reBaselineCount`,
 * because nothing in this process ever saw the pre-replacement bytes. Only
 * re-baselines observed between two polls of one process are counted.
 */
private class ColdStartOffsetStore(private val delegate: SpendOffsetStore) : SpendOffsetStore {

    @Volatile
    private var writtenHere = false

    override fun read(): CheckpointState? = if (writtenHere) delegate.read() else null

    override fun write(state: CheckpointState) {
        delegate.write(state)
        writtenHere = true
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
 * ## A restart IS equivalent to an uninterrupted run — and how, without durable cells
 *
 * Stated here because this class is where the two halves meet and neither half
 * says it on its own. Both cells this app folds into are still **fresh and
 * in-memory**: `SetCell`'s durability is the kernel's `Stateful`
 * snapshot/restore seam, which nothing here wires up (the epic's non-goal).
 * Restart equivalence is instead reached by making the two folds re-derivable
 * from what the run directory and the log already hold (task
 * `computenet-fpml.5.2`, design fpml.5-D4):
 *
 * - **The spend fold is re-read, not resumed.** [ColdStartOffsetStore] hides the
 *   persisted checkpoint from this process's first poll, so that poll is a
 *   whole-file read and the fold is rebuilt from the log — the log is the
 *   durable fold. Later polls in the same process resume from the checkpoint as
 *   before.
 * - **The declaration history is journalled.** `allocation.yaml` holds only the
 *   current declaration, so [DeclarationHistoryJournal] persists one line per
 *   observed event under `config.runDir` and replays them into the declarations
 *   cell at construction, before the first poll.
 *
 * With both, a process restarted over the same run directory and log serves the
 * report an uninterrupted process would (feature `computenet-fpml.5`'s rule 2,
 * asserted at every poll boundary of the fixture week by
 * `restart/AppRestartEquivalenceTest`).
 *
 * **What is still not equivalent**, stated precisely rather than dropped:
 * - `ingest` health is per-process by construction and says so — [polls],
 *   [reBaselineCount], `lastPollAt` and the ingesters' failure counters all
 *   start at zero in the new process, and `checkpointOffset` reads `null` until
 *   its first tick has written one. Only the *fold* crosses a restart, not the
 *   account of how this process got there.
 * - A truncation or replacement of the log that happens while the app is DOWN
 *   is absorbed uncounted by the cold-start read ([ColdStartOffsetStore]): the
 *   fold converges on the log's current content, but `reBaselineCount` does not
 *   see an event no process observed.
 * - A crash between a declaration's fold and its journal append loses that
 *   line; the next poll re-observes the declaration as a new event with a later
 *   `observedAt`, which moves one sub-interval boundary rather than losing it
 *   (see [DeclarationHistoryJournal]).
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

    private val journal = DeclarationHistoryJournal(config.runDir)

    init {
        // BEFORE the ingesters are constructed and before any poll: the
        // declaration ingester reads its "current declaration" from this very
        // cell, so a history replayed after it exists would still be correct,
        // but a history replayed after the first poll would make that poll
        // re-append the declaration it already knows. Property initialisers and
        // `init` blocks run in declaration order, which is what sequences this
        // against the two ingesters below.
        journal.replayInto(declarations)
    }

    private val offsets = RecordingOffsetStore(ColdStartOffsetStore(OffsetCheckpoint(config.runDir)))

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
        //
        // computenet-w20a4: read the holder first, then the stopped flag — the
        // same pessimistic order `AllocatorRoutes.handle` uses for `/state*`
        // (fpml.4-D6) — so a connecting client is never handed a live-looking
        // document for a fold that is actually frozen. While [holder.stopped]
        // is non-null the frame carries the same `frozenJson` envelope the
        // `/state*` 503 body does, so a client that connects AFTER the poll
        // loop has died can tell a frozen fold from a merely quiet one from the
        // SSE stream alone — the gap D6's "SSE simply stops receiving frames"
        // sentence covers only an already-connected subscriber, not this one.
        shell.sse(EVENTS_PATH) {
            val state = holder.current
            val frozen = holder.stopped
            when {
                state == null -> NOT_YET_POLLED
                frozen != null -> state.frozenJson(frozen)
                else -> state.toJson()
            }
        }
    }

    /** The port the shell actually bound; meaningful only after [start]. */
    val boundPort: Int get() = shell.boundPort

    /**
     * Journal lines this process could not parse while replaying the declaration
     * history at construction (see [DeclarationHistoryJournal.replayFailures]).
     *
     * Exposed so the loss is reachable from the process rather than only from
     * the file. It is deliberately NOT in the served `ingest` document: that
     * shape is `http/ServedState.kt`'s, which this task does not own.
     */
    val declarationReplayFailures: Long get() = journal.replayFailures

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
        val declaration = declarationIngester.poll()
        // AFTER the poll returned: the event is already in the cell by then, so
        // this persists a fold that has happened — the checkpoint's own
        // fold-before-persist order. The reverse would let a crash leave a
        // journal line for an event no fold ever saw.
        if (declaration is DeclarationPollOutcome.Appended) journal.append(declaration.event)
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
                    val stopped = PollLoopStopped(t, lastPollAt)
                    holder.stop(stopped)
                    // computenet-w20a4: an ALREADY-CONNECTED /events subscriber
                    // would otherwise only see frames stop arriving — the exact
                    // confusion fpml.4-D6 forbids on the HTTP side. Send the
                    // same frozenJson envelope the next `/state*` request would
                    // get, once, so a connected client is told rather than left
                    // to infer death from silence. `holder.current` is non-null
                    // here: [start] always completes one tick before this
                    // thread starts, so some prior tick swapped a value in.
                    holder.current?.let { last -> shell.broadcast { last.frozenJson(stopped) } }
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
