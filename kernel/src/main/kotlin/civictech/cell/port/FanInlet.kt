package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.link.Link
import civictech.cell.link.Linked
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.LinkSupport
import civictech.cell.link.handshake
import civictech.cell.protocol.EdgeEvent
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.Buffering
import civictech.cell.proxy.Invocation
import civictech.cell.control.ParkQueue
import civictech.cell.proxy.Proxy
import civictech.cell.port.PortRef

/**
 * Per-inlet wave-completeness policy (spec 20/22 §Bridged frontier / Completeness
 * over silent or stuck edges, CP-A4): buffers the reactive data waves arriving on
 * an inlet until each wave's edge frontier is complete, then releases them, in
 * per-source counter order, to the inlet's served handler. The [PolicyTier.ALIGN]
 * member of the inlet policy chain (PN-9); opt in via [FanInlet.install] (or the
 * deprecated [FanInlet.frontierPolicy] sugar). The sugar cell
 * [civictech.cell.consistency.GlitchFreeCell] installs one over an inlet→outlet
 * pass-through.
 *
 * A frontier registers its own generic-protocol handlers (topology-order,
 * progress, suspension) when [attach]ed, so edge and watermark markers reach it
 * through the ordinary [ProtocolSupport] delivery path — identical whether the
 * arm is in-process or bridged (spec 40/41 point 4).
 */
