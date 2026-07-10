package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation
import java.util.concurrent.ConcurrentHashMap

/**
 * Which host currently serves a [CellRef] (spec 33/41, G-5). The fast path is
 * spec 33's contract — one volatile map read + enqueue. On closure or absence
 * the invocation **parks** in per-ref order (the Buffering pattern at location
 * granularity; the wave context rides each invocation, G-4) and replays into
 * the next [publish]ed host before the fast path becomes visible again.
 *
 * In-process only until the wire layer (G-15, M5); the interface is the seam
 * remote addressing will fill.
 */
class LocationRegistry {

    private val locations = ConcurrentHashMap<CellRef, ManagedHost>()
    private val parked = ConcurrentHashMap<CellRef, MutableList<HostedPortInvocation>>()

    /** The host currently serving [ref], if any. */
    fun locate(ref: CellRef): ManagedHost? = locations[ref]

    /** Parked invocations awaiting a [publish] for [ref] (test/introspection surface). */
    fun parkedFor(ref: CellRef): List<HostedPortInvocation> =
        parked[ref]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /**
     * Optimistic send with lazy re-resolution: enqueue on the located host;
     * on closed intake or no location, park in order. Never blocks the sender,
     * never drops (O(rare-event) cost lands here, not on the fast path).
     */
    fun deliver(invocation: HostedPortInvocation) {
        locations[invocation.cellRef]?.let { host ->
            try {
                host.enqueueHostedInvocation(invocation)
                return
            } catch (_: IntakeClosedException) {
                // fall through to park
            }
        }
        val queue = parked.computeIfAbsent(invocation.cellRef) { mutableListOf() }
        synchronized(queue) {
            // re-check under the per-ref lock so a concurrent publish can't strand this invocation
            locations[invocation.cellRef]?.let { host ->
                try {
                    host.enqueueHostedInvocation(invocation)
                    return
                } catch (_: IntakeClosedException) {
                }
            }
            queue.add(invocation)
        }
    }

    /**
     * Make [host] the location of [ref]. Parked invocations replay — in park
     * order — before the fast path sees the new location, preserving the
     * accepted-then-parked-then-new total order per link (spec 33).
     */
    fun publish(ref: CellRef, host: ManagedHost) {
        val queue = parked.computeIfAbsent(ref) { mutableListOf() }
        synchronized(queue) {
            queue.forEach(host::enqueueHostedInvocation)
            queue.clear()
            locations[ref] = host
        }
    }

    /** Remove [ref]'s location; subsequent deliveries park until the next [publish]. */
    fun unpublish(ref: CellRef) {
        locations.remove(ref)
    }
}
