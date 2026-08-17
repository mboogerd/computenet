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
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.beadsmirror.sanitizedDoltDatabaseName
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
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

    /** The listening node. Available after [startListener]. */
    val listener: Node get() = checkNotNull(listenerNode) { "startListener() has not run yet" }

    /** The dialing node. Available after [startDialer]. */
    val dialer: Node get() = checkNotNull(dialerNode) { "startDialer() has not run yet" }

    private val started: List<Node> get() = listOfNotNull(listenerNode, dialerNode)

    /**
     * Starts node L: `--listen 0`, so it binds a port of its own choosing and
     * [BeadsMirrorApp.boundWsPort] is the only place that knows which
     * (computenet-dqy.25 — a pre-picked number would be a port nobody bound).
     */
    fun startListener(): Node {
        check(listenerNode == null) { "the listener is already started" }
        val node = start(MirrorCellRefs.LISTENER, listenerWorkspace, MirrorWire.Listen(0))
        checkNotNull(node.app.boundWsPort) { "a listening node must have bound a ws port" }
        listenerNode = node
        return node
    }

    /** Starts node D against the listener's **bound** ws port. */
    fun startDialer(): Node {
        check(dialerNode == null) { "the dialer is already started" }
        val wsPort = checkNotNull(listener.app.boundWsPort) { "the listener has no bound ws port" }
        val node = start(MirrorCellRefs.DIALER, dialerWorkspace, MirrorWire.Dial("ws://localhost:$wsPort"))
        dialerNode = node
        return node
    }

    private fun start(role: String, workspace: BdScratchWorkspace, wire: MirrorWire): Node {
        val runDir = tempDir("beadsmirror-tworig-$role-run-")
        // Captured per node, and created BEFORE the app starts: a start always
        // re-baselines, so the FirstStart event is emitted inside
        // BeadsMirrorApp.start and a list installed afterwards would miss it.
        // Synchronized because the poller thread appends while the test thread
        // reads (task computenet-7em.4.3).
        val events = Collections.synchronizedList(mutableListOf<MirrorEvent>())
        val app = BeadsMirrorApp.start(
            BeadsMirrorConfig(
                workspace = workspace.root,
                pollInterval = pollInterval,
                runDir = runDir,
                repoSearchRoot = searchRoot,
                onEvent = { events += it },
                peering = MirrorPeeringSettings(rigName, wire),
                peeringTransport = transport,
            ),
        )
        return Node(role, workspace, app, runDir, events)
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
     * [awaitUntil] with both nodes' diagnostics folded into the failure —
     * the in-process counterpart of [JvmPeer.await][civictech.testkit.JvmPeer]'s
     * child-output folding in [TwoJvmMirrorTest], and for the same reason: every
     * way convergence can fail here (a poll loop dead on an unserializable
     * payload — computenet-7em.1.5's defect — a socket that never linked, a
     * fold frozen at 503) is invisible in a bare "timed out awaiting: …", and
     * the state that names it is one field read away.
     */
    fun await(what: String, timeoutMs: Long = AWAIT_CONVERGENCE_MS, condition: () -> Boolean) {
        try {
            awaitUntil(what, timeoutMs, condition)
        } catch (e: AssertionFailedError) {
            throw AssertionFailedError("$what\n${diagnostics()}", e)
        }
    }

    private fun diagnostics(): String = started.joinToString("\n") { node ->
        "  ${node.role}: pollerFailure=${node.app.pollerFailure}, " +
            "http=${runCatching { node.servedFold() }.getOrElse { "unreadable: $it" }}"
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
        fun quiesce() {
            val feed = DoltCommitFeed(workspace.doltRoot)
            awaitUntil("$role reaches its own workspace's head commit") {
                app.pollerFailure == null && checkpoint() == feed.history().last()
            }
            check(app.pollerFailure == null) { "$role's poll loop died: ${app.pollerFailure}" }
        }

        private fun checkpoint(): String? =
            runDir.resolve("checkpoint").takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }

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
