package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import java.util.Collections
import java.util.WeakHashMap

/**
 * The `(ownerRef, registeredName)` a port was registered under — the
 * back-reference that lets typed [link] recover the exact strings the
 * stringly-typed `connect(fromRef, "outlet", toRef, "inlet")` needs, straight
 * from the port object itself.
 *
 * The registry already knows both at registration time (the owning [Cell]'s
 * [Cell.ref] and the property/port name); this only records that pairing so it
 * can be read back off a bare [Port].
 */
data class PortIdentity(val owner: CellRef, val name: String)

/**
 * JVM-global weak port → [PortIdentity] table. Mirrors [PortRegistry]'s own
 * `registries` map (C-5, M5): the KSP-generated registries are the KMP path,
 * so the identity is stamped on the same JVM-only seam and never leaks into the
 * cell/port model itself — [Port] stays a pure structural contract.
 */
internal object PortIdentities {
    private val table = Collections.synchronizedMap(WeakHashMap<Port, PortIdentity>())

    /**
     * Records [identity] for [port] when it is registered on a [Cell]. Ports
     * registered on a non-cell owner (ad-hoc test scaffolding) carry no logical
     * identity and are simply not stamped — [of] returns null for them.
     */
    fun stamp(owner: Any?, name: String, port: Port) {
        if (owner is Cell) {
            table[port] = PortIdentity(owner.ref, name)
            // PN-1: a hosted cell's port gets a replay-stable ref derived from
            // (ownerRef, name) here, at the one seam that knows both. Anonymous
            // ports (not a Cell owner) are never stamped and keep generate().
            (port as? DerivedPortRef)?.deriveRef(owner.ref, name)
        }
    }

    fun of(port: Port): PortIdentity? = table[port]
}

/**
 * The `(ownerRef, registeredName)` this port was registered under, or null when
 * it was not registered on a [Cell] (e.g. an ad-hoc [Use.fixed] endpoint). Used
 * by [link] to lower typed port objects back onto the stringly-typed
 * `connect(ref, name, ...)` host call.
 */
fun Port.identity(): PortIdentity? = PortIdentities.of(this)
