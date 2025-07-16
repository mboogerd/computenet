package civictech.gen.async

import kotlin.reflect.KClass

typealias SuspendedProxyFactory<I, S> = (SendOperation<I>) -> S

class SuspendedProxyFactoryRegistry {
    private val factories: MutableMap<KClass<*>, (SendOperation<*>) -> Any> = mutableMapOf()

    fun <I : Any, S : Any> register(
        proxyClass: KClass<S>,
        factory: SuspendedProxyFactory<I, S>
    ) {
        @Suppress("UNCHECKED_CAST")
        factories[proxyClass] = factory as (SendOperation<*>) -> Any
    }

    fun <I : Any, S : Any> create(proxyClass: KClass<S>, op: SendOperation<I>): S? {
        @Suppress("UNCHECKED_CAST")
        return factories[proxyClass]?.invoke(op) as? S
    }
}
