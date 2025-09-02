package civictech.kernel.germ.proxy

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class Callback(val callback: (Invocation) -> Any?) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        return callback(Invocation.of(method, args))
    }
}

/**
 * Creates a dynamic proxy implementing [T] that performs no operation on any method invocation.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> callback(noinline cb: (Invocation) -> Any?): T =
    Proxy.createProxy(Callback(cb))