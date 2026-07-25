package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.data.DeliveryTracking
import civictech.cell.data.Propagate
import civictech.cell.data.Replicable
import civictech.cell.data.WatermarkCell
import civictech.cell.data.WatermarkDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanOutlet
import civictech.cell.port.Link
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.proxy.HostedCellProxy
import java.util.*

/**
 * Replica wiring (spec 42, G-7, M7.3). A replica is an instance of the same
 * logical cell — equal `CellRef.id`, distinct `instanceId` (G-8) — on some
 * host, possibly behind another registry. Every peer runs the same local
 * rule: *link each of MY replicas' delta outlets to every other replica's
 * `deltaInlet` I learn about* — so the full gossip mesh emerges symmetrically
 * from ordinary announcements, with no coordinator and no second sync
 * protocol: delta links are ordinary links over routed proxies, late-join
 * catch-up doubles as initial sync, and park/replay + tag idempotence double
 * as anti-entropy after partitions (M7.4).
 *
 * Only cells of the mergeable class replicate — [Replicable] is the contract
 * (session delta 4): the tagged set family (tombstoned OR-set) and the
 * per-source [civictech.cell.data.PnCounterCell]. Plain CounterCell does not
 * qualify (addition is not idempotent — echoes would double-count).
 *
 * ponytail: link wiring calls streamTo on the local outlet directly (same
 * ceiling as the M5.7 streamTo idiom — fine single-threaded/simulated;
 * production wiring wants a host-queue hop).
 */
class Replication(private val registry: LocationRegistry) {

    interface ReplicaDeltaInlet {
        val deltaInlet: Use<Propagate<Any?>>
    }

    private val localReplicas = mutableMapOf<UUID, MutableList<Replicable<*>>>()

    /** The host each local replica was spawned on — needed to suspend/despawn it later (eviction). */
    private val hostOf = mutableMapOf<CellRef, ManagedHost>()

    /**
     * Local replicas parked (spec 33) rather than despawned because no peer
     * was reachable at eviction time (the suspend-when-partitioned gate,
     * G-45). Cleared, and the replica resumed, on the next re-announce that
     * makes a peer of the same logical id visible again (heal).
     */
    private val partitionSuspended = mutableSetOf<CellRef>()

    /** Established gossip links per (local replica → remote replica) pair. */
    private val linked = mutableMapOf<Pair<CellRef, CellRef>, Pair<Replicable<*>, Link>>()

    /**
     * The local delivered-watermark companion per replicated logical id (spec
     * 40/42 §Delivered watermarks, E3.3 point b): one [WatermarkCell] tracks
     * every local replica of that id, and — being itself [Replicable] — gossips
     * over the *same* mesh as the data it tracks, no second protocol.
     */
    private val watermarks = mutableMapOf<UUID, WatermarkCell>()

    /**
     * The local delivered-watermark lattice for [logicalId]: its merged
     * `rows()` are the per-(replica, source) delivered frontier every peer
     * converges to. Null until a replica of [logicalId] is [replicate]d here.
     */
    fun watermarkOf(logicalId: UUID): WatermarkCell? = watermarks[logicalId]

    /**
     * The companion [WatermarkCell] ref for a data replica [dataRef] (spec 40/42
     * §Delivered watermarks, E3.3 point b): derived from the data id and sharing
     * the data replica's `instanceId`, so every peer contributes a distinct,
     * replay-stable slot and the companions of one logical id find each other by
     * [LocationRegistry.replicasOf] exactly as the data replicas do.
     */
    private fun watermarkRef(dataRef: CellRef): CellRef =
        CellRef(UUID.nameUUIDFromBytes("watermark:${dataRef.id}".toByteArray()), dataRef.instanceId)

