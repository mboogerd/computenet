package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellError
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.ErrorReporting
import civictech.cell.Timestamp
import civictech.cell.attention.Progress
import civictech.cell.attention.StallNotice
import civictech.cell.data.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.EdgeClose
import civictech.cell.port.EdgeOpen
import civictech.cell.port.Link
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.StateRequest
import civictech.cell.port.registerPort
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*

/**
 * A glitch-free join's frontier condition was violated: a contribution was
 * lost for good (dead-lettered) and the join advanced past it rather than
 * waiting forever (spec 20/22, 30/31 rule 5, decided in 93 I-18). Surfaced on
 * [GlitchFreeCell.errorOutlet] as the [CellError.cause].
 */
class GlitchViolation(message: String) : Exception(message)

/**
 * Opt-in glitch-freedom wrapper (spec 20/22): buffers per-wave inputs on [inlet]
 * until the wave's edge set is complete, then replays the wave's invocations to
 * [outlet] as one consistent group, each under its own context.
 *
 * The completeness frontier is folded from in-band EdgeOpen/EdgeClose markers.
 * Wave order is per-source counter order; per-link FIFO (spec 31) makes wave
 * completion monotone per source.
 *
 * ponytail: static link-set frontier; real upstream traversal ("describe your
 * frontier") needs multiplex ports (G-13). Unwaved traffic passes through.
 *
 * Eager cell (C-7): serves in init, usable unhosted; safe without onActivate.
 */
