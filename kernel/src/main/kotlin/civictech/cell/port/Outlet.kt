package civictech.cell.port

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.proxy.Proxy
import civictech.cell.proxy.noop
import java.util.concurrent.atomic.AtomicLong

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
) : Use<Api>, Subscribe<Api>, Linked {

    override val linking = LinkSupport()

    private var subscribedPort: PortRef? = null
    private var subscribedPortApi: Use<Api> = Use.fixed(unicastFactory())
    private val waveCounter = AtomicLong()

    // Emission stamps the wave context — see FanOutlet for the rules.
    override val call: Api = Proxy.fromClass(clazz) { _, method, args ->
        val ctx = CurrentContext.get()?.copy(sourcePort = ref)
            ?: MessageContext(Timestamp(ref.id, waveCounter.incrementAndGet()), ref)
        CurrentContext.with(ctx) {
            try {
                method.invoke(subscribedPortApi.call, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }

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

    override fun linkFrom(portOut: LinkTo<Api>): LinkResult {
        if (subscribedPort != null) {
            return LinkResult.Rejected("Outlet at capacity: already has a subscriber (strict point-to-point)")
        }
        return handshake(
            portOut = portOut,
            target = this,
            targetRef = ref,
            install = {
                subscribedPort = portOut.ref
                portOut.linkTo(this as Use<Api>)
            },
            uninstall = {
                (portOut as? Subscribe<Api>)?.unsubscribe(ref)
                if (subscribedPort == portOut.ref) unsubscribe(portOut.ref)
            },
        )
    }

    override fun linkTo(useApi: Use<Api>) {
        if (subscribedPort != null) {
            throw IllegalStateException("Outlet already has a subscriber and enforces strict point-to-point connectivity.")
        }
        subscribe(useApi)
    }

    /** Source-side cardinality: a second link proposal is rejected, not thrown. */
    override fun linkTo(linkFrom: LinkFrom<Api>): LinkResult {
        if (subscribedPort != null) {
            return LinkResult.Rejected("Outlet at capacity: already has a subscriber (strict point-to-point)")
        }
        return linkFrom.linkFrom(this) ?: LinkResult.Deferred
    }

    companion object {
        /**
         * Creates an [Outlet] with a No-Op default implementation of [Api].
         */
        inline fun <reified Api : Any> withNoOp(portRef: PortRef = PortRef.generate()): Outlet<Api> =
            Outlet(Api::class.java, portRef) { noop() }
    }
}
