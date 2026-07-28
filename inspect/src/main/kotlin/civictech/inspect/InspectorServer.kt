package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.PortRef
import civictech.demo.shell.DemoShell
import civictech.demo.shell.beginSse
import civictech.demo.shell.respond
import civictech.demo.shell.sseFrame
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The ComputeNet Inspector backend (`doc/spec/90-roadmap/97-inspector-plan`):
 * a **read-only** view of the dataflow graph a host process is running, served
 * beside the application on its own port.
 *
 * M0 serves the topology vertical of `20-api-contract.md`:
 *
 * - `GET /api/inspect/topology` — a [TopologySnapshot] of the live cells and links;
 * - `GET /api/inspect/events` — the SSE delta stream (`topology.node`,
 *   `topology.link`, `lifecycle`, `heartbeat`) that keeps that snapshot current.
 *
 * M1 adds selection and state:
 *
 * - `GET /api/inspect/cell/{ref}` — a [CellDetail];
 * - `POST`/`DELETE /api/inspect/cell/{ref}/observe` — the explicit state
 *   subscription (see [Observations]);
 * - `GET /api/inspect/cell/{ref}/state` — a [CellState], plus the
 *   `state.summary` SSE events an open subscription streams.
 *
 * M2 adds the error lane (see [Errors]):
 *
 * - `GET /api/inspect/errors` — an [ErrorSnapshot];
 * - `error.deadLetter` / `error.parked` / `error.restart` SSE events.
 *
 * M3 adds the flow feed (see [FlowCollector]): the 1 Hz `flow.rates` events.
 *
 * M4 adds the multi-graph navigator (see [ComponentIndex] and [Graphs]):
 *
 * - `GET /api/inspect/graphs` — a [GraphList] of the connected components;
 * - `GET /api/inspect/search?mode=&q=` — a [SearchResult];
 * - `?graph=g-…` scoping on `GET /api/inspect/topology`;
 * - `graphs.changed` SSE events, plus `Node.graph` on every node;
 * - [nameGraph], the opt-in host-side annotation that labels a component.
 *
 * M5 adds network hosts (see [Peers]): peer-announced cells appear in topology
 * with their `Node.net` set to the connection they arrived through (and
 * `host: null` — a mirrored location carries no process host), a peer
 * disconnect retracts them, and [declareLink] reports a cross-boundary stream
 * the kernel indexes nowhere. Remote cells are topology and placement only:
 * they are not locally hosted, so state, flow and error feeds have nothing to
 * read for them and say so (`GET state` → `unavailable`, `POST observe` → 409).
 *
 * ### What it can and cannot see
 *
 * The inspector reads one [LocationRegistry]. **Cells on registry-less hosts
 * (`ManagedHost(registry = null)`) are invisible**: such a host publishes
 * nowhere, so it has no [LocationRegistry.localRefs], no
 * [LocationRegistry.locate], and no [LocationRegistry.describe] — there is
 * nothing to show and nothing to fail on. Passing such a host in [hosts] is
 * harmless; its cells simply never appear.
 *
 * ### Invariants
 *
 * Nothing here sits on the per-message data path (P2): the only feeds are the
 * registry's rare-path publish/unpublish/link/unlink hooks, plus — for a cell a
 * client explicitly asked to observe — one ordinary `ObserveCell` sink folding
 * that cell's outlet, which is a normal graph participant, not a hook. Serving
 * topology and cell detail subscribes to no cell and raises no attention (P6):
 * they read registry metadata and generated descriptors only. State is the one
 * causal read, and it is opt-in per cell and released explicitly (see
 * [Observations]). And the viz never blocks the graph: hook threads and sink
 * dispatch threads hand frames to per-client bounded queues that drop their
 * oldest frames rather than wait (see [SseBroadcaster]).
 */
