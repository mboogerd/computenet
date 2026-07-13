package civictech.cell.port

import civictech.cell.proxy.Buffering
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import civictech.cell.port.PortRef

/**
 * An aggregating input port that supports multiple concurrent producers.
 *
 * Its "Fan-In" nature comes from allowing many upstream cells to hold its [Use]
 * site and push data into it.
 *
 * Role:
 * - Inside the Cell: [Serve] interface is used to provide logic.
 * - Outside the Cell: [Use] interface is used by multiple upstreams to push data.
 *
 * **Admission vs activation** (10/15, 10/13, G-55): the port's structural layers
 * (name, descriptor, policies, [linking]) exist and are binding from
 * construction — admission runs in any phase. Dispatch is behavioral and
 * requires a handler: before [serve]/[delegate] installs one, this is a *cold*
 * port and inbound invocations MUST NOT throw or drop — they park, in order
 * with their contexts, in the [Buffering] primitive (its parked-tail use) and
 * replay against the handler the moment it is installed, before any
 * post-activation send lands.
 */
class FanInlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef = PortRef.generate(),
    default: Api? = null
) : Use<Api>, Serve<Api>, Linked {

    override val linking = LinkSupport()

    /** Parked tail (G-55): invocations that arrive cold, awaiting activation. */
    private val parked = mutableListOf<Invocation>()

    /** Cold-state sink: every method call parks instead of dispatching or throwing. */
    private val parkingImplementation: Api = Proxy.fromClass(clazz, Buffering(parked))

    /** Current usable API implementation; null while cold (handler not yet installed). */
    private var activeImplementation: Use<Api>? = default?.let { Use.fixed(it, ref) }

    override val call: Api = Proxy.delegating(clazz) {
        activeImplementation?.call ?: parkingImplementation
    }

    override fun at(portRef: PortRef): Api {
        return Proxy.delegating(clazz) {
            activeImplementation?.at(portRef) ?: parkingImplementation
        }
    }

    /**
     * Replace the root and invalidates upstream branches
     */
    override fun serve(api: Api) {
        activeImplementation = Use.fixed(api, ref)
        replayParked()
    }

    /**
     * Sets the origin to a new Use, clearing any prior origin.
     */
    override fun delegate(port: Use<Api>) {
        require(port != this)
        activeImplementation = port
        replayParked()
    }

    /**
     * Handler-establishment time (activation): drains the parked tail, in
     * order, against the just-installed implementation before returning — no
     * post-activation send can land ahead of it (10/15 §Admission vs activation).
     */
    private fun replayParked() {
        if (parked.isEmpty()) return
        val tail = parked.toList()
        parked.clear()
        tail.forEach { it.invoke(call) }
    }

    override fun linkFrom(portOut: LinkTo<Api>): LinkResult = handshake(
        portOut = portOut,
        target = this,
        targetRef = ref,
        install = { portOut.linkTo(this as Use<Api>) },
        uninstall = { (portOut as? Subscribe<Api>)?.unsubscribe(ref) },
    )

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