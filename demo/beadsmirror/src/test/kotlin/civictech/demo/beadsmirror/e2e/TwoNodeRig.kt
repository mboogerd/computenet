package civictech.demo.beadsmirror.e2e

import civictech.cell.Timestamp
import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.BeadsMirrorApp
import civictech.demo.beadsmirror.BeadsMirrorConfig
import civictech.demo.beadsmirror.MirrorPeeringSettings
import civictech.demo.beadsmirror.MirrorTransport
import civictech.demo.beadsmirror.MirrorWire
import civictech.demo.beadsmirror.WsMirrorTransport
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.EchoGate
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.beadsmirror.sanitizedDoltDatabaseName
import civictech.demo.beadsmirror.writeback.WriteBackEvent
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.UUID

/**
 * Task computenet-7em.1.3: the **in-test** two-node rig — two
 * [BeadsMirrorApp]s in ONE test JVM, each on its own
 * [civictech.cell.host.ManagedHost], connected through a **real**
 * `:wire` WebSocket socket.
 *
 * This is feature computenet-7em.1's decided allowance ("nodes may run as two
 * ManagedHosts in one test JVM connected through a real WsTransport socket
 * where two full JVMs are impractical"). The two-full-JVM launch path is a
 * different test and stays that way: [TwoJvmMirrorTest], task
 * computenet-7em.1.4. What this rig buys over that one is *reach* — the test
 * thread holds both apps' [MirrorProjector]s, so a dot's `sourceId` and a
 * delta re-injected at the [civictech.cell.data.Replicable] seam are
 * assertable, and neither is reachable across a process boundary.
 *
 * **Everything is scratch.** Both workspaces are [BdScratchWorkspace]s
 * (`bd --sandbox init` into a fresh temp directory) and `repoSearchRoot`
 * points at a throwaway tree, so the app's live-`.beads` refusal has nothing
 * of this repository to find — epic computenet-dqj §4: never the live
 * tracker.
 *
 * **Three-step lifecycle, deliberately not one call.** [startListener] and
 * [startDialer] are separate because the interesting orderings are between
 * them: the dialer can only be told the listener's **bound** ws port after the
 * listener bound one (computenet-dqy.25), and a `bd` mutation applied to the
 * listener's workspace *before* the dialer exists is the only way to make the
 * late-join `pullServe` baseline — as opposed to a live delta — the sole path
 * by which that state can reach the dialer.
 *
 * **Bounded waits only** ([await], on `testkit`'s [awaitUntil]); no sleeps.
 */
