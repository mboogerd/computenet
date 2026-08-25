package civictech.testkit.dst.churn

import civictech.cell.CellRef
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import java.util.UUID
import java.util.WeakHashMap

/**
 * The subscription reading of one replica, on **both** observables ([CHA3-20]).
 *
 * Two counts rather than one because they are two different records of the same attachment and
 * a leak can show in either: [consumers] is the live fan-out map the emit path actually walks,
 * [links] is the port's [civictech.cell.link.LinkSupport] bookkeeping. `GossipLinkIdempotenceTest`
 * asserts both for exactly this reason — a re-`streamTo` that installed a second consumer beside
 * an orphaned first one, and a `LinkSupport.active` keyed by a random `Link.id` that kept a
 * record per attachment rather than per attachment-target, are separate defects.
 *
 * @property peer the declared peer name.
 * @property consumers live consumer attachments on the replica's delta outlet.
 * @property links registered links on that outlet's `LinkSupport`.
 * @property liveMembership `replicasOf(dataId) − {self}` as **this peer's own registry** sees it
 *   — the bound [CHA3-20] states, and the same set [civictech.cell.replication.Replication.evict]
 *   gates on. Per-peer rather than global on purpose: peers MAY transiently disagree about
 *   membership (spec 93 I-3), and a global "live membership" would elect one peer's opinion.
 * @property gossipConsumers of [consumers], those whose [PortRef] is the derived gossip ref of
 *   this replica paired with some *declared* mesh peer's ref. See [GossipInstruments.gossipRefFor].
 * @property staleConsumers of [gossipConsumers], those paired with a ref this peer's registry no
 *   longer lists — an outbound subscription to a departed replica.
 * @property unattributedConsumers consumers that are neither: not a gossip link to a declared
 *   peer. Non-zero means something other than the linker subscribed to this outlet (a
 *   [MeshConvergences] observation is the one in this package), and every bound below is then
 *   reported against [gossipConsumers] rather than [consumers].
 */
data class SubscriptionReading(
    val peer: String,
    val consumers: Int,
    val links: Int,
    val liveMembership: Int,
    val gossipConsumers: Set<CellRef>,
    val staleConsumers: Set<CellRef>,
    val unattributedConsumers: Int,
) {
    /** Outbound gossip subscriptions in excess of live membership — the leak [CHA3-20] bounds. */
    val excess: Int get() = gossipConsumers.size - liveMembership

    fun summary(): String =
        "$peer: consumers=$consumers (gossip=${gossipConsumers.size}, stale=${staleConsumers.size}, " +
            "unattributed=$unattributedConsumers) links=$links liveMembership=$liveMembership"
}

/**
 * What one replica did with the deltas that reached it ([CHA3-22], [CHA3-24]).
 *
 * ## Why "effective" is the emission count and not a delivery count
 *
 * `Replicable`'s contract is that [civictech.cell.data.Replicable.outlet] carries its
 * **effective**-delta stream: a remote delta whose information the receiver already holds is
 * absorbed and re-emits nothing — that is the echo-termination seam the whole mesh rests on.
 * So counting emissions at the outlet counts exactly the deltas that carried new information,
 * which is the quantity a duplicate must leave unchanged. Deliveries are *not* counted: the
 * receiving side is a `Use` port with no observation seam, and manufacturing one would be a
 * kernel `main` edit ([CHA3-82] forbids it). How many duplicates were *injected* is read off the
 * `DuplicateFault`'s own fired count instead, which is where the rig already records it.
 *
 * @property peer the declared peer name.
 * @property effectiveDeltas emissions observed on this replica's outlet since [GossipObservation]
 *   attached to it. **Local writes emit too** — an effective delta is an effective delta whoever
 *   originated it — so a window in which the peer writes nothing counts absorptions only.
 * @property attachedAtStep the step the observation attached, or -1 if it attached before the
 *   run's first sampled step.
 */
data class DeltaReading(
    val peer: String,
    val effectiveDeltas: Int,
    val attachedAtStep: Int,
)

