package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.broadcast
import civictech.kernel.port.PortRef

class FanOutPort<Api : Any>(val broadcastFactory: (List<Use<Api>>) -> Api) : Use<Api>, Broadcast<Api>,
    Invalidating {
    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()
    private val implementationTrackers: MutableSet<Invalidating> = mutableSetOf()
    private var broadcastApi: Api = updateBroadcastImplementation()
    private var stale: Boolean = false

    override fun subscribe(portRef: PortRef, portApi: Use<Api>) {
        subscriptions += portRef to portApi
        portApi.attach(this)
        invalidate()
    }

    override fun unsubscribe(portRef: PortRef) {
        subscriptions.remove(portRef)?.detach(this)
        invalidate()
    }

    override fun use(portRef: PortRef, block: Api.() -> Any?) {
        subscriptions[portRef]?.use { block() }
    }

    override fun use(block: Api.() -> Any?) {
        if (stale) {
            broadcastApi = updateBroadcastImplementation()
            stale = false
        }
        broadcastApi.block()
    }

    override fun attach(invalidating: Invalidating) {
        implementationTrackers.add(invalidating)
        // caller may already have a copy before attaching
        if (stale) invalidating.invalidate()
    }

    override fun detach(invalidating: Invalidating) {
        implementationTrackers.remove(invalidating)
    }

    override fun invalidate() {
        if (stale) return
        stale = true
        implementationTrackers.forEach { it.invalidate() }
    }

    private fun updateBroadcastImplementation(): Api =
        broadcastFactory(subscriptions.values.toList()).also { invalidate() }

    companion object Companion {
        inline fun <reified Api : Any> withProxy(): FanOutPort<Api> = FanOutPort(::broadcast)
    }
}