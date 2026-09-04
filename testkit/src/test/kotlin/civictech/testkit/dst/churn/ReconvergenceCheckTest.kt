package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.PartitionFault
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.RejoinEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [CHA3-10]–[CHA3-14] and [CHA3-40]: the reconvergence property over a churn mesh — every
 * replica still counted as live membership exposes one fold, that fold is the batch reference
 * over the operations the surviving replicas accepted, a departed replica's frozen fold is
 * excluded rather than counted as divergence, and a late joiner is indistinguishable from an
 * original member.
 *
 * ## Two things every test here does, and why
 *
 * **It asserts the exact set of fired fault ids.** A green run whose adversary never fired
 * proves nothing, and this feature has measured that twice — once on a test its own docstring
 * called "the positive control", which stayed green with the departure event entirely silenced.
 * So each test compares `report.appliedFaults.filter { it.fired > 0 }.map { it.id }` against the
 * ids it planned, not "the run passed".
 *
 * **It strides its writes and places its events in the hundreds.** A `SimulationController` step
 * runs ONE task and a single replicated write on this mesh costs tens of them (measured by the
 * churn-mesh task: 58 writes drained in 3102 steps). Writes packed one per step are all issued
 * inside the first sixty, and every later event then fires against a quiescent mesh — a green
 * verdict about a system nobody asked about.
 */
class ReconvergenceCheckTest {

