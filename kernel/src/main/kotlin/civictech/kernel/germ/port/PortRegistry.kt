package civictech.kernel.germ.port

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
 */
fun <P : Port> Any.registerPort(name: String, port: P): P =
    port.also { PortRegistry.of(this).register(name, it) }
