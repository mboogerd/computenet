package civictech.cell.proxy

import civictech.cell.Leased
import civictech.cell.Owned
import civictech.nature.ContractRegistry
import civictech.nature.JvmDescriptors
import civictech.gen.wire.ProxyRegistry
import java.lang.reflect.InvocationHandler
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

object Proxy {
    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T : Any> createProxy(proxyType: InvocationHandler): T {
        val clazz = when (val javaType = typeOf<T>().javaType) {
            is Class<*> -> javaType
            is ParameterizedType -> javaType.rawType as Class<*>
            else -> error("Unsupported type: $javaType")
        }

        return fromClass(clazz, proxyType)
    }

    /**
     * Constructs an instance of [clazz] dispatching every method through
     * [invocationHandler] — the shape `Buffering`, `NoOp`,
     * `Callback`, `HostProxy`, `MediateProxy`, and every port
     * (`Outlet`, `Inlet`, `FanOutlet`, `FanInlet`, ...) already build on.
     *
     * C-5 completion (W4.6, spec 10/14 §Reflection budget): every `@Contract`
     * interface has a KSP-generated proxy class (`gen.wire.ContractProcessor`)
     * registered in [ProxyRegistry] — the ahead-of-time-compiled replacement
     * for `java.lang.reflect.Proxy.newProxyInstance`, used first. The runtime
     * dynamic-proxy fallback below is retained only for interfaces outside the
     * `@Contract` surface — the cross-host structural navigation proxies
     * `HostedCellProxy`/`HostProxy` walk over ad hoc `Cell`/`Port` resource
     * types (tier 2/3 dispatch, spec 10/14 §Dispatch tiers), which are not
     * fixed method-dispatch contracts KSP can generate ahead of time.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> fromClass(clazz: Class<out Any>, invocationHandler: InvocationHandler): T {
        if (!clazz.isInterface) {
            throw IllegalArgumentException("Only interfaces can be represented. $clazz is not an interface")
        }
        val generated = ProxyRegistry.factory(clazz)
        return if (generated != null) {
            generated(invocationHandler) as T
        } else {
            Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), invocationHandler) as T
        }
    }

    /**
     * Unwraps a reflective [java.lang.reflect.InvocationTargetException],
     * rethrowing the invocation's real cause — the shape every `Method.invoke`
     * dispatch site in this package repeats (delegating here,
     * [Invocation], and the port outlets).
     */
    internal inline fun <T> unwrapInvocationTarget(block: () -> T): T =
        try {
            block()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }

    /**
     * Creates a proxy that delegates all calls to the implementation returned by [provider].
     */
    fun <T : Any> delegating(clazz: Class<T>, provider: () -> T): T {
        return fromClass(clazz) { _, method, args ->
            unwrapInvocationTarget {
                method.invoke(provider(), *(args ?: emptyArray()))
            }
        }
    }

    /**
     * Creates a proxy that broadcasts all calls to the implementations returned by [provider].
     */
    fun <T : Any> broadcasting(clazz: Class<T>, provider: () -> Iterable<T>): T {
        return fromClass(clazz) { _, method, args ->
            provider().forEach {
                unwrapInvocationTarget {
                    method.invoke(it, *(args ?: emptyArray()))
                }
            }
            null
        }
    }

    /**
     * Creates a proxy that does nothing — delegates to the [NoOp] handler.
     */
    fun <T : Any> noop(clazz: Class<T>): T {
        return fromClass(clazz, NoOp)
    }

    /** Create a sink which discharges methods marked exclusive by generated metadata. */
    fun <T : Any> discharging(clazz: Class<T>): T {
        val descriptor = requireNotNull(ContractRegistry.descriptor(clazz)) {
            "A discharging proxy requires a generated contract descriptor for ${clazz.name}"
        }
        val exclusiveMethods = descriptor.methods.filter { it.exclusive }.mapTo(mutableSetOf()) {
            it.name to it.jvmDescriptor
        }
        return fromClass(clazz) { _, method, args ->
            if ((method.name to JvmDescriptors.of(method)) in exclusiveMethods) {
                args.orEmpty().forEach(::discharge)
            }
            null
        }
    }

    /**
     * T05 finding 3: promoted from `private` to `internal` so [civictech.cell.port.Admit]
     * can discharge a *dropped* invocation's exclusive args directly
     * (consume `Owned`, release `Leased`) without needing a whole
     * [discharging] proxy — the ADMIT tier drops one already-decoded
     * `Invocation`, not a method call it forwards to a sink.
     */
    internal fun discharge(value: Any?) {
        when (value) {
            is Owned<*> -> value.take()
            is Leased<*> -> value.release()
            is Map<*, *> -> value.forEach { (key, item) ->
                discharge(key)
                discharge(item)
            }
            is Iterable<*> -> value.forEach(::discharge)
            is Array<*> -> value.forEach(::discharge)
        }
    }
}
