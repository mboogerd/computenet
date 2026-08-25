package civictech.testkit.dst.churn

import civictech.cell.wire.Peering
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.LinkControl
import civictech.testkit.dst.LinkControls
import java.util.UUID
import java.util.WeakHashMap

/**
 * A churn failure whose *identity* is a fixed string and whose run-varying half is carried
 * separately ([CHA3-48] mechanics, `doc/dst-rig.md` §3).
 *
 * The reason is [civictech.testkit.dst.PlanShrinker]'s default `FailurePredicate`: it compares
 * the failing check's **message** to decide whether a candidate reduction reproduced "the same
 * failure". A message embedding a count, a peer set or a seed therefore silently defeats
 * shrinking — a genuine reduction produces a different string and is discarded as a different
 * defect. This is the same split `SweepFailure` landed for CHA1's sweep messages
 * (`computenet-umx.4`), copied deliberately rather than re-invented.
 *
 * So [message] names *what* failed and nothing else; [detail] carries the numbers, and reaches
 * a human as a suppressed throwable rather than through the identity.
 */
class ChurnCheckFailure(
    identity: String,
    val detail: String,
    cause: Throwable? = null,
) : AssertionError(identity, cause) {
    init {
        addSuppressed(ChurnDetail(detail))
    }
}

/** Carrier for [ChurnCheckFailure.detail]. See [ChurnCheckFailure]; mirrors `SweepDetail`. */
class ChurnDetail(message: String) : Throwable(message) {
    override fun fillInStackTrace(): Throwable = this

    override fun toString(): String = message ?: ""
}

/**
 * The measured churn/write overlap of one run ([CHA3-60], BS-15).
 *
 * ## Why this is a number on a report and not a log line
 *
 * A churn harness is configured with an *intent* — "half the workload should land while the
 * mesh is churning" — and nothing about running it guarantees the intent was met. A run that
 * configured 50% and achieved 2% is a **red** result: it exercised a quiescent mesh under a plan
 * that claimed to exercise a churning one, and every green verdict it produced is about a
 * system nobody asked about. That failure is invisible in a log, so the number is a field, a
 * sweep can assert on it ([atLeast]), and the assertion's message is fixed-string per
 * [ChurnCheckFailure].
 *
 * @property configured the fraction the suite asked for. There is no dedicated write-overlap
 *   knob on [ChurnConfig] — its `partitionOverlap` is about where *events* land relative to a
 *   suspension window, not about where *writes* land relative to events — so the caller states
 *   the target, and [ChurnMesh.spec] defaults it to `partitionOverlap` only because that is the
 *   config's nearest declared statement of intent. Read it as "what this run was aiming at",
 *   not as a knob the generator honoured.
 * @property achieved [opsDuringChurn] over [opsIssued]; `0.0` when nothing was issued.
 * @property opsScheduled how many writes the plan carried. [opsIssued] is smaller whenever a
 *   scheduled write named a peer that was not a member at that step — expected, since the write
 *   schedule is generated against the roster rather than against membership.
 * @property inFlightWindow how many steps after an event's activation step count as "while that
 *   event is in flight". A window rather than a single step because the kernel consequences of a
 *   departure (unpublish, re-link, catch-up) take controller steps to play out, and a write on
 *   the exact activation step is not the only one that overlaps it.
 */
data class ChurnOverlap(
    val configured: Double,
    val opsDuringChurn: Int,
    val opsIssued: Int,
    val opsScheduled: Int,
    val inFlightWindow: Int,
) {
    val achieved: Double get() = if (opsIssued == 0) 0.0 else opsDuringChurn.toDouble() / opsIssued

    /** How far short of [configured] the run landed; negative when it exceeded the target. */
    val shortfall: Double get() = configured - achieved

    /** Reporting only — it embeds run-varying counts. Never a check's message. */
    fun summary(): String =
        "churn/write overlap: configured=${pct(configured)} achieved=${pct(achieved)} " +
            "($opsDuringChurn of $opsIssued issued writes inside a ${inFlightWindow}-step churn window; " +
            "$opsScheduled scheduled)"

    private fun pct(v: Double): String = "${Math.round(v * 100)}%"
}

