package civictech.cell.control

import civictech.cell.CurrentContext
import civictech.cell.Timestamp
import civictech.cell.link.Link
import civictech.cell.link.LinkRole
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.InletPolicy
import civictech.cell.port.PolicyTier
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.Invocation
import java.util.UUID

/**
 * Metadata-plane absorb-ack (spec 20/22 §Completeness over silent or stuck
 * edges, G-40, CP-A3): an absorbing operator that consumes a reactive wave
 * without emitting a delta advances a downstream glitch-free join's per-source
 * watermark past the wave it silently swallowed — the second of the three
 * watermark-advance mechanisms (delta, `Progress`, later wave / monotone max).
 * Without it the join can only settle a bridged/absorbing arm from real data
 * arrivals; a mid-pipeline filter/join/antijoin that drops the final wave
 * strands it forever (the CP-A3 control).
 *
 * The ack rides the wave the downstream *would* have seen on this edge — the
 * incoming context's timestamp, which [FanOutlet] preserves on a reactive
 * emission — so it keys the exact `(sourceId, counter)` watermark slot a real
 * delta would have. It fans over the outlet's real links only, so a
 * topology-blind subscriber (`Use.fixed`, plain `subscribe`) pays nothing.
 * Baseline (`StateRequest` catch-up) and spontaneous emissions carry no wave
 * position and are skipped — they are excluded from every completeness set.
 */
internal fun FanOutlet<*>.absorbAck() {
    val ctx = CurrentContext.get() ?: return
    if (ctx.baseline != null) return
    if (AbsorbAckCapture.record(this, ctx.timestamp)) return
    if (linking.links.isEmpty()) return
    val ack = Progress(ctx.timestamp.sourceId, ctx.timestamp.counter)
    sendAbsorbAck(ack)
}

private fun FanOutlet<*>.sendAbsorbAck(progress: Progress) {
    linking.links.forEach { Protocols.sendDownstream(it, Protocols.Progress, progress) }
}

/**
 * Relays an upstream absorb-ack through a transparent operator hop.
 *
 * [Progress] is metadata-plane traffic, so forwarding preserves its original
 * `(sourceId, thru)` watermark verbatim: the hop neither mints a wave nor
 * rewrites source/tag identity. [ProtocolSupport]'s descriptor-directed relay
 * fans it over this inlet's owning cell's downstream links and carries a
 * visited-edge set, so a local cycle cannot amplify the acknowledgement.
 *
 * Relaying one input edge's settlement is sound only when it settles the whole
 * transparent hop. This helper therefore relays only while the hop has exactly
 * one open [LinkRole.Consume] input edge in total. The receiver is counted by
 * default; [otherInlets] must name every sibling inlet that can feed the same
 * operator. Source-to-edge reachability is not available at this layer, so the
 * count is deliberately conservative across sources: this unary overload never
 * claims whole-hop settlement from one edge's [Progress]. Operators that obey
 * the uniform emit-or-[absorbAck] shape can use the `outputs`-taking overload
 * below, which owns the proper per-edge watermark fold. [ProtocolSupport]
 * evaluates this overload's predicate for every arriving acknowledgement, and
 * [FanInlet.linking] exposes only the currently active links, so links added
 * after construction count and an unlinked edge stops counting before the next
 * acknowledgement.
 *
 * A frontier installed on this inlet is also a terminal: local delivery happens
 * first and the dynamic predicate then suppresses relay. This matters when a
 * policy is installed after cell construction — one [Progress] must either be
 * consumed by that frontier or pass through this transparent hop, never both.
 */
internal fun FanInlet<*>.relayAbsorbAcks(vararg otherInlets: FanInlet<*>) {
    val support = ProtocolSupport.of(this)
    support.relay(Protocols.Progress) {
        support.handles(Protocols.Progress) ||
            (sequenceOf(this) + otherInlets.asSequence())
                .flatMap { it.linking.links.asSequence() }
                .count { it.role == LinkRole.Consume } != 1
    }
}

