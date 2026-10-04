package civictech.runtime

import civictech.cell.BudgetLedger
import civictech.cell.CellRef
import civictech.cell.durability.Journal
import civictech.cell.graph.AppliedGraph
import civictech.cell.graph.ApplyContext
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
import civictech.cell.port.Use
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import civictech.cell.proxy.InvocationSink
import civictech.cell.replication.Replication
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.BridgeInstallMode
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.InspectorServer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Builds one manifest node without exposing host, journal or transport construction to its caller. */
object Runtime {

    /**
     * Applies the node-local sub-spec, then installs this node's halves of every
     * cross-node edge. A bridged edge is deliberately not an [ApplyContext]
     * topology link: local cycle admission and the Inspector's declared-link
     * view cannot see it (8k723-D6; spec 41 G-41).
     *
     * Recovery reinstalls only this node's physical bridge halves. The address-pair
     * handshake is recovery-idempotent: a surviving peer observes no logical
     * close/open churn, while a rebuilt consumer reconstructs its one local open-edge
     * observation (9kvab-D1; spec 13 [13-REBIND-01], 93 I-13).
     */
    private fun applyPlacement(
        plan: PlacementPlan,
        context: ApplyContext,
        mainHost: ManagedHost,
        registry: LocationRegistry,
    ): AppliedGraph {
        val applied = plan.localSpec.apply(context)
        installHalves(plan, mainHost, registry)
        return applied
    }

    /** Install the unjournaled data-path and handshake half for every cross-node edge. */
    private fun installHalves(
        plan: PlacementPlan,
        mainHost: ManagedHost,
        registry: LocationRegistry,
        recovered: Boolean = false,
    ) {
        val sink = InvocationSink(registry::deliver)
        val installMode = if (recovered) BridgeInstallMode.RECOVERED else BridgeInstallMode.OPEN

        plan.producerHalves.forEach { edge ->
            val outlet = producerPort(mainHost, edge)
            val counterpartRef = PortRef.of(edge.toRef, edge.inlet)
            mainHost.managementInlet.call.connect(
                edge.fromRef,
                edge.outlet,
                Use.fixed(
                    RoutedPropagate<Any>(edge.toRef, edge.inlet, registry::deliver),
                    counterpartRef,
                ),
            )
            val result = outlet.bridgeTo(
                selfAddr = PortAddress(edge.fromRef, edge.outlet),
                toAddr = PortAddress(edge.toRef, edge.inlet),
                sink = sink,
                counterpartRef = counterpartRef,
                installMode = installMode,
            )
            if (result is LinkResult.Rejected) {
                throw IllegalStateException(
                    "bridging ${edge.key()} across nodes was rejected on the producer half: ${result.reason}",
                )
            }
        }
        plan.consumerHalves.forEach { edge ->
            val inlet = consumerPort(mainHost, edge)
            val result = inlet.bridgeFrom(
                selfAddr = PortAddress(edge.toRef, edge.inlet),
                fromAddr = PortAddress(edge.fromRef, edge.outlet),
                sink = sink,
                installMode = installMode,
            )
            if (result is LinkResult.Rejected) {
                throw IllegalStateException(
                    "bridging ${edge.key()} across nodes was rejected on the consumer half: ${result.reason}",
                )
            }
        }
    }

    private fun producerPort(mainHost: ManagedHost, edge: CrossEdge): FanOutlet<*> {
        val port = mainHost.portAt(edge.fromRef, edge.outlet)
        return port as? FanOutlet<*>
            ?: throw IllegalStateException(
                "bridging ${edge.key()} across nodes on the producer half requires " +
                    "${edge.fromHandle}.${edge.outlet} to be a FanOutlet " +
                    "(was ${port?.javaClass?.simpleName ?: "missing"})",
            )
    }

    private fun consumerPort(mainHost: ManagedHost, edge: CrossEdge): FanInlet<*> {
        val port = mainHost.portAt(edge.toRef, edge.inlet)
        return port as? FanInlet<*>
            ?: throw IllegalStateException(
                "bridging ${edge.key()} across nodes on the consumer half requires " +
                    "${edge.toHandle}.${edge.inlet} to be a FanInlet " +
                    "(was ${port?.javaClass?.simpleName ?: "missing"})",
            )
    }

    private fun CrossEdge.key(): String = "$fromHandle.$outlet -> $toHandle.$inlet"

    /**
     * Enforce the journal-observable part of the cumulative placed-spec boot contract.
     * Bridge halves themselves are deliberately absent from the local topology fold, but
     * every active local endpoint and its pinned identity must agree before any half is
     * reinstalled.
     */
    private fun recoveredPlacementSpawns(
        plan: PlacementPlan,
        context: ApplyContext,
        journalDir: File,
    ): List<SpawnStep> {
        val expected = plan.activeLocalSpawns().associateBy(SpawnStep::handle)
        val recovered = context.handles
        val missing = expected.keys.firstOrNull { it !in recovered }
        val unexpected = recovered.keys.firstOrNull { it !in expected }
        val wrongRef = expected.values.firstOrNull { step ->
            !step.replicated && recovered[step.handle]?.let { it != plan.refOf(step.handle) } == true
        }
        if (missing != null || unexpected != null || wrongRef != null) {
            val mismatch = when {
                missing != null -> "is missing active local handle '$missing'"
                unexpected != null -> "contains undeclared active local handle '$unexpected'"
                else -> {
                    val handle = checkNotNull(wrongRef).handle
                    "binds active local handle '$handle' to ${recovered.getValue(handle)} " +
                        "instead of ${plan.refOf(handle)}"
                }
            }
            throw IllegalStateException(
                "recovered placed topology $mismatch; Runtime.boot requires the cumulative placed " +
                    "GraphSpec (the original boot spec followed by every successful Node.apply delta) " +
                    "for journal directory '$journalDir'",
            )
        }
        return expected.values.toList()
    }

