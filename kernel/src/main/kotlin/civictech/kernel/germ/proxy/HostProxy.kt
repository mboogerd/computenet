package civictech.kernel.germ.proxy

import civictech.kernel.germ.ManagedHost
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * A generic proxy that forwards invocations to a [ManagedHost] or expands its [Context]
 * to return a new [HostProxy] representing a sub-resource.
 */
class HostProxy<C : Any>(
    private val host: ManagedHost,
    private val context: C,
    private val transition: (C, Method, Array<out Any?>?) -> TransitionResult<C>
) : InvocationHandler {

    override fun invoke(
        proxy: Any?,
        method: Method?,
        args: Array<out Any?>?
    ): Any? {
        if (method == null) return null

        // Default: handle toString, equals, hashCode if not handled by transition
        when (method.name) {
            "toString" -> return "HostProxy($context)"
            "hashCode" -> return context.hashCode()
            "equals" -> return args?.get(0)?.let { it === proxy } ?: false
        }

        return when (val result = transition(context, method, args)) {
            is TransitionResult.NewProxy ->
                Proxy.fromClass(result.clazz, HostProxy(host, result.nextContext, transition))
            is TransitionResult.EnqueueInvocation -> {
                host.enqueueHostedInvocation(result.invocation)
                null
            }
            is TransitionResult.ImmediateReturn -> result.value
        }
    }

    sealed class TransitionResult<out C : Any> {
        /**
         * Returns a new proxy for the specified [clazz] with an updated [nextContext].
         */
        data class NewProxy<C : Any>(val clazz: Class<*>, val nextContext: C) : TransitionResult<C>()

        /**
         * Enqueues the provided [invocation] to the host.
         */
        data class EnqueueInvocation(val invocation: HostedPortInvocation) : TransitionResult<Nothing>()

        /**
         * Returns an [ImmediateReturn] value directly (e.g. for identity/metadata methods).
         */
        data class ImmediateReturn(val value: Any?) : TransitionResult<Nothing>()
    }
}
