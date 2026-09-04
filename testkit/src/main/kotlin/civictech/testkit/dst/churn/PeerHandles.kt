package civictech.testkit.dst.churn

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.PnCounterCell
import civictech.cell.data.PnCounterOps
import civictech.cell.data.Replicable
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Interest
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.replication.InstanceSet
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.HostSlot
import civictech.testkit.dst.LinkControl
import civictech.testkit.dst.PeerHandle
import civictech.testkit.dst.PeerHandles
import java.util.UUID
import java.util.WeakHashMap

/**
 * What the mesh replicates ([CHA3-03]'s departures are the same four either way).
 *
 * Two payloads rather than one because the two mergeable families reach [Replication.evict]'s
 * `closeDepartedRow` seam through different state: a tombstoned OR-set carries per-element tag
 * lanes, a PN counter carries a per-source register. A departure that is correct for one and
 * wrong for the other would otherwise be invisible.
 */
enum class MeshPayload {

    /** A replicated [SetCell] of strings; a write is `add("<peer>-<ordinal>")`. */
    SET,

    /** A replicated [PnCounterCell]; a write is `increment(1)`. */
    PN_COUNTER,
}

/** The proxy surface a workload write reaches a [SetCell] replica through. */
interface SetInletProxy {
    val inlet: Use<SetOps<String>>
}

/** The proxy surface a workload write reaches a [PnCounterCell] replica through. */
interface PnCounterInletProxy {
    val inlet: Use<PnCounterOps>
}

/**
 * One peer of the churn mesh: the [PeerHandle] implementation the [civictech.testkit.dst.ChurnEvent]
 * vocabulary resolves by name, plus the observables a check reads.
 *
 * ## Every peer keeps its OWN registry, and that is the whole reason this class exists
 *
 * `DstWorld` backs [DstWorld.hosts] with the one [DstWorld.registry], while every gossip mesh in
 * the kernel's own tests gives each peer its own [LocationRegistry] bridged over a
 * [Peering.Side] — one registry for the whole mesh would make "peer0 can no longer see peer1"
 * unexpressible, because there would be no second directory to lose the mirror from. The rig
 * does not force the world's registry on a host: [civictech.testkit.dst.HostBuildContext] *offers*
 * `ctx.registry`, and the build lambda constructs the [ManagedHost] itself. So this class takes
 * only `ctx.scheduler` from the context and supplies [registry] — verified by
 * `ChurnMeshTest.aCrashedPeerRebuildsOnItsOwnRegistry…`, which is the probe the design was
 * gated on rather than an assumption inherited from the plan.
 *
 * ## What survives a crash, and what does not
 *
 * [registry], [bridgeHost] and [side] survive: they are the peer's durable location directory
 * and its wire attachment, and surviving is precisely their job (the rig's own seam-2 contract
 * says the same of the registry and the journals). [replication] does **not**: the linker's
 * `localReplicas` / `hostOf` / `linked` bookkeeping is in-process host state naming a host that
 * no longer exists, and carrying it across a rebuild leaves a stale replica entry that makes a
 * later [Replication.evict] believe this peer still hosts a replica of the id — which would
 * silently disable the `closeDepartedRow` arm the whole of [DepartureMode.EVICT_CLEAN] rests
 * on. A generation > 0 therefore starts from a fresh [Replication] and **no** replica: a
 * CRASH_UNCLEAN departure is a departure, not a restart, and membership comes back only through
 * an explicit [rejoin].
 *
 * ## Rejoin determinism
 *
 * [ref] is `CellRef(dataId, index)` — derived from the peer's declared index, never from
 * `UUID.randomUUID()`. A rejoining replica therefore reuses the same `CellRef` by construction
 * rather than by remembering to (feature §9 risk 2), and there is no wall-clock entropy
 * anywhere on this path.
 */