/**
 * Per-run churn observation ([CHA3-60]): what the workload actually did, as opposed to what the
 * plan said it would.
 *
 * Held on a per-graph [WeakHashMap] registry ([ChurnMesh.observerOf]) for the reason
 * `doc/dst-rig.md` §1 gives for `LinkControls` and `CrashWitnesses`: an observation the graph
 * builder produces and a check reads is a per-graph declaration, never a seventh seam on
 * [DstWorld].
 */
class ChurnObserver internal constructor(
    private val plan: ChurnPlan,
    private val configuredOverlap: Double,
    private val inFlightWindow: Int,
) {
    /** Writes actually issued against a live replica. */
    var opsIssued: Int = 0
        private set

    /** Writes the schedule named for a peer that was not a member at that step. */
    var opsSkipped: Int = 0
        private set

    /** Of [opsIssued], how many landed inside some event's in-flight window. */
    var opsDuringChurn: Int = 0
        private set

    /** The steps at which a write was issued, in order — for a report, never for control flow. */
    val issuedAt: MutableList<Int> = mutableListOf()

    internal fun recordWrite(step: Int, issued: Boolean) {
        if (!issued) {
            opsSkipped++
            return
        }
        opsIssued++
        issuedAt += step
        if (inFlight(step)) opsDuringChurn++
    }

    /** True while some planned event's `[atStep, atStep + inFlightWindow)` window covers [step]. */
    fun inFlight(step: Int): Boolean =
        plan.events.any { step >= it.atStep && step < it.atStep + inFlightWindow }

    fun overlap(): ChurnOverlap = ChurnOverlap(
        configured = configuredOverlap,
        opsDuringChurn = opsDuringChurn,
        opsIssued = opsIssued,
        opsScheduled = plan.writeSchedule.size,
        inFlightWindow = inFlightWindow,
    )
}

/**
 * The churn mesh under test ([CHA3-03], [CHA3-04]): a pre-declared set of replicated peers, a
 * seeded op-script workload issued from a step hook, and the executor wiring that turns a
 * [ChurnPlan]'s events into real membership changes.
 *
 * ## Three rig limits this graph is shaped by
 *
 * Each was measured during CHA1 and is a boundary of the rig, not a defect to route around:
 *
 *  1. **`DstRun.execute()` ends at the first true idle.** A workload that drains between churn
 *     events truncates the run before the later events fire. So every write is scheduled from a
 *     [DstWorld.steps] hook (`doc/dst-rig.md` §1 seam 4), and a heartbeat task keeps the run
 *     non-idle up to [aliveUntil] — by default the last *scheduled write's* step, which is the
 *     workload's own horizon. An event past that is genuinely past quiescence and reports
 *     `fired=0` inert ([CHA3-47]) rather than being silently dropped; [allEventsFired] is the
 *     check that turns that report into a failure for a suite that meant to cover it.
 *  2. **`DstWorld` backs `world.hosts` with the one `world.registry`.** Each [MeshPeer] declares
 *     its crashable host with a build lambda that supplies its OWN registry and takes only
 *     `ctx.scheduler` from the context — see [MeshPeer]'s KDoc for why, and
 *     `ChurnMeshTest`'s crash-and-rebuild probe for the verification that a rebuild generation
 *     composes with it.
 *  3. **Frame-plane faults select targets by edge NAME, and a graph builder runs once.** So the
 *     *maximum* peer set is declared at build time, with every pairwise edge and every
 *     [LinkControl]; "not yet joined" means declared-but-not-replicated, and a [JoinEvent]
 *     activates a pre-declared slot at its step. That is what lets a CHA1 `PartitionFault` name
 *     an edge of a peer that joins at step 40 ([CHA3-04]).
 *
 * ## Composition with CHA1 faults
 *
 * The mesh declares the same seams a CHA1 fault reaches for — a host slot per peer, an edge and
 * a `LinkControl.severing` per pair — so a plan may fold `CrashFault.atQuiescence("...",
 * "peer2", ...)` or `PartitionFault.park("...", "peer0<->peer1", ...)` in beside its churn
 * events with no extra wiring ([CHA3-04]). The one interaction worth knowing:
 * [MeshPeer.partitionAway] and a `PartitionFault` in `PARK` mode can name the same control, and
 * a severing control's park/heal is not a counter — [MeshPeer.partitionAway] is idempotent per
 * peer for exactly that reason, but a `PartitionFault` healing at step N still releases a
 * control a churn departure parked at step N-1. A suite composing both on one edge should say
 * which it means to own.
 */
