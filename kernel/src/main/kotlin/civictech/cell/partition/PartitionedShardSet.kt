package civictech.cell.partition

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import civictech.cell.port.Use
import civictech.cell.proxy.InvocationSink
import civictech.cell.control.ParkQueue
import civictech.cell.replication.Assignment
import civictech.cell.link.Interest
import civictech.cell.link.sliceTo
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import java.util.UUID

/**
 * The distributed placement of a [PartitionedCell] over an **instance set**
 * (spec 40/42 §Interest-scoped instance sets, 20/24 §Partitioned state, CP-D3):
 * shards are interest-scoped [ShardCell] instances of one logical id, each
 * hosted on a real [civictech.cell.host.ManagedHost] (possibly behind a bridge)
 * and reached over [registry]. The single-host, in-process [PartitionedCell] is
 * the degenerate placement of this same model; here the shards are genuinely
 * distributed and fed over the wire.
 *
 * The router **is** the disjoint-interest linker: for each incoming element it
 * consults the routing table — the per-shard [Interest] assignment — and routes
 * the element's slice to exactly the shard whose interest admits its group key,
 * using the same `Scoped.within` filter the gossip linker uses (CP-D2). Each
 * routed slice carries [routingEpoch], the versioned interest-assignment epoch,
 * which crosses the wire (additive `WireFrame.routingEpoch`). Because ranges are
 * disjoint, the scatter-gather union of the shards' key ranges is the coherent
 * cross-partition board with the merge function never exercised.
 *
 * [repartition] is interest reassignment, not a bespoke protocol: bump the
 * epoch, reassign each shard's [Interest] (each shard sheds the range it lost),
 * and replay the live state-as-delta into the new owners over the ordinary
 * routing path — the same machinery a re-announce drives. The [ledger] is the
 * router's own total-interest view (the sharded-replication setting, 42), kept
 * so the replay is a local state-as-delta rather than a scatter of shard-to-
 * shard pulls.
 */
