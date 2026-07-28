package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.TopologyLink
import civictech.cell.port.PortRef
import civictech.nature.ContractRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * The inspector's materialized view of one [LocationRegistry]'s topology, and
 * the single source of both `GET /api/inspect/topology` and the delta stream —
 * the initial-sync-then-delta shape `cell.wire.Peering.announceTo` uses for
 * peers, kept consistent here by one monitor:
 *
 * - a snapshot is the view plus the current [seq];
 * - every delta increments [seq] and is emitted while the same monitor is held,
 *   so a client that applies events with `seq > snapshot.seq` sees each change
 *   exactly once, in the order it happened.
 *
 * Holding a monitor across [emit] is safe because emission is a non-blocking
 * hand-off to per-client bounded queues ([SseBroadcaster]); nothing here waits
 * on a socket, and nothing here touches a cell — reading topology raises no
 * attention and subscribes to nothing (invariant P6).
 */
internal class InspectorModel(
    private val registry: LocationRegistry,
    hosts: Map<String, ManagedHost>,
    private val cellNames: Map<CellRef, String>,
    /** Non-blocking sink for one serialized SSE frame. */
    private val emit: (String) -> Unit,
) {
    private val lock = Any()
    private val hostNames: Map<ManagedHost, String> = hosts.entries.associate { (name, host) -> host to name }

    private val nodes = LinkedHashMap<CellRef, Node>()
    private val edges = LinkedHashMap<UUID, Edge>()

    /** Derived `PortRef.id -> declared port name` per cell (PN-1 derivation, inverted). */
    private val portNames = HashMap<CellRef, Map<UUID, String>>()

    private var seq = 0L

    /**
     * Adopt everything the registry already holds, without emitting deltas —
     * the "initial sync" half. Safe to call after the hooks are installed: a
     * ref or link a hook already added is simply already present.
     */
    fun sync() = synchronized(lock) {
        registry.localRefs().forEach { ref -> nodes.getOrPut(ref) { nodeOf(ref) } }
        topologyLinks().forEach { link -> edges.getOrPut(link.id) { edgeOf(link) } }
    }

    /**
     * Every live topology edge — the link half of the initial sync.
     *
     * Single point of contact with the registry's topology projection, on
     * purpose: `LocationRegistry.topology` is private and exposed through
     * read-only projections (T03), of which [LocationRegistry.all] is the one
     * the inspector needs.
     */
    private fun topologyLinks(): Set<TopologyLink> = registry.all()

    fun snapshot(): TopologySnapshot = synchronized(lock) {
        TopologySnapshot(seq, nodes.values.toList(), edges.values.toList())
    }

    fun snapshotJson(): String = inspectorJson.encodeToString(snapshot())

    /** Has this view ever been told [ref] is published here? (404 vs 200 at the routes.) */
    fun knows(ref: CellRef): Boolean = synchronized(lock) { ref in nodes }

    /**
     * `GET /api/inspect/cell/{ref}` — the contract's "[Node] plus" body, built
     * by *merging* the encoded node with M1's two extra fields rather than by
     * restating the node's own. One source for the shared half means a detail
     * response and the same cell in the snapshot can never disagree.
     *
     * Null when the view has never seen [ref] published (a 404 at the route).
     */
    fun detailJson(ref: CellRef): String? = synchronized(lock) {
        val node = nodes[ref] ?: return@synchronized null
        val links = linkCounts(node.ref)
        buildJsonObject {
            inspectorJson.encodeToJsonElement(node).jsonObject.forEach { (key, value) -> put(key, value) }
            // the band lives on the cell object, out of reach without new
            // kernel surface the ticket forbids — the contract's null
            put("attention", JsonNull)
            put("links", inspectorJson.encodeToJsonElement(links))
        }.toString()
    }

    /**
     * The per-cell link census, counted off this view's own edges — which are
     * the registry `TopologyIndex` projection ([topologyLinks]) materialized
     * under [lock]. Counting here rather than re-reading the index keeps the
     * detail panel consistent with the canvas the client has already drawn.
     */
    private fun linkCounts(encodedRef: String): LinkCounts {
        var inbound = 0
        var outbound = 0
        var taps = 0
        edges.values.forEach { edge ->
            if (edge.role == Edge.OBSERVE) {
                if (edge.from.ref == encodedRef || edge.to.ref == encodedRef) taps += 1
                return@forEach
            }
            if (edge.to.ref == encodedRef) inbound += 1
            if (edge.from.ref == encodedRef) outbound += 1
        }
        return LinkCounts(inbound = inbound, outbound = outbound, taps = taps)
    }

    /**
     * `state.summary` (contract §SSE): emitted only for a cell with an active
     * observe subscription, once per settled effective change plus the
     * subscription's own immediate catch-up. Rides the same monotonic [seq] as
     * the topology deltas — one stream, one gap detector.
     */
    fun stateSummary(ref: CellRef, reading: StateReading) = synchronized(lock) {
        emitEvent(Event.STATE_SUMMARY, buildJsonObject {
            put("ref", encode(ref))
            put("cardinality", ValueEncoder.cardinality(reading.value))
            put("frontier", stampJson(reading.frontier))
            put("staleMs", reading.staleMs)
        })
    }

    /** `{"source": …, "counter": …}` or JSON null — the contract's `frontier`. */
    private fun stampJson(stamp: Timestamp?): JsonElement =
        stamp?.let { inspectorJson.encodeToJsonElement(WaveStamp(it.sourceId.toString(), it.counter)) } ?: JsonNull

    /**
     * A local publish. A ref the view has not seen is a new node; a ref it has
     * is a re-publish (`resumeHost`, a returning migration) — the node is
     * refreshed and reported as a [Event.LIFECYCLE] change rather than a
     * duplicate add.
     */
    fun published(ref: CellRef) = synchronized(lock) {
        val known = ref in nodes
        val node = nodeOf(ref)
        nodes[ref] = node
        if (known) {
            emitEvent(Event.LIFECYCLE, buildJsonObject {
                put("ref", node.ref)
                put("lifecycle", node.lifecycle)
                put("generation", node.generation)
            })
        } else {
            emitEvent(Event.TOPOLOGY_NODE, buildJsonObject {
                put("op", Event.ADDED)
                put("node", inspectorJson.encodeToJsonElement(node))
            })
        }
    }

    fun unpublished(ref: CellRef) = synchronized(lock) {
        // the hook fires after the location is gone, so the removal payload is
        // served from the view — the last node the client was told about
        val node = nodes.remove(ref) ?: return@synchronized
        portNames.remove(ref)
        emitEvent(Event.TOPOLOGY_NODE, buildJsonObject {
            put("op", Event.REMOVED)
            put("node", inspectorJson.encodeToJsonElement(node))
        })
    }

    fun linked(link: TopologyLink) = synchronized(lock) {
        val edge = edgeOf(link)
        edges[link.id] = edge
        emitEvent(Event.TOPOLOGY_LINK, buildJsonObject {
            put("op", Event.ADDED)
            put("edge", inspectorJson.encodeToJsonElement(edge))
        })
    }

    fun unlinked(id: UUID) = synchronized(lock) {
        if (edges.remove(id) == null) return@synchronized
        // contract: a removal carries only the id
        emitEvent(Event.TOPOLOGY_LINK, buildJsonObject {
            put("op", Event.REMOVED)
            putJsonObject("edge") { put("id", id.toString()) }
        })
    }

    /**
     * Liveness frame. It re-states the *current* seq without consuming one, so
     * it is inert for an up-to-date client and a gap signal for one that lost a
     * delta while the graph was quiet.
     */
    fun heartbeat() = synchronized(lock) {
        emit(frame(seq, Event.HEARTBEAT, JsonObject(emptyMap())))
    }

    private fun emitEvent(kind: String, payload: JsonObject) {
        seq += 1
        emit(frame(seq, kind, payload))
    }

    private fun frame(seq: Long, kind: String, payload: JsonObject): String =
        inspectorJson.encodeToString(Event(seq, kind, payload))

    private fun nodeOf(ref: CellRef): Node {
        // the class is what the registry captured at publish time; everything
        // structural comes from its generated descriptor, the authoritative
        // runtime metadata — no reflection beyond this lookup
        val type = registry.describe(ref)
        val descriptor = type?.let { ContractRegistry.cellDescriptor(it) }
        val host = registry.locate(ref)
        return Node(
            ref = encode(ref),
            name = cellNames[ref],
            typeFqn = type?.name?.replace('$', '.') ?: UNKNOWN_TYPE,
            color = descriptor?.color?.name,
            manifests = descriptor?.manifest?.map { it.name }?.sorted() ?: emptyList(),
            ports = descriptor?.ports?.map { NodePort(it.name, it.direction.name, it.contractFqn) } ?: emptyList(),
            host = host?.let { hostNames[it] ?: defaultHostName(it) },
            // M0 is single-process; peer introspection is M5
            net = Node.LOCAL_NET,
            // the registry knows published-or-not, not suspended-or-not; a
            // published ref is HOT until a kernel seam can say otherwise
            lifecycle = Node.HOT,
            generation = host?.generationOf(ref) ?: 0L,
            // components are M4
            graph = null,
        )
    }

    private fun edgeOf(link: TopologyLink): Edge = Edge(
        id = link.id.toString(),
        from = endpointOf(link.from),
        to = endpointOf(link.to),
        // every edge in the topology index comes from `ManagedHost.connect`,
        // which links consume-role; taps (`FanOutlet.tap`) are not indexed
        role = Edge.CONSUME,
        // fusion is not cheaply detectable in M0 — null, never a guess
        fused = null,
    )

    private fun endpointOf(port: PortRef): Endpoint =
        Endpoint(ref = port.cell?.let(::encode) ?: UNKNOWN_REF, port = portNameOf(port))

    /**
     * The declared name behind a [PortRef]. A hosted cell's port ref is derived
     * from `(ownerRef, name)` (PN-1), so the descriptor's port names re-derive
     * the exact ids the topology recorded. Falls back to the raw port id for a
     * cell with no generated descriptor — an honest opaque handle rather than a
     * fabricated name.
     */
    private fun portNameOf(port: PortRef): String {
        val cell = port.cell ?: return port.id.toString()
        return portNamesOf(cell)[port.id] ?: port.id.toString()
    }

    private fun portNamesOf(cell: CellRef): Map<UUID, String> = portNames.getOrPut(cell) {
        val descriptor = registry.describe(cell)?.let { ContractRegistry.cellDescriptor(it) }
        descriptor?.ports?.associate { PortRef.of(cell, it.name).id to it.name } ?: emptyMap()
    }

    private companion object {
        /** A ref this registry never saw published — it cannot be typed. */
        const val UNKNOWN_TYPE = "<unknown>"

        /** A free-standing endpoint (`Use.fixed`) owns no cell. */
        const val UNKNOWN_REF = ""

        /** The contract's ref encoding — shared with the route parser's inverse. */
        fun encode(ref: CellRef): String = InspectorServer.encodeRef(ref)

        fun defaultHostName(host: ManagedHost): String = "host-" + host.ref.id.toString().substringBefore('-')
    }
}
