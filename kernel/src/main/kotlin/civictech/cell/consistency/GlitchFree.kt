package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*

/**
 * Opt-in glitch-freedom wrapper (spec 20/22): buffers per-wave inputs on [inlet]
 * until the wave's edge set is complete, then replays the wave's invocations to
 * [outlet] as one consistent group, each under its own context.
 *
 * The completeness frontier is the inlet's current link set — recomputed on every
 * check, so link/unlink adapts the condition (topology is part of completeness).
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
) : Cell {

    val inlet = registerPort("inlet", FanInlet(clazz))
    val outlet = registerPort("outlet", FanOutlet(clazz))

    private val pending = LinkedHashMap<Timestamp, LinkedHashMap<PortRef, Invocation>>()

    init {
        inlet.serve(Proxy.fromClass(clazz) { _, method, args ->
            val ctx = CurrentContext.get()
            if (ctx == null) {
                Invocation.of(method, args).invoke(outlet.call)
            } else {
                pending.getOrPut(ctx.timestamp) { LinkedHashMap() }[ctx.sourcePort] =
                    Invocation.of(method, args, ctx)
                flushReady()
            }
            null
        })
        inlet.linking.onUnlink = { flushReady() } // shrinking the edge set may complete waves
    }

    private fun expectedEdges(): Set<PortRef> = inlet.linking.links.map { it.from }.toSet()

    private fun flushReady() {
        val expected = expectedEdges()
        if (expected.isEmpty()) return
        val ready = pending.keys
            .filter { pending.getValue(it).keys.containsAll(expected) }
            .sortedWith(compareBy({ it.sourceId }, { it.counter }))
        for (timestamp in ready) {
            val wave = pending.remove(timestamp) ?: continue
            wave.values.forEach { it.invoke(outlet.call) } // each under its own context
        }
    }

    companion object {
        inline fun <reified Api : Any> create(): GlitchFreeCell<Api> = GlitchFreeCell(Api::class.java)
    }
}
