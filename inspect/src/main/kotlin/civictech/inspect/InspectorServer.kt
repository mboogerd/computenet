package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.shell.DemoShell
import civictech.demo.shell.beginSse
import civictech.demo.shell.respond
import civictech.demo.shell.sseFrame
import com.sun.net.httpserver.HttpExchange
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
 * registry's rare-path publish/unpublish/link/unlink hooks. Serving topology
 * subscribes to no cell and raises no attention (P6) — it reads registry
 * metadata and generated descriptors, never cell state. And the viz never
 * blocks the graph: hook threads hand frames to per-client bounded queues that
 * drop their oldest frames rather than wait (see [SseBroadcaster]).
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
    private val model = InspectorModel(registry, hosts, cellNames, broadcaster::publish)

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
    }

    fun start(): InspectorServer = apply {
        shell.start()
        heartbeats.scheduleAtFixedRate(
            { runCatching { model.heartbeat() } },
            HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS,
        )
    }

    fun stop() = close()

    override fun close() {
        heartbeats.shutdownNow()
        hooks.forEach { runCatching { it.close() } }
        broadcaster.close()
        shell.stop()
    }

    companion object {
        const val DEFAULT_PORT = 7071
        const val BASE_PATH = "/api/inspect"
        const val TOPOLOGY_PATH = "$BASE_PATH/topology"
        const val EVENTS_PATH = "$BASE_PATH/events"

        /** Contract §SSE: "Server sends `heartbeat` every 15 s". */
        const val HEARTBEAT_SECONDS = 15L
    }
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
