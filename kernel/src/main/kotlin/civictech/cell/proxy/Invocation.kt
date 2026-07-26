package civictech.cell.proxy

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.nature.ContractRegistry
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

data class Invocation(
    val methodName: String,
    val parameterTypes: List<String>,
    val args: List<Any?>,
    /** Data-path wave context (G-4); null on management paths and spontaneous calls. */
    val context: MessageContext? = null,
    /**
     * Stable wire identity (G-15, C-5) from the generated contract tables;
     * null when the captured interface has no `@Contract` annotation. The
     * serialized form (M5.2) uses only these ids — name/parameterTypes stay
     * the in-process reflective dispatch path.
     */
    val contractId: Long? = null,
    val methodId: Long? = null,
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
            Proxy.unwrapInvocationTarget {
                method.invoke(target, *(args.toTypedArray()))
            }
        }
    }

    /**
     * Suspend-aware delivery (spec 32): if the target method is a suspend fun
     * (trailing [Continuation] parameter), call it with a real continuation so
     * it may park the host's task; otherwise fall back to [invoke]. The context
     * rides a coroutine element, surviving suspension (G-4).
     */
    suspend fun invokeSuspending(target: Any?): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.find {
            it.name == methodName &&
                it.parameterTypes.size == parameterTypes.size + 1 &&
                it.parameterTypes.last() == Continuation::class.java &&
                it.parameterTypes.dropLast(1).map { p -> p.name } == parameterTypes
        } ?: return invoke(target)

        return CurrentContext.withSuspending(context) {
            suspendCoroutineUninterceptedOrReturn { cont ->
                Proxy.unwrapInvocationTarget {
                    method.invoke(target, *(args.toTypedArray()), cont)
                }
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
            // A captured suspend fun arrives with a trailing Continuation; the
            // invocation is fire-and-forget across the boundary (spec 32), so the
            // continuation is stripped here and re-supplied by invokeSuspending.
            val types = method?.parameterTypes?.map { it.name } ?: emptyList()
            val suspendCapture = method?.parameterTypes?.lastOrNull() == Continuation::class.java
            val values = args?.toList() ?: emptyList()
            val ids = method?.let { ContractRegistry.idsOf(it) }
            return Invocation(
                methodName = method?.name ?: "",
                parameterTypes = if (suspendCapture) types.dropLast(1) else types,
                args = if (values.lastOrNull() is Continuation<*>) values.dropLast(1) else values,
                context = context,
                contractId = ids?.first,
                methodId = ids?.second,
            )
        }
    }
}
