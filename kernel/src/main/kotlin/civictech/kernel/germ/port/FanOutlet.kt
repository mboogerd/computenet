package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.Proxy
import civictech.kernel.port.PortRef

/**
 * A broadcasting output port.
 * When [use] is called, it broadcasts the invocation to all subscribed ports.
 */
class FanOutlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef = PortRef.generate()
) : Use<Api>, Subscribe<Api> {
    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()

    override val call: Api = Proxy.broadcasting(clazz) {
        subscriptions.values.map { it.call }
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

    override fun linkFrom(portOut: LinkTo<Api>) {
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        subscribe(useApi)
    }

    companion object {
        inline fun <reified Api : Any> create(
            ref: PortRef = PortRef.generate()
        ): FanOutlet<Api> = FanOutlet(Api::class.java, ref)
    }
}