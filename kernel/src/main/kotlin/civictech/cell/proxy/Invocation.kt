package civictech.cell.proxy

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.PendingReBaseline
import civictech.cell.ReplayScope
import civictech.nature.ContractRegistry
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
        }?.let { accessible(target, it) }
            ?: throw NoSuchMethodException("Method $methodName with types $parameterTypes not found on ${target.javaClass}")

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
     *
     * T04 finding 7: [PendingReBaseline] and [ReplayScope] compose alongside
     * [CurrentContext] — all three are bare `ThreadLocal`s a `SuspendingCell`'s
     * handler may read; without a coroutine context element for the two
     * added here, a handler resuming after a genuine suspension on a
     * different worker thread would see whatever (or nothing) that worker's
     * thread-local happened to hold, instead of the value ambient at the
     * point this delivery began.
     */
    suspend fun invokeSuspending(target: Any?): Any? {
        if (target == null) return null
        val method = target.javaClass.methods.find {
            it.name == methodName &&
                it.parameterTypes.size == parameterTypes.size + 1 &&
                it.parameterTypes.last() == Continuation::class.java &&
                it.parameterTypes.dropLast(1).map { p -> p.name } == parameterTypes
        }?.let { accessible(target, it) } ?: return invoke(target)

        return CurrentContext.withSuspending(context) {
            PendingReBaseline.withSuspending(PendingReBaseline.get()) {
                ReplayScope.withSuspending(ReplayScope.get()) {
                    suspendCoroutineUninterceptedOrReturn { cont ->
                        Proxy.unwrapInvocationTarget {
                            method.invoke(target, *(args.toTypedArray()), cont)
                        }
                    }
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
        /**
         * computenet-mdvgt: [invoke]/[invokeSuspending] find [method] on the
         * target's CONCRETE class, and `Method.invoke` enforces ordinary JVM
         * access on that method's declaring class. A handler whose class is
         * not accessible from here — a Kotlin fun-interface SAM adapter
         * (`onEach`'s `Propagate(handler)` is `PropagateKt$sam$…`, package-
         * private), an `invokedynamic` lambda (a hidden class), a private or
         * internal nested class — made every policy-routed delivery throw
         * `IllegalAccessException`, although ordinary (non-reflective)
         * interface dispatch to the same object works. When [method] is not
         * accessible, the SAME signature is resolved on an accessible
         * supertype (the port Api interface, in practice) instead. Invoking
         * that declaration dispatches virtually, so the implementation that
         * runs is unchanged; only the access check moves to a type this class
         * may see. An accessible [method] is returned as-is (the common path,
         * unchanged), and when no accessible declaration exists the original
         * is returned so the failure stays the one it always was.
         */
        private fun accessible(target: Any, method: Method): Method {
            if (method.canAccess(target)) return method
            val seen = HashSet<Class<*>>()
            val queue = ArrayDeque<Class<*>>()
            target.javaClass.superclass?.let { queue.addLast(it) }
            queue.addAll(target.javaClass.interfaces)
            while (queue.isNotEmpty()) {
                val type = queue.removeFirst()
                if (!seen.add(type)) continue
                val candidate = try {
                    type.getDeclaredMethod(method.name, *method.parameterTypes)
                } catch (_: NoSuchMethodException) {
                    null
                }
                if (candidate != null &&
                    !Modifier.isStatic(candidate.modifiers) &&
                    candidate.canAccess(target)
                ) {
                    return candidate
                }
                type.superclass?.let { queue.addLast(it) }
                queue.addAll(type.interfaces)
            }
            return method
        }

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
