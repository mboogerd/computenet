package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.Proxy
import civictech.kernel.germ.proxy.noop
import civictech.kernel.germ.port.PortRef

/**
 * A point-to-point input port.
 *
 * It enforces strict point-to-point connectivity, allowing only a single producer
 * (either a concrete implementation via [serve] or a delegation via [delegate]).
 *
 * Role:
 * - Inside the Cell: [Serve] interface is used to provide logic.
 * - Outside the Cell: [Use] interface is used by upstreams to push data.
 */
class Inlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef,
    unicastFactory: () -> Api
) : Use<Api>, Serve<Api> {

    private var activeProducer: PortRef? = null
    private var activeProducerApi: Use<Api> = Use.fixed(unicastFactory())

    override val call: Api = Proxy.delegating(clazz) { activeProducerApi.call }

    override fun at(portRef: PortRef): Api {
        return if (activeProducer == portRef) {
            activeProducerApi.at(portRef)
        } else {
            Proxy.noop(clazz)
        }
    }

    override fun serve(api: Api) {
        activeProducer = null
        activeProducerApi = Use.fixed(api)
    }

    override fun delegate(port: Use<Api>) {
        activeProducer = port.ref
        activeProducerApi = port
    }

    override fun linkFrom(portOut: LinkTo<Api>) {
        if (activeProducer != null) {
            throw IllegalStateException("Inlet already has an active producer and enforces strict point-to-point connectivity.")
        }
        activeProducer = portOut.ref
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        if (activeProducer != null) {
            throw IllegalStateException("Inlet already has an active producer and enforces strict point-to-point connectivity.")
        }
        delegate(useApi)
    }

    companion object {
        /**
         * Creates an [Inlet] served with a No-Op default implementation of [Api].
         */
        inline fun <reified Api : Any> withNoOp(portRef: PortRef = PortRef.generate()): Inlet<Api> =
            Inlet(Api::class.java, portRef) { noop() }

        /**
         * Creates an [Inlet] served with the provided [api] instance.
         */
        inline fun <reified Api : Any> withApi(api: Api, portRef: PortRef = PortRef.generate()): Inlet<Api> =
            Inlet(Api::class.java, portRef) { api }.apply { serve(api) }
    }
}
