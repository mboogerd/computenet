package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.noop
import civictech.kernel.port.PortRef

class OneToOnePort<Api : Any>(
    unicastFactory: () -> Api
) : Use<Api>, Broadcast<Api>, Invalidating {

    private var subscribedPort: PortRef? = null
    private var subscribedPortApi: Use<Api> = Use.fixed(unicastFactory())
    private var implementationTrackers: MutableSet<Invalidating> = mutableSetOf()
    private var stale: Boolean = false

    override fun subscribe(portRef: PortRef, portApi: Use<Api>) {
        if (subscribedPort != null) {
            throw IllegalStateException("OneToOnePort already has a subscriber")
        }
        subscribedPort = portRef
        subscribedPortApi = portApi
        portApi.attach(this)
        invalidate()
    }

    override fun unsubscribe(portRef: PortRef) {
        if (subscribedPort == portRef) {
            subscribedPortApi.detach(this)
            subscribedPort = null
            invalidate()
        }
    }

    override fun use(portRef: PortRef, block: Api.() -> Any?) {
        if (stale && portRef == subscribedPort) {
            stale = false
        }
        subscribedPortApi
            .takeIf { subscribedPort == portRef }
            ?.use { block() }
    }

    override fun use(block: Api.() -> Any?) {
        if (stale) {
            stale = false
        }
        subscribedPortApi.use { block() }
    }

    override fun attach(invalidating: Invalidating) {
        implementationTrackers.add(invalidating)
    }

    override fun detach(invalidating: Invalidating) {
        implementationTrackers.remove(invalidating)
    }

    override fun invalidate() {
        if (stale) return
        stale = true
        implementationTrackers.forEach { it.invalidate() }
    }

    companion object {
        inline fun <reified Api : Any> withNoOp(): OneToOnePort<Api> = OneToOnePort { noop() }
    }
}
