package civictech.cell.proxy

import civictech.cell.CellRef
import civictech.cell.host.ManagedHost
import civictech.cell.port.Port
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * A proxy reflecting the public API of a [Cell], its [Port]s or their respective API when hosted in a [ManagedHost].
 */
object HostedCellProxy {
    private data class Context(
        val cellRef: CellRef,
        val portName: String? = null,
        val apiClass: Class<*>? = null,
        val isApi: Boolean = false
    )

    fun create(cellRef: CellRef, host: ManagedHost, clazz: Class<*>): Any {
        return Proxy.fromClass(clazz, HostProxy(host, Context(cellRef)) { ctx, method, args ->
            when {
                ctx.portName == null -> cellInvocation(ctx, method)
                !ctx.isApi -> portInvocation(ctx, ctx.portName, method, args)
                else -> apiInvocation(ctx, ctx.portName, method, args)
            }
        })
    }

    private fun cellInvocation(
        ctx: Context,
        method: Method
    ): HostProxy.TransitionResult<Context> {
        val methodName = method.name

        return if (methodName == "getRef" || methodName == "ref") {
            HostProxy.TransitionResult.ImmediateReturn(ctx.cellRef)
        } else if (Port::class.java.isAssignableFrom(method.returnType)) {
            val portName = methodName.removePrefix("get").replaceFirstChar { it.lowercase() }
            // Resolve the API type of the port (e.g. Api in Inlet<Api>)
            val apiType = when (val portType = method.genericReturnType) {
                is ParameterizedType -> portType.actualTypeArguments[0].let {
                    it as? Class<*> ?: if (it is ParameterizedType) {
                        it.rawType as Class<*>
                    } else {
                        Any::class.java // Fallback
                    }
                }

                else -> Any::class.java // Fallback
            }

            HostProxy.TransitionResult.NewProxy(
                method.returnType,
                ctx.copy(portName = portName, apiClass = apiType)
            )
        } else {
            HostProxy.TransitionResult.ImmediateReturn(null)
        }
    }

    private fun portInvocation(
        ctx: Context,
        portName: String,
        method: Method,
        args: Array<out Any?>?
    ): HostProxy.TransitionResult<Context> {
        val methodName = method.name
        return if ((methodName == "getCall" || methodName == "call" || methodName == "at") && ctx.apiClass != null) {
            HostProxy.TransitionResult.NewProxy(
                ctx.apiClass,
                ctx.copy(isApi = true)
            )
        } else if (methodName == "getRef" || methodName == "ref") {
            HostProxy.TransitionResult.ImmediateReturn(null)
        } else {
            HostProxy.TransitionResult.EnqueueInvocation(
                HostedPortInvocation(
                    cellRef = ctx.cellRef,
                    portName = portName,
                    type = HostedPortInvocation.Type.PORT_MANAGEMENT,
                    invocation = Invocation.of(method, args)
                )
            )
        }
    }

    private fun apiInvocation(
        ctx: Context,
        portName: String,
        method: Method,
        args: Array<out Any?>?
    ): HostProxy.TransitionResult.EnqueueInvocation = HostProxy.TransitionResult.EnqueueInvocation(
        HostedPortInvocation(
            cellRef = ctx.cellRef,
            portName = portName,
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(method, args)
        )
    )
}