    private companion object {
        const val WRITE_STRIDE = 50
        const val BUDGET = 40_000
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun config(
        peers: Int,
        stepBudget: Int,
        eventCount: Int = 0,
        departureWeights: Map<DepartureMode, Int> = mapOf(DepartureMode.EVICT_CLEAN to 1),
        opScriptLength: Int = 0,
    ) = ChurnConfig(
        peerCount = peers..peers,
        eventCount = eventCount,
        departureWeights = departureWeights,
        writeConcurrency = 0.0,
        partitionOverlap = 0.5,
        opScriptLength = opScriptLength,
        stepBudget = stepBudget,
        suspendWindow = 8,
    )

    private fun roster(peers: Int): List<String> = List(peers) { "peer$it" }

    /** [count] writes round-robined over [peers], one every [stride] steps. See the class KDoc. */
    private fun writes(peers: List<String>, count: Int, from: Int = 2, stride: Int = WRITE_STRIDE): List<ChurnWrite> =
        (0 until count).map { i -> ChurnWrite(from + i * stride, peers[i % peers.size], i) }

    private fun joinsAt(step: Int, peers: List<String>): List<ChurnEvent> =
        peers.map { peer -> JoinEvent("join-$peer", peer, step) as ChurnEvent } +
            peers.mapIndexed { i, peer -> ReassignEvent("assign-$peer", peer, step, "interest-$i", 1L) }

    /**
     * Execute [plan] with the reconvergence observation armed, handing back the report and the
     * world the check saw.
     *
     * The world is captured through the [DstCheck] because that is the only place a consumer
     * sees one — which makes a null world a signal rather than an inconvenience: `DstRun` runs no
     * check on a `BUDGET_EXHAUSTED` run, so a missing world means the run never quiesced.
     */
    private fun execute(
        plan: ChurnPlan,
        payload: MeshPayload = MeshPayload.SET,
        check: DstCheck = ReconvergenceCheck.of(payload),
        budget: Int = BUDGET,
        aliveUntil: Int = aliveHorizon(plan),
        before: (DstWorld) -> Unit = {},
        onStep: (DstWorld, Int) -> Unit = { _, _ -> },
    ): Pair<DstReport, DstWorld> {
        var captured: DstWorld? = null
        val mesh = ChurnMesh.spec(
            plan,
            payload = payload,
            maxPeers = plan.peers.size,
            aliveUntil = aliveUntil,
        )
        // Wrap rather than edit: `ChurnMesh` is another task's file, and a per-step observation is
        // this suite's own. The wrapper builds the mesh, then adds its hook.
        val spec = GraphSpec("${mesh.id}-observed") { world ->
            mesh.builder.build(world)
            world.steps.onStep { w, step -> onStep(w, step) }
        }
        val report = MeshConvergences.observing {
            DstRun(
                graph = spec,
                plan = plan.toFaultPlan(),
                budget = budget,
                check = DstCheck { world ->
                    captured = world
                    before(world)
                    check.verify(world)
                },
            ).execute()
        }
        val world = captured ?: fail(
            "the run never quiesced (${report.outcome}, ${report.steps}/${report.budget} steps), " +
                "so no check ran and nothing was observed",
        )
        return report to world
    }

    /** Keep the run non-idle past the last event, then let it drain. */
    private fun aliveHorizon(plan: ChurnPlan): Int =
        maxOf(
            plan.events.maxOfOrNull { it.atStep } ?: 0,
            plan.writeSchedule.maxOfOrNull { it.atStep } ?: 0,
        ) + 300

    private fun firedIds(report: DstReport): Set<String> =
        report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet()

    private fun plannedIds(plan: ChurnPlan): Set<String> =
        (plan.events.map { it.id } + plan.faults.map { it.id }).toSet()

    private fun assertPassed(report: DstReport, plan: ChurnPlan) {
        assertEquals(
            DstOutcome.PASSED,
            report.outcome,
            "${report.summary()} :: ${report.failingCheck?.error?.suppressed?.joinToString()}",
        )
        assertEquals(plannedIds(plan), firedIds(report), "the adversary must have fired every planned event")
    }

    // ------------------------------------------------------------------------------- BS-1

    /**
     * BS-1: a seeded churn plan over a `SetCell` mesh with concurrent writes — every live fold is
     * equal AND equal to the batch reference — over a small seed sweep, with the density
     * reported ([CHA3-10], [CHA3-11]).
     *
     * ## Two deliberate restrictions of the generated plan, and the reason for each
     *
     * **Only the two eviction modes are drawn.** `CRASH_UNCLEAN` and `PARTITION_SUSPEND` are
     * excluded from *this* sweep, not because they are unsupported but because their
     * reconvergence needs the event that answers them — a rejoin after a crash, a heal after a
     * park — and the generator does not guarantee to place one inside the horizon. The kernel has
     * no failure detector, so a crashed peer never leaves `replicasOf` and its frozen fold is a
     * genuine live divergence; a still-parked peer is a partitioned mesh. Both are stated on
     * [ReconvergenceCheck] and both are covered by hand-built plans below (BS-8 heals; the
     * departed-stream control departs cleanly).
     *
     * **The generated write schedule is re-strided.** `ChurnGenerator` places writes on
     * consecutive steps, which — see the class KDoc — issues the whole script inside the first
     * few dozen steps of a run whose events are hundreds of steps out. Only the *steps* are
     * replaced; which peer writes which ordinal stays exactly as the seed drew it.
     */
    @Test
    fun `BS-1 a seeded churn plan converges to the batch reference over a seed sweep`() {
        val seeds = 1L..6L
        val cfg = config(peers = 3, stepBudget = 1200, eventCount = 5, opScriptLength = 18)
        val failures = mutableListOf<String>()

        seeds.forEach { seed ->
            val generated = ChurnGenerator.generate(seed, cfg)
            val plan = generated.copy(
                writeSchedule = generated.writeSchedule.mapIndexed { i, w ->
                    w.copy(atStep = 2 + i * WRITE_STRIDE)
                },
            )
            val (report, world) = execute(plan)
            if (report.outcome != DstOutcome.PASSED) {
                failures += "seed=$seed ${report.summary()} :: " +
                    report.failingCheck?.error?.suppressed?.joinToString().orEmpty() +
                    " :: events=${plan.events.map { "${it.id}@${it.atStep}" }}"
                return@forEach
            }
            assertEquals(plannedIds(plan), firedIds(report), "seed=$seed: every planned event must have fired")
            // Not vacuous: the mesh actually accepted work, and the reference is over that work.
            val accepted = AcceptedOps.of(world)
            assertTrue(accepted.size >= 6, "seed=$seed accepted only ${accepted.size} op(s): ${report.summary()}")
        }

        // [CHA1-39]'s density, reported rather than "first failure".
        assertTrue(
            failures.isEmpty(),
            "reconvergence held on ${seeds.count() - failures.size} of ${seeds.count()} seeds; " +
                "failures: $failures",
        )
    }

    // ------------------------------------------------------------------------------- BS-2

    /**
     * BS-2: a 3-peer mesh, a clean mid-run evict, the survivors converge and the departed
     * replica's frozen final fold is **excluded** rather than counted as a divergence
     * ([CHA3-13], `[42-REPL-06]`).
     *
     * The single orderly departure is already covered as a conformance scenario —
     * `concord/corpus/42-replication/42-REPL-DEPART-01.yaml` (`covers: [42-REPL-06]`) — and this
     * test does **not** duplicate it and adds no scenario ([CHA3-83]). What it adds is the
     * generalisation the scenario cannot express: the departure happens *inside* a running
     * workload, and the survivors are judged against a batch reference rather than against each
     * other.
     */
    @Test
    fun `BS-2 a clean mid-run evict leaves survivors converged and the departed fold excluded`() {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 21L,
            config = config(peers = 3, stepBudget = 2000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(
                DepartEvent("depart-peer1", "peer1", 600, DepartureMode.EVICT_CLEAN),
            ),
            writeSchedule = writes(peers, count = 20),
        )

        val (report, world) = execute(plan)
        assertPassed(report, plan)

        val peer1 = MeshPeers.require(world, "peer1")
        assertFalse(peer1.member, "the evicted peer must have departed")
        assertEquals(true, peer1.lastEvictDespawned, "a clean evict with two reachable peers despawns")

        // The departed-stream rule, observed rather than assumed: peer1's fold is still attached
        // (frozen at its last value) and is simply not in live membership any more.
        val observer = MeshPeers.require(world, "peer0")
        val convergence = assertNotNull(MeshConvergences.of(world, "peer0"))
        assertTrue(peer1.ref in convergence.states().keys, "the departed replica's frozen fold is still attached")
        assertFalse(
            peer1.ref in observer.registry.replicasOf(observer.ref.id),
            "an evicted replica drops out of replicasOf — that exclusion is what the check rests on",
        )
        assertTrue(convergence.converged(), "the survivors converge with the departed stream excluded")

        // ...and the survivors hold everything peer1 accepted before it left. Note WHY that is
        // assertable here: peer1's last write is ~100 controller steps ahead of the departure, so
        // it is applied and gossiped long before the evict. A clean departure does NOT lose
        // nothing in general — a write accepted a step earlier is dropped at peer1's own intake
        // (computenet-9c5t; the boundary is pinned by :kernel's ChurnReconvergenceTest and stated
        // on BatchReference), which is why the check's permitted arm exists at all.
        val accepted = AcceptedOps.of(world)
        val reference = BatchReference.of(world, MeshPayload.SET).foldOf(peers.toSet())
        val observed = MeshConvergences.project(assertNotNull(convergence.state(observer.ref)))
        assertEquals(reference, observed, "the survivors' fold is the batch reference over every accepted op")
        assertTrue(accepted.any { it.peer == "peer1" }, "peer1 must have accepted work before departing")
    }

    // ------------------------------------------------------------------------------- BS-7

    /**
     * BS-7 (fold-equality half): a peer that joins after deltas have already gossiped through the
     * mesh reaches the mesh fold and is indistinguishable from an original member ([CHA3-14],
     * `[42-REPL-05]`). The hop-count half of BS-7 belongs to the instrumentation task and is
     * deliberately not asserted here.
     */
    @Test
    fun `BS-7 a late joiner reaches the mesh fold`() {
        val peers = roster(3)
        val early = peers.take(2)
        val plan = ChurnPlan(
            seed = 31L,
            config = config(peers = 3, stepBudget = 2000),
            peers = peers,
            events = joinsAt(1, early) + listOf(
                JoinEvent("join-peer2", "peer2", 700),
                ReassignEvent("assign-peer2", "peer2", 700, "interest-2", 2L),
            ),
            writeSchedule = writes(early, count = 16),
        )

        val (report, world) = execute(plan)
        assertPassed(report, plan)

        val joiner = MeshPeers.require(world, "peer2")
        val original = MeshPeers.require(world, "peer0")
        val convergence = assertNotNull(MeshConvergences.of(world, "peer0"))
        val joinerFold = MeshConvergences.project(assertNotNull(convergence.state(joiner.ref)))
        val originalFold = MeshConvergences.project(assertNotNull(convergence.state(original.ref)))
        assertEquals(originalFold, joinerFold, "a late joiner's fold is indistinguishable from an original member's")

        // Non-vacuity: the joiner arrived after real deltas had already gossiped, so this is a
        // catch-up and not an empty mesh agreeing with itself.
        val beforeJoin = AcceptedOps.of(world).count { it.peer in early }
        assertTrue(beforeJoin >= 8, "the mesh must have accepted work before peer2 joined, got $beforeJoin")
        assertTrue(joinerFold is ReferenceFold.Elements && joinerFold.elements.size >= 8, "joiner caught up")
    }

    // ------------------------------------------------------------------------------- BS-8

    /**
     * BS-8: a join issued while an A→B partition is active. After the heal the joiner converges,
     * every peer's membership fold reaches the same set, and the transient disagreement is
     * recorded while it lasts.
     *
     * The transient half is what the assertion at the end pins: peers MAY disagree about live
     * membership while a partition is open (42, decided 93 I-3), and the check only requires the
     * disagreement to be gone at quiescence. A run in which no peer ever disagreed would mean the
     * partition never bit, so the recording is a positive control, not decoration.
     */
    @Test
    fun `BS-8 a join during a partition converges after the heal and membership agrees`() {
        val peers = roster(3)
        val early = peers.take(2)
        val plan = ChurnPlan(
            seed = 41L,
            config = config(peers = 3, stepBudget = 3000),
            peers = peers,
            events = joinsAt(1, early) + listOf(
                JoinEvent("join-peer2", "peer2", 700),
                ReassignEvent("assign-peer2", "peer2", 700, "interest-2", 2L),
            ),
            writeSchedule = writes(early, count = 20),
        ).withFaults(PartitionFault.park("cha1-park", "peer0<->peer1", from = 500, until = 1500))

        // The transient half: how many distinct membership opinions the joined peers held at each
        // step. Recorded from a step hook wrapped around the mesh's own builder, because a check
        // only ever sees quiescence.
        val opinionsPerStep = mutableListOf<Pair<Int, Int>>()
        val (report, world) = execute(plan) { w, step ->
            val members = MeshPeers.all(w).filter { it.member }
            if (members.isNotEmpty()) {
                val distinct = members.map { it.registry.replicasOf(it.ref.id) }.toSet().size
                if (distinct > 1) opinionsPerStep += step to distinct
            }
        }

        assertPassed(report, plan)
        assertEquals(listOf(500, 1500), report.appliedFaults.single { it.id == "cha1-park" }.activationSteps)

        // At quiescence every peer's membership fold is the same set — that is what the check's
        // MEMBERSHIP arm asserted, re-read here so the test states it rather than implying it.
        val memberships = MeshPeers.all(world).filter { it.member }.map { it.registry.replicasOf(it.ref.id) }
        assertEquals(1, memberships.toSet().size, "peers must agree on live membership once healed: $memberships")
        assertEquals(3, memberships.first().size, "all three peers are live at quiescence")

        // ...and the disagreement genuinely existed while the partition was open. A run in which
        // no peer ever disagreed would mean the partition never bit, so this is a positive
        // control on the whole test rather than a decorative record.
        assertTrue(
            opinionsPerStep.isNotEmpty(),
            "no peer ever disagreed about membership, so the partition did not bite: ${report.summary()}",
        )
    }

    // ------------------------------------------------------------------------- the controls

    /**
     * [CHA3-13] made non-vacuous: with the departed-stream rule **disabled** — the control seam's
     * variant judgement, which keeps a departed replica's frozen fold inside the comparison —
     * the very same orderly departure reads as a divergence.
     *
     * That is what makes BS-2's green a result rather than a tautology: the exclusion is doing
     * work, and this is the run that proves it. No kernel code is touched to obtain it.
     */
    @Test
    fun `the departed-stream rule is load-bearing - disabling it turns an orderly departure into a divergence`() {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 51L,
            config = config(peers = 3, stepBudget = 2000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(
                DepartEvent("depart-peer1", "peer1", 600, DepartureMode.EVICT_CLEAN),
            ),
            writeSchedule = writes(peers, count = 20),
        )

        val variant = ReconvergenceCheck(MeshPayload.SET) { _, attached -> attached }
        val (report, _) = execute(plan, check = variant)

        assertEquals(DstOutcome.FAILED, report.outcome, report.summary())
        assertEquals(ReconvergenceCheck.DIVERGED, report.failingCheck?.message)
        assertEquals(plannedIds(plan), firedIds(report), "the departure must have fired for this to mean anything")
    }

    /**
     * [CHA3-11]: the reference is not the folds. An operation recorded as accepted that no
     * replica ever applied must fail the run — replica-vs-replica agreement would be silent.
     *
     * The injected operation is the instrument: it stands for a write the mesh lost. If this
     * test can be made green by deleting the reference comparison, the comparison is decoration.
     */
    @Test
    fun `a lost operation fails the run - replica agreement alone is not accepted`() {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 61L,
            config = config(peers = 3, stepBudget = 1500),
            peers = peers,
            events = joinsAt(1, peers),
            writeSchedule = writes(peers, count = 12),
        )

        val (report, _) = execute(
            plan,
            before = { world ->
                AcceptedOps.record(world, AcceptedOp(peer = "peer0", ordinal = 999, element = "peer0-lost"))
            },
        )

        assertEquals(DstOutcome.FAILED, report.outcome, report.summary())
        assertEquals(ReconvergenceCheck.LOST, report.failingCheck?.message)
        assertTrue(
            report.failingCheck?.error?.suppressed?.joinToString().orEmpty().contains("peer0-lost"),
            "the lost element belongs in the detail, never in the identity",
        )
    }

