package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.link.PeerId
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.control.ParkQueue
import java.lang.ref.WeakReference
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

    private val topology = TopologyIndex()

    sealed interface Location
    data class Local(val host: ManagedHost) : Location

    /**
     * A ref a peer announced, reachable through [sink] (in practice a bridge
     * egress, spec 41).
     *
     * [peer] is the announcing connection's transport identity (V4-PEERID) —
     * the name the peer put in its transport hello, captured by the
     * `RegistryMirrorCell` that served the announcement. Null when the peer is
     * anonymous, which is every peering that never names a `Peering.Side`, so
     * omitting it is exactly the pre-V4-PEERID shape.
     *
     * It exists because [sink] is per-*connection*, not per-peer: a reconnect
     * builds a new bridge egress, so anything identifying a peer by its sink
     * renames it on every reconnect. [peer] is the peer's own claim and
     * survives. It is **transport-vouched, not authenticated** ([PeerId]) —
     * a stable label, never a verified principal.
     */
    data class Remote(val sink: InvocationSink, val peer: PeerId? = null) : Location

    private val locations = ConcurrentHashMap<CellRef, Location>()
    private val parked = ConcurrentHashMap<CellRef, ParkQueue<HostedPortInvocation>>()

    /**
     * Instances-by-logical-id index (PN-7 perf cliff): the interest-scoped
     * settlement read ([civictech.cell.replication.Replication.replicaFrontier])
     * calls [instancesOf] once per buffered wave per `recheck`, so a linear scan
     * of every published ref would be quadratic in a large mesh. This index keeps
     * the membership read O(instances-of-one-id). Maintained in lockstep with
     * [locations] on every install/removal.
     */
    private val byLogicalId = ConcurrentHashMap<java.util.UUID, MutableSet<CellRef>>()

    private fun indexAdd(ref: CellRef) {
        byLogicalId.computeIfAbsent(ref.id) { ConcurrentHashMap.newKeySet() }.add(ref)
    }

    private fun indexRemove(ref: CellRef) {
        byLogicalId[ref.id]?.let { set -> set.remove(ref); if (set.isEmpty()) byLogicalId.remove(ref.id, set) }
    }

    /**
     * Per-instance [civictech.cell.link.Interest] (spec 40/42
     * §Interest-scoped instance sets, CP-D2): the demand predicate the gossip
     * linker consults to decide whether a link forms and to filter each
     * emission to the target's interest. Unset ⇒ total interest — every
     * instance wants every delta, so the linker's behavior is byte-identical
     * to pre-interest gossip (the replication default).
     */
    private val interests = ConcurrentHashMap<CellRef, civictech.cell.link.Interest>()

    /** Declare [ref]'s interest (the interest-assignment table entry, CP-D2/CP-D3). */
    fun setInterest(ref: CellRef, interest: civictech.cell.link.Interest) {
        interests[ref] = interest
    }

    /** [ref]'s declared interest, or [civictech.cell.link.Interest.Total] when unset. */
    fun interestOf(ref: CellRef): civictech.cell.link.Interest =
        interests[ref] ?: civictech.cell.link.Interest.Total

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

    /**
     * Fire after *any* unpublish — local, mirrored ([mirrorUnpublish]) or a whole
     * peer's worth at once ([unpublishRemotes], T21). Lets a linker
     * (`Replication`) drop stale gossip links, and lets an out-of-kernel observer
     * learn that a peer's cells are gone from an event rather than from a poll.
     */
    private val onUnpublish = java.util.concurrent.CopyOnWriteArrayList<(CellRef) -> Unit>()
    private val onLocalLink = java.util.concurrent.CopyOnWriteArrayList<(TopologyLink) -> Unit>()
    private val onLocalUnlink = java.util.concurrent.CopyOnWriteArrayList<(java.util.UUID) -> Unit>()

    /**
     * Fire after *any* topology mutation — a local [link]/[unlink] or an
     * announcement-fed [mirrorLink]/[mirrorUnlink] (T21). The topology
     * counterpart of the [onPublish]/[onUnpublish] any-scope pair: a peer's
     * edges are registry state that subscribers care about, and the no-loop
     * guarantee mirroring rests on is that [mirrorLink] never re-announces
     * *onward* — not that nothing in this process may observe it.
     */
    private val onLink = java.util.concurrent.CopyOnWriteArrayList<(TopologyLink) -> Unit>()
    private val onUnlink = java.util.concurrent.CopyOnWriteArrayList<(java.util.UUID) -> Unit>()
    private val localLinkIds = ConcurrentHashMap.newKeySet<java.util.UUID>()

    /** Returns a deregistration handle — reconnecting transports replace their announcement hook (M10.3). */
    fun onLocalPublish(listener: (CellRef) -> Unit): AutoCloseable {
        onLocalPublish += listener
        return AutoCloseable { onLocalPublish -= listener }
    }

    /**
     * Returns a deregistration handle, exactly like the three `onLocal…` hooks
     * (T21). Before that symmetry existed an any-scope subscriber could never
     * detach and had to disarm itself with a flag instead; every caller that
     * ignores the handle keeps behaving as it did.
     */
    fun onPublish(listener: (CellRef) -> Unit): AutoCloseable {
        onPublish += listener
        return AutoCloseable { onPublish -= listener }
    }

    /** Returns a deregistration handle — mirrors [onLocalPublish]'s reconnect contract. */
    fun onLocalUnpublish(listener: (CellRef) -> Unit): AutoCloseable {
        onLocalUnpublish += listener
        return AutoCloseable { onLocalUnpublish -= listener }
    }

    /** Returns a deregistration handle — mirrors [onPublish]'s detachment contract (T21). */
    fun onUnpublish(listener: (CellRef) -> Unit): AutoCloseable {
        onUnpublish += listener
        return AutoCloseable { onUnpublish -= listener }
    }

    fun onLocalTopology(linked: (TopologyLink) -> Unit, unlinked: (java.util.UUID) -> Unit): AutoCloseable {
        onLocalLink += linked
        onLocalUnlink += unlinked
        return AutoCloseable { onLocalLink -= linked; onLocalUnlink -= unlinked }
    }

    /**
     * Subscribe to *every* topology mutation this registry records — local and
     * mirrored alike (T21). [onLocalTopology] is the announce-outward seam (a
     * peer must only ever hear about local edges, or mirroring would loop);
     * this is the observe-in-process seam.
     */
    fun onTopology(linked: (TopologyLink) -> Unit, unlinked: (java.util.UUID) -> Unit): AutoCloseable {
        onLink += linked
        onUnlink += unlinked
        return AutoCloseable { onLink -= linked; onUnlink -= unlinked }
    }

    fun localLinks(): Set<TopologyLink> = topology.all().filterTo(mutableSetOf()) { it.id in localLinkIds }

    /**
     * Was [id] admitted by this registry's own [link] (as opposed to mirrored in
     * from a peer)? The O(1) companion to [localLinks], for a consumer that
     * holds one edge id and needs its scope — scanning [localLinks] to answer
     * that is O(E) per question.
     */
    fun isLocalLink(id: java.util.UUID): Boolean = id in localLinkIds

    /** Every inbound or outbound link incident on the full [ref] (read-only [TopologyIndex] projection, T03). */
    fun swapSet(ref: CellRef): Set<TopologyLink> = topology.swapSet(ref)

    /** Would a `from -> to` edge close a cycle already visible in this index? (read-only [TopologyIndex] projection, T03). */
    fun wouldCloseCycle(from: CellRef, to: CellRef): Boolean = topology.wouldCloseCycle(from, to)

    /** Every live topology edge, local and mirrored (read-only [TopologyIndex] projection, T03). */
    fun all(): Set<TopologyLink> = topology.all()

    internal fun link(link: TopologyLink) {
        localLinkIds += link.id
        topology.linked(link)
        onLocalLink.forEach { notifyLink(it, link) }
        onLink.forEach { notifyLink(it, link) }
    }

    internal fun unlink(id: java.util.UUID) {
        localLinkIds -= id
        topology.unlinked(id)
        onLocalUnlink.forEach { listener -> runCatching { listener(id) } }
        onUnlink.forEach { listener -> runCatching { listener(id) } }
    }

    /**
     * Announcement-fed remote edge; deliberately does not re-announce *onward*
     * — that is what keeps mirrored registries from looping — but it does
     * notify this process's any-scope [onTopology] subscribers (T21), so an
     * observer of a peer's edges no longer has to poll [all] for them.
     */
    internal fun mirrorLink(link: TopologyLink) {
        topology.linked(link)
        onLink.forEach { notifyLink(it, link) }
    }

    /** Announcement-fed remote unlink; notifies [onTopology] only (mirrors [mirrorLink]). */
    internal fun mirrorUnlink(id: java.util.UUID) {
        topology.unlinked(id)
        onUnlink.forEach { listener -> runCatching { listener(id) } }
    }

    private fun notifyLink(listener: (TopologyLink) -> Unit, link: TopologyLink) {
        try { listener(link) } catch (e: Exception) {
            System.err.println("[LocationRegistry] topology hook failed for ${link.id}: $e")
        }
    }

    /**
     * Every published instance (ref) sharing [logicalId] — local and remote (spec
     * 42). Served off the [byLogicalId] index (PN-7): O(instances-of-one-id), not
     * a full scan of every published ref.
     */
    fun instancesOf(logicalId: java.util.UUID): Set<CellRef> =
        byLogicalId[logicalId]?.toSet() ?: emptySet()

    /** Every published ref sharing [logicalId] — replicas, local and remote (spec 42). */
    fun replicasOf(logicalId: java.util.UUID): Set<CellRef> = instancesOf(logicalId)

    /**
     * What a locally published ref *is*: the concrete [Cell] class captured at
     * [publish] time (M0 inspector seam). Held [WeakReference]ly so the table
     * pins nothing — the entry is dropped on [unpublish] anyway, this only
     * makes the retention explicit.
     */
    private val descriptions = ConcurrentHashMap<CellRef, WeakReference<Class<out Cell>>>()

    /**
     * The concrete cell class published under [ref], or null when this registry
     * never saw that publish. Registry-less hosts (`ManagedHost(registry = null)`)
     * publish nowhere and are therefore invisible here — as they are to
     * [localRefs] and [locate].
     *
     * The one metadata accessor the topology inspector needs: descriptors
     * (color, ports, manifests) are then read with
     * `ContractRegistry.cellDescriptor(cls)`, the sanctioned lookup — the class
     * is captured on the rare publish path rather than recovered reflectively
     * at read time.
     */
    fun describe(ref: CellRef): Class<out Cell>? = descriptions[ref]?.get()

    /** The host currently serving [ref] on this registry, if local. */
    fun locate(ref: CellRef): ManagedHost? = (locations[ref] as? Local)?.host

    fun location(ref: CellRef): Location? = locations[ref]

    /** Refs currently published as [Local] — the initial-sync set for a new peer. */
    fun localRefs(): Set<CellRef> =
        locations.entries.filter { it.value is Local }.mapTo(mutableSetOf()) { it.key }

    /**
     * Refs currently published as [Remote] — every peer-announced location this
     * registry holds (T21). The catch-up counterpart of [localRefs] for an
     * observer constructed *after* the announcements it needs: the [onPublish]
     * hook names a remote ref exactly once, when it arrives, so anything built
     * later could previously only rediscover one that happens to be a link
     * endpoint or a replica named by [instancesOf].
     */
    fun remoteRefs(): Set<CellRef> =
        locations.entries.filter { it.value is Remote }.mapTo(mutableSetOf()) { it.key }

    /** Parked invocations awaiting a [publish] for [ref] (test/introspection surface). */
    fun parkedFor(ref: CellRef): List<HostedPortInvocation> =
        parked[ref]?.let { synchronized(it) { it.snapshot() } } ?: emptyList()

    /**
     * Optimistic send with lazy re-resolution: enqueue on the located host or
     * hand to the remote sink; on closed intake or no location, park in order.
     * Never blocks the sender, never drops (O(rare-event) cost lands here,
     * not on the fast path).
     */
    fun deliver(invocation: HostedPortInvocation) {
        if (invocation.cellRef !in held && send(locations[invocation.cellRef], invocation)) return
        val queue = parked.computeIfAbsent(invocation.cellRef) { ParkQueue() }
        synchronized(queue) {
            // re-check under the per-ref lock so a concurrent publish can't strand this invocation
            if (invocation.cellRef !in held && send(locations[invocation.cellRef], invocation)) return
            queue.park(invocation)
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
        if (ref in held) return // parked deliberately for the flip window — [release] drains it
        val queue = parked[ref] ?: return
        synchronized(queue) {
            if (locations[ref] != expected) return
            queue.drainWhile { send(expected, it) }
        }
    }

    /**
     * Refs whose delivery is deliberately parked for a repartition flip window
     * (spec 20/24 §Partitioned state "park the flip window", 40/42, CP-D4):
     * per-ref, so a held key range confines its parking to itself — every other
     * range flows unblocked (the funnel rule, 93 I-19). This reuses the ordinary
     * park/replay path, not a second buffer.
     */
    private val held = ConcurrentHashMap.newKeySet<CellRef>()

    /** Park [ref]'s deliveries (per-ref, funnel rule) until [release] — the flip-window buffer. */
    fun hold(ref: CellRef) {
        held += ref
    }

    /**
     * Is [ref] currently held (mid-migration)? Read-only view of the same
     * flip-window set (spec 20/24 §Partitioned state, PN-5): a scatter-gather
     * pull leg to a migrating shard defers rather than reading torn state — the
     * consumer's per-shard `since` makes the deferred leg's later pull fresh.
     */
    fun isHeld(ref: CellRef): Boolean = ref in held

    /** Stop holding [ref] and drain everything parked during the window, in park order. */
    fun release(ref: CellRef) {
        held -= ref
        locations[ref]?.let { replay(ref, it) }
    }

    /**
     * Make [host] the location of [ref]. Parked invocations replay — in park
     * order — before the fast path sees the new location, preserving the
     * accepted-then-parked-then-new total order per link (spec 33). T05
     * finding 1: a refused head (e.g. the freshly published host is itself
     * already saturated) now stays parked, in order, rather than being
     * destroyed — the ordinary [onIntakeAvailable]-driven [replay] finishes
     * the batch once intake reopens.
     *
     * [cell] is the instance being published, when the caller has it (the spawn
     * path does): its class is recorded for [describe]. Omitting it leaves any
     * previously captured class in place, so a re-publish that only moves a ref
     * (`resumeHost`) never blinds [describe].
     */
    fun publish(ref: CellRef, host: ManagedHost, cell: Cell? = null) {
        if (cell != null) descriptions[ref] = WeakReference(cell.javaClass)
        install(ref, Local(host))
        onLocalPublish.forEach { notify(it, ref) }
        onPublish.forEach { notify(it, ref) }
    }

    /**
     * Make [ref] remote, reachable through [sink] (a bridge egress, spec 41).
     *
     * [peer] is the announcing connection's transport identity, when the
     * caller has it (the peering path does — the mirror cell that serves an
     * announcement is per-connection, so it knows whose announcement this is;
     * V4-PEERID). Omitting it publishes an anonymous remote location — exactly
     * what every caller got before this parameter existed, and what an unnamed
     * `Peering.Side` still gets. It is recorded, never consulted by routing:
     * [deliver] resolves through [Remote.sink] alone, as before.
     */
    fun publish(ref: CellRef, sink: InvocationSink, peer: PeerId? = null) {
        install(ref, Remote(sink, peer))
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

    /**
     * T05 finding 1 (critical): `queue.drain()` used to snapshot-and-clear
     * the whole park batch before anything was sent; the first refusal
     * (SATURATED/CLOSED intake) then tripped `check(...)`, throwing — and
     * the already-cleared batch (the refused head, every ordered successor,
     * `Owned`/`Leased` included) was simply gone: not re-parked, not
     * dead-lettered, not counted, and [locations] never got updated so the
     * ref stayed unpublished with its history destroyed.
     * [ParkQueue.drainWhile] exists precisely for this: it stops at the
     * first refusal and RETAINS the remainder, in order, in the same queue.
     *
     * [locations] is still assigned only AFTER the drain attempt, same as
     * before this fix — publishing it first was considered (and matches the
     * ticket's literal text) but measurably breaks per-ref FIFO: a
     * concurrent `deliver()` for this ref would see the new location on its
     * lock-free fast path and could race ahead of a not-yet-drained parked
     * remainder still sitting behind this method's lock
     * (`RelocationTest`'s `concurrent relocation is exactly-once under real
     * threads` catches exactly this — it started failing under the
     * publish-first ordering). Draining first keeps every concurrent
     * `deliver()` funneled through the SAME `synchronized(queue)` this
     * method holds (its fast path fails — [locations] isn't updated yet —
     * so it falls through and blocks on the lock), preserving order. A
     * refused head that stops `drainWhile` early still parks correctly:
     * [send]'s SATURATED branch registers an `onIntakeAvailable` hook
     * bound to `location` (not read back from [locations]), and by the time
     * that hook fires later, [locations] has long since been assigned here
     * — so [replay]'s `locations[ref] == expected` check still passes.
     *
     * That last sentence holds for the *queued* hook, but not for [send]'s
     * `runNow` path (review addendum): [ManagedHost.onIntakeAvailable] fires
     * the listener **synchronously on this thread** when the host is no
     * longer SATURATED by the time the hook goes in — i.e. exactly when the
     * host crossed low-water in the window between throwing
     * `IntakeSaturatedException` and the registration. That immediate
     * [replay] re-enters this monitor reentrantly and bails on
     * `locations[ref] != expected`, since [locations] is not assigned yet —
     * so nothing re-drives the retained remainder and it strands until the
     * next [deliver]/[publish] for this ref. [deliver] already covers the
     * same window by re-registering the hook *after* it parks (see its own
     * comment); this is the missing analogue: re-register once [locations]
     * is assigned, so the immediate and the deferred wake-up alike find
     * [replay]'s `locations[ref] == expected` guard satisfied.
     */
    private fun install(ref: CellRef, location: Location) {
        val queue = parked.computeIfAbsent(ref) { ParkQueue() }
        synchronized(queue) {
            queue.drainWhile { send(location, it) }
            locations[ref] = location
            indexAdd(ref)
            if (!queue.isEmpty()) (location as? Local)?.host?.onIntakeAvailable { replay(ref, location) }
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
        indexRemove(ref)
        descriptions.remove(ref)
        if (wasLocal) onLocalUnpublish.forEach { notify(it, ref) }
        onUnpublish.forEach { notify(it, ref) }
    }

    /** Announcement-fed remote unpublish; deliberately does not re-announce (mirrors [mirrorLink]). */
    fun mirrorUnpublish(ref: CellRef) {
        locations.remove(ref)
        indexRemove(ref)
        descriptions.remove(ref)
        onUnpublish.forEach { notify(it, ref) }
    }

    /**
     * Drop every [Remote] location routed through [via] — the transport's
     * disconnect hook (M5.5): senders park until the peer re-announces.
     *
     * T21: this is an unpublish like any other, so it notifies [onUnpublish] —
     * the same hook [unpublish] and [mirrorUnpublish] fire.
     *
     * Notification happens after the whole batch has left [locations], never per
     * removal inside the scan, and that ordering is load-bearing: this method is
     * the transport's *send-failure* path as much as its close path (`WsTransport`
     * calls it from inside an outlet propagate), and a listener is free to send
     * on the wire itself — a send that can fail and re-enter here. Draining the
     * scan first means the re-entrant call finds nothing of [via] left and
     * terminates, instead of recursing through a half-emptied map. The batch is
     * also what makes a listener's own re-read of this registry answer about a
     * fully disconnected peer rather than an arbitrary prefix of one.
     */
    fun unpublishRemotes(via: InvocationSink) {
        val dropped = mutableListOf<CellRef>()
        locations.entries.removeIf { entry ->
            ((entry.value as? Remote)?.sink === via).also {
                if (it) {
                    indexRemove(entry.key)
                    dropped += entry.key
                }
            }
        }
        dropped.forEach { ref -> onUnpublish.forEach { notify(it, ref) } }
    }
}
