package civictech.cell.proxy

import civictech.cell.Borrowed
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.nature.ContractRegistry
import civictech.nature.JvmDescriptors
import civictech.gen.wire.ProxyRegistry
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
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
        discharge(value, Collections.newSetFromMap(IdentityHashMap()))
    }

    /**
     * C-11 residual 1 (computenet-ulss, 93 I-6 / I-8): the walk reaches an exclusive nested
     * in a **plain payload object's field**, not only one held directly, in a collection, or
     * in a type argument. Before the widening, an `Owned` inside a data-class parameter was
     * left live by a proxy that believed it had discharged — no take, no release, no dead
     * letter, no accounting — which is exactly the silent drop AGENTS.md's exclusive-payload
     * invariant forbids. Its KSP half is `ContractProcessor.carriesExclusive`, which had to
     * widen with it: without that, the method carrying the nested exclusive is never marked
     * exclusive and this walk is never entered.
     *
     * Rules of the object walk, each load-bearing:
     *
     * - **[seen] is an identity set**, so an aliased payload reachable twice in one argument
     *   graph is discharged once (a second `take()` would throw), and a cyclic graph
     *   terminates.
     * - **`Borrowed`/`Frozen` are not opened.** Both are explicitly non-consuming views
     *   (spec 23 §Taps); descending into one could consume an exclusive whose sole consumer
     *   is somebody else.
     * - **Platform declarations are not opened**, because opening JDK internals would trip
     *   module access. This is a real limit on reach, not a free one: a `kotlin.*`/`java.*`
     *   container that is neither `Map`, `Iterable` nor `Array` — `Pair`, `Triple`,
     *   `Result`, `java.util.Optional` — is skipped here, so an exclusive held only inside
     *   one is **not** discharged even though the KSP scan marks the method exclusive
     *   (measured 2026-08-16 under review of computenet-ulss). Widening to those shapes is
     *   filed, not done.
     * - **Reflection failures are swallowed per field** rather than aborting the walk: this
     *   runs on suppression and denial paths, where discharging the fields that *are*
     *   reachable is strictly better than propagating out of a cleanup. Note the swallow
     *   covers field *access* only — an `Owned` already consumed elsewhere still throws out
     *   of `take()` here, and the walk stops with the remaining fields undischarged. Callers
     *   that must not throw wrap this themselves (`dischargeRefusedArgs`).
     */
    private fun discharge(value: Any?, seen: MutableSet<Any>) {
        if (value == null || !seen.add(value)) return
        when (value) {
            is Owned<*> -> value.take()
            is Leased<*> -> value.release()
            is Map<*, *> -> value.forEach { (key, item) ->
                discharge(key, seen)
                discharge(item, seen)
            }
            is Iterable<*> -> value.forEach { discharge(it, seen) }
            is Array<*> -> value.forEach { discharge(it, seen) }
            is Borrowed<*>, is Frozen<*> -> Unit
            else -> dischargeFields(value, seen)
        }
    }

    /** The field walk behind [discharge]'s `else` branch. */
    private fun dischargeFields(value: Any, seen: MutableSet<Any>) {
        var clazz: Class<*>? = value.javaClass
        if (clazz!!.isEnum || clazz.isPrimitive || isPlatformClass(clazz)) return
        while (clazz != null && !isPlatformClass(clazz)) {
            clazz.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                // Only primitives are excluded by *declared* type: a field declared
                // `List<Owned<T>>` erases to a platform type and still holds exclusives, so
                // the decision to open a value belongs to its runtime class, above.
                if (field.type.isPrimitive) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.onSuccess { discharge(it, seen) }
            }
            clazz = clazz.superclass
        }
    }

    /** Declarations [dischargeFields] must not open — see [discharge]'s KDoc. */
    private fun isPlatformClass(clazz: Class<*>): Boolean {
        val name = clazz.name
        return name.startsWith("kotlin.") || name.startsWith("java.") ||
            name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
    }
}
