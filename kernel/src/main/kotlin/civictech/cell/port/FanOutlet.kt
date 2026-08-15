package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.PendingReBaseline
import civictech.cell.ReBaselineNotice
import civictech.cell.ReplayScope
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.link.Linked
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.LinkSupport
import civictech.cell.link.PortLink
import civictech.cell.link.handshake
import civictech.cell.protocol.ProtocolAnchored
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.proxy.Proxy
import civictech.nature.ContractRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
) : Use<Api>, Subscribe<Api>, Linked, DerivedPortRef, ProtocolAnchored {

    /**
     * computenet-7iyy: this outlet anchors its own [ProtocolSupport]. Outlets
     * carry the attention, saturation-relay and state-request handlers
     * (`AttentionSupport.wire`, `ManagedHost` spawn, `CatchUp`,
     * `ShardCell`), whose closures capture the owning cell — a globally-rooted
     * support would pin it. Storage only; [ProtocolSupport.of] is the accessor.
     */
    override var protocolSupport: ProtocolSupport? = null

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

    /**
     * Consume-role attachments: SPSC-checked, receive the declared payload
     * form. T04 finding 6: a `ConcurrentHashMap` (was a plain `mutableMapOf`)
     * — [subscribe]/[unsubscribe] (management-band `handshake`) race against
     * the every-emit iteration below on a threaded host; a snapshot
     * iteration is only genuinely safe once the underlying map itself
     * tolerates concurrent structural mutation. Keyed through [keyOf]:
     * `ConcurrentHashMap` forbids a null key, but a cross-host
     * [HostedCellProxy]-backed `Use.ref` genuinely reports null (the real
     * ref lives on the remote side) — [keyOf] substitutes a stable sentinel,
     * preserving the prior plain map's "at most one null-ref entry, last
     * write wins" behavior. [consumerOrder] tracks insertion order
     * separately — `ConcurrentHashMap` (unlike the prior `LinkedHashMap`-
     * backed `mutableMapOf`) does not preserve it, and taps/consumers fire
     * in a documented order (spec 20/23 "taps-fire-first", emission order).
     */
    private val consumers: MutableMap<PortRef, Use<Api>> = ConcurrentHashMap()
    private val consumerOrder = CopyOnWriteArrayList<PortRef>()

    /**
     * Observe-role attachments — taps (spec 20/23 §Taps, G-47): uncounted by
     * the SPSC funnel, always admitted regardless of the exclusive bit. Fire
     * before consumers on emit ("taps-fire-first"). T04 finding 6: same
     * `ConcurrentHashMap` + [keyOf] + order-tracking rationale as [consumers].
     *
     * Holds both Observe-role shapes — the contract-typed [tap] and the
     * payload-agnostic [observe] — in the *same* pair of structures (see
     * [TapTarget]): one order list is what "taps-fire-first, in emission
     * order" means, and a second parallel pair would need T04 finding 6's
     * whole concurrency argument re-made for it.
     */
    private val taps: MutableMap<PortRef, TapTarget> = ConcurrentHashMap()
    private val tapOrder = CopyOnWriteArrayList<PortRef>()

    private fun putConsumer(key: PortRef, port: Use<Api>) {
        if (consumers.put(key, port) == null) consumerOrder += key
    }

    private fun removeConsumer(key: PortRef) {
        if (consumers.remove(key) != null) consumerOrder.remove(key)
    }

    private fun putTap(key: PortRef, target: TapTarget) {
        if (taps.put(key, target) == null) tapOrder += key
    }

    private fun removeTap(key: PortRef) {
        if (taps.remove(key) != null) tapOrder.remove(key)
    }

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
     *
     * **Evaluation contract — once per delivery ATTEMPT.** This outlet calls
     * the filter once per *attempted delivery*, never once per emission:
     * once inside [invoke] for each consumer and each contract-typed tap,
     * once inside [notifyObserver] for each payload-agnostic observer, and
     * once inside [at]'s targeted delivery. So one broadcast to k attachments
     * evaluates it k times, and an emission with no attachment evaluates it
     * not at all. That is what makes a filter which accounts its suppressions
     * (`civictech.cell.membrane.BoundaryPolicy`'s `disclosure` seam, spec
     * 40/43) report exactly one denial per suppressed attempt — a boundary
     * that suppressed N deliveries reports N — with no per-target bookkeeping
     * on this hot path. A filter installed here must therefore be safe to call
     * repeatedly with the *same* argument array within one emission, and must
     * not consume what it is handed; the one landed consequence of that
     * repetition (a suppressing filter routing the same exclusive payload to
     * sanitization once per attempt) is tracked as its own work item and is
     * deliberately not repaired here.
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
            // mutates or moves it. Order comes from consumerOrder/tapOrder
            // (T04 finding 6): ConcurrentHashMap's own iteration order is not
            // insertion order.
            //
            // Both lists are iterated directly, with no intervening copy
            // (audit finding B11). A CopyOnWriteArrayList's iterator IS the
            // stable array snapshot taken at iterator creation — that is
            // precisely the guarantee T04 finding 6 introduced these lists for
            // — so copying one into a second list allocated a throwaway List
            // per tap/consumer set per message, on the hottest path in the
            // runtime, and bought nothing.
            for (key in tapOrder) {
                when (val target = taps[key]) {
                    is TapTarget.Typed -> invoke(target.port, method, args)
                    is TapTarget.Observer -> notifyObserver(target, args, ctx)
                    // untapped between the order snapshot and this step
                    null -> Unit
                }
            }
            for (key in consumerOrder) consumers[key]?.let { target -> invoke(target, method, args) }
        }
        null
    }

    // `Use<*>`, not `Use<Api>`: the consumer path passes a `Use<Api>` and the
    // typed-tap path a [TapTarget.Typed]'s star-projected port, and both end at
    // the same reflective `Method.invoke`, which is untyped anyway.
    private fun invoke(target: Use<*>, method: java.lang.reflect.Method, args: Array<out Any?>?) {
        val filtered = disclosureFilter(args ?: emptyArray()) ?: return
        Proxy.unwrapInvocationTarget {
            method.invoke(target.call, *filtered)
        }
    }

    /**
     * Delivers one emission to a payload-agnostic Observe-role attachment
     * ([observe]) — the [invoke] of the observer shape, on the same dispatch
     * step, inside the same [CurrentContext.with] frame, so the wave-stamping
     * setup is written once in [call] and never duplicated.
     *
     * Disclosure (spec 40/43 seam 3, decided 93 I-28): the observer is handed
     * no arguments, so there is nothing here to redact — but a delivery
     * [disclosureFilter] *suppresses* did not happen at all, and an observer
     * that counted it would report traffic no subscriber ever received. So the
     * filter still gates the notification exactly as it gates [invoke]; only
     * its rewritten arguments are discarded, unread. Not an exemption: an
     * Observe-role attachment is subject to disclosure like any other, it
     * simply has nothing disclosed to it.
     */
    private fun notifyObserver(
        target: TapTarget.Observer,
        args: Array<out Any?>?,
        ctx: MessageContext,
    ) {
        disclosureFilter(args ?: emptyArray()) ?: return
        target.onEmit(ctx)
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
            val key = keyOf(portRef)
            // Only a contract-typed attachment can take a targeted delivery: a
            // payload-agnostic [observe] attachment has no `Api` to invoke, so
            // `at(observerRef)` is a genuine target miss (counted below), not a
            // silent no-op.
            val target = consumers[key]?.call ?: (taps[key] as? TapTarget.Typed)?.port?.call ?: run {
                // T05 finding 7: an unresolvable target used to answer into
                // the void with no signal at all — the delivery path for
                // baselineTo and every targeted catch-up/StateRequest reply.
                // A requester that unlinked between request and reply gets
                // nothing back; a consumer depending on the pull for its
                // baseline starves silently. Counted, and logged once per
                // ref so a genuinely leaking miss is observable without
                // spamming on a routine unlink race.
                targetMissCount.incrementAndGet()
                if (loggedTargetMisses.add(portRef)) {
                    System.err.println("[FanOutlet] at($portRef): no consumer/tap for this target — answered into the void")
                }
                Proxy.noop(clazz)
            }
            val filtered = disclosureFilter(args ?: emptyArray()) ?: return@fromClass null
            Proxy.unwrapInvocationTarget {
                method.invoke(target, *filtered)
            }
        }
    }

    /** T05 finding 7: count of [at] calls that found no consumer/tap for their target ref. */
    val targetMisses: Long get() = targetMissCount.get()
    private val targetMissCount = AtomicLong()

    /** Refs already logged by [at]'s target-miss path — log once per ref, not once per delivery. */
    private val loggedTargetMisses = ConcurrentHashMap.newKeySet<PortRef>()

    override fun subscribe(port: Use<Api>) {
        // every attach path funnels here: handshake installs, Use.fixed links,
        // cross-host and bridge links alike — "rejectable everywhere". SPSC
        // (spec 23) counts Consume links only — taps are a separate, always-
        // admitted funnel (see [tap]).
        check(!(exclusive && consumers.isNotEmpty() && keyOf(port.ref) !in consumers)) {
            "SPSC (spec 23): ${clazz.name} carries Owned/Leased payloads; a second subscriber is not allowed"
        }
        putConsumer(keyOf(port.ref), port)
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
    fun tap(port: Use<Api>, negotiated: Boolean = true): LinkResult {
        // PN-10: opt-in negotiation. When [negotiated] AND the target is a local
        // [Linked] port, the tap runs the same target-side handshake every Consume
        // link runs — policies + peer allowlist + nature reconcile + EdgeOpen —
        // with [LinkRole.Observe], so it announces itself (and refuses on a
        // mismatch where today it dropped silently) yet never gates a wave.
        //
        // PN-12: the default is now [true]. A non-[Linked] target (an ad-hoc
        // `Use.fixed` endpoint, a routed proxy) cannot negotiate, so it falls
        // through to the historic bypass unchanged — the flip is byte-for-byte for
        // every existing tap, and live only for local port-to-port taps.
        (port as? Linked)?.takeIf { negotiated }?.let { target ->
            return handshake(
                portOut = this,
                target = target,
                targetRef = port.ref,
                role = LinkRole.Observe,
                install = { putTap(keyOf(port.ref), TapTarget.Typed(port)) },
                uninstall = { removeTap(keyOf(port.ref)) },
            )
        }
        putTap(keyOf(port.ref), TapTarget.Typed(port))
        return LinkResult.Connected(
            PortLink(ref, port.ref, this, port as? Port, LinkRole.Observe) { removeTap(keyOf(port.ref)) },
        )
    }

    /**
     * Payload-agnostic Observe-role attachment (spec 20/23 §Taps, G-47; audit
     * finding B2) — the second shape of [tap], installed through the very same
     * [putTap] path into the same [taps]/[tapOrder] pair. It is therefore
     * uncounted by the SPSC funnel, always admitted regardless of the
     * exclusive bit, fires before every consumer in tap-insertion order
     * interleaved with contract-typed taps, is never an expected sibling of a
     * wave-completeness set, and so never gates a wave — identical to [tap] in
     * every respect a downstream observer can see.
     *
     * What differs is the shape: [onEmit] is told *that* an emission happened
     * and which [MessageContext] it carries, and is never handed the payload.
     * That is the point. A message counter, a rate sampler or a last-wave
     * recorder has no business decoding `Api`, yet before this the only way to
     * attach one was to synthesize a dynamic proxy of the outlet's contract
     * and hand-screen `Object`'s own methods off the counting path — answering
     * `null` to a primitive-returning `hashCode()` throws on unboxing, on the
     * *emitting cell's* thread. Nothing reachable from here can decode, borrow
     * or retain the payload, so that hazard cannot be re-derived by the next
     * out-of-graph observer.
     *
     * [onEmit] runs on the emitting thread, inside the emission, exactly where
     * a [tap] handler runs: it must not block, and an exception it throws
     * propagates to the emitter just as a tap's does.
     * [CurrentContext.get] inside it answers the same [MessageContext] handed
     * to it, so an observer needs no thread-local read of its own.
     *
     * No handshake: a lambda is not a [Linked] port, so there is no target side
     * to negotiate policies, natures or an `EdgeOpen` with — this takes the
     * same unnegotiated path [tap] takes for a `Use.fixed` endpoint, and so
     * always answers [LinkResult.Connected].
     *
     * [observerRef] is the attachment's identity; detach with [untap] or with
     * [civictech.cell.link.Link.unlink] on the returned link, both idempotent.
     * Deliberately not defaulted: port identity stays explicit, and a caller
     * that cannot name its attachment cannot detach it.
     *
     * NB the parameter is NOT named `ref` — that would shadow this outlet's own
     * [ref] property inside the body (the same footgun the constructor
     * parameter is named around), and the [PortLink] below would then record
     * the observer's ref as *both* endpoints of the link.
     */
    fun observe(observerRef: PortRef, onEmit: (MessageContext) -> Unit): LinkResult {
        val key = keyOf(observerRef)
        putTap(key, TapTarget.Observer(onEmit))
        return LinkResult.Connected(
            PortLink(ref, observerRef, this, null, LinkRole.Observe) { removeTap(key) },
        )
    }

    /**
     * Detaches an Observe-role attachment previously installed with [tap] or
     * [observe] — both live in the same [taps] map, so one detach covers both.
     * Idempotent.
     */
    fun untap(portRef: PortRef) {
        removeTap(keyOf(portRef))
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
        removeConsumer(keyOf(portRef))
        removeTap(keyOf(portRef))
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

        /**
         * T04 finding 6: `ConcurrentHashMap` ([consumers]/[taps]) forbids a
         * null key. A `HostedCellProxy`-backed [Use.ref] genuinely reports
         * null (`HostedCellProxy.portInvocation`'s `getRef` branch — the real
         * ref lives on the remote side); this class-wide sentinel stands in
         * for it, funnelled through [keyOf].
         */
        private val NULL_PORT_REF = PortRef.generate()
    }

    /**
     * Normalizes a possibly-null [Use.ref]/[PortRef] into a map key safe for
     * [consumers]/[taps] (T04 finding 6). [PortRef] is declared non-null in
     * Kotlin, but a value crossing the [Proxy.fromClass] dynamic-proxy
     * boundary (e.g. a cross-host [HostedCellProxy]) can still be a runtime
     * null despite that static type — hence the `?:` here despite the
     * apparently-useless-elvis warning.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun keyOf(candidate: PortRef): PortRef = candidate ?: NULL_PORT_REF
}

/**
 * One Observe-role attachment on a [FanOutlet] (spec 20/23 §Taps, G-47) — the
 * value side of the outlet's `taps` map.
 *
 * The two shapes deliberately share one map + one order list rather than
 * getting a structure each: the `ConcurrentHashMap` + `CopyOnWriteArrayList`
 * pair is what T04 finding 6 (commit `fae2ffa`) put in place so that a
 * `tap`/`untap`/`observe`/`unsubscribe` racing the per-emission iteration can
 * neither corrupt it nor lose a delivery, and a second parallel pair would
 * need that whole argument re-made for it *and* would fracture the single
 * documented emission order taps have ("taps-fire-first", in tap-insertion
 * order).
 *
 * File-private: the distinction is an implementation detail of the fan-out
 * loop. Callers see two attachment methods and one detach.
 */
private sealed interface TapTarget {

    /**
     * A contract-typed tap ([FanOutlet.tap]): receives the declared payload
     * form and is expected to read it read-only
     * ([civictech.cell.Owned.borrow] / [civictech.cell.Leased.borrow], never
     * `take`/`release`). Star-projected — the outlet's own `Api` bound already
     * gates what [FanOutlet.tap] admits here, and the emission path hands the
     * port straight to a reflective `Method.invoke`.
     */
    class Typed(val port: Use<*>) : TapTarget

    /**
     * A payload-agnostic observer ([FanOutlet.observe]): told that an emission
     * happened and which [MessageContext] it carried, never handed the payload.
     */
    class Observer(val onEmit: (MessageContext) -> Unit) : TapTarget
}
