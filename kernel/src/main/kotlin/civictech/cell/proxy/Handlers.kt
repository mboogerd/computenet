package civictech.cell.proxy

import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.port.Use
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

class Buffering(val invocations: MutableList<Invocation>) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        // capture the wave context so replay restores it (Invocation.invoke)
        invocations.add(Invocation.of(method, args, CurrentContext.get()))
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

class Broadcast<Api>(val apis: List<Use<Api>>) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        // spec 23 corollary: exclusive payloads must not fan out
        require(apis.size <= 1 || args.orEmpty().none { it is Owned<*> || it is Leased<*> }) {
            "Owned/Leased payloads must not cross into a Broadcast proxy (spec 23) — freeze first"
        }
        apis.forEach { useApi ->
            Proxy.unwrapInvocationTarget {
                method?.invoke(useApi.call, *(args ?: arrayOf()))
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
