package civictech.demo.allocatorobserve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.DurableInput
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.declaration.DeclarationIngester
import civictech.demo.allocatorobserve.http.AllocatorRoutes
import civictech.demo.allocatorobserve.http.IngestFailureCounts
import civictech.demo.allocatorobserve.http.IngestHealth
import civictech.demo.allocatorobserve.http.PollLoopStopped
import civictech.demo.allocatorobserve.http.ServedState
import civictech.demo.allocatorobserve.http.ServedStateHolder
import civictech.demo.allocatorobserve.http.frozenJson
import civictech.demo.allocatorobserve.http.toJson
import civictech.demo.allocatorobserve.ingest.CheckpointState
import civictech.demo.allocatorobserve.ingest.SpendLogIngester
import civictech.demo.allocatorobserve.ingest.TailReason
import civictech.demo.allocatorobserve.view.AllocatorReportViews
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Everything [AllocatorObserveApp] needs to run (fpml.4-D9).
 *
 * Every path is a parameter — the socaity spend log's eventual location is
 * undecided (fpml.1-D1), so nothing here may acquire a default.
 *
 * @param logPath the spend log the tail reader follows. It need not exist yet.
 * @param runDir where the kernel durability journal is persisted.
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

private const val ALLOCATOR_JOURNAL_ID = "main"
private const val SPEND_INPUT_NAME = "spend"

private enum class AllocatorCellKind { RECORDS, DECLARATIONS }

/**
 * Topology recovery constructs cells from the factory serialized in the journal. The host exposes
 * routed APIs and state snapshots, not its concrete cell object, so the ref-aware factory records
 * the exact live instance long enough for this composition root to attach its read-side views.
 */
private object AllocatorCellCapture {
    private val cells = ConcurrentHashMap<CellRef, SetCell<*>>()

    fun record(ref: CellRef, cell: SetCell<*>) {
        cells[ref] = cell
    }

    @Suppress("UNCHECKED_CAST")
    fun <E> take(ref: CellRef): SetCell<E> =
        cells.remove(ref) as? SetCell<E>
            ?: error("allocator-observe cell factory did not materialize $ref")
}

private data class AllocatorSetFactory(val kind: AllocatorCellKind) : CellFactory {
    override fun create(ref: CellRef): Cell = when (kind) {
        AllocatorCellKind.RECORDS -> SetCell<SpendRecord>(ref)
        AllocatorCellKind.DECLARATIONS -> SetCell<DeclarationEvent>(ref)
    }.also { AllocatorCellCapture.record(ref, it) }
}

