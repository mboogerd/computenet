package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.Proxy
import civictech.kernel.germ.proxy.noop
import civictech.kernel.port.PortRef

/**
 * A point-to-point output port.
 *
 * It enforces strict point-to-point connectivity, allowing exactly one external
 * subscriber to receive method calls.
 *
 * Role:
 * - Inside the Cell: [Use] interface is used to emit data to the subscriber.
 * - Outside the Cell: [Subscribe] interface is used to attach a consumer.
 */
class Outlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef,
    private val unicastFactory: () -> Api
) : Use<Api>, Subscribe<Api> {

    private var subscribedPort: PortRef? = null
    private var subscribedPortApi: Use<Api> = Use.fixed(unicastFactory())

    override val call: Api = Proxy.delegating(clazz) { subscribedPortApi.call }

    override fun at(portRef: PortRef): Api {
        return if (subscribedPort == portRef) {
            subscribedPortApi.at(portRef)
        } else {
            Proxy.noop(clazz)
        }
    }

    /**
     * Subscribes the provided [port] to receive method calls from this port.
     *
     * @throws IllegalStateException if the port already has an active subscriber.
     * An explicit [unsubscribe] is required before a new subscriber can be attached.
     */
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

    override fun linkFrom(portOut: LinkTo<Api>) {
        if (subscribedPort != null) {
            throw IllegalStateException("Outlet already has a subscriber and enforces strict point-to-point connectivity.")
        }
        subscribedPort = portOut.ref
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        if (subscribedPort != null) {
            throw IllegalStateException("Outlet already has a subscriber and enforces strict point-to-point connectivity.")
        }
        subscribe(useApi)
    }

    companion object {
        /**
         * Creates an [Outlet] with a No-Op default implementation of [Api].
         */
        inline fun <reified Api : Any> withNoOp(portRef: PortRef = PortRef.generate()): Outlet<Api> =
            Outlet(Api::class.java, portRef) { noop() }
    }
}
