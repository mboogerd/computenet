package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanOutlet
import civictech.cell.link.Interest
import civictech.cell.link.Link
import civictech.cell.link.sliceTo
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.proxy.Proxy
import java.util.UUID

/**
 * Relocated to `civictech.cell.host` (f7h.1-D1): the fold now lives on
 * [civictech.cell.host.InstanceIndex], the membership lane, not here. This
 * `typealias` keeps every existing caller in this package — and testkit's
 * `import civictech.cell.replication.LeaderMark` — compiling unmodified; see
 * [civictech.cell.host.LeaderMark] for the type's KDoc.
 */
typealias LeaderMark = civictech.cell.host.LeaderMark

/**
 * An epoch-stamped unit on the leader→follower log (spec 42): "every leader
 * stamps its produced deltas with the epoch it applied under; deltas or
 * commands stamped below the current epoch are fenced (inert)".
 *
 * [baseline] distinguishes the two kinds of unit that ride this one stream
 * ([MEM1-32], f7h.3-D1). A **baseline** is the leader's WHOLE state at
 * [epoch] and is *adopted* — it replaces the replica's local state. A
 * non-baseline (the default) is a *delta* and is applied by the replica's
 * ordinary apply. The distinction exists because a claimant's first
 * shipment on a fresh link used to be the current state expressed as a
 * from-zero delta, which is correct only for a follower that holds nothing:
 * a follower that already held state ADDED the leader's total onto its own
 * (testkit's BS-14 measured the demoted ex-leader at 6 where the successor
 * held 4). Route every incoming unit through [applyTo] rather than reading
 * this flag directly (f7h.3-D2).
 *
 * The field is **additive on the wire**: it is the third positional
 * parameter and defaults to `false`, so every pre-existing two-argument
 * construction compiles unchanged, and `WireCodec`'s `Json` never sets
 * `encodeDefaults` — so a `baseline = false` unit encodes to exactly the
 * bytes it encoded to before this field existed. `WireCodec.VERSION` is
 * therefore unchanged. Pinned, not merely asserted, by
 * `civictech.cell.wire.StampedWireCompatTest` over two fixtures captured
 * before the field existed.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("Stamped")
data class Stamped<D>(val epoch: Long, val delta: D, val baseline: Boolean = false)

/**
 * The **one** fence-and-route rule for an incoming [Stamped] at a replica
 * holding [currentEpoch] ([MEM1-04], [MEM1-31], [MEM1-32], f7h.3-D2).
 *
 * Fenced when `epoch < currentEpoch`: returns `null` and calls neither
 * lambda — the unit is inert, and in particular the replica's epoch does
 * NOT move. Otherwise exactly one of [onBaseline] (iff [Stamped.baseline],
 * adopt: replace local state) and [onDelta] (apply) is called, and the
 * epoch the replica now holds — `maxOf(currentEpoch, epoch)` — is returned
 * for the caller to assign:
 *
 * ```kotlin
 * currentEpoch = value.applyTo(currentEpoch, ::adoptState) { total += it } ?: currentEpoch
 * ```
 *
 * It lives here, and every reference cell calls it, so the rule cannot
 * drift between fixtures (f7h.3-D2). The fence stays in the CELL rather
 * than in [SingleWriterReplication] because the engine never sees a
 * follower's inlet — deltas arrive by [HostedCellProxy] straight at the
 * cell's port.
 */
fun <D> Stamped<D>.applyTo(currentEpoch: Long, onBaseline: (D) -> Unit, onDelta: (D) -> Unit): Long? {
    if (epoch < currentEpoch) return null
    if (baseline) onBaseline(delta) else onDelta(delta)
    return maxOf(currentEpoch, epoch)
}

/**
 * Contract for a single-writer replicated cell (spec 42 §Single-writer
 * replication). Unlike [civictech.cell.data.Replicable]'s symmetric mesh,
 * exactly one instance — the leader — applies writes and is the single wave
 * source; the rest are command-forwarding followers. There is no merge
 * function: the leader's delta stream is totally ordered, so followers
 * apply in per-link FIFO order (31) — exactly why a non-idempotent cell can
 * replicate this way when the mesh would double-count.
 */
