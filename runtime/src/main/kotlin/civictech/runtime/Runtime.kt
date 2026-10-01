package civictech.runtime

import civictech.cell.BudgetLedger
import civictech.cell.CellRef
import civictech.cell.graph.GraphSpec
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.cell.wire.Peering
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.InspectorServer
import kotlinx.serialization.json.Json
import java.io.File

/** Builds one manifest node without exposing host, journal or transport construction to its caller. */
object Runtime {

    /**
     * Construct with an exact transport instance without exposing the optional
     * Inspector type to callers that do not otherwise depend on `:inspect`.
     */
    fun boot(
        manifest: Manifest,
        node: String,
        spec: GraphSpec,
        transport: PeerTransport,
    ): Node = boot(manifest, node, spec, overrides = emptyMap(), transport = transport)

    /**
     * Construct the node-local graph. Network endpoints and the inspector are deferred to [Node.open].
     * A future GraphSpec structure-log hook (INT1 1.2b) belongs immediately before the apply below.
     */
    fun boot(
        manifest: Manifest,
        node: String,
        spec: GraphSpec,
        overrides: Map<String, String> = emptyMap(),
        inspector: InspectorFlag.Options? = null,
        transport: PeerTransport? = null,
    ): Node {
        manifest.validated()
        val nodeSpec = requireNotNull(manifest.nodes[node]) { "manifest has no node '$node'" }
        require(transport == null || transport.scheme == nodeSpec.transport) {
            "transport override scheme '${transport?.scheme}' does not match node '$node' scheme '${nodeSpec.transport}'"
        }
        val registry = LocationRegistry()
        val budget = nodeSpec.budget?.let { policyFile ->
            val policy = Json.decodeFromString(EconomicPolicy.serializer(), File(policyFile).readText())
            TokenBucketLedger(policy.applied(), System::nanoTime, scope = node)
        } ?: BudgetLedger.Unlimited
        val hosts = nodeSpec.hosts.associateWith { hostName ->
            val journalDir = nodeSpec.journalDir?.let { File(it, hostName) }
            ManagedHost(
                registry = registry,
                journal = KeyedCells.hostJournal(journalDir),
                budget = budget,
            )
        }
        val mainHost = hosts.getValue(nodeSpec.hosts.first())
        val refs = spec.applyTo(mainHost.managementInlet)
        return Node(
            name = node,
            manifest = manifest,
            nodeSpec = nodeSpec,
            registry = registry,
            hosts = hosts,
            mainHost = mainHost,
            refs = refs,
            replica = nodeSpec.replica,
            budget = budget,
            overrides = overrides.toMap(),
            inspectorOptions = inspector,
            transportOverride = transport,
        )
    }

    /**
     * Additive inspector customization, registered with [Node.customizeInspector] before
     * [Node.open]. Everything defaults to what the Runtime derives itself, so a node that
     * registers nothing is served exactly as before.
     *
     * @property hostNames display name per host, keyed by manifest host name (`"main"`) or
     *   `"bridge"` for the peering bridge host; unnamed hosts keep `<node>/<host>`.
     * @property cellNames extra cell labels, merged over the spec-handle labels.
     * @property configure runs on the server before it starts (graph names, declared links).
     */
    class InspectorExtras(
        val hostNames: Map<String, String> = emptyMap(),
        val cellNames: Map<CellRef, String> = emptyMap(),
        val configure: InspectorServer.() -> Unit = {},
    )

    /** A booted node. Call [open] only after application-owned wiring is installed. */
    class Node internal constructor(
        val name: String,
        private val manifest: Manifest,
        private val nodeSpec: NodeSpec,
        val registry: LocationRegistry,
        val hosts: Map<String, ManagedHost>,
        val mainHost: ManagedHost,
        val refs: Map<String, CellRef>,
        val replica: Long?,
        val budget: BudgetLedger,
        private val overrides: Map<String, String>,
        private val inspectorOptions: InspectorFlag.Options?,
        private val transportOverride: PeerTransport?,
    ) : AutoCloseable {

        private var opened = false
        private var closed = false
        private var bridgeHost: ManagedHost? = null
        private var listener: PeerListener? = null
        private val connectionEndpoints = mutableListOf<PeerConnection>()
        private var inspectorExtras = InspectorExtras()

        /** Register inspector naming/link extras; only meaningful before [open]. */
        @Synchronized
        fun customizeInspector(extras: InspectorExtras) {
            check(!opened) { "runtime node '$name' is already open; customize the inspector before open()" }
            inspectorExtras = extras
        }

        /** The listener's granted address, including a selected port or iroh NodeId, after [open]. */
        val boundAddress: PeerAddress? get() = listener?.boundAddress

        /** Dialled connections, in manifest order. */
        val connections: List<PeerConnection> get() = connectionEndpoints.toList()

        /** The running inspector, when [boot] was given inspector options and [open] succeeded. */
        var inspector: InspectorServer? = null
            private set

        /**
         * Open exactly one bridge side, then listen, dial manifest peers and finally serve the inspector.
         * A second call is always a configuration error, even if the first call failed partway through.
         */
        @Synchronized
        fun open() {
            check(!closed) { "runtime node '$name' is closed" }
            check(!opened) { "runtime node '$name' is already open" }
            opened = true

            try {
                val bridge = ManagedHost(registry = registry).also { bridgeHost = it }
                val side = Peering.Side(
                    registry = registry,
                    bridgeHost = bridge,
                    peer = nodeSpec.peerName?.let(::PeerId),
                )
                val hasEndpoints = nodeSpec.listen != null || nodeSpec.dial.isNotEmpty()
                val transport = if (hasEndpoints) {
                    transportOverride ?: PeerTransports.forScheme(nodeSpec.transport, nodeSpec.transportConfig)
                } else {
                    null
                }

                nodeSpec.listen?.let { address ->
                    listener = checkNotNull(transport).listen(transport.parseAddress(address), side)
                }
                nodeSpec.dial.forEach { targetName ->
                    val address = overrides[targetName] ?: manifest.nodes.getValue(targetName).listen!!
                    connectionEndpoints += checkNotNull(transport).dial(transport.parseAddress(address), side)
                }

                inspector = inspectorOptions?.serve(
                    registry = registry,
                    hosts = buildMap {
                        hosts.forEach { (hostName, host) ->
                            put(inspectorExtras.hostNames[hostName] ?: "$name/$hostName", host)
                        }
                        put(inspectorExtras.hostNames["bridge"] ?: "$name/bridge", bridge)
                    },
                    cellNames = refs.entries.associate { (handle, ref) -> ref to handle } + inspectorExtras.cellNames,
                    configure = inspectorExtras.configure,
                )
            } catch (failure: Throwable) {
                runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        /** Close endpoints, then the inspector, then drain the bridge and application hosts. */
        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            val failures = mutableListOf<Throwable>()
            fun capture(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }

            connectionEndpoints.asReversed().forEach { connection -> capture(connection::close) }
            listener?.let { endpoint -> capture(endpoint::close) }
            inspector?.let { server -> capture(server::close) }
            buildList {
                bridgeHost?.let { add(it) }
                addAll(hosts.values)
            }.forEach { host ->
                capture {
                    host.managementInlet.call.drainHost()
                    host.quiescence().await(30_000, "closing runtime node '$name'")
                }
            }

            if (failures.isNotEmpty()) {
                throw IllegalStateException("failed to close runtime node '$name'").also { combined ->
                    failures.forEach(combined::addSuppressed)
                }
            }
        }
    }
}
