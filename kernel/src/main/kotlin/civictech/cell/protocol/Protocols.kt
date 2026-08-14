package civictech.cell.protocol

import civictech.cell.link.Link
import civictech.cell.link.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry
import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
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
@Protocol("topology-order", ProtocolDirection.DOWNSTREAM, band = 0)
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
 * A port that anchors its own [ProtocolSupport] instead of leaving it in
 * `ProtocolSupport.registries` (computenet-7iyy). Implemented by the
 * hosted-cell ports — `FanInlet`, `FanOutlet`, `FeedbackInlet`, which are the
 * kernel's only [civictech.cell.link.Linked] port classes: these are the ports
 * a [civictech.cell.Cell] owns, so they are the ports whose retention retains a
 * cell. (`FanInlet`/`FanOutlet` also implement
 * [civictech.cell.port.DerivedPortRef]; `FeedbackInlet` does not, so the two
 * sets are close but not equal.)
 *
 * **Why the slot has to exist.** `ProtocolSupport` holds caller-supplied
 * handler closures, and a closure captures whatever its call site captures —
 * for `FanInlet.onEdgeEvent`, the inlet itself. A JVM-global `WeakHashMap`
 * holds its values strongly, so such a value reaches its own key and the entry
 * (with the port, and through the port's served implementation the owning cell)
 * is immortal, reclaimable only by an explicit `unbind` that a dropped-without-
 * despawn owner never reaches.
 *
 * Weakening the map's value — computenet-w5sm's resolution for `PortRegistry` —
 * does not transfer, because it rests on an anchor `ProtocolSupport` did not
 * have: there, the *owner* holds its ports, so weak values in the registry lose
 * nothing. Nothing held a port's `ProtocolSupport`, so a weak value would let a
 * live port's handlers vanish at the next collection. This interface is that
 * missing anchor: the port holds its support, the owner holds the port
 * (`registerPort`'s contract), and no global root holds either.
 *
 * Implementations only declare the field; [ProtocolSupport.of] and
 * [ProtocolSupport.unbind] are the only readers and writers, both under the
 * `registries` monitor.
 */
internal interface ProtocolAnchored {
    /** Storage only — go through [ProtocolSupport.of]. */
    var protocolSupport: ProtocolSupport?
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
// PN-9 (leak fix): no `port` field is stored, and [ownerRef] is weak. A stored
// `port`, or a strong `owner` ref (owner → its ports), would pin every port's
// ProtocolSupport and its handler closures for the JVM lifetime — hosted cells
// then never collect, so the whole suite's ports accumulate in one test JVM. A
// GC'd owner means the cell is dead, so relay correctly stops (a live cell is
// always strongly held by its host).
//
// computenet-7iyy closed the edge PN-9 could not: [handlers]/[relays] hold
// caller-supplied closures, which routinely capture the port they were
// registered on, so a globally-rooted support pins its port anyway. See
// [ProtocolAnchored] and [of].
class ProtocolSupport private constructor() {
    private val handlers = mutableMapOf<ProtocolId, (Link, Any) -> Unit>()
    private val relays = mutableMapOf<ProtocolId, (Any) -> Boolean>()
    @Volatile private var ownerRef: java.lang.ref.WeakReference<Any>? = null

    /**
     * Boundary-policy hook (spec 40/43 seam 3, decided 93 I-28, W4.1): applied
     * to every arriving `PORT_PROTOCOL` message before this port's own local
     * [handlers] see it — e.g. an attention `ceiling` clamp
     * (`slot.level = min(asserted, ceiling)`, the fold/band-gating in
     * [civictech.cell.control.AttentionSupport] left untouched) or a
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
        val incoming = message as? ProtocolTraversal
        if (incoming != null && !incoming.visitedEdges.add(link.id)) return
        val payload = incoming?.payload ?: message
        inboundFilter(id, payload)?.let { handlers[id]?.invoke(link, it) }

        val terminal = relays[id] ?: return
        if (terminal(payload)) return
        val descriptor = requireNotNull(ProtocolRegistry.protocol(id.name))
        val cell = ownerRef?.get() ?: return
        // Lazy wrap (hot-path, T03): a leaf with no relay for [id] never builds a
        // traversal at all. Only a relaying hop needs one — reuse the incoming
        // traversal if this message already carries one, otherwise mint a fresh
        // visited-edge set seeded with this hop so a later second edge into the
        // same traversal is caught (G-36).
        val outgoing = incoming
            ?: ProtocolTraversal(ConcurrentHashMap.newKeySet<UUID>().also { it.add(link.id) }, payload)
        PortRegistry.of(cell).names().forEach { name ->
            val linkedPort = PortRegistry.of(cell)[name] as? Linked ?: return@forEach
            linkedPort.linking.links.forEach { next ->
                if (next.id in outgoing.visitedEdges) return@forEach
                when (descriptor.direction) {
                    ProtocolDirection.UPSTREAM -> if (next.toPort === linkedPort) {
                        send(next, id, outgoing, upstream = true)
                    }
                    ProtocolDirection.DOWNSTREAM -> if (next.fromPort === linkedPort) {
                        send(next, id, outgoing, upstream = false)
                    }
                }
            }
        }
    }

    fun handles(id: ProtocolId): Boolean = id in handlers

    companion object {
        /**
         * Fallback storage for ports that carry no [ProtocolAnchored] slot —
         * `Use.fixed` endpoints, test doubles, anything outside the hosted-cell
         * port classes. Such a port is not owned by a cell, so pinning it pins
         * only itself.
         *
         * It is deliberately *not* the general store any more (computenet-7iyy):
         * a JVM-global `WeakHashMap<Port, ProtocolSupport>` holds its values
         * strongly and reclaims an entry only when its key stops being strongly
         * reachable, so every entry whose value can reach its own key is
         * immortal — and the handler closures in a value reach their port
         * routinely (`FanInlet.onEdgeEvent` captures the inlet). See
         * [ProtocolAnchored].
         */
        private val registries = Collections.synchronizedMap(WeakHashMap<Port, ProtocolSupport>())

        /**
         * The port's support, created on first use. An anchored port keeps it in
         * its own field, so it lives exactly as long as the port and never
         * enters [registries]; anything else falls back to the global map.
         *
         * T04 finding 3: getOrPut on a synchronizedMap is two monitor
         * acquisitions (get, then put), not atomic — a racing constructor can
         * discard the first instance (and the Saturation relay/ownerRef it
         * carries). Explicit synchronized(registries) matches Attention.kt's
         * existing correct form (control/Attention.kt companion `of`), and the
         * anchored branch shares the same monitor so it is equally atomic.
         */
        fun of(port: Port): ProtocolSupport = synchronized(registries) {
            if (port is ProtocolAnchored) {
                port.protocolSupport ?: ProtocolSupport().also { port.protocolSupport = it }
            } else {
                registries.getOrPut(port) { ProtocolSupport() }
            }
        }

        /** Associates every currently registered port with its owning cell. */
        fun bind(owner: Any) {
            val ports = PortRegistry.of(owner)
            val ref = java.lang.ref.WeakReference(owner)
            ports.names().forEach { name -> ports[name]?.let { of(it).ownerRef = ref } }
        }

        /**
         * Drop a despawned cell's protocol state — its anchored slots and its
         * [registries] entries alike.
         *
         * Since computenet-7iyy this is an *eagerness* optimization, not a leak
         * bound: an anchored port's support dies with the port, which dies with
         * its owner, so a dropped cell is collectable whether or not anyone
         * calls this. It stays because despawn means the handlers are finished
         * *now*, while the port object may outlive the despawn (a caller still
         * holding the cell) and must not keep serving protocol traffic.
         */
        fun unbind(owner: Any) {
            val ports = PortRegistry.of(owner)
            ports.names().forEach { name ->
                ports[name]?.let { port ->
                    synchronized(registries) {
                        if (port is ProtocolAnchored) port.protocolSupport = null else registries.remove(port)
                    }
                }
            }
        }

        private fun send(link: Link, id: ProtocolId, traversal: ProtocolTraversal, upstream: Boolean) {
            if (upstream) Protocols.sendUpstream(link, id, traversal)
            else Protocols.sendDownstream(link, id, traversal)
        }
    }
}

/** Metadata-only traversal state; deliberately unrelated to MessageContext. */
private data class ProtocolTraversal(
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