class PartitionedShardSet<E>(
    private val totalSlots: Int,
    private val keyFn: (E) -> Any?,
    private val registry: LocationRegistry,
    /**
     * How a repartition applies an interest reassignment to a shard (PN-6, the
     * ticket's payoff). `true` ⇒ the assignment rides a **journaled, ref-addressed
     * hosted invocation** to the shard's [ShardCell.assignInlet] over [registry]
     * (in-process or across a bridge) — so it lands in the shard's WAL and a
     * non-checkpointed shard replays the shed on recovery (PN-4's shed is now
     * journaled-durable). `false` (the default, and control a) ⇒ a plain
     * in-process `ShardCell.assign` call — unjournaled, so a crash+replay
     * resurrects the shed range. The four PN-4/5 partition pin tests run the
     * default (direct) path unchanged.
     */
    private val journaledAssign: Boolean = false,
) {

    /** The router's proxy view of one shard: the wire-crossing data plane plus the local control handle. */
    private class Shard<E>(
        val cell: ShardCell<E>,
        var interest: Interest,
        val route: Propagate<RoutedCommand<E>>,
        val assign: Propagate<Assignment>,
    )

    /** Registry proxy shape for a shard's [ShardCell.routeInlet] (resolved by port name, in-process or bridged). */
    interface ShardRoute<E> {
        val routeInlet: Use<Propagate<RoutedCommand<E>>>
    }

    /** Registry proxy shape for a shard's [ShardCell.assignInlet] — the journaled control-plane channel (PN-6). */
    interface ShardAssign {
        val assignInlet: Use<Propagate<Assignment>>
    }

    private val shards = mutableListOf<Shard<E>>()

    /**
     * The router's repartition-replay source (PN-6, *scoped* — plan §3 "scope or
     * delete ledger"). The **routing** path ([route]) and the **pull** path
     * ([pull]) are O(instances): routing consults only the per-instance interest
     * table ([shards]); pull fans a `StateRequest` to each shard (PN-5). The
     * ledger is retained solely because a repartition replay must be *complete and
     * synchronous* even while routed slices are still in flight across a bridge to
     * a shard — a snapshot gathered from the shards' own (async-lagging) contents
     * would miss in-flight elements and lose them at the flip. It is written on
     * the write path but read by no correctness path other than the flip replay
     * (and the [pullFromLedger] control b). The fully leaderless, watermark-gated,
     * mesh-sourced replay that would let this go entirely is R1 (out of scope —
     * see spec §Interest-scoped instance sets, "single routing authority").
     */
    private var ledger = TagState<E>()

    /** Synthetic instance id for a ledger-served pull reply (the control-b answerer). */
    private val ledgerRef = CellRef(UUID.randomUUID())

    /** The versioned interest-assignment epoch (spec 20/24 "flip the table atomically and bump its epoch"). */
    var routingEpoch: Long = 0L
        private set

    // Repartition flip window (spec 20/24 §Partitioned state "per-range
    // Buffering", 93 I-19, CP-D4): while a flip is open, commands touching a
    // moving key range are buffered — parked at the router — and replayed once,
    // to the new owner, after the flip closes. Non-moving ranges flow untouched
    // (the funnel never blocks). [flipBuffered] false is the CP-D4 control.
    private var flipping = false
    private var flipBuffered = true
    private val flipBuffer = ParkQueue<SetDelta<E>>()
    private var movingInterest: Interest? = null

    val shardCount: Int get() = shards.size

    /**
     * Register a shard already spawned+published on its host (reachable via
     * [registry], in-process or over a bridge). The router opens a wire-crossing
     * data-plane proxy to its [ShardCell.routeInlet] and records the shard's
     * [Interest] in the routing table.
     */
    @Suppress("UNCHECKED_CAST")
    fun addShard(cell: ShardCell<E>, interest: Interest) {
        val route = (HostedCellProxy.create(cell.ref, registry, ShardRoute::class.java) as ShardRoute<E>).routeInlet.call
        // The journaled control-plane proxy (PN-6): assignment rides the registry
        // to the shard's assignInlet exactly as routed slices ride to routeInlet —
        // in-process or over a bridge — so it lands in the shard host's WAL.
        val assign = (HostedCellProxy.create(cell.ref, registry, ShardAssign::class.java) as ShardAssign).assignInlet.call
        shards += Shard(cell, interest, route, assign)
        registry.instances.setInterest(cell.ref, interest)
    }

    /**
     * Apply an interest reassignment to a shard (PN-6). Journaled path: a
     * ref-addressed hosted invocation to the shard's [ShardCell.assignInlet] over
     * the registry — durable and replayed on recovery. Direct path (control a):
     * an in-process `assign` call — unjournaled, so the shed resurrects on replay.
     */
    private fun assignShard(shard: Shard<E>, interest: Interest, epoch: Long) {
        if (journaledAssign) shard.assign.propagate(Assignment(interest, epoch))
        else shard.cell.assign(interest, epoch)
    }

    /** Route [delta] to the owning shards (each slice filtered to that shard's interest), stamped with the epoch. */
    fun route(delta: SetDelta<E>) {
        ledger.apply(delta)
        val moving = movingInterest
        if (flipping && flipBuffered && moving != null) {
            // Per-range Buffering (spec 20/24, CP-D4): a command touching the
            // moving range is parked at the router (delivered once, to the new
            // owner, when the flip closes); the non-moving remainder flows now.
            val movingPart = delta.within(moving) { keyFn(it as E) }
            if (movingPart != null) flipBuffer.park(movingPart)
            val stablePart = delta.within(complement(moving)) { keyFn(it as E) }
            if (stablePart != null) emit(stablePart, routingEpoch)
            return
        }
        emit(delta, routingEpoch)
    }

    private fun emit(delta: SetDelta<E>, epoch: Long) {
        shards.forEach { shard ->
            // the one slice-and-route primitive (PN-6) — the same [sliceTo] the
            // gossip linker uses, here with the group keyOf.
            val slice = sliceTo(delta, shard.interest) { keyFn(it as E) } ?: return@forEach
            shard.route.propagate(RoutedCommand(epoch, slice))
        }
    }

    /**
     * Repartition = interest reassignment (spec 42, 20/24 §Partitioned state,
     * CP-D3): bump the epoch, reassign each shard's [Interest] (each shard sheds
     * the range it lost), then replay the live state-as-delta into the new
     * owners over the routing path. Under the epoch guard this loses nothing and
     * double-counts nothing; the epoch-blind control skips the shed and forks
     * moved groups across two shards.
     */
    fun repartition(newInterests: List<Interest>) {
        require(newInterests.size == shards.size) { "expected ${shards.size} interests, got ${newInterests.size}" }
        routingEpoch++
        shards.forEachIndexed { i, shard ->
            shard.interest = newInterests[i]
            registry.instances.setInterest(shard.cell.ref, newInterests[i])
            assignShard(shard, newInterests[i], routingEpoch)
        }
        emit(ledger.asDelta(), routingEpoch)
    }

    /**
     * Open a repartition flip window under concurrent placement (spec 20/24
     * §Partitioned state, 93 I-19, CP-D4). The moving key range — every key
     * whose owning shard changes between the current and [newInterests]
     * assignment — is set to Buffering ([route] parks commands touching it while
     * the window is open); the live state-as-delta of the moving range is
     * replayed into its new owners now (parking per-ref if a shard is migrating,
     * so the funnel never blocks other ranges). Each gaining shard adopts a
     * transient union of its old and new interest so it keeps serving its old
     * range and accepts the moved range during the window. Close with
     * [endRepartition].
     *
     * With [buffered] false (the CP-D4 control) the moving range is not buffered:
     * during the window a command for a moving key is routed to both its old and
     * its new owner (their interests transiently overlap) and double-counts.
     */
    fun beginRepartition(newInterests: List<Interest>, buffered: Boolean = true) {
        require(newInterests.size == shards.size) { "expected ${shards.size} interests, got ${newInterests.size}" }
        check(!flipping) { "a repartition flip is already open" }
        routingEpoch++
        flipping = true
        flipBuffered = buffered
        val old = shards.map { it.interest }
        // Moving range = keys whose owning shard changes (β's algebra, PN-4/F6):
        // a key stays put iff the SAME shard admits it in both tables, so the
        // stable set is the union of per-shard old∩new and the moving set its
        // complement — serializable and honest, replacing the anonymous
        // predicate whose `overlaps` unconditionally lied.
        val stable = Interest.Union(newInterests.indices.map { i -> Interest.Intersect(listOf(old[i], newInterests[i])) })
        val moving = Interest.Complement(stable)
        movingInterest = moving
        pendingInterests = newInterests
        // The routing table stays OLD for the whole window (routing continuity —
        // the old owner keeps serving the moving range until the flip closes);
        // only the moving range is set to Buffering. Each new owner's *guard* is
        // widened (old ∪ new) so it accepts the replay of its moved-in range, and
        // that pre-window snapshot is replayed to it now — parking per-ref if the
        // shard is migrating, so the funnel never blocks other ranges.
        val snapshot = ledger.asDelta()
        shards.forEachIndexed { i, shard ->
            assignShard(shard, unionInterest(old[i], newInterests[i]), routingEpoch) // widen guard only; sheds nothing
            val movedIn = snapshot.within(newInterests[i]) { keyFn(it as E) }?.within(moving) { keyFn(it as E) }
            if (movedIn != null) shard.route.propagate(RoutedCommand(routingEpoch, movedIn))
        }
    }

    /**
     * Close the flip window (spec 20/24 §Partitioned state, CP-D4): narrow every
     * shard to its final [Interest] (each old owner sheds the range it lost),
     * then replay the buffered moving-range commands once, in order, to their new
     * owners under the settled table. Zero loss, no double-count.
     */
    fun endRepartition() {
        check(flipping) { "no repartition flip is open" }
        val newInterests = checkNotNull(pendingInterests)
        shards.forEachIndexed { i, shard ->
            shard.interest = newInterests[i]
            registry.instances.setInterest(shard.cell.ref, newInterests[i])
            assignShard(shard, newInterests[i], routingEpoch)
        }
        flipping = false
        movingInterest = null
        pendingInterests = null
        flipBuffer.drain().forEach { emit(it, routingEpoch) }
    }

    private var pendingInterests: List<Interest>? = null

    /**
     * Follow a shard through an in-place **rolling promotion** (FU-3, PN-14
     * shard-by-shard; spec 53 §Replicated promotion). A shard is a ref-addressed
     * [Replicable] instance, so promoting it is the same reuse-ref rebind
     * [civictech.cell.evolve.Promotion.promoteReplica] /
     * [civictech.cell.replication.Replication.rebind] runs for a replica: the
     * [candidate] REUSES the incumbent's [CellRef], which makes the swap
     * crash-recovery-equivalent. Because both the routing data plane ([route]) and
     * the journaled control plane ([assign]) are **ref-keyed** proxies over the
     * registry, they already follow the reused ref to the republished candidate
     * with no rewiring — the write and repartition paths need no change. The one
     * router-held handle that does NOT resolve by ref is the direct [ShardCell]
     * object read by [memberships] (the harness/in-process board read): it still
     * points at the retired incumbent object, frozen at promotion time. This
     * repoints it to [candidate], keeping its (unchanged) [Interest] and its
     * ref-keyed proxies.
     *
     * Ref-keyed by construction: a candidate spawned with a **fresh** CellRef (the
     * FU-3 control) has no matching routing-table entry, so this refuses it —
     * exactly the orphaning that loses that shard's post-promotion writes.
     */
    fun rebindShard(candidate: ShardCell<E>) {
        val i = shards.indexOfFirst { it.cell.ref == candidate.ref }
        require(i >= 0) {
            "rebindShard follows a shard's REUSED CellRef through an in-place promotion; no shard is " +
                "registered for ref ${candidate.ref} (a fresh-ref candidate orphans its routing entry)"
        }
        val old = shards[i]
        shards[i] = Shard(candidate, old.interest, old.route, old.assign)
    }

    /** The scatter-gather board: each shard's live key range (spec: "union of disjoint-key catch-ups"). */
    fun memberships(): List<Set<E>> = shards.map { it.cell.membership() }

    /**
     * Scatter-gather pull (PN-5, spec 20/24 §Partitioned state, 40/42
     * §Interest-scoped instance sets). A pull against a partitioned logical id
     * has no single answerer: the router serving it from its own [ledger] would
     * hold O(total state) at one node — the very thing partitioning exists to
     * avoid ([pullFromLedger] is that control). Instead the router **fans** a
     * `StateRequest` to every shard whose interest overlaps [scope], **over the
     * registry** — the same wire transport the write path uses ([addShard]'s
     * `HostedCellProxy`/`ShardRoute` proxy). Each shard answers with its own
     * existing PN-4 [Protocols.StateRequest] handler
     * (`outlet.baselineTo(replyTo, currentFrontier(scope))`), so a shard behind a
     * bridge is genuinely reached over that bridge and its reply genuinely
     * crosses back to [replyTo] — the router holds no direct object reference to
     * a bridged shard's state on this path (only its [CellRef], to address the
     * wire send, exactly as the write path does). The board is never materialized
     * at one node — each baseline reply carries only its shard's range.
     *
     * The reply is asynchronous (it rides a bridge): this call **fires** the
     * fan-out and returns; the requester at [replyTo] assembles the union from
     * the N per-shard baseline replies as they arrive (each a
     * [PullReply]-shaped `baselineTo` whose [civictech.cell.MessageContext.baseline]
     * is that shard's frontier — a baseline is never a wave, so a live routed
     * delta on the same subscription is not mistaken for a pull leg). The
     * consumer retains the frontier **per instance**
     * ([civictech.cell.protocol.RetainedFrontiers]).
     *
     * Freshness is per-shard-consistent, cross-shard-arbitrary: each leg is a
     * baseline (never a wave), and legs are independent — a shard that is
     * mid-migration ([civictech.cell.host.DeliveryHold.isHeld], the funnel-hold reuse) is skipped
     * here and its leg deferred to a later pull rather than read torn.
     *
     * [sinceOf] supplies the currency to pull each instance from — the consumer's
     * **per-instance** retained frontier ([civictech.cell.protocol.RetainedFrontiers.sinceFor]).
     * Feeding one frontier merged across instances instead silently loses a
     * deferred shard's non-contiguous tags (control a): its counters read as
     * already-seen under a sibling's higher water.
     */
    fun pull(replyTo: PortRef, scope: Interest, sinceOf: (CellRef) -> TagFrontier?) {
        shards.forEach { shard ->
            if (!shard.interest.overlaps(scope)) return@forEach
            if (registry.holds.isHeld(shard.cell.ref)) return@forEach // migrating — its leg defers
            // Fan the StateRequest over the wire (the write path's transport), not
            // a direct object read: replyTo names the requester's inlet, since is
            // this instance's retained currency, and scope rides as a @Polymorphic
            // Interest (FU-1) so a cross-host shard narrows its reply to
            // shardInterest ∩ scope instead of over-fetching its whole slice.
            Protocols.sendUpstream(
                pullEdge(shard.cell.ref, replyTo),
                Protocols.StateRequest,
                StateRequest(replyTo, sinceOf(shard.cell.ref), scope),
            )
        }
    }

    /**
     * A one-shot bridged link carrying a pull `StateRequest` upstream to a shard's
     * outlet (PN-5). Its `protocolBridge` routes the frame to `fromAddr` (the
     * shard outlet) through [registry] — resolving in-process or across a bridge
     * transparently, the same resolution the write proxy uses. The shard's
     * handler replies `baselineTo(replyTo)`, which reaches the requester over the
     * reverse data path its subscription established (never this link), so this
     * link needs no registration and lives only for the one send.
     */
    private fun pullEdge(shardRef: CellRef, replyTo: PortRef): WireEdgeLink =
        WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.of(shardRef, "outlet"),
            to = replyTo,
            fromAddr = PortAddress(shardRef, "outlet"),
            toAddr = PortAddress(replyTo.cell ?: shardRef, "inlet"),
            protocolCapabilities = setOf(Protocols.StateRequest),
            sink = InvocationSink(registry::deliver),
        )

    /**
     * Control (b): answer the whole pull from the router's own total-interest
     * [ledger] in one reply. Functionally green — the assembled union is
     * identical — but it materializes O(total state) at a single node, which is
     * exactly what the fan-out [pull] avoids (a partitioned cell exists so no one
     * node holds the whole board).
     */
    fun pullFromLedger(scope: Interest, since: TagFrontier?): List<PullReply<E>> {
        val admit: (E) -> Boolean =
            if (scope is Interest.Total) { _ -> true } else { e -> scope.admits(keyFn(e)) }
        // control b: answer the whole board from the router's own ledger in one
        // reply — O(total) at one node, exactly what the fan-out [pull] avoids.
        val adds = ledger.elements.filter(admit).associateWith { e ->
            ledger.tags(e).filterTo(mutableSetOf()) { since == null || (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }
        if (adds.isEmpty()) return emptyList()
        val frontier = mutableMapOf<UUID, Long>()
        adds.forEach { (_, tags) -> tags.forEach { t -> frontier.merge(t.sourceId, t.counter, ::maxOf) } }
        return listOf(PullReply(ledgerRef, SetDelta(adds = adds), TagFrontier(frontier)))
    }

    /**
     * Recover the router from its (already-recovered) shards (PN-4). The router
     * holds no durable state of its own — the routing table *is* the shards'
     * interests and the epoch *is* their max-register — so after a crash the
     * table is recomputed by asking each restored shard what interest and epoch
     * it holds. Reading each shard's *current* [ShardCell.interest] (restored
     * from its checkpoint / replayed frames under its recovered interest) is what
     * keeps a shed range shed; recomputing from the constructor `initialInterest`
     * instead resurrects it and double-counts (the PN-4 control).
     */
    fun rebuildFrom(restored: List<ShardCell<E>>) {
        shards.clear()
        ledger = TagState()
        var maxEpoch = 0L
        restored.forEach { cell ->
            addShard(cell, cell.interest)
            ledger.apply(cell.contents())
            maxEpoch = maxOf(maxEpoch, cell.assignedEpoch)
        }
        routingEpoch = maxEpoch
    }

    companion object {
        private fun unionInterest(a: Interest, b: Interest): Interest = Interest.Union(listOf(a, b))

        private fun complement(interest: Interest): Interest = Interest.Complement(interest)
    }
}
