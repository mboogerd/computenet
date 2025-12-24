package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.noop
import civictech.kernel.port.PortRef

class OneToOnePort<Api : Any>(
    private val unicastFactory: () -> Api
) : Use<Api>, Broadcast<Api> {

    private var subscribedPort: PortRef? = null
    private var subscribedPortApi: Use<Api> = Use.fixed(unicastFactory())

    override fun subscribe(portRef: PortRef, portApi: Use<Api>) {
        if (subscribedPort != null) {
            throw IllegalStateException("OneToOnePort already has a subscriber")
        }
        subscribedPort = portRef
        subscribedPortApi = portApi
    }

    override fun unsubscribe(portRef: PortRef) {
        if (subscribedPort == portRef) {
            subscribedPort = null
            subscribedPortApi = Use.fixed(unicastFactory())
        }
    }

    override fun use(portRef: PortRef, block: Api.() -> Any?) {
        subscribedPortApi
            .takeIf { subscribedPort == portRef }
            ?.use { block() }
    }

    override fun use(block: Api.() -> Any?) {
        subscribedPortApi.use { block() }
    }

    companion object {
        inline fun <reified Api : Any> withNoOp(): OneToOnePort<Api> = OneToOnePort { noop() }
    }
}