/**
 * Relays absorb-acks through a transparent **fan-in** hop using the same
 * per-open-inlink completeness fold as `WaveFrontier` (spec 20/22
 * `[22-LIVE-01]`). [this] and [otherInlets] form one input frontier;
 * [outputs] are the data outlets whose waved handler obeys the uniform
 * emit-or-[absorbAck] rule.
 *
 * Every real delta and [Progress] advances only the edge it arrived on. A
 * later counter monotonically settles earlier counters on that edge. The
 * exact `Progress(sourceId, thru)` is forwarded once every currently open
 * [LinkRole.Consume] edge **that can actually carry this wave's source**
 * has settled it, except on an output that emitted a real delta for that
 * wave. An `EdgeClose` immediately shrinks the condition; an `EdgeOpen`
 * joins it with a floor at the already-flushed high-water, so it cannot
 * resurrect an old wave but does participate in later arrivals.
 *
 * Data arrivals are observed by a transparent ADMIT policy. While its handler
 * runs, [absorbAck] calls on [outputs] are captured instead of sent early. For
 * each output, calling [absorbAck] means this delivery produced no delta;
 * omitting it means the handler emitted data, as required by the operator
 * suite's emit-or-absorb contract. This lets a data edge settle the input
 * frontier without one absorbed lane claiming whole-hop settlement.
 *
 * An ALIGN policy installed on any participating inlet remains a terminal: it
 * owns settlement and this relay becomes a pass-through. Unmatched, unwaved,
 * baseline, and already-flushed data likewise bypass the fold.
 *
 * **Reading 2** (computenet-t6vex, `[22-LIVE-01]`'s floor-qualified
 * completeness: "every OPEN inlink with floor(s) < t"): an edge only
 * withholds settlement of a source's wave while it can actually carry that
 * source ([SourceProvenance]). This is a best-effort, in-process-only,
 * synchronous resolution — not the general upstream-traversal protocol the
 * spec names as undesigned (G-13's declined multiplex-port traversal form,
 * G-39's hop-by-hop source-set propagation gap) — so it is precise only
 * through two decided shapes: an outlet whose owning cell structurally has no
 * open inbound link, and a chain of [relayAbsorbAcks] fan-in hops that each
 * republish their resolved input provenance plus their outlet's own minted
 * ids. An unpublished outlet on a cell with an open inbound link is
 * "unknown", whatever it emitted before, so its edge remains expected exactly
 * as in Reading 1. Root classification reads the live input topology when
 * Progress is evaluated; an input linked after the wave began therefore
 * withholds that in-flight wave too. These fail-closed rules keep the
 * computenet-6ovpx first-edge-relay safety fix intact: no edge is excluded on
 * emission history or unresolved reachability.
 */
internal fun FanInlet<*>.relayAbsorbAcks(
    outputs: List<FanOutlet<*>>,
    vararg otherInlets: FanInlet<*>,
) {
    SettledAbsorbAckRelay(
        inlets = listOf(this) + otherInlets,
        outputs = outputs,
    )
}

/** One captured operator delivery; nested synchronous operators stack these frames. */
private class AbsorbAckFrame(
    val owner: SettledAbsorbAckRelay,
    val timestamp: Timestamp,
) {
    val requested = LinkedHashSet<FanOutlet<*>>()
}

/**
 * Thread-local interception for the uniform emit-or-absorb call shape. The
 * frame is deliberately delivery-scoped: a synchronous downstream operator
 * installs a nested frame and restores this one when it returns.
 */
private object AbsorbAckCapture {
    private val current = ThreadLocal<AbsorbAckFrame?>()

    fun record(outlet: FanOutlet<*>, timestamp: Timestamp): Boolean {
        val frame = current.get() ?: return false
        if (frame.timestamp != timestamp || !frame.owner.owns(outlet)) return false
        frame.requested += outlet
        return true
    }

    fun <R> within(
        owner: SettledAbsorbAckRelay,
        timestamp: Timestamp,
        block: () -> R,
    ): Pair<R, Set<FanOutlet<*>>> {
        val previous = current.get()
        val frame = AbsorbAckFrame(owner, timestamp)
        current.set(frame)
        try {
            return block() to frame.requested.toSet()
        } finally {
            current.set(previous)
        }
    }
}

