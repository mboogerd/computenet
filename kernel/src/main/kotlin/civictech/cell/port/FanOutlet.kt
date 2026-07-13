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

    /** Consume-role attachments: SPSC-checked, receive the declared payload form. */
    private val consumers: MutableMap<PortRef, Use<Api>> = mutableMapOf()

    /**
     * Observe-role attachments — taps (spec 20/23 §Taps, G-47): uncounted by
     * the SPSC funnel, always admitted regardless of the exclusive bit. Fire
     * before consumers on emit ("taps-fire-first").
     */
    private val taps: MutableMap<PortRef, Use<Api>> = mutableMapOf()

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
            // Taps fire first, in emission order (spec 20/23 "taps-fire-first"),
            // then consumers — no tap view can alias the buffer once a consumer
            // mutates or moves it.
            taps.values.toList().forEach { target -> invoke(target, method, args) }
            consumers.values.toList().forEach { target -> invoke(target, method, args) }
        }
        null
    }

    private fun invoke(target: Use<Api>, method: java.lang.reflect.Method, args: Array<out Any?>?) {
        try {
            method.invoke(target.call, *(args ?: emptyArray()))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
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
            consumers[portRef]?.call ?: taps[portRef]?.call ?: Proxy.noop(clazz)
        }
    }

    override fun subscribe(port: Use<Api>) {
        // every attach path funnels here: handshake installs, Use.fixed links,
        // cross-host and bridge links alike — "rejectable everywhere". SPSC
        // (spec 23) counts Consume links only — taps are a separate, always-
        // admitted funnel (see [tap]).
        check(!(exclusive && consumers.isNotEmpty() && port.ref !in consumers)) {
            "SPSC (spec 23): ${clazz.name} carries Owned/Leased payloads; a second subscriber is not allowed"
        }
        consumers += port.ref to port
    }

    /**
     * Observe-role attachment (spec 20/23 §Taps, 10/12 §Cardinality rule 2
     * extension, G-47): an uncounted read-only tap, always admitted
     * regardless of the exclusive bit. Fires before the sole consumer on
     * emit, receiving the same invocation — taps are expected to read
     * exclusive payloads via [civictech.cell.Owned.borrow] /
     * [civictech.cell.Leased.borrow], never [civictech.cell.Owned.take] /
     * [civictech.cell.Leased.release].
     */
    fun tap(port: Use<Api>) {
        taps += port.ref to port
    }

    /** Detaches a tap previously installed with [tap]. Idempotent. */
    fun untap(portRef: PortRef) {
        taps.remove(portRef)
    }

    /** Source-side rejection for the handshake path (mirrors Outlet's cardinality style). */
    override fun linkTo(linkFrom: LinkFrom<Api>): LinkResult {
        if (exclusive && consumers.isNotEmpty()) {
            return LinkResult.Rejected(
                "SPSC (spec 23): ${clazz.name} carries Owned/Leased payloads; outlet already has a subscriber"
            )
        }
        return super.linkTo(linkFrom)
    }

    override fun unsubscribe(portRef: PortRef) {
        consumers.remove(portRef)
        taps.remove(portRef)
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
