package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

class FanOutPort<Api : Any>(override val ref: PortRef = PortRef.generate()) : Use<Api>, ServeMany<Api> {
    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()

    override fun subscribe(port: Use<Api>) {
        subscriptions += port.ref to port
    }

    override fun unsubscribe(portRef: PortRef) {
        subscriptions.remove(portRef)
    }

    override fun use(portRef: PortRef, block: Api.() -> Any?) {
        subscriptions[portRef]?.use { block() }
    }

    override fun use(block: Api.() -> Any?) {
        subscriptions.forEach { (_, useApi) -> useApi.use { block() } }
    }

    override fun linkFrom(portOut: LinkTo<Api>) {
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        subscribe(useApi)
    }
}