class InspectorServer(
    registry: LocationRegistry,
    /**
     * The process hosts to inspect, by the name their cells report as
     * `Node.host`. A located host that is not named here falls back to a name
     * derived from its ref.
     */
    hosts: Map<String, ManagedHost>,
    port: Int = DEFAULT_PORT,
    /**
     * Optional application-supplied cell names (`Node.name`). The kernel has no
     * name registry — graph-builder handle names live in the `GraphSpec`, not
     * in the runtime — so an app that wants readable node labels passes what it
     * knows. Unnamed cells report `null`, per the contract.
     */
    cellNames: Map<CellRef, String> = emptyMap(),
    /**
     * This JVM's network-host label — the launcher's `--net-name` (M5-NET).
     * Every locally published cell reports it as `Node.net`; peer-announced
     * cells report a label derived from the connection they arrived through
     * (see [Peers]). Defaults to the contract's `"local"`, so an inspector
     * that is not told about a wider network keeps emitting exactly what M0–M4
     * emitted.
     */
    netName: String = Node.LOCAL_NET,
) : AutoCloseable {

    /** Name the hosts by ref — the convenience form when the app has no names of its own. */
    constructor(registry: LocationRegistry, hosts: Set<ManagedHost>, port: Int = DEFAULT_PORT) :
        this(registry, hosts.associateBy { "host-" + it.ref.id.toString().substringBefore('-') }, port)

    constructor(registry: LocationRegistry, host: ManagedHost, port: Int = DEFAULT_PORT) :
        this(registry, setOf(host), port)

    private val shell = DemoShell(port)
    private val broadcaster = SseBroadcaster()

    /** M5 — the network-host resolver (see [Peers]). */
    private val peers = Peers(registry, netName)

    // `flow` is declared below and read through the supplier, so the two can
    // reference each other without a construction cycle: the model asks the
    // collector for an edge's `fused`, the collector hands its windows back to
    // the model's `flowRates`. The supplier only runs after construction.
    private val model: InspectorModel =
        InspectorModel(registry, hosts, cellNames, broadcaster::publish, flow = { flow }, peers = peers)

    /** M3 — the flow feed (see [FlowCollector]); attaches taps as edges appear. */
    private val flow: FlowCollector = FlowCollector(registry, onBatch = model::flowRates)

    /**
     * The `Stateful.snapshot()` fallback, absent by default — see
     * [SnapshotSource]'s doc for why M1 ships without one. Internal: wiring it
     * is an orchestrator decision that comes with a kernel accessor, not an
     * app-facing extension point (an app-supplied source would inherit the
     * host-thread routing obligation this seam exists to honor).
     */
    internal var snapshots: SnapshotSource = SnapshotSource.Unavailable

    private val observations = Observations(
        registry = registry,
        onChange = model::stateSummary,
        // read through, so a source installed after construction takes effect
        snapshots = SnapshotSource { ref -> snapshots.snapshotOf(ref) },
    )

    /** M2 — the error lane (see [Errors]'s doc for the three sources it feeds). */
    private val errors = Errors(
        registry = registry,
        hosts = hosts,
        onDeadLetter = model::deadLetterEvent,
        onParked = model::parkedEvent,
        onRestart = model::restartEvent,
    )

    private val heartbeats = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "inspector-heartbeat").apply { isDaemon = true }
    }

    /**
     * Registry hook registrations. Held so [close] can drop them: a stopped
     * inspector must not keep a dead broadcaster attached to a live registry.
     */
    private val hooks: List<AutoCloseable> = listOf(
        registry.onLocalPublish(model::published),
        registry.onLocalUnpublish(model::unpublished),
        registry.onLocalTopology(model::linked, model::unlinked),
    )

    /**
     * False from [close] on, disarming the two any-publish hooks below.
     *
     * `LocationRegistry.onPublish`/`onUnpublish` — unlike their `onLocal…`
     * counterparts — return no deregistration handle, so a closed inspector
     * cannot detach from them. It disarms them instead: the listeners stay
     * registered on the registry and do nothing. Noted rather than fixed
     * because widening those two hooks is a kernel change, and M5-NET owns the
     * inspector module (and the pilot's wiring) only.
     */
    @Volatile
    private var attached = true

    val boundPort: Int get() = shell.boundPort

    /** Live SSE subscribers — diagnostics, and the tests' connect barrier. */
    internal val attachedClients: Int get() = broadcaster.clientCount

    init {
        // M5 — peer-announced refs arrive on the *any*-publish hooks: a
        // mirrored location is published by `RegistryMirrorCell`, which
        // deliberately never fires the local ones. Filtered to Remote
        // locations, so a local publish is not seen twice; the unpublish side
        // needs no filter, since a local ref has already left the view by the
        // time this fires and the model's removal is idempotent.
        registry.onPublish { ref -> if (attached && peers.isRemote(ref)) model.mirroredPublish(ref) }
        registry.onUnpublish { ref -> if (attached) model.unpublished(ref) }

        // hooks first, then the catch-up read: a publish racing construction is
        // seen by the hook and merely re-confirmed by the sync, never lost
        model.sync()

        shell.route(TOPOLOGY_PATH) { exchange ->
            exchange.allowCrossOrigin()
            // M4: `?graph=g-…` scopes the snapshot to one component; absent, the
            // whole process, exactly as M0 through M3 served it
            val graph = exchange.query()[GRAPH_PARAM]?.takeIf { it.isNotBlank() }
            exchange.respond(200, model.snapshotJson(graph), JSON)
        }
        shell.route(ERRORS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.respond(200, inspectorJson.encodeToString(ErrorSnapshot.serializer(), errors.snapshot()), JSON)
        }
        shell.route(GRAPHS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            val body = Graphs.list(model.components(), errors.snapshot())
            exchange.respond(200, inspectorJson.encodeToString(GraphList.serializer(), body), JSON)
        }
        shell.route(SEARCH_PATH) { exchange ->
            exchange.allowCrossOrigin()
            runCatching { serveSearch(exchange) }
                .onFailure { failure -> runCatching { exchange.respond(500, problem(failure.toString()), JSON) } }
        }
        shell.route(EVENTS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.beginSse()
            // the handler returns immediately; frames leave on the client's own
            // pump thread, so a slow reader never occupies the http dispatcher
            broadcaster.attach(
                writer = { frame -> exchange.sseFrame(frame) },
                onDetach = { runCatching { exchange.close() } },
            )
        }
        // one context for the whole /cell subtree: the JDK http server matches
        // contexts by longest path prefix, so {ref}, {ref}/state and
        // {ref}/observe all arrive here and are dispatched on method + tail
        shell.route(CELL_PATH) { exchange ->
            exchange.allowCrossOrigin()
            runCatching { serveCell(exchange) }
                .onFailure { failure -> runCatching { exchange.respond(500, problem(failure.toString()), JSON) } }
        }
    }

    /**
     * `GET /api/inspect/search?mode={name|problems|data}&q=`.
     *
     * `name` and `problems` are answered from metadata the inspector already
     * holds — component membership, cell names and types, and M2's error rows
     * — so searching subscribes to nothing and raises no attention (P6).
     * `data` is the one mode that would have to *ask cells*, which is exactly
     * why it is M5-SEARCH's problem and answers 501 here rather than a
     * plausible-looking empty result.
     */
    private fun serveSearch(exchange: HttpExchange) {
        val params = exchange.query()
        val query = params[QUERY_PARAM].orEmpty()
        when (val mode = params[MODE_PARAM]?.takeIf { it.isNotBlank() } ?: SearchResult.NAME) {
            SearchResult.NAME -> exchange.respondSearch(Graphs.byName(model.components(), query))
            SearchResult.PROBLEMS -> exchange.respondSearch(Graphs.problems(model.components(), errors.snapshot()))
            SearchResult.DATA -> exchange.respond(501, DATA_SEARCH_DEFERRED, JSON)
            else -> exchange.respond(400, problem("unknown search mode: $mode"), JSON)
        }
    }

    private fun HttpExchange.respondSearch(result: SearchResult) =
        respond(200, inspectorJson.encodeToString(SearchResult.serializer(), result), JSON)

    private fun serveCell(exchange: HttpExchange) {
        val segments = exchange.requestURI.path.removePrefix(CELL_PATH)
            .split('/').filter { it.isNotEmpty() }
        val ref = segments.firstOrNull()?.let(::decodeRef)
            ?: return exchange.respond(404, problem("expected /cell/{ref}"), JSON)
        val tail = segments.drop(1)

        when {
            tail.isEmpty() && exchange.requestMethod == "GET" -> serveDetail(exchange, ref)
            tail == listOf(STATE) && exchange.requestMethod == "GET" -> serveState(exchange, ref)
            tail == listOf(OBSERVE) && exchange.requestMethod == "POST" -> startObserving(exchange, ref)
            tail == listOf(OBSERVE) && exchange.requestMethod == "DELETE" -> {
                // idempotent: releasing what was never opened is a success
                observations.stop(ref)
                exchange.noContent()
            }

            else -> exchange.respond(404, problem("no such inspector resource"), JSON)
        }
    }

    private fun serveDetail(exchange: HttpExchange, ref: CellRef) {
        val body = model.detailJson(ref) ?: return exchange.respond(404, problem("unknown cell"), JSON)
        exchange.respond(200, body, JSON)
    }

    /**
     * A state read. An open observation answers from its materialized fold; a
     * wired [SnapshotSource] answers from a host-routed snapshot; otherwise the
     * contract's `unavailable` — the honest answer for a cell nobody subscribed
     * to, since *reading* it here would mean either racing its fold from the
     * HTTP thread or silently subscribing (P6).
     *
     * Also the "matching `GET state`" that renews the idle deadline.
     */
    private fun serveState(exchange: HttpExchange, ref: CellRef) {
        if (!model.knows(ref)) return exchange.respond(404, problem("unknown cell"), JSON)
        observations.touch(ref)
        val reading = observations.reading(ref) ?: observations.snapshotReading(ref)
        val state = if (reading == null) {
            CellState(ref = encodeRef(ref), kind = CellState.UNAVAILABLE)
        } else {
            CellState(
                ref = encodeRef(ref),
                frontier = reading.frontier?.let { WaveStamp(it.sourceId.toString(), it.counter) },
                kind = reading.kind,
                value = ValueEncoder.encode(reading.value),
                staleMs = reading.staleMs,
            )
        }
        exchange.respond(200, inspectorJson.encodeToString(CellState.serializer(), state), JSON)
    }

    /**
     * Contract: `204; starts state summaries for this cell`. A cell the kernel
     * offers no fold for (no outlet, or a delta shape no built-in `View`
     * covers) cannot start them, and answering 204 would promise summaries that
     * never arrive — so that case is refused explicitly. A client that ignores
     * the refusal still behaves correctly: its `GET state` reports
     * `unavailable`.
     */
    private fun startObserving(exchange: HttpExchange, ref: CellRef) {
        if (!model.knows(ref)) return exchange.respond(404, problem("unknown cell"), JSON)
        if (observations.start(ref)) exchange.noContent()
        else exchange.respond(409, problem("no observable outlet on this cell"), JSON)
    }

    /**
     * Label the graph that contains [anchorRef] (M4). The kernel has no `Graph`
     * entity and no naming mechanism — a graph is an emergent connected
     * component (`10-target-v3.md` §Known kernel gaps; membranes as the real
     * nameable boundary are tracked in Linear MRB-156) — so this is an
     * inspector-level annotation an application opts into for the components it
     * knows the meaning of. Everything else stays `name: null`, which the
     * navigator renders as its generated id; no name is ever invented.
     *
     * Anchored to a cell rather than to a component id on purpose: ids change
     * whenever components merge or split, and a name that evaporated on the
     * first merge would be worse than none. Nothing is persisted — a restarted
     * process re-annotates from its own code, as this one does.
     *
     * Safe to call before the cell is published; it simply names nothing until
     * the ref shows up.
     */
    fun nameGraph(anchorRef: CellRef, name: String): InspectorServer = apply { model.nameGraph(anchorRef, name) }

    /**
     * Declare a cross-boundary stream as an edge (M5-NET).
     *
     * The kernel records an edge only where `ManagedHost.connect` admitted one,
     * and that path resolves *both* endpoints in one host's own cell map — so a
     * link from a local outlet to a ref on another JVM is not expressible as a
     * `TopologyLink` and appears in no index, on either side of the wire. What
     * peered applications actually build is
     * `outlet.streamTo(RoutedPropagate(peerRef, port, registry::deliver))`: a
     * genuine consume-role subscription that the registry routes across the
     * bridge, and that nothing introspects.
     *
     * This is the same shape of opt-in annotation as [nameGraph]: the process
     * tells the inspector about wiring it performed, and the inspector reports
     * it rather than inferring or inventing it. Declare after both the
     * producing cell is published and (ideally) the peer has announced [toRef],
     * so the edge is tapped for flow and lands in the right component;
     * declaring the same stream twice is idempotent.
     *
     * Nothing about the graph changes — no link is created here, only reported.
     * A declared edge therefore also *survives* the peer going away, unlike a
     * mirrored one: the subscription is still there and still emitting (into
     * the registry's park queue), so retracting the edge would misreport the
     * process. The target cell leaves the topology, and a client draws what it
     * can anchor.
     */
    fun declareLink(
        fromRef: CellRef,
        fromPort: String,
        toRef: CellRef,
        toPort: String,
    ): InspectorServer = apply {
        model.declareLink(PortRef.of(fromRef, fromPort), PortRef.of(toRef, toPort))
    }

    fun start(): InspectorServer = apply {
        shell.start()
        heartbeats.scheduleAtFixedRate(
            { runCatching { model.heartbeat() } },
            HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS,
        )
        heartbeats.scheduleAtFixedRate(
            { runCatching { observations.sweep() } },
            SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS,
        )
        heartbeats.scheduleAtFixedRate(
            { runCatching { errors.poll() } },
            ERROR_POLL_SECONDS, ERROR_POLL_SECONDS, TimeUnit.SECONDS,
        )
        // M3: the flow feed's single aggregation thread is this same scheduler
        // — snapshot-and-reset is a handful of atomic reads, and sharing the
        // one daemon thread keeps the inspector at exactly the threads it had
        heartbeats.scheduleAtFixedRate(
            { runCatching { flow.sample() } },
            FlowCollector.WINDOW_MS, FlowCollector.WINDOW_MS, TimeUnit.MILLISECONDS,
        )
        // M4: `graphs.changed` is published from here rather than from the
        // registry hooks, which coalesces a whole graph build into one hint and
        // keeps the component sweep off the thread that is linking cells.
        // M5 reconciles peers on the same tick, deliberately *before* the
        // announcement: a peer that just arrived or vanished is in the
        // partition by the time the `graphs.changed` describing it goes out.
        heartbeats.scheduleAtFixedRate(
            { runCatching { model.reconcilePeers() }; runCatching { model.publishGraphChanges() } },
            GRAPHS_POLL_MS, GRAPHS_POLL_MS, TimeUnit.MILLISECONDS,
        )
    }

    fun stop() = close()

    override fun close() {
        attached = false
        heartbeats.shutdownNow()
        hooks.forEach { runCatching { it.close() } }
        // before the broadcaster: releasing a sink emits topology deltas, and a
        // still-attached client should see its own subscriptions retract
        observations.close()
        errors.close()
        // untaps every outlet: a stopped inspector leaves no handler on a live graph
        flow.close()
        broadcaster.close()
        shell.stop()
    }

    /** Refs with a live observation — diagnostics and tests. */
    internal val observedRefs: Set<CellRef> get() = observations.openRefs

    /** Run the idle sweep now instead of waiting for its schedule — tests. */
    internal fun sweepIdleObservations() = observations.sweep()

    /** Run the error-lane poll now instead of waiting for its 2 s schedule — tests. */
    internal fun pollErrorsNow() = errors.poll()

    /** Close the flow window now instead of waiting for its 1 s schedule — tests. */
    internal fun sampleFlowNow() = flow.sample()

    /** Outlets the flow feed currently taps — tests. */
    internal val tappedOutlets: Set<PortRef> get() = flow.tappedOutlets

    /** `GET /api/inspect/errors`'s body, decoded — tests. */
    internal fun errorSnapshot(): ErrorSnapshot = errors.snapshot()

    /** Publish any pending `graphs.changed` now instead of waiting for its 1 s schedule — tests. */
    internal fun publishGraphChangesNow() = model.publishGraphChanges()

    /** Reconcile peer refs/links now instead of waiting for the 1 s schedule — tests. */
    internal fun reconcilePeersNow() = model.reconcilePeers()

    /** Has this view adopted [ref] yet? The barrier a peer-announcement test waits on. */
    internal fun knowsNow(ref: CellRef): Boolean = model.knows(ref)

    /** The live components, as `GET /graphs` and `GET /search` see them — tests. */
    internal fun componentsNow(): List<Component> = model.components()

    companion object {
        const val DEFAULT_PORT = 7071
        const val BASE_PATH = "/api/inspect"
        const val TOPOLOGY_PATH = "$BASE_PATH/topology"
        const val EVENTS_PATH = "$BASE_PATH/events"
        const val CELL_PATH = "$BASE_PATH/cell"
        const val ERRORS_PATH = "$BASE_PATH/errors"
        const val GRAPHS_PATH = "$BASE_PATH/graphs"
        const val SEARCH_PATH = "$BASE_PATH/search"

        /** Contract §SSE: "Server sends `heartbeat` every 15 s". */
        const val HEARTBEAT_SECONDS = 15L

        /** How often idle observations are swept; the deadline itself is [Observations.IDLE_RELEASE_MS]. */
        const val SWEEP_SECONDS = 30L

        /** M2-BE ticket: "poll parked counts on a 2 s timer" — the same cadence covers restarts. */
        const val ERROR_POLL_SECONDS = 2L

        /** How often pending component changes are announced as `graphs.changed`. */
        const val GRAPHS_POLL_MS = 1_000L

        private const val JSON = "application/json"
        private const val STATE = "state"
        private const val OBSERVE = "observe"

        /** `GET /topology`'s M4 scoping parameter. */
        private const val GRAPH_PARAM = "graph"
        private const val MODE_PARAM = "mode"
        private const val QUERY_PARAM = "q"

        /** M4-BE §4, verbatim: `mode=data` returns 501 with this body. */
        private const val DATA_SEARCH_DEFERRED = """{"error": "data search arrives in M5"}"""

        /** `"<uuid>:<instanceId>"` — the contract's ref encoding. */
        internal fun encodeRef(ref: CellRef): String = "${ref.id}:${ref.instanceId}"

        /** Inverse of [encodeRef]; null for anything that is not one. */
        internal fun decodeRef(encoded: String): CellRef? {
            val id = runCatching { UUID.fromString(encoded.substringBefore(':')) }.getOrNull() ?: return null
            val instance = encoded.substringAfter(':', "0").toLongOrNull() ?: return null
            return CellRef(id, instance)
        }

        private fun problem(reason: String): String =
            buildJsonObject { put("reason", reason) }.toString()
    }
}

/**
 * The request's decoded query parameters. The JDK http server hands over a raw
 * query string and nothing else, and the inspector's two parameterized
 * endpoints (`?graph=`, `?mode=&q=`) need no more than this: last value wins,
 * a valueless key reads as empty, and percent/`+` escapes are decoded so a
 * search for `civictech.cell.data` survives the wire.
 */
private fun HttpExchange.query(): Map<String, String> =
    requestURI.rawQuery
        ?.split('&')
        ?.filter { it.isNotEmpty() }
        ?.associate { pair ->
            val name = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8)
            name to URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
        ?: emptyMap()

/** 204 has no body; the JDK server needs `-1`, not `0` (which means "chunked"). */
private fun HttpExchange.noContent() {
    sendResponseHeaders(204, -1)
    close()
}

/**
 * The inspector runs on its own port, so a dev UI (Vite, another port) is
 * cross-origin unless it proxies. `demo/agora/ui` proxies and the inspector UI
 * is expected to as well; this header only removes the failure mode where it
 * does not. Read-only endpoints, no credentials, developer instrument.
 */
private fun HttpExchange.allowCrossOrigin() {
    responseHeaders.add("Access-Control-Allow-Origin", "*")
}
