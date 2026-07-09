package civictech.cell.proxy

import civictech.cell.port.Use
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class Broadcast<Api>(val apis: List<Use<Api>>) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        apis.forEach { useApi ->
            try {
                method?.invoke(useApi.call, *(args ?: arrayOf()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
        return null
    }
}

/**
 * Creates a dynamic proxy implementing [Api] that performs operations on all underlying api implementations
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified Api : Any> broadcast(invocations: List<Use<Api>>): Api =
    Proxy.createProxy(Broadcast(invocations))
