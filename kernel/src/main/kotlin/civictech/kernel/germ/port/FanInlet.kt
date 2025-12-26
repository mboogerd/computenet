package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

/**
 * An aggregating input port that supports multiple concurrent producers.
 *
 * Its "Fan-In" nature comes from allowing many upstream cells to hold its [Use]
 * site and push data into it.
 *
 * Role:
 * - Inside the Cell: [Serve] interface is used to provide logic.
 * - Outside the Cell: [Use] interface is used by multiple upstreams to push data.
 */
class FanInlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef = PortRef.generate(),
    default: Api? = null
) : Use<Api>, Serve<Api> {

    /** Current usable API implementation */
    private var activeImplementation: Use<Api>? = default?.let { Use.fixed(it, ref) }

    override val call: Api = civictech.kernel.germ.proxy.Proxy.delegating(clazz) {
        activeImplementation?.call ?: throw IllegalStateException("Port has not been initialized")
    }

    override fun at(portRef: PortRef): Api {
        return civictech.kernel.germ.proxy.Proxy.delegating(clazz) {
            activeImplementation?.at(portRef) ?: throw IllegalStateException("Port has not been initialized")
        }
    }

    /**
     * Replace the root and invalidates upstream branches
     */
    override fun serve(api: Api) {
        activeImplementation = Use.fixed(api, ref)
    }

    /**
     * Sets the origin to a new Use, clearing any prior origin.
     */
    override fun delegate(port: Use<Api>) {
        require(port != this)
        activeImplementation = port

    }

    override fun linkFrom(portOut: LinkTo<Api>) {
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        delegate(useApi)
    }

    companion object {
        inline fun <reified Api : Any> create(
            ref: PortRef = PortRef.generate(),
            default: Api? = null
        ): FanInlet<Api> = FanInlet(Api::class.java, ref, default)
    }
}