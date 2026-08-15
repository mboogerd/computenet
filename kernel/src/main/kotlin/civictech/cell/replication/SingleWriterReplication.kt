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
 * Leadership announcement for a single-writer logical cell (spec 42
 * §Single-writer replication, decided 93 I-25, not built until this
 * ticket). Folded into an eventually-consistent membership index the same
 * way as an ordinary [LocationRegistry] publish (P4) — no view number, no
 * quorum, no barrier: `leaderOf(id)` is simply the mark with the greatest
 * epoch this peer has folded. Automatic election that *mints* these marks
 * is the deferred liveness half (G-44 residual, 95 §R1); this ticket ships
 * EXPLICIT/orchestrated designation only — [SingleWriterReplication.designateLeader]
 * is the manual-failover hook the spec declares the default.
 */
data class LeaderMark(val logicalId: UUID, val epoch: Long, val leaderRef: CellRef)

/**
 * An epoch-stamped unit on the leader→follower log (spec 42): "every leader
 * stamps its produced deltas with the epoch it applied under; deltas or
 * commands stamped below the current epoch are fenced (inert)".
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("Stamped")
data class Stamped<D>(val epoch: Long, val delta: D)

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

    /** Follower apply inlet — FIFO per link; a below-current-epoch delta is fenced (inert). */
    val deltaInlet: Use<Propagate<Stamped<D>>>

    /** Current applied state — used for late-join catch-up and RESTART peer catch-up. */
    fun currentState(): D

    /**
     * Adopt a peer's state wholesale, replacing whatever local state was
     * restored — the RESTART-by-peer-catch-up path (spec 42 §RESTART).
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
    private val leaderMarks = mutableMapOf<UUID, LeaderMark>()

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
    }

    fun leaderOf(logicalId: UUID): LeaderMark? = leaderMarks[logicalId]

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
     * Fold a [LeaderMark] announcement (spec 42 §Leadership is a
     * `LeaderMark` epoch fold): a mark at or below the currently-folded
     * epoch is fenced — inert, rejected outright — exactly the split-brain
     * guard the spec requires ("a leader that folds a strictly greater
     * epoch steps down ... and a leader that folds a strictly greater
     * epoch steps down to a command-forwarding follower"). Returns `true`
     * if adopted.
     */
    fun designateLeader(mark: LeaderMark): Boolean {
        val current = leaderMarks[mark.logicalId]
        if (current != null && mark.epoch <= current.epoch) return false
        leaderMarks[mark.logicalId] = mark
        localReplicas[mark.logicalId]?.forEach { local ->
            if (local.cell.ref == mark.leaderRef) {
                local.cell.becomeLeader(mark.epoch)
                registry.instances.replicasOf(mark.logicalId).filter { it != mark.leaderRef }
                    .forEach { follower -> shipTo(local.cell, follower) }
            } else {
                local.cell.becomeFollower(mark.leaderRef, mark.epoch, registry)
            }
        }
        return true
    }

    private fun onPeerPublished(ref: CellRef) {
        val mark = leaderMarks[ref.id] ?: return
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
        val sink: Propagate<Stamped<Any?>> = if (targetInterest is Interest.Total) routed
        else Propagate { stamped ->
            sliceTo(stamped.delta, targetInterest, keyOf)?.let { routed.propagate(Stamped(stamped.epoch, it)) }
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
