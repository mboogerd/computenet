package civictech.gen.async

import kotlin.reflect.KClass
import kotlin.reflect.KType

fun KType.allTypes(): List<KType> = let {
    listOf(it, *(it.classifier as KClass<*>).supertypes.toTypedArray())
}