package civictech.cell.port

import civictech.cell.control.Progress
import civictech.cell.protocol.EdgeEvent
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import civictech.cell.proxy.Invocation
import civictech.nature.PullService

/**
 * Fixed processing tiers of a [FanInlet] policy chain (PN-9, spec 12 §Policies).
 * Install order is irrelevant; **tier order is authoritative** — a chain always
 * runs ADMIT → GATE → ALIGN → ACTIVATE regardless of the order policies were
 * installed in.
 *
 *  - **ADMIT** — may drop an invocation, never holds one. A dropping ADMIT that
 *    sits above an ALIGN must mint a metadata-plane [Progress] absorb-ack for
 *    every waved invocation it drops ([InletPolicy.mintsProgressAck], the CP-A3
 *    law), or the downstream frontier stalls forever waiting for the dropped
 *    edge's contribution.
 *  - **GATE** — holds invocations FIFO (backpressure / suspension), draining in
 *    arrival order when released.
 *  - **ALIGN** — reorders/buffers for wave completeness
 *    ([civictech.cell.consistency.WaveFrontier]); **at most one per inlet**
 *    (install-time `require`).
 *  - **ACTIVATE** — cold-park until a handler is installed. This tier is
 *    intrinsic to [FanInlet] (its [civictech.cell.proxy.Buffering] parked tail);
 *    it is the terminal of every chain, not an installable policy.
 */
enum class PolicyTier { ADMIT, GATE, ALIGN, ACTIVATE }

/**
 * A composable stage on a [FanInlet]'s inbound path (PN-9). Stages are installed
 * with [FanInlet.install] in any order and run in [tier] order. Each stage
 * receives an [Invocation] via [offer] and forwards it to the next stage through
 * the `release` it was [attach]ed with — or drops/holds it, per its tier.
 */
interface InletPolicy {
    val tier: PolicyTier

    /**
     * CP-A3 law: whether this policy mints a [Progress] absorb-ack for a waved
     * invocation it drops, so a downstream ALIGN frontier can retire the wave on
     * the dropped edge instead of stalling. Only meaningful for [PolicyTier.ADMIT]
     * (the only tier that drops); non-dropping tiers never exercise it.
     */
    val mintsProgressAck: Boolean get() = true

    /** Wire this stage onto [inlet], forwarding admitted invocations through [release]. */
    fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit)

    /** Process one inbound invocation (forward via the attached release, drop, or hold). */
    fun offer(invocation: Invocation)

    /** Drop transient buffered state (RESTART); tier accounting is unaffected. */
    fun reset() {}
}

/**
 * ADMIT tier (spec 12 §Policies): filters inbound invocations by [admits]. A
 * rejected invocation is dropped (never held) and reported to [onDrop]; when
 * [mintsProgressAck] is set, a dropped *waved* invocation also mints a
 * downstream [Progress] absorb-ack on its own edge — the CP-A3 law that keeps an
 * ALIGN frontier below from stalling on the dropped contribution.
 */
class Admit(
    override val mintsProgressAck: Boolean = true,
    private val admits: (Invocation) -> Boolean,
    private val onDrop: (Invocation) -> Unit = {},
) : InletPolicy {
    override val tier get() = PolicyTier.ADMIT

    private var inlet: FanInlet<*>? = null
    private lateinit var release: (Invocation) -> Unit

    override fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit) {
        this.inlet = inlet
        this.release = release
    }

    override fun offer(invocation: Invocation) {
        if (admits(invocation)) {
            release(invocation)
            return
        }
        onDrop(invocation)
        if (mintsProgressAck) mintAck(invocation)
    }

    /**
     * CP-A3: advance the downstream frontier's per-edge watermark past the
     * dropped wave by delivering a [Progress] on the very edge the invocation
     * arrived on (matched by [civictech.cell.MessageContext.sourcePort]). Reuses
     * the ordinary absorb-ack path — the frontier's own [Progress] handler — so a
     * dropped edge contribution is indistinguishable from one silently absorbed
     * upstream.
     */
    private fun mintAck(invocation: Invocation) {
        val ctx = invocation.context ?: return
        val inlet = inlet ?: return
        val link = inlet.linking.links.firstOrNull { it.from == ctx.sourcePort } ?: return
        ProtocolSupport.of(inlet).deliver(
            Protocols.Progress, link, Progress(ctx.timestamp.sourceId, ctx.timestamp.counter)
        )
    }
}

