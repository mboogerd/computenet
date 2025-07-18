package civictech.gen.async

import kotlin.reflect.KClass

typealias SuspendedProxyFactory<S> = (SendOperation) -> S

class SuspendedProxyFactoryRegistry {
    private val factories: MutableMap<KClass<*>, (SendOperation) -> Any> = mutableMapOf()

    fun <S : Any> register(
        proxyClass: KClass<S>,
        factory: SuspendedProxyFactory<S>
    ) {
        @Suppress("UNCHECKED_CAST")
        factories[proxyClass] = factory as (SendOperation) -> Any
    }

    fun <S : Any> create(proxyClass: KClass<S>, op: SendOperation): S? {
        @Suppress("UNCHECKED_CAST")
        return factories[proxyClass]?.invoke(op) as? S
    }
}
