package civictech.cell.port

import java.util.*

/**
 * Per-cell `name → Port` registry (G-17). Both declaration styles feed it:
 * delegate-declared ports (`by input()` / `by output()`) register at construction via
 * `provideDelegate`, explicit ports register via [registerPort].
 *
 * The registry key is the property name — hosts resolve ports by that name, and
 * client-side proxies derive it from interface getter names.
 */
class PortRegistry {
    private val ports = LinkedHashMap<String, Port>()

    fun register(name: String, port: Port) {
        require(ports.put(name, port) == null) { "Duplicate port name: $name" }
    }

    operator fun get(name: String): Port? = ports[name]

    fun names(): Set<String> = ports.keys

    companion object {
        // ponytail: JVM-global weak map; KSP-generated registries are the KMP path (C-5, M5)
        private val registries = Collections.synchronizedMap(WeakHashMap<Any, PortRegistry>())

        fun of(owner: Any): PortRegistry = registries.getOrPut(owner) { PortRegistry() }
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
