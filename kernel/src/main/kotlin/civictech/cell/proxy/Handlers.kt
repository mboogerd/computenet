package civictech.cell.proxy

import civictech.cell.CurrentContext
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

/**
 * Records every invocation via [record] (T03: a callback, not a bare
 * `MutableList<Invocation>` — [civictech.cell.control.ParkQueue]'s callers
 * pass `queue::park` so a parked [Invocation] (which may carry an
 * `Owned`/`Leased` payload) is never reachable through an exposed
 * `clear()`/`removeAt()` on the recording collection itself).
 */
class Buffering(private val record: (Invocation) -> Unit) : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
        // capture the wave context so replay restores it (Invocation.invoke)
        record(Invocation.of(method, args, CurrentContext.get()))
        return null
    }
}

/**
 * Creates a dynamic proxy implementing [T] that performs no operation on any method invocation.
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> buffering(invocations: MutableList<Invocation>): T =
    Proxy.createProxy(Buffering(invocations::add))
