package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.PendingReBaseline
import civictech.cell.ReBaselineNotice
import civictech.cell.ReplayScope
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.proxy.Proxy
import civictech.gen.wire.ContractRegistry
import java.util.UUID
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
    initialRef: PortRef = PortRef.generate()
) : Use<Api>, Subscribe<Api>, Linked, DerivedPortRef {

    // PN-1: fresh random at construction, reassigned once at stamp time to the
    // (ownerRef, name)-derived ref when this outlet is registered on a Cell.
    // NB: the parameter is NOT named `ref` — a same-named ctor param would
    // shadow this property inside body lambdas (e.g. the `call` proxy), which
    // would then capture the construction-time value and never see the derived
    // ref, defeating the whole ticket.
    override var ref: PortRef = initialRef
        private set

    override fun deriveRef(owner: CellRef, name: String) {
        ref = PortRef.of(owner, name)
    }

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
     * Disclosure filter (spec 40/43 seam 3, decided 93 I-28, 20/21 §Pull,
     * W4.1): applied uniformly to every `PORT_API` emission this outlet
     * makes, whether a broadcast [call] (live stream) or a single-target
     * [at] delivery (the `onLinked` catch-up baseline / on-demand pull
     * reply) — "a snapshot IS a delta, one filter covers both", filtered not
     * forked. Returning null suppresses that particular delivery entirely.
     * Identity by default — zero cost, today's behavior, byte-for-byte
     * (P2/P6): only a Mediate exposure that declares a non-`Full`
     * `disclosure` policy installs a non-identity filter here.
     */
    @Volatile
    var disclosureFilter: (Array<out Any?>) -> Array<out Any?>? = { it }

    /**
     * This outlet's current emission epoch (spec 20/22 §Source identity: a
     * "source" is one outlet during one emission epoch — never the port
     * identity itself). Fresh at construction (cold start mints a fresh
     * epoch by default); [adoptWaveState] overrides it for a
     * preserved-epoch continuation, [mintFreshEpoch] rotates it forward.
     */
    @Volatile
    private var sourceId: UUID = UUID.randomUUID()

    /**
     * SPSC rule (spec 23, G-21 phase 2): a contract carrying `Owned`/`Leased`
     * payloads gets exactly one subscriber. Read from generated metadata —
     * no runtime reflection; un-annotated contracts are never exclusive.
     */
    private val exclusive: Boolean =
        ContractRegistry.descriptor(clazz)?.methods?.any { it.exclusive } == true

    override val call: Api = Proxy.fromClass(clazz) { _, method, args ->
        // PN-2: during a journal replay every emission in the replayed cone
        // rides as a catch-up baseline, not a live wave. A reactive emission
        // inherits the baseline the replayed frame already carries (the copy
        // below); a *spontaneous* emission (a cell that originates mid-replay)
        // reads it from [ReplayScope], the exact analogue of [PendingReBaseline].
        val ctx = CurrentContext.get()?.let { it.copy(sourcePort = ref, hop = it.hop + 1, baseline = it.baseline ?: ReplayScope.get()) }
            ?: MessageContext(Timestamp(sourceId, waveCounter.incrementAndGet()), ref, PendingReBaseline.get(), baseline = ReplayScope.get())
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
        val filtered = disclosureFilter(args ?: emptyArray()) ?: return
        try {
            method.invoke(target.call, *filtered)
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

    /** Current `(sourceId, highWater)` (spec 20/22, 93 I-14 Rule S1) — the unit a preserved-epoch transfer moves wholesale. */
    fun waveState(): OutletWaveState = OutletWaveState(sourceId, waveCounter.get())

    /**
     * Preserved-epoch adoption (suspend/resume, migration, promotion state
     * transfer): this outlet continues the given source lane instead of its
     * own — invisible to downstream completeness, no `ReBaseline` (93 I-11).
     */
    fun adoptWaveState(state: OutletWaveState) {
        sourceId = state.sourceId
        waveCounter.set(state.highWater)
    }

    /**
     * Mints a fresh emission epoch (cold start default; RESTART, replica/
     * candidate spawn, and fallback promotion swaps call this explicitly) and
     * returns the superseded `sourceId` (spec 93 I-14 Rule S1).
     */
    fun mintFreshEpoch(): UUID {
        val superseded = sourceId
        sourceId = UUID.randomUUID()
        waveCounter.set(0)
        return superseded
    }

    /**
     * Emits [block] as a RESTART re-baseline (spec 93 I-22 R2): a fresh
     * origination — the ordinary spontaneous-emission path — flagged with
     * [ReBaselineNotice] so the receiving [MessageContext] carries it.
     */
    fun reBaseline(supersedes: Set<UUID>, supersede: Boolean, block: Api.() -> Unit) {
        val notice = ReBaselineNotice(supersedes, supersede)
        CurrentContext.with(null) {
            PendingReBaseline.with(notice) { call.block() }
        }
    }

    /**
     * Emits [block] as a catch-up baseline (spec 20/21 §Pull, 20/22
     * §Interaction, decided in 93 I-24) to a single requester [replyTo]: a
     * fresh wave from this outlet's own counter (FIFO/sequencing, the I-16
     * reply rule) whose context carries [frontier] as
     * [MessageContext.baseline] — excluded from every wave-completeness set,
     * a glitch-free consumer installs it as arm state instead of buffering
     * it. Delivered only to [replyTo] via [at], never broadcast to every
     * subscriber.
     */
    fun baselineTo(replyTo: PortRef, frontier: TagFrontier, block: Api.() -> Unit) {
        val ctx = MessageContext(Timestamp(sourceId, waveCounter.incrementAndGet()), ref, baseline = frontier)
        CurrentContext.with(ctx) { at(replyTo).block() }
    }

    override fun at(portRef: PortRef): Api {
        // The catch-up/pull-reply path (baselineTo): routed through the same
        // disclosureFilter as broadcast [invoke] so one filter covers both
        // (spec 40/43 seam 3, 20/21 §Pull) — targeted delivery still bypasses
        // taps/consumers fan-out, unchanged.
        return Proxy.fromClass(clazz) { _, method, args ->
            val target = consumers[portRef]?.call ?: taps[portRef]?.call ?: Proxy.noop(clazz)
            val filtered = disclosureFilter(args ?: emptyArray()) ?: return@fromClass null
            try {
                method.invoke(target, *filtered)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
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
