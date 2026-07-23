package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import java.lang.ref.WeakReference
import java.util.*

/** A registered port's owning cell + registry name — the two strings `connect` needs. */
data class PortAddress(val cell: CellRef, val name: String)

/**
 * Per-cell `name → Port` registry (G-17). Both declaration styles feed it:
 * delegate-declared ports (`by input()` / `by output()`) register at construction via
 * `provideDelegate`, explicit ports register via [registerPort].
 *
 * The registry key is the property name — hosts resolve ports by that name, and
 * client-side proxies derive it from interface getter names.
 */
class PortRegistry internal constructor(private val owner: WeakReference<Any>? = null) {
    private val ports = LinkedHashMap<String, Port>()

    fun register(name: String, port: Port) {
        require(ports.put(name, port) == null) { "Duplicate port name: $name" }
        owner?.let { owners[port] = it to name }
    }

    operator fun get(name: String): Port? = ports[name]

    fun names(): Set<String> = ports.keys

    companion object {
        // ponytail: JVM-global weak map; KSP-generated registries are the KMP path (C-5, M5)
        private val registries = Collections.synchronizedMap(WeakHashMap<Any, PortRegistry>())

        // port → (owner, property name), owner held weakly: the value must not
        // strongly reach the registries key (owner) or neither entry ever clears.
        private val owners =
            Collections.synchronizedMap(WeakHashMap<Port, Pair<WeakReference<Any>, String>>())

        fun of(owner: Any): PortRegistry =
            registries.getOrPut(owner) { PortRegistry(WeakReference(owner)) }

        /** Typed-wiring seam: recovers the strings `connect` needs from a port object. */
        fun addressOf(port: Port): PortAddress? {
            val (ownerRef, name) = owners[port] ?: return null
            val cell = ownerRef.get() as? Cell ?: return null
            return PortAddress(cell.ref, name)
        }
    }
}

/**
 * Registers an explicitly-constructed port under [name] on the receiver (the owning cell).
 * The name must match the property it is assigned to, e.g.
 * `override val inlet = registerPort("inlet", FanInlet.create<SetOps<E>>())`.
 */
fun <P : Port> Any.registerPort(name: String, port: P): P =
    port.also { PortRegistry.of(this).register(name, it) }
