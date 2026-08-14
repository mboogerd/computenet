package civictech.cell.port

import java.lang.ref.WeakReference
import java.util.*

/**
 * Per-cell `name → Port` registry (G-17). Both declaration styles feed it:
 * delegate-declared ports (`by input()` / `by output()`) register at construction via
 * `provideDelegate`, explicit ports register via [registerPort].
 *
 * The registry key is the property name — hosts resolve ports by that name, and
 * client-side proxies derive it from interface getter names.
 *
 * **The registry is an index, not an owner (computenet-w5sm).** It holds each
 * [Port] through a [WeakReference], because the registry itself is the *value*
 * of a JVM-global `WeakHashMap<owner, PortRegistry>` ([registries]). A
 * `WeakHashMap` reclaims an entry only when its key stops being strongly
 * reachable and it holds its values strongly, so any strong path value → key
 * makes the entry — and its key — immortal. A strongly-held port is exactly
 * such a path in the ordinary shape of a cell:
 * `registries[cell] → PortRegistry → inlet → served implementation → cell`,
 * which closes the loop and pins every owner ever constructed (measured:
 * 61553 live registry entries and ~199 KB/iteration in
 * `WsAnnouncementStressTest`, computenet-uo75). Weak port values cut that one
 * edge; nothing else about the registry changes.
 *
 * The anchor that keeps a live owner's ports alive is the owner itself: the
 * registration contract is that a port is assigned to a property of its owner
 * (see [registerPort] — "the name must match the property it is assigned to"),
 * so a port outlives the registry entry exactly as long as its owner does. A
 * port whose registration return value is discarded and which the owner
 * therefore does not hold is *not* anchored, and [get] may start returning null
 * for it: registering a port nobody keeps was never meaningful (it can neither
 * be linked nor served), and [names] still reports it, so duplicate-name
 * detection in [register] is unaffected — name entries are never removed.
 */
class PortRegistry {
    /**
     * `name → weakly-held port`. Entries are never removed once created, so
     * [names] and [register]'s duplicate check see the full registration
     * history regardless of collection; only [get] observes a cleared referent.
     */
    private val ports = LinkedHashMap<String, WeakReference<Port>>()

    fun register(name: String, port: Port) {
        require(ports.put(name, WeakReference(port)) == null) { "Duplicate port name: $name" }
    }

    operator fun get(name: String): Port? = ports[name]?.get()

    fun names(): Set<String> = ports.keys

    companion object {
        // ponytail: JVM-global weak map; KSP-generated registries are the KMP path (C-5, M5)
        private val registries = Collections.synchronizedMap(WeakHashMap<Any, PortRegistry>())

        // T04 finding 3: getOrPut on a synchronizedMap is two monitor
        // acquisitions (get, then put), not atomic — a racing scheduler-
        // thread spawn vs. a WS-thread protocol delivery can both construct,
        // and the second put silently discards the first instance. Explicit
        // synchronized(registries) matches Attention.kt's existing correct
        // form (control/Attention.kt companion `of`).
        fun of(owner: Any): PortRegistry = synchronized(registries) { registries.getOrPut(owner) { PortRegistry() } }

        /**
         * T04 finding 3: explicit reclaim of [owner]'s registry entry, for
         * callers that tear down a cell and want its registry (and the
         * name → port index it carries) dropped immediately rather than
         * waiting on GC to notice the weak key is unreachable.
         *
         * Since computenet-w5sm this is an *eagerness* optimization only, not
         * a leak mitigation: the map no longer reaches its own keys (see the
         * class KDoc), so a dropped owner and everything its ports reach is
         * collectable whether or not anyone calls this.
         */
        internal fun release(owner: Any) {
            synchronized(registries) { registries.remove(owner) }
        }
    }
}

/**
 * Registers an explicitly-constructed port under [name] on the receiver (the owning cell).
 * The name must match the property it is assigned to, e.g.
 * `override val inlet = registerPort("inlet", FanInlet.create<SetOps<E>>())`.
 *
 * When the owner is a [civictech.cell.Cell] the port is also stamped with its
 * `(ownerRef, name)` [PortIdentity], so typed [link] can recover the wiring
 * strings from the port object itself.
 */
fun <P : Port> Any.registerPort(name: String, port: P): P =
    port.also {
        PortRegistry.of(this).register(name, it)
        PortIdentities.stamp(this, name, it)
        // CP-F3: project the generated descriptor's declared natures onto the
        // live port so the handshake can reconcile them (no-op unless the cell
        // has a generated descriptor carrying non-default natures).
        PortNatures.project(this, name, it)
    }

/**
 * Declares and registers a [FanInlet] under [name] in one call, inferring
 * `Class<Api>` from the reified type — the typed alternative to
 * `registerPort("inlet", FanInlet(Api::class.java as Class<Api>))`, removing the
 * unchecked cast from cell port declarations (05 §Solution sketch). Adopt at
 * will; existing `registerPort(name, FanInlet.create<Api>())` sites keep working.
 */
inline fun <reified Api : Any> Any.inlet(name: String): FanInlet<Api> =
    registerPort(name, FanInlet.create<Api>())

/**
 * Declares and registers a [FanOutlet] under [name] in one call, inferring
 * `Class<Api>` from the reified type. The outlet counterpart of [inlet].
 */
inline fun <reified Api : Any> Any.outlet(name: String): FanOutlet<Api> =
    registerPort(name, FanOutlet.create<Api>())