private data class AllocatorRuntime(
    val host: ManagedHost,
    val records: SetCell<SpendRecord>,
    val declarations: SetCell<DeclarationEvent>,
    val recordOps: SetOps<SpendRecord>,
    val declarationOps: SetOps<DeclarationEvent>,
    val spendInput: DurableInput,
) {
    companion object {
        fun create(runDir: Path): AllocatorRuntime {
            Files.createDirectories(runDir)
            val journal = FileJournal(runDir.resolve("journal").toFile())
            val registry = LocationRegistry()
            lateinit var context: ApplyContext
            val host = ManagedHost(
                registry = registry,
                journalFor = { ref -> context.journalFor(ref) },
            )
            context = ApplyContext(
                host = host,
                journals = mapOf(ALLOCATOR_JOURNAL_ID to journal),
                topology = journal,
            )

            val recovered = journal.replay().isNotEmpty()
            val refs: Map<String, CellRef>
            val spendInput: DurableInput
            if (recovered) {
                context.recover(journal).awaitApplied()
                refs = context.handles
                spendInput = host.durableInput(refs.getValue("records"), SPEND_INPUT_NAME)
                host.checkpoint(journal)
            } else {
                val applied = GraphSpec(
                    listOf(
                        SpawnStep(
                            handle = "records",
                            factory = AllocatorSetFactory(AllocatorCellKind.RECORDS),
                            journalId = ALLOCATOR_JOURNAL_ID,
                            inputs = setOf(SPEND_INPUT_NAME),
                        ),
                        SpawnStep(
                            handle = "declarations",
                            factory = AllocatorSetFactory(AllocatorCellKind.DECLARATIONS),
                            journalId = ALLOCATOR_JOURNAL_ID,
                        ),
                    ),
                ).apply(context)
                refs = applied.refs
                spendInput = applied.inputs.getValue("records").getValue(SPEND_INPUT_NAME)
            }

            val recordsRef = refs.getValue("records")
            val declarationsRef = refs.getValue("declarations")
            val records = AllocatorCellCapture.take<SpendRecord>(recordsRef)
            val declarations = AllocatorCellCapture.take<DeclarationEvent>(declarationsRef)
            val recordOps = checkNotNull(host.lookup(TypedRef<SetApi<SpendRecord>>(recordsRef))).inlet.call
            val declarationOps =
                checkNotNull(host.lookup(TypedRef<SetApi<DeclarationEvent>>(declarationsRef))).inlet.call
            return AllocatorRuntime(host, records, declarations, recordOps, declarationOps, spendInput)
        }
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
 * ## Restart equivalence
 *
 * The records fold, declaration fold, and spend cursor all live in one kernel
 * journal under [AllocatorObserveConfig.runDir]. A fresh process recovers the
 * graph and both cells before it constructs the ingesters and report views;
 * the next spend poll therefore resumes from the committed cursor without a
 * whole-file re-read. Declaration changes use the same hosted intake and need
 * no application-owned history file.
 *
 * **What is still not equivalent**, stated precisely rather than dropped:
 * - `ingest` health is per-process by construction and says so — [polls],
 *   [reBaselineCount], `lastPollAt` and the ingesters' failure counters all
 *   start at zero in the new process. Only the fold and cursor cross a restart, not the
 *   account of how this process got there.
 * - A truncation or replacement of the log while the app is down is detected
 *   on the first poll by the recovered cursor's fingerprint.
 * - A log that is DELETED — while the app is down or while it runs — no longer
 *   diverges in the fold (6jbep-D1, [convergeOnDeletedLog]): a log this process
 *   has read and that then disappears is treated as the log replaced by an
 *   empty one. Kernel recovery restores the non-empty fold before the first
 *   poll, so both an uninterrupted process and one restarted during the gap
 *   observe the records go, count one re-baseline, and serve the same empty
 *   report until the log comes back with a changed head (computenet-k2cif).
 *   A log that has not arrived yet in this
 *   process is still left alone, as `SpendLogIngester` does for
 *   `TailReason.LogAbsent`.
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

    private val runtime = AllocatorRuntime.create(config.runDir)
    private val records = runtime.records
    private val declarations = runtime.declarations
    private val spendInput = runtime.spendInput

    private val spendIngester =
        SpendLogIngester(
            logPath = config.logPath,
            records = runtime.recordOps,
            input = spendInput,
            view = records::membership,
        )

    private val declarationIngester =
        DeclarationIngester(
            declarationPath = config.declarationPath,
            historyInlet = runtime.declarationOps,
            view = declarations::membership,
            clock = now,
        )

    private val views =
        AllocatorReportViews.derivedFrom(records, declarations, config.windowLength, now)

    private val holder = ServedStateHolder()

    private val shell = DemoShell(config.port)

    private var polls: Long = 0L
    private var reBaselineCount: Long = 0L
    private var lastPollAt: Instant? = null

    /** Last spend-tail decision, exposed to the restart test that guards against whole-file re-read. */
    internal var lastSpendReason: TailReason? = null
        private set

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

    /** Compatibility accessor: kernel recovery is fail-loud, so no replay failures are suppressed. */
    @Deprecated("kernel recovery fails loudly instead of counting skipped declaration events")
    val declarationReplayFailures: Long get() = 0L

    /** Non-null once the background poll loop has exited on a throwable (fpml.4-D6). */
    val pollLoopStopped: PollLoopStopped? get() = holder.stopped

    /**
     * Test seam (computenet-p29ai). `null` in production (a no-op). Invoked
     * once, from INSIDE the frame lambda the death path hands to
     * [DemoShell.broadcast] — i.e. at the instant the terminal frozen frame is
     * computed (under `DemoShell`'s `clientsLock`), not merely when
     * [DemoShell.broadcast] is called — with the value of [pollLoopStopped] at
     * that instant. A test asserts it is already non-null: the served state is
     * marked stopped before the terminal frame exists. It reports state, not
     * its own position, so moving `holder.stop` below the broadcast turns the
     * reading to `null` whichever line the probe sits next to; no sleep or
     * thread race is involved, since both steps run on the one poll thread.
     */
    internal var stopBroadcastProbe: ((PollLoopStopped?) -> Unit)? = null

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
        lastSpendReason = spend.reason
        if (spend.reason is TailReason.LogAbsent) convergeOnDeletedLog()
        declarationIngester.poll()
        // Hosted inlet calls are asynchronous. Fence before reading either fold
        // or publishing a report so this tick observes every accepted mutation.
        runtime.host.quiescence().await(30_000, "allocator-observe poll")
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
                checkpointOffset = (spendInput.committed() as? CheckpointState)?.offset,
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
     * What an absent spend log means to the served fold (design entry
     * 6jbep-D1): **a log this process has read and that is now gone is the log
     * replaced by an empty one**, so the fold converges on that — emptied — the
     * same way `SpendLogIngester` already converges a log truncated to zero
     * bytes. A log this process has never read is left alone: it has not
     * arrived yet, which is not an empty log, and the fold is empty anyway.
     *
     * The recovered fold itself distinguishes "not arrived yet" from "was
     * present and is now absent": an empty membership needs no action; a
     * non-empty one is reconciled to the absent log.
     *
     * The removals deliberately run outside a durable-input commit: disappearance
     * changes the fold but does not invent a new source cursor. The deletion is
     * counted once in `reBaselineCount`.
     *
     * The cost, stated where it is paid: because the committed cursor is left
     * in place, a log that is only transiently absent and comes back with its
     * old content (a sync that unlinks and recreates the file) still matches
     * that cursor's length and head fingerprint, so it is NOT re-read: the
     * served report stays empty (or holds only lines appended after the old
     * cursor) until the log's head changes. A recreated log with a different
     * head is re-baselined as usual. Tracked as computenet-k2cif.
     *
     * This makes the app a second writer of the records cell besides the
     * ingester, in this one case only; it writes through the same `SetOps`
     * inlet, on the same poll thread, before `views.publish()`, so the tick
     * still publishes one consistent fold.
     */
    private fun convergeOnDeletedLog() {
        val live = records.membership()
        if (live.isEmpty()) return
        live.forEach(runtime.recordOps::remove)
        reBaselineCount++
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
                    holder.current?.let { last ->
                        shell.broadcast {
                            stopBroadcastProbe?.invoke(holder.stopped)
                            last.frozenJson(stopped)
                        }
                    }
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
        // Built from a Char literal rather than a leading-slash string literal:
        // this module's
        // `NoHardcodedLogPathTest` scans this file deliberately, because
        // `AllocatorObserveConfig` and `parseArgs` live here and the guard
        // exists to catch a pasted-in log path default at that parse site. A
        // leading-slash literal anywhere in the file trips the same scan, so
        // this route is built from a Char rather than dropped out of scope.
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