interface SingleWriterReplicable<D> : Cell {
    /** Promote: serve the real write implementation locally under [epoch]. */
    fun becomeLeader(epoch: Long)

    /**
     * Demote: command-forward every write to [leaderRef] under [epoch] —
     * spec 42's "a write landing on a follower is redirected, not
     * rejected" via 10/14's `delegate`. [forwardWrites] is the shared
     * helper implementors use to build the forwarding target.
     */
    fun becomeFollower(leaderRef: CellRef, epoch: Long, registry: LocationRegistry)

    /** Leader→follower shipping outlet — one direction, no gossip back. */
    val deltaOutlet: FanOutlet<Propagate<Stamped<D>>>

    /**
     * Follower apply inlet — FIFO per link.
     *
     * Normative ([MEM1-04], [MEM1-31], [MEM1-32]): an implementor SHALL
     * apply an incoming [Stamped] only if its [Stamped.epoch] is **at or
     * above** the replica's current epoch, and SHALL fence the remainder —
     * a below-epoch unit is inert, applies nothing and does not move the
     * replica's epoch. Of the units that pass the fence, one carrying
     * [Stamped.baseline] `true` SHALL be adopted via [adoptState]
     * (replacing local state) and one carrying `false` SHALL be applied by
     * the ordinary apply.
     *
     * f7h.3-D2: implementors realize this rule by calling [applyTo] rather
     * than open-coding the comparison, so the rule has exactly one
     * definition and the reference cells cannot drift from it.
     */
    val deltaInlet: Use<Propagate<Stamped<D>>>

    /** Current applied state — used for late-join catch-up and RESTART peer catch-up. */
    fun currentState(): D

    /**
     * Adopt a peer's state wholesale, replacing whatever local state was
     * restored — the RESTART-by-peer-catch-up path (spec 42 §RESTART).
     *
     * Normative ([MEM1-32], f7h.3-D6): this is also the apply path for a
     * **baseline** unit on [deltaInlet]. A claimant's first shipment on
     * every fresh link is `Stamped(epoch, currentState(), baseline = true)`
     * — the "announce as a re-baseline" half of [MEM1-32] — and a follower
     * that receives it SHALL adopt, not add. Implementors MUST therefore
     * make this REPLACE local state rather than merge into it; a
     * merge-shaped `adoptState` re-introduces exactly the duplication the
     * baseline flag exists to remove. Reached through [applyTo]'s
     * `onBaseline` (f7h.3-D2), never by testing [Stamped.baseline] inline.
     */
    fun adoptState(state: D)
}

/**
 * Command-forward a follower's write inlet to its leader (spec 42 §Leader =
 * the single applying instance; 10/14 `delegate`). Built directly against
 * [InvocationSink]/[HostedPortInvocation] — the same primitives
 * [HostedCellProxy] uses — because the write API type is arbitrary per
 * cell and only known to the calling implementor, not to this generic
 * replication package.
 *
 * Type-determined exception (spec 20/23, 42): a `Leased` argument cannot
 * cross a machine boundary and is *Rejected* — thrown synchronously at the
 * follower rather than silently forwarded and dropped downstream; every
 * other write, including `Owned` (which crosses by move-by-serialize), is
 * an ordinary redirect.
 */
fun <Api : Any> forwardWrites(clazz: Class<Api>, portName: String, leaderRef: CellRef, registry: LocationRegistry): Use<Api> {
    val sink = InvocationSink(registry::deliver)
    val api: Api = Proxy.fromClass(clazz) { _, method, args ->
        check(args?.none { it is Leased<*> } != false) {
            "Rejected: Leased payload cannot cross a machine boundary off-leader (spec 20/23, 42)"
        }
        sink.deliver(
            HostedPortInvocation(
                cellRef = leaderRef,
                portName = portName,
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(method, args, CurrentContext.get()),
            )
        )
        null
    }
    return Use.fixed(api, PortRef.generate())
}