class MeshPeer internal constructor(
    /** The declared peer name churn events resolve against ([PeerHandles]). */
    val name: String,
    /** The peer's slot index; also the `instanceId` of every ref it owns. */
    val index: Int,
    private val world: DstWorld,
    private val dataId: UUID,
    private val assignmentId: UUID,
    /** What this mesh replicates — read by the reconvergence observation to pick its fold. */
    val payload: MeshPayload,
    private val totalSlots: Int,
) : PeerHandle {

    /** This peer's own location directory. See the class KDoc. */
    val registry: LocationRegistry = LocationRegistry()

    /**
     * The peer's wire attachment host. Deliberately **not** declared through [DstWorld.hosts]:
     * only a host a crash targets needs the rebuild seam, and a crash that also discarded the
     * bridge would sever the peering rather than crash the peer — a different experiment.
     */
    val bridgeHost: ManagedHost = ManagedHost(scheduler = world.controller.scheduler(), registry = registry)

    /** This peer's side of every loopback the mesh wires it into. */
    val side: Peering.Side = Peering.Side(registry, bridgeHost)

    /** The gossip linker. Replaced on every crash generation — see the class KDoc. */
    var replication: Replication = Replication(registry)
        private set

    /** The crashable host slot ([DepartureMode.CRASH_UNCLEAN] targets this name). */
    lateinit var slot: HostSlot
        private set

    /** This peer's replica ref of the mesh's one logical cell. Stable across rejoins. */
    val ref: CellRef = CellRef(dataId, index.toLong())

    /** This peer's instance of the interest/epoch assignment register. */
    val assignmentRef: CellRef = CellRef(assignmentId, index.toLong())

    /** The live replica, or null while this peer is not a member. */
    var replica: Replicable<*>? = null
        private set

    /** The live assignment register, or null while this peer is not a member. */
    var assignments: InstanceSet? = null
        private set

    /**
     * Whether this peer is currently a member of the mesh (`join`ed and not departed).
     *
     * **Which [DepartureMode] paths clear it** (measured, `computenet-usmw`; the flag surviving
     * a departure is INTENDED, not a defect):
     *
     *  - [DepartureMode.CRASH_UNCLEAN] — **always**. [crash] discards the host slot, whose
     *    rebuild runs [declareHost]'s lambda at `generation > 0` and so [discardHostLocalState].
     *  - [DepartureMode.EVICT_CLEAN] / [DepartureMode.EVICT_NO_CLOSE] — **only when
     *    [Replication.evict] despawns**. Evict returns false when `replicasOf(id) − {local}` is
     *    empty; it then suspends the replica rather than dropping it, and this peer stays a
     *    member with its fold intact. That is BS-9's own pinned reading
     *    (`DepartureGatesTest`: "a refused eviction never despawns — state is retained"), so
     *    clearing the flag there would falsify a landed property, not fix a bug.
     *  - [DepartureMode.PARTITION_SUSPEND] — **never**, by construction. [partitionAway] parks
     *    links and sets [suspended]; the peer is still a member, and [rejoin]'s heal branch is
     *    its return path.
     *
     * The one place this used to bite is the second case, where the plan's own bookkeeping and
     * the harness's disagree — see [rejoin], which reconciles them.
     */
    var member: Boolean = false
        private set

    /** Whether this peer's links are currently parked ([DepartureMode.PARTITION_SUSPEND]). */
    var suspended: Boolean = false
        private set

    /** How this peer last left, or null if it has never departed. */
    var lastDeparture: DepartureMode? = null
        private set

    /**
     * What [Replication.evict] returned on the last eviction: `true` despawned, `false`
     * suspended because no peer was reachable. Null when the last departure was not an
     * eviction. The coarse kernel-visible consequence [CHA3-03] asks a report to carry; the
     * full watermark/stability assertions belong to the "departure gates" task.
     */
    var lastEvictDespawned: Boolean? = null
        private set

    /** Whether the last eviction asked for the departed watermark row to be closed. */
    var lastEvictClosedRow: Boolean? = null
        private set

    private val controls = mutableListOf<LinkControl>()

    private var writeProxy: Any? = null

    // -------------------------------------------------------------------------- declaration

    internal fun declareHost() {
        slot = world.hosts.declare(name) { ctx ->
            // ctx.registry is deliberately unused — see the class KDoc. Only the scheduler is
            // taken from the context, because a fresh scheduler per generation IS the crash.
            if (ctx.generation > 0) discardHostLocalState()
            ManagedHost(scheduler = ctx.scheduler, registry = registry)
        }
    }

    internal fun attach(control: LinkControl) {
        controls += control
    }

    /** Every park primitive that stops traffic between this peer and some other peer. */
    val linkControls: List<LinkControl> get() = controls.toList()

    private fun discardHostLocalState() {
        replication = Replication(registry)
        replica = null
        assignments = null
        writeProxy = null
        member = false
    }

    // ---------------------------------------------------------------------- PeerHandle ops

    override fun join() {
        check(!member) { "peer \"$name\" is already a member, so a join cannot be applied to it" }
        spawn()
    }

    override fun rejoin() {
        if (suspended) {
            // A suspended peer never left: healing its links is the whole return path, and
            // re-replicating would mint a second replica behind the same ref.
            heal()
            return
        }
        if (member && refusedEviction()) {
            // The paired DepartEvent was an eviction the KERNEL refused: `Replication.evict`
            // found `replicasOf(id) − {local}` empty, so it suspended the replica instead of
            // despawning it and returned false — and [evict] below deliberately keeps
            // [member] true, because a refused eviction is not a departure (BS-9,
            // `DepartureGatesTest`: "a refused eviction never despawns — state is retained").
            //
            // A generated plan does not model that refusal: [ChurnGenerator] marks the peer
            // DEPARTED at the moment it emits the DepartEvent and later emits the paired
            // RejoinEvent, so the plan asks a peer that never left to come back. Before
            // `computenet-usmw` this raised `IllegalStateException` from the `check` below and
            // reddened the whole sweep on the seeds that draw the sequence (1 of 50 at
            // `eventCount = 8` on the BS-17 bridge config, 9 of 50 at 12) — an *incoherence
            // between two models*, not a property failure. The honest resolution is a no-op:
            // this peer is already a member with its fold intact, and the kernel's own G-45
            // heal (`Replication.linkOut`) resumes it the moment another replica of the id
            // becomes visible again. Re-spawning would mint a second replica behind one ref.
            return
        }
        check(!member) { "peer \"$name\" is already a member, so a rejoin cannot be applied to it" }
        spawn()
    }

    /**
     * True when the last departure was an eviction [Replication.evict] refused — the one way a
     * [depart] leaves this peer a member without leaving it [suspended]. See [rejoin].
     */
    private fun refusedEviction(): Boolean =
        lastEvictDespawned == false &&
            (lastDeparture == DepartureMode.EVICT_CLEAN || lastDeparture == DepartureMode.EVICT_NO_CLOSE)

    override fun depart(mode: DepartureMode) {
        lastDeparture = mode
        when (mode) {
            DepartureMode.EVICT_CLEAN -> evict(closeDepartedRow = true)
            DepartureMode.EVICT_NO_CLOSE -> evict(closeDepartedRow = false)
            DepartureMode.CRASH_UNCLEAN -> crash()
            DepartureMode.PARTITION_SUSPEND -> partitionAway()
        }
    }

    override fun reassign(interest: String, epoch: Long) {
        val register = assignments
            ?: error("peer \"$name\" holds no assignment register, so a reassignment cannot be applied to it")
        register.assign(ref, interestNamed(interest), epoch)
    }

    // ------------------------------------------------------------------- departure primitives

    /** [DepartureMode.EVICT_CLEAN]. Returns [Replication.evict]'s own verdict. */
    fun evictClean(): Boolean = evict(closeDepartedRow = true)

    /** [DepartureMode.EVICT_NO_CLOSE] — the PN-0c control seam. */
    fun evictNoClose(): Boolean = evict(closeDepartedRow = false)

    private fun evict(closeDepartedRow: Boolean): Boolean {
        val cell = replica ?: error("peer \"$name\" holds no replica, so an eviction cannot be applied to it")
        val despawned = replication.evict(cell, slot.host, closeDepartedRow)
        lastEvictDespawned = despawned
        lastEvictClosedRow = closeDepartedRow
        if (despawned) {
            replica = null
            writeProxy = null
            member = false
        }
        return despawned
    }

    /** [DepartureMode.CRASH_UNCLEAN]: discard the host, rebuild it empty. */
    fun crash() {
        check(::slot.isInitialized) { "peer \"$name\" has no declared host slot" }
        slot.crash()
    }

    /**
     * [DepartureMode.PARTITION_SUSPEND]: park every link this peer has, so that nothing in
     * `replicasOf(id) − {local}` is reachable from it any more.
     *
     * Idempotent: a peer already suspended is left alone rather than parked twice, because a
     * [LinkControl.severing] control's park/heal pair is not a counter and a double park
     * followed by one heal would leave the peering severed for the rest of the run — the
     * composition case [CHA3-04] exists to keep honest.
     */
    fun partitionAway() {
        if (suspended) return
        controls.forEach { it.park() }
        suspended = true
    }

    /** Release every parked link. The return path for a [DepartureMode.PARTITION_SUSPEND]. */
    fun heal() {
        if (!suspended) return
        controls.forEach { it.heal() }
        suspended = false
    }

    // ---------------------------------------------------------------- [CHA3-70]/[CHA3-73] control seams

    /**
     * [CHA3-70]'s harness-side control seam, BS-3: park **every** invocation this peer's own
     * [registry] would route to another currently-visible replica, and never release the park —
     * the harness-layer stand-in for "this departing replica's final push-catch-up is lost",
     * decided against editing kernel `main` (feature computenet-umx.2 §4.8, umx.2-D5).
     *
     * ## Why the whole outbound channel, not only the one `fireLinked` call
     *
     * [Replication.evict]'s success arm fires its best-effort catch-up
     * (`linked.entries.firstOrNull { it.key.first == cell.ref }?.let { ... fireLinked(...) }`)
     * on **the exact same routed channel** every ordinary per-write gossip emission already
     * uses: both resolve through `HostedCellProxy.create(other, registry, ...)`, i.e. through
     * THIS peer's own [registry] (`Replication`'s constructor field is this peer's [registry] —
     * see [MeshPeer]'s class KDoc), and `LocationRegistry.deliver` decides hold-vs-send at the
     * moment of the call, synchronously. There is no seam that reaches only the redundant
     * `fireLinked` re-fire without also reaching the ordinary stream it duplicates — pulling one
     * out from `:kernel` `internal` state would be the kernel `main` edit this control is
     * expressly forbidden from making. So the control parks the channel itself, which is honest
     * about the mechanism it stands in for: **whichever of the two emissions would have carried
     * a write this peer accepted immediately before departing, neither does.**
     *
     * ## Window sizing — call this BEFORE the write it must suppress, not only before [evictClean]
     *
     * A caller that suppresses only immediately before evicting, after the interesting write has
     * already been issued and drained (`world.controller.runToIdle()` already called), suppresses
     * nothing observable: ordinary per-write gossip already delivered it, `fireLinked`'s re-fire
     * is idempotent no-op, and the control is **inert** — exactly the CHA1-measured failure mode
     * the feature bead warns about (a window smaller than the traffic it must catch never fires).
     * Call this first, then issue the write the control is meant to lose, then evict — see
     * `ControlSeams.suppressedFinalCatchUp` for the full sequence.
     *
     * Never released: this peer despawns immediately after, issuing nothing further on
     * [registry], so there is nothing left to unpark — the park is permanent by construction,
     * matching "lost", not "delayed".
     */
    fun suppressOutboundDeliveries() {
        check(member) { "peer \"$name\" is not a member, so its outbound deliveries cannot be suppressed" }
        (visibleReplicas() - ref).forEach { registry.hold(it) }
    }

    /**
     * [CHA3-73]'s harness-side control seam: install a **second**, duplicate outbound gossip
     * subscription to [other] alongside the properly re-derived one [Replication] already
     * maintains — the accumulating-rejoin defect T21 fixed (`Replication.gossipRef`'s own KDoc:
     * "a fresh ref per `streamTo` re-link installs a *second* consumer beside the orphaned
     * first"), reproduced deliberately here rather than by editing kernel `main`.
     *
     * Builds the exact routed proxy `Replication.maybeLink` builds
     * (`HostedCellProxy.create(other.ref, registry, Replication.ReplicaDeltaInlet::class.java)`)
     * and subscribes it via the same public [civictech.cell.port.streamTo] extension, but with a
     * **freshly generated** [PortRef] instead of the derived, stable one `Replication`'s own
     * `gossipRef` computes — the one difference that turns an idempotent re-link into a second
     * consumer. `Replication`'s private `linked` map is never touched, so the properly-derived
     * subscription it already installed is left exactly in place: this call *adds* a duplicate,
     * it does not replace anything.
     */
    fun accumulateDuplicateSubscription(other: MeshPeer) {
        val cell = replica ?: error("peer \"$name\" holds no replica; accumulate after it has (re)joined")
        val target = (
            HostedCellProxy.create(other.ref, registry, Replication.ReplicaDeltaInlet::class.java)
                as Replication.ReplicaDeltaInlet
            ).deltaInlet.call
        @Suppress("UNCHECKED_CAST")
        (cell.outlet as FanOutlet<Propagate<Any?>>).streamTo(target, at = PortRef.generate())
    }

    // ------------------------------------------------------------------------------ workload

    /**
     * Issue one workload write. No-op — and reported as such — while this peer is not a member:
     * the plan's write schedule is generated against the roster, not against the membership
     * state at each step, so a write aimed at a departed peer is expected rather than an error.
     *
     * Returns true if the write was issued.
     *
     * **An issued write is recorded as an [AcceptedOp] here, at the issuing site.** That is what
     * makes [BatchReference] an independent reference rather than a restatement of the folds it
     * checks ([CHA3-11]): a reference re-derived from the replicas would agree with them by
     * construction. "Accepted" means applied to this replica's own state — the replica is a
     * member, so its intake is open — and says nothing about whether the delta reached anyone
     * else; which accepted operations the survivors still owe is [ReconvergenceCheck]'s call.
     */
    fun write(ordinal: Int): Boolean {
        if (!member) return false
        when (payload) {
            MeshPayload.SET -> {
                val element = "$name-$ordinal"
                (proxy() as SetInletProxy).inlet.call.add(element)
                AcceptedOps.record(world, AcceptedOp(peer = name, ordinal = ordinal, element = element))
            }

            MeshPayload.PN_COUNTER -> {
                (proxy() as PnCounterInletProxy).inlet.call.increment(1)
                AcceptedOps.record(world, AcceptedOp(peer = name, ordinal = ordinal, increment = 1L))
            }
        }
        return true
    }

    private fun proxy(): Any = writeProxy ?: HostedCellProxy.create(
        ref,
        registry,
        when (payload) {
            MeshPayload.SET -> SetInletProxy::class.java
            MeshPayload.PN_COUNTER -> PnCounterInletProxy::class.java
        },
    ).also { writeProxy = it }

    // --------------------------------------------------------------------------- observables

    /**
     * The peer's converged fold, as a value a check can compare across peers: the sorted
     * membership of a [SetCell], or the total of a [PnCounterCell]. Null while not a member.
     */
    fun foldSnapshot(): Any? = when (val cell = replica) {
        null -> null
        is SetCell<*> -> cell.membership().map { it.toString() }.sorted()
        is PnCounterCell -> cell.total()
        else -> error("unknown payload cell ${cell::class.simpleName}")
    }

    /**
     * How many *other* replicas of the mesh's logical cell this peer can currently see —
     * `replicasOf(id) − {local}`, the exact set [Replication.evict] gates on.
     *
     * This is the observable that replaces the `linkCountAmong` one a plan might reach for:
     * `Replication.linkCountAmong` is `internal` to `:kernel` (verified at
     * `Replication.kt:389`), so it is reachable from `:kernel`'s own tests and from nowhere
     * else. Exposing it would be a kernel `main` edit, which [CHA3-82] forbids. The reachable
     * count is what the eviction gate actually reads, so it is the stronger observable for a
     * departure check even though it says nothing about how many gossip links carry it.
     */
    fun reachablePeers(): Int = (registry.replicasOf(dataId) - ref).size

    /** Every replica ref of the mesh's logical cell this peer can currently see. */
    fun visibleReplicas(): Set<CellRef> = registry.replicasOf(dataId)

    /** The interest this peer's register currently assigns to its own replica. */
    fun assignedInterest(): Interest? = assignments?.interestOf(ref)

    /** The routing epoch this peer's register currently holds for its own replica. */
    fun assignedEpoch(): Long? = assignments?.epochOf(ref)

    /** How many times this peer's host has been discarded and rebuilt. */
    val crashGeneration: Int get() = if (::slot.isInitialized) slot.generation else 0

    override fun toString(): String = "MeshPeer($name, member=$member, suspended=$suspended)"

    // ------------------------------------------------------------------------------ internals

    private fun spawn() {
        val cell: Replicable<*> = when (payload) {
            MeshPayload.SET -> SetCell<String>(ref)
            MeshPayload.PN_COUNTER -> PnCounterCell(ref)
        }
        replication.replicate(cell, slot.host)
        replica = cell
        // The reconvergence observation attaches to this replica's OWN delta outlet, here rather
        // than at build time because a replica only exists once its peer has joined — and again on
        // every rejoin, which is what lets a late joiner's catch-up stream rebuild a fold from
        // scratch ([CHA3-14]). A no-op unless the run is wrapped in `MeshConvergences.observing`.
        MeshConvergences.onSpawn(world, this, cell)
        // The assignment register is spawned once per host *generation*, not once per join: an
        // eviction despawns the data replica and leaves the register (and the delivered-watermark
        // companion `Replication` keeps for the id) in place, so a rejoin that re-created it would
        // hit `Cell already spawned` on the same ref. A crash is the case that genuinely needs a
        // new one, and `discardHostLocalState` is what nulls it.
        if (assignments == null) {
            val register = InstanceSet(assignmentRef)
            replication.replicate(register, slot.host)
            assignments = register
        }
        writeProxy = null
        member = true
        world.cells.redeclare(name, ref)
    }

    /**
     * Resolve a plan's interest **name** to a kernel [Interest].
     *
     * The plan carries a name because `Interest` is polymorphic and would not survive the
     * flat-primitive codec rule (`doc/dst-rig.md` §2); the graph is what knows the slice a name
     * denotes. `interest-<n>` is the generator's own convention (`ChurnGenerator`: the slice of
     * peer index `n`), so it maps to slot `n` of [totalSlots]. Any other name is a whole-mesh
     * interest rather than a silent failure, because a name the generator did not mint is a
     * suite's own and the suite means "all of it".
     */
    private fun interestNamed(interest: String): Interest {
        val slot = interest.removePrefix("interest-").toIntOrNull() ?: return Interest.Total
        return Interest.Slots(setOf(slot.coerceIn(0, totalSlots - 1)), totalSlots)
    }
}

