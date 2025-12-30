package civictech.kernel.germ.port

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A property delegate for declaring ports in a [civictech.kernel.germ.Cell].
 *
 * This allows the framework to discover ports via reflection and ensures
 * they are lazily initialized when first accessed.
 */
class PortDelegate<P : Port>(private val factory: () -> P) : ReadOnlyProperty<Any?, P> {
    private var instance: P? = null
    override fun getValue(thisRef: Any?, property: KProperty<*>): P {
        if (instance == null) {
            instance = factory()
        }
        return instance!!
    }
}

/**
 * Declares a [FanInlet] for the cell.
 */
inline fun <reified T : Any> input() = input(T::class.java)

/**
 * Declares a [FanInlet] for the cell with a specific class.
 */
fun <T : Any> input(clazz: Class<T>) = PortDelegate { FanInlet(clazz) }

/**
 * Declares a [FanOutlet] for the cell.
 */
inline fun <reified T : Any> output() = output(T::class.java)

/**
 * Declares a [FanOutlet] for the cell with a specific class.
 */
fun <T : Any> output(clazz: Class<T>) = PortDelegate { FanOutlet(clazz) }