    /** Drain every host built by a boot that failed before it could return a [Node]. */
    private fun closeFailedBootHosts(hosts: Collection<ManagedHost>, failure: Throwable) {
        hosts.forEach { host ->
            try {
                host.managementInlet.call.drainHost()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            try {
                host.quiescence().await(30_000, "closing runtime boot host after failed boot")
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
    }

    /**
     * Construct with an exact transport instance without exposing the optional
     * Inspector type to callers that do not otherwise depend on `:inspect`.
     * A recovered placed node has the same cumulative [spec] contract documented
     * on the full [boot] overload below.
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
     * A recovered placed node reinstalls its physical bridge halves before [Node.open]
     * without reopening the logical edge or duplicating its edge-event accounting.
     *
     * For a placed node, [spec] on recovery MUST be the cumulative ordered graph history: the
     * original boot spec followed by every successfully applied [Node.apply] delta. Local cells
     * recover from the topology journal, but cross-node halves are deliberately not journaled, so
     * the cumulative spec is their recovery source and becomes [Node.placement]. Recovery refuses
     * a spec whose active local handles or pinned refs disagree with the journal, naming this
     * contract; an ordered spawn followed by an isolated despawn is valid and remains absent.
     * The check is limited to what the local journal records: a stale spec that omits only
     * cross-node edges (or spawns placed on other nodes) is not detected, and boots without them.
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
        return try {
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
            val recovered = topology?.replay()?.isNotEmpty() == true
            val families: Map<String, KeyedCells<*>>
            val inputs: Map<String, Map<String, DurableInput>>
            if (recovered) {
                applyContext.recover(topology).awaitApplied(30_000)
                val journalDir = journalDirs.getValue(mainHostName)
                val declaredSpawns = if (placement != null) {
                    recoveredPlacementSpawns(placement, applyContext, journalDir)
                } else {
                    spec.lowered()
                        .filterIsInstance<SpawnStep>()
                        .filter { it.family == null }
                        .also { spawns ->
                            spawns.firstOrNull { it.handle !in applyContext.handles }?.let { missing ->
                                throw IllegalStateException(
                                    "recovered topology is missing handle '${missing.handle}' declared by " +
                                        "the GraphSpec in journal directory '$journalDir'",
                                )
                            }
                        }
                }
                if (placement != null) installHalves(placement, mainHost, registry, recovered = true)
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
                    applyPlacement(placement, applyContext, mainHost, registry)
                }
                families = applied.families
                inputs = applied.inputs
            }
            Node(
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
            )
        } catch (failure: Throwable) {
            closeFailedBootHosts(hosts.values, failure)
            throw failure
        }
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

        private val recoveryCatchUps = mutableListOf<AutoCloseable>()

        init {
            if (recovered) {
                initialPlacement?.consumerHalves?.forEach { edge ->
                    val requested = AtomicBoolean()
                    fun requestIfAvailable(ref: CellRef) {
                        if (
                            ref == edge.fromRef &&
                            registry.location(ref) is LocationRegistry.Remote &&
                            requested.compareAndSet(false, true)
                        ) {
                            val link = recoveredConsumerLink(edge)
                            Protocols.sendUpstream(
                                link,
                                Protocols.StateRequest,
                                StateRequest(link.to, since = null),
                            )
                        }
                    }
                    recoveryCatchUps += registry.onPublish(::requestIfAvailable)
                    requestIfAvailable(edge.fromRef)
                }
            }
        }

        private fun recoveredConsumerLink(edge: CrossEdge): WireEdgeLink =
            consumerPort(mainHost, edge).linking.links
                .filterIsInstance<WireEdgeLink>()
                .single {
                    it.fromAddr == PortAddress(edge.fromRef, edge.outlet) &&
                        it.toAddr == PortAddress(edge.toRef, edge.inlet)
                }

        /**
         * Apply a graph delta through this node's services and topology journal.
         * When this is a placed, topology-journalled node, the caller must retain each
         * successful delta after the original boot spec and pass that cumulative ordered
         * [GraphSpec] to the next [Runtime.boot]; bridge halves are not journaled.
         *
         * A placed delta is not atomic. If [applyPlacement] throws after its local prefix has
         * been applied, [placement] remains the previous cumulative plan while live cells and
         * links may already reflect part of the delta. The kernel has no rollback mechanism and
         * [PlacementPlan] cannot represent that partial bridge state, so this node is unusable
         * for further placed deltas after such a failure; close it and boot a fresh node instead.
         */
        fun apply(spec: GraphSpec): AppliedGraph {
            val previous = placement ?: return spec.apply(applyContext)
            val next = requireNotNull(PlacementPlan.of(spec, manifest, name, previous))
            val applied = applyPlacement(next, applyContext, mainHost, registry)
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
            recoveryCatchUps.forEach { subscription -> capture(subscription::close) }
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
