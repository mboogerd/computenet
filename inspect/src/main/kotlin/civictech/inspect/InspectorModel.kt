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
 * A network-identity label: `prefix + the first uuid segment` — short enough
 * for a hull/label chip, and stable for whatever [id] names for the life of
 * the resource behind it (a [civictech.cell.host.ManagedHost] or a peer's
 * bridge egress). [InspectorModel]'s own [InspectorModel.defaultHostName] uses
 * it with `"host-"`; [Peers.labelOf] uses it with `"peer-"` — same shape, same
 * code, one definition, so the two families cannot silently drift apart (the
 * problem three independent copies of this expression used to risk: T24).
 */
internal fun labelFor(prefix: String, id: UUID): String = prefix + id.toString().substringBefore('-')

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
    /**
     * M3's flow feed, read through a supplier so the collector — which emits
     * its batches back through [flowRates] — can be constructed after this
     * model (the same read-through shape [InspectorServer] uses for its
     * `SnapshotSource`). Defaults to [FlowBinding.None]: a model built without
     * a collector reports every edge's `fused` as the contract's `null`,
     * exactly as M0 did.
     */
    private val flow: () -> FlowBinding = { FlowBinding.None },
    /** M5 — the network-host resolver; defaults to a purely local process. */
    private val peers: Peers = Peers(registry, Node.LOCAL_NET),
    /**
     * M5-EVAL — is this ref one of the inspector's own `ObserveCell` sinks
     * ([Observations.sinkRefs])? An instrument is not a subject: it never
     * becomes a node, an edge endpoint, or a component member. Without this, a
     * sink spawned on selection joins the observed cell's component and — when
     * its uuid sorts below the current minimum — *renames* it, kicking the
     * client out of the very graph it selected a cell in (the flip-flop
     * M5-COLD's report reproduced at the API level). Read through a supplier
     * for the same construction-order reason as [flow].
     */
    private val instruments: (CellRef) -> Boolean = { false },
) {
    private val lock = Any()
    private val hostNames: Map<ManagedHost, String> = hosts.entries.associate { (name, host) -> host to name }

    private val nodes = LinkedHashMap<CellRef, Node>()
    private val edges = LinkedHashMap<UUID, Edge>()

    /**
     * M5 — edge ids [declareLink] created. Held apart from the registry-fed
     * ids so [retractDangling] never retracts an edge the registry was never
     * asked about: a declared cross-boundary stream is a real local
     * subscription that outlives the peer it points at (see
     * [InspectorServer.declareLink]).
     */
    private val declared = LinkedHashSet<UUID>()

    /** Derived `PortRef.id -> declared port name` per cell (PN-1 derivation, inverted). */
    private val portNames = HashMap<CellRef, Map<UUID, String>>()

    /**
     * M4 — the connected components over [edges], kept under this same monitor
     * so a snapshot's `Node.graph` and its `GET /graphs` card can never
     * disagree. [Node]s are stored with `graph = null` and stamped on the way
     * out ([withGraph]): the component id is a property of the *partition*, not
     * of the cell, and re-stamping on read is what keeps a merge or split from
     * having to rewrite every node it touched.
     */
    private val componentIndex = ComponentIndex()

    /**
     * Graph names, anchored to a cell rather than to a component id — ids
     * change on merge and split by design, so an id-keyed name would evaporate
     * exactly when the graph got more interesting. See [nameGraph].
     */
    private val graphAnchors = LinkedHashMap<CellRef, String>()

    /** The membership last announced through `graphs.changed`; drives [publishGraphChanges]. */
    private var announced: Map<String, Set<CellRef>> = emptyMap()

    /**
     * The per-cell lifecycle last announced (M5-COLD); drives
     * [publishLifecycleChanges]. Kept beside [announced] rather than inside the
     * node records because lifecycle is *read* from the host on every stamp
     * ([stamped]) — this map is only the memory of what a client was last told,
     * so a change can be detected without polling turning into a change.
     */
    private val announcedLifecycle = HashMap<CellRef, String>()

    private var seq = 0L

    /**
     * Adopt everything the registry already holds, without emitting deltas —
     * the "initial sync" half. Safe to call after the hooks are installed: a
     * ref or link a hook already added is simply already present.
     */
    fun sync() = synchronized(lock) {
        registry.localRefs().forEach { ref ->
            if (instruments(ref)) return@forEach
            nodes.getOrPut(ref) { nodeOf(ref) }
            componentIndex.addCell(ref)
            announcedLifecycle[ref] = lifecycleOf(ref)
        }
        // M5: an inspector started *after* peering already announced picks up
        // no publish hook for the refs already mirrored. T21 closed that hole
        // outright — [LocationRegistry.remoteRefs] names every peer-announced
        // location directly, instead of the old inference from link endpoints
        // plus the replica index (which missed an unlinked, unreplicated one).
        registry.remoteRefs().forEach { ref ->
            if (instruments(ref)) return@forEach
            nodes.getOrPut(ref) { nodeOf(ref) }
            componentIndex.addCell(ref)
        }
        topologyLinks().forEach { link ->
            if (touchesInstrument(link)) return@forEach
            // a mirrored edge is only as live as the cells it names, and
            // `unpublishRemotes` clears locations without touching the topology
            // index — so a peer that left before this inspector existed leaves
            // its edges behind, with no event that could ever retract them
            if (!registry.isLocalLink(link.id) && !anchored(link)) return@forEach
            edges.getOrPut(link.id) { edgeOf(link) }
            componentIndex.addLink(link.id, link.from.cell, link.to.cell)
        }
        // the components a client's first `GET /graphs` will see: announcing
        // them again on the first tick would be a gap signal for a change that
        // never happened
        announced = componentIndex.components()
    }

    /**
     * Every live topology edge — the link half of the initial sync, and after
     * T21 the *only* place this view reads the whole edge set (the 1 Hz
     * difference against it is gone; edges arrive as
     * [LocationRegistry.onTopology] events).
     *
     * `LocationRegistry.topology` is private and exposed through read-only
     * projections (T03); [LocationRegistry.all] is the one a catch-up read
     * needs, and [LocationRegistry.swapSet] the one [retractDangling] does.
     */
    private fun topologyLinks(): Set<TopologyLink> = registry.all()

    /**
     * `GET /api/inspect/topology`, optionally scoped to one component
     * (M4's `?graph=g-…`; unfiltered stays valid and is the default).
     *
     * [seq] is the *global* sequence either way — the SSE stream stays global
     * and the client filters it (M4-BE §5), so a filtered snapshot must resume
     * from the same position an unfiltered one would.
     *
     * An id no component currently carries answers an empty snapshot rather
     * than a 404: ids change on merge and split by design, so "that graph is
     * gone" is an ordinary race the client resolves by following the
     * `graphs.changed` it will also have received, not an error.
     */
    fun snapshot(graph: String? = null): TopologySnapshot = synchronized(lock) {
        val stamped = nodes.entries.map { (ref, node) -> stamped(ref, node) }
        if (graph == null) return@synchronized TopologySnapshot(seq, stamped, edges.values.toList())
        val members = stamped.filter { it.graph == graph }
        val refs = members.mapTo(HashSet()) { it.ref }
        TopologySnapshot(
            seq = seq,
            nodes = members,
            edges = edges.values.filter { it.from.ref in refs || it.to.ref in refs },
        )
    }

    fun snapshotJson(graph: String? = null): String = inspectorJson.encodeToString(snapshot(graph))

    /**
     * Every connected component, with its members' nodes already stamped —
     * the input `GET /graphs` and `GET /search` are built from. Ordered by id
     * so both endpoints list graphs the same way between refreshes.
     */
    fun components(): List<Component> = synchronized(lock) {
        componentIndex.components().entries.sortedBy { it.key }.map { (id, refs) ->
            Component(
                id = id,
                name = nameOf(refs),
                nodes = refs.mapNotNull { ref -> nodes[ref]?.let { stamped(ref, it) } },
            )
        }
    }

    /**
     * The opt-in naming hook (M4-BE §2): label the component that currently
     * contains [anchor]. The name follows the *cell*, so it survives the
     * component growing, and it moves with the anchor when a merge or split
     * renames the component around it.
     *
     * A merge that brings two differently-named anchors into one component
     * resolves to the anchor with the lexicographically-min cell uuid — the
     * same tie-break [ComponentIndex] uses for the id, so the answer is
     * deterministic rather than dependent on which host annotated first.
     * Nothing is invented: a component holding no anchor stays `null`.
     */
    fun nameGraph(anchor: CellRef, name: String) = synchronized(lock) {
        if (graphAnchors.put(anchor, name) == name) return@synchronized
        // the partition did not move, but every card of it just changed
        emitEvent(Event.GRAPHS_CHANGED, JsonObject(emptyMap()))
    }

    /**
     * `graphs.changed` (contract §SSE): `{}`, a hint to refetch the
     * [GraphList]. Driven by the inspector's scheduler rather than from the
     * registry hooks, which coalesces a whole graph build — N publishes and M
     * links — into a single hint instead of N+M of them, and keeps the O(V+E)
     * component sweep off the thread that is linking cells.
     *
     * Compares full membership, not just the id set: growing a component
     * without displacing its minimum member leaves the id alone but still
     * changes the card the client is showing.
     */
    fun publishGraphChanges() = synchronized(lock) {
        val current = componentIndex.components()
        if (current == announced) return@synchronized
        announced = current
        emitEvent(Event.GRAPHS_CHANGED, JsonObject(emptyMap()))
    }

    /**
     * `lifecycle` (contract §SSE, M5-COLD): one `topology.node`-shaped
     * lifecycle event per cell whose observed lifecycle changed since the last
     * announcement, plus a single `graphs.changed` when any did — a suspend or
     * a wake changes a navigator card's cold pill, and nothing else would tell
     * the client to refetch it.
     *
     * Polled from the inspector's scheduler for the same reason
     * [publishGraphChanges] is: `HostManagementApi.suspend`/`resume` and
     * `drainHost`/`resumeHost` are ordinary management calls with no
     * notification hook, so there is nothing to subscribe to. The poll reads
     * host metadata only ([Heat]) — no cell is touched and no attention raised.
     *
     * A resume that republishes (`resumeHost`) is already reported by
     * [published]; that path updates [announcedLifecycle] too, so this poll
     * does not double-announce it.
     */
    fun publishLifecycleChanges() = synchronized(lock) {
        var changed = false
        nodes.keys.toList().forEach { ref ->
            val now = lifecycleOf(ref)
            if (announcedLifecycle.put(ref, now) == now) return@forEach
            changed = true
            emitEvent(Event.LIFECYCLE, buildJsonObject {
                put("ref", encode(ref))
                put("lifecycle", now)
                put("generation", nodes[ref]?.generation ?: 0L)
            })
        }
        if (changed) emitEvent(Event.GRAPHS_CHANGED, JsonObject(emptyMap()))
    }

    /** The name of whichever anchor in [refs] sorts first by uuid, or null when none is anchored. */
    private fun nameOf(refs: Set<CellRef>): String? = refs
        .filter { it in graphAnchors }
        .minByOrNull { it.id.toString() }
        ?.let(graphAnchors::get)

    /**
     * [node] with the two fields that belong to the *world* rather than to the
     * stored record resolved at read time: `graph` (a property of the current
     * partition, which changes without the cell changing) and `lifecycle` (a
     * property of the cell's host right now — see [Heat]).
     *
     * Both are stamped in one place so every reader — the snapshot, the
     * component list behind `GET /graphs`, the detail body — sees one
     * consistent answer for one cell.
     */
    private fun stamped(ref: CellRef, node: Node): Node =
        node.copy(graph = componentIndex.componentOf(ref), lifecycle = lifecycleOf(ref))

    /**
     * The contract's `"HOT" | "SUSPENDED"` for one cell, from registry and host
     * metadata only. [Heat] carries the finer distinctions (individually
     * suspended vs. on a drained host vs. held for a migration flip); the
     * contract offers two words, and a cell the kernel is not running is
     * `SUSPENDED` in both of the senses that qualify.
     */
    private fun lifecycleOf(ref: CellRef): String =
        if (Heat.of(registry, ref).isCold) Node.SUSPENDED else Node.HOT

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
        val node = nodes[ref]?.let { stamped(ref, it) } ?: return@synchronized null
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
     * `state.summary` (contract §SSE): emitted only for a cell with an observe
     * subscription — one coalesced window per [Observations.WINDOW_MS] while
     * that subscription is open (even a quiet one), plus the single trailing
     * window its release publishes. [Observations.sample] owns that cadence;
     * this is only the emission point. Rides the same monotonic [seq] as the
     * topology deltas — one stream, one gap detector.
     *
     * `changes` is additive to the four fields M1 shipped (V1A-BE): how many
     * settled effective changes this window coalesced, `0` for a quiet one. A
     * client that ignores it is still correct — `staleMs` is computed at
     * publish time, so it drops when something settled and grows across quiet
     * windows — but it cannot tell a change that landed exactly one window
     * after the previous one (identical `staleMs`) from a quiet window, and it
     * cannot say *how many* changes a window stood for. `changes` answers both
     * exactly.
     */
    fun stateSummary(summary: StateSummary) = synchronized(lock) {
        emitEvent(Event.STATE_SUMMARY, buildJsonObject {
            put("ref", encode(summary.ref))
            put("cardinality", ValueEncoder.cardinality(summary.reading.value))
            put("frontier", stampJson(summary.reading.frontier))
            put("staleMs", summary.reading.staleMs)
            put("changes", summary.changes)
        })
    }

    /** `{"source": …, "counter": …}` or JSON null — the contract's `frontier`. */
    private fun stampJson(stamp: Timestamp?): JsonElement =
        stamp?.let { inspectorJson.encodeToJsonElement(WaveStamp(it.sourceId.toString(), it.counter)) } ?: JsonNull

    /**
     * `error.deadLetter` (contract §SSE): one retained [DeadLetterRow], rides
     * the same monotonic [seq] as every other event — [Errors] is the
     * collaborator that captures and bounds these ([Errors.deadLetterRing]),
     * this is only the emission point, kept here so the one gap detector
     * covers topology, state and error events alike (mirrors [stateSummary]).
     */
    fun deadLetterEvent(row: DeadLetterRow) = synchronized(lock) {
        emitEvent(Event.ERROR_DEAD_LETTER, inspectorJson.encodeToJsonElement(row).jsonObject)
    }

    /** `error.parked` (contract §SSE): sent on change; a `count: 0` row clears. */
    fun parkedEvent(row: ParkedRow) = synchronized(lock) {
        emitEvent(Event.ERROR_PARKED, inspectorJson.encodeToJsonElement(row).jsonObject)
    }

    /** `error.restart` (contract §SSE): one observed generation increase. */
    fun restartEvent(row: RestartRow) = synchronized(lock) {
        emitEvent(Event.ERROR_RESTART, inspectorJson.encodeToJsonElement(row).jsonObject)
    }

    /**
     * `flow.rates` (contract §SSE): one 1 Hz aggregation window from
     * [FlowCollector]. Rides the same monotonic [seq] as every other event —
     * one stream, one gap detector (mirrors [stateSummary]).
     */
    fun flowRates(batch: FlowBatch) = synchronized(lock) {
        emitEvent(Event.FLOW_RATES, inspectorJson.encodeToJsonElement(batch).jsonObject)
    }

    /**
     * A local publish. A ref the view has not seen is a new node; a ref it has
     * is a re-publish (`resumeHost`, a returning migration) — the node is
     * refreshed and reported as a [Event.LIFECYCLE] change rather than a
     * duplicate add.
     */
    fun published(ref: CellRef) = synchronized(lock) {
        // the inspector's own observation sink: real in the registry, but not
        // a subject of the view it serves (see the [instruments] doc)
        if (instruments(ref)) return@synchronized
        val known = ref in nodes
        val node = nodeOf(ref)
        nodes[ref] = node
        componentIndex.addCell(ref)
        // a re-publish is how `resumeHost` reports a wake, so the announced
        // lifecycle moves here too — otherwise [publishLifecycleChanges] would
        // announce the same transition a second time on its next tick
        val lifecycle = lifecycleOf(ref)
        announcedLifecycle[ref] = lifecycle
        if (known) {
            emitEvent(Event.LIFECYCLE, buildJsonObject {
                put("ref", node.ref)
                put("lifecycle", lifecycle)
                put("generation", node.generation)
            })
        } else {
            emitEvent(Event.TOPOLOGY_NODE, buildJsonObject {
                put("op", Event.ADDED)
                put("node", inspectorJson.encodeToJsonElement(stamped(ref, node)))
            })
        }
    }

    /**
     * Any unpublish — a local despawn/migrate, a peer's announced eviction
     * (`mirrorUnpublish`), or one ref of a whole peer dropped by a disconnect
     * (`LocationRegistry.unpublishRemotes`, which T21 made notify
     * `onUnpublish` like every other removal path). Idempotent: a ref this view
     * does not hold is silently ignored, which is what lets the local
     * unpublish's two hooks and a re-announced eviction all land here.
     */
    fun unpublished(ref: CellRef) = synchronized(lock) {
        // the hook fires after the location is gone, so the removal payload is
        // served from the view — the last node the client was told about
        val node = nodes.remove(ref)?.let { stamped(ref, it) } ?: return@synchronized
        // read the component id above, before the vertex leaves the index: a
        // removal reports the graph the cell was in, not the one it is not in
        componentIndex.removeCell(ref)
        announcedLifecycle.remove(ref)
        portNames.remove(ref)
        // a despawn unpublishes but does not unlink (only an explicit
        // `Link.unlink` retracts an edge), so the flow feed's taps on this
        // cell's outlets are released from here, not from [unlinked]
        flow().dropCell(ref)
        emitEvent(Event.TOPOLOGY_NODE, buildJsonObject {
            put("op", Event.REMOVED)
            put("node", inspectorJson.encodeToJsonElement(node))
        })
        retractDangling(ref)
    }

    /**
     * T21 — retract every *mirrored* edge that [ref]'s departure just left with
     * no endpoint in this view, in the same event that removed [ref].
     *
     * This is what the retired 1 Hz `reconcilePeers` sweep did with its
     * `anchored()` check over the whole edge set: `unpublishRemotes` clears
     * *locations* and leaves the topology index alone, so a partition would
     * otherwise leave the peer's edges drawn between two cells that are gone.
     * Driven from the removal event instead of from a poll, it is also
     * O(degree-of-[ref]) rather than O(V+E) per tick — [LocationRegistry.swapSet]
     * is the incidence projection, and the edges are still in the index at hook
     * time precisely because `unpublishRemotes` does not touch it.
     *
     * Two exclusions, both carried over verbatim from the sweep's rules:
     *
     * - a **local** edge is authoritative on its own — "a despawn unpublishes
     *   but does not unlink", so only [unlinked] retracts one, however many of
     *   its endpoints have left ([LocationRegistry.isLocalLink] is the scope
     *   question, asked once per incident edge);
     * - a **declared** edge ([declareLink]) is a real local subscription the
     *   registry indexes nowhere, so it survives the peer going away — and
     *   [LocationRegistry.swapSet] never names one anyway.
     */
    private fun retractDangling(ref: CellRef) {
        registry.swapSet(ref)
            .filter { it.id in edges && it.id !in declared && !registry.isLocalLink(it.id) && !anchored(it) }
            .map { it.id }
            .forEach(::unlinked)
    }

    /**
     * Any topology edge this registry recorded — a local `connect` or a peer's
     * mirrored one, both arriving on [LocationRegistry.onTopology] since T21.
     * One handler for both: a mirrored edge is drawn exactly like a local one
     * (its producer simply is not hosted here, so [edgeOf] reports the
     * contract's `fused: null` for it). Upsert on the same id is idempotent
     * apart from re-emitting the edge, which is what a re-announcing peer
     * should produce.
     */
    fun linked(link: TopologyLink) = synchronized(lock) {
        // the producer→sink link of an observation is instrument wiring, not
        // topology — reporting it would draw the observer effect on the canvas
        if (touchesInstrument(link)) return@synchronized
        val edge = edgeOf(link)
        edges[link.id] = edge
        // undirected for component purposes: an edge means "same graph",
        // whichever way the messages run
        componentIndex.addLink(link.id, link.from.cell, link.to.cell)
        emitEvent(Event.TOPOLOGY_LINK, buildJsonObject {
            put("op", Event.ADDED)
            put("edge", inspectorJson.encodeToJsonElement(edge))
        })
    }

    fun unlinked(id: UUID) = synchronized(lock) {
        if (edges.remove(id) == null) return@synchronized
        // may split the component in two — the next read sweeps and finds out
        componentIndex.removeLink(id)
        flow().unbind(id)
        // contract: a removal carries only the id
        emitEvent(Event.TOPOLOGY_LINK, buildJsonObject {
            put("op", Event.REMOVED)
            putJsonObject("edge") { put("id", id.toString()) }
        })
    }

    /**
     * M5 — a ref a peer announced (`Peering.announceTo` → `RegistryMirrorCell`
     * → [LocationRegistry.publish]`(ref, sink)`). Fed from the registry's
     * any-publish hook, filtered to [LocationRegistry.Remote] locations by the
     * caller, so a local publish never arrives here twice.
     *
     * Upsert rather than add-once: a reconnecting peer re-announces every ref
     * it holds, and the second announcement may carry a *different* network
     * host (a new bridge egress — see [Peers]). Re-emitting `topology.node
     * added` for a node whose content changed is exactly the upsert the client
     * applies; an announcement that changes nothing emits nothing.
     */
    fun mirroredPublish(ref: CellRef) = synchronized(lock) {
        val node = nodeOf(ref)
        if (nodes.put(ref, node) == node) return@synchronized
        componentIndex.addCell(ref)
        emitEvent(Event.TOPOLOGY_NODE, buildJsonObject {
            put("op", Event.ADDED)
            // stamped, exactly like every other node emission: `graph` and
            // `lifecycle` belong to the world, not to the stored record (the
            // cross-branch desync `476d047` fixed was this call losing its stamp)
            put("node", inspectorJson.encodeToJsonElement(stamped(ref, node)))
        })
    }

    /** Does [link] still name a cell this view holds? (See [retractDangling].) */
    private fun anchored(link: TopologyLink): Boolean =
        link.from.cell?.let { it in nodes } == true || link.to.cell?.let { it in nodes } == true

    /** Does [link] touch one of the inspector's own observation sinks? (See [instruments].) */
    private fun touchesInstrument(link: TopologyLink): Boolean =
        link.from.cell?.let(instruments) == true || link.to.cell?.let(instruments) == true

    /**
     * M5 — an application-declared edge, for a cross-boundary stream the kernel
     * records nowhere.
     *
     * `ManagedHost.connect` — the one path that writes the topology index — is
     * `LinkAdmission.connect`, which resolves *both* endpoints in the host's
     * own `cells` map: a cross-JVM link is therefore not expressible as a
     * `TopologyLink` at all. What actually crosses the wire in every peered
     * demo is `outlet.streamTo(RoutedPropagate(peerRef, port, registry::deliver))`
     * — a real consume-role subscription on a real local outlet, but one no
     * index in the kernel knows about, on either side.
     *
     * So the application declares it, exactly as it annotates a graph's name
     * ([nameGraph]): the inspector never invents an edge, and never guesses one
     * from a heuristic — it reports the wiring the process says it performed.
     * The id is derived from the two endpoints, so re-declaring the same stream
     * is idempotent and survives a restart with the same edge id.
     *
     * The producing endpoint is locally hosted by construction, so the edge
     * takes M3's ordinary tap and carries a real per-message rate — declare it
     * *after* the producing cell is published, or the flow feed will answer the
     * contract's `fused: null` for it forever.
     */
    fun declareLink(from: PortRef, to: PortRef): UUID = synchronized(lock) {
        val id = declaredLinkId(from, to)
        declared += id
        if (id in edges) return@synchronized id
        val link = TopologyLink(id, from, to)
        edges[id] = edgeOf(link)
        componentIndex.addLink(id, from.cell, to.cell)
        emitEvent(Event.TOPOLOGY_LINK, buildJsonObject {
            put("op", Event.ADDED)
            put("edge", inspectorJson.encodeToJsonElement(edges.getValue(id)))
        })
        id
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

    /**
     * One node's contract shape.
     *
     * A **peer-announced** ref answers the same fields honestly rather than
     * differently: `RegistryAnnounce.published` carries a [CellRef] and nothing
     * else, so the registry captured no class for it
     * ([LocationRegistry.describe] is written on the local publish path only)
     * and there is no descriptor to read — type `<unknown>`, no color, no
     * manifests, no ports. Its process host is likewise unknowable from here
     * (`locate` answers only for [LocationRegistry.Local]), which the contract
     * covers with `host: null`; its *network* host is the one placement fact a
     * mirrored location does carry, and that is what M5 adds.
     */
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
            // M5: the JVM this ref lives on — [Peers.localNet] for a local
            // publish, the announcing peer's derived label for a mirrored one
            net = peers.netOf(ref) ?: peers.localNet,
            // stamped on the way out by [stamped], like `graph`: whether a cell
            // is running is a property of its host *now*, not of the publish
            // that recorded it (M5-COLD — see [Heat])
            lifecycle = Node.HOT,
            generation = host?.generationOf(ref) ?: 0L,
            // stamped on the way out by [withGraph] — the component id belongs
            // to the partition, not to the cell, and changes without the cell
            graph = null,
        )
    }

    /**
     * Registers [link] with the flow feed and encodes it. Binding here rather
     * than at the two call sites keeps "an edge the client has been told about"
     * and "an edge the collector is watching" the same set by construction —
     * and makes the contract's `fused` the collector's answer for that very
     * edge rather than a second, independently-derived guess.
     */
    private fun edgeOf(link: TopologyLink): Edge = Edge(
        id = link.id.toString(),
        from = endpointOf(link.from),
        to = endpointOf(link.to),
        // every edge in the topology index comes from `ManagedHost.connect`,
        // which links consume-role; taps (`FanOutlet.tap`) are not indexed
        role = Edge.CONSUME,
        fused = flow().bind(link),
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

        /**
         * A declared edge's id: derived from its two endpoints, in the same
         * name-based shape `PortRef.of` uses, so declaring the same stream
         * twice — or after a restart — yields the same `Edge.id` rather than a
         * second edge for one link.
         */
        fun declaredLinkId(from: PortRef, to: PortRef): UUID =
            UUID.nameUUIDFromBytes("inspect-declared:${from.id}->${to.id}".toByteArray())

        fun defaultHostName(host: ManagedHost): String = labelFor("host-", host.ref.id)
    }
}
