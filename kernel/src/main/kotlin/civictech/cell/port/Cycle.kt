package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Magnitude
import civictech.cell.link.Linked
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkSupport
import civictech.cell.link.handshake
import civictech.cell.protocol.ProtocolAnchored
import civictech.cell.protocol.ProtocolSupport
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Raised when a hop-guard bound (spec 20/22 §MessageContext, 93 I-5) or the
 * cycle-edge ownership rule (spec 20/23 §Cycle edges, 93 I-6) trips. Both are
 * backstops for a correctly-headed graph, not expected traffic — the host
 * dead-letters the offending invocation under this cause rather than letting
 * a headless or cross-host loop run unbounded.
 */
class CycleError(message: String) : RuntimeException(message)

/**
 * Marker for a cell that declares at least one cycle-closing terminus (spec
 * 21 §Cycles, 10/13 `CycleWithoutHead`, 93 I-5): every cycle MUST have at
 * least one such head. [feedbackInput] is the conventional Consumer-shaped
 * absorbing inlet — see [FeedbackInlet] and its shared [FeedbackPort] core.
 * Link-time cycle admission
 * ([civictech.cell.host.ManagedHost.connect]) accepts a locally-visible
 * cycle-closing edge only when it lands on a [FeedbackPort] port; declaring
 * [CycleHead] on a Consumer-shaped cell documents intent for readers of the
 * graph. The interface remains Consumer-shaped for source compatibility;
 * Propagate-shaped heads declare a [PropagateFeedbackInlet] directly.
 */
interface CycleHead<D : Any> {
    val feedbackInput: FeedbackInlet<D>
}

/**
 * Shared feedback-absorbing core for the terminus of a cycle-closing edge
 * (spec 21 §Cycles, 10/13 `CycleWithoutHead`, 93 I-5/I-6). Every cycle MUST
 * declare at least one [FeedbackPort]-backed head. Distinct from an ordinary [Inlet]/
 * [FanInlet]:
 *
 * - **Absorbed, not joined**: the returning lap terminates here — it never
 *   enters any downstream glitch-free completeness set. [onLap] runs under a
 *   **freshly minted** [Timestamp] from this head's own emission epoch, the
 *   one precisely-located exception to transparent flow (20/22 rule 2); hop
 *   is therefore reset to 0 for the next iteration by construction.
 * - **Two-tier quiescence** (93 I-6): a [Magnitude] delta is the *weak* tier
 *   — absorbed without invoking [onLap] (no re-origination) when
 *   `size() <= quiescence`, a divergence damper, not a proof. Any other
 *   delta is treated as the *strong* tier — idempotent-merge, threshold-free
 *   by construction (the framework relies on upstream effective-only
 *   emission to have already dropped empty deltas; see [Magnitude] for why
 *   this is a runtime `is`-check rather than a KSP descriptor bit, same as
 *   magnitude-band dispatch, spec 34).
 * - **`Leased` is forbidden on cycle edges** (20/23, 93 I-6): a lease
 *   circulating a loop has ambiguous release responsibility. Structural
 *   (link-time) rejection is a topology-visibility problem the same as
 *   `CycleWithoutHead` (see [civictech.cell.host.ManagedHost.connect]); this
 *   is the runtime backstop that fires regardless of how the edge was wired.
 * - **Fusion barrier** (§Fusion, 93 I-6): re-origination MUST enqueue on the
 *   host queue even co-hosted, bounding stack depth to O(1) per lap. [barrier]
 *   defaults to an inline call for bare/unhosted use;
 *   [civictech.cell.host.ManagedHost] rebinds it at spawn time for every
 *   [FeedbackPort] it finds on a cell.
 */