class GlitchFreeCell<Api : Any>(
    clazz: Class<Api>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    mode: WaveMode = WaveMode.WAIT,
) : Cell, ErrorReporting {

    /**
     * Recoverable-stall interaction (spec 34 decision 3): WAIT holds incomplete
     * waves until parked upstream traffic replays (park-not-drop makes that
     * correct, latency-unbounded); DEGRADE removes recoverably-stalled edges
     * from the wave frontier — the unlink frontier-shrink, reused — and
     * restores them on resume, passing replayed stale waves through as late
     * catch-up. Terminal stalls (spec 20/22, decided in 93 I-18) always
     * RE-SCOPE regardless of mode — WAIT/DEGRADE only ever govern recoverable
     * causes.
     */
    enum class WaveMode { WAIT, DEGRADE }

    val inlet = registerPort("inlet", FanInlet(clazz))
    val outlet = registerPort("outlet", FanOutlet(clazz))
    override val errorOutlet = registerPort("errorOutlet", FanOutlet.create<Propagate<CellError>>())

    private val pending = LinkedHashMap<Timestamp, LinkedHashMap<UUID, Invocation>>()

    private data class EdgeState(
        val link: Link,
        val floors: Map<UUID, Long>,
        var open: Boolean = true,
    )

    private val edges = LinkedHashMap<UUID, EdgeState>()

    /** Edges announced recoverably stalled by their host (DEGRADE only). */
    private val suspendedEdges = mutableSetOf<UUID>()

    /**
     * Per-edge, per-source watermark (spec 20/22 "Completeness over silent or
     * stuck edges"): the highest counter known settled on that edge. Advances
     * on a real data delta, on a metadata-plane [Progress] absorb-ack, or on a
     * later wave (monotone `max`) — so an edge that silently absorbs a wave
     * (no delta) is retired by whatever it next produces, never stuck.
     * Completeness of wave (s, t): every OPEN, non-suspended inlink with
     * floor(s) < t has watermark(s) >= t.
     */
    private val watermark = mutableMapOf<UUID, MutableMap<UUID, Long>>()

    /** Highest flushed wave per source: replayed stragglers pass through, never re-buffer. */
    private val flushedHighWater = mutableMapOf<UUID, Long>()

    init {
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event) {
                EdgeOpen -> {
                    edges[link.id] = EdgeState(link, flushedHighWater.toMap())
                    // On-demand pull (spec 20/21 §Pull, G-18 residual, decided
                    // in 93 I-16): a fresh link issues a StateRequest so a
                    // subscriber-side subscription is caught up without
                    // depending on which side observes the install — the
                    // producer's own onLinked push (spec 21, G-22) remains
                    // the co-hosted fast path; idempotence (observed-remove
                    // tags, 24) makes racing both harmless.
                    Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(link.to, since = null))
                }
                EdgeClose -> edges[link.id]?.open = false
                else -> return@handle
            }
            flushReady()
        }
        inlet.serve(Proxy.fromClass(clazz) { _, method, args ->
            val ctx = CurrentContext.get()
            if (ctx == null) {
                Invocation.of(method, args).invoke(outlet.call)
            } else if (ctx.baseline != null) {
                // Catch-up baseline (spec 20/21 §Pull, 20/22 §Interaction,
                // decided in 93 I-24): a topology-versioned state-as-delta,
                // never a wave position — install as arm state immediately,
                // never buffered, never touching floors/watermark/pending,
                // so it is excluded from every wave-completeness set.
                Invocation.of(method, args, ctx).invoke(outlet.call)
            } else {
                val edge = edges.values.singleOrNull { it.open && it.link.from == ctx.sourcePort }
                    ?: return@fromClass null
                val floor = edge.floors[ctx.timestamp.sourceId] ?: Long.MIN_VALUE
                if (ctx.timestamp.counter <= floor) return@fromClass null
                if (ctx.timestamp.counter <= (flushedHighWater[ctx.timestamp.sourceId] ?: Long.MIN_VALUE)) {
                    // a wave that already completed without this edge (DEGRADE +
                    // resume replay): emit late rather than buffer forever —
                    // catch-up semantics, spec 21
                    Invocation.of(method, args, ctx).invoke(outlet.call)
                } else {
                    advanceWatermark(edge.link.id, ctx.timestamp.sourceId, ctx.timestamp.counter)
                    pending.getOrPut(ctx.timestamp) { LinkedHashMap() }[edge.link.id] =
                        Invocation.of(method, args, ctx)
                    flushReady()
                }
            }
            null
        })
        ProtocolSupport.of(inlet).handle(Protocols.Suspension) { link, message ->
            when (val notice = message as StallNotice) {
                is StallNotice.Stall -> when {
                    !notice.recoverable -> reScope(link, notice)
                    mode == WaveMode.DEGRADE -> {
                        suspendedEdges += link.id
                        flushReady() // shrinking the frontier may complete waves
                    }
                    // WAIT: a recoverable stall needs no action here — the join
                    // resolves it either by the eventual Resume + real replay, or
                    // by a later wave's monotone watermark advance.
                }

                StallNotice.Resume -> {
                    suspendedEdges -= link.id
                    flushReady()
                }
            }
        }
        ProtocolSupport.of(inlet).handle(Protocols.Progress) { link, message ->
            val progress = message as Progress
            advanceWatermark(link.id, progress.sourceId, progress.thru)
            flushReady()
        }
    }

    private fun advanceWatermark(edgeId: UUID, sourceId: UUID, counter: Long) {
        watermark.getOrPut(edgeId) { mutableMapOf() }.merge(sourceId, counter, ::maxOf)
    }

    private fun isSettled(edgeId: UUID, timestamp: Timestamp): Boolean =
        (watermark[edgeId]?.get(timestamp.sourceId) ?: Long.MIN_VALUE) >= timestamp.counter

    /**
     * RE-SCOPE (spec 20/22, 30/31 rule 5, decided in 93 I-18): the only
     * admissible disposition for a terminal stall. With a known [Stall.timestamp]
     * (the wave the failing invocation was itself processing) only that wave's
     * watermark advances — rescuing exactly the poisoned wave, leaving the
     * edge open for future ones. Without one, the edge closes outright (the
     * unlink frontier-shrink), unblocking everything pending on it. Either way
     * a [GlitchViolation] surfaces on [errorOutlet].
     */
    private fun reScope(link: Link, stall: StallNotice.Stall) {
        val timestamp = stall.timestamp
        if (timestamp != null) {
            advanceWatermark(link.id, timestamp.sourceId, timestamp.counter)
        } else {
            edges[link.id]?.open = false
        }
        val detail = if (timestamp != null) " wave $timestamp" else ""
        errorOutlet.call.propagate(
            CellError(ref, GlitchViolation("edge ${link.id} ${stall.reason}$detail — advanced past the poisoned wave"))
        )
        flushReady()
    }

    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.open && it.link.id !in suspendedEdges }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    private fun flushReady() {
        val ready = pending.keys
            .filter { timestamp -> expectedEdges(timestamp).all { isSettled(it, timestamp) } }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            wave.values.forEach { it.invoke(outlet.call) } // each under its own context
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (spec 20/22, 30/31 rule 5,
     * decided in 93 I-18): the transient version buffer is dropped — any
     * partially-collected wave was never observed downstream, so dropping it
     * is safe. Floors, the watermark, and flushed high-water all stay valid
     * (they record what genuinely happened, not what this instance holds), so
     * frontier accounting for waves arriving after restart is unaffected.
     */
    override fun onDeactivate(ctx: CellContext) {
        pending.clear()
    }

    companion object {
        inline fun <reified Api : Any> create(
            mode: WaveMode = WaveMode.WAIT,
        ): GlitchFreeCell<Api> = GlitchFreeCell(Api::class.java, mode = mode)
    }
}
