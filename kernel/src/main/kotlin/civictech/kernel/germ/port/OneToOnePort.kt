package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.noop
import civictech.kernel.port.PortRef

class OneToOnePort<Api : Any>(
    override val ref: PortRef,
    private val unicastFactory: () -> Api
) : Use<Api>, ServeMany<Api> {

    private var subscribedPort: PortRef? = null
    private var subscribedPortApi: Use<Api> = Use.fixed(unicastFactory())

    override fun subscribe(port: Use<Api>) {
        if (subscribedPort != null) {
            throw IllegalStateException("OneToOnePort already has a subscriber")
        }
        subscribedPort = port.ref
        subscribedPortApi = port
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

    override fun linkFrom(portOut: LinkTo<Api>) {
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        subscribe(useApi)
    }

    companion object {
        inline fun <reified Api : Any> withNoOp(portRef: PortRef = PortRef.generate()): OneToOnePort<Api> =
            OneToOnePort(portRef) { noop() }
    }
}