    /**
     * Cross-track settlement read (spec 20/22 §Completeness — cross-replica
     * extension, E3.4): the JoinBarrier "has every replica-set member delivered
     * origin wave `(source, counter)`" answered off the local merged
     * [WatermarkCell]. Membership is the [LocationRegistry.replicasOf] fold
     * (eventually consistent — the R13 caveat); a member's row is read at its
     * derived [WatermarkCell.slotId]. A `closed` slot (cleanly departed) stops
     * constraining; a member whose row has not yet gossiped in holds the wave
     * (WAIT), never releases it early — the honest R13 boundary.
     */
    fun replicaFrontier(logicalId: UUID): ReplicaFrontier = ReplicaFrontier { source, counter ->
        val companion = watermarks[logicalId]
        val members = registry.replicasOf(logicalId)
        if (companion == null || members.isEmpty()) return@ReplicaFrontier false
        val rows = companion.rows()
        val closed = companion.closed()
        members.all { ref ->
            val slot = WatermarkCell.slotId(watermarkRef(ref))
            slot in closed || (rows[slot]?.get(source) ?: Long.MIN_VALUE) >= counter
        }
    }

    /**
     * Poke [listener] whenever [logicalId]'s merged watermark advances (E3.4):
     * a tap on the local companion's outlet fires on every effective advance —
     * a local delivery OR an absorbed-and-re-emitted peer delta — so a
     * glitch-free consumer gating on [replicaFrontier] re-checks exactly when a
     * held wave might have become replica-complete.
     */
    fun onWatermarkAdvance(logicalId: UUID, listener: () -> Unit) {
        val companion = watermarks[logicalId] ?: return
        companion.outlet.tap(Use.fixed(Propagate<WatermarkDelta> { listener() }, PortRef.generate()))
    }

    init {
        registry.onPublish { ref -> linkOut(ref) }
        // reconcile (spec 42, G-45): a peer's despawn/eviction removes it from
        // replicasOf; drop the now-stale outbound gossip link rather than
        // leaving it targeting a gone ref (no ack protocol — this is purely
        // local bookkeeping, the routed proxy would otherwise just dead-letter)
        registry.onUnpublish { ref -> linked.keys.filter { it.second == ref }.toList().forEach { linked.remove(it) } }
    }

    /**
     * Spawn [cell] on [host] as a replica: gossip links to every currently
     * known replica of its logical id are installed now, and to future ones
     * as their announcements arrive. The caller owns instance-id uniqueness
     * (distinct per replica, minted without coordination).
     */
    fun replicate(cell: Replicable<*>, host: ManagedHost) {
        localReplicas.getOrPut(cell.ref.id) { mutableListOf() } += cell
        hostOf[cell.ref] = host
        host.managementInlet.call.spawn(cell)
        registry.replicasOf(cell.ref.id).forEach { other -> maybeLink(cell, other) }
        trackDeliveries(cell, host)
    }

    /**
     * Delivered-tracking seam (spec 40/42 §Delivered watermarks, E3.3): wire
     * [cell]'s delivery path into a [WatermarkCell] companion for its logical
     * id, itself replicated on [host] so its deltas gossip over the existing
     * mesh (no new protocol). The companion's ref id is derived from the data
     * id (`watermark:{logicalId}`) and it borrows the data replica's
     * `instanceId`, so each peer contributes a distinct, replay-stable row and
     * the companions of one logical id find each other by [replicasOf] exactly
     * as the data replicas do. A [WatermarkCell] is not itself tracked —
     * that would recurse — its own convergence is the ordinary gossip path.
     */
    private fun trackDeliveries(cell: Replicable<*>, host: ManagedHost) {
        if (cell is WatermarkCell) return
        val companion = watermarks.getOrPut(cell.ref.id) {
            WatermarkCell(watermarkRef(cell.ref)).also { replicate(it, host) }
        }
        // Per-origin delivered frontier (E3.3(a), E3.4's read): the fold reports
        // each raised ORIGIN prefix from inside applyRemote / local mints, where
        // the origin tags survive — the substrate E3.4's cross-track read needs.
        if (cell is DeliveryTracking) {
            cell.onDeliver { source, thru -> companion.advance(source, thru) }
        }
        // CP-B2 re-emission tracking (spec 40/42 §Delivered watermarks, E3.3):
        // retained — its per-outlet-epoch watermark is a distinct key space from
        // the per-origin advances above (both ride the one companion, one mesh).
        @Suppress("UNCHECKED_CAST")
        companion.trackDeliveriesOf(cell.outlet as FanOutlet<Propagate<Any?>>)
    }