object ChurnMesh {

    private val observers = WeakHashMap<DstWorld, ChurnObserver>()

    /**
     * Build a [GraphSpec] for [plan].
     *
     * @param payload what the mesh replicates. See [MeshPayload].
     * @param maxPeers how many peer slots to pre-declare. Defaults to [ChurnConfig.peerCount]'s
     *   upper bound, which is the largest roster any seed can draw for this config — so a plan
     *   generated from any seed of the same config names only declared peers.
     * @param configuredOverlap the churn/write overlap this run is aiming at. See
     *   [ChurnOverlap.configured] for why the default is `partitionOverlap` and what that does
     *   and does not mean.
     * @param inFlightWindow see [ChurnOverlap.inFlightWindow].
     * @param aliveUntil the step up to which a heartbeat keeps the run non-idle. Defaults to one
     *   past the last scheduled write — the workload's own horizon. Raising it makes later
     *   events reachable; it does **not** make them meaningful, since the mesh is then spinning
     *   on an empty heartbeat rather than on work.
     */
    @Suppress("LongParameterList")
    fun spec(
        plan: ChurnPlan,
        payload: MeshPayload = MeshPayload.SET,
        maxPeers: Int = plan.config.peerCount.last,
        configuredOverlap: Double = plan.config.partitionOverlap,
        inFlightWindow: Int = plan.config.suspendWindow,
        aliveUntil: Int = (plan.writeSchedule.maxOfOrNull { it.atStep } ?: 0) + 1,
    ): GraphSpec {
        require(maxPeers >= plan.peers.size) {
            "the mesh must pre-declare at least the plan's roster: maxPeers=$maxPeers, roster=${plan.peers}"
        }
        val id = "churn-mesh-${payload.name.lowercase()}-${maxPeers}p-seed${plan.seed}-" +
            "e${plan.events.size}w${plan.writeSchedule.size}-alive$aliveUntil"
        // Re-registering an id with a *different* builder instance is refused by GraphRegistry,
        // and every call here mints a fresh lambda. Unregister first so a suite may build the
        // same plan twice (an `assertDeterministic` re-run reuses ONE spec and is unaffected).
        GraphRegistry.unregister(id)
        return GraphRegistry.register(id) { world ->
            build(world, plan, payload, maxPeers, configuredOverlap, inFlightWindow, aliveUntil)
        }
    }

    /** The [ChurnObserver] declared for [world], or a loud failure if this is not a churn mesh. */
    @Synchronized
    fun observerOf(world: DstWorld): ChurnObserver =
        observers[world] ?: throw IllegalStateException(
            "no churn observation was declared on this world — ChurnMesh.spec(...) is what declares it",
        )

    /** The measured overlap of the run [world] belongs to ([CHA3-60]). */
    fun overlapOf(world: DstWorld): ChurnOverlap = observerOf(world).overlap()

    /**
     * [CHA3-47]: fail the run if any planned churn event never fired.
     *
     * The truncation guard rig limit 1 above exists for: a plan whose later half never activated
     * looks exactly like a plan that activated and found nothing.
     */
    fun allEventsFired(plan: ChurnPlan): DstCheck = DstCheck { world ->
        val activity = world.faultActivity()
        val inert = plan.events.filter { (activity[it.id]?.fired ?: 0) == 0 }
        if (inert.isNotEmpty()) {
            throw ChurnCheckFailure(
                "churn plan truncated: the run quiesced before every planned event fired",
                detail = "inert events: ${inert.map { "${it.id}@${it.atStep}" }}; " +
                    "planned=${plan.events.size} fired=${plan.events.size - inert.size}",
            )
        }
    }

