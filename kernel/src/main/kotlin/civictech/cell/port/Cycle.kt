package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.MessageContext
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
 * least one such head. [feedbackInput] is the absorbing inlet — see
 * [FeedbackInlet]. Link-time cycle admission
 * ([civictech.cell.host.ManagedHost.connect]) accepts a locally-visible
 * cycle-closing edge only when it lands on a [FeedbackInlet] port; declaring
 * [CycleHead] on the cell documents intent for readers of the graph.
 */
interface CycleHead<D : Any> {
    val feedbackInput: FeedbackInlet<D>
}

/**
 * The feedback-absorbing terminus of a cycle-closing edge (spec 21 §Cycles,
 * 10/13 `CycleWithoutHead`, 93 I-5/I-6). Every cycle MUST declare at least
 * one [FeedbackInlet]-backed head. Distinct from an ordinary [Inlet]/
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
 *   [FeedbackInlet] port it finds on a cell.
 */
class FeedbackInlet<D : Any>(
    override val ref: PortRef = PortRef.generate(),
    val quiescence: Double = 0.0,
    /**
     * The erased payload class, when the port was declared via the [feedbackInlet]
     * delegate (which reifies `D`). It lets link-time admission apply the same
     * `is Magnitude` damping test this inlet dispatches on at runtime (Cycle.kt
     * `provide`), without a KSP descriptor — see
     * [civictech.cell.host.ManagedHost.connect]. `null` for bare/direct
     * construction, in which case the payload-type witness simply does not fire.
     */
    val payloadType: Class<*>? = null,
    private val onLap: (D) -> Unit,
) : Use<Consumer<D>>, Linked, ProtocolAnchored {

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

    override val call: Consumer<D> = object : Consumer<D> {
        override fun provide(input: D) {
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
    }

    override fun at(portRef: PortRef): Consumer<D> =
        if (activeProducer == portRef) call else object : Consumer<D> {
            override fun provide(input: D) {}
        }

    override fun linkFrom(portOut: LinkTo<Consumer<D>>): LinkResult {
        if (activeProducer != null) {
            return LinkResult.Rejected("FeedbackInlet at capacity: already has an active producer (strict point-to-point)")
        }
        return handshake(
            portOut = portOut,
            target = this,
            targetRef = ref,
            install = { activeProducer = portOut.ref; portOut.linkTo(this as Use<Consumer<D>>) },
            uninstall = {
                (portOut as? Subscribe<Consumer<D>>)?.unsubscribe(ref)
                if (activeProducer == portOut.ref) activeProducer = null
            },
        )
    }
}
