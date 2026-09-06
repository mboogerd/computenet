package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.TagFrontier
import civictech.cell.consistency.CausalStability
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.consistency.ReplicaQuorum
import civictech.cell.consistency.StabilityFreezeDetector
import civictech.cell.control.StallNotice
import civictech.cell.Propagate
import civictech.cell.data.Replicable
import civictech.cell.data.WatermarkCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.notifyDownstream
import civictech.cell.port.FanOutlet
import civictech.cell.link.Interest
import civictech.cell.link.Link
import civictech.cell.link.sliceTo
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.host.HostedCellProxy
import java.util.*
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.data.delta.DeliveryTracking

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
    /**
     * Enable the heartbeat cadence's effect (9sm.2-D3, [KE3-15]): defaulted
     * `true` so every existing `Replication(registry)` / `Replication(registry,
     * keyOf = …)` call site compiles unchanged and, because nothing yet ticks
     * [heartbeat], behaves byte-identically. `false` makes [heartbeat] a
     * no-op — the flag half of [KE3-15]'s "heartbeat disabled by
     * configuration" control.
     */
    private val heartbeat: Boolean = true,
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
     * [civictech.cell.host.InstanceIndex.replicasOf] exactly as the data replicas do.
     */
    internal fun watermarkRef(dataRef: CellRef): CellRef =
        CellRef(UUID.nameUUIDFromBytes("watermark:${dataRef.id}".toByteArray()), dataRef.instanceId)

    /**
     * The cross-replica settlement predicate (T11-D): extracted to
     * [ReplicaQuorum] in `.consistency` — a consistency concern, same
     * package as [ReplicaFrontier] itself and its consumer `WaveFrontier` —
     * constructed from the three reads it needs, all already injected here
     * ([watermarks], [civictech.cell.host.InstanceIndex.instancesOf],
     * [civictech.cell.host.InstanceIndex.interestOf])
     * plus the [watermarkRef] derivation.
     */
    private val replicaQuorum = ReplicaQuorum(
        watermarkOf = { logicalId -> watermarks[logicalId] },
        membersOf = { logicalId -> registry.instances.instancesOf(logicalId) },
        interestOf = { ref -> registry.instances.interestOf(ref) },
        watermarkRefOf = ::watermarkRef,
    )

    /**
     * The **causal-stability** read (E3.5, `computenet-9sm.3`; spec
     * `doc/spec/40-distribution/42-replication.md` [42-WM-05], [42-WM-07]):
     * a sibling of [replicaQuorum] over the *same* injected reads minus
     * `interestOf` — decision 9sm.3-D1 (one class each, not one class with
     * two moods) and 9sm.3-D4 (interest is deliberately not applied; there
     * is no key to scope it against for a per-logical-id read). See
     * [CausalStability] for the whole model, including R14 (a superseded
     * column stays in the MIN).
     */
    private val causalStability = CausalStability(
        watermarkOf = { logicalId -> watermarks[logicalId] },
        membersOf = { logicalId -> registry.instances.instancesOf(logicalId) },
        watermarkRefOf = ::watermarkRef,
    )

    /**
     * One-line facade over [CausalStability.stableFrontier] — the same shape
     * [replicaFrontier] has over [ReplicaQuorum.frontier], and for the same
     * reason: call sites name `Replication`, the semantics live in the
     * `.consistency` class. **[CausalStability.stableFrontier] is the truth**:
     * the pointwise MIN of every open membership row, an absent row (and an
     * open slot lacking a column) reading as bottom and therefore ABSENT from
     * [TagFrontier.perSource]; no companion yields `TagFrontier(emptyMap())`.
     *
     * Inert ([KE3-22], `[24-BOUND-01]`): a read emits nothing on the companion
     * outlet, mints no tag and never enters [civictech.cell.CurrentContext], so
     * it is safe from a checkpoint or GC pass outside any wave.
     *
     * @param degrade drop recoverably-suspended (odd-epoch) slots from the open
     *   set — the same PN-19 quorum-shrink switch [replicaFrontier] carries.
     */
    fun stableFrontier(logicalId: UUID, degrade: Boolean = false): TagFrontier =
        causalStability.stableFrontier(logicalId, degrade)

    /**
     * Poke [listener] with the new [TagFrontier] whenever [logicalId]'s
     * [stableFrontier] **rises** — the stability analogue of
     * [onWatermarkAdvance] and built the same way (decision 9sm.3-D2: a tap on
     * the local companion's outlet). Returns silently when no replica of
     * [logicalId] has been [replicate]d here, exactly as [onWatermarkAdvance]
     * does.
     *
     * **Exactly once per effective rise, per listener** ([KE3-21]). The
     * baseline is taken at registration — registering never fires — and each
     * companion delta recomputes. A *rise* is: some source strictly greater
     * than in the previous frontier, or present now and absent before (absent
     * = bottom). Anything else is not a rise and does not call: a redelivered
     * or echoed delta, a rowless member marker, or one member advancing while
     * another still caps the MIN. A source DROPPING OUT (membership grew, or a
     * newly-open slot has no row yet) is likewise not a rise, and the recorded
     * baseline is **not** lowered — so the next genuine rise of another source
     * still fires exactly once rather than twice.
     *
     * **The tap only sees companion-lattice movement** — `rows`, `closed`,
     * `suspended`, `members` deltas. A `closed` arrival CAN raise the MIN (a
     * lagging slot leaves the open set) and does fire. A rise caused *solely*
     * by [civictech.cell.host.InstanceIndex.instancesOf] shrinking moves no
     * companion lattice and is therefore observed only at the next companion
     * delta.
     */
    fun onStabilityAdvance(logicalId: UUID, listener: (TagFrontier) -> Unit) {
        val companion = watermarks[logicalId] ?: return
        var last = stableFrontier(logicalId)
        companion.outlet.tap(Use.fixed(Propagate<WatermarkDelta> {
            val now = stableFrontier(logicalId)
            // A rise: strictly greater somewhere, or newly present (absent = bottom).
            val rises = now.perSource.any { (source, value) ->
                last.perSource[source]?.let { value > it } ?: true
            }
            if (rises) {
                last = now
                listener(now)
            }
        }, PortRef.generate()))
    }

    /**
     * One [StabilityFreezeDetector] per logical id, created lazily by the
     * first [onStabilityStall] registration and shared by every listener of
     * that id — the latch is per **id**, not per listener, so a second
     * listener registering mid-freeze does not re-arm the counter.
     */
    private val stabilityFreeze = mutableMapOf<UUID, StabilityFreezeDetector>()

    /** App listeners registered through [onStabilityStall], per logical id. */
    private val stabilityStallListeners = mutableMapOf<UUID, MutableList<(StallNotice) -> Unit>>()

    /**
     * Hand [listener] every stability-freeze notice for [logicalId] — the
     * stall analogue of [onStabilityAdvance] and built the same way
     * (decisions 9sm.5-D1/D7, [KE3-27]): a tap on the local companion's
     * outlet, evaluated on every companion delta. Returns silently when no
     * replica of [logicalId] has been [replicate]d here, exactly as
     * [onStabilityAdvance] does.
     *
     * What arrives is a `Stall(STABILITY_FROZEN, timestamp, slot)` naming the
     * replica slot the causal-stability MIN is pinned on, and later exactly
     * one [StallNotice.Resume] retracting it. The predicate, the threshold
     * and the latch live entirely in [StabilityFreezeDetector]; see its KDoc.
     *
     * **One snapshot per evaluation** ([KE3-24]): `rows()`, `closed()`,
     * `members()` and
     * [civictech.cell.host.InstanceIndex.instancesOf] are each read exactly
     * once per delta, and the WAIT open set is derived from them the way
     * [CausalStability.stableFrontier] derives its own — announced members ∪
     * instance-derived slots, minus `closed`. Suspended slots stay IN: the
     * WAIT read is frozen on them (9sm.5-D6).
     *
     * **Also fanned downstream** (9sm.5-D7): every notice is additionally
     * pushed through [civictech.cell.host.notifyDownstream] for each local
     * replica of [logicalId], so a downstream `WaveFrontier` consumer
     * receives it on its Suspension protocol edge exactly as an ordinary
     * `Stall` and applies the 9sm.5-D3 disposition (WAIT: no action; DEGRADE:
     * the local-replica edge joins the suspended set). A consumer with no
     * Suspension handler ignores it — delivery is `handlers[id]?.invoke`.
     *
     * **Known conflation, not redesigned here** (9sm.5-D7): [StallNotice.Resume]
     * is not keyed by reason, so a stability Resume also clears a
     * SUSPENDED- or RESTARTING-caused suspension of the same edge. That
     * conflation already exists between the shipped reasons; this path
     * inherits it.
     *
     * **This path mutates nothing** ([KE3-28]): it never calls `close`,
     * `suspend`, `resume` or [evict] on any slot or replica. The notice is a
     * diagnostic; unfreezing is an operator action.
     */
    fun onStabilityStall(logicalId: UUID, listener: (StallNotice) -> Unit) {
        val companion = watermarks[logicalId] ?: return
        val listeners = stabilityStallListeners.getOrPut(logicalId) { mutableListOf() }
        listeners += listener
        // The tap and the detector are installed once per id; later listeners
        // join the existing one so the latch stays per id.
        if (listeners.size > 1) return
        val detector = stabilityFreeze.getOrPut(logicalId) { StabilityFreezeDetector() }
        companion.outlet.tap(Use.fixed(Propagate<WatermarkDelta> {
            // ONE snapshot per evaluation ([KE3-24]).
            val rows = companion.rows()
            val closed = companion.closed()
            val announced = companion.members()
            val instances = registry.instances.instancesOf(logicalId)
            val open = buildSet {
                instances.mapTo(this) { WatermarkCell.slotId(watermarkRef(it)) }
                addAll(announced)
                removeAll(closed)
            }
            for (notice in detector.evaluate(rows, open, closed)) {
                stabilityStallListeners[logicalId]?.toList()?.forEach { it(notice) }
                localReplicas[logicalId]?.toList()?.forEach { replica -> notifyDownstream(replica, notice) }
            }
        }, PortRef.generate()))
    }

    /**
     * **A control seam only** (decision 9sm.3-D5, [KE3-20]): this peer's OWN
     * companion row for [logicalId] — what this replica has locally delivered,
     * NOT what is globally stable. Empty when no replica of [logicalId] lives
     * here.
     *
     * It exists so `computenet-9sm.4`'s BS-13 control can switch a compaction
     * trigger to the **wrong** frontier and show the difference; nothing in
     * production may read it, and it is `internal` so only `:kernel` tests
     * reach it. The right read is [stableFrontier], which is this row's
     * pointwise MIN against every other open member's.
     */
    internal fun localDeliveredFrontier(logicalId: UUID): TagFrontier {
        val companion = watermarks[logicalId] ?: return TagFrontier(emptyMap())
        return TagFrontier(companion.rows()[WatermarkCell.slotId(companion.ref)] ?: emptyMap())
    }

    /**
     * One-line factory over [ReplicaQuorum.frontier] (T11-D) — kept here so
     * every existing call site (`Replication.replicaFrontier(...)`) is
     * unchanged; see [ReplicaQuorum.frontier] for the full KDoc: the R13
     * creation fence, the PN-19 DEGRADE quorum-shrink, and the FU-2
     * converged-membership barrier.
     */
    fun replicaFrontier(
        logicalId: UUID,
        creationFence: Boolean = true,
        degrade: Boolean = false,
        membershipBarrier: Boolean = true,
    ): ReplicaFrontier = replicaQuorum.frontier(logicalId, creationFence, degrade, membershipBarrier)

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

    /**
     * The idle-liveness cadence entry point ([42-WM-06], spec 40/42
     * §"Idle liveness: heartbeat rows", authored by 9sm.1; [KE3-11]–[KE3-15]):
     * re-[civictech.cell.data.WatermarkCell.republish] every local
     * delivered-watermark companion this `Replication` tracks. No-op when
     * [heartbeat] (the constructor flag) is `false` — [KE3-15]'s "heartbeat
     * disabled by configuration".
     *
     * **This is the WHOLE kernel surface for the heartbeat — no cadence
     * driver ships (9sm.2-D5).** `HostScheduler` exposes `submit`/`await`/
     * `shutdown` only, with no timer or delayed-submit primitive; a
     * self-resubmitting task would keep [civictech.cell.host.SimulationController.runToIdle]
     * from ever returning (`hasWork()` reads the queue as non-empty forever)
     * and would spin a `VirtualThreadScheduler` at idle for no reason; and
     * `ManagedHost` exposes no submit/post/schedule hook a cadence could hang
     * off. So a deployment ticks this method from its OWN periodic source —
     * outside any cell, submitting the call onto the host scheduler like any
     * other management-band action — and no cell ever reads a clock (96
     * §E3.3(c)'s constraint, unbroken). Tests tick it directly between
     * `runToIdle()` drains, or from a DST `StepHooks.onStep` hook.
     *
     * **What a heartbeat can and cannot do.** It repairs a peer's view of
     * this replica's row when that peer's earlier view was LOST — a dropped
     * frame, a missed catch-up — by re-emitting the unchanged row so the
     * peer's stale view is corrected on the next delivery. It never raises
     * the causal-stability MIN above this replica's own row: an unchanged
     * row is a fixpoint everywhere it already arrived
     * ([civictech.cell.data.WatermarkCell.republish]'s "echo terminates
     * here"), so in a lossless mesh — where `advance`/`applyRemote`/
     * `catchUpOnLinked` already deliver every row to every peer without
     * this call — a heartbeat changes no read at all ([KE3-12]).
     */
    fun heartbeat() {
        if (!heartbeat) return
        watermarks.values.forEach { it.republish() }
    }

    init {
        registry.onPublish { ref -> linkOut(ref) }
        // reconcile (spec 42, G-45): a peer's despawn/eviction — or, since T21,
        // a whole peer dropped by `unpublishRemotes` — removes it from
        // replicasOf; drop the now-stale outbound gossip link rather than
        // leaving it targeting a gone ref (no ack protocol — this is purely
        // local bookkeeping, the routed proxy would otherwise just dead-letter).
        //
        // Bookkeeping only, deliberately: the outlet attachment itself is left
        // in place and simply *replaced* if the peer comes back, because the
        // gossip subscription is keyed by a ref derived from the pair
        // ([gossipRef]). Unlinking here instead would be the other half of the
        // same guarantee — but it would have to be repeated in [evict] and
        // [rebind], and it is the derived key, not this call site, that makes
        // re-linking idempotent no matter which path dropped the entry.
        registry.onUnpublish { ref -> linked.keys.filter { it.second == ref }.toList().forEach { linked.remove(it) } }
    }

    /**
     * Spawn [cell] on [host] as a replica: gossip links to every currently
     * known replica of its logical id are installed now, and to future ones
     * as their announcements arrive. The caller owns instance-id uniqueness
     * (distinct per replica, minted without coordination).
     *
     * **Re-replicating an existing ref is crash recovery** (computenet-h50w):
     * see [supersedeLocalInstance] for why the superseded instance's local
     * bookkeeping has to be dropped here rather than by a cooperative path.
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
            val interest = registry.instances.interestOf(cell.ref)
            val overlapsExisting = registry.instances.replicasOf(cell.ref.id)
                .any { it != cell.ref && interest.overlaps(registry.instances.interestOf(it)) }
            SingleWriterReplication.requireEffectAuthority(
                effectful = true,
                disjoint = !overlapsExisting,
                // the mergeable mesh carries no leader discipline; a cell that wants
                // to fire once must replicate via SingleWriterReplication instead
                hasAuthority = false,
            )
        }
        val superseded = supersedeLocalInstance(cell)
        localReplicas.getOrPut(cell.ref.id) { mutableListOf() } += cell
        hostOf[cell.ref] = host
        host.managementInlet.call.spawn(cell)
        registry.instances.replicasOf(cell.ref.id).forEach { other -> maybeLink(cell, other) }
        trackDeliveries(cell, host, rehome = superseded)
    }

    /**
     * Drop the local bookkeeping of a *superseded* instance at [cell]'s ref —
     * a different object that was replicated here under the same [CellRef] and
     * is now gone (computenet-h50w).
     *
     * Three cooperative paths already clear [linked]: the [registry] `onUnpublish`
     * reconciliation (which clears the pair's REMOTE side), [evict] and [rebind]
     * (both by `it.first == ref`, and both called by whoever is retiring the
     * replica). **A host discarded by a crash takes none of them.** So a rebuild
     * at the SAME instance id — the case [maybeLink]'s own M10.1 KDoc claims to
     * serve, "a re-announced replica may be RECOVERING from a crash" — used to
     * find `linked[(ownRef, remoteRef)]` still holding the DISCARDED cell:
     * [maybeLink] took its already-linked branch, re-fired the state-as-delta
     * catch-up through the dead cell's outlet, and never installed a link from
     * the rebuilt cell. Every delta the rebuilt replica then produced was
     * stranded on this peer — measured as 9-of-10-seed divergence by the CHA1
     * exchange DST rig (`ExchangeReplicatedCrashDstTest`).
     *
     * Clearing it here rather than at a crash hook is deliberate: nothing calls
     * the runtime back when a host is discarded, so the *return* of the ref —
     * this call — is the only event a crash and a rebuild both reach.
     *
     * Like [rebind] and unlike [evict] this does **not** close the
     * delivered-watermark row: this peer is not departing, the same ref returns,
     * and the row must keep constraining replica-fed frontier reads. And like
     * [rebind] it leaves the stale outlet attachment alone — [gossipRef] makes
     * the re-link *replace* it at the outlet.
     *
     * A first replicate of a fresh ref, and [rebind]/[evict] (which already
     * removed the incumbent), find nothing here: the path is a no-op for every
     * caller but the crash rebuild. Returns whether it superseded anything —
     * [replicate] passes that on to [trackDeliveries] as the rebuild signal
     * ([rehomeCompanion]).
     *
     * **The PN-19 Stall is retracted with the local marker** (computenet-nf8w).
     * Dropping `partitionSuspended` alone would have been a latch leak: [evict]
     * with no reachable peer sets that marker AND raises the companion's odd
     * suspend epoch, and [linkOut]'s heal branch is the only path that retracts
     * either — gated on the marker still being present. Clearing the marker here
     * without the paired `resume()` makes the heal branch unreachable for this
     * ref forever, and [civictech.cell.data.WatermarkCell.suspend]/`resume` is a
     * latched per-slot epoch nothing else retracts, so a DEGRADE covering-quorum
     * read would drop this member permanently. The retraction is right rather
     * than merely symmetric: the ref RETURNS here, spawned live on the rebuilt
     * host — it is not parked, so nothing may still read it as parked. (The
     * reviewer's alternative reading, that a `resume()` on a companion spawned
     * on the crashed host propagates into nothing, does not hold: the companion
     * is the same in-process object, its epoch map is plain local state, and
     * [civictech.cell.consistency.ReplicaQuorum] reads it directly — measured by
     * `CrashRebuildWatermarkResidencyTest`.) The guard is `it !== cell`, so a
     * plain re-replicate of the same object never reaches this and no live
     * partition-suspend is resumed out from under [evict].
     */
    private fun supersedeLocalInstance(cell: Replicable<*>): Boolean {
        val locals = localReplicas[cell.ref.id] ?: return false
        if (!locals.any { it !== cell && it.ref == cell.ref }) return false
        locals.removeAll { it !== cell && it.ref == cell.ref }
        linked.keys.filter { it.first == cell.ref }.toList().forEach { linked.remove(it) }
        if (partitionSuspended.remove(cell.ref)) watermarks[cell.ref.id]?.resume()
        return true
    }

    /**
     * Delivered-tracking seam (spec 40/42 §Delivered watermarks, E3.3): wire
     * [cell]'s delivery path into a [WatermarkCell] companion for its logical
     * id, itself replicated on [host] so its deltas gossip over the existing
     * mesh (no new protocol). The companion's ref id is derived from the data
     * id (`watermark:{logicalId}`) and it borrows the data replica's
     * `instanceId`, so each peer contributes a distinct, replay-stable row and
     * the companions of one logical id find each other by [civictech.cell.host.InstanceIndex.replicasOf] exactly
     * as the data replicas do. A [WatermarkCell] is not itself tracked —
     * that would recurse — its own convergence is the ordinary gossip path.
     *
     * **The `getOrPut` memoisation deliberately survives a crash-and-rebuild at
     * the same instance id, and this path provides no crash recovery of the
     * delivered frontier** (computenet-2h68). A rebuilt replica reuses the
     * companion object, so the row it froze at crash time is what a frontier
     * read still sees — the decided disposition for an unclean departure
     * (96-incremental-engines-plan E3.6(c); lease-based row eviction is R13,
     * out of scope). Only the companion's *residency* is re-established, via
     * [rehomeCompanion] on the [rehome] signal [replicate] passes down from
     * [supersedeLocalInstance]; read that KDoc before assuming more is offered
     * here than there is.
     */
    private fun trackDeliveries(cell: Replicable<*>, host: ManagedHost, rehome: Boolean = false) {
        if (cell is WatermarkCell) return
        var fresh = false
        val companion = watermarks.getOrPut(cell.ref.id) {
            fresh = true
            WatermarkCell(watermarkRef(cell.ref)).also { replicate(it, host) }
        }
        if (rehome && !fresh && hostOf[companion.ref] !== host) rehomeCompanion(companion, host)
        // FU-2 converged-membership barrier: announce this covering member's
        // existence on the companion mesh (a transitively-gossiped CRDT), so a
        // settling peer holds keyed waves until its own membership view has caught
        // up to this join — the unknown-joiner analogue of the R13 creation fence.
        companion.announceMember()
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
     * Move an existing delivered-watermark companion onto [host] after a
     * crash-and-rebuild at the same instance id (computenet-2h68).
     *
     * **The companion object is deliberately reused, and that is not the bug.**
     * A crash is an *unclean departure*, and the decided disposition for one is
     * that the delivered-watermark row FREEZES rather than being recovered or
     * evicted (96-incremental-engines-plan E3.6(c): "unclean departure (crash
     * without close, mesh churn) leaves the frontier frozen ... not worked
     * around: lease-based row eviction is R13, explicitly out of scope"). The
     * same ref returns, so — exactly as in [rebind] — the row must keep
     * constraining replica-fed frontier reads; minting a *fresh* companion here
     * would silently discard this peer's `rows`, `closed`, `suspended` and
     * `members` lattices and read as a member that restarted at bottom. So
     * [trackDeliveries]'s `getOrPut` memoisation is the correct behaviour.
     *
     * What the memoisation left behind is not state but **residency**: the
     * companion was spawned on the host the crash discarded, so
     * `registry.locate(watermarkRef(...))` still answered the dead host while
     * the data replica had moved to the rebuilt one, and inbound companion
     * gossip resolved to a `Local` host that no longer runs anything. That is a
     * bookkeeping residue of the same shape [supersedeLocalInstance] repairs for
     * [linked], and it is repaired the same way — on the *return* of the ref,
     * the only event a crash and a rebuild both reach.
     *
     * Re-spawning the same object republishes the ref at the live host; peers
     * keep resolving to the ref with no re-link (every mesh identity derives
     * from the [CellRef]), and inbound gossip that arrives mid-move parks at the
     * registry and replays on the republish — the same guarantee [rebind]
     * relies on. The lattice is untouched, so nothing about the frozen-row
     * disposition above changes.
     *
     * **Honest scope.** No measured frontier failure motivated this: the
     * `ExchangeReplicatedCrashFrontierDstTest` sweep agrees on both peers across
     * the crash on all ten seeds *without* this repair, because the discarded
     * host's simulated scheduler keeps draining newly submitted work
     * (`SimulationController` never deregisters a scheduler and `shutdown()`
     * only clears its queue). The residue is pinned structurally instead, by
     * `CrashRebuildWatermarkResidencyTest`, and this is a repair of a stale
     * registry pointer — not a claim that crash-recovery of the delivered
     * frontier is now provided. It is not; see E3.6(c) above.
     */
    private fun rehomeCompanion(companion: WatermarkCell, host: ManagedHost) {
        hostOf[companion.ref] = host
        host.managementInlet.call.spawn(companion)
    }

    /**
     * Evict [cell] from [host] — the decided gated drain+despawn (93 I-3,
     * G-45's gate half; the *trigger* — sustained attention band NONE, 34,
     * or manual — is wiring the economic layer owns, G-62, out of this
     * ticket's scope).
     *
     * **Membership-gated**: [civictech.cell.host.InstanceIndex.replicasOf] already drops a
     * partitioned peer's `Remote` location (42 §Anti-entropy), so "no
     * reachable peer" and "partitioned" are the same local observation. With
     * none reachable this replica may hold unique un-gossiped state nobody
     * else has a copy of — it MUST suspend (park), never despawn, and await
     * heal; the next re-announce that grows `replicasOf` back above one
     * resumes it automatically ([linkOut]).
     *
     * **Gated drain+despawn** otherwise, in three steps — spec 42's
     * *"intake closes (spec 33's drain, applied at cell instead of host
     * granularity)"*, realized by
     * [civictech.cell.host.ManagedHost.drainCellThenDespawn]:
     * [civictech.cell.host.HostManagementApi.suspend] closes this replica's own
     * intake on the management band; then, on the drain band (priority 30,
     * BELOW data's 20, so every already-accepted invocation has been dispatched
     * first — spec 31 §priorities, spec 33 step 2), the parked accepted work is
     * applied to the cell, a final state-as-delta catch-up re-fires at one
     * reachable peer's existing link (the same M10.1 re-announce hook
     * [maybeLink] uses), and despawn unpublishes the ref — surviving peers'
     * linkers simply stop targeting a ref no longer in `replicasOf` on their
     * next announcement, no ack protocol.
     *
     * **This call blocks, and only from outside the host's own execution
     * context.** [civictech.cell.host.ManagedHost.drainCellThenDespawn]'s
     * first act is an awaited drain-band task, so `evict` itself blocks until
     * that barrier returns — the same rule
     * [civictech.cell.host.HostScheduler.await]'s KDoc states for
     * `spawn`/`lookup`/`connect`: *"Only legal from outside the host's
     * execution context ... host tasks never await."* Call it from a host
     * task and the scheduler enforces the rule itself:
     * `VirtualThreadScheduler`/`CoroutineScheduler`'s `await` throws *"await
     * called from the host's own execution context (would deadlock)"*, and
     * `SimulationController`'s throws *"simulation quiescent but awaited
     * future incomplete."* This is a genuinely new precondition
     * (computenet-078s): before it, both `suspend` and `despawn` took the
     * management proxy's fire-and-forget `else` branch (`enqueue(0)`,
     * `ManagedHost`'s `managementInlet.serve` dispatch) and `evict` never
     * awaited anything.
     *
     * **The barrier is host-wide, not cell-scoped.** It is one more task on
     * the same host-wide queue every other priority-0/10/20 task on this
     * host drains through, so it cannot run while any of those are pending —
     * `evict` blocks for the whole host's backlog, not just this cell's. A
     * host under a saturating data stream can hold the barrier off for as
     * long as the scheduler's await timeout (`future.get(5,
     * TimeUnit.SECONDS)` in both `VirtualThreadScheduler` and
     * `CoroutineScheduler`), at which point `evict` throws rather than
     * completing.
     *
     * **This IS spec 33's drain at cell granularity, and used not to be**
     * (computenet-078s; the boundary computenet-9c5t documented). Until that
     * item, `suspend` and `despawn` were both enqueued at management priority 0
     * — ahead of data's 20 — so `suspend` PREEMPTED a write the host had already
     * accepted and journalled but not yet dispatched to the cell: `deliver` found
     * the freshly installed `ParkQueue`, parked it, and `despawn` drained that
     * queue into dead letters (one per parked invocation, reason
     * `cell <ref> left the host while suspended`, counted in the host's
     * `parkedDrainedOnTeardown` stat). Accounted for, never silently dropped —
     * but never applied to the cell, so never gossiped and never reaching the
     * survivors. That inverted the very ordering the spec's own no-loss argument
     * rests on (93 I-3 §4.6: a connected replica *"drains its outbound delta
     * queue before deactivation (30/33 step 2, whose phase-2 task sits below
     * data priority)"*), which is why it was a defect and not a boundary.
     * The phase-2 ordering also makes the catch-up genuinely drain-gated: it
     * reads state as of the drained intake rather than as of the call.
     *
     * What the catch-up IS: anti-entropy for state this replica holds that one
     * reachable peer may not have absorbed yet — idempotent either way.
     * `[42-REPL-06]` asks that survivors converge and that the departed
     * replica's frozen stream not count as divergence; the drain adds that the
     * departing replica's *accepted* operations are applied before it goes, so
     * they gossip like any other. Pinned by `ChurnReconvergenceTest."a write
     * issued one step before a clean evict reaches the survivors"`.
     *
     * Returns `true` if the replica despawned, `false` if it suspended
     * instead (no reachable peer).
     *
     * On a real (despawn) departure this closes the local delivered-watermark
     * row once this peer hosts no further replica of the logical id (PN-0c, spec
     * 40/42 §Delivered watermarks): [WatermarkCell.close] marks the row cleanly
     * departed so a downstream replica-fed frontier stops constraining reads on
     * it. This matters because membership ([civictech.cell.host.InstanceIndex.replicasOf]) is only
     * eventually consistent (the R13 caveat) — a consumer whose view still lists
     * the departed member would otherwise wait forever on a row that can never
     * advance again. The `closed` marker rides the same idempotent watermark
     * mesh as the data, so it converges even where the topology unpublish is
     * lost. [closeDepartedRow] is the PN-0c control seam only (default keeps the
     * fix; `false` reproduces the pre-PN-0c wedge). Non-replica-fed graphs never
     * read the row, so closing it is unobservable to them.
     */
    fun evict(cell: Replicable<*>, host: ManagedHost, closeDepartedRow: Boolean = true): Boolean {
        val reachablePeers = registry.instances.replicasOf(cell.ref.id) - cell.ref
        if (reachablePeers.isEmpty()) {
            if (partitionSuspended.add(cell.ref)) {
                host.managementInlet.call.suspend(cell.ref)
                // PN-19: publish the recoverable Stall on the watermark mesh so a
                // DEGRADE covering-quorum read drops this parked member (restored
                // by resume on heal); WAIT reads still hold on it.
                watermarks[cell.ref.id]?.suspend()
            }
            return false
        }
        // Gated DRAIN+despawn (spec 42 §Eviction; spec 33 §The drain protocol
        // step 2; 93 I-3 §4.6). The link to catch up on is captured HERE, in the
        // caller's frame, because the local bookkeeping below drops `linked`
        // synchronously while the drain runs on the host's scheduler thread.
        val catchUpTarget = linked.entries.firstOrNull { it.key.first == cell.ref }?.value?.second
        host.drainCellThenDespawn(cell.ref) {
            // Final push-catch-up to one reachable peer: anti-entropy for state
            // this replica holds that the peer may not have absorbed, idempotent
            // either way. It now runs in the drain's phase 2, so it reads state
            // as of the *drained* intake — including the writes this eviction
            // just flushed (computenet-078s closed that ordering wart).
            catchUpTarget?.let { link ->
                @Suppress("UNCHECKED_CAST")
                (cell.outlet as FanOutlet<Propagate<Any?>>).linking.fireLinked(link)
            }
        }
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
            if (local.ref in partitionSuspended && (registry.instances.replicasOf(local.ref.id) - local.ref).isNotEmpty()) {
                partitionSuspended -= local.ref
                hostOf[local.ref]?.managementInlet?.call?.resume(local.ref)
                // PN-19: retract the recoverable Stall — the DEGRADE covering quorum
                // re-admits this member; its post-resume catch-up (PN-2 baseline /
                // anti-entropy) advances the row it froze while parked.
                watermarks[local.ref.id]?.resume()
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
        val targetInterest = registry.instances.interestOf(other)
        if (!registry.instances.interestOf(local.ref).overlaps(targetInterest)) return
        val key = local.ref to other
        linked[key]?.let { (cell, link) ->
            // Anti-entropy on re-announce (M10.1): a re-announced replica may
            // be RECOVERING from a crash — frames the transport had already
            // swallowed at crash time were never journaled on its side, so
            // parked replay alone cannot restore them. Re-fire the catch-up
            // hook through the existing link: the full state-as-delta unicast
            // is idempotent (tags / pointwise max), so a plain re-announce
            // costs one redundant delta at worst.
            //
            // The crash half of that claim only holds because [replicate] drops
            // a superseded instance's entry first ([supersedeLocalInstance],
            // computenet-h50w): `cell` here is whatever object was linked, and
            // re-firing a DISCARDED one's outlet reaches nobody.
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
        linked[key] = local to (local.outlet as FanOutlet<Propagate<Any?>>).streamTo(sink, at = gossipRef(local.ref, other))
    }

    /**
     * The stable identity of the gossip subscription `local → other` carries on
     * `local`'s delta outlet (T21).
     *
     * Derived rather than [PortRef.generate]d because *re-linking is a normal
     * event*: a peer disconnect drops the pair from [linked] (the [onUnpublish]
     * reconciliation above) without unsubscribing the outlet, and the peer's
     * next announcement re-runs [maybeLink]. With a fresh ref per `streamTo`
     * that re-link installs a *second* consumer beside the orphaned first —
     * which nothing names any more, so no [evict]/[rebind]/reconcile can ever
     * reach it — and every disconnect/reconnect cycle adds one more, unbounded.
     * The mergeable merge is idempotent, so duplicated delivery still converges
     * and no convergence assertion can see the leak (see
     * `GossipLinkIdempotenceTest`).
     *
     * Keyed on the ordered pair, so [FanOutlet.subscribe]'s
     * `consumers[ref] = port` *replaces* the stale attachment rather than
     * joining it. That makes re-linking idempotent at the outlet itself,
     * independent of which reconciliation hook fired or in what order — the
     * self-healing property, rather than plugging one call site.
     *
     * No owning `cell`: this endpoint is a free-standing [Use.fixed] stand-in
     * for the *remote* inlet, not a port of [local] (PortRef's own rule —
     * "free-standing endpoints have none"), so only the id becomes derived
     * where it was random. The derivation mirrors [watermarkRef] /
     * [PortRef.of]: a name-spaced UUID over the identities involved.
     */
    private fun gossipRef(local: CellRef, other: CellRef): PortRef = PortRef(
        UUID.nameUUIDFromBytes(
            "gossip:${local.id}:${local.instanceId}:${other.id}:${other.instanceId}".toByteArray(),
        ),
    )

}