    /**
     * [CHA3-40]: the failure *identity* is a fixed string across runs whose numbers differ, and
     * the numbers are reachable behind the detail. `PlanShrinker`'s default predicate compares
     * the message, so a run-varying identity silently discards every genuine reduction.
     */
    @Test
    fun `the failure identity is fixed while its detail varies`() {
        val peers = roster(3)
        fun run(writeCount: Int): DstReport {
            val plan = ChurnPlan(
                seed = 71L + writeCount,
                config = config(peers = 3, stepBudget = 1500),
                peers = peers,
                events = joinsAt(1, peers),
                writeSchedule = writes(peers, count = writeCount),
            )
            return execute(
                plan,
                before = { world ->
                    AcceptedOps.record(world, AcceptedOp(peer = "peer0", ordinal = 900 + writeCount, element = "ghost-$writeCount"))
                },
            ).first
        }

        val small = run(6)
        val large = run(15)
        assertEquals(ReconvergenceCheck.LOST, small.failingCheck?.message)
        assertEquals(small.failingCheck?.message, large.failingCheck?.message, "identity must not vary with counts")
        val smallDetail = small.failingCheck?.error?.suppressed?.joinToString().orEmpty()
        val largeDetail = large.failingCheck?.error?.suppressed?.joinToString().orEmpty()
        assertTrue(smallDetail.isNotBlank() && smallDetail != largeDetail, "the varying half lives in the detail")
    }

