package civictech.agora

import civictech.agora.cell.*
import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.ReplayScope
import civictech.cell.Stateful
import civictech.cell.data.delta.MapDelta
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TopologyFold
import civictech.cell.graph.UnlinkStep
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.inlet
import civictech.cell.link.catchUpOnLinked
import civictech.cell.link.LinkOptions
import civictech.cell.observe.Observation
import civictech.cell.observe.get
import civictech.cell.observe.observation
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.io.Serializable

private const val CREDENCES_VIEW = "credences"

private val AGORA_HUB_REF =
    CellRef(java.util.UUID.nameUUIDFromBytes("agora:hub".toByteArray()))

private val AGORA_OBSERVATION_REF =
    CellRef(java.util.UUID.nameUUIDFromBytes("agora:observation".toByteArray()))

/**
 * Dynamic credence ingress in front of Agora's canonical app-edge observation.
 *
 * Claims and edges are created after the observation itself, so their outlets
 * cannot be registered as a fixed builder block. They continue to link to this
 * stable source, which translates each [CredenceUpdate] into the canonical
 * map-delta vocabulary consumed by `host.observation { map(...) }`.
 *
 * The source keeps the historical `agora:hub` ref and the old [CredenceView]
 * snapshot shape. Journal replay and checkpoints therefore still address and
 * restore the same durable fold; [publishCurrent] seeds the derived observation
 * after recovery, including from a checkpoint written before the migration.
 */
class CredenceObservationSource internal constructor(
    override val ref: CellRef,
    onUpdate: (CellRef, Double) -> Unit,
) : Cell, Stateful {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<CredenceUpdate>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<CellRef, Double>>>())

    private val fold = CredenceView(onUpdate)
    private lateinit var observation: Observation

    init {
        inlet.onEach { update ->
            if (fold.apply(update)) {
                outlet.call.propagate(MapDelta(mapOf(update.source to update.credence), emptySet()))
            }
        }
        outlet.catchUpOnLinked {
            fold.current().takeIf { it.isNotEmpty() }?.let { MapDelta(it, emptySet()) }
        }
    }

    internal fun attach(observation: Observation) {
        check(!this::observation.isInitialized) { "credence observation already attached" }
        this.observation = observation
    }

    internal fun publishCurrent() {
        fold.current().takeIf { it.isNotEmpty() }
            ?.let { outlet.call.propagate(MapDelta(it, emptySet())) }
    }

    fun credenceOf(ref: CellRef): Double? =
        observation.get<Map<CellRef, Double>>(CREDENCES_VIEW)[ref]

    override fun snapshot(): Serializable = fold.snapshot()

    override fun restore(state: Serializable) = fold.restore(state)
}

/** The durable construction record for one claim. */
data class ClaimFactory(
    val text: String,
    val semantics: GradualSemantics,
) : CellFactory {
    /** The live product is deliberately not part of the journaled factory data. */
    @Transient
    internal var created: ClaimCell? = null

    override fun create(ref: CellRef): ClaimCell = ClaimCell(ref, semantics).also { cell ->
        cell.catchUp = ReplayScope.get() == null
        created = cell
    }
}

/** The durable construction record for one argument edge. */
data class EdgeFactory(
    val polarity: Polarity,
    val semantics: GradualSemantics,
    val head: Boolean,
    val quiescence: Double,
) : CellFactory {
    /** See [ClaimFactory.created]. */
    @Transient
    internal var created: EdgeCell? = null

    override fun create(ref: CellRef): EdgeCell = EdgeCell(
        polarity = polarity,
        ref = ref,
        semantics = semantics,
        quiescence = if (head) quiescence else 0.0,
    ).also { cell ->
        cell.catchUp = ReplayScope.get() == null
        created = cell
    }
}

/**
 * Durable intent for a removal whose source detaches, retractions, and
 * despawns must stay ordered across separate runtime operations.
 *
 * This is app data inside the existing topology `Spawn` event, not a new
 * journal record shape. A normal removal despawns the marker in its final
 * delta; a recovered live host uses it to finish every referenced despawn.
 */
private data class RemovalIntentFactory(val doomed: List<CellRef>) : CellFactory {
    override fun create(ref: CellRef): Cell = RemovalIntentCell(ref)
}

private class RemovalIntentCell(override val ref: CellRef) : Cell

