package civictech.cell.protocol

import civictech.cell.link.Link
import civictech.cell.link.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry
import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.nature.ProtocolCardinality
import civictech.nature.ProtocolDirection
import civictech.nature.ProtocolRegistry
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Id of a generic protocol stacked on a data link (spec 12, G-13 minimal). */
data class ProtocolId(val name: String)

/**
 * Contract-backed identity for `TopologyOrder` (spec 41 point 4, G-39 phase
 * B): edge events travel downstream, broadcast to every subscriber — giving
 * `ProtocolRegistry` a real descriptor (band/lane/direction/contractId) so
 * `PORT_PROTOCOL` host dispatch and cross-peer capability negotiation treat
 * `EdgeOpen`/`EdgeClose` uniformly with attention/suspension/etc, rather than
 * only the direct in-process `ProtocolSupport.deliver` path handshake() uses.
 */
@Contract(management = true)
@Protocol("topology-order", ProtocolDirection.DOWNSTREAM, band = 0, lane = "topology-order", cardinality = ProtocolCardinality.FAN_OUT_BROADCAST)
fun interface TopologyOrderProtocol {
    fun edgeEvent(message: EdgeEvent)
}

/**
 * Well-known generic protocols and their direction relative to data flow
 * (spec 12): attention travels upstream (consumer → producer), suspension
 * notices travel downstream. Direction is expressed by which send helper a
 * protocol uses — links know their data direction (from = producer side).
 */
object Protocols {
    /** spec 34: `Attention(level)` aggregated per cell, quantized to bands. */
    val Attention = ProtocolId("attention")

    /** spec 34 decision 3, 20/22 (G-40): typed Stall/Resume frontier events. */
    val Suspension = ProtocolId("suspension")

    /** spec 20/22 (G-40): `Progress(sourceId, thru)` absorb-ack advancing a watermark. */
    val Progress = ProtocolId("progress")

    /** spec 20/22: topology changes ordered with the data carried by a link. */
    val TopologyOrder = ProtocolId("topology-order")

    /** spec 32/34: retractable intake backpressure, traveling upstream. */
    val Saturation = ProtocolId("saturation")

    /** spec 20/21 §Pull, G-18 residual, decided in 93 I-16: on-demand state pull. */
    val StateRequest = ProtocolId("state-request")

    /**
     * Deliver [message] to the link's producer-side port (against data
     * flow). When no local port is reachable (a bridged link, spec 41
     * point 4), falls back to [Link.protocolBridge] when the peer has
     * negotiated support for [id] (G-35 phase B) — the reverse bridge path
     * a cross-host link already maintains for re-resolution. [TopologyOrder]
     * always crosses: EdgeOpen/EdgeClose are their own decided WireFrame
     * event type (G-39 phase B), not subject to G-35's contract-backed
     * capability negotiation (they have no `ProtocolRegistry` descriptor).
     */
    fun sendUpstream(link: Link, id: ProtocolId, message: Any) {
        val port = link.fromPort
        if (port != null) {
            ProtocolSupport.of(port).deliver(id, link, message)
        } else if (id == TopologyOrder || id in link.protocolCapabilities) {
            link.protocolBridge?.send(id, message, upstream = true)
        }
    }

    /** Deliver [message] to the link's consumer-side port (with data flow); see [sendUpstream]. */
    fun sendDownstream(link: Link, id: ProtocolId, message: Any) {
        val port = link.toPort
        if (port != null) {
            ProtocolSupport.of(port).deliver(id, link, message)
        } else if (id == TopologyOrder || id in link.protocolCapabilities) {
            link.protocolBridge?.send(id, message, upstream = false)
        }
    }
}

/**
 * Per-port generic-protocol sub-channels (G-13 minimal): one handler per
 * [ProtocolId], sharing the port's existing links and requiring no
 * cell-specific logic — no per-message cost beyond one map lookup (P2).
 * Ports acquire support lazily via [of]; unhandled protocols drop silently.
 *
 * ponytail: delivery is synchronous on the sender's thread — fine for
 * protocol metadata (attention state is thread-safe by construction) and for
 * the single-threaded simulation; queue-hop delivery through the owning host
 * is the upgrade path if a handler ever needs to touch cell state from a
 * threaded production host. Endpoint objects are in-process only — generic
 * protocols do not cross the wire yet (bridged links have null endpoints).
 */
// PN-9 (leak fix): the constructor param is NOT stored, and [ownerRef] is weak.
// [registries] is a WeakHashMap keyed on the port; a stored `port` field, or a
// strong `owner` ref (owner → its ports = the keys), would pin every port's
// ProtocolSupport and its handler closures for the JVM lifetime — hosted cells
// then never collect, so the whole suite's ports accumulate in one test JVM. The
// `port` field was dead (never read); a GC'd owner means the cell is dead, so
// relay correctly stops (a live cell is always strongly held by its host). PN-9's
// extra per-inlet policy state made this latent leak exceed the default test heap.
class ProtocolSupport private constructor(port: Port) {
    private val handlers = mutableMapOf<ProtocolId, (Link, Any) -> Unit>()
    private val relays = mutableMapOf<ProtocolId, (Any) -> Boolean>()
    @Volatile private var ownerRef: java.lang.ref.WeakReference<Any>? = null

