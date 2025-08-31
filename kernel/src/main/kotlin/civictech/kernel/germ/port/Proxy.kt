package civictech.kernel.germ.port

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

object Proxy {

    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T: Any> createProxy(proxyType: InvocationHandler): T {
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
     * Creates a dynamic proxy implementing [T] that throws on any method invocation.
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> throwing(): T = createProxy(Throwing)


    /**
     * Creates a dynamic proxy implementing [T] that performs no operation on any method invocation.
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> noop(): T = createProxy(NoOp)

    /**
     * Creates a dynamic proxy implementing [T] that performs no operation on any method invocation.
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> buffering(invocations: MutableList<Invocation>): T =
        createProxy(Buffering(invocations))


    object Throwing : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
            throw IllegalStateException("Attempted to invoke $method(${args.contentToString()}) on an unmounted input. Please mount a link to the input")
        }
    }


    object NoOp : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
            return null
        }
    }

    class Buffering(val invocations: MutableList<Invocation>) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
            invocations.add(Invocation.of(method, args))
            return null
        }
    }

    data class Invocation(val method: Method?, val args: List<Any>) {
        operator fun invoke(target: Any?) = method?.invoke(
            target,
            *(args.toTypedArray())
        )
        companion object {
            fun of(method: Method?, args: Array<out Any>?): Invocation {
                return Invocation(method, args?.toList() ?: listOf())
            }
        }
    }
}
