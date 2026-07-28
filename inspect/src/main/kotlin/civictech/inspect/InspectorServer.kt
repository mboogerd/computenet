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
) : AutoCloseable {

    /** Name the hosts by ref — the convenience form when the app has no names of its own. */
    constructor(registry: LocationRegistry, hosts: Set<ManagedHost>, port: Int = DEFAULT_PORT) :
        this(registry, hosts.associateBy { "host-" + it.ref.id.toString().substringBefore('-') }, port)

    constructor(registry: LocationRegistry, host: ManagedHost, port: Int = DEFAULT_PORT) :
        this(registry, setOf(host), port)

    private val shell = DemoShell(port)
    private val broadcaster = SseBroadcaster()

    // `flow` is declared below and read through the supplier, so the two can
    // reference each other without a construction cycle: the model asks the
    // collector for an edge's `fused`, the collector hands its windows back to
    // the model's `flowRates`. The supplier only runs after construction.
    private val model: InspectorModel =
        InspectorModel(registry, hosts, cellNames, broadcaster::publish, flow = { flow })

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

    val boundPort: Int get() = shell.boundPort

    /** Live SSE subscribers — diagnostics, and the tests' connect barrier. */
    internal val attachedClients: Int get() = broadcaster.clientCount

    init {
        // hooks first, then the catch-up read: a publish racing construction is
        // seen by the hook and merely re-confirmed by the sync, never lost
        model.sync()

        shell.route(TOPOLOGY_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.respond(200, model.snapshotJson(), "application/json")
        }
        shell.route(ERRORS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.respond(200, inspectorJson.encodeToString(ErrorSnapshot.serializer(), errors.snapshot()), JSON)
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
    }

    fun stop() = close()

    override fun close() {
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

    companion object {
        const val DEFAULT_PORT = 7071
        const val BASE_PATH = "/api/inspect"
        const val TOPOLOGY_PATH = "$BASE_PATH/topology"
        const val EVENTS_PATH = "$BASE_PATH/events"
        const val CELL_PATH = "$BASE_PATH/cell"
        const val ERRORS_PATH = "$BASE_PATH/errors"

        /** Contract §SSE: "Server sends `heartbeat` every 15 s". */
        const val HEARTBEAT_SECONDS = 15L

        /** How often idle observations are swept; the deadline itself is [Observations.IDLE_RELEASE_MS]. */
        const val SWEEP_SECONDS = 30L

        /** M2-BE ticket: "poll parked counts on a 2 s timer" — the same cadence covers restarts. */
        const val ERROR_POLL_SECONDS = 2L

        private const val JSON = "application/json"
        private const val STATE = "state"
        private const val OBSERVE = "observe"

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
