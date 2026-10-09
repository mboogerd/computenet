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
    internal class EntryObservation {
        @Volatile
        var externalEntryObserved: Boolean = false
            private set

        @Volatile
        var reactiveEntryObserved: Boolean = false
            private set

        /** Monotone entry classification: settled kinds need no further shared write. */
        fun observe(reactive: Boolean) {
            if (reactive) {
                if (!reactiveEntryObserved) reactiveEntryObserved = true
            } else {
                if (!externalEntryObserved) externalEntryObserved = true
            }
        }
    }

    private class Registration(
        val identity: PortIdentity,
        val registry: WeakReference<PortRegistry>,
        val entryObservation: EntryObservation = EntryObservation(),
    )

    private val table = Collections.synchronizedMap(WeakHashMap<Port, Registration>())

    /** Test diagnostic: invoked with the key of every read of [table]. */
    @Volatile
    internal var onTableRead: ((Port) -> Unit)? = null

    private fun registration(port: Port): Registration? {
        onTableRead?.invoke(port)
        return table[port]
    }

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
            val registration = Registration(
                identity = PortIdentity(owner.ref, name),
                registry = WeakReference(PortRegistry.of(owner)),
            )
            table[port] = registration
            // The inlet holds its own observation handle so its dispatch path
            // never reads [table]; a re-stamp rebinds it to the new registration.
            (port as? FanInlet<*>)?.bindEntryObservation(registration.entryObservation)
            // PN-1: a hosted cell's port gets a replay-stable ref derived from
            // (ownerRef, name) here, at the one seam that knows both. Anonymous
            // ports (not a Cell owner) are never stamped and keep generate().
            if (deriveRefs) (port as? DerivedPortRef)?.deriveRef(owner.ref, name)
        }
    }

    fun of(port: Port): PortIdentity? = registration(port)?.identity

    /**
     * Records that [api] was attached to an outlet without a target-side link
     * handshake. The marker is installed before the attachment becomes
     * visible, so the first bypass delivery cannot race root classification.
     * It remains for the API's lifetime: an unlinked bypass has no target-side
     * topology record from which to prove that every such feed disappeared,
     * so retaining `unknown` is the conservative disposition.
     *
     * The identity marker directly recognises an attachment whose API object
     * is a registered port's own `call`. A wrapper delegating into another
     * cell's inlet is instead covered by [EntryObservation.observe]: an
     * unobserved inlet is already conservative before its first call, and the
     * reactive call keeps it conservative thereafter. If that inlet was
     * previously observed as an external entry, its first opaque delegated
     * call cannot be identified before delivery; only an identity-visible
     * attachment can close that irreducible first-call ambiguity.
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

    /**
     * Whether [port] has received a reactive wave through a path that is not
     * represented by one of its currently open inbound links. A direct bypass
     * marker is authoritative even when linked feeds coexist. Without that
     * marker, a reactive entry only proves an unlinked path when no open link
     * could explain it; entry observations deliberately do not guess which of
     * several possible paths delivered a call.
     */
    fun hasUnlinkedWaveEntry(port: Use<*>): Boolean {
        if (isBypassTarget(port.call)) return true
        val reactiveEntry = registration(port)?.entryObservation?.reactiveEntryObserved == true
        if (!reactiveEntry) return false
        val hasOpenInbound = (port as? Linked)?.linking?.links?.any { it.to == port.ref } == true
        return !hasOpenInbound
    }

    private fun reapBypassTargets() {
        while (true) {
            val stale = bypassQueue.poll() as? ApiReference ?: return
            bypassTargets.remove(stale)
        }
    }

    /**
     * Whether [port]'s owning cell can receive another source's wave through
     * an open inbound link, a link-bypassing attachment, or an input whose
     * entry mode has not yet been observed; or `null` when [port] has no
     * registered owner. An external call into a registered input proves that
     * an input-bearing source can originate its own outlet wave. Until
     * then the input is conservatively reactive, which covers an opaque
     * `Use.fixed` wrapper before its first delegated delivery. A reactive
     * invocation observed later keeps the owner non-root even if it also has
     * an external entry path. An opaque wrapper first attached after an
     * external entry remains ambiguous until that first reactive invocation;
     * the wrapper object exposes no target identity to classify earlier.
     *
     * This is a structural query over the owning cell's existing
     * [PortRegistry], plus identity-keyed weak markers for APIs actually
     * attached through bypass paths and entry observations on the same weak
     * registrations: no JVM-wide port scan or emission history is involved.
     * Released graph objects are not retained and an absent owner degrades to
     * `null`.
     */
    fun hasInboundWavePath(port: Port): Boolean? {
        val registry = registration(port)?.registry?.get() ?: return null
        val inputs = mutableListOf<Port>()
        registry.names().forEach { name ->
            val candidate = registry[name] ?: return@forEach
            if (candidate is LinkFrom<*> && candidate !is Subscribe<*>) inputs += candidate
            val openInbound = candidate is Linked &&
                candidate.linking.links.any { it.to == candidate.ref }
            val bypassFed = candidate is Use<*> && isBypassTarget(candidate.call)
            val reactiveEntry = registration(candidate)?.entryObservation?.reactiveEntryObserved == true
            if (openInbound || bypassFed || reactiveEntry) return true
        }
        if (inputs.isEmpty()) return false
        return inputs.none { registration(it)?.entryObservation?.externalEntryObserved == true }
    }
}

/**
 * The `(ownerRef, registeredName)` this port was registered under, or null when
 * it was not registered on a [Cell] (e.g. an ad-hoc [Use.fixed] endpoint). Used
 * by [link] to lower typed port objects back onto the stringly-typed
 * `connect(ref, name, ...)` host call.
 */
fun Port.identity(): PortIdentity? = PortIdentities.of(this)
