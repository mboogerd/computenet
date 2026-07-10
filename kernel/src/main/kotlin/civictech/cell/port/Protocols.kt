package civictech.cell.port

import java.util.*

/** Id of a generic protocol stacked on a data link (spec 12, G-13 minimal). */
data class ProtocolId(val name: String)

/**
 * Well-known generic protocols and their direction relative to data flow
 * (spec 12): attention travels upstream (consumer → producer), suspension
 * notices travel downstream. Direction is expressed by which send helper a
 * protocol uses — links know their data direction (from = producer side).
 */
object Protocols {
    /** spec 34: `Attention(level)` aggregated per cell, quantized to bands. */
    val Attention = ProtocolId("attention")

    /** spec 34 decision 3: hosts announce parked/replayed cells downstream. */
    val Suspension = ProtocolId("suspension")

    /** Deliver [message] to the link's producer-side port (against data flow). */
    fun sendUpstream(link: Link, id: ProtocolId, message: Any) {
        link.fromPort?.let { ProtocolSupport.of(it).deliver(id, link, message) }
    }

    /** Deliver [message] to the link's consumer-side port (with data flow). */
    fun sendDownstream(link: Link, id: ProtocolId, message: Any) {
        link.toPort?.let { ProtocolSupport.of(it).deliver(id, link, message) }
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
class ProtocolSupport {
    private val handlers = mutableMapOf<ProtocolId, (Link, Any) -> Unit>()

    fun handle(id: ProtocolId, handler: (Link, Any) -> Unit) {
        handlers[id] = handler
    }

    fun deliver(id: ProtocolId, link: Link, message: Any) {
        handlers[id]?.invoke(link, message)
    }

    companion object {
        // ponytail: JVM-global weak map, same pattern as PortRegistry
        private val registries = Collections.synchronizedMap(WeakHashMap<Port, ProtocolSupport>())

        fun of(port: Port): ProtocolSupport = registries.getOrPut(port) { ProtocolSupport() }
    }
}
