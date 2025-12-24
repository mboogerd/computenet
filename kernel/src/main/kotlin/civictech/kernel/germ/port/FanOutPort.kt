package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.broadcast
import civictech.kernel.port.PortRef

class FanOutPort<Api : Any>(val broadcastFactory: (List<Use<Api>>) -> Api) : Use<Api>, Broadcast<Api> {
    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()
    private var broadcastApi: Use<Api> = updateBroadcastImplementation()

    override fun subscribe(portRef: PortRef, portApi: Use<Api>) {
        subscriptions += portRef to portApi
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

    companion object Companion {
        inline fun <reified Api : Any> withProxy(): FanOutPort<Api> = FanOutPort(::broadcast)
    }
}