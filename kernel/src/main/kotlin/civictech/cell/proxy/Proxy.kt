package civictech.cell.proxy

import civictech.cell.Leased
import civictech.cell.Owned
import civictech.gen.wire.ContractRegistry
import civictech.gen.wire.JvmDescriptors
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

    @Suppress("UNCHECKED_CAST")
    fun <T> fromClass(clazz: Class<out Any>, invocationHandler: InvocationHandler): T {
        return if (clazz.isInterface) {
            Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), invocationHandler) as T
        } else {
            throw IllegalArgumentException("Only interfaces can be represented. $clazz is not an interface")
        }
    }

    /**
     * Creates a proxy that delegates all calls to the implementation returned by [provider].
     */
    fun <T : Any> delegating(clazz: Class<T>, provider: () -> T): T {
        return fromClass(clazz) { _, method, args ->
            try {
                method.invoke(provider(), *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /**
     * Creates a proxy that broadcasts all calls to the implementations returned by [provider].
     */
    fun <T : Any> broadcasting(clazz: Class<T>, provider: () -> Iterable<T>): T {
        return fromClass(clazz) { _, method, args ->
            provider().forEach {
                try {
                    method.invoke(it, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
            null
        }
    }

    /**
     * Creates a proxy that does nothing.
     */
    fun <T : Any> noop(clazz: Class<T>): T {
        return fromClass(clazz) { _, _, _ -> null }
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

    private fun discharge(value: Any?) {
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
