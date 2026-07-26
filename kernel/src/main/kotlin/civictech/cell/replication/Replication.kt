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
class Replication(
    private val registry: LocationRegistry,
    /**
     * How the linker projects a delta element to the key an [Interest] is scoped
     * over (PN-6, plan §3 "`maybeLink` generalized with `keyOf`"). Identity by
     * default — in a replica mesh the element *is* the key, so the linker is
     * byte-identical to pre-PN-6 gossip. A partitioned substrate supplies the
     * group key, so the *same* linker serves partitioning: replication and
     * partitioning are one slice-and-route mechanism ([sliceTo]), not two.
     */
    private val keyOf: (Any?) -> Any? = { it },
) {

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
    internal fun watermarkRef(dataRef: CellRef): CellRef =
        CellRef(UUID.nameUUIDFromBytes("watermark:${dataRef.id}".toByteArray()), dataRef.instanceId)

    /**
     * Cross-track settlement read (spec 20/22 §Completeness — cross-replica
     * extension, E3.4; interest-scoped in PN-7, plan §3 Rule of settlement,
     * spec 22 §Interest-scoped settlement): the JoinBarrier "has the covering
     * subset delivered origin wave `(source, counter)` touching `key`" answered
     * off the local merged [WatermarkCell].
     *
     * **Quorum = the covering subset** (PN-7, resolving F2): the live members
     * whose [Interest] ADMITS `key` — not every member. A disjoint-interest
     * instance never delivers waves outside its slice, so its row stays at
     * bottom forever; quantifying over it (the pre-PN-7 `members.all`) stalls a
     * WAIT consumer the moment shards join the mesh. Filtering to the covering
     * subset collapses to today's behavior when every interest is [Interest.Total]
     * (all members cover every key) and to the sharded-replication quorum under
     * overlap. A `null` `key` (a consumer that extracts no origin keys) is
     * unfiltered — every member — byte-identical to pre-PN-7.
     *
     * Membership is the [LocationRegistry.instancesOf] fold; a member's row is
     * read at its derived [WatermarkCell.slotId]. A `closed` slot (cleanly
     * departed) stops constraining; a member whose row has not yet gossiped in
     * holds the wave (WAIT), never releases it early.
     *
     * **R13 creation fence** ([creationFence], default on — promoted from optional
     * to blocking in PN-7). Because filtering *shrinks* the quorum, a joining
     * covering member that has not yet established its delivered-watermark row is
     * the premature-release hazard: dropping it from the quorum releases a wave it
     * has not delivered. The fence is the requirement that such a member **holds**
     * the wave — a covering member with no row for [source] reads as bottom
     * (`Long.MIN_VALUE`) and fails the predicate, exactly as a lagging member
     * does, so the wave waits until it catches up. With the fence OFF a covering
     * member is admitted to the quorum only once it has *some* row for [source] —
     * a rowless (freshly-joined) member is silently skipped, reproducing the
     * premature release. An empty covering subset always holds (never a vacuous
     * release).
     *
     * Membership itself ([LocationRegistry.instancesOf]) remains eventually
     * consistent: a covering member the local view has not yet learned of at all
     * cannot be waited on here — that residual (a converged membership barrier /
     * the DEGRADE quorum-shrink) is sequenced to PN-19, documented not hidden.
     */
    fun replicaFrontier(logicalId: UUID, creationFence: Boolean = true): ReplicaFrontier =
        ReplicaFrontier { source, counter, key ->
            val companion = watermarks[logicalId]
            val members = registry.instancesOf(logicalId)
            if (companion == null || members.isEmpty()) return@ReplicaFrontier false
            val rows = companion.rows()
            val closed = companion.closed()
            val covering = members
                .filter { key == null || registry.interestOf(it).admits(key) }
                .filter { ref ->
                    // R13: with the fence on, EVERY covering member is required (a
                    // rowless one holds on bottom); with it off, a member is only
                    // required once it has published a row for this source, so a
                    // freshly-joined covering member is skipped — the premature hazard.
                    creationFence ||
                        WatermarkCell.slotId(watermarkRef(ref)) in closed ||
                        rows[WatermarkCell.slotId(watermarkRef(ref))]?.containsKey(source) == true
                }
            covering.isNotEmpty() && covering.all { ref ->
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
        // PN-17 effect-authority formation refusal (spec 31 §Effects on instance
        // sets, plan §3b). A [Replicable] that is ALSO
        // [civictech.cell.evolve.Effectful] joining THIS mergeable mesh has no
        // single-writer discipline to name one firer: the delta gossips to every
        // overlapping replica and each fires the external effect once per logical
        // delta (the ×N bug the ReplicatedEffectTest control makes visible). Refuse
        // the moment the overlapping-effectful set is formed — a loud typed error
        // ([SingleWriterReplication.requireEffectAuthority]), not a silent admit.
        //
        // What is NOT refused, so no existing graph changes:
        //  - a non-[Effectful] replicable (every mergeable cell today — SetCell,
        //    PnCounterCell, WatermarkCell, InstanceSet): the predicate is a no-op,
        //    so this path is byte-for-byte unchanged;
        //  - a disjoint-interest effectful replica (partitioning): each logical
        //    delta reaches exactly one covering instance, effect-once by
        //    construction, so no authority is needed. Disjointness here is the same
        //    overlap test the linker ([maybeLink]) uses — an effectful replica that
        //    overlaps NO already-known replica of its id forms no fan-out link;
        //  - a lone/first effectful replica (overlaps nobody yet — single-instance
        //    effectful is unchanged).
        // The authority-bearing path is [SingleWriterReplication.replicate] (a
        // SingleWriterReplicable leader fires, followers suppress) — a distinct
        // method this guard never sees, so authority-declaring cells stay admitted.
        if (cell is civictech.cell.evolve.Effectful) {
            val interest = registry.interestOf(cell.ref)
            val overlapsExisting = registry.replicasOf(cell.ref.id)
                .any { it != cell.ref && interest.overlaps(registry.interestOf(it)) }
            SingleWriterReplication.requireEffectAuthority(
                effectful = true,
                disjoint = !overlapsExisting,
                // the mergeable mesh carries no leader discipline; a cell that wants
                // to fire once must replicate via SingleWriterReplication instead
                hasAuthority = false,
            )
        }
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
     *
     * On a real (despawn) departure this closes the local delivered-watermark
     * row once this peer hosts no further replica of the logical id (PN-0c, spec
     * 40/42 §Delivered watermarks): [WatermarkCell.close] marks the row cleanly
     * departed so a downstream replica-fed frontier stops constraining reads on
     * it. This matters because membership ([LocationRegistry.replicasOf]) is only
     * eventually consistent (the R13 caveat) — a consumer whose view still lists
     * the departed member would otherwise wait forever on a row that can never
     * advance again. The `closed` marker rides the same idempotent watermark
     * mesh as the data, so it converges even where the topology unpublish is
     * lost. [closeDepartedRow] is the PN-0c control seam only (default keeps the
     * fix; `false` reproduces the pre-PN-0c wedge). Non-replica-fed graphs never
     * read the row, so closing it is unobservable to them.
     */
    fun evict(cell: Replicable<*>, host: ManagedHost, closeDepartedRow: Boolean = true): Boolean {
        val reachablePeers = registry.replicasOf(cell.ref.id) - cell.ref
        if (reachablePeers.isEmpty()) {
            if (partitionSuspended.add(cell.ref)) host.managementInlet.call.suspend(cell.ref)
            return false
        }
        host.managementInlet.call.suspend(cell.ref)
        // final push-catch-up to one reachable peer (best-effort; idempotent either way)
        linked.entries.firstOrNull { it.key.first == cell.ref }?.let { (_, linkedPair) ->
            @Suppress("UNCHECKED_CAST")
            (cell.outlet as FanOutlet<Propagate<Any?>>).linking.fireLinked(linkedPair.second)
        }
        host.managementInlet.call.despawn(cell.ref)
        localReplicas[cell.ref.id]?.remove(cell)
        // clean-departure watermark close: only once the last local replica of
        // this id leaves (the companion carries this peer's single row).
        if (closeDepartedRow && localReplicas[cell.ref.id].isNullOrEmpty()) watermarks[cell.ref.id]?.close()
        hostOf.remove(cell.ref)
        linked.keys.filter { it.first == cell.ref }.toList().forEach { linked.remove(it) }
        partitionSuspended.remove(cell.ref)
        return true
    }

    /**
     * Rolling replicated promotion (PN-14, spec 53 §Replicated promotion): swap
     * the local [incumbent] replica for [candidate] **behind the same CellRef**,
     * one instance at a time. This is the replicated analogue of
     * [civictech.cell.evolve.Promotion.promote] — see
     * [civictech.cell.evolve.Promotion.promoteReplica], which wraps it with
     * PRECHECK. It is *additive*: single-instance `promote` is untouched.
     *
     * Because every mesh identity derives from the [CellRef] — the tag lane
     * ([civictech.cell.data.SetCell] mints under a ref-derived `tagSource`), the
     * delivered-watermark row ([watermarkRef]), and every port ref (PN-1) — a
     * candidate that REUSES the incumbent's ref makes the swap indistinguishable
     * from **crash-recovery**, and that is the mechanism: peers' inbound gossip
     * links keep resolving to the ref (now the candidate) with no re-link, this
     * peer's delivered-watermark companion (and its row) is retained so a
     * downstream replica-frontier read never sees the member vanish, and the
     * candidate re-syncs by the same anti-entropy catch-up a recovered replica
     * uses. The surviving replicas on other peers play the retained incumbent —
     * the state source — so no cross-peer coordination is needed. Set-atomic
     * promotion (every replica at once) is consensus and out of scope.
     *
     * Two halves of recovery run here:
     *  - **journal replay** ([carryTagState], default on): the incumbent's
     *    [Stateful.snapshot] is restored into the candidate synchronously, before
     *    the swap. `SetCell`'s tag *counter* is snapshot state, so this is what
     *    continues the ref-derived tag lane without a restart — a fresh mint
     *    never collides with an already-emitted tag. It is idempotent under the
     *    mergeable merge, so the anti-entropy catch-up below cannot double-count.
     *    Off is the PN-14 control seam only (reproduces the T2 fresh-epoch
     *    collision — the tag lane restarts under the same source).
     *  - **anti-entropy**: [replicate] re-establishes this peer's outbound gossip
     *    and re-points delivered-watermark tracking to the candidate, **reusing**
     *    the existing companion (keyed by logical id, [trackDeliveries]) so the
     *    row survives; the re-announce fires the [maybeLink] catch-up at every
     *    known peer and the candidate's own `catchUpOnLinked` re-syncs it.
     *
     * The incumbent's local bookkeeping and outbound links are dropped WITHOUT
     * closing the delivered-watermark row (unlike [evict]): this peer stays a
     * member — the same ref returns — so the row must keep constraining. Inbound
     * gossip that arrives during the object swap parks at the registry on the
     * despawn's unpublish and replays on the candidate's republish, so no peer
     * delta is lost.
     */
    fun rebind(
        incumbent: Replicable<*>,
        candidate: Replicable<*>,
        host: ManagedHost,
        carryTagState: Boolean = true,
    ) {
        require(candidate.ref == incumbent.ref) {
            "replicated promotion reuses the incumbent's CellRef (the crash-recovery mechanism that " +
                "continues the tag lane and the watermark row); candidate ${candidate.ref} != incumbent ${incumbent.ref}"
        }
        val ref = incumbent.ref
        if (carryTagState && incumbent is civictech.cell.Stateful && candidate is civictech.cell.Stateful) {
            candidate.restore(incumbent.snapshot())
        }
        // drop the incumbent's local bookkeeping + outbound gossip, but NEVER the
        // watermark row (this peer is not departing — the same ref returns).
        localReplicas[ref.id]?.remove(incumbent)
        hostOf.remove(ref)
        linked.keys.filter { it.first == ref }.toList().forEach { linked.remove(it) }
        host.managementInlet.call.despawn(ref)
        // recovery: republish the candidate under the SAME ref and re-establish
        // this peer's gossip + watermark tracking (companion reused).
        replicate(candidate, host)
    }

    /**
     * How many gossip links the linker has formed among [refs] (PN-6 test seam):
     * the number of ordered `(local, other)` pairs, both in [refs], that overlap
     * and therefore linked. Filtered to [refs] so the count excludes the delivered-
     * watermark companions the mesh also links — the assertion is "one link per
     * overlapping instance pair", the one-linker invariant.
     */
    internal fun linkCountAmong(refs: Set<CellRef>): Int =
        linked.keys.count { it.first in refs && it.second in refs }

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
        // Interest gate (spec 40/42 §Interest-scoped instance sets, CP-D2): a
        // gossip link forms only where the two instances' interests overlap —
        // disjoint interests (the partitioning setting) form no link at all, so
        // a delta cannot even reach an instance that does not want it. Default
        // is total interest on both sides, so overlap is always true here and
        // this is byte-identical to pre-interest gossip.
        val targetInterest = registry.interestOf(other)
        if (!registry.interestOf(local.ref).overlaps(targetInterest)) return
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
            (cell.outlet as FanOutlet<Propagate<Any?>>).linking.fireLinked(link)
            return
        }
        // the proxy resolves the port by name; delta types are erased on this
        // path and re-checked at the receiving inlet's serve
        val routed = (HostedCellProxy.create(other, registry, ReplicaDeltaInlet::class.java)
                as ReplicaDeltaInlet).deltaInlet.call
        // Per-emission interest filter (CP-D2): every delta — the live stream
        // and the onLinked catch-up baked into the link below — is restricted
        // to the *target's* interest before it rides. A delta a partial-interest
        // peer has no interest in never crosses. Total interest short-circuits to
        // the bare routed sink, so the default gossip path is unwrapped and
        // byte-identical.
        val sink: Propagate<Any?> = if (targetInterest is Interest.Total) routed
        else Propagate { delta -> sliceTo(delta, targetInterest, keyOf)?.let { routed.propagate(it) } }
        @Suppress("UNCHECKED_CAST")
        linked[key] = local to (local.outlet as FanOutlet<Propagate<Any?>>).streamTo(sink)
    }

}