interface InletFrontier : InletPolicy

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
    initialRef: PortRef = PortRef.generate(),
    default: Api? = null,
    /**
     * FU-6: opt-in single-writer (strict point-to-point) cardinality. When
     * `true`, [linkFrom] refuses a second [LinkRole.Consume] producer, mirroring
     * [FeedbackInlet]'s point-to-point refusal; Observe taps (read cardinality)
     * stay unrestricted. Default `false` is unconditional fan-in — byte-identical
     * to every existing inlet, no behavior change for non-opting graphs.
     */
    val singleWriter: Boolean = false
) : Use<Api>, Serve<Api>, Linked, DerivedPortRef {

    // PN-1: fresh random at construction, reassigned once at stamp time to the
    // (ownerRef, name)-derived ref when this inlet is registered on a Cell.
    // NB: the parameter is NOT named `ref` — a same-named ctor param would
    // shadow this property inside body lambdas/initializers, capturing the
    // construction-time value instead of the derived ref.
    override var ref: PortRef = initialRef
        private set

    override fun deriveRef(owner: CellRef, name: String) {
        ref = PortRef.of(owner, name)
    }

    override val linking = LinkSupport()

    /** Parked tail (G-55): invocations that arrive cold, awaiting activation. */
    private val parked = ParkQueue<Invocation>()

    /** Cold-state sink: every method call parks instead of dispatching or throwing. */
    private val parkingImplementation: Api = Proxy.fromClass(clazz, Buffering(parked::park))

    /** Current usable API implementation; null while cold (handler not yet installed). */
    private var activeImplementation: Use<Api>? = default?.let { Use.fixed(it, ref) }

    /**
     * The inbound policy chain (PN-9, spec 12 §Policies): an ordered set of
     * [InletPolicy] stages run in fixed [PolicyTier] order (ADMIT →
     * ALIGN → ACTIVATE), install order irrelevant. Each stage is [attach]ed once
     * with a stable release that indirects through [Stage.downstream]; [rewire]
     * only re-links the downstream slots when a stage is added, so no policy is
     * ever double-attached (double protocol-handler registration).
     */
    private class Stage(val policy: InletPolicy) {
        var downstream: (Invocation) -> Unit = {}
    }

    private val stages = mutableListOf<Stage>()

    /** Entry of the built chain; null while no policy is installed (direct dispatch). */
    private var chainEntry: ((Invocation) -> Unit)? = null

    /** The chain terminal: the ACTIVATE tier — dispatch to the handler, or cold-park. */
    private val terminal: (Invocation) -> Unit = { inv ->
        inv.invoke(activeImplementation?.call ?: parkingImplementation)
    }

    /**
     * Install an [InletPolicy] on this inlet (PN-9). Install order is irrelevant;
     * the chain always runs in tier order. At most one [PolicyTier.ALIGN] policy
     * is allowed (spec 12 §Policies) — a second throws.
     */
    fun install(policy: InletPolicy) {
        require(!(policy.tier == PolicyTier.ALIGN && stages.any { it.policy.tier == PolicyTier.ALIGN })) {
            "spec 12 §Policies: at most one ALIGN policy per inlet"
        }
        val stage = Stage(policy)
        stages += stage
        // Attach once; the release indirects through the (later re-wired) slot.
        policy.attach(this) { inv -> stage.downstream(inv) }
        rewire()
    }

    /** True iff a policy of [tier] is installed (spec 34 region detection keys on ALIGN). */
    fun hasPolicy(tier: PolicyTier): Boolean = stages.any { it.policy.tier == tier }

    /**
     * Edge-event fan-out (PN-9): multiple policies on one inlet may care about
     * `EdgeOpen`/`EdgeClose` (an ALIGN frontier tracks edges; a PullOnOpen issues
     * a StateRequest) — but [ProtocolSupport] keeps a single handler per protocol
     * id. This inlet owns that one [Protocols.TopologyOrder] handler and fans the
     * event to every registered observer, in registration order, so policies
     * compose without a global protocol-handler semantic change.
     */
    private val edgeObservers = mutableListOf<(Link, EdgeEvent) -> Unit>()

    fun onEdgeEvent(observer: (Link, EdgeEvent) -> Unit) {
        if (edgeObservers.isEmpty()) {
            ProtocolSupport.of(this).handle(Protocols.TopologyOrder) { link, event ->
                val edgeEvent = event as EdgeEvent
                edgeObservers.toList().forEach { it(link, edgeEvent) }
            }
        }
        edgeObservers += observer
    }

    private fun rewire() {
        val sorted = stages.sortedBy { it.policy.tier.ordinal }
        sorted.forEachIndexed { i, stage ->
            stage.downstream = if (i + 1 < sorted.size) {
                val next = sorted[i + 1].policy
                ({ inv: Invocation -> next.offer(inv) })
            } else {
                terminal
            }
        }
        chainEntry = sorted.firstOrNull()?.let { first -> { inv -> first.policy.offer(inv) } }
    }

    /** Drop transient buffered state across every installed policy (RESTART). */
    fun resetPolicies() = stages.forEach { it.policy.reset() }

    /**
     * Deprecated ALIGN sugar (PN-9): assigning a frontier is now shorthand for
     * [install]. Kept for the many call sites that set a [WaveFrontier] directly;
     * new code should [install] policies (which composes across tiers).
     */
    @Deprecated("Install policies via install(); frontierPolicy is ALIGN-tier sugar", ReplaceWith("install(value)"))
    var frontierPolicy: InletFrontier? = null
        set(value) {
            field = value
            if (value != null) install(value)
        }

    /** Chain entry point: captures each call as a wave-stamped [Invocation] and offers it to the chain head. */
    private val frontierGate: Api by lazy {
        Proxy.fromClass(clazz) { _, method, args ->
            chainEntry?.invoke(Invocation.of(method, args, CurrentContext.get()))
            null
        }
    }

    override val call: Api = Proxy.delegating(clazz) {
        if (chainEntry != null) frontierGate else (activeImplementation?.call ?: parkingImplementation)
    }

    /**
     * Targeted delivery (PN-9 decision: **policy-exempt**). [at] delivers a
     * catch-up/pull-reply to one requester — a topology-versioned baseline
     * ([civictech.cell.MessageContext.baseline]), never a wave position. The
     * ALIGN frontier already releases such deliveries immediately (offer's
     * baseline fast-path); routing [at] through the chain would be wrong (ADMIT
     * could drop, GATE could hold, ALIGN could reorder a baseline — none
     * admissible for state transfer). So [at] bypasses the policy chain and
     * dispatches straight to the handler.
     */
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
        parked.drain().forEach { it.invoke(call) }
    }

    override fun linkFrom(portOut: LinkTo<Api>): LinkResult {
        // FU-6: strict point-to-point on the write side. Refuse before the
        // handshake if a producer already holds the single-writer slot; Observe
        // taps do not count (read cardinality is unrestricted). Mirrors
        // FeedbackInlet's point-to-point refusal shape.
        if (singleWriter && linking.links.any { it.role == LinkRole.Consume }) {
            return LinkResult.Rejected(
                "single-writer inlet already has a producer (strict point-to-point, FU-6)"
            )
        }
        return handshake(
            portOut = portOut,
            target = this,
            targetRef = ref,
            install = { portOut.linkTo(this as Use<Api>) },
            uninstall = { (portOut as? Subscribe<Api>)?.unsubscribe(ref) },
        )
    }

    override fun linkTo(useApi: Use<Api>) {
        delegate(useApi)
    }

    companion object {
        inline fun <reified Api : Any> create(
            ref: PortRef = PortRef.generate(),
            default: Api? = null,
            singleWriter: Boolean = false
        ): FanInlet<Api> = FanInlet(Api::class.java, ref, default, singleWriter)
    }
}