abstract class FeedbackPort<D : Any>(
    initialRef: PortRef = PortRef.generate(),
    val quiescence: Double = 0.0,
    /**
     * The erased payload class, when the port was declared via [feedbackInlet]
     * or [propagateFeedbackInlet] (which reify `D`). It lets link-time
     * admission apply the same `is Magnitude` damping test this inlet dispatches
     * on at runtime ([absorb]), without a KSP descriptor — see
     * [civictech.cell.host.ManagedHost.connect]. `null` for bare/direct
     * construction, in which case the payload-type witness simply does not fire.
     */
    val payloadType: Class<*>? = null,
    private val onLap: (D) -> Unit,
) : Port, Linked, DerivedPortRef, ProtocolAnchored {

    final override var ref: PortRef = initialRef
        private set

    override fun deriveRef(owner: CellRef, name: String) {
        ref = PortRef.of(owner, name)
    }

    /** The top-level port API shape used by link-time payload negotiation. */
    abstract val apiClass: Class<*>

    /**
     * computenet-7iyy: a cell-owned port anchors its own [ProtocolSupport]
     * rather than leaving it in a JVM-global map that would pin the port and,
     * through [onLap], the cell. See [ProtocolAnchored]; [ProtocolSupport.of]
     * is the accessor.
     */
    override var protocolSupport: ProtocolSupport? = null

    override val linking = LinkSupport()

    private var activeProducer: PortRef? = null

    /** Fusion barrier hook (see class doc) — rebound by the host at spawn time. */
    var barrier: (() -> Unit) -> Unit = { it() }

    /** This head's own emission epoch (93 I-5): fresh per construction, one counter per iteration. */
    private val headSourceId: UUID = UUID.randomUUID()
    private val headCounter = AtomicLong()

    /**
     * The promotion-gate probe (spec 53 §Cycle promotion gates on quiescence,
     * G-19/G-50): whether the most recently absorbed delta's [Magnitude]
     * fell at/under [quiescence]. `null` means no G-19 throttling is in
     * effect yet — either no delta has been observed, or the observed
     * payload wasn't [Magnitude]-typed — and per 53 "without G-19
     * throttling, cycle promotion is deferred, not attempted", callers MUST
     * treat `null` the same as `false`, never as vacuously quiescent.
     */
    var lastQuiescent: Boolean? = null
        private set

    /** One absorb/quiescence/fresh-wave core shared by every feedback API shape. */
    protected fun absorb(input: D) {
        if (input is Leased<*>) {
            throw CycleError(
                "CycleRejectsLeased: Leased is forbidden on cycle edges (spec 20/23, 93 I-6); freeze() or copy first"
            )
        }
        val magnitude = input as? Magnitude
        lastQuiescent = magnitude?.let { it.size() <= quiescence }
        val effective = magnitude?.let { it.size() > quiescence } ?: true
        if (!effective) return // weak tier: absorbed, not re-originated
        barrier {
            val fresh = MessageContext(Timestamp(headSourceId, headCounter.incrementAndGet()), ref)
            CurrentContext.with(fresh) { onLap(input) }
        }
    }

    /** Single-producer targeted delivery shared by the concrete API shapes. */
    protected fun <Api : Any> at(portRef: PortRef, call: Api, inactive: () -> Api): Api =
        if (activeProducer == portRef) call else inactive()

    /** Single-producer admission and teardown shared by the concrete API shapes. */
    protected fun <Api : Any> linkFrom(portOut: LinkTo<Api>, target: Use<Api>): LinkResult {
        if (activeProducer != null) {
            return LinkResult.Rejected("FeedbackInlet at capacity: already has an active producer (strict point-to-point)")
        }
        return handshake(
            portOut = portOut,
            target = this,
            targetRef = ref,
            install = { activeProducer = portOut.ref; portOut.linkTo(target) },
            uninstall = {
                (portOut as? Subscribe<Api>)?.unsubscribe(ref)
                if (activeProducer == portOut.ref) activeProducer = null
            },
        )
    }
}

/**
 * Consumer-shaped [FeedbackPort]. Its constructor and public behavior are the
 * original feedback-inlet surface; all cycle-head semantics live in the base.
 */
class FeedbackInlet<D : Any>(
    ref: PortRef = PortRef.generate(),
    quiescence: Double = 0.0,
    payloadType: Class<*>? = null,
    onLap: (D) -> Unit,
) : FeedbackPort<D>(ref, quiescence, payloadType, onLap), Use<Consumer<D>> {

    override val apiClass: Class<*> = Consumer::class.java

    override val call: Consumer<D> = object : Consumer<D> {
        override fun provide(input: D) = absorb(input)
    }

    override fun at(portRef: PortRef): Consumer<D> =
        super.at(portRef, call) {
            object : Consumer<D> {
                override fun provide(input: D) {}
            }
        }

    override fun linkFrom(portOut: LinkTo<Consumer<D>>): LinkResult = super.linkFrom(portOut, this)
}

/**
 * Propagate-shaped [FeedbackPort] for the kernel's standard incremental data
 * path. It differs from [FeedbackInlet] only in its port API shape.
 */
class PropagateFeedbackInlet<D : Any>(
    ref: PortRef = PortRef.generate(),
    quiescence: Double = 0.0,
    payloadType: Class<*>? = null,
    onLap: (D) -> Unit,
) : FeedbackPort<D>(ref, quiescence, payloadType, onLap), Use<Propagate<D>> {

    override val apiClass: Class<*> = Propagate::class.java

    override val call: Propagate<D> = Propagate { absorb(it) }

    override fun at(portRef: PortRef): Propagate<D> = super.at(portRef, call) { Propagate { _ -> } }

    override fun linkFrom(portOut: LinkTo<Propagate<D>>): LinkResult = super.linkFrom(portOut, this)
}
