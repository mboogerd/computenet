package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.PendingReBaseline
import civictech.cell.ReBaselineNotice
import civictech.cell.ReplayScope
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.link.CurrentPeer
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
     * **Evaluation contract — at most once per EMISSION, lazily** (`[SEC1-19]`,
     * decided in `computenet-usd.2`/`computenet-usd.2.2`). One broadcast
     * evaluates this filter **at most once**, whatever the number of
     * attachments: the first attempted delivery of an emission evaluates it,
     * and the verdict — the rewritten argument array, or suppression — is
     * shared unchanged by every contract-typed tap, every payload-agnostic
     * observer and every consumer of that same emission (see
     * [EmissionDisclosure]). "At most", not "exactly": an emission with no
     * attachment attempts no delivery and so evaluates the filter **zero**
     * times, and evaluation is lazy for exactly that reason. [at]'s targeted
     * delivery ([baselineTo]'s catch-up unicast / pull reply) is its own
     * emission frame with a single target, hence its own single evaluation.
     *
     * A `Project` transform therefore runs once per emitted payload rather
     * than once per subscriber, which is what makes it safe over an exclusive
     * argument — and a projection **must still borrow, never consume**: it may
     * read an [civictech.cell.Owned] / [civictech.cell.Leased] argument through
     * `borrow()` only. The single consumption SPSC allocates belongs to the
     * sole consumer's `take()`/`release()`; taps borrow. A projection that
     * consumed would take the payload out from under that consumer even though
     * it now runs only once (D2, `computenet-usd.2.2`).
     *
     * **Accounting stays per delivery ATTEMPT, and is separate from
     * evaluation.** A filter that accounts its suppressions
     * (`civictech.cell.membrane.BoundaryPolicy`'s `disclosure` seam, spec
     * 40/43) must still report one denial per *suppressed attempt* — a
     * boundary that suppressed N deliveries reports N — which was previously a
     * free consequence of evaluating N times. Since it no longer is, the two
     * are split: the one evaluation accounts the **first** suppressed attempt
     * itself, carrying the refused arguments to sanitization exactly once, and
     * every **further** suppressed attempt of the same emission is accounted
     * through [onRepeatSuppression], which re-runs no transform and carries no
     * arguments. So the counter still moves per attempt while the exclusive
     * payload is discharged exactly once (`[SEC1-20]`).
     */
    @Volatile
    var disclosureFilter: (Array<out Any?>) -> Array<out Any?>? = IDENTITY_DISCLOSURE

    /**
     * Accounts one **further** suppressed delivery attempt of an emission this
     * outlet's [disclosureFilter] already suppressed — the additive half of the
     * evaluation/accounting split described there (`computenet-usd.2.2`).
     *
     * Invoked once per suppressed attempt *after* the first, and never for a
     * delivered one. Deliberately no-args: the refused arguments rode to
     * sanitization with the first suppression and have been discharged
     * (`Owned -> Frozen`, `Leased -> ` [civictech.cell.Redacted]) by then, so
     * handing them here again is precisely the double-discharge `[SEC1-20]`
     * forbids. Null by default — an outlet with no mediated disclosure has
     * nothing to account, and `civictech.cell.membrane.CompositeCell`'s
     * `mediateOutlet` is the only installer.
     */
    @Volatile
    var onRepeatSuppression: (() -> Unit)? = null

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
            //
            // One disclosure verdict per emission, shared by every attachment
            // below ([SEC1-19], see [disclosureFilter]). Read the @Volatile
            // filter ONCE here: an emission must not straddle a `mediateOutlet`
            // install, and the loops below must not re-read it per attachment.
            val gate = disclosureFilter.let { filter ->
                if (filter === IDENTITY_DISCLOSURE) null
                else EmissionDisclosure(filter, onRepeatSuppression)
            }
            // A broadcast carries no peer (`computenet-usd.8`, deciding 93 I-28
            // §8's fan-out corner as `LocalTrusted`). `CurrentPeer` is a bare
            // ThreadLocal, so everything a remote delivery synchronously causes
            // inherits it — and since `computenet-usd.4.3` a `PORT_PROTOCOL`
            // frame installs one. A `Protocols.Progress` ack that completes a
            // wave in a gated organelle (`CoalescingCombineCell`, `WaveGate`)
            // runs `flushReady()` -> `propagate(...)` right here, on the acking
            // peer's thread, for a delta addressed to every attachment rather
            // than to that peer: the disclosure verdict and the
            // `BoundaryDenialSink` record would name a peer who is neither the
            // requester nor a recipient of this emission. The peer is not
            // *wrong* about anything it names — it is simply not this
            // crossing's identity, and this fan-out has no single one: each
            // attachment is a [PortRef] no peer is derivable from, and a
            // remote one gets its own stamped ingress a hop further down.
            //
            // The reset is HERE, at the fan-out, and deliberately not at
            // `ManagedHost`'s delivery frame: the other shape a remote frame
            // causes — a pull/catch-up reply through [at] — IS addressed to the
            // asking peer and must keep `Peer`. Narrowing the frame would take
            // both (`PeerUnblockedFanOutPrincipalTest` pins the pair).
            //
            // Scope only: `[SEC1-19]`'s single shared verdict and `[SEC1-20]`'s
            // exactly-once discharge are untouched — the gate above is still
            // computed once per emission and shared by every attachment below.
            // Whether disclosure could ever be decided *per recipient* is 93
            // I-28 §8 cross-hop composition, open and outside SEC1.
            //
            // Read-first, write-never on the local path (P2/P6): in-process
            // traffic has no stamp, so the hot path pays one ThreadLocal read
            // and no set/restore at all.
            if (CurrentPeer.get() == null) fanOut(method, args, ctx, gate)
            else CurrentPeer.with(null) { fanOut(method, args, ctx, gate) }
        }
        null
    }

    /**
     * The broadcast itself: taps first, in emission order (spec 20/23
     * "taps-fire-first"), then consumers. Extracted from [call] only so the
     * peer scope decided in `computenet-usd.8` can wrap the whole fan-out in
     * one place; the loops are otherwise verbatim, iterating both lists
     * directly with no intervening copy (audit finding B11).
     */
    private fun fanOut(
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
        ctx: MessageContext,
        gate: EmissionDisclosure?,
    ) {
        for (key in tapOrder) {
            when (val target = taps[key]) {
                is TapTarget.Typed -> invoke(target.port, method, args, gate)
                is TapTarget.Observer -> notifyObserver(target, args, ctx, gate)
                // untapped between the order snapshot and this step
                null -> Unit
            }
        }
        for (key in consumerOrder) consumers[key]?.let { target -> invoke(target, method, args, gate) }
    }

    // `Use<*>`, not `Use<Api>`: the consumer path passes a `Use<Api>` and the
    // typed-tap path a [TapTarget.Typed]'s star-projected port, and both end at
    // the same reflective `Method.invoke`, which is untyped anyway.
    private fun invoke(
        target: Use<*>,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
        gate: EmissionDisclosure?,
    ) {
        val raw = args ?: emptyArray()
        // A null gate is the identity filter: nothing to evaluate, nothing to
        // memoize, byte-for-byte the pre-disclosure path (P2/P6).
        val filtered = if (gate == null) raw else (gate.verdict(raw) ?: return)
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
     *
     * The gate is the emission's *shared* verdict, not a fresh evaluation
     * (`[SEC1-19]`, [disclosureFilter]): notifying an observer never re-runs a
     * `Project` transform. It is still a delivery **attempt**, so a repeat of
     * an already-suppressed emission is accounted through [onRepeatSuppression]
     * — the gating semantics are unchanged, only the evaluation count is.
     */
    private fun notifyObserver(
        target: TapTarget.Observer,
        args: Array<out Any?>?,
        ctx: MessageContext,
        gate: EmissionDisclosure?,
    ) {
        if (gate != null && gate.verdict(args ?: emptyArray()) == null) return
        target.onEmit(ctx)
    }

    /**
     * One emission's disclosure verdict, computed lazily on the first attempted
     * delivery and shared by every later attempt of that same emission
     * (`[SEC1-19]`, decided in `computenet-usd.2.2` — see [disclosureFilter]).
     *
     * Deliberately unsynchronized and allocated per emission: one emission is
     * one dispatch step on one thread (the emitting cell's), the taps/consumers
     * loops in [call] are that thread's, and the instance never escapes them.
     * A `@Volatile` field or a lock here would buy nothing and cost the hottest
     * path in the runtime. An outlet with no mediated disclosure allocates
     * nothing at all — [call] passes a null gate for the identity filter.
     *
     * [suppressed] is not merely "verdict == null": that would conflate "no
     * attempt has evaluated yet" with "evaluated, and suppressed", and the
     * first attempt is the only one allowed to carry the refused arguments to
     * sanitization.
     */
    private class EmissionDisclosure(
        private val filter: (Array<out Any?>) -> Array<out Any?>?,
        private val onRepeatSuppression: (() -> Unit)?,
    ) {
        private var evaluated = false
        private var suppressed = false
        private var filtered: Array<out Any?>? = null

        /**
         * This emission's verdict for one delivery attempt: the rewritten
         * arguments, or null to suppress the attempt. The first call evaluates
         * the filter (which accounts its own suppression, arguments and all);
         * every later call re-reads the decision and, when it was suppression,
         * accounts this further attempt through the no-args repeat hook.
         */
        fun verdict(args: Array<out Any?>): Array<out Any?>? {
            if (!evaluated) {
                evaluated = true
                val result = filter(args)
                suppressed = result == null
                filtered = result
                return result
            }
            if (suppressed) {
                onRepeatSuppression?.invoke()
                return null
            }
            return filtered
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
        //
        // No EmissionDisclosure gate here, and none is needed: a targeted
        // delivery IS its own emission frame with exactly one target, so the
        // single evaluation below already satisfies the at-most-once rule
        // ([SEC1-19]) and is also the frame's first (and only) suppressed
        // attempt when it suppresses — arguments ride to sanitization exactly
        // once, the repeat hook never fires. Catch-up is unregressed by
        // computenet-usd.2.2 in both count and accounting.
        //
        // What the missing gate is NOT is the reason [TargetedDelivery] exists
        // (computenet-oenm). A suppressing filter installed by
        // `CompositeCell.mediateOutlet` also *classifies* the refusal — the
        // landed I-18 `StallNotice.Stall(DEAD_LETTERED, ts)` walk over the
        // outlet's `Consume` links — and that walk is right for a broadcast and
        // over-broad here: a suppressed targeted delivery starves exactly one
        // consumer, the target, while the walk told every consumer of this
        // outlet that its edge would not deliver. Downstream that is a spurious
        // watermark advance plus a `GlitchViolation` on links nothing was
        // withheld from. The corner is reachable through supported API and not
        // merely in principle — [at] is `Use`'s targeted-delivery method,
        // public on the very outlet object `mediateOutlet` exposes — but it is
        // NOT reachable through any of today's in-kernel [at] callers, which is
        // why it went unnoticed: [baselineTo] stamps a
        // [MessageContext.baseline] and `catchUpOnLinked` /
        // `LookupJoinCell` / `PresenceCountCell` deliver context-less, and the
        // walk's own guard (`context == null || context.baseline != null`,
        // `computenet-usd.3.1`) returns without classifying for both. The
        // corner is exactly a NON-baseline targeted suppression, and
        // `FanOutletTest` pins it.
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
            // Only the filter evaluation is scoped, never the delivery below:
            // whatever the target's handler synchronously causes is its own
            // traffic and must not inherit this frame's single-recipient scope.
            val filtered = TargetedDelivery.to(portRef) {
                disclosureFilter(args ?: emptyArray())
            } ?: return@fromClass null
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

        /**
         * The default [disclosureFilter]: identity, and a *shared singleton* so
         * that [call] can recognize it by reference and skip allocating an
         * [EmissionDisclosure] per emission entirely (P2/P6 — an outlet with no
         * mediated disclosure pays one reference comparison per emission and
         * nothing else). A per-instance `{ it }` lambda would be a distinct
         * object per outlet and defeat that test.
         */
        private val IDENTITY_DISCLOSURE: (Array<out Any?>) -> Array<out Any?>? = { it }
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
 * The single recipient of the targeted delivery ([FanOutlet.at]) whose
 * disclosure filter is evaluating right now on this thread, or null when the
 * evaluating filter belongs to a broadcast ([FanOutlet.call]) — computenet-oenm.
 *
 * This exists for one consumer: the I-18 "edge that will not deliver"
 * classification a suppressing `BoundaryPolicy.disclosure` filter emits
 * (`CompositeCell.stallDeniedEdges`). That walk sends
 * `StallNotice.Stall(DEAD_LETTERED, ts)` over the refusing outlet's `Consume`
 * links, which is exactly right for a broadcast — every consumer really was
 * starved — and over-broad for a targeted delivery, where only the target was.
 * A thread-scoped hint rather than a filter parameter because the filter's type
 * is deliberately arguments-only (`FanOutlet.disclosureFilter`,
 * `BoundaryDenials`): widening it would put a hot-path cost and a public
 * signature change on every filter author to serve one accounting seam.
 *
 * [take] reads **and clears**, and the clearing is the point rather than an
 * optimization: the walk it feeds delivers protocol frames to downstream
 * handlers, and anything one of those synchronously emits is a fresh emission
 * that must not inherit this frame's recipient. Reading a stale target there
 * would scope a *later* classification to a link that emission never had, and
 * silently drop it. Falling back to null instead re-widens the walk to today's
 * whole-fan-out behavior, which is over-broad but never silently lossy.
 *
 * `internal`, and a `ThreadLocal` rather than a `CoroutineContext` element, for
 * the same reason `civictech.cell.link.CurrentPeer` is: an emission is one
 * dispatch step on one thread, and nothing here may park.
 */
internal object TargetedDelivery {

    private val current = ThreadLocal<PortRef?>()

    /** Runs [block] with [portRef] as this thread's targeted-delivery recipient, restoring the prior scope after. */
    fun <T> to(portRef: PortRef, block: () -> T): T {
        val prior = current.get()
        current.set(portRef)
        try {
            return block()
        } finally {
            if (prior == null) current.remove() else current.set(prior)
        }
    }

    /** This thread's targeted-delivery recipient, cleared by the read (see the class KDoc). */
    fun take(): PortRef? = current.get()?.also { current.remove() }
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
