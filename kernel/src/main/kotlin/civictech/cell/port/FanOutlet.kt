package civictech.cell.port

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.proxy.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * A broadcasting output port.
 * When [use] is called, it broadcasts the invocation to all subscribed ports.
 *
 * Emission is the context-stamping point (spec 20/22): a reactive call keeps the
 * incoming timestamp with this port as [MessageContext.sourcePort]; a spontaneous
 * call mints a fresh wave from this port's counter. All subscribers of one call
 * receive the same context.
 */
class FanOutlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef = PortRef.generate()
) : Use<Api>, Subscribe<Api>, Linked {

    override val linking = LinkSupport()

    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()
    private val waveCounter = AtomicLong()

    override val call: Api = Proxy.fromClass(clazz) { _, method, args ->
        val ctx = CurrentContext.get()?.copy(sourcePort = ref)
            ?: MessageContext(Timestamp(ref.id, waveCounter.incrementAndGet()), ref)
        CurrentContext.with(ctx) {
            // snapshot: link/unlink during a wave must not fail the broadcast
            subscriptions.values.toList().forEach { target ->
                try {
                    method.invoke(target.call, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
        null
    }

    override fun at(portRef: PortRef): Api {
        return Proxy.delegating(clazz) {
            subscriptions[portRef]?.call ?: Proxy.noop(clazz)
        }
    }

    override fun subscribe(port: Use<Api>) {
        subscriptions += port.ref to port
    }

    override fun unsubscribe(portRef: PortRef) {
        subscriptions.remove(portRef)
    }

    override fun linkFrom(portOut: LinkTo<Api>): LinkResult = handshake(
        portOut = portOut,
        target = this,
        targetRef = ref,
        install = { portOut.linkTo(this as Use<Api>) },
        uninstall = { (portOut as? Subscribe<Api>)?.unsubscribe(ref) },
    )

    override fun linkTo(useApi: Use<Api>) {
        subscribe(useApi)
    }

    companion object {
        inline fun <reified Api : Any> create(
            ref: PortRef = PortRef.generate()
        ): FanOutlet<Api> = FanOutlet(Api::class.java, ref)
    }
}