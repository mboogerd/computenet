package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Timestamp
import civictech.cell.attention.SuspensionNotice
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.EdgeClose
import civictech.cell.port.EdgeOpen
import civictech.cell.port.Link
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.registerPort
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*

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
) : Cell {

    /**
     * Suspension interaction (spec 34 decision 3): WAIT holds incomplete waves
     * until parked upstream traffic replays (park-not-drop makes that correct,
     * latency-unbounded); DEGRADE removes suspended edges from the wave
     * frontier — the unlink frontier-shrink, reused — and restores them on
     * resume, passing replayed stale waves through as late catch-up.
     */
    enum class WaveMode { WAIT, DEGRADE }

    val inlet = registerPort("inlet", FanInlet(clazz))
    val outlet = registerPort("outlet", FanOutlet(clazz))

    private val pending = LinkedHashMap<Timestamp, LinkedHashMap<UUID, Invocation>>()

    private data class EdgeState(
        val link: Link,
        val floors: Map<UUID, Long>,
        var open: Boolean = true,
    )

    private val edges = LinkedHashMap<UUID, EdgeState>()

    /** Edges announced suspended by their host (DEGRADE only). */
    private val suspendedEdges = mutableSetOf<UUID>()

    /** Highest flushed wave per source: replayed stragglers pass through, never re-buffer. */
    private val flushedHighWater = mutableMapOf<UUID, Long>()

    init {
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event) {
                EdgeOpen -> edges[link.id] = EdgeState(link, flushedHighWater.toMap())
                EdgeClose -> edges[link.id]?.open = false
                else -> return@handle
            }
            flushReady()
        }
        inlet.serve(Proxy.fromClass(clazz) { _, method, args ->
            val ctx = CurrentContext.get()
            if (ctx == null) {
                Invocation.of(method, args).invoke(outlet.call)
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
                    pending.getOrPut(ctx.timestamp) { LinkedHashMap() }[edge.link.id] =
                        Invocation.of(method, args, ctx)
                    flushReady()
                }
            }
            null
        })
        if (mode == WaveMode.DEGRADE) {
            ProtocolSupport.of(inlet).handle(Protocols.Suspension) { link, notice ->
                when (notice) {
                    SuspensionNotice.Suspended -> {
                        suspendedEdges += link.id
                        flushReady() // shrinking the frontier may complete waves
                    }

                    SuspensionNotice.Resumed -> suspendedEdges -= link.id
                }
            }
        }
    }

    private fun expectedEdges(timestamp: Timestamp): Set<UUID> = edges.values
        .asSequence()
        .filter { it.open && it.link.id !in suspendedEdges }
        .filter { (it.floors[timestamp.sourceId] ?: Long.MIN_VALUE) < timestamp.counter }
        .map { it.link.id }
        .toSet()

    private fun flushReady() {
        val ready = pending.keys
            .filter { pending.getValue(it).keys.containsAll(expectedEdges(it)) }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            flushedHighWater.merge(timestamp.sourceId, timestamp.counter, ::maxOf)
            wave.values.forEach { it.invoke(outlet.call) } // each under its own context
        }
    }

    companion object {
        inline fun <reified Api : Any> create(
            mode: WaveMode = WaveMode.WAIT,
        ): GlitchFreeCell<Api> = GlitchFreeCell(Api::class.java, mode = mode)
    }
}