/**
 * How far a mid-run joiner was from the mesh fold, and how many effective deltas it took to get
 * there ([CHA3-23]).
 *
 * @property peer the joiner.
 * @property spawnedAtStep the step this replica was first seen live.
 * @property convergedAtStep the first sampled step at which this peer's fold equalled the fold
 *   of every other live member, or -1 if it never did within the sampled run.
 * @property hops effective deltas absorbed by the joiner between [spawnedAtStep] and
 *   [convergedAtStep] — one per delta that carried information it did not have. See
 *   [GossipObservation.hopTraces] for what this is and is not evidence of.
 * @property peersThatHadLearned how many other replicas this peer's registry listed at
 *   [spawnedAtStep] — the candidate bound "one hop per peer that had learned the joiner".
 */
data class HopTrace(
    val peer: String,
    val spawnedAtStep: Int,
    val convergedAtStep: Int,
    val hops: Int,
    val peersThatHadLearned: Int,
) {
    val converged: Boolean get() = convergedAtStep >= 0

    fun summary(): String =
        "$peer: spawned@$spawnedAtStep converged@$convergedAtStep hops=$hops " +
            "peersThatHadLearned=$peersThatHadLearned"
}

/**
 * The four per-run measured quantities [CHA3-25] asks a churn run to **report** — not merely to
 * assert: "bounded" is only a claim about a system if the bound is a number somebody measured.
 *
 * @property subscriptions per-replica subscription counts on both observables ([CHA3-20]).
 * @property deltas per-replica effective-delta counts ([CHA3-22], [CHA3-24]).
 * @property staleLinks total outbound subscriptions to refs no longer in the subscriber's
 *   directory, summed over live replicas.
 * @property hops per-joiner hop traces ([CHA3-23]).
 */
data class GossipInstrumentReport(
    val subscriptions: List<SubscriptionReading>,
    val deltas: List<DeltaReading>,
    val staleLinks: Int,
    val hops: List<HopTrace>,
) {
    /** Reporting only — it embeds run-varying counts. Never a check's message. */
    fun summary(): String = buildString {
        append("gossip instruments: staleLinks=$staleLinks")
        append("; subscriptions=[").append(subscriptions.joinToString("; ") { it.summary() }).append("]")
        append("; effectiveDeltas=").append(deltas.associate { it.peer to it.effectiveDeltas })
        if (hops.isNotEmpty()) append("; hops=[").append(hops.joinToString("; ") { it.summary() }).append("]")
    }
}

/**
 * Bounded-gossip instrumentation over a churn mesh ([CHA3-20]–[CHA3-25], [CHA3-43]).
 *
 * ## What this reads, and why it reads it from outside
 *
 * The kernel-side test this generalises — `civictech.cell.replication.GossipLinkIdempotenceTest`
 * — asserts the one-linker invariant three ways: the outlet's consumer map (read reflectively,
 * because `FanOutlet.consumers` is deliberately private hot-path state), the port's
 * `LinkSupport.links`, and `Replication.linkCountAmong`. The first two are reachable from any
 * module and this file reads both. **The third is not**: `linkCountAmong` is `internal` to
 * `:kernel` (verified at `Replication.kt:389`), so it is callable from `:kernel`'s own tests and
 * nowhere else, and widening it is a kernel `main` edit [CHA3-82] forbids.
 *
 * The substitution is *not* `reachablePeers()`, which is a directory reading and says nothing
 * about links: it is [gossipRefFor], which re-derives the linker's own gossip [PortRef] from the
 * `(local, remote)` pair. That derivation is the property `linkCountAmong` measures — one link
 * per overlapping instance pair — expressed at the port instead of at the linker's private
 * `linked` map, so a consumer set can be *attributed* to remote replicas rather than merely
 * counted. It is a duplicate of a private kernel expression and is therefore pinned by
 * [GossipInstruments] its own test (`the derived gossip ref attributes every live consumer`):
 * if the kernel changes the derivation, that test goes red rather than the instruments silently
 * reporting every link as unattributed.
 *
 * ## Attachment
 *
 * [GossipObservation.sample] must be driven from a step hook, because a replica exists only once
 * its peer has joined and this file may not edit [MeshPeer] to add a spawn hook (another task's
 * claim). Sampling attaches an outlet *observer* — a tap, uncounted by the SPSC funnel and
 * absent from the consumer map, so the instrument does not perturb the quantity it measures —
 * to any replica that does not have one yet, and records the fold reading for that step. The
 * cost is one step of latency between a spawn and its observation, which is why
 * [DeltaReading.attachedAtStep] is reported rather than assumed to be the spawn step.
 *
 * A run that also arms [MeshConvergences] subscribes a fold to every replica's outlet; whether
 * that lands in the consumer map is the reconvergence task's business, and this file does not
 * assume either way — it attributes consumers by derived gossip ref and reports the remainder as
 * [SubscriptionReading.unattributedConsumers], so a bound is never quietly computed over a
 * consumer this file cannot name.
 */
