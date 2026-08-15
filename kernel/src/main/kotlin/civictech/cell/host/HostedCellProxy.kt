package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.proxy.Proxy
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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

    /** Fixed-host proxy: a closed intake surfaces [civictech.cell.host.IntakeClosedException] at the send site. */
    fun create(cellRef: CellRef, host: ManagedHost, clazz: Class<*>): Any =
        create(cellRef, InvocationSink(host::enqueueHostedInvocation), clazz)

    /** Registry-resolving proxy: closure parks and replays on re-publication (spec 33). */
    fun create(cellRef: CellRef, registry: LocationRegistry, clazz: Class<*>): Any =
        create(cellRef, InvocationSink(registry::deliver), clazz)

    fun create(cellRef: CellRef, sink: InvocationSink, clazz: Class<*>): Any {
        return Proxy.fromClass(clazz, HostProxy(sink, Context(cellRef)) { ctx, method, args ->
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
            // data path: carry the wave context across the host boundary (G-4)
            invocation = Invocation.of(method, args, CurrentContext.get())
        )
    )
}

/**
 * The **stamped ingress** an external driver plugs into the graph through
 * (`[24-DUR-06]`, spec 24 §Effectful; KFX-16).
 *
 * `HostedCellProxy` stamps whatever [CurrentContext] is ambient, which is
 * `null` off the data path — a spontaneous call from outside the graph. Such a
 * frame carries no `(sourceId, counter)`, so it has no position on an
 * `Effectful` inlet's processed-frontier, and `ManagedHost` refuses it there
 * rather than acting on something recovery could never dedup. The decided rule
 * is that the *act of plugging in to drive the graph directly is itself a
 * frontier*: an external actor — a user, a machine, a connector principal —
 * carries a stable source id and a monotonic counter over its own actions, and
 * every frame it drives is stamped with the next position on that lane.
 *
 * This class is the kernel-side seam for that, and deliberately no more:
 *
 * - It takes [actorId] rather than minting one. **Minting and persisting the
 *   actor identity is the connector ingress's job (CON1)** — the identity has
 *   to mean the same thing across a restart and across peers, which nothing in
 *   the kernel can decide. An id that is stable per actor keeps the frontier
 *   bounded by the number of actors; a caller that passes a fresh id per
 *   process re-derives correctness across replay (the stamp rides the journaled
 *   frame and the frontier advance is journaled beside it) but grows the
 *   frontier by one lane per session. Both are the caller's call, and stated
 *   here so the weaker one is not chosen by accident.
 * - It does **not** persist [counter]. A counter that restarts at 0 under the
 *   same [actorId] re-uses positions the restored frontier already holds, and
 *   those re-drives are then suppressed as already-acted — safe, but it means
 *   an actor that wants its *post-restart* actions to fire must either persist
 *   its counter (CON1) or continue from [position].
 */
class ActorIngress(
    /** The external actor's frontier lane — stable per actor, minted and persisted by CON1. */
    val actorId: UUID,
    /** The ingress port this actor's frames name as their `sourcePort`; identity only, never dedup. */
    private val ingressPort: PortRef = PortRef.generate(),
    /** Resume an actor's lane where it left off (its last stamped counter). */
    startingPosition: Long = 0L,
) {
    private val counter = AtomicLong(startingPosition)

    /** The last position this ingress stamped — what a persisting connector checkpoints. */
    val position: Long get() = counter.get()

    /** The next position on this actor's lane, as a full wave context. */
    fun next(): MessageContext = MessageContext(Timestamp(actorId, counter.incrementAndGet()), ingressPort)

    /**
     * Runs [block] with the next position on this actor's lane installed as the
     * ambient [CurrentContext], so every hosted invocation it sends — including
     * one to an `Effectful` inlet — carries a frontier position.
     */
    fun <R> drive(block: () -> R): R = CurrentContext.with(next(), block)
}