/**
 * RESTART-by-peer-catch-up (spec 42 §RESTART = peer catch-up, not
 * checkpoint trust, decided 93 I-25): RESTART preserves `instanceId`, so
 * the leader's ref, links, and [LeaderMark] all survive — no re-election,
 * no relink (already true of ordinary RESTART supervision). What this adds:
 * the recovered leader MUST re-catch-up from a reachable follower rather
 * than trust its own possibly-stale spawn-time checkpoint. [donor] is a
 * *reachable* follower's live state — supplying it is the explicit/
 * orchestrated hook this ticket ships (choosing the *most advanced* one
 * automatically across an arbitrary follower set is the liveness/election
 * residual, G-44, 95 §R1). Checkpoint restore (whatever RESTART
 * supervision already put back) is the correct SOLO fallback when no
 * follower is reachable — pass `donor = null` and this is a no-op.
 */
fun <D> restartCatchUp(leader: SingleWriterReplicable<D>, donor: SingleWriterReplicable<D>?) {
    if (donor == null) return
    leader.adoptState(donor.currentState())
}

/**
 * The leader/follower engine (spec 42 §Single-writer replication). One
 * instance per peer, mirroring [Replication]'s shape: fold [LeaderMark]
 * announcements, apply the resulting role to every locally-spawned replica
 * of that logical id, and ship the leader's delta outlet to every follower
 * discovered via [civictech.cell.host.InstanceIndex.replicasOf] / `onPublish` — reusing the
 * same membership-discovery machinery the mergeable mesh already built
 * (M7.2), just wired asymmetrically instead of into a full mesh.
 */