object GossipInstruments {

    private val byWorld = WeakHashMap<DstWorld, GossipObservation>()

    /** Declare the observation for [world]. Idempotent; returns the one observation. */
    @Synchronized
    fun armOn(world: DstWorld): GossipObservation = byWorld.getOrPut(world) { GossipObservation(world) }

    /** The observation declared on [world], or a loud failure naming the remedy. */
    @Synchronized
    fun of(world: DstWorld): GossipObservation = byWorld[world] ?: throw IllegalStateException(
        "no gossip observation was declared on this world — GossipInstruments.armOn(world) from a " +
            "step hook is what declares it",
    )

    /**
     * The [PortRef] the gossip linker installs on [local]'s delta outlet for the link to
     * [other].
     *
     * A deliberate duplicate of `Replication.gossipRef`, which is private. See the class KDoc for
     * why this is the honest substitute for the `internal` `linkCountAmong` and what pins it.
     */
    fun gossipRefFor(local: CellRef, other: CellRef): PortRef = PortRef(
        UUID.nameUUIDFromBytes(
            "gossip:${local.id}:${local.instanceId}:${other.id}:${other.instanceId}".toByteArray(),
        ),
    )

    /**
     * Live consumer attachments on [outlet]. `FanOutlet.consumers` is private — deliberately, it
     * is the fan-out hot path — and no public projection counts it, so the probe reads it
     * reflectively exactly as `GossipLinkIdempotenceTest.consumerRefs` does rather than widening
     * the port API. Taps (and therefore this file's own observers) live in a separate map and
     * are not counted here.
     */
    @Suppress("UNCHECKED_CAST")
    fun consumerRefs(outlet: FanOutlet<*>): Set<PortRef> {
        val field = FanOutlet::class.java.getDeclaredField("consumers").apply { isAccessible = true }
        return (field.get(outlet) as Map<PortRef, *>).keys.toSet()
    }

    // --------------------------------------------------------------------------------- checks

    /**
     * [CHA3-20]: no live replica holds more outbound gossip subscriptions than its own registry
     * lists live peers, on **both** observables.
     *
     * The `links` arm is asserted against the same bound rather than against the consumer count:
     * the two records are independent, and asserting one against the other would pass a run in
     * which both leaked together.
     */
    fun subscriptionsBoundedByMembership(): DstCheck = DstCheck { world ->
        val readings = of(world).subscriptions()
        val over = readings.filter { it.excess > 0 || it.links > it.liveMembership }
        if (over.isNotEmpty()) {
            throw ChurnCheckFailure(
                SUBSCRIPTIONS_EXCEED_MEMBERSHIP,
                detail = "over-subscribed replicas: ${over.map { it.summary() }}; " +
                    "all: ${readings.map { it.summary() }}",
            )
        }
    }

    /**
     * [CHA3-21]: no orphaned subscription to a departed replica survives on a live one, on
     * either observable.
     *
     * Distinct from [subscriptionsBoundedByMembership] and not implied by it: a peer that
     * departed while another joined keeps the count right and the *target* wrong.
     */
    fun noOrphanedSubscriptions(): DstCheck = DstCheck { world ->
        val readings = of(world).subscriptions()
        val orphaned = readings.filter { it.staleConsumers.isNotEmpty() }
        if (orphaned.isNotEmpty()) {
            throw ChurnCheckFailure(
                ORPHANED_SUBSCRIPTION,
                detail = "replicas holding a subscription to a departed replica: " +
                    orphaned.joinToString("; ") { "${it.peer} -> ${it.staleConsumers}" },
            )
        }
    }

