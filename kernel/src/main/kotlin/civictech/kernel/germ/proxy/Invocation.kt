package civictech.kernel.germ.proxy

import java.lang.reflect.Method

data class Invocation(
    val methodName: String,
    val parameterTypes: List<String>,
    val args: List<Any>
) : java.io.Serializable {
    operator fun invoke(target: Any?): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.find {
            it.name == methodName && it.parameterTypes.map { p -> p.name } == parameterTypes
        } ?: throw NoSuchMethodException("Method $methodName with types $parameterTypes not found on ${target.javaClass}")
        
        return method.invoke(target, *(args.toTypedArray()))
    }

    companion object {
        fun of(method: Method?, args: Array<out Any>?): Invocation {
            return Invocation(
                methodName = method?.name ?: "",
                parameterTypes = method?.parameterTypes?.map { it.name } ?: emptyList(),
                args = args?.toList() ?: emptyList()
            )
        }
    }
}