class SingleWriterReplication(
    private val registry: LocationRegistry,
    /**
     * How the shipper projects a delta element to the key an [Interest] is
     * scoped over (T07 finding 1, mirroring [Replication]'s `keyOf`).
     * Identity by default. Kept as a constructor parameter — not yet used by
     * any caller in this ticket's scope — for the same reason `Replication`
     * carries it: a future partitioned single-writer substrate supplies the
     * group key through the identical [sliceTo] primitive rather than a
     * second slicing mechanism.
     */
    private val keyOf: (Any?) -> Any? = { it },
) {

    private data class Local(val cell: SingleWriterReplicable<*>)

    private val localReplicas = mutableMapOf<UUID, MutableList<Local>>()

    /** Established leader→follower shipping links (one direction only). */
    private val shipped = mutableMapOf<Pair<CellRef, CellRef>, Link>()

    interface DeltaInletHolder {
        val deltaInlet: Use<Propagate<Stamped<Any?>>>
    }

    init {
        registry.onPublish { ref -> onPeerPublished(ref) }
        // T07 finding 1 (Divergence B): mirrors Replication.kt's onUnpublish
        // reconciliation (G-45) — a follower's despawn/eviction drops the now-
        // stale outbound shipping link rather than leaving it targeting a gone
        // ref; its next re-announce ([onPeerPublished]) rebuilds via the
        // ordinary [shipTo] construction path. Unlike `Replication`'s mesh
        // (idempotent merge tolerates a lingering duplicate subscriber), a
        // single-writer follower's apply is explicitly NOT idempotent
        // (`SwCounterOps`'s doc), so the stale link is also [Link.unlink]ed —
        // not just dropped from bookkeeping — so a rebuilt link never doubles
        // up a live subscription and double-applies future shipments.
        registry.onUnpublish { ref ->
            shipped.keys.filter { it.second == ref }.toList().forEach { key -> shipped.remove(key)?.unlink() }
        }
        // f7h.1-D4: role application hangs off the registry's ANY-scope
        // leader-mark hook, not off [designateLeader]'s body, so a mark that
        // arrives by any path — this engine's own [designateLeader], another
        // engine on the same registry, or F2's `mirrorLeaderMark` fed from a
        // peer announcement — applies roles identically. Exactly one fold per
        // registry, therefore exactly one role application per adopted mark.
        //
        // Known behavioural delta (f7h.1-D3), accepted: [LocationRegistry]
        // treats hooks as notifications, not participants — it swallows and
        // prints a listener exception. A throw from [becomeLeader]/
        // [becomeFollower] therefore no longer propagates out of
        // [designateLeader], which now returns purely the fold's verdict. No
        // shipped call site asserted such a throw.
        registry.onLeaderMark { mark -> applyRoles(mark) }
    }

    /**
     * The folded leader for [logicalId] — read straight off the membership
     * index ([MEM1-03]). The engine keeps no [LeaderMark] map of its own:
     * [civictech.cell.host.InstanceIndex] is the single fold ([civictech.cell.host.LocationRegistry.markLeader]
     * its single writer), so an engine and its registry can never disagree.
     */
    fun leaderOf(logicalId: UUID): LeaderMark? = registry.instances.leaderOf(logicalId)

    /**
     * Spawn [cell] as one replica of a single-writer logical cell and fold
     * the initial [mark]. The first replica of a logical id is typically
     * spawned as its own leader (epoch 0) — an orchestrated decision, never
     * elected.
     */
    fun <D> replicate(
        cell: SingleWriterReplicable<D>,
        host: ManagedHost,
        mark: LeaderMark,
    ) {
        localReplicas.getOrPut(cell.ref.id) { mutableListOf() } += Local(cell)
        host.managementInlet.call.spawn(cell)
        designateLeader(mark)
    }

    /**
     * Fold a *local* [LeaderMark] announcement (spec 42 §Leadership is a
     * `LeaderMark` epoch fold). The verdict is the registry's, verbatim: a
     * mark not strictly greater under the fold's total order over `(epoch,
     * leaderRef.instanceId)` is fenced — inert, rejected outright — exactly
     * the split-brain guard the spec requires. Returns `true` if adopted.
     *
     * Roles are NOT applied here; adoption notifies
     * [civictech.cell.host.LocationRegistry.onLeaderMark], and this engine's
     * subscriber (see `init`) applies them. The signature and the verdict are
     * unchanged for callers.
     */
    fun designateLeader(mark: LeaderMark): Boolean = registry.markLeader(mark)

    /**
     * Apply [mark]'s roles to every local replica of its logical id — the
     * single subscriber's body, run once per adopted fold (see `init`). The
     * designated replica promotes and then ships to every other known
     * instance; every other local replica demotes to command-forwarding.
     */
    private fun applyRoles(mark: LeaderMark) {
        localReplicas[mark.logicalId]?.forEach { local ->
            if (local.cell.ref == mark.leaderRef) {
                local.cell.becomeLeader(mark.epoch)
                registry.instances.replicasOf(mark.logicalId).filter { it != mark.leaderRef }
                    .forEach { follower -> shipTo(local.cell, follower) }
            } else {
                local.cell.becomeFollower(mark.leaderRef, mark.epoch, registry)
            }
        }
    }

    private fun onPeerPublished(ref: CellRef) {
        val mark = registry.instances.leaderOf(ref.id) ?: return
        if (ref == mark.leaderRef) return
        val leaderLocal = localReplicas[ref.id]?.firstOrNull { it.cell.ref == mark.leaderRef } ?: return
        shipTo(leaderLocal.cell, ref)
    }

    private fun shipTo(leader: SingleWriterReplicable<*>, followerRef: CellRef) {
        if (followerRef == leader.ref) return
        // Interest gate (T07 finding 1, Divergence A; spec 40/42 §Interest-
        // scoped instance sets, CP-D2; mirrors Replication.maybeLink's gate):
        // a shipping link forms only where the leader's and the follower's
        // interests overlap — a disjoint-interest follower never links at
        // all, so a delta cannot even reach an instance that doesn't want it.
        // Default (unset) interest is Total on both sides, so overlap is
        // always true and this is byte-identical to pre-interest shipping.
        val targetInterest = registry.instances.interestOf(followerRef)
        if (!registry.instances.interestOf(leader.ref).overlaps(targetInterest)) return
        val key = leader.ref to followerRef
        shipped[key]?.let { link ->
            @Suppress("UNCHECKED_CAST")
            (leader.deltaOutlet as FanOutlet<Propagate<Stamped<Any?>>>).linking.fireLinked(link)
            return
        }
        val routed = (HostedCellProxy.create(followerRef, registry, DeltaInletHolder::class.java)
                as DeltaInletHolder).deltaInlet.call
        // Per-emission interest filter (T07 finding 1, Divergence A; mirrors
        // Replication.maybeLink's `sink`): every delta — the live stream and
        // the onLinked catch-up baked into the link below — is restricted to
        // the *target's* interest before it ships, by slicing the STAMPED
        // envelope's payload and re-stamping under the same epoch. A delta a
        // partial-interest follower has no interest in never crosses. Total
        // interest short-circuits to the bare routed sink, so the default
        // shipping path is unwrapped and byte-identical.
        //
        // `stamped.baseline` rides through the re-wrap (f7h.3-D1): a sliced
        // baseline is still a baseline — the leader's whole state RESTRICTED
        // to what this follower wants — so dropping the flag here would turn
        // every partial-interest follower's catch-up back into an additive
        // delta, which is the exact defect the flag removes. Note the one
        // asymmetry this creates for an EMPTY winner: `sliceTo` of an empty
        // `SetDelta` under a non-Total interest returns null (SetDelta.within
        // refuses an empty restriction), so a partial-interest follower
        // receives no baseline frame at all for an empty leader, while a
        // Total-interest one does (Interest.Total short-circuits). Accepted
        // per f7h.3.1: the empty-baseline convergence guarantee holds for
        // Total interest only.
        val sink: Propagate<Stamped<Any?>> = if (targetInterest is Interest.Total) routed
        else Propagate { stamped ->
            sliceTo(stamped.delta, targetInterest, keyOf)?.let {
                routed.propagate(Stamped(stamped.epoch, it, stamped.baseline))
            }
        }
        @Suppress("UNCHECKED_CAST")
        shipped[key] = (leader.deltaOutlet as FanOutlet<Propagate<Stamped<Any?>>>).streamTo(sink)
    }

    /**
     * How many leader→follower shipping links exist among [refs] (T07 finding
     * 1 test seam, mirroring [Replication.linkCountAmong]): used to pin
     * finding 1's Divergence B fix — the count drops to 0 on a follower's
     * unpublish and rebuilds to 1 on its re-announce, rather than leaving a
     * stale entry forever.
     */
    internal fun shipCountAmong(refs: Set<CellRef>): Int =
        shipped.keys.count { it.first in refs && it.second in refs }

    companion object {
        /**
         * PN-17 formation predicate (spec 31 §Effects on instance sets, plan
         * §3b): an [Effectful][civictech.cell.evolve.Effectful] cell on a
         * non-[disjoint] (Total/overlapping) instance set needs a declared
         * effect **authority** — a single-writer leader that fires while its
         * followers suppress. Disjoint interest is effect-once *by
         * construction* (each logical delta reaches exactly one covering
         * instance; the per-inlet processed-frontier dedups replay), so it
         * needs none; a non-effectful cell is unconstrained.
         */
        fun effectAuthorityRequired(effectful: Boolean, disjoint: Boolean): Boolean =
            effectful && !disjoint

        /**
         * The instance-set **formation** refusal (spec 31, mirroring PN-18's
         * [civictech.cell.nature.NatureNegotiation.admitToInstanceSet] and PN-8's
         * overlap refusal): an [effectful] cell joining a Total/overlapping set
         * with no declared authority is refused with a loud typed error, moved
         * to the moment the combination is formed rather than discovered as N
         * duplicate effects later. A leaderful set ([hasAuthority]) or a
         * [disjoint] one never raises — exactly as a default requirement never
         * refuses at a link, so no existing (non-effectful, or disjoint, or
         * single-writer) instance set changes.
         */
        fun requireEffectAuthority(effectful: Boolean, disjoint: Boolean, hasAuthority: Boolean) {
            check(!effectAuthorityRequired(effectful, disjoint) || hasAuthority) {
                "Refused: an Effectful cell on a Total/overlapping instance set requires a declared " +
                    "effect authority (a SingleWriterReplication leader) — spec 31 §Effects on instance sets (PN-17)"
            }
        }
    }
}
