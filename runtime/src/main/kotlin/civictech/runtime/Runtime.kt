package civictech.runtime

import civictech.cell.BudgetLedger
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.durability.Journal
import civictech.cell.graph.AppliedGraph
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DurableInput
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.RoutedPropagate
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.InvocationSink
import civictech.cell.replication.Replication
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
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
     * Applies the node-local sub-spec, then installs this node's halves of every
     * cross-node edge. A bridged edge is deliberately not an [ApplyContext]
     * topology link: local cycle admission and the Inspector's declared-link
     * view cannot see it (8k723-D6; spec 41 G-41).
     */
    private fun applyPlacement(
        plan: PlacementPlan,
        context: ApplyContext,
        mainHost: ManagedHost,
        registry: LocationRegistry,
        cells: MutableMap<String, Cell>,
    ): AppliedGraph {
        val wrapped = plan.localSpec.copy(
            steps = plan.localSpec.steps.map { step ->
                if (step is SpawnStep && step.family == null) {
                    step.copy(
                        factory = CellFactory { ref ->
                            step.factory.create(ref).also { cells[step.handle] = it }
                        },
                    )
                } else {
                    step
                }
            },
        )
        val applied = wrapped.apply(context)
        val sink = InvocationSink(registry::deliver)

        plan.producerHalves.forEach { edge ->
            val outlet = producerPort(cells, edge)
            mainHost.managementInlet.call.connect(
                edge.fromRef,
                edge.outlet,
                Use.fixed(
                    RoutedPropagate<Any>(edge.toRef, edge.inlet, registry::deliver),
                    PortRef.generate(),
                ),
            )
            val result = outlet.bridgeTo(
                selfAddr = PortAddress(edge.fromRef, edge.outlet),
                toAddr = PortAddress(edge.toRef, edge.inlet),
                sink = sink,
            )
            if (result is LinkResult.Rejected) {
                throw IllegalStateException(
                    "bridging ${edge.key()} across nodes was rejected on the producer half: ${result.reason}",
                )
            }
        }
        plan.consumerHalves.forEach { edge ->
            val inlet = consumerPort(cells, edge)
            val result = inlet.bridgeFrom(
                selfAddr = PortAddress(edge.toRef, edge.inlet),
                fromAddr = PortAddress(edge.fromRef, edge.outlet),
                sink = sink,
            )
            if (result is LinkResult.Rejected) {
                throw IllegalStateException(
                    "bridging ${edge.key()} across nodes was rejected on the consumer half: ${result.reason}",
                )
            }
        }
        plan.localSpec.steps.filterIsInstance<DespawnStep>().forEach { cells.remove(it.handle) }
        return applied
    }

    private fun producerPort(cells: Map<String, Cell>, edge: CrossEdge): FanOutlet<*> {
        val cell = cells[edge.fromHandle]
            ?: throw IllegalStateException(
                "bridging ${edge.key()} across nodes on the producer half has no local cell " +
                    "for handle '${edge.fromHandle}'",
            )
        val port = PortRegistry.of(cell)[edge.outlet]
        return port as? FanOutlet<*>
            ?: throw IllegalStateException(
                "bridging ${edge.key()} across nodes on the producer half requires " +
                    "${edge.fromHandle}.${edge.outlet} to be a FanOutlet " +
                    "(was ${port?.javaClass?.simpleName ?: "missing"})",
            )
    }

    private fun consumerPort(cells: Map<String, Cell>, edge: CrossEdge): FanInlet<*> {
        val cell = cells[edge.toHandle]
            ?: throw IllegalStateException(
                "bridging ${edge.key()} across nodes on the consumer half has no local cell " +
                    "for handle '${edge.toHandle}'",
            )
        val port = PortRegistry.of(cell)[edge.inlet]
        return port as? FanInlet<*>
            ?: throw IllegalStateException(
                "bridging ${edge.key()} across nodes on the consumer half requires " +
                    "${edge.toHandle}.${edge.inlet} to be a FanInlet " +
                    "(was ${port?.javaClass?.simpleName ?: "missing"})",
            )
    }

    private fun CrossEdge.key(): String = "$fromHandle.$outlet -> $toHandle.$inlet"

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
     * A node with [NodeSpec.journalTopology] recovers its graph from the main host journal when
     * that journal is non-empty; otherwise its spec is applied and journaled as the first topology.
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
        lateinit var applyContext: ApplyContext
        val journals = mutableMapOf<String, Journal>()
        val journalDirs = mutableMapOf<String, File>()
        val hosts = nodeSpec.hosts.associateWith { hostName ->
            val journalDir = nodeSpec.journalDir?.let { File(it, hostName) }
            journalDir?.let { journalDirs[hostName] = it }
            val hostJournal = KeyedCells.hostJournal(journalDir)
            hostJournal?.let { journals[hostName] = it }
            ManagedHost(
                registry = registry,
                journal = hostJournal,
                journalFor = { ref -> applyContext.journalFor(ref) ?: hostJournal },
                budget = budget,
            )
        }
        val mainHostName = nodeSpec.hosts.first()
        val mainHost = hosts.getValue(mainHostName)
        val replication = Replication(registry)
        val nodeJournals = journals.toMap()
        val topology = if (nodeSpec.journalTopology) nodeJournals.getValue(mainHostName) else null
        applyContext = ApplyContext(
            host = mainHost,
            replication = replication,
            journals = nodeJournals,
            journalDirs = journalDirs.toMap(),
            topology = topology,
        )
        val placement = PlacementPlan.of(spec, manifest, node)
        val placedCells = linkedMapOf<String, Cell>()
        val recovered = topology?.replay()?.isNotEmpty() == true
        val families: Map<String, KeyedCells<*>>
        val inputs: Map<String, Map<String, DurableInput>>
        if (recovered) {
            applyContext.recover(topology).awaitApplied(30_000)
            val declaredSpawns = spec.lowered().filterIsInstance<SpawnStep>().filter { it.family == null }
            declaredSpawns.firstOrNull { it.handle !in applyContext.handles }?.let { missing ->
                throw IllegalStateException(
                    "recovered topology is missing handle '${missing.handle}' declared by the GraphSpec " +
                        "in journal directory '${journalDirs.getValue(mainHostName)}'",
                )
            }
            inputs = declaredSpawns
                .filter { it.inputs.isNotEmpty() }
                .associate { step ->
                    val ref = applyContext.handles.getValue(step.handle)
                    step.handle to step.inputs.associateWith { input -> mainHost.durableInput(ref, input) }
                }
            families = applyContext.live().families.keys.associateWith { handle ->
                checkNotNull(applyContext.familyFor(handle)) {
                    "recovered topology family '$handle' was not materialized"
                }
            }
        } else {
            val applied = if (placement == null) {
                spec.apply(applyContext)
            } else {
                applyPlacement(placement, applyContext, mainHost, registry, placedCells)
            }
            families = applied.families
            inputs = applied.inputs
        }
        return Node(
            name = node,
            manifest = manifest,
            nodeSpec = nodeSpec,
            registry = registry,
            hosts = hosts,
            mainHost = mainHost,
            journals = nodeJournals,
            recovered = recovered,
            applyContext = applyContext,
            families = families,
            inputs = inputs,
            replication = replication,
            replica = nodeSpec.replica,
            budget = budget,
            overrides = overrides.toMap(),
            inspectorOptions = inspector,
            transportOverride = transport,
            initialPlacement = placement,
            placedCells = placedCells,
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
        val journals: Map<String, Journal>,
        val recovered: Boolean,
        private val applyContext: ApplyContext,
        val families: Map<String, KeyedCells<*>>,
        val inputs: Map<String, Map<String, DurableInput>>,
        val replication: Replication,
        val replica: Long?,
        val budget: BudgetLedger,
        private val overrides: Map<String, String>,
        private val inspectorOptions: InspectorFlag.Options?,
        private val transportOverride: PeerTransport?,
        initialPlacement: PlacementPlan?,
        private val placedCells: MutableMap<String, Cell>,
    ) : AutoCloseable {

        private var opened = false
        private var closed = false
        private var bridgeHost: ManagedHost? = null
        private var listener: PeerListener? = null
        private val connectionEndpoints = mutableListOf<PeerConnection>()
        private var inspectorExtras = InspectorExtras()

        /** The current graph handles, including deltas applied after boot. */
        val refs: Map<String, CellRef> get() = applyContext.handles

        /** The cumulative placement fold, or null when manifest placement is inert. */
        var placement: PlacementPlan? = initialPlacement
            private set

        /** Apply a graph delta through this node's services and topology journal. */
        fun apply(spec: GraphSpec): AppliedGraph {
            val previous = placement ?: return spec.apply(applyContext)
            val next = requireNotNull(PlacementPlan.of(spec, manifest, name, previous))
            val applied = applyPlacement(next, applyContext, mainHost, registry, placedCells)
            placement = next
            return applied
        }

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
