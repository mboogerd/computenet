package civictech.cell.port

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.proxy.Proxy
import civictech.gen.wire.ContractRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * A broadcasting output port.
 * When [use] is called, it broadcasts the invocation to all subscribed ports.
 *
 * Emission is the context-stamping point (spec 20/22): a reactive call keeps the
 * incoming timestamp with this port as [MessageContext.sourcePort]; a spontaneous
 * call mints a fresh wave from this port's counter. All subscribers of one call
 * receive the same context.
 */
class FanOutlet<Api : Any>(
    val clazz: Class<Api>,
    override val ref: PortRef = PortRef.generate()
) : Use<Api>, Subscribe<Api>, Linked {

    override val linking = LinkSupport()

    private val subscriptions: MutableMap<PortRef, Use<Api>> = mutableMapOf()
    private val waveCounter = AtomicLong()

    /**
     * SPSC rule (spec 23, G-21 phase 2): a contract carrying `Owned`/`Leased`
     * payloads gets exactly one subscriber. Read from generated metadata —
     * no runtime reflection; un-annotated contracts are never exclusive.
     */
    private val exclusive: Boolean =
        ContractRegistry.descriptor(clazz)?.methods?.any { it.exclusive } == true

    override val call: Api = Proxy.fromClass(clazz) { _, method, args ->
        val ctx = CurrentContext.get()?.copy(sourcePort = ref)
            ?: MessageContext(Timestamp(ref.id, waveCounter.incrementAndGet()), ref)
        CurrentContext.with(ctx) {
            // snapshot: link/unlink during a wave must not fail the broadcast
            subscriptions.values.toList().forEach { target ->
                try {
                    method.invoke(target.call, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
        null
    }

    /**
     * Emit at a declared origination boundary even when called reactively.
     * The normal stamping path then mints a fresh wave from this outlet.
     */
    fun originate(block: Api.() -> Unit) {
        CurrentContext.with(null) { call.block() }
    }

    override fun at(portRef: PortRef): Api {
        return Proxy.delegating(clazz) {
            subscriptions[portRef]?.call ?: Proxy.noop(clazz)
        }
    }

    override fun subscribe(port: Use<Api>) {
        // every attach path funnels here: handshake installs, Use.fixed links,
        // cross-host and bridge links alike — "rejectable everywhere"
        check(!(exclusive && subscriptions.isNotEmpty() && port.ref !in subscriptions)) {
            "SPSC (spec 23): ${clazz.name} carries Owned/Leased payloads; a second subscriber is not allowed"
        }
        subscriptions += port.ref to port
    }

    /** Source-side rejection for the handshake path (mirrors Outlet's cardinality style). */
    override fun linkTo(linkFrom: LinkFrom<Api>): LinkResult {
        if (exclusive && subscriptions.isNotEmpty()) {
            return LinkResult.Rejected(
                "SPSC (spec 23): ${clazz.name} carries Owned/Leased payloads; outlet already has a subscriber"
            )
        }
        return super.linkTo(linkFrom)
    }

    override fun unsubscribe(portRef: PortRef) {
        subscriptions.remove(portRef)
    }

    override fun linkFrom(portOut: LinkTo<Api>): LinkResult = handshake(
        portOut = portOut,
        target = this,
        targetRef = ref,
        install = { portOut.linkTo(this as Use<Api>) },
        uninstall = { (portOut as? Subscribe<Api>)?.unsubscribe(ref) },
    )

    override fun linkTo(useApi: Use<Api>) {
        subscribe(useApi)
    }

    companion object {
        inline fun <reified Api : Any> create(
            ref: PortRef = PortRef.generate()
        ): FanOutlet<Api> = FanOutlet(Api::class.java, ref)
    }
}
