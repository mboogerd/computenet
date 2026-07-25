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

    val topology = TopologyIndex()

    sealed interface Location
    data class Local(val host: ManagedHost) : Location
    data class Remote(val sink: InvocationSink) : Location

    private val locations = ConcurrentHashMap<CellRef, Location>()
    private val parked = ConcurrentHashMap<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Per-instance [civictech.cell.replication.Interest] (spec 40/42
     * §Interest-scoped instance sets, CP-D2): the demand predicate the gossip
     * linker consults to decide whether a link forms and to filter each
     * emission to the target's interest. Unset ⇒ total interest — every
     * instance wants every delta, so the linker's behavior is byte-identical
     * to pre-interest gossip (the replication default).
     */
    private val interests = ConcurrentHashMap<CellRef, civictech.cell.replication.Interest>()

    /** Declare [ref]'s interest (the interest-assignment table entry, CP-D2/CP-D3). */
    fun setInterest(ref: CellRef, interest: civictech.cell.replication.Interest) {
        interests[ref] = interest
    }

    /** [ref]'s declared interest, or [civictech.cell.replication.Interest.Total] when unset. */
    fun interestOf(ref: CellRef): civictech.cell.replication.Interest =
        interests[ref] ?: civictech.cell.replication.Interest.Total

    /**
     * Fire after a *local* publish — the announcement seam (M5.4; multicast
     * since M7.2 so a registry can peer with several remotes). Remote
     * publishes never re-announce, so mirrored registries cannot loop.
     */
    private val onLocalPublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()

    /** Fire after *any* publish (local or remote) — the replica-discovery seam (M7.2, spec 42). */
    private val onPublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()

    /** Fire after a *local* unpublish — the eviction-announcement seam (spec 42, G-45). */
    private val onLocalUnpublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()

    /** Fire after *any* unpublish (local or mirrored) — lets a linker (`Replication`) drop stale gossip links. */
    private val onUnpublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()
    private val onLocalLink = java.util.concurrent.CopyOnWriteArrayList<(TopologyLink) -> Unit>()
    private val onLocalUnlink = java.util.concurrent.CopyOnWriteArrayList<(java.util.UUID) -> Unit>()
    private val localLinkIds = ConcurrentHashMap.newKeySet<java.util.UUID>()

    /** Returns a deregistration handle — reconnecting transports replace their announcement hook (M10.3). */
    fun onLocalPublish(listener: (CellRef) -> Unit): AutoCloseable {
        onLocalPublish += listener
        return AutoCloseable { onLocalPublish -= listener }
    }

    fun onPublish(listener: (CellRef) -> Unit) {
        onPublish += listener
    }

    /** Returns a deregistration handle — mirrors [onLocalPublish]'s reconnect contract. */
    fun onLocalUnpublish(listener: (CellRef) -> Unit): AutoCloseable {
        onLocalUnpublish += listener
        return AutoCloseable { onLocalUnpublish -= listener }
    }

    fun onUnpublish(listener: (CellRef) -> Unit) {
        onUnpublish += listener
    }

    fun onLocalTopology(linked: (TopologyLink) -> Unit, unlinked: (java.util.UUID) -> Unit): AutoCloseable {
        onLocalLink += linked
        onLocalUnlink += unlinked
        return AutoCloseable { onLocalLink -= linked; onLocalUnlink -= unlinked }
    }

    fun localLinks(): Set<TopologyLink> = topology.all().filterTo(mutableSetOf()) { it.id in localLinkIds }

    internal fun link(link: TopologyLink) {
        localLinkIds += link.id
        topology.linked(link)
        onLocalLink.forEach { notifyLink(it, link) }
    }

    internal fun unlink(id: java.util.UUID) {
        localLinkIds -= id
        topology.unlinked(id)
        onLocalUnlink.forEach { listener -> runCatching { listener(id) } }
    }

    /** Announcement-fed remote edge; deliberately does not re-announce. */
    fun mirrorLink(link: TopologyLink) = topology.linked(link)
    fun mirrorUnlink(id: java.util.UUID) = topology.unlinked(id)

    private fun notifyLink(listener: (TopologyLink) -> Unit, link: TopologyLink) {
        try { listener(link) } catch (e: Exception) {
            System.err.println("[LocationRegistry] topology hook failed for ${link.id}: $e")
        }
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
            // Register after parking as well as on the failed offer. If the
            // target crossed low-water between those operations, runNow
            // replays the newly parked tail instead of stranding it.
            val expected = locations[invocation.cellRef]
            (expected as? Local)?.host?.onIntakeAvailable { replay(invocation.cellRef, expected) }
        }
    }

    private fun send(location: Location?, invocation: HostedPortInvocation): Boolean = when (location) {
        is Local -> try {
            location.host.enqueueHostedInvocation(invocation)
            true
        } catch (_: IntakeSaturatedException) {
            location.host.onIntakeAvailable { replay(invocation.cellRef, location) }
            false
        } catch (_: IntakeClosedException) {
            false
        }

        is Remote -> try {
            location.sink.deliver(invocation)
            true
        } catch (_: IntakeClosedException) {
            // the transport noticed the peer is gone before the close event
            // landed (M10.4): same contract as a closed local intake — park,
            // never drop; the sink has unpublished itself
            false
        }

        null -> false
    }

    private fun replay(ref: CellRef, expected: Location) {
        val queue = parked[ref] ?: return
        synchronized(queue) {
            if (locations[ref] != expected) return
            while (queue.isNotEmpty()) {
                if (!send(expected, queue.first())) return
                queue.removeAt(0)
            }
        }
    }

    /**
     * Make [host] the location of [ref]. Parked invocations replay — in park
     * order — before the fast path sees the new location, preserving the
     * accepted-then-parked-then-new total order per link (spec 33).
     */
    fun publish(ref: CellRef, host: ManagedHost) {
        install(ref, Local(host))
        onLocalPublish.forEach { notify(it, ref) }
        onPublish.forEach { notify(it, ref) }
    }

    /** Make [ref] remote, reachable through [sink] (a bridge egress, spec 41). */
    fun publish(ref: CellRef, sink: InvocationSink) {
        install(ref, Remote(sink))
        onPublish.forEach { notify(it, ref) }
    }

    /**
     * Hooks are notifications, not participants (M10.4): a failing announcer
     * (e.g. a dying transport not yet closed) must not break the publish —
     * or the spawn that triggered it. The peer that mattered is gone; its
     * re-hello does a full localRefs catch-up anyway.
     */
    private fun notify(listener: (CellRef) -> Unit, ref: CellRef) {
        try {
            listener(ref)
        } catch (e: Exception) {
            System.err.println("[LocationRegistry] publish hook failed for $ref: $e")
        }
    }

    private fun install(ref: CellRef, location: Location) {
        val queue = parked.computeIfAbsent(ref) { mutableListOf() }
        synchronized(queue) {
            queue.forEach { check(send(location, it)) { "replay into fresh location failed for $ref" } }
            queue.clear()
            locations[ref] = location
        }
    }

    /**
     * Remove [ref]'s location; subsequent deliveries park until the next
     * [publish]. Always called on the ref's own host (a despawn/migrate is a
     * local event), so a removed [Local] location also announces — the
     * eviction-gate seam (spec 42, G-45's "peers' linkers reconcile"): a
     * peer's `RegistryMirrorCell` relays this onward so remote registries
     * drop their stale mirror too ([mirrorUnpublish]).
     */
    fun unpublish(ref: CellRef) {
        val wasLocal = locations[ref] is Local
        locations.remove(ref)
        if (wasLocal) onLocalUnpublish.forEach { notify(it, ref) }
        onUnpublish.forEach { notify(it, ref) }
    }

    /** Announcement-fed remote unpublish; deliberately does not re-announce (mirrors [mirrorLink]). */
    fun mirrorUnpublish(ref: CellRef) {
        locations.remove(ref)
        onUnpublish.forEach { notify(it, ref) }
    }

    /**
     * Drop every [Remote] location routed through [via] — the transport's
     * disconnect hook (M5.5): senders park until the peer re-announces.
     */
    fun unpublishRemotes(via: InvocationSink) {
        locations.entries.removeIf { (it.value as? Remote)?.sink === via }
    }
}