/** Per-edge settlement and per-output emission accounting for one operator hop. */
private class SettledAbsorbAckRelay(
    private val inlets: List<FanInlet<*>>,
    outputs: List<FanOutlet<*>>,
) {
    private class EdgeState(
        val inlet: FanInlet<*>,
        val link: Link,
        val floors: Map<UUID, Long>,
        var open: Boolean = true,
    )

    private class Wave {
        /** An output that emitted data needs no Progress for this wave. */
        val emitted = LinkedHashSet<FanOutlet<*>>()
    }

    private data class Delivery(val outlet: FanOutlet<*>, val progress: Progress)

    private val outputs = outputs.distinct()
    private val edges = LinkedHashMap<UUID, EdgeState>()
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()
    private val flushedHighWater = mutableMapOf<UUID, Long>()
    private val pending = LinkedHashMap<Timestamp, Wave>()
    private val lock = Any()

    init {
        require(this.outputs.isNotEmpty()) { "fan-in Progress relay needs at least one output" }
        require(inlets.isNotEmpty()) { "fan-in Progress relay needs at least one input" }
        inlets.forEach { inlet ->
            inlet.onEdgeEvent { link, event ->
                val deliveries = synchronized(lock) {
                    when (event) {
                        EdgeOpen -> edges[link.id] = EdgeState(inlet, link, flushedHighWater.toMap())
                        EdgeClose -> edges[link.id]?.open = false
                    }
                    flushReady()
                }
                deliver(deliveries)
            }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { link, message ->
                onProgress(link, message as Progress)
            }
            inlet.install(RelayPolicy(inlet, this))
        }
        // [22-LIVE-01]'s floor-qualified completeness ("every OPEN inlink with
        // floor(s) < t"), not the source-blind "every open edge" this fold
        // used before: a sibling input edge only withholds settlement of wave
        // (s,t) when it can actually carry source s. Publish each output's own
        // resolved provenance — the union of this hop's currently open input
        // edges' resolved source sets, plus the output's own minted ids (added
        // by SourceProvenance) — so a chain of relay hops composes.
        this.outputs.forEach { output ->
            SourceProvenance.publish(output) { resolvedInputSources() }
        }
    }

    /** The union of this hop's currently open Consume input edges' resolved source sets. */
    private fun resolvedInputSources(): Set<UUID>? {
        val openEdges = synchronized(lock) { edges.values.filter { it.open && it.link.role == LinkRole.Consume }.map { it.link } }
        val result = mutableSetOf<UUID>()
        for (link in openEdges) {
            val sources = SourceProvenance.resolve(link) ?: return null
            result += sources
        }
        return result
    }

    fun owns(outlet: FanOutlet<*>): Boolean = outputs.any { it === outlet }

    /** Observe one waved data invocation while leaving its delivery order untouched. */
    fun offer(inlet: FanInlet<*>, invocation: Invocation, release: (Invocation) -> Unit) {
        val ctx = invocation.context
        if (ctx == null || ctx.baseline != null || aligned()) {
            if (aligned()) resetPending()
            release(invocation)
            return
        }

        val timestamp = ctx.timestamp
        val tracked = synchronized(lock) {
            val edge = edges.values.singleOrNull {
                it.open && it.inlet === inlet && it.link.role == LinkRole.Consume && it.link.from == ctx.sourcePort
            } ?: return@synchronized false
            val floor = edge.floors[timestamp.sourceId] ?: Long.MIN_VALUE
            val flushed = flushedHighWater[timestamp.sourceId] ?: Long.MIN_VALUE
            if (timestamp.counter <= floor || timestamp.counter <= flushed) return@synchronized false
            advanceWatermark(edge.link.id, timestamp.sourceId, timestamp.counter)
            pending.getOrPut(timestamp) { Wave() }
            true
        }
        if (!tracked) {
            release(invocation)
            return
        }

        val (_, absorbed) = AbsorbAckCapture.within(this, timestamp) { release(invocation) }
        val deliveries = synchronized(lock) {
            val wave = pending.getOrPut(timestamp) { Wave() }
            // Every registered output must either emit or absorb on a waved
            // operator delivery. Emission wins if another edge previously
            // absorbed the same wave on that output.
            outputs.filterNotTo(wave.emitted) { output -> output in absorbed }
            flushReady()
        }
        deliver(deliveries)
    }

    fun resetPending() = synchronized(lock) { pending.clear() }

    private fun aligned(): Boolean = inlets.any { it.hasPolicy(PolicyTier.ALIGN) }

    private fun onProgress(link: Link, progress: Progress) {
        if (aligned()) {
            resetPending()
            return
        }
        val deliveries = synchronized(lock) {
            val edge = edges[link.id]
            if (edge == null || !edge.open || edge.link.role != LinkRole.Consume) {
                return@synchronized emptyList()
            }
            advanceWatermark(link.id, progress.sourceId, progress.thru)
            val floor = edge.floors[progress.sourceId] ?: Long.MIN_VALUE
            val flushed = flushedHighWater[progress.sourceId] ?: Long.MIN_VALUE
            if (progress.thru > floor && progress.thru > flushed) {
                pending.getOrPut(Timestamp(progress.sourceId, progress.thru)) { Wave() }
            }
            flushReady()
        }
        deliver(deliveries)
    }

    private fun advanceWatermark(edgeId: UUID, sourceId: UUID, counter: Long) {
        watermark.getOrPut(edgeId) { mutableMapOf() }.merge(sourceId, counter, ::maxOf)
    }

    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.open && it.link.role == LinkRole.Consume }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .filter { edge -> sourceMayReach(edge, timestamp.sourceId) }
        .map { it.link.id }
        .toSet()

    /**
     * Reading 2 ([22-LIVE-01], computenet-t6vex): an edge withholds settlement
     * of wave (s,t) only while it can actually carry source s. A `null`
     * (unknown) resolution keeps the old, safe, source-blind behavior for that
     * edge — this only ever narrows the expected set, never widens it, so a
     * structurally single-source sibling can no longer block an independent
     * source's wave forever (the OperatorAbsorbAckTest regression this reading
     * fixes), while an edge whose reachability is genuinely unresolved still
     * withholds exactly as Reading 1 did (the computenet-6ovpx first-edge-relay
     * hazard stays fixed).
     */
    private fun sourceMayReach(edge: EdgeState, sourceId: UUID): Boolean {
        val sources = SourceProvenance.resolve(edge.link) ?: return true
        return sourceId in sources
    }

    private fun settled(edgeId: UUID, timestamp: Timestamp): Boolean =
        (watermark[edgeId]?.get(timestamp.sourceId) ?: Long.MIN_VALUE) >= timestamp.counter

    /** Called with [lock] held; returns protocol sends to perform after releasing it. */
    private fun flushReady(): List<Delivery> {
        val deliveries = mutableListOf<Delivery>()
        val ready = pending.keys
            .filter { timestamp -> expectedEdges(timestamp).all { settled(it, timestamp) } }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        ready.forEach { timestamp ->
            val wave = pending.remove(timestamp) ?: return@forEach
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            val progress = Progress(timestamp.sourceId, timestamp.counter)
            outputs.filterNot { it in wave.emitted }.forEach { deliveries += Delivery(it, progress) }
        }
        return deliveries
    }

    private fun deliver(deliveries: List<Delivery>) {
        deliveries.forEach { (outlet, progress) -> outlet.sendAbsorbAck(progress) }
    }

    private class RelayPolicy(
        private val inlet: FanInlet<*>,
        private val relay: SettledAbsorbAckRelay,
    ) : InletPolicy {
        override val tier: PolicyTier = PolicyTier.ADMIT
        private lateinit var release: (Invocation) -> Unit

        override fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit) {
            this.release = release
        }

        override fun offer(invocation: Invocation) = relay.offer(inlet, invocation, release)

        override fun reset() = relay.resetPending()
    }
}
