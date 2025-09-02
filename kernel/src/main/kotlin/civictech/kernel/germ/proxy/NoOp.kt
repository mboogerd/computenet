package civictech.kernel.germ.proxy

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * Creates a dynamic proxy implementing [T] that performs no operation on any method invocation.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> noop(): T = Proxy.createProxy(NoOp)

object NoOp : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        return null
    }
}