/**
 * GATE tier (spec 12 §Policies): holds invocations FIFO while [closed], draining
 * them in arrival order on [open]. Backpressure / suspension hold — never drops,
 * never reorders.
 */
class Gate : InletPolicy {
    override val tier get() = PolicyTier.GATE

    private lateinit var release: (Invocation) -> Unit
    private val held = ArrayDeque<Invocation>()
    private var open = true

    override fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit) {
        this.release = release
    }

    override fun offer(invocation: Invocation) {
        if (open) release(invocation) else held.addLast(invocation)
    }

    fun close() {
        open = false
    }

    fun open() {
        open = true
        while (held.isNotEmpty()) release(held.removeFirst())
    }

    override fun reset() {
        held.clear()
    }
}

/**
 * Pull-on-open (spec 20/21 §Pull, decided 93 I-16): a consumer-side link
 * lifecycle policy that issues an upstream [StateRequest] on the in-band
 * `EdgeOpen`, so a subscriber is caught up regardless of which side observes the
 * install. Extracted verbatim from [civictech.cell.consistency.WaveFrontier]
 * (PN-9): pull and wave-alignment are now composed, not welded — [GlitchFreeCell]
 * installs both, but either works alone (a plain inlet can pull-on-open without
 * an ALIGN frontier). It fires on the same [Protocols.TopologyOrder] `EdgeOpen`
 * event the frontier tracks — for a bridged consumer that arrives in-band once
 * the bridge is wired, so the emitted StateRequest sequence is identical to
 * pre-PN-9 (local and bridged alike). Data-transparent: [offer] is a pass-through.
 *
 * FU-5 — [requireServing] opts this inlet into the `PULL_SERVICE` refusing axis.
 * Pull-on-open is *not* by itself a hard dependency on a serving producer: a
 * [GlitchFreeCell] installs it against ordinary (non-serving) upstreams and
 * converges fine via its ALIGN frontier and upstream waves — the emitted
 * StateRequest is opportunistic there, tolerated-unanswered. Only a consumer
 * whose baseline arrives *solely* through the pull genuinely starves when the
 * producer cannot answer; such a consumer sets [requireServing] = true, folding
 * `BASELINE_SERVING` onto its inlet so a non-serving producer is refused at the
 * handshake instead of silently starving. Default false keeps every existing
 * install (glitch-free's included) DEFAULT on the axis ⇒ byte-identical.
 */
class PullOnOpen(
    private val since: () -> civictech.cell.TagFrontier? = { null },
    private val requireServing: Boolean = false,
) : InletPolicy {
    // Occupies the ADMIT slot as a data-transparent pass-through: it observes
    // links, not waves, so its position in the chain is immaterial.
    override val tier get() = PolicyTier.ADMIT

    private lateinit var release: (Invocation) -> Unit

    override fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit) {
        this.release = release
        // FU-5: opting in IS the requirement. Fold BASELINE_SERVING onto the inlet's
        // declared vector so a non-serving producer refuses at handshake instead of
        // leaving the StateRequest below unanswered (starved). Off by default so a
        // tolerant puller (e.g. a glitch-free inlet with its own catch-up path)
        // stays DEFAULT on the axis and reconciles exactly as today.
        if (requireServing) {
            PortNatures.stamp(inlet, PortNatures.of(inlet).with(PullService.BASELINE_SERVING))
        }
        inlet.onEdgeEvent { link, event ->
            if (event == EdgeOpen) {
                Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(link.to, since = since()))
            }
        }
    }

    override fun offer(invocation: Invocation) = release(invocation)
}