    /** Both subscription checks, membership bound first. */
    fun checks(): DstCheck = DstCheck { world ->
        subscriptionsBoundedByMembership().verify(world)
        noOrphanedSubscriptions().verify(world)
    }

    /** Fixed failure identities. See [ChurnCheckFailure] for why the numbers are not in them. */
    const val SUBSCRIPTIONS_EXCEED_MEMBERSHIP: String =
        "a replica holds more outbound gossip subscriptions than its registry lists live peers"
    const val ORPHANED_SUBSCRIPTION: String =
        "a live replica still holds an outbound gossip subscription to a departed replica"
    const val REJOIN_INCREASED_SUBSCRIPTIONS: String =
        "a depart/rejoin cycle increased a replica's outbound gossip subscription count"
    const val DUPLICATE_CHANGED_EFFECTIVE_DELTAS: String =
        "re-delivering a delta the receiver already holds changed its effective-delta count"
}

/**
 * The per-run gossip observation. See [GossipInstruments].
 *
 * Not thread-safe by design: the rig drives one `SimulationController` from the calling thread,
 * and every method here is called from a step hook or from a check, both of which run there.
 */
class GossipObservation internal constructor(private val world: DstWorld) {

    private val effective = linkedMapOf<String, Int>()
    private val attachedAt = linkedMapOf<String, Int>()
    private val spawnedAt = linkedMapOf<String, Int>()
    private val convergedAt = linkedMapOf<String, Int>()
    private val effectiveAtSpawn = linkedMapOf<String, Int>()
    private val learnedAtSpawn = linkedMapOf<String, Int>()
    private val observed = mutableSetOf<Any>()
    private var lastStep = -1

    /**
     * Attach to any replica that has spawned since the last call, and record this step's fold
     * reading. Idempotent per replica *instance*: a rejoin mints a new cell object behind the
     * same [CellRef], and that new instance is attached to and re-timed as a fresh joiner —
     * which is what makes a rejoin's hop count a hop count rather than a continuation.
     */
    fun sample(step: Int) {
        lastStep = step
        MeshPeers.all(world).forEach { peer ->
            val cell = peer.replica
            if (cell == null) {
                // Departed: forget the convergence timing so a rejoin is timed from its rejoin.
                convergedAt.remove(peer.name)
                return@forEach
            }
            if (observed.add(cell)) {
                attachedAt[peer.name] = step
                spawnedAt[peer.name] = step
                effectiveAtSpawn[peer.name] = effective[peer.name] ?: 0
                learnedAtSpawn[peer.name] = peer.reachablePeers()
                convergedAt.remove(peer.name)
                (cell.outlet as FanOutlet<*>).observe(
                    GossipInstruments.gossipRefFor(peer.ref, CellRef(OBSERVER_ID, peer.index.toLong())),
                ) { effective[peer.name] = (effective[peer.name] ?: 0) + 1 }
            }
        }
        recordConvergence(step)
    }

    private fun recordConvergence(step: Int) {
        val live = MeshPeers.all(world).filter { it.replica != null }
        if (live.size < 2) return
        live.forEach { peer ->
            if (convergedAt.containsKey(peer.name)) return@forEach
            val mine = peer.foldSnapshot()
            if (live.all { it.name == peer.name || it.foldSnapshot() == mine }) convergedAt[peer.name] = step
        }
    }

    // ---------------------------------------------------------------------------- observables