/**
 * The mesh's declared peers, per [DstWorld] ([CHA3-46]).
 *
 * A second per-graph registry beside [PeerHandles] rather than a widening of it, and the
 * division is deliberate: [PeerHandles] is the rig-facing one — it holds the [PeerHandle]
 * *behaviour* a churn event resolves by name and is all `civictech.testkit.dst` needs to know —
 * while this one holds the concrete [MeshPeer] a **check** reads observables off. Keeping the
 * rig's registry typed to the interface is what stops the fault vocabulary from acquiring a
 * dependency on this graph.
 *
 * Entries are keyed by identity on a [WeakHashMap], exactly as [PeerHandles],
 * [civictech.testkit.dst.LinkControls] and `CrashWitnesses` are, so nothing outlives the run
 * that declared it.
 */
object MeshPeers {

    private val byWorld = WeakHashMap<DstWorld, MutableMap<String, MeshPeer>>()

    /** Declare [peer], registering its handle with the rig's own [PeerHandles] as well. */
    @Synchronized
    fun declare(world: DstWorld, peer: MeshPeer): MeshPeer {
        val peers = byWorld.getOrPut(world) { linkedMapOf() }
        require(peer.name !in peers) { "peer \"${peer.name}\" is already declared on this world" }
        peers[peer.name] = peer
        PeerHandles.declare(world, peer.name, peer)
        return peer
    }

    @Synchronized
    fun names(world: DstWorld): Set<String> = byWorld[world]?.keys?.toSet() ?: emptySet()

    @Synchronized
    fun all(world: DstWorld): List<MeshPeer> = byWorld[world]?.values?.toList() ?: emptyList()

    @Synchronized
    fun find(world: DstWorld, peer: String): MeshPeer? = byWorld[world]?.get(peer)

    /** The [MeshPeer] for [peer], or a loud failure naming what the graph did declare. */
    @Synchronized
    fun require(world: DstWorld, peer: String): MeshPeer =
        find(world, peer) ?: throw IllegalStateException(
            "peer \"$peer\" was not declared on this churn mesh; declared peers: ${names(world).sorted()}",
        )
}
