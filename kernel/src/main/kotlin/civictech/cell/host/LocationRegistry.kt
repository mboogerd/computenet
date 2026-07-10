package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a [CellRef] currently lives (spec 33/41, G-5, G-15). The fast path is
 * spec 33's contract — one volatile map read + enqueue (local) or one frame
 * encode (remote). On closure or absence the invocation **parks** in per-ref
 * order (the Buffering pattern at location granularity; the wave context
 * rides each invocation, G-4) and replays into the next [publish]ed location
 * before the fast path becomes visible again.
 *
 * M5.4: a location is [Local] (a host on this registry) or [Remote] (an
 * `InvocationSink` — in practice a bridge egress, spec 41). Remote locations
 * are learned via peer announcements (`cell.wire.Peering`); senders never
 * know which side of the wire a ref lives on.
 */
class LocationRegistry {

    sealed interface Location
    data class Local(val host: ManagedHost) : Location
    data class Remote(val sink: InvocationSink) : Location

    private val locations = ConcurrentHashMap<CellRef, Location>()
    private val parked = ConcurrentHashMap<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Fire after a *local* publish — the announcement seam (M5.4; multicast
     * since M7.2 so a registry can peer with several remotes). Remote
     * publishes never re-announce, so mirrored registries cannot loop.
     */
    private val onLocalPublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()

    /** Fire after *any* publish (local or remote) — the replica-discovery seam (M7.2, spec 42). */
    private val onPublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()

    fun onLocalPublish(listener: (CellRef) -> Unit) {
        onLocalPublish += listener
    }

    fun onPublish(listener: (CellRef) -> Unit) {
        onPublish += listener
    }

    /** Every published ref sharing [logicalId] — replicas, local and remote (spec 42). */
    fun replicasOf(logicalId: java.util.UUID): Set<CellRef> =
        locations.keys.filterTo(mutableSetOf()) { it.id == logicalId }

    /** The host currently serving [ref] on this registry, if local. */
    fun locate(ref: CellRef): ManagedHost? = (locations[ref] as? Local)?.host

    fun location(ref: CellRef): Location? = locations[ref]

    /** Refs currently published as [Local] — the initial-sync set for a new peer. */
    fun localRefs(): Set<CellRef> =
        locations.entries.filter { it.value is Local }.mapTo(mutableSetOf()) { it.key }

    /** Parked invocations awaiting a [publish] for [ref] (test/introspection surface). */
    fun parkedFor(ref: CellRef): List<HostedPortInvocation> =
        parked[ref]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /**
     * Optimistic send with lazy re-resolution: enqueue on the located host or
     * hand to the remote sink; on closed intake or no location, park in order.
     * Never blocks the sender, never drops (O(rare-event) cost lands here,
     * not on the fast path).
     */
    fun deliver(invocation: HostedPortInvocation) {
        if (send(locations[invocation.cellRef], invocation)) return
        val queue = parked.computeIfAbsent(invocation.cellRef) { mutableListOf() }
        synchronized(queue) {
            // re-check under the per-ref lock so a concurrent publish can't strand this invocation
            if (send(locations[invocation.cellRef], invocation)) return
            queue.add(invocation)
        }
    }

    private fun send(location: Location?, invocation: HostedPortInvocation): Boolean = when (location) {
        is Local -> try {
            location.host.enqueueHostedInvocation(invocation)
            true
        } catch (_: IntakeClosedException) {
            false
        }

        is Remote -> {
            location.sink.deliver(invocation)
            true
        }

        null -> false
    }

    /**
     * Make [host] the location of [ref]. Parked invocations replay — in park
     * order — before the fast path sees the new location, preserving the
     * accepted-then-parked-then-new total order per link (spec 33).
     */
    fun publish(ref: CellRef, host: ManagedHost) {
        install(ref, Local(host))
        onLocalPublish.forEach { it(ref) }
        onPublish.forEach { it(ref) }
    }

    /** Make [ref] remote, reachable through [sink] (a bridge egress, spec 41). */
    fun publish(ref: CellRef, sink: InvocationSink) {
        install(ref, Remote(sink))
        onPublish.forEach { it(ref) }
    }

    private fun install(ref: CellRef, location: Location) {
        val queue = parked.computeIfAbsent(ref) { mutableListOf() }
        synchronized(queue) {
            queue.forEach { check(send(location, it)) { "replay into fresh location failed for $ref" } }
            queue.clear()
            locations[ref] = location
        }
    }

    /** Remove [ref]'s location; subsequent deliveries park until the next [publish]. */
    fun unpublish(ref: CellRef) {
        locations.remove(ref)
    }

    /**
     * Drop every [Remote] location routed through [via] — the transport's
     * disconnect hook (M5.5): senders park until the peer re-announces.
     */
    fun unpublishRemotes(via: InvocationSink) {
        locations.entries.removeIf { (it.value as? Remote)?.sink === via }
    }
}
