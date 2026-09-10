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
 *
 * **The forwarded-port record** ([LocationRegistry.noteForwardedPort],
 * f7h.5-D2, [MEM1-16]). Building the forwarder records `portName` as a
 * command-forwarded write port for this logical id, which is what lets
 * `SingleWriterReplication`'s release rule tell a *write* parked at a
 * superseded leaderRef from any other parked invocation. It is noted here —
 * at construction, i.e. inside `becomeFollower` — rather than on the first
 * forwarded call, so the record exists before any write is attempted: the
 * release rule can otherwise be reached by a mark that lands before this
 * follower has ever written, and a write parked by a DIFFERENT engine on the
 * same registry would then not be recognized. The note is idempotent, so
 * repeated demotions cost nothing.
 */
fun <Api : Any> forwardWrites(clazz: Class<Api>, portName: String, leaderRef: CellRef, registry: LocationRegistry): Use<Api> {
    registry.noteForwardedPort(leaderRef.id, portName)
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
 *
 * **Election posture** ([MEM1-05], f7h.4-D1). By default the engine only
 * *folds* marks somebody else decided: [designateLeader] is the sole path to
 * `LocationRegistry.markLeader`, and a leader that vanishes stays folded
 * forever. Constructed with [LeaderElection.EpochClaim] it additionally
 * *mints* one — when a witnessed departure of the folded leader persists for
 * the configured [DetectionWindow] of membership observations, a surviving
 * local replica claims the next epoch through that same single fold
 * (f7h.4-D4). The window is counted in observations, never in time
 * ([MEM1-25]): this package reads no clock and starts no thread.
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
    /**
     * Election posture ([MEM1-05], f7h.4-D1). [LeaderElection.Manual] — the
     * default — is exactly the pre-F4 engine: nothing here arms, counts or
     * claims, and [observe] returns immediately. Positioned THIRD, after
     * [keyOf], so a positional `(registry, keyOf)` construction is untouched;
     * that ordering is the compatibility guarantee, not an accident of the
     * diff.
     */
    private val election: LeaderElection = LeaderElection.Manual,
) {

    private data class Local(val cell: SingleWriterReplicable<*>, val host: ManagedHost)

    /**
     * Per-leading-replica divergence record (f7h.5-D3 as refined by
     * computenet-f7h.5.2; [MEM1-13], [MEM1-21], [MEM1-28], G-44).
     *
     * [tap] is a subscription on the leader's own `deltaOutlet` that exists for
     * exactly as long as this leader is ARMED — installed on the first
     * departure, dropped when the last departed member returns, and dropped
     * again by the flush at step-down (see [armDivergence] for why it is not
     * simply held for the whole of the leadership). It ships nowhere, is never
     * entered in [shipped], and appends `(epoch, delta)` to [buffer] only while
     * [departed] is non-empty — i.e. only for deltas produced after this leader
     * WITNESSED a member leave and before it saw that member return.
     *
     * ## What the buffer is, and is not
     *
     * It is "deltas this leader produced while a member of its instance set was
     * away", surfaced at step-down as G-44's *writes the winner did not
     * receive*. The no-ack design (93 I-25 §4.4) admits no stronger reading,
     * and the resulting record is imprecise in both directions — state the
     * bound rather than the intent:
     *
     * - **Over-inclusive** by deltas the departed member later received anyway.
     *   In-process a `Peering` partition is a park, not a loss, so a delta
     *   buffered during it can still be delivered on the heal; and a leader
     *   that survives a partition intact and steps down much later surfaces
     *   partition-era deltas the returned member already took in its baseline.
     * - **Under-inclusive** by deltas in flight at a socket close.
     *
     * ## Why the buffer is retained until step-down
     *
     * f7h.5's original D3 cleared the buffer once `replicasOf(id)` again
     * contained every ref present at arming. That cannot work against the
     * observed catch-up order: `Peering`'s heal announces refs BEFORE marks, so
     * the departed member's `publish` reaches the ex-leader one notification
     * ahead of the superseding mark — clear-on-return would empty the buffer
     * exactly when the step-down that must surface it is next. So [departed]
     * disarms on a return (recording stops) but [buffer] is retained, and both
     * are cleared only by the flush at step-down.
     *
     * Which test pins the retention, measured (computenet-f7h.5.2, re-measured
     * in review): reinstating clear-on-return reddens
     * `DivergentWriteSurfacingTest`'s **example 6** and "a write made after the
     * departed member returned is not divergent" — the two single-partition
     * shapes — and **not example 4**. Example 4 partitions the leader from BOTH peers, so
     * the first heal's `publish` leaves [departed] still non-empty and the
     * superseding mark arrives before the second — it survives the unrefined
     * rule by an ordering accident. The single-partition shape is the one that
     * genuinely constrains this.
     */
    private class Divergence {
        var tap: Link? = null
        val departed = mutableSetOf<CellRef>()
        val buffer = mutableListOf<Pair<Long, Any?>>()
    }

    /** [Divergence] per LEADING local replica ref; absent for a replica that does not lead. */
    private val divergences = mutableMapOf<CellRef, Divergence>()

    /** [onDivergentWrite] subscribers. Copy-on-write: a handler may detach from inside the flush. */
    private val divergentWriteHandlers =
        java.util.concurrent.CopyOnWriteArrayList<(UUID, Long, Long, Any?) -> Unit>()

    /**
     * Observe **divergent writes** — the application-level hook G-44 asks for
     * ([MEM1-13], f7h.5-D4). Fires once per buffered delta at the moment the
     * local leader that produced it steps down, with the logical id, the
     * `fencedEpoch` it was produced under, the `canonicalEpoch` of the mark
     * that superseded it, and the raw delta payload (not its [Stamped]
     * envelope).
     *
     * The same deltas are simultaneously reported on the host's dead-letter
     * outlet, so an application that registers nothing still loses nothing.
     * Detachment follows [LocationRegistry.onPublish]'s contract: close the
     * returned handle to unsubscribe. A handler is a *notification, not a
     * participant*: its exception is caught, reported as its own dead letter,
     * and never propagated — the next handler and the rest of the step-down
     * run regardless.
     *
     * **Delivery, not replay.** The hook hands the write over; re-applying it
     * is the application's decision. Surfacing runs AFTER
     * [SingleWriterReplicable.becomeFollower] precisely so that a handler which
     * chooses to re-apply through the ordinary write API reaches the winner.
     */
    fun onDivergentWrite(
        handler: (logicalId: UUID, fencedEpoch: Long, canonicalEpoch: Long, payload: Any?) -> Unit,
    ): AutoCloseable {
        divergentWriteHandlers += handler
        return AutoCloseable { divergentWriteHandlers -= handler }
    }

    private val localReplicas = mutableMapOf<UUID, MutableList<Local>>()

    /** Established leader→follower shipping links (one direction only). */
    private val shipped = mutableMapOf<Pair<CellRef, CellRef>, Link>()

    /**
     * The last [LeaderMark] this engine actually APPLIED, per logical id
     * (f7h.3-D3). [LocationRegistry.onLeaderMark] hands over only the new
     * mark, and neither [LeaderMark] nor [Stamped] carries a role, so this map
     * is the only way [applyRoles] can know which local replica WAS leading
     * and therefore has to step down.
     *
     * Inferring "was leading" from `shipped.keys.any { it.first == local.ref }`
     * was considered and rejected: a leader with no followers yet holds no
     * links at all, and the step-down seam below needs the OLD mark, not a
     * boolean. Written at the END of [applyRoles], so the whole pass reads a
     * consistent `previous`.
     */
    private val applied = mutableMapOf<UUID, LeaderMark>()

    /** Step-down observers ([onStepDown]). */
    private val stepDownListeners = mutableListOf<(StepDown) -> Unit>()

    /**
     * The detection window's state, per logical id (f7h.4-D2 as refined by
     * f7h.4.1-D1). A key's PRESENCE is the arming bit and its value is the
     * number of observations made since; an id with no key is **unarmed** and
     * is never counted by anything, including [observe].
     *
     * Arming is deliberately narrower than "the leader is absent": only an
     * `onUnpublish` naming the currently folded `leaderRef`, for a logical id
     * this engine hosts a replica of, arms. **An absence never witnessed as a
     * departure is not a failure observation** — and without that narrowing a
     * freshly spawned follower whose leader has not yet been announced (the
     * spawn-first/peer-second ordering every fixture uses) would see its own
     * and its siblings' `onPublish` with the leaderRef absent and, at a window
     * no larger than the sibling count, claim against a live leader. `window =
     * 1` still means "claim on the unpublish itself".
     *
     * Empty and untouched under [LeaderElection.Manual].
     */
    private val misses = mutableMapOf<UUID, Int>()

    interface DeltaInletHolder {
        val deltaInlet: Use<Propagate<Stamped<Any?>>>
    }

    /**
     * One replica's demotion out of leadership, as observed by [onStepDown]
     * (f7h.3-D3): [replica] led under [from] and has just been demoted by the
     * adoption of [to], which is strictly greater under the fold's total
     * order. [logicalId] is `to.logicalId`, carried explicitly so a listener
     * need not destructure a mark to route.
     */
    internal data class StepDown(
        val logicalId: UUID,
        val from: LeaderMark,
        val to: LeaderMark,
        val replica: SingleWriterReplicable<*>,
    )

    /**
     * Observe local step-downs — the seam F5 (divergent-write surfacing)
     * subscribes to; nothing in F3 subscribes, deliberately. Fires exactly
     * once per demoted ex-leader, AFTER its outbound shipping links are
     * unlinked and after its [SingleWriterReplicable.becomeFollower] has run,
     * so a listener observes a replica that already forwards writes and can
     * emit nothing to a follower.
     *
     * Detachment follows [LocationRegistry.onPublish]'s contract: close the
     * returned handle to unsubscribe. A listener is a *notification, not a
     * participant* (f7h.1-D3's precedent, [LocationRegistry.notify]): its
     * exception is swallowed and printed, never propagated into the fold and
     * never allowed to stop the next listener or the promotion pass.
     */
    internal fun onStepDown(listener: (StepDown) -> Unit): AutoCloseable {
        stepDownListeners += listener
        return AutoCloseable { stepDownListeners -= listener }
    }

    private fun notifyStepDown(event: StepDown) {
        stepDownListeners.toList().forEach { listener ->
            try {
                listener(event)
            } catch (e: Exception) {
                System.err.println("[SingleWriterReplication] step-down hook failed for ${event.logicalId}: $e")
            }
        }
    }

    init {
        registry.onPublish { ref ->
            // f7h.5-D3 disarm: the member is back, so recording stops. The
            // BUFFER is deliberately not cleared here — see [Divergence].
            disarmDivergence(ref)
            onPeerPublished(ref)
            // f7h.4-D2(b): a publish for an ARMED id is an observation like any
            // other — it disarms when it restores the leaderRef and counts when
            // it does not. After [onPeerPublished], so a re-announced follower
            // is already linked when a claim evaluated here promotes somebody.
            observeIfArmed(ref.id)
        }
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
            // f7h.5-D3 arm: a local replica that currently LEADS this logical
            // id has just witnessed a member of its own instance set depart.
            // Every delta it produces from here until that member returns is
            // recorded, and surfaced at its step-down.
            armDivergence(ref)
            // f7h.4-D2(a)/(b), and f7h.4-D7's ordering requirement: the
            // detector runs AFTER the unlink above, so a claim evaluated
            // inside this same notification sees the stale link already gone.
            onMembershipDeparture(ref)
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
        registry.onLeaderMark { mark ->
            // f7h.4-D2, rule 5: ANY adopted mark ends the window for that
            // logical id — this engine's own claim, another engine's
            // designation, or a peer's mirrored mark alike. The new leader's
            // presence is witnessed from scratch afterwards.
            misses.remove(mark.logicalId)
            // f7h.5-D2, [MEM1-16]: the OLD leaderRef, read before [applyRoles]
            // overwrites [applied]. [applyRoles] reads the same value for its
            // own step-down decision; capturing it here rather than having
            // [applyRoles] return it keeps that function's contract unchanged.
            val previous = applied[mark.logicalId]
            applyRoles(mark)
            if (previous != null && previous.leaderRef != mark.leaderRef) {
                releaseParked(previous.leaderRef, mark)
            }
        }
    }

    /**
     * Explicitly step the detection window ([MEM1-25], f7h.4-D2(c)) — the
     * management-band cadence hook, mirroring [Replication.heartbeat]: the
     * kernel ships the step and the caller decides when steps happen, because
     * a step driven from a clock or a background thread is exactly what
     * [DetectionWindow] exists to avoid.
     *
     * Counts one observation for every **armed** logical id, disarming any
     * whose folded leaderRef is present again. An id that was never witnessed
     * departing is untouched, however many times this is called, and under
     * [LeaderElection.Manual] this returns immediately having done nothing.
     */
    fun observe() {
        if (election !is LeaderElection.EpochClaim) return
        misses.keys.toList().forEach { id -> observeIfArmed(id) }
    }

    /**
     * The detection window's count for [logicalId] — 0 both when the id is
     * unarmed and when an observation disarmed it (f7h.4-D2). The internal
     * seam the F4 behavioural tasks read: `leaderOf` alone cannot tell "the
     * count was reset" from "the id was never counted", and a totals-only
     * assertion in this subsystem is vacuous precisely because the fold
     * swallows the difference.
     */
    internal fun missCount(logicalId: UUID): Int = misses[logicalId] ?: 0

    /**
     * f7h.4-D2(a): arm on a witnessed departure of the folded leader, or —
     * when already armed — treat this departure as one more observation.
     */
    private fun onMembershipDeparture(ref: CellRef) {
        if (election !is LeaderElection.EpochClaim) return
        val id = ref.id
        if (misses.containsKey(id)) {
            observeIfArmed(id)
            return
        }
        if (localReplicas[id].isNullOrEmpty()) return
        val mark = registry.instances.leaderOf(id) ?: return
        if (ref != mark.leaderRef) return
        misses[id] = 1
        evaluateClaim(id)
    }

    /** f7h.4-D2(b)/(c): one observation for an armed id; a no-op for any other. */
    private fun observeIfArmed(logicalId: UUID) {
        if (election !is LeaderElection.EpochClaim) return
        if (!misses.containsKey(logicalId)) return
        val mark = registry.instances.leaderOf(logicalId)
        if (mark == null || mark.leaderRef in registry.instances.replicasOf(logicalId)) {
            misses.remove(logicalId)
            return
        }
        misses[logicalId] = (misses[logicalId] ?: 0) + 1
        evaluateClaim(logicalId)
    }

    /**
     * The claim rule (f7h.4-D3, guarded per f7h.4.1-D2). Runs
     * **synchronously** on the observing caller's thread (f7h.4-D5): no hop,
     * no queue, no thread is added, so the claim is folded — and, through
     * `onLocalLeaderMark`, announced — before the membership notification that
     * triggered it returns.
     *
     * Two guards, and both KEEP the count rather than resetting it:
     *
     * - `local` is this engine's **published** local replicas of the id.
     *   [localReplicas] is never pruned on despawn, so a stale [Local] entry
     *   for a replica that is gone from the membership index must not become a
     *   claimant. An engine that hosted the leader and despawned it therefore
     *   arms (on its own `onUnpublish`) and then refuses here.
     * - `reachable` is [MEM1-23]: a claimant with nobody to lead mints
     *   nothing.
     *
     * The count is kept so a later observation — a new peer's `onPublish` for
     * this id, or another [observe] — re-evaluates and claims as soon as
     * somebody is reachable. Only the leaderRef's return (an observation) or
     * an adopted mark resets it.
     */
    private fun evaluateClaim(logicalId: UUID) {
        val window = (election as? LeaderElection.EpochClaim)?.window ?: return
        if ((misses[logicalId] ?: 0) < window.observations) return
        val mark = registry.instances.leaderOf(logicalId) ?: return
        val replicas = registry.instances.replicasOf(logicalId)
        val local = localReplicas[logicalId].orEmpty().map { it.cell.ref }.filter { it in replicas }
        val reachable = replicas - local.toSet()
        if (local.isEmpty() || reachable.isEmpty()) return
        val selfRef = local.minByOrNull { it.instanceId } ?: return
        // f7h.4-D3: the folded mark's epoch is the only epoch this engine has
        // ever seen — there is no separate maxSeenEpoch. f7h.4-D4: the SAME
        // fold [designateLeader] uses; no second write path exists.
        registry.markLeader(LeaderMark(logicalId, mark.epoch + 1, selfRef))
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
        localReplicas.getOrPut(cell.ref.id) { mutableListOf() } += Local(cell, host)
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
     * single subscriber's body, run once per adopted fold (see `init`).
     *
     * **Two passes, demotions before the promotion** (f7h.3-D3). The order is
     * load-bearing twice over:
     *
     * - Within a demoted ex-leader: unlink → [SingleWriterReplicable.becomeFollower]
     *   → [onStepDown]. Unlinking first means nothing the ex-leader emits
     *   during its own demotion can still reach a follower ([MEM1-15]); the
     *   `becomeFollower` call is the SAME single call a non-leading local
     *   gets, not an extra one, so the exactly-once role-call accounting
     *   ([MEM1-08], pinned by [LeaderMarkFoldTest]) is unchanged.
     * - Between passes: the loser's links are gone before the winner's are
     *   formed, whatever order `localReplicas` happens to hold — a
     *   three-replica set can visit the new leader before the ex-leader in a
     *   single pass, so relying on list order would leave the outcome
     *   spawn-order dependent.
     *
     * The between-pass half is **defensive, and not currently observable**:
     * measured on computenet-f7h.3.2's branch and re-measured in its review,
     * moving the promotion pass ahead of the demotion pass leaves the WHOLE
     * of `:kernel:test` green (1510 tests), not merely `StepDownTest`,
     * `LeaderMarkFoldTest` and `ShippingLinkIdempotenceTest` — because the
     * two passes touch disjoint key sets today: the demotion removes only
     * links SOURCED at the ex-leader and the promotion adds only links
     * sourced at the winner. Nor does the emission order rescue it:
     * promote-first EMITS the winner's baseline while the ex-leader is still
     * marked leading, but that catch-up crosses `registry.deliver` and is
     * applied only when the scheduler drains, by which time `applyRoles` has
     * returned and the demotion has run. So the swap is not an unasserted
     * behavioural difference — it has no observable consequence at all under
     * today's `shipTo`. The order is kept because it is the one that is
     * correct under a future
     * `shipTo` whose teardown and construction can collide (and under
     * f7h.3-D3), not because a test would catch losing it. The
     * WITHIN-demotion order — unlink before `becomeFollower` — *is* pinned:
     * dropping the unlink turns `StepDownTest`'s stale-emission control red
     * (b's `receivedDeltas` 2 → 3).
     *
     * Which local WAS leading comes from [applied], not from the hook, which
     * carries only the new mark.
     */
    private fun applyRoles(mark: LeaderMark) {
        val previous = applied[mark.logicalId]
        val locals = localReplicas[mark.logicalId]

        // Pass 1 — demotions.
        locals?.toList()?.forEach { local ->
            if (local.cell.ref == mark.leaderRef) return@forEach
            val steppingDown = previous != null && previous.leaderRef == local.cell.ref
            if (steppingDown) {
                // [MEM1-15]: every outbound shipping link this replica holds as
                // SOURCE is unlinked AND dropped from bookkeeping. `unlink()` on
                // a `streamTo`-built link unsubscribes at the outlet and drops
                // the source-side LinkSupport record (T21), so the ex-leader's
                // deltaOutlet genuinely has no consumer for that target
                // afterwards. Until F3 nothing tore these down: only
                // `onUnpublish` unlinked, and only by TARGET.
                shipped.keys.filter { it.first == local.cell.ref }.toList()
                    .forEach { key -> shipped.remove(key)?.unlink() }
            }
            local.cell.becomeFollower(mark.leaderRef, mark.epoch, registry)
            if (steppingDown) {
                // f7h.5-D4, and the ORDER is the contract: unlink shipping →
                // becomeFollower → surface → notifyStepDown. Surfacing after
                // `becomeFollower` means a handler that re-applies through the
                // ordinary write API reaches the WINNER; surfacing before
                // `notifyStepDown` means an `onStepDown` listener observes a
                // dead-letter count that already includes these records.
                surfaceDivergentWrites(local, mark)
                notifyStepDown(StepDown(mark.logicalId, previous!!, mark, local.cell))
            }
        }

        // Pass 2 — the promotion, and the winner's outbound links. The
        // winner→ex-leader link formed here is what carries T1's baseline back
        // to the replica that just stepped down.
        locals?.toList()?.firstOrNull { it.cell.ref == mark.leaderRef }?.let { local ->
            local.cell.becomeLeader(mark.epoch)
            registry.instances.replicasOf(mark.logicalId).filter { it != mark.leaderRef }
                .forEach { follower -> shipTo(local.cell, follower) }
        }

        applied[mark.logicalId] = mark
    }

    /**
     * Release the writes parked at a **superseded** leaderRef onto the winner
     * (f7h.5-D2, [MEM1-16]). Run once per adopted mark whose `leaderRef`
     * differs from the previously applied one, immediately after
     * [applyRoles] — so the winner has already been promoted locally (if it is
     * local) by the time anything is re-addressed to it.
     *
     * [LocationRegistry.deliver] parks an invocation whose target ref has no
     * location — which is exactly what a partition leaves behind on a
     * follower's registry: `unpublishRemotes` removes the far leader, and the
     * writes this follower command-forwarded sit in `parkedFor(oldRef)` with
     * no trigger that would ever move them to a DIFFERENT ref. (The registry's
     * own `install` drain releases them into whatever location `oldRef` next
     * gets, which is correct for a RESTART preserving `instanceId` and useless
     * for a new leader.) So: drain, and re-address the *writes* — in park
     * order, and once each, because [LocationRegistry.unpark] empties the
     * queue as it reads it.
     *
     * **Only command-forwarded write ports are re-addressed.** A parked
     * invocation whose port is not in [LocationRegistry.forwardedPorts] for
     * this logical id — a delta shipment, a management call, anything aimed at
     * the old *instance* rather than at whoever leads — is redelivered
     * unchanged, which re-parks it at [oldRef] when that ref still has no
     * location. Re-addressing those would send a unit meant for one replica to
     * a different one.
     *
     * ## Why there is no `leaderRef ∈ replicasOf(id)` gate
     *
     * f7h.5's D2 text gated the release on the winner already being published
     * here. It is dropped deliberately: a mark can be adopted for a ref this
     * registry has not (re)published yet, and D2 names no later trigger that
     * would retry — the writes would stay at the dead ref forever. Releasing
     * unconditionally is safe by construction, because `deliver` to a ref with
     * no location parks AT THE NEW REF, where the registry's ordinary `install`
     * drain delivers it the moment that ref publishes; and if a higher mark
     * supersedes first, this same rule runs again from that ref.
     *
     * "Epoch-confirmed" is therefore rendered as two weaker facts rather than a
     * pre-check: [mark] is the fold's local maximum at release time (the fold
     * guarantees it), and every delivery is epoch-fenced at apply — 93 I-25
     * §4.6's "the epoch fence makes a mis-timed release inert". A release that
     * races a further supersession can land at a replica that is no longer
     * leading; what it cannot do is apply below the canonical epoch. No
     * stronger guarantee is claimed here, and F7 carries its absence.
     *
     * **Two engines on one registry** (testkit's `SingleWriterChurnTest`
     * builds this) both subscribe to the one fold and both run this rule: the
     * first [LocationRegistry.unpark] drains the queue and the second finds it
     * empty, so the release is idempotent by construction rather than by
     * agreement between the engines.
     */
    private fun releaseParked(oldRef: CellRef, mark: LeaderMark) {
        val drained = registry.unpark(oldRef)
        if (drained.isEmpty()) return
        val writePorts = registry.forwardedPorts(mark.logicalId)
        drained.forEach { parked ->
            val isForwardedWrite = parked.type == HostedPortInvocation.Type.PORT_API &&
                parked.portName in writePorts
            registry.deliver(if (isForwardedWrite) parked.copy(cellRef = mark.leaderRef) else parked)
        }
    }

    /**
     * f7h.5-D3 arming: [ref] has left the membership index. Record it against
     * every local replica of the same logical id that this engine believes is
     * currently leading — `applied[id]?.leaderRef` is the engine's own view of
     * who leads, the same source [applyRoles] steps down from — and subscribe
     * the divergence tap on that leader's own delta outlet.
     *
     * ## The tap exists only while the leader is armed
     *
     * f7h.5-D3's text put the tap on at PROMOTION and took it off at
     * step-down. That is a permanent second attachment on every leader's
     * `deltaOutlet`, and it breaks an invariant this engine already pins:
     * `ShippingLinkIdempotenceTest` reads `deltaOutlet`'s raw consumer set and
     * `linking.links` and requires **exactly one** — the shipping link — at a
     * leader in steady state (measured: both of its examples fail 1 vs 2 with a
     * promotion-time tap). Nothing about the divergence record needs the tap
     * outside the armed window: while [Divergence.departed] is empty the sink
     * discards everything it sees. So the tap is installed on the first
     * departure and dropped again by [disarmDivergence] when the last one
     * returns — invisible in every steady state, and identical inside the
     * window. The BUFFER's lifetime is unchanged and independent: it is
     * retained across a disarm and cleared only by the flush ([Divergence]).
     *
     * ## Two properties the sink depends on
     *
     * - **The tap ignores baselines.** [SingleWriterReplicable]'s catch-up
     *   contract emits the leader's WHOLE state as a `baseline` unit whenever a
     *   link forms while leading — including on this very link, the instant it
     *   is installed, which is now inside the armed window. Without the guard
     *   the tap records the leader's entire state as a "divergent write" on
     *   every arming.
     * - **The tap records only while [Divergence.departed] is non-empty**, so a
     *   delta produced between a return and the next departure is not
     *   divergent, and a leader that never witnessed a departure has no tap at
     *   all.
     *
     * Note that the closing edge of that window is now defended **twice** — by
     * the gate above and by [disarmDivergence] unlinking the tap — and neither
     * alone mutates to a red test, because each covers for the other. What is
     * pinned (measured, computenet-f7h.5.2) is the window itself: removing BOTH
     * reddens `DivergentWriteSurfacingTest`'s "a write made after the departed
     * member returned is not divergent".
     *
     * The opening edge is doubly defended in the same way — the tap's late
     * install AND the same gate — and the consequence is worth stating plainly,
     * because it bounds what this task's own suite proves. Re-measured in
     * review: restoring D3's prescribed lifetime *faithfully* (tap installed at
     * promotion, gate and `!baseline` guard both kept, [disarmDivergence] no
     * longer unlinking) leaves all eight of `DivergentWriteSurfacingTest`
     * GREEN — the two lifetimes are observationally identical on every example
     * here. What that lifetime does redden is `ShippingLinkIdempotenceTest`,
     * both examples, at 1-vs-2 attachments: the permanent second attachment on
     * every leader's `deltaOutlet` is the whole reason the tap is armed-window
     * scoped, and that test is its pin. (Under the prescribed lifetime the
     * `!baseline` guard is dead code — also measured: dropping it there leaves
     * all eight green, where dropping it here reddens seven.)
     *
     * The subscription rides its own `divergence:` [PortRef] namespace, which
     * cannot collide with [shipRef]'s `ship:`, and is never entered in
     * [shipped] — nothing ships anywhere and no follower is involved.
     */
    private fun armDivergence(ref: CellRef) {
        val leading = applied[ref.id]?.leaderRef ?: return
        if (ref == leading) return
        localReplicas[ref.id].orEmpty().filter { it.cell.ref == leading }.forEach { local ->
            val record = divergences.getOrPut(local.cell.ref) { Divergence() }
            record.departed += ref
            if (record.tap == null) {
                val sink = Propagate<Stamped<Any?>> { stamped ->
                    if (record.departed.isNotEmpty() && !stamped.baseline) {
                        record.buffer += stamped.epoch to stamped.delta
                    }
                }
                @Suppress("UNCHECKED_CAST")
                record.tap = (local.cell.deltaOutlet as FanOutlet<Propagate<Stamped<Any?>>>)
                    .streamTo(sink, at = divergenceRef(local.cell.ref))
            }
        }
    }

    /** The divergence tap's stable [PortRef], under its own namespace ([shipRef]'s reasoning). */
    private fun divergenceRef(leader: CellRef): PortRef = PortRef(
        UUID.nameUUIDFromBytes("divergence:${leader.id}:${leader.instanceId}".toByteArray()),
    )

    /**
     * f7h.5-D3 disarming: [ref] is back, so recording stops and the tap comes
     * off once the last departed member has returned ([armDivergence]'s
     * "exactly one attachment in steady state"). The BUFFER is retained —
     * that retention is the refinement of D3, and [Divergence] carries why.
     */
    private fun disarmDivergence(ref: CellRef) {
        localReplicas[ref.id].orEmpty().forEach { local ->
            val record = divergences[local.cell.ref] ?: return@forEach
            record.departed -= ref
            if (record.departed.isEmpty()) {
                record.tap?.unlink()
                record.tap = null
            }
        }
    }

    /**
     * f7h.5-D4: surface every recorded divergent write of a stepping-down
     * [local], once each, then clear the record and drop the tap.
     *
     * Each write goes to two places, in this order: the host's dead-letter
     * outlet (so an application that registered nothing still loses nothing),
     * then every [onDivergentWrite] handler. A throwing handler is caught and
     * reported as its OWN dead letter — it never stops the next handler, the
     * next write, or the step-down.
     */
    private fun surfaceDivergentWrites(local: Local, mark: LeaderMark) {
        val record = divergences[local.cell.ref] ?: return
        record.buffer.toList().forEach { (epoch, delta) ->
            // The fold's total order guarantees it; assert rather than filter,
            // so a future path that buffers an at-or-above-epoch delta is a
            // loud failure and not a silently dropped record.
            check(epoch < mark.epoch) {
                "divergent write for ${mark.logicalId} stamped at epoch $epoch is not below the " +
                    "superseding mark's epoch ${mark.epoch} — the fold's total order was violated"
            }
            val invocation = HostedPortInvocation(
                cellRef = local.cell.ref,
                portName = "deltaInlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation(
                    methodName = "propagate",
                    parameterTypes = listOf(Stamped::class.java.name),
                    args = listOf(Stamped(epoch, delta)),
                    context = null,
                ),
            )
            local.host.deadLetterDivergent(
                cause = null,
                description = "single-writer divergent write: id=${mark.logicalId} " +
                    "fencedEpoch=$epoch canonicalEpoch=${mark.epoch}",
                invocation = invocation,
            )
            divergentWriteHandlers.forEach { handler ->
                try {
                    handler(mark.logicalId, epoch, mark.epoch, delta)
                } catch (e: Exception) {
                    System.err.println(
                        "[SingleWriterReplication] divergent-write hook failed for ${mark.logicalId}: $e",
                    )
                    local.host.deadLetterDivergent(
                        cause = e,
                        description = "divergent-write handler failed: $e",
                        invocation = invocation,
                    )
                }
            }
        }
        record.buffer.clear()
        record.departed.clear()
        record.tap?.unlink()
        record.tap = null
        divergences.remove(local.cell.ref)
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
        shipped[key] = (leader.deltaOutlet as FanOutlet<Propagate<Stamped<Any?>>>)
            .streamTo(sink, at = shipRef(leader.ref, followerRef))
    }

    /**
     * The stable identity of the shipping subscription `leader → follower`
     * carried on the leader's delta outlet (f7h.3-D4, closing the epic §3.2
     * open question "`shipTo` has no derived `PortRef`").
     *
     * `streamTo`'s default is `PortRef.generate()` — a fresh random ref per
     * call — so a rebuilt link ADDS a second consumer at the outlet wherever a
     * teardown did not happen to run first, and a single-writer follower's
     * apply is explicitly not idempotent. Deriving the ref from the pair makes
     * `consumers[ref] = port` REPLACE a stale attachment instead of joining
     * it, so re-linking is idempotent at the outlet itself, independent of
     * which teardown route (unpublish reconciliation, step-down unlink, none
     * at all) ran — the same self-healing property, and the same derivation
     * idiom, as [Replication]'s `gossipRef`, under its own `"ship:"` namespace
     * so the two can never collide.
     */
    private fun shipRef(leader: CellRef, follower: CellRef): PortRef = PortRef(
        UUID.nameUUIDFromBytes(
            "ship:${leader.id}:${leader.instanceId}:${follower.id}:${follower.instanceId}".toByteArray(),
        ),
    )

    /**
     * How many leader→follower shipping links exist among [refs] (T07 finding
     * 1 test seam, mirroring [Replication.linkCountAmong]): used to pin
     * finding 1's Divergence B fix — the count drops to 0 on a follower's
     * unpublish and rebuilds to 1 on its re-announce, rather than leaving a
     * stale entry forever.
     */
    internal fun shipCountAmong(refs: Set<CellRef>): Int = shippedPairs(refs).size

    /**
     * The shipping links among [refs] themselves, not just how many
     * ([shipCountAmong]'s companion seam, f7h.3). [MEM1-18]'s steady-state
     * half is a statement about the SOURCE of every surviving link — "every
     * link's first element is the current leaderRef" — which a bare count
     * cannot express: 2 links is the right count both when they run
     * `B→A, B→C` and when they run `B→C, A→C`.
     */
    internal fun shippedPairs(refs: Set<CellRef>): Set<Pair<CellRef, CellRef>> =
        shipped.keys.filter { it.first in refs && it.second in refs }.toSet()

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
