package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.link.LinkRole
import java.lang.ref.WeakReference
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
 * JVM-global weak port → registration table. Each registration carries the
 * public [PortIdentity] plus a weak reference to the owner's existing
 * [PortRegistry]. This mirrors that registry's own weak-owner lifecycle (C-5,
 * M5): the KSP-generated registries are the KMP path, so both facts are stamped
 * on the same JVM-only seam and never leak into the cell/port model itself —
 * [Port] stays a pure structural contract.
 */
internal object PortIdentities {
    private class Registration(
        val identity: PortIdentity,
        val registry: WeakReference<PortRegistry>,
    )

    private val table = Collections.synchronizedMap(WeakHashMap<Port, Registration>())

    /**
     * Records [identity] for [port] when it is registered on a [Cell]. Ports
     * registered on a non-cell owner (ad-hoc test scaffolding) carry no logical
     * identity and are simply not stamped — [of] returns null for them.
     */
    /**
     * PN-2 control seam: revert PN-1's replay-stable derivation (ports keep the
     * fresh random ref minted at construction) while keeping everything else.
     * `DurableGlitchFreeReplayTest`'s control (b) flips this to prove PN-2's
     * baseline path carries recovery *independently* of PN-1's identity
     * derivation. Production always derives.
     */
    internal var deriveRefs = true

    fun stamp(owner: Any?, name: String, port: Port) {
        if (owner is Cell) {
            table[port] = Registration(
                identity = PortIdentity(owner.ref, name),
                registry = WeakReference(PortRegistry.of(owner)),
            )
            // PN-1: a hosted cell's port gets a replay-stable ref derived from
            // (ownerRef, name) here, at the one seam that knows both. Anonymous
            // ports (not a Cell owner) are never stamped and keep generate().
            if (deriveRefs) (port as? DerivedPortRef)?.deriveRef(owner.ref, name)
        }
    }

    fun of(port: Port): PortIdentity? = table[port]?.identity

    /**
     * Whether [port]'s owning cell currently has an open [LinkRole.Consume]
     * edge on any registered [FanInlet], or `null` when [port] has no
     * registered owner. This is a live structural query over the owning cell's
     * existing [PortRegistry]: no parallel owner/port registry or emission
     * history is involved, and an inlet linked after a wave began is visible
     * when the query is evaluated. The registry reference is weak, so a released
     * owner degrades to `null` (unknown) rather than being kept alive or
     * misclassified as a root.
     *
     * [FanInlet.linking] contains only active links, so an unlinked edge stops
     * counting immediately. Work is bounded by this one owner's registered
     * ports rather than every port created during the JVM's lifetime.
     */
    fun hasOpenConsumeInput(port: Port): Boolean? {
        val registry = table[port]?.registry?.get() ?: return null
        return registry.names().any { name ->
            val candidate = registry[name]
            candidate is FanInlet<*> &&
                candidate.linking.links.any { it.role == LinkRole.Consume }
        }
    }
}

/**
 * The `(ownerRef, registeredName)` this port was registered under, or null when
 * it was not registered on a [Cell] (e.g. an ad-hoc [Use.fixed] endpoint). Used
 * by [link] to lower typed port objects back onto the stringly-typed
 * `connect(ref, name, ...)` host call.
 */
fun Port.identity(): PortIdentity? = PortIdentities.of(this)