    /**
     * BS-15: fail the run when the ACHIEVED churn/write overlap is below [minimum].
     *
     * The message is the fixed identity of the failure; the configured and achieved percentages
     * are the run-varying half and live in [ChurnCheckFailure.detail]. See [ChurnOverlap].
     */
    fun overlapAtLeast(minimum: Double): DstCheck = DstCheck { world ->
        val overlap = overlapOf(world)
        if (overlap.achieved < minimum) {
            throw ChurnCheckFailure(
                "achieved churn/write overlap is below the configured target",
                detail = "${overlap.summary()}; required at least ${Math.round(minimum * 100)}%",
            )
        }
    }

    /** Every check above, in order: truncation first, then overlap. */
    fun checks(plan: ChurnPlan, minimumOverlap: Double): DstCheck = DstCheck { world ->
        allEventsFired(plan).verify(world)
        overlapAtLeast(minimumOverlap).verify(world)
    }

    // ------------------------------------------------------------------------------- builder

    @Suppress("LongParameterList")
    private fun build(
        world: DstWorld,
        plan: ChurnPlan,
        payload: MeshPayload,
        maxPeers: Int,
        configuredOverlap: Double,
        inFlightWindow: Int,
        aliveUntil: Int,
    ) {
        // Derived from the plan's seed, never from wall-clock entropy: the mesh's logical ids
        // are part of what "same seed, same graph" means (feature §9 risk 2).
        val dataId = UUID.nameUUIDFromBytes("churn-mesh-data:${plan.seed}".toByteArray())
        val assignmentId = UUID.nameUUIDFromBytes("churn-mesh-assignments:${plan.seed}".toByteArray())

        val peers = (0 until maxPeers).map { index ->
            MeshPeer(
                name = "peer$index",
                index = index,
                world = world,
                dataId = dataId,
                assignmentId = assignmentId,
                payload = payload,
                totalSlots = maxPeers,
            ).also { it.declareHost() }
        }

        // Every pair is wired, edge-named and park-controlled at BUILD time — a peer that joins
        // at step 40 must already have the seams a frame-plane fault targets by name.
        for (i in peers.indices) {
            for (j in i + 1 until peers.size) {
                val a = peers[i]
                val b = peers[j]
                val edge = "${a.name}<->${b.name}"
                world.edges.declare(edge, from = a.name, to = b.name)
                val control = LinkControl.severing(Peering.loopback(a.side, b.side))
                LinkControls.declare(world, edge, control)
                a.attach(control)
                b.attach(control)
            }
        }

        peers.forEach { MeshPeers.declare(world, it) }

        val observer = ChurnObserver(plan, configuredOverlap, inFlightWindow)
        synchronized(this) { observers[world] = observer }

        // The workload: every write is issued from a step hook, because DstRun owns the drive
        // loop (`doc/dst-rig.md` §1 seam 4).
        val byStep = plan.writeSchedule.groupBy { it.atStep }
        world.steps.onStep { w, step ->
            byStep[step]?.forEach { write ->
                val peer = MeshPeers.find(w, write.peer)
                observer.recordWrite(step, issued = peer?.write(write.ordinal) ?: false)
            }
        }

        // The heartbeat: one trivial task per step keeps `controller.step()` returning true, so
        // a gap between two scheduled writes does not end the run before the last event's
        // window. Bounded by `aliveUntil`, so quiescence is still reachable and [CHA3-47]'s
        // inert marking still means something.
        val heartbeat = world.controller.scheduler()
        world.steps.onStep { _, step ->
            if (step < aliveUntil) heartbeat.submit(10) { }
        }
    }
}
