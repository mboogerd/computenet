package civictech.gen.async

import kotlin.reflect.KClass
import kotlin.reflect.KType

abstract class AsyncRecipe<I: Any, SI: DerivedSuspends<I>>(val `interface`: KType) {

    val allTypes: List<KType> by lazy {
        listOf(`interface`, *(`interface`.classifier as KClass<*>).supertypes.toTypedArray())
    }

    fun canConstructFor(i: I): Boolean = (`interface`.classifier as KClass<*>).isInstance(i)

    abstract fun construct(sendOperation: SendOperation): SI

    companion object {
        fun Iterable<AsyncRecipe<*, *>>.buildMapping(): Map<KType, AsyncRecipe<*, *>> {
            val result = mutableMapOf<KType, AsyncRecipe<*, *>>()
            val conflicts = mutableSetOf<KType>()

            for (value in this) {
                for (key in value.allTypes) {
                    when {
                        conflicts.contains(key) -> continue
                        result.containsKey(key) -> {
                            if (result[key] != value) {
                                result.remove(key)
                                conflicts.add(key)
                            }
                        }
                        else -> result[key] = value
                    }
                }
            }

            return result
        }
    }
}