    /** Per-replica subscription readings over the currently live replicas ([CHA3-20]). */
    fun subscriptions(): List<SubscriptionReading> {
        val peers = MeshPeers.all(world)
        return peers.mapNotNull { peer ->
            val cell = peer.replica ?: return@mapNotNull null
            val outlet = cell.outlet as FanOutlet<*>
            val consumers = GossipInstruments.consumerRefs(outlet)
            val visible = peer.visibleReplicas()
            val attributed = linkedMapOf<PortRef, CellRef>()
            peers.forEach { other ->
                if (other.ref != peer.ref) attributed[GossipInstruments.gossipRefFor(peer.ref, other.ref)] = other.ref
            }
            val gossip = consumers.mapNotNull { attributed[it] }.toSet()
            SubscriptionReading(
                peer = peer.name,
                consumers = consumers.size,
                links = outlet.linking.links.size,
                liveMembership = (visible - peer.ref).size,
                gossipConsumers = gossip,
                staleConsumers = gossip.filterNot { it in visible }.toSet(),
                unattributedConsumers = consumers.count { it !in attributed },
            )
        }
    }

    /** Per-replica effective-delta counts ([CHA3-22], [CHA3-24]). */
    fun deltas(): List<DeltaReading> = MeshPeers.all(world).map { peer ->
        DeltaReading(peer.name, effective[peer.name] ?: 0, attachedAt[peer.name] ?: -1)
    }

    /** Effective deltas absorbed/emitted by [peer] since the observation attached to it. */
    fun effectiveDeltas(peer: String): Int = effective[peer] ?: 0

    /**
     * The current fold of every live replica, read directly off [MeshPeer.foldSnapshot].
     *
     * Direct rather than through [MeshConvergences] because these suites deliberately do not arm
     * the reconvergence observation — it subscribes to the very outlet whose subscriber set they
     * measure. Departed replicas are absent rather than null-valued: a departed fold is frozen,
     * not a reading.
     */
    fun foldsByPeer(): Map<String, Any?> =
        MeshPeers.all(world).filter { it.replica != null }.associate { it.name to it.foldSnapshot() }

    /** Total outbound subscriptions to refs their subscriber's registry no longer lists. */
    fun staleLinks(): Int = subscriptions().sumOf { it.staleConsumers.size }

    /**
     * Per-joiner hop traces ([CHA3-23]).
     *
     * ## What a "hop" is here, and what the number is and is not evidence of
     *
     * A hop is one **effective** delta absorbed by the joiner: a delivery that carried
     * information it did not already hold and therefore re-emitted on its own outlet. Deliveries
     * that carry nothing new are not hops — they are the duplicate-absorption case
     * ([CHA3-22]/[CHA3-24]) and are invisible here by construction.
     *
     * The number is **not** a graph distance. The feature's own risk 1 (umx.2-D7) records why
     * "diameter 1" is wrong mid-plan: during churn the effective topology is transiently not a
     * full mesh, so a joiner may be reachable from a subset of members and learn through them.
     * And it counts a *local* write's emission too, so a trace is only about absorption for a
     * joiner that does not write in its own catch-up window — which is how the tests place it.
     */
    fun hopTraces(): List<HopTrace> = MeshPeers.all(world).mapNotNull { peer ->
        val spawned = spawnedAt[peer.name] ?: return@mapNotNull null
        val converged = convergedAt[peer.name] ?: -1
        HopTrace(
            peer = peer.name,
            spawnedAtStep = spawned,
            convergedAtStep = converged,
            hops = (effective[peer.name] ?: 0) - (effectiveAtSpawn[peer.name] ?: 0),
            peersThatHadLearned = learnedAtSpawn[peer.name] ?: 0,
        )
    }

    /** The trace for one joiner, or null if it never spawned within the sampled run. */
    fun hopTrace(peer: String): HopTrace? = hopTraces().firstOrNull { it.peer == peer }

    /** The four quantities [CHA3-25] asks every churn run to report. */
    fun report(): GossipInstrumentReport = GossipInstrumentReport(
        subscriptions = subscriptions(),
        deltas = deltas(),
        staleLinks = staleLinks(),
        hops = hopTraces(),
    )

    /** The last step [sample] saw; -1 if it was never driven. */
    fun sampledUntil(): Int = lastStep

    private companion object {
        /**
         * Namespace for the instrument's own observer [PortRef]s. Derived, never
         * `PortRef.generate()`: a random ref per run would be entropy on a path the rig requires
         * to be seed-determined.
         */
        val OBSERVER_ID: UUID = UUID.nameUUIDFromBytes("churn-gossip-instruments-observer".toByteArray())
    }
}