    /** A run that never armed the observation judges nothing, and says so. */
    @Test
    fun `an unobserved run fails loudly rather than judging an empty fold set`() {
        val peers = roster(2)
        val plan = ChurnPlan(
            seed = 81L,
            config = config(peers = 2, stepBudget = 1000),
            peers = peers,
            events = joinsAt(1, peers),
            writeSchedule = writes(peers, count = 6),
        )
        val spec = ChurnMesh.spec(plan, maxPeers = 2, aliveUntil = aliveHorizon(plan))
        val report = DstRun(
            graph = spec,
            plan = plan.toFaultPlan(),
            budget = BUDGET,
            check = ReconvergenceCheck.of(),
        ).execute()

        assertEquals(DstOutcome.FAILED, report.outcome, report.summary())
        assertTrue(
            report.failingCheck?.message.orEmpty().contains("no reconvergence observation"),
            "an unobserved run must name the remedy, got ${report.failingCheck?.message}",
        )
    }

    /** The second payload: the batch reference is the SUM of the accepted counter operations. */
    @Test
    fun `a PnCounter mesh converges to the summed batch reference`() {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 91L,
            config = config(peers = 3, stepBudget = 2000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(
                DepartEvent("depart-peer2", "peer2", 600, DepartureMode.EVICT_NO_CLOSE),
            ),
            writeSchedule = writes(peers, count = 18),
        )

        val (report, world) = execute(plan, payload = MeshPayload.PN_COUNTER)
        assertPassed(report, plan)

        val accepted = AcceptedOps.of(world)
        val reference = BatchReference.of(world, MeshPayload.PN_COUNTER).foldOf(peers.toSet())
        assertEquals(ReferenceFold.Total(accepted.sumOf { it.increment }), reference)
        val observer = MeshPeers.require(world, "peer0")
        val convergence = assertNotNull(MeshConvergences.of(world, "peer0"))
        assertEquals(reference, MeshConvergences.project(assertNotNull(convergence.state(observer.ref))))
    }

    /** The registered form ([CHA3-40]): an artifact can name the property that failed. */
    @Test
    fun `the check registers under a stable id so an artifact can name it`() {
        val registered = ReconvergenceCheck.registered(MeshPayload.SET)
        assertEquals(
            registered,
            civictech.testkit.dst.CheckRegistry.require(ReconvergenceCheck.idFor(MeshPayload.SET)),
        )
    }
}