    /**
     * Boundary-policy hook (spec 40/43 seam 3, decided 93 I-28, W4.1): applied
     * to every arriving `PORT_PROTOCOL` message before this port's own local
     * [handlers] see it — e.g. an attention `ceiling` clamp
     * (`slot.level = min(asserted, ceiling)`, the fold/band-gating in
     * [civictech.cell.attention.AttentionSupport] left untouched) or a
     * `minAuth`/`ratePerWindow` refusal. Returning null dead-letters the
     * message for local handling (relay to further hops, if any, still sees
     * the original). Identity by default — zero cost, today's behavior,
     * byte-for-byte (P2/P6).
     */
    @Volatile
    var inboundFilter: (ProtocolId, Any) -> Any? = { _, message -> message }

    fun handle(id: ProtocolId, handler: (Link, Any) -> Unit) {
        handlers[id] = handler
    }

    /**
     * Enables descriptor-directed, hop-by-hop propagation for [id].  The
     * predicate is evaluated after local delivery; true makes this owner a
     * protocol terminal.  Traversals carry their own epoch and immutable
     * visited-edge set, so cycles and duplicate paths expand each edge at
     * most once without entering the data-wave context domain (G-36).
     */
    fun relay(id: ProtocolId, terminal: (Any) -> Boolean = { false }) {
        requireNotNull(ProtocolRegistry.protocol(id.name)) { "unknown protocol ${id.name}" }
        relays[id] = terminal
    }

    fun deliver(id: ProtocolId, link: Link, message: Any) {
        val traversal = (message as? ProtocolTraversal)
            ?: ProtocolTraversal(UUID.randomUUID(), ConcurrentHashMap.newKeySet(), message)
        if (!traversal.visitedEdges.add(link.id)) return
        inboundFilter(id, traversal.payload)?.let { handlers[id]?.invoke(link, it) }

        val terminal = relays[id] ?: return
        if (terminal(traversal.payload)) return
        val descriptor = requireNotNull(ProtocolRegistry.protocol(id.name))
        val cell = ownerRef?.get() ?: return
        PortRegistry.of(cell).names().forEach { name ->
            val linkedPort = PortRegistry.of(cell)[name] as? Linked ?: return@forEach
            linkedPort.linking.links.forEach { next ->
                if (next.id in traversal.visitedEdges) return@forEach
                when (descriptor.direction) {
                    ProtocolDirection.UPSTREAM -> if (next.toPort === linkedPort) {
                        send(next, id, traversal, upstream = true)
                    }
                    ProtocolDirection.DOWNSTREAM -> if (next.fromPort === linkedPort) {
                        send(next, id, traversal, upstream = false)
                    }
                }
            }
        }
    }

    fun handles(id: ProtocolId): Boolean = id in handlers

    companion object {
        // ponytail: JVM-global weak map, same pattern as PortRegistry
        private val registries = Collections.synchronizedMap(WeakHashMap<Port, ProtocolSupport>())

        fun of(port: Port): ProtocolSupport = registries.getOrPut(port) { ProtocolSupport(port) }

        /** Associates every currently registered port with its owning cell. */
        fun bind(owner: Any) {
            val ports = PortRegistry.of(owner)
            val ref = java.lang.ref.WeakReference(owner)
            ports.names().forEach { name -> ports[name]?.let { of(it).ownerRef = ref } }
        }

        /**
         * PN-9 (leak bound): drop a despawned cell's ports from [registries]. The
         * map's values (handler closures) reference their port keys, so a bare
         * WeakHashMap never reclaims them; explicit eviction on teardown keeps
         * retention proportional to live cells rather than every cell ever hosted.
         */
        fun unbind(owner: Any) {
            val ports = PortRegistry.of(owner)
            ports.names().forEach { name -> ports[name]?.let { registries.remove(it) } }
        }

        private fun send(link: Link, id: ProtocolId, traversal: ProtocolTraversal, upstream: Boolean) {
            if (upstream) Protocols.sendUpstream(link, id, traversal)
            else Protocols.sendDownstream(link, id, traversal)
        }
    }
}

/** Metadata-only traversal state; deliberately unrelated to MessageContext. */
private data class ProtocolTraversal(
    val epoch: UUID,
    val visitedEdges: MutableSet<UUID>,
    val payload: Any,
)

/** In-band logical-edge lifecycle markers (spec 41 point 4, G-39 phase B: wire-crossing PORT_PROTOCOL frames). */
@kotlinx.serialization.Serializable
sealed interface EdgeEvent

@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("EdgeOpen")
data object EdgeOpen : EdgeEvent

@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("EdgeClose")
data object EdgeClose : EdgeEvent
