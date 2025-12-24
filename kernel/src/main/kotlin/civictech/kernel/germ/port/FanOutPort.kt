package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.broadcast
import civictech.kernel.port.PortRef

class FanOutPort<Api : Any>(
    override val ref: PortRef,
    val broadcastFactory: (List<Use<Api>>) -> Api
) : Use<Api>, ServeMany<Api> {
    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()
    private var broadcastApi: Use<Api> = updateBroadcastImplementation()

    override fun subscribe(port: Use<Api>) {
        subscriptions += port.ref to port
        broadcastApi = updateBroadcastImplementation()
    }

    override fun unsubscribe(portRef: PortRef) {
        subscriptions.remove(portRef)
        broadcastApi = updateBroadcastImplementation()
    }

    override fun use(portRef: PortRef, block: Api.() -> Any?) {
        subscriptions[portRef]?.use { block() }
    }

    override fun use(block: Api.() -> Any?) {
        broadcastApi.use { block() }
    }

    private fun updateBroadcastImplementation(): Use<Api> =
        Use.fixed(broadcastFactory(subscriptions.values.toList()))

    override fun linkFrom(portOut: LinkTo<Api>) {
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        subscribe(useApi)
    }

    companion object Companion {
        inline fun <reified Api : Any> withProxy(portRef: PortRef = PortRef.generate()): FanOutPort<Api> =
            FanOutPort(portRef, ::broadcast)
    }
}