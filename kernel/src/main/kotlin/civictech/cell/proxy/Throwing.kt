package civictech.cell.proxy

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

object Throwing : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        throw IllegalStateException("Attempted to invoke $method(${args.contentToString()}) on an unmounted input. Please mount a link to the input")
    }
}

/**
 * Creates a dynamic proxy implementing [T] that throws on any method invocation.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> throwing(): T = Proxy.createProxy(Throwing)