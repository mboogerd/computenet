package civictech.testkit.dst.churn

import civictech.cell.Owned
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.CrashFault
import civictech.testkit.dst.DeadLetterAccounting
import civictech.testkit.dst.DeadLetterPolicy
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DuplicateFault
import civictech.testkit.dst.ExclusiveLedger
import civictech.testkit.dst.ExclusiveLedgers
import civictech.testkit.dst.ExclusivePayloadLost
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphBuilder
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.PartitionFault
import civictech.testkit.dst.TrackedExclusive
import civictech.testkit.dst.UnexplainedDeadLetters
import civictech.testkit.dst.dstSweep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BS-17 (`[CHA3-44]`, `[CHA3-45]`): a **replicated churn mesh** — the same `ChurnMesh` the
 * reconvergence sweeps run, `MeshPayload.SET`, membership churning under a generated plan —
 * additionally **carrying `Owned` payloads across a bridge** between two extra hosts, one of
 * which departs uncleanly mid-transfer under a CHA1 [CrashFault] folded into the same
 * [ChurnPlan] the mesh's own membership events came from.
 *
 * ## Why the bridge is separate hosts, not a mesh peer's own replica
 *
 * A [MeshPeer]'s replicated cell is a [civictech.cell.data.SetCell] of strings — there is no
 * `Owned` payload anywhere in that path, and adding one would be a change to `PeerHandles.kt`
 * or `ChurnMesh.kt`, both claimed by other tasks. So the exclusive-payload channel is CHA1's own
 * "bridge" idiom (`ExclusivePayloadAccountingTest`'s `ExclusiveBridgeGraph`, copied deliberately
 * rather than re-invented — a sender host, a receiver host, two named edges), wired into the
 * SAME [civictech.testkit.dst.DstWorld] a real churn mesh already occupies, on host and edge
 * names (`excl-sender`, `excl-receiver`, `es->er`, `er->es`) disjoint from the mesh's own
 * (`peer0`, `peer1`, `peer0<->peer1`). "Unclean departure" for the bridge receiver is CHA1's own
 * [CrashFault.midDrain] rather than a churn [civictech.testkit.dst.DepartEvent] — the bridge
 * host is not on the churn roster (`PeerHandles` only knows `peer0`/`peer1` here), and reaching
 * for a `DepartEvent` would mean hand-rolling a second [civictech.testkit.dst.PeerHandle]
 * implementation to answer to it, for no behavioural difference: both mechanisms discard the
 * host generation exactly the same way (`HostSlot.crash()`).
 *
 * ## The mesh is present but idle
 *
 * `ChurnConfig.opScriptLength = 0`: the mesh peers churn but issue no writes of
 * their own. BS-17's property is exclusive-payload accounting, not reconvergence — that is
 * BS-1's job, already covered elsewhere — so the mesh's own workload would be traffic this test
 * does not read. `peerCount = 2..2` fixes the roster so ONE [GraphSpec] (built once, with a
 * template plan whose `writeSchedule` is empty for the identical reason) is valid for every
 * seed `dstSweep` runs it against — a seed-varying roster would need a seed-varying graph,
 * which `dstSweep`'s single `graph:` parameter cannot express.
 *
 * ## What the mesh's churn does and does NOT contribute here — measured, not assumed
 *
 * At `peerCount = 2..2` and `eventCount = 2` the generator draws, across the WHOLE checked-in
 * range (seeds 1..50): 73 [civictech.testkit.dst.JoinEvent]s, 100
 * [civictech.testkit.dst.ReassignEvent]s, and **zero [civictech.testkit.dst.DepartEvent]s of any
 * [civictech.testkit.dst.DepartureMode]**. No mesh peer ever departs, cleanly or uncleanly, on
 * any seed this suite runs. The unclean departure BS-17's *Given* names is therefore delivered
 * **solely** by [CrashFault.midDrain] on `excl-receiver` (`fired=1` on every seed), never by a
 * churn departure. The mesh's membership events are *not* disjoint from the payload path — the
 * bridge's last traced activity lands somewhere in steps 56..105 depending on seed, and the
 * earliest joins (step 92 / 289 / 26 on seeds 1 / 2 / 5) fall inside that window on seeds 1 and
 * 5 — but a join is not the departure BS-17's *Given* asks for, and no departure is drawn at
 * all.
 *
 * So read this suite as **CHA1's exclusive accounting under an unclean HOST departure, in a
 * world that also contains a churning mesh** — not as an exclusive payload carried through
 * membership churn. Widening it to a real mesh departure overlapping the transfer is
 * `computenet-usmw`. Raising `EVENT_COUNT` here is not a *free* fix, but it is closer than it
 * looks: at 8 the generator draws 107 [civictech.testkit.dst.DepartEvent]s across the same 50
 * seeds (26 of them [civictech.testkit.dst.DepartureMode.CRASH_UNCLEAN], on 20 seeds), the
 * earliest at step 61, and 14 of 50 seeds place one inside the 56..105 band above — and 49 of
 * the 50 conforming runs still pass. What blocks it is **one** seed: seed 8 refuses at run time
 * with `peer "peer0" is already a member, so a rejoin cannot be applied to it`, raised from
 * `MeshPeer.rejoin`, which is enough to redden the whole sweep.
 *
 * ## Accounting, not a bespoke assertion (`[CHA3-44]`, `[CHA3-45]`)
 *
 * The registered check is exactly [ExclusiveLedgers.check] (CHA1-53's own — accounts the run's
 * dead letters into the ledger, then verifies the balance) composed with
 * [DeadLetterPolicy.strict]'s own check ([CHA1-52]) — nothing here reimplements either
 * property. In this graph neither `HostSlot.crash()` nor a destroyed frame produces a dead
 * letter (the same rig limit `computenet-umx.2.5`'s `DISPUTES.md` entry traces: a crash
 * discards a generation's scheduler outright, with no invocation path to dead-letter through),
 * so the strict policy is asserted as a real, checked absence rather than an untested default —
 * [theDeadLetterPolicyIsGenuinelyExercisedAndReadsZero_CHA3_45] pins the reading directly.
 */
object ChurnExclusiveBridgeGraph {

    const val CONFORMING_ID: String = "churn-exclusive-bridge"
    const val CONTROL_ID: String = "churn-exclusive-bridge-control"
    const val CHECK_ID: String = "churn-exclusive-check"

    const val PAYLOADS: Int = 6
    const val MAX_ATTEMPTS: Int = 4
    private const val CRASH_STEP: Int = 2

    private const val PEER_COUNT: Int = 2
    private const val EVENT_COUNT: Int = 2
    private const val STEP_BUDGET: Int = 600

    private val meshConfig = ChurnConfig(
        peerCount = PEER_COUNT..PEER_COUNT,
        eventCount = EVENT_COUNT,
        opScriptLength = 0,
        stepBudget = STEP_BUDGET,
    )

    /** Built once, from seed 0, only to size the graph: roster length and an empty write schedule. */
    private val templatePlan: ChurnPlan = ChurnGenerator.generate(0L, meshConfig)

    private val meshBuilder: GraphBuilder = ChurnMesh.spec(
        templatePlan,
        payload = MeshPayload.SET,
        maxPeers = PEER_COUNT,
        aliveUntil = STEP_BUDGET,
    ).builder

    /** The per-seed churn plan: real membership churn over the 2-peer mesh, [PAYLOADS]-independent. */
    fun churnPlan(seed: Long): ChurnPlan = ChurnGenerator.generate(seed, meshConfig)

    /**
     * The composed run plan ([CHA3-04]): the seed's churn events, plus CHA1's drop/duplicate/crash
     * adversary against the exclusive bridge, folded in via [ChurnPlan.withFaults].
     */
    fun plan(seed: Long): FaultPlan = churnPlan(seed).withFaults(
        PartitionFault.drop("excl-drop-sr", "es->er", from = 0, until = 3),
        PartitionFault.drop("excl-drop-ack", "er->es", from = 0),
        DuplicateFault.frames("excl-dup-sr", "es->er", copies = 1, probability = 0.5),
        CrashFault.midDrain("excl-crash-receiver", "excl-receiver", atStep = CRASH_STEP),
    ).toFaultPlan()

    val conforming: GraphSpec = GraphSpec(CONFORMING_ID, builder(dischargeOnExhaustion = true))
    val control: GraphSpec = GraphSpec(CONTROL_ID, builder(dischargeOnExhaustion = false))

    /** [ExclusiveLedgers.check] and [DeadLetterPolicy.strict]'s check, composed — no bespoke assertion. */
    fun check(): DstCheck = DstCheck { world ->
        ExclusiveLedgers.check(CHECK_ID).verify(world)
        DeadLetterPolicy.strict.check().verify(world)
    }

    private fun builder(dischargeOnExhaustion: Boolean): GraphBuilder = GraphBuilder { world ->
        meshBuilder.build(world)

        val ledger: ExclusiveLedger = ExclusiveLedgers.declare(world, CHECK_ID)
        val schedulers = mutableMapOf<String, HostScheduler>()

        val sender = world.hosts.declare("excl-sender") { ctx ->
            schedulers["excl-sender"] = ctx.scheduler
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
        }
        val receiver = world.hosts.declare("excl-receiver") { ctx ->
            schedulers["excl-receiver"] = ctx.scheduler
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
        }
        world.cells.declare("excl-sender", sender.host.ref)
        world.cells.declare("excl-receiver", receiver.host.ref)
        world.edges.declare("es->er", from = "excl-sender", to = "excl-receiver")
        world.edges.declare("er->es", from = "excl-receiver", to = "excl-sender")

        val outbox = linkedMapOf<String, Owned<TrackedExclusive>>()
        val attempts = linkedMapOf<String, Int>()

        fun ack(id: String) {
            world.edges.deliver("er->es", id.toByteArray()).forEach { frame ->
                schedulers.getValue("excl-sender").submit(10) {
                    world.trace.emit(host = "excl-sender", cell = "excl-sender", port = "ack")
                    outbox.remove(String(frame))?.let { ledger.consume(it, "acked by excl-receiver") }
                }
            }
        }

        lateinit var send: (String) -> Unit
        send = { id ->
            attempts[id] = (attempts[id] ?: 0) + 1
            world.edges.deliver("es->er", id.toByteArray()).forEach { frame ->
                schedulers.getValue("excl-receiver").submit(10) {
                    world.trace.emit(host = "excl-receiver", cell = "excl-receiver", port = "recv")
                    ack(String(frame))
                }
            }
            schedulers.getValue("excl-sender").submit(20) {
                if (id in outbox) {
                    if ((attempts[id] ?: 0) < MAX_ATTEMPTS) {
                        send(id)
                    } else {
                        val owned = outbox.remove(id)!!
                        if (dischargeOnExhaustion) {
                            ledger.discharge(owned.take(), "undeliverable after $MAX_ATTEMPTS attempts")
                        }
                        // else: the control drops the handle here, accounting nothing.
                    }
                }
            }
        }

        repeat(PAYLOADS) { i ->
            val id = "p$i"
            outbox[id] = ledger.mintOwned(id, origin = "excl-sender")
            send(id)
        }
    }
}

/**
 * BS-17 — every exclusive payload survives churn, or fails the run through CHA1's own
 * accounting ([CHA3-44]) — plus [CHA3-45]'s dead-letter half on the same graph.
 *
 * Both halves are asserted over a real seed sweep, not one hand-picked seed: `dstSweep` runs
 * every seed regardless of earlier failures and reports density ([CHA3-80]).
 */
class ExclusiveChurnTest {

    /**
     * BS-17: the conforming graph survives churn plus drop/duplicate/crash on every seed of the
     * sweep. The sweep also asserts non-vacuity two ways: the churn events themselves all fired
     * ([CHA3-47]), and every fixed-id CHA1 fault folded into the bridge fired at least once
     * somewhere in the range — a green result whose adversary never fired proves nothing.
     */
    @Test
    fun everyExclusivePayloadSurvivesChurnOrFailsTheRun_BS17() {
        val sweep = dstSweep(
            suite = "churn-exclusive",
            seeds = 1L..50L,
            graph = ChurnExclusiveBridgeGraph.conforming,
            checkId = ChurnExclusiveBridgeGraph.CHECK_ID,
            artifactRoot = root,
            planFor = ChurnExclusiveBridgeGraph::plan,
        )
        sweep.assertAllPassed()

        // [CHA3-47]: no seed's own churn plan left an inert event.
        sweep.entries.forEach { entry ->
            val plan = ChurnExclusiveBridgeGraph.churnPlan(entry.seed)
            val fired = entry.report?.appliedFaults.orEmpty().filter { !it.inert }.map { it.id }.toSet()
            val plannedIds = plan.events.map { it.id }.toSet()
            assertTrue(
                plannedIds.all { it in fired },
                "seed ${entry.seed}: churn events must not go inert inside this bridge graph; " +
                    "planned=$plannedIds fired=$fired",
            )
        }

        // The fixed-identity bridge adversary must have fired at least once across the range —
        // a single inert seed is expected (partition/duplicate probabilities), but never every seed.
        val bridgeFaultIds = setOf("excl-drop-sr", "excl-drop-ack", "excl-dup-sr", "excl-crash-receiver")
        val everFired = sweep.entries.flatMap { it.report?.appliedFaults.orEmpty() }
            .filter { !it.inert }
            .map { it.id }
            .toSet()
        assertEquals(
            bridgeFaultIds,
            everFired intersect bridgeFaultIds,
            "the bridge adversary must fire at least once across the sweep: ${sweep.summary()}",
        )
    }

    /** The diverging control ([CHA1-62]/[CHA1-63]): letting the handle go fails on at least one seed. */
    @Test
    fun theSilentlyDroppingControlLosesAnExclusiveOnAtLeastOneSeed_BS17() {
        val sweep = dstSweep(
            suite = "churn-exclusive-control",
            seeds = 1L..50L,
            graph = ChurnExclusiveBridgeGraph.control,
            checkId = ChurnExclusiveBridgeGraph.CHECK_ID,
            artifactRoot = root,
            planFor = ChurnExclusiveBridgeGraph::plan,
        )

        assertTrue(
            sweep.failures.isNotEmpty(),
            "a control that does not diverge fails the rig's own self-test: ${sweep.summary()}",
        )
        val first = sweep.failures.first()
        assertTrue(
            first.cause is ExclusivePayloadLost,
            "the control must fail on the ownership invariant, not on something else: ${first.cause}",
        )
    }

    /**
     * [CHA3-45]: the dead-letter half of this graph's check is genuinely exercised, not a
     * default that happens never to fire. A crash discards its generation's scheduler with no
     * invocation path to dead-letter through (`computenet-umx.2.5`'s `DISPUTES.md` finding,
     * traced independently here rather than assumed) — this test pins that reading directly so
     * a future rig change that starts dead-lettering crash-orphaned work is caught, instead of
     * silently making [DeadLetterPolicy.strict] the untested half of [ChurnExclusiveBridgeGraph.check].
     */
    @Test
    fun theDeadLetterPolicyIsGenuinelyExercisedAndReadsZero_CHA3_45() {
        val run = civictech.testkit.dst.DstRun(
            ChurnExclusiveBridgeGraph.conforming,
            ChurnExclusiveBridgeGraph.plan(seed = 3),
            check = CheckRegistry.require(ChurnExclusiveBridgeGraph.CHECK_ID),
        )
        val report = run.execute()
        assertEquals(civictech.testkit.dst.DstOutcome.PASSED, report.outcome, "seed 3 is a conforming, passing run")

        val accounting = DeadLetterAccounting.of(report, DeadLetterPolicy.strict)
        assertEquals(0, accounting.total, "this graph's crash produces no dead letter: ${accounting.renderCounts()}")

        // And the check genuinely reads that path rather than skipping it: force one dead letter
        // shape through the accounting type directly (not through a run) and confirm it fails.
        val forcedFailure = runCatching {
            DeadLetterAccounting(
                classified = listOf(
                    civictech.testkit.dst.ClassifiedDeadLetter(
                        letter = civictech.cell.host.DeadLetter(
                            hostRef = civictech.cell.CellRef(java.util.UUID.randomUUID()),
                            cause = null,
                            description = "undeliverable, unexplained",
                            invocation = null,
                        ),
                        reason = civictech.testkit.dst.DeadLetterReason.UNDELIVERABLE,
                    ),
                ),
                policy = DeadLetterPolicy.strict,
            ).verify()
        }.exceptionOrNull()
        assertTrue(forcedFailure is UnexplainedDeadLetters, "$forcedFailure")
    }

    companion object {
        private val root = File("build/dst-churn/exclusive")

        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(ChurnExclusiveBridgeGraph.conforming)
            GraphRegistry.register(ChurnExclusiveBridgeGraph.control)
            CheckRegistry.register(ChurnExclusiveBridgeGraph.CHECK_ID, ChurnExclusiveBridgeGraph.check())
            root.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ChurnExclusiveBridgeGraph.CONFORMING_ID)
            GraphRegistry.unregister(ChurnExclusiveBridgeGraph.CONTROL_ID)
            CheckRegistry.unregister(ChurnExclusiveBridgeGraph.CHECK_ID)
        }
    }
}

