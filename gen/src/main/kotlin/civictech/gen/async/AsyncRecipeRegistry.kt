package civictech.gen.async

import civictech.gen.async.AsyncRecipe.Companion.buildMapping
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class AsyncRecipeRegistry(val mapping: Map<KType, AsyncRecipe<*, *>>) {

    fun hasMapping(clazz: KType): Boolean = mapping.containsKey(clazz)

    inline fun <reified I : Any> recipe(): AsyncRecipe<*, *>? =
        typeOf<I>().allTypes().asSequence().map { mapping[it] }.first()

    inline fun <reified I : Any, SI : DerivedSuspends<I>> spawn(send: SendOperation): SI? {
        @Suppress("UNCHECKED_CAST")
        return recipe<I>()?.construct(send) as SI
    }

    companion object {
        operator fun invoke(recipes: Iterable<AsyncRecipe<*, *>>): AsyncRecipeRegistry =
            AsyncRecipeRegistry(recipes.buildMapping())
    }
}