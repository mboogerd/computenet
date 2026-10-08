package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.link.Linked
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

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
     * Identity-keyed weak reference: contract APIs may be JDK proxies whose
     * `equals`/`hashCode` dispatch into cell code, so ordinary [WeakHashMap]
     * keys are not safe for this reverse marker.
     */
    private class ApiReference(
        api: Any,
        queue: ReferenceQueue<Any>? = null,
    ) : WeakReference<Any>(api, queue) {
        private val identityHash = System.identityHashCode(api)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ApiReference) return false
            val api = get() ?: return false
            return api === other.get()
        }
    }

    private val bypassQueue = ReferenceQueue<Any>()
    private val bypassTargets = ConcurrentHashMap<ApiReference, Unit>()

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
     * Records that [api] was attached to an outlet without a target-side link
     * handshake. The marker is installed before the attachment becomes
     * visible, so the first bypass delivery cannot race root classification.
     * It remains for the API's lifetime: an unlinked bypass has no target-side
     * topology record from which to prove that every such feed disappeared,
     * so retaining `unknown` is the conservative disposition.
     */
    fun markBypassTarget(api: Any) {
        reapBypassTargets()
        bypassTargets[ApiReference(api, bypassQueue)] = Unit
    }

    private fun isBypassTarget(api: Any?): Boolean {
        api ?: return false
        reapBypassTargets()
        return bypassTargets.containsKey(ApiReference(api))
    }

    private fun reapBypassTargets() {
        while (true) {
            val stale = bypassQueue.poll() as? ApiReference ?: return
            bypassTargets.remove(stale)
        }
    }

    /**
     * Whether [port]'s owning cell can receive another source's wave through
     * an open inbound link or a link-bypassing attachment, or `null` when
     * [port] has no registered owner. A registered inlet alone is not enough:
     * an external call into it has no [civictech.cell.CurrentContext] and the
     * cell's outlet genuinely originates its own wave. By contrast, an open
     * link or a `Use.fixed`/un-negotiated attachment preserves the producer's
     * context and makes every unpublished outlet on the target owner
     * conservatively non-root.
     *
     * This is a structural query over the owning cell's existing
     * [PortRegistry], plus an identity-keyed weak marker for APIs actually
     * attached through bypass paths: no JVM-wide port scan or emission history
     * is involved. The registry and API references are weak, so released graph
     * objects are not retained and an absent owner degrades to `null`.
     */
    fun hasInboundWavePath(port: Port): Boolean? {
        val registry = table[port]?.registry?.get() ?: return null
        return registry.names().toList().any { name ->
            val candidate = registry[name]
            val openInbound = candidate is Linked &&
                candidate.linking.links.any { it.to == candidate.ref }
            val bypassFed = candidate is Use<*> && isBypassTarget(candidate.call)
            openInbound || bypassFed
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
