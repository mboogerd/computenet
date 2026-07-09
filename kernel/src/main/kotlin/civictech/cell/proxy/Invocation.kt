package civictech.cell.proxy

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import java.lang.reflect.Method

data class Invocation(
    val methodName: String,
    val parameterTypes: List<String>,
    val args: List<Any?>,
    /** Data-path wave context (G-4); null on management paths and spontaneous calls. */
    val context: MessageContext? = null,
) : java.io.Serializable {
    operator fun invoke(target: Any?): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.find {
            it.name == methodName && it.parameterTypes.map { p -> p.name } == parameterTypes
        } ?: throw NoSuchMethodException("Method $methodName with types $parameterTypes not found on ${target.javaClass}")

        // The invocation executes under its own context — the single restore
        // point for delivery and buffered replay alike. A null context clears
        // any stale wave (management calls, spontaneous emissions).
        return CurrentContext.with(context) {
            try {
                method.invoke(target, *(args.toTypedArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    @Transient
    private var fixedTarget: Any? = null

    fun withTarget(target: Any): Invocation {
        this.fixedTarget = target
        return this
    }

    fun invoke(): Any? = invoke(fixedTarget)

    companion object {
        fun of(method: Method?, args: Array<out Any?>?, context: MessageContext? = null): Invocation {
            return Invocation(
                methodName = method?.name ?: "",
                parameterTypes = method?.parameterTypes?.map { it.name } ?: emptyList(),
                args = args?.toList() ?: emptyList(),
                context = context,
            )
        }
    }
}