class TwoNodeRig private constructor(
    val rigName: String,
    val listenerWorkspace: BdScratchWorkspace,
    val dialerWorkspace: BdScratchWorkspace,
    private val pollInterval: Duration,
    /**
     * The rig's transport wiring, injected into BOTH nodes (task
     * computenet-7em.2.1, made a rig parameter by computenet-7em.2.3) — one
     * instance, because a partition is a property of the peering rather than
     * of either node, so the object that severs it has to be the one that
     * established both ends.
     *
     * This is the seam the DSC0 iroh re-run turns, and it is a **constructor
     * parameter** rather than a field expression precisely so that turning it
     * costs no edit to this file: [create]'s default is
     * [civictech.demo.beadsmirror.WsMirrorTransport], the only binding that
     * exists today, and a future transport is passed in by whatever
     * constructs the rig (a sibling of [WsConvergenceSuiteTest] supplying a
     * different `newRig`). What the seam does NOT deliver is transport
     * neutrality of the *rig's own* test file set: this class and every test
     * that names [create] without an argument still get the WebSocket
     * binding, and [ConvergenceDivergenceControlTest] uses the same parameter
     * to inject a deliberately defective wrapper around it.
     *
     * The near-zero reconnect backoff of [create]'s default is the same T12
     * seam `:wire`'s own reconnect tests use: a heal then costs scheduling
     * rather than the production 1s-doubling wall clock, and a dropped socket
     * the rig did not ask for is retried promptly instead of on a
     * real-network schedule.
     */
    private val transport: MirrorTransport,
) : AutoCloseable {

    private val tempDirs = mutableListOf<Path>()
    private val searchRoot = tempDir("beadsmirror-tworig-searchroot-")

    private var listenerNode: Node? = null
    private var dialerNode: Node? = null

    /** This rig's poll cadence, in milliseconds — for a caller that needs to size a bounded sleep against it. */
    fun pollIntervalMs(): Long = pollInterval.toMillis()

    /** The listening node. Available after [startListener]. */
    val listener: Node get() = checkNotNull(listenerNode) { "startListener() has not run yet" }

    /** The dialing node. Available after [startDialer]. */
    val dialer: Node get() = checkNotNull(dialerNode) { "startDialer() has not run yet" }

    private val started: List<Node> get() = listOfNotNull(listenerNode, dialerNode)

    /**
     * Starts node L: `--listen 0`, so it binds a port of its own choosing and
     * [BeadsMirrorApp.boundWsPort] is the only place that knows which
     * (computenet-dqy.25 — a pre-picked number would be a port nobody bound).
     *
     * @param writeBack task computenet-6wc.1.5: starts this node's mirror with
     *   `--write-back` on. `false` (the default) is every caller before this
     *   parameter existed — nothing about the fixture changes for them.
     */
    fun startListener(writeBack: Boolean = false): Node {
        check(listenerNode == null) { "the listener is already started" }
        val node = start(MirrorCellRefs.LISTENER, listenerWorkspace, MirrorWire.Listen(0), writeBack)
        checkNotNull(node.app.boundWsPort) { "a listening node must have bound a ws port" }
        listenerNode = node
        return node
    }

    /** Starts node D against the listener's **bound** ws port. See [startListener] for [writeBack]. */
    fun startDialer(writeBack: Boolean = false): Node {
        check(dialerNode == null) { "the dialer is already started" }
        val wsPort = checkNotNull(listener.app.boundWsPort) { "the listener has no bound ws port" }
        val node = start(MirrorCellRefs.DIALER, dialerWorkspace, MirrorWire.Dial("ws://localhost:$wsPort"), writeBack)
        dialerNode = node
        return node
    }

    private fun start(role: String, workspace: BdScratchWorkspace, wire: MirrorWire, writeBack: Boolean = false): Node {
        val runDir = tempDir("beadsmirror-tworig-$role-run-")
        // Captured per node, and created BEFORE the app starts: a start always
        // re-baselines, so the FirstStart event is emitted inside
        // BeadsMirrorApp.start and a list installed afterwards would miss it.
        // Synchronized because the poller thread appends while the test thread
        // reads (task computenet-7em.4.3).
        val events = Collections.synchronizedList(mutableListOf<MirrorEvent>())
        val writeBackEvents = Collections.synchronizedList(mutableListOf<WriteBackEvent>())
        val app = BeadsMirrorApp.start(
            BeadsMirrorConfig(
                workspace = workspace.root,
                pollInterval = pollInterval,
                runDir = runDir,
                repoSearchRoot = searchRoot,
                onEvent = { events += it },
                peering = MirrorPeeringSettings(rigName, wire),
                peeringTransport = transport,
                writeBack = writeBack,
                onWriteBackEvent = { _, event -> writeBackEvents += event },
            ),
        )
        return Node(role, workspace, app, runDir, events, writeBackEvents)
    }

    /**
     * Sever the peering between the two nodes at the transport level, so
     * neither node's deltas can reach the other until [heal] (task
     * computenet-7em.2.1).
     *
     * Delegated to the injected binding, which decides what severing means for
     * its transport — for the WebSocket binding it is the dialing end shutting
     * its connection down for good, the listener staying bound throughout (see
     * [civictech.demo.beadsmirror.WsMirrorTransport]). A test states "the
     * peering is down", never "the socket is closed", which is what lets the
     * same case run over a different transport unedited.
     *
     * Each node keeps folding its own workspace while severed; what stops is
     * the gossip between them.
     */
    fun partition() = transport.partition()

    /**
     * Re-establish what [partition] severed. Returns once the peering is
     * carrying again — convergence follows through the ordinary
     * re-announcement catch-up, so the caller still awaits the *fold*, with
     * [await], rather than assuming this call converged anything.
     */
    fun heal() = transport.heal()

    /**
     * Run a `bd` mutation on [node]'s OWN workspace and return only once that
     * mutation is a **commit in the workspace's `dolt_log`** — the only form
     * in which a mirror's feed can ever see it.
     *
     * **Why a plain `workspace.run("update", …)` is not enough, and why
     * [Node.quiesce] does not cover the gap** (bug computenet-rl2qx). `bd`
     * exits 0 when the mutation is durable in *its* terms; the matching Dolt
     * commit is not always visible in `dolt_log` by then. `quiesce` asks
     * "has my poller applied every commit up to my workspace's head" — which
     * a workspace whose head has not yet moved answers **yes, vacuously**,
     * because the checkpoint still equals the stale head. The test then
     * proceeds to await a convergence that nothing has been asked to carry
     * yet, and burns its whole budget.
     *
     * That is the shape the bug's failing run had: at the timeout BOTH nodes
     * read `checkpoint == head`, `0 commits behind`, `pollerFailure == null`
     * and 0 records classified over 30 s, with the listener's edit nowhere in
     * its own `dolt_log` — no poll loop was starved, the edit had simply
     * never become a commit for one to read (measured 2026-09-18,
     * darwin/arm64, bd 1.1.2 / dolt 2.2.3, under the loaded harness on the
     * bead).
     *
     * So this is a **precondition made explicit, not a longer budget**: the
     * caller's later awaits start from a state in which the edit demonstrably
     * exists as a commit, and a mutation that never becomes one fails here,
     * naming itself, instead of two steps later as an unexplained convergence
     * timeout.
     *
     * Polled at this rig's own poll interval rather than [awaitUntil]'s 5 ms,
     * because each check is a `dolt` subprocess.
     */
    fun mutate(node: Node, vararg bdArgs: String, timeoutMs: Long = COMMIT_VISIBLE_MS): String {
        val before = node.logHead().firstOrNull()
        val output = node.workspace.run(*bdArgs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (node.logHead().firstOrNull() == before) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionFailedError(
                    "`bd ${bdArgs.joinToString(" ")}` exited 0 on the ${node.role}'s workspace but never " +
                        "appeared in its dolt_log within ${timeoutMs}ms (head still $before) — " +
                        "nothing downstream of it can converge\n${node.progressReport(null, timeoutMs)}",
                )
            }
            Thread.sleep(pollInterval.toMillis())
        }
        return output
    }

    /**
     * [awaitUntil] with both nodes' diagnostics folded into the failure —
     * the in-process counterpart of [JvmPeer.await][civictech.testkit.JvmPeer]'s
     * child-output folding in [TwoJvmMirrorTest], and for the same reason: every
     * way convergence can fail here (a poll loop dead on an unserializable
     * payload — computenet-7em.1.5's defect — a socket that never linked, a
     * fold frozen at 503) is invisible in a bare "timed out awaiting: …", and
     * the state that names it is one field read away.
     *
     * **A timeout reports MOTION, not only state** (bug computenet-rl2qx
     * clause 2). The served folds alone cannot distinguish "a loop is dead or
     * starved" from "a loop is running and merely slower than the budget":
     * the bug this was written for timed out with both folds reading the
     * pre-edit value and `pollerFailure == null` on both nodes, and its
     * diagnosis — "a starved poll loop" — was therefore inference rather than
     * a reading. So a snapshot of every counter that advances when a loop
     * makes progress ([Node.progress]) is taken BEFORE the wait and again on
     * timeout, and the failure names each node's deltas. A node whose
     * checkpoint and record counts are unchanged across the whole budget did
     * not advance; a node whose counts moved was running and did not get far
     * enough.
     *
     * The before-snapshot is deliberately in-memory plus one small file read
     * — no `dolt` subprocess — because it runs on EVERY await in the module,
     * including the ones that return immediately. The subprocess reads
     * (`dolt_log` head, the served fold) happen only on the failure path.
     */
    fun await(what: String, timeoutMs: Long = AWAIT_CONVERGENCE_MS, condition: () -> Boolean) {
        val before = started.associate { it.role to it.progress() }
        try {
            awaitUntil(what, timeoutMs, condition)
        } catch (e: AssertionFailedError) {
            throw AssertionFailedError("$what\n${diagnostics(before, timeoutMs)}", e)
        }
    }

    private fun diagnostics(before: Map<String, Node.Progress>, timeoutMs: Long): String =
        started.joinToString("\n") { node ->
            node.progressReport(before[node.role], timeoutMs)
        }

    /** Best-effort teardown: probes, apps (which close their sockets), workspaces, temp dirs. */
    override fun close() {
        started.forEach { runCatching { it.close() } }
        runCatching { listenerWorkspace.close() }
        runCatching { dialerWorkspace.close() }
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { tempDirs.add(it) }

    /**
     * One node of the rig: its scratch workspace, its running [BeadsMirrorApp],
     * and the two surfaces the acceptance tests read it through — the served
     * HTTP fold and the projector's own cells.
     */
    class Node internal constructor(
        /** [MirrorCellRefs.LISTENER] or [MirrorCellRefs.DIALER]. */
        val role: String,
        /** The `bd` workspace this node — and ONLY this node — mirrors. */
        val workspace: BdScratchWorkspace,
        val app: BeadsMirrorApp,
        private val runDir: Path,
        private val capturedEvents: MutableList<MirrorEvent>,
        private val capturedWriteBackEvents: MutableList<WriteBackEvent> = Collections.synchronizedList(mutableListOf()),
    ) : AutoCloseable {

        private val probe = HttpProbe("http://localhost:${app.boundPort}")

        /** This node's own DemoShell port; the two nodes are independently addressable. */
        val httpPort: Int get() = app.boundPort

        /** The live projector — re-read per call, because a re-baseline replaces it wholesale. */
        val projector: MirrorProjector get() = app.state.current

        /**
         * The dot `sourceId` this node's [DotMinter] mints under: a pure
         * function of its workspace's Dolt database identity
         * ([sanitizedDoltDatabaseName]), which is exactly what feature rule 4
         * says a dot's provenance must name.
         */
        val dotSourceId: UUID = DotMinter(sanitizedDoltDatabaseName(workspace.root)).sourceId

        /**
         * Every [MirrorEvent] this node's [BeadsMirrorConfig.onEvent] has
         * received so far, oldest first, as an immutable snapshot (task
         * computenet-7em.4.3).
         *
         * This is the *typed* surface a re-baseline is observed through — a
         * test asserts `Rebaselined(reason = HistoryMerged(..))` rather than
         * inferring a rebuild from log prose or from a fold that happens to
         * change. The list starts with the [civictech.demo.beadsmirror.baseline.RebaselineReason.FirstStart]
         * event every node emits during [BeadsMirrorApp.start], so a test
         * looking for a later re-baseline filters by reason rather than by
         * emptiness.
         */
        fun events(): List<MirrorEvent> = synchronized(capturedEvents) { capturedEvents.toList() }

        /**
         * Every [WriteBackEvent] this node's applier has emitted so far
         * (task computenet-6wc.1.5), oldest first, as an immutable snapshot —
         * empty for a node started with `writeBack = false`, since no applier
         * ever runs to emit one.
         */
        fun writeBackEvents(): List<WriteBackEvent> =
            synchronized(capturedWriteBackEvents) { capturedWriteBackEvents.toList() }

        fun view(): Map<String, Map<String, String>> = projector.view()

        fun edgeView(): Set<MirrorEdge> = projector.edgeView()

        /** The fold **as served**, verbatim: the response body of `GET /beads/issues`. */
        fun servedFold(): String = probe.get("/beads/issues").body()

        /** The status of `GET /beads/issues/{id}` — `200` once this node's fold carries [issueId]. */
        fun servedStatus(issueId: String): Int = probe.get("/beads/issues/$issueId").statusCode()

        /** `bd export` of this node's OWN workspace — the baseline every node's fold is compared against. */
        fun exportNow(): List<ExportRow> = BdExportReader(workspace.root).read()

        /** This workspace's `dolt_log` commit hashes, newest first — `bd`-level state, untouched by gossip. */
        fun logHead(): List<String> =
            DoltSql(workspace.doltRoot).query("select commit_hash from dolt_log")
                .map { it.getValue("commit_hash").jsonPrimitive.content }

        /**
         * Every [MirrorEvent.RecordClassified] this node's echo gate has
         * emitted, oldest first (feature computenet-6wc.3 clause 5, decision
         * 6wc.3-D6) — the per-commit echo/local verdict, read off the same
         * typed event sink as [events] rather than off the gate's counters, so
         * an assertion can name the *commit* it is talking about.
         */
        fun classifications(): List<MirrorEvent.RecordClassified> =
            events().filterIsInstance<MirrorEvent.RecordClassified>()

        /**
         * `(echoCount, localCount)` of this node's [EchoGate] — the aggregate
         * counters beside [classifications]' per-record detail. Read from the
         * live gate, so this is the mirror's own bookkeeping and not a
         * re-count of the event list.
         *
         * Reads `app.mirrors.single()`: every rig node runs exactly one
         * workspace, which is also what `TwoNodeRig` assumes everywhere else it
         * reaches into a node's mirror.
         */
        fun echoCounts(): Pair<Int, Int> =
            app.mirrors.single().echoGate.let { it.echoCount to it.localCount }

        /**
         * This workspace's `dolt_diff_issues` rows that touch [issueId] —
         * `to_commit`, `diff_type`, `from_metadata` and `to_metadata` — newest
         * commit first, ordered by this workspace's own [logHead].
         *
         * The provenance surface feature computenet-6wc.3 clause 1 is stated
         * against, read exactly the way [civictech.demo.beadsmirror.feed.DoltCommitFeed]
         * reads it (`DoltSql`, the same `'`-escaping its `narrowedQuery` uses
         * for hashes) so a test is asserting about the surface the mirror
         * itself polls rather than a second rendering of it.
         *
         * **Compare the metadata structurally, never as text.** Dolt re-orders
         * JSON object keys — `to_metadata` prints alphabetically regardless of
         * the order the row was written in (measured on this feature's
         * breakdown probe, 2026-09-18) — so a `toString()` comparison against a
         * built object is a false negative waiting to happen. The values come
         * back as [JsonElement]s for that reason.
         *
         * Both sides of the diff are matched (`to_id` or `from_id`) so a
         * removal, which has no `to_` side at all, is not silently invisible
         * here.
         */
        fun diffRowsFor(issueId: String): List<Map<String, JsonElement>> {
            val quoted = "'" + issueId.replace("'", "''") + "'"
            val rows = DoltSql(workspace.doltRoot).query(
                "select to_commit, diff_type, from_metadata, to_metadata from dolt_diff_issues " +
                    "where to_id = $quoted or from_id = $quoted",
            )
            // dolt_diff_issues has no ordering of its own that a test may rely
            // on; dolt_log does, and this node already reads it newest-first.
            val order = logHead().withIndex().associate { (index, hash) -> hash to index }
            return rows.sortedBy { row ->
                val commit = (row["to_commit"] as? JsonPrimitive)?.contentOrNull
                order[commit] ?: Int.MAX_VALUE
            }
        }

        /**
         * Every live dot this node's mirror holds for [issueId]'s keys, as
         * `key -> (dot -> value)`. Read from [civictech.cell.data.OrMapCell.state],
         * so it is the cell's own dot metadata rather than anything the test
         * reconstructs — which is what makes the provenance assertion a
         * statement about the gossip path and not about the test's bookkeeping.
         */
        fun dotsFor(issueId: String): Map<MirrorKey, Map<Timestamp, String>> =
            projector.cell.state().puts.filterKeys { it.issueId == issueId }

        /**
         * Waits until this node's persisted checkpoint reaches its workspace's
         * `dolt_log` head — the poller writes the checkpoint only *after*
         * handing the batch to the projector, so "checkpoint at head" means
         * "every record of my own workspace applied". Says nothing about
         * gossip from the peer; that is what [TwoNodeRig.await] is for.
         */
        fun quiesce(timeoutMs: Long = AWAIT_CONVERGENCE_MS) {
            val feed = DoltCommitFeed(workspace.doltRoot)
            val before = progress()
            try {
                awaitUntil("$role reaches its own workspace's head commit", timeoutMs) {
                    app.pollerFailure == null && checkpoint() == feed.history().last()
                }
            } catch (e: AssertionFailedError) {
                // Same reason as TwoNodeRig.await's: "the checkpoint never
                // reached head" is not diagnosable without knowing whether the
                // poll loop moved at all while we waited.
                throw AssertionFailedError(
                    "$role never reached its own workspace's head commit\n${progressReport(before, timeoutMs)}",
                    e,
                )
            }
            check(app.pollerFailure == null) { "$role's poll loop died: ${app.pollerFailure}" }
        }

        private fun checkpoint(): String? =
            runDir.resolve("checkpoint").takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }

        /**
         * Everything about this node that ADVANCES when one of its two loops
         * makes progress, read cheaply enough to sample on every await
         * (bug computenet-rl2qx clause 2): no `dolt` subprocess, no HTTP — one
         * small file read plus in-memory counters.
         *
         * - [checkpoint] is the poll loop's own record of the last commit of
         *   **this** workspace it has applied: it is written after the batch
         *   reaches the projector, so it moving means records were folded.
         * - [echoCount] + [localCount] is every record this node's [EchoGate]
         *   has classified since start — the poll loop's throughput counter,
         *   and the one that still moves when the checkpoint is already at
         *   head.
         * - [importerAttempts] counts the write-back events that imply a `bd
         *   import` ran ([WriteBackEvent.Imposed] and [WriteBackEvent.Failed]);
         *   [WriteBackEvent.Skipped] and `PreFlight` do not invoke the
         *   importer. It is an event-derived count rather than the applier's
         *   own `ApplyReport.importerInvocations`, which is per-pass and not
         *   retained anywhere a test can read.
         * - the two failures are the "this loop is dead" answers; both `null`
         *   with nothing advancing is the *starved* reading, which is the one
         *   the original occurrence of this bug could not distinguish.
         */
        data class Progress(
            val checkpoint: String?,
            val echoCount: Int,
            val localCount: Int,
            val pendingEchoes: Int,
            val mirrorEvents: Int,
            val writeBackEvents: Int,
            val importerAttempts: Int,
            val pollerFailure: String?,
            val writeBackFailure: String?,
        ) {
            /** Records folded through the gate — the poll loop's throughput counter. */
            val recordsClassified: Int get() = echoCount + localCount
        }

        /** This node's [Progress] right now. Cheap: one file read plus field reads. */
        fun progress(): Progress {
            val mirror = runCatching { app.mirrors.single() }.getOrNull()
            val writeBack = writeBackEvents()
            return Progress(
                checkpoint = runCatching { checkpoint() }.getOrElse { "unreadable: $it" },
                echoCount = mirror?.echoGate?.echoCount ?: -1,
                localCount = mirror?.echoGate?.localCount ?: -1,
                pendingEchoes = mirror?.echoGate?.pendingCount() ?: -1,
                mirrorEvents = events().size,
                writeBackEvents = writeBack.size,
                importerAttempts = writeBack.count { it is WriteBackEvent.Imposed || it is WriteBackEvent.Failed },
                pollerFailure = app.pollerFailure?.toString(),
                writeBackFailure = mirror?.writeBackFailure?.toString(),
            )
        }

        /**
         * This node's progress since [before], rendered for a timeout message,
         * plus the expensive reads worth paying for once a test is already
         * failing: this workspace's own `dolt_log` head (so "the checkpoint is
         * N commits behind head" is a reading) and the served fold.
         *
         * The verdict line is deliberately mechanical — `POLL LOOP DID NOT
         * ADVANCE` only when neither the checkpoint nor the classified-record
         * count moved across the whole budget — so the next occurrence names
         * the starved loop instead of leaving it to be inferred.
         */
        internal fun progressReport(before: Progress?, timeoutMs: Long): String {
            val now = progress()
            val head = runCatching { logHead().firstOrNull() }.getOrElse { "unreadable: $it" }
            val behind = runCatching {
                val log = logHead()
                now.checkpoint?.let { cp -> log.indexOf(cp).takeIf { it >= 0 } }
            }.getOrNull()
            val pollMoved = before == null ||
                now.checkpoint != before.checkpoint || now.recordsClassified != before.recordsClassified
            val writeBackMoved = before == null ||
                now.writeBackEvents != before.writeBackEvents || now.importerAttempts != before.importerAttempts
            fun delta(from: Int?, to: Int) = if (from == null) "$to" else "$from->$to (${plus(to - from)})"
            return buildString {
                append("  $role over ${timeoutMs}ms:")
                append(" poll=${if (pollMoved) "advanced" else "DID NOT ADVANCE"}")
                append(" writeBack=${if (writeBackMoved) "advanced" else "did not advance"}\n")
                append("    checkpoint=${before?.checkpoint ?: "?"}->${now.checkpoint}")
                append(", workspace head=$head")
                append(behind?.let { ", checkpoint is $it commit(s) behind head" } ?: "")
                append("\n")
                append("    recordsClassified=${delta(before?.recordsClassified, now.recordsClassified)}")
                append(" (echo=${delta(before?.echoCount, now.echoCount)},")
                append(" local=${delta(before?.localCount, now.localCount)},")
                append(" pendingEchoes=${delta(before?.pendingEchoes, now.pendingEchoes)})")
                append(", mirrorEvents=${delta(before?.mirrorEvents, now.mirrorEvents)}\n")
                append("    writeBackEvents=${delta(before?.writeBackEvents, now.writeBackEvents)}")
                append(", importerAttempts=${delta(before?.importerAttempts, now.importerAttempts)}")
                append(", pollerFailure=${now.pollerFailure}, writeBackFailure=${now.writeBackFailure}\n")
                append("    http=${runCatching { servedFold() }.getOrElse { "unreadable: $it" }}")
            }
        }

        private fun plus(n: Int): String = if (n >= 0) "+$n" else "$n"

        override fun close() {
            runCatching { probe.close() }
            runCatching { app.stop() }
        }
    }

    companion object {

        /**
         * Convergence budget for every cross-node wait — `awaitUntil`'s own
         * default. These waits cover a poll tick plus one socket hop, not a
         * process start.
         */
        const val AWAIT_CONVERGENCE_MS: Long = 30_000

        /**
         * Budget for [mutate]'s "this `bd` mutation has become a Dolt commit"
         * wait. Separate from [AWAIT_CONVERGENCE_MS], and longer, because it
         * covers something else entirely: not a poll tick plus a socket hop,
         * but `bd`'s own commit becoming visible in `dolt_log` — a subprocess
         * path that is the first thing to stretch when the machine is
         * contended, and the one the bug computenet-rl2qx harness caught
         * exceeding 30 s.
         *
         * **Not measured as a distribution.** It is a bound chosen to be
         * comfortably past the longest delay that harness observed, not a
         * percentile of one: a `bd` commit that takes minutes is a defect
         * worth failing on, and this fails on it with a message that says so.
         */
        const val COMMIT_VISIBLE_MS: Long = 120_000

        /**
         * Two fresh scratch workspaces and a rig name nothing else can collide
         * with — the rig name is hashed into the shared logical `CellRef`s
         * ([MirrorCellRefs]), so a value reused across runs sharing this JVM
         * would be the one way two unrelated rigs could link.
         */
        fun create(
            name: String,
            pollInterval: Duration = Duration.ofMillis(200),
            /**
             * The wiring both nodes are built through — defaulted to the
             * production binding, so every existing caller is unchanged, and
             * overridable so a different transport (DSC0's iroh binding) or a
             * deliberately defective wrapper
             * ([ConvergenceDivergenceControlTest]) is supplied without editing
             * this class.
             */
            transport: MirrorTransport = WsMirrorTransport(reconnectBackoff = { 10L }),
            /**
             * The two workspaces the nodes mirror — defaulted to two fresh,
             * mutually *independent* scratch workspaces, which is what every
             * caller before task computenet-7em.4.3 got and still gets.
             *
             * Passing them in is what lets a rig run on workspaces that are
             * related to each other — specifically
             * [BdScratchWorkspace.createSyncedPair]'s pusher/puller pair
             * sharing one `file://` bare Dolt remote, so a REAL `bd dolt
             * push`/`bd dolt pull` can land a peer's history in a running
             * mirror's workspace ([PullRebaselineTest]). The rig cannot mint
             * such a pair itself: the relation is between the two, and only
             * the factory that builds both knows the remote.
             *
             * Ownership is unchanged either way — [close] closes both
             * workspaces, so a caller supplying a pair may (and
             * [PullRebaselineTest] does) also close the pair, which is
             * idempotent.
             */
            listenerWorkspace: BdScratchWorkspace = BdScratchWorkspace.create(),
            dialerWorkspace: BdScratchWorkspace = BdScratchWorkspace.create(),
        ): TwoNodeRig =
            TwoNodeRig(
                rigName = "$name-${System.nanoTime()}",
                listenerWorkspace = listenerWorkspace,
                dialerWorkspace = dialerWorkspace,
                pollInterval = pollInterval,
                transport = transport,
            )
    }
}