    /**
     * Evict [cell] from [host] — the decided gated drain+despawn (93 I-3,
     * G-45's gate half; the *trigger* — sustained attention band NONE, 34,
     * or manual — is wiring the economic layer owns, G-62, out of this
     * ticket's scope).
     *
     * **Membership-gated**: [LocationRegistry.replicasOf] already drops a
     * partitioned peer's `Remote` location (42 §Anti-entropy), so "no
     * reachable peer" and "partitioned" are the same local observation. With
     * none reachable this replica may hold unique un-gossiped state nobody
     * else has a copy of — it MUST suspend (park), never despawn, and await
     * heal; the next re-announce that grows `replicasOf` back above one
     * resumes it automatically ([linkOut]).
     *
     * **Drain-gated** otherwise: [civictech.cell.host.HostManagementApi.suspend]
     * first closes this replica's own intake so no further local write races
     * the teardown (spec 33's drain, applied at cell instead of host
     * granularity — every effective delta already streamed to peers as it
     * was produced, so nothing buffered needs an extra flush), a final
     * state-as-delta catch-up re-fires at one reachable peer's existing link
     * (the same M10.1 re-announce hook [maybeLink] uses), then despawn
     * unpublishes the ref — surviving peers' linkers simply stop targeting a
     * ref no longer in `replicasOf` on their next announcement, no ack
     * protocol.
     *
     * Returns `true` if the replica despawned, `false` if it suspended
     * instead (no reachable peer).
     */
    fun evict(cell: Replicable<*>, host: ManagedHost): Boolean {
        val reachablePeers = registry.replicasOf(cell.ref.id) - cell.ref
        if (reachablePeers.isEmpty()) {
            if (partitionSuspended.add(cell.ref)) host.managementInlet.call.suspend(cell.ref)
            return false
        }
        host.managementInlet.call.suspend(cell.ref)
        // final push-catch-up to one reachable peer (best-effort; idempotent either way)
        linked.entries.firstOrNull { it.key.first == cell.ref }?.let { (_, linkedPair) ->
            @Suppress("UNCHECKED_CAST")
            (cell.outlet as FanOutlet<Propagate<Any?>>).linking.onLinked(linkedPair.second)
        }
        host.managementInlet.call.despawn(cell.ref)
        localReplicas[cell.ref.id]?.remove(cell)
        hostOf.remove(cell.ref)
        linked.keys.filter { it.first == cell.ref }.toList().forEach { linked.remove(it) }
        partitionSuspended.remove(cell.ref)
        return true
    }

    private fun linkOut(newRef: CellRef) {
        localReplicas[newRef.id]?.forEach { local ->
            maybeLink(local, newRef)
            // heal (G-45): a newly visible peer un-partitions a locally suspended replica
            if (local.ref in partitionSuspended && (registry.replicasOf(local.ref.id) - local.ref).isNotEmpty()) {
                partitionSuspended -= local.ref
                hostOf[local.ref]?.managementInlet?.call?.resume(local.ref)
            }
        }
    }

    private fun maybeLink(local: Replicable<*>, other: CellRef) {
        if (other == local.ref) return
        val key = local.ref to other
        linked[key]?.let { (cell, link) ->
            // Anti-entropy on re-announce (M10.1): a re-announced replica may
            // be RECOVERING from a crash — frames the transport had already
            // swallowed at crash time were never journaled on its side, so
            // parked replay alone cannot restore them. Re-fire the catch-up
            // hook through the existing link: the full state-as-delta unicast
            // is idempotent (tags / pointwise max), so a plain re-announce
            // costs one redundant delta at worst.
            @Suppress("UNCHECKED_CAST")
            (cell.outlet as FanOutlet<Propagate<Any?>>).linking.onLinked(link)
            return
        }
        // the proxy resolves the port by name; delta types are erased on this
        // path and re-checked at the receiving inlet's serve
        val routed = (HostedCellProxy.create(other, registry, ReplicaDeltaInlet::class.java)
                as ReplicaDeltaInlet).deltaInlet.call
        @Suppress("UNCHECKED_CAST")
        linked[key] = local to (local.outlet as FanOutlet<Propagate<Any?>>).streamTo(routed)
    }
}