/**
 * Graph management shared by the HTTP layer and the tests. Cells stay
 * topology-blind; the service owns the index — which is also what lets it
 * designate cycle heads at edge-creation time for the decided cycle model
 * (spec 21 §Cycles / 93 I-5+I-6: every elementary cycle contains at least
 * one head, because any new cycle runs through the edge that closed it).
 *
 * All wiring uses the host-admitted primitive with
 * `LinkOptions(staged = true)`: every hop enters the host queue, so
 * magnitude-based prioritization remains active even for co-hosted cells, and
 * the topology records the same links the kernel admits for cycle safety. Hops
 * into a `ClaimCell` or `EdgeCell` use their registered port-name strings; the
 * app-local credence source keeps the reified string form.
 */
class AgoraService(
    private val host: ManagedHost,
    private val registry: LocationRegistry,
    private val semantics: GradualSemantics = DfQuad,
    /** Cycle-head absorb threshold (per feedback edge; heads only). */
    private val quiescence: Double = 1e-3,
    context: ApplyContext? = null,
    onCredence: (CellRef, Double) -> Unit = { _, _ -> },
) {
    enum class Kind { CLAIM, EDGE }

    data class NodeInfo(
        val kind: Kind,
        val text: String? = null,
        val polarity: Polarity? = null,
        val source: CellRef? = null,
        val target: CellRef? = null,
        val head: Boolean = false,
    )

    data class Node(val ref: CellRef, val info: NodeInfo, val credence: Double)

    private data class TornEdgeRemoval(
        val ref: CellRef,
        val polarity: Polarity,
        val target: CellRef,
    )

    private val manage = host.managementInlet.call
    private val context = context ?: ApplyContext(host)

    // Stable ingress identity: journaled credence frames re-deliver after a restart.
    val hub = CredenceObservationSource(AGORA_HUB_REF, onCredence)

    private val observation: Observation

    private val cells = mutableMapOf<CellRef, ClaimCell>()

    /**
     * `nodes` is read from `graph()`/`nodeInfo()`/`findEdge()` off whatever
     * thread is serving `/graph` or an SSE `onCredence` broadcast, while
     * `createClaim`/`createEdge`/`remove` mutate it from the app's
     * mutation thread (the HTTP dispatcher in `AgoraApp`, the
     * `dialogue-driver` thread in `DialogueApp` — see computenet-47nz).
     * `nodesLock` is the single mutex both sides take: every mutator holds
     * it for its full read-modify-write span (including [reaches], which
     * walks `nodes` from inside [createEdge]), and every reader takes a
     * defensive snapshot under it before iterating outside the lock.
     */
    private val nodesLock = Any()
    private val nodes = LinkedHashMap<CellRef, NodeInfo>()

    init {
        require(this.context.host === host) { "AgoraService context belongs to a different host" }
        manage.spawn(hub)
        observation = host.observation(groupRef = { AGORA_OBSERVATION_REF }) {
            // This app-owned source is the explicit alignment boundary for a
            // dynamically growing set of claim/edge feeds.
            unchecked(CREDENCES_VIEW)
            map(CREDENCES_VIEW, hub.ref)
        }
        hub.attach(observation)
        this.context.adopt(HUB_HANDLE, hub.ref)
    }

    internal val observationGroups: Map<String, String>
        get() = observation.current().groupOf

    internal val observationGroupRef: CellRef
        get() = observation.group(CREDENCES_VIEW).ref

    internal val observationBufferedWaves: Int
        get() = observation.bufferedWaves

    /** Apply one claim and its hub link as one write-ahead topology delta. */
    fun createClaim(
        text: String,
        ref: CellRef = CellRef(java.util.UUID.randomUUID()),
        handle: String = "claim:${ref.id}",
    ): CellRef {
        synchronized(nodesLock) { nodes[ref] = NodeInfo(Kind.CLAIM, text = text) }
        val factory = ClaimFactory(text, semantics)
        GraphSpec(
            listOf(
                SpawnStep(handle, factory, IdentityBinding.Exact(ref)),
                ConnectStep(handle, "credenceOutlet", HUB_HANDLE, "inlet", STAGED),
            ),
        ).apply(context)
        synchronized(nodesLock) {
            cells[ref] = checkNotNull(factory.created) { "claim factory did not retain $ref" }
        }
        return ref
    }

    fun createEdge(
        source: CellRef,
        target: CellRef,
        polarity: Polarity,
        ref: CellRef = CellRef(java.util.UUID.randomUUID()),
        handle: String = "edge:${ref.id}",
    ): CellRef {
        val head = synchronized(nodesLock) {
            require(source in nodes) { "unknown source ${source.id}" }
            require(target in nodes) { "unknown target ${target.id}" }
            val h = reaches(from = target, to = source)
            nodes[ref] = NodeInfo(Kind.EDGE, polarity = polarity, source = source, target = target, head = h)
            h
        }
        val topology = context.live()
        val factory = EdgeFactory(polarity, semantics, head, quiescence)
        GraphSpec(
            listOf(
                SpawnStep(handle, factory, IdentityBinding.Exact(ref)),
                ConnectStep(handle, "credenceOutlet", HUB_HANDLE, "inlet", STAGED),
                ConnectStep(
                    handle,
                    "influenceOutlet",
                    topology.handleFor(target),
                    "influenceInlet",
                    STAGED,
                ),
                // Install the source link last: it is the link that can close
                // a newly visible cycle.
                ConnectStep(
                    topology.handleFor(source),
                    "credenceOutlet",
                    handle,
                    if (head) "feedbackInlet" else "sourceInlet",
                    STAGED,
                ),
            ),
        ).apply(context)
        synchronized(nodesLock) {
            cells[ref] = checkNotNull(factory.created) { "edge factory did not retain $ref" }
        }
        return ref
    }

    /**
     * Rebuild the application index from the kernel's folded live topology.
     * Factories are the durable node records; edge endpoints come from the
     * admitted links, never from an application-owned side log.
     *
     * Recovery-created cells keep catch-up disabled while frames replay. The
     * caller invokes this only after `Recovery.awaitApplied`; this method is
     * the one point that re-enables future link baselines.
     *
     * This operation only reads the fold. A live recovery path must call
     * [repairTornRemovals] first; offline readers deliberately do not, so an
     * unfinished removal is reported instead of mutating the journal being
     * inspected.
     */
    fun rebuildIndex() {
        val topology = context.live()
        topology.spawns.values.firstOrNull { it.factory is RemovalIntentFactory }?.let { marker ->
            error("unfinished removal intent '${marker.handle}' in recovered topology")
        }
        val rebuiltCells = linkedMapOf<CellRef, ClaimCell>()
        val rebuiltNodes = linkedMapOf<CellRef, NodeInfo>()

        topology.spawns.values.forEach { spawn ->
            when (val factory = spawn.factory) {
                is ClaimFactory -> {
                    rebuiltCells[spawn.ref] = checkNotNull(factory.created) {
                        "claim ${spawn.ref} is live in the topology fold but its factory has no cell"
                    }
                    rebuiltNodes[spawn.ref] = NodeInfo(Kind.CLAIM, text = factory.text)
                }

                is EdgeFactory -> {
                    rebuiltCells[spawn.ref] = checkNotNull(factory.created) {
                        "edge ${spawn.ref} is live in the topology fold but its factory has no cell"
                    }
                    val source = topology.links.values.singleOrNull { link ->
                        link.to == spawn.ref &&
                            link.outlet == "credenceOutlet" &&
                            link.inlet in SOURCE_INLETS
                    }?.from ?: error("edge ${spawn.ref} has no unique source link in recovered topology")
                    val target = topology.links.values.singleOrNull { link ->
                        link.from == spawn.ref &&
                            link.outlet == "influenceOutlet" &&
                            link.inlet == "influenceInlet"
                    }?.to ?: error("edge ${spawn.ref} has no unique target link in recovered topology")
                    rebuiltNodes[spawn.ref] = NodeInfo(
                        kind = Kind.EDGE,
                        polarity = factory.polarity,
                        source = source,
                        target = target,
                        head = factory.head,
                    )
                }
            }
        }

        synchronized(nodesLock) {
            cells.clear()
            cells.putAll(rebuiltCells)
            nodes.clear()
            nodes.putAll(rebuiltNodes)
        }
        rebuiltCells.values.forEach { it.catchUp = true }
        hub.publishCurrent()
    }

    /**
     * Complete a write-ahead removal prefix on a live recovery host.
     *
     * New removals leave a [RemovalIntentFactory] in the same durable delta as
     * their source unlinks. Journals written before that marker existed are
     * still recognized by the old signature: a live [EdgeFactory] with no
     * source link. Retractions are enqueued before the final despawns so each
     * staged `EdgeClose` trails the explicit removal at a surviving target.
     *
     * @return `true` when repair enqueued retractions and journaled despawns.
     */
    fun repairTornRemovals(): Boolean {
        val topology = context.live()
        val intents = topology.spawns.values.mapNotNull { spawn ->
            (spawn.factory as? RemovalIntentFactory)?.let { spawn to it }
        }
        val doomed = linkedSetOf<CellRef>()
        intents.forEach { (marker, intent) ->
            intent.doomed.forEach { ref ->
                check(ref in topology.spawns) {
                    "removal intent '${marker.handle}' names non-live cell $ref"
                }
                doomed += ref
            }
        }

        val legacyTornEdges = topology.spawns.values.mapNotNull { spawn ->
            val factory = spawn.factory as? EdgeFactory ?: return@mapNotNull null
            val sourceLinks = topology.links.values.filter { link ->
                link.to == spawn.ref &&
                    link.outlet == "credenceOutlet" &&
                    link.inlet in SOURCE_INLETS
            }
            when (sourceLinks.size) {
                1 -> null
                0 -> spawn.ref

                else -> error("edge ${spawn.ref} has no unique source link in recovered topology")
            }
        }
        doomed += legacyTornEdges
        if (doomed.isEmpty()) return false

        val doomedEdges = doomed.mapNotNull { ref ->
            val spawn = topology.spawns.getValue(ref)
            val factory = spawn.factory as? EdgeFactory ?: return@mapNotNull null
            val target = topology.links.values.singleOrNull { link ->
                link.from == ref &&
                    link.outlet == "influenceOutlet" &&
                    link.inlet == "influenceInlet"
            }?.to ?: error("edge $ref has no unique target link in recovered topology")
            TornEdgeRemoval(ref, factory.polarity, target)
        }
        doomedEdges.forEach { edge ->
            if (edge.target !in doomed) {
                val size = hub.credenceOf(edge.ref) ?: 1.0
                registry.inlet(edge.target, ClaimCellPorts.influenceInlet)
                    .propagate(InfluenceDelta(edge.ref, edge.polarity, null, size))
            }
        }
        val markerRefs = intents.map { (spawn, _) -> spawn.ref }
        GraphSpec((doomed + markerRefs).map { DespawnStep(topology.handleFor(it)) }).apply(context)
        return true
    }

    fun setStance(id: CellRef, user: String, value: Double?) {
        synchronized(nodesLock) { require(id in nodes) { "unknown node ${id.id}" } }
        value?.let { require(it in 0.0..1.0) { "stance must be between 0 and 1 (was $it)" } }
        registry.inlet(id, ClaimCellPorts.stanceInlet).propagate(StanceDelta(user, value))
    }

    /**
     * Remove a claim or edge, cascading over every edge that becomes dangling
     * (sources/targets it, recursively — edges targeting a removed edge go
     * too). Each removed edge first stops its inbound feed, then retracts its
     * influence at any surviving target, then despawns.
     */
    fun remove(id: CellRef) {
        val (doomed, infos) = synchronized(nodesLock) {
            require(id in nodes) { "unknown node ${id.id}" }
            val d = mutableSetOf(id)
            var grew = true
            while (grew) {
                grew = d.addAll(nodes.filter { (ref, info) ->
                    ref !in d && info.kind == Kind.EDGE && (info.source in d || info.target in d)
                }.keys)
            }
            d to d.associateWith { nodes.getValue(it) }
        }
        val topology = context.live()
        val handles = doomed.associateWith { topology.handleFor(it) }
        val unlinkSourceSteps = infos.mapNotNull { (ref, info) ->
            if (info.kind != Kind.EDGE) null else UnlinkStep(
                from = topology.handleFor(checkNotNull(info.source)),
                outlet = "credenceOutlet",
                to = handles.getValue(ref),
                inlet = if (info.head) "feedbackInlet" else "sourceInlet",
            )
        }
        val removalHandle = "removal:${java.util.UUID.randomUUID()}"
        GraphSpec(
            listOf(SpawnStep(removalHandle, RemovalIntentFactory(doomed.toList()))) + unlinkSourceSteps,
        ).apply(context)

        infos.forEach { (ref, info) ->
            if (info.kind == Kind.EDGE) {
                if (info.target !in doomed) {
                    // retraction urgency: the edge's credence bounds its influence
                    val size = hub.credenceOf(ref) ?: 1.0
                    registry.inlet(info.target!!, ClaimCellPorts.influenceInlet).propagate(InfluenceDelta(ref, info.polarity!!, null, size))
                }
            }
        }
        // After the retractions above, so each staged EdgeClose trails them.
        GraphSpec(doomed.map { DespawnStep(handles.getValue(it)) } + DespawnStep(removalHandle)).apply(context)
        synchronized(nodesLock) {
            doomed.forEach {
                cells.remove(it)
                nodes.remove(it)
            }
        }
    }

    fun graph(): List<Node> {
        val snapshot = synchronized(nodesLock) { nodes.entries.map { it.key to it.value } }
        return snapshot.map { (ref, info) -> Node(ref, info, hub.credenceOf(ref) ?: 0.5) }
    }

    /**
     * Every cell instance this service spawned and still holds: [hub], its
     * canonical observation group, and every live claim and edge cell. A
     * point-in-time copy for callers at rest — the
     * underlying map is mutated by [createClaim]/[createEdge]/[remove] on the
     * app's mutation thread, so a caller racing those sees one side of the race.
     *
     * Added for `:timetravel`'s `GraphSource` contract (computenet-3qkx1 D12):
     * a reconstruction adapter hands back `GraphBuild(cells())`, and that must be
     * *all* of the spawned instances or the reconstructor refuses with
     * `GRAPH_SOURCE_INCOMPLETE`.
     */
    fun cells(): Collection<Cell> = synchronized(nodesLock) {
        listOf<Cell>(hub, observation.group(CREDENCES_VIEW)) + cells.values.toList()
    }

    fun nodeInfo(id: CellRef): NodeInfo? = synchronized(nodesLock) { nodes[id] }

    /**
     * The existing edge with exactly this `(source, target, polarity)`, if any.
     * The engine deliberately permits parallel edges and self-loops (both are
     * valid constructs the cycle/DF-QuAD tests exercise directly), so relation
     * uniqueness is a *product-surface* policy the HTTP layer applies via this
     * lookup — not an invariant enforced in [createEdge].
     */
    fun findEdge(source: CellRef, target: CellRef, polarity: Polarity): CellRef? =
        synchronized(nodesLock) {
            nodes.entries.firstOrNull { (_, info) ->
                info.kind == Kind.EDGE &&
                    info.source == source && info.target == target && info.polarity == polarity
            }?.key
        }

    /** DFS along the influence-flow direction: claim → edges sourced at it → their targets. */
    private fun reaches(from: CellRef, to: CellRef): Boolean {
        val seen = mutableSetOf<CellRef>()
        val stack = ArrayDeque<CellRef>().apply { add(from) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n == to) return true
            if (!seen.add(n)) continue
            nodes[n]?.target?.let { stack.add(it) }
            nodes.forEach { (ref, info) -> if (info.source == n) stack.add(ref) }
        }
        return false
    }

    private fun TopologyFold.handleFor(ref: CellRef): String =
        spawns[ref]?.handle
            ?: handles.entries.singleOrNull { it.value == ref }?.key
            ?: error("no live topology handle for $ref")

    companion object {
        private const val HUB_HANDLE = "hub"
        private val SOURCE_INLETS = setOf("sourceInlet", "feedbackInlet")
        private val STAGED = LinkOptions(staged = true)

        /**
         * Magnitude → band mapping for agora hosts: sizes are credence deltas
         * in [0,1], so the attention quantizer's 0.4/0.75 knees would leave
         * almost everything unboosted. Dramatic shifts outrank the neutral
         * band, noticeable ones match it, micro-adjustments yield to it.
         */
        val MAGNITUDE_BANDS: (Double) -> civictech.cell.control.AttentionBand = {
            when {
                it >= 0.2 -> civictech.cell.control.AttentionBand.HIGH
                it >= 0.05 -> civictech.cell.control.AttentionBand.NORMAL
                else -> civictech.cell.control.AttentionBand.LOW
            }
        }
    }
}
