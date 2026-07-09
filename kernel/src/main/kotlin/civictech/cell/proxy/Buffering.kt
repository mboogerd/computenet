package civictech.cell.proxy

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class Buffering(val invocations: MutableList<Invocation>) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        invocations.add(Invocation.of(method, args))
        return null
    }
}

/**
 * Creates a dynamic proxy implementing [T] that performs no operation on any method invocation.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> buffering(invocations: MutableList<Invocation>): T =
    Proxy.createProxy(Buffering(invocations))
