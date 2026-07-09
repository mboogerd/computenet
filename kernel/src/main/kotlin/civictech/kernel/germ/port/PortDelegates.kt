package civictech.kernel.germ.port

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A property-delegate provider for declaring ports in a [civictech.kernel.germ.Cell].
 *
 * `provideDelegate` runs at construction, handing over the owner instance and the
 * property name — the port is created eagerly and registered in the owner's
 * [PortRegistry] under the property name (G-17). Cold cells can therefore have
 * their ports enumerated without touching logic.
 */
class PortDelegateProvider<P : Port>(private val factory: () -> P) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, P> {
        val port = factory()
        thisRef?.let { PortRegistry.of(it).register(property.name, port) }
        return ReadOnlyProperty { _, _ -> port }
    }
}

/**
 * Declares a [FanInlet] for the cell.
 */
inline fun <reified T : Any> input() = input(T::class.java)

/**
 * Declares a [FanInlet] for the cell with a specific class.
 */
fun <T : Any> input(clazz: Class<T>) = PortDelegateProvider { FanInlet(clazz) }

/**
 * Declares a [FanOutlet] for the cell.
 */
inline fun <reified T : Any> output() = output(T::class.java)

/**
 * Declares a [FanOutlet] for the cell with a specific class.
 */
fun <T : Any> output(clazz: Class<T>) = PortDelegateProvider { FanOutlet(clazz) }
