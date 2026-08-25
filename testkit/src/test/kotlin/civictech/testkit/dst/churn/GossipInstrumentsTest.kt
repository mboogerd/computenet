package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.JoinEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared churn-run fixture for the gossip-instrument suites ([CHA3-20]–[CHA3-25],
 * [CHA3-43]).
 *
 * ## Two things every test in these three files does, and why
 *
 * **It asserts the exact set of fired fault ids** ([assertPassed]). A green run whose adversary
 * never fired proves nothing, and this feature has measured that twice — once on a test whose
 * own docstring called it "the positive control", which stayed green with its departure event
 * entirely silenced. So the assertion is on `report.appliedFaults.filter { it.fired > 0 }`, not
 * on "the run passed".
 *
 * **It strides its writes and places its events in the hundreds.** A `SimulationController` step
 * runs ONE task and a single replicated write on this mesh costs tens of them (measured by the
 * churn-mesh task: 58 writes drained in 3102 steps). Writes packed one per step are all issued
 * inside the first sixty, and every later event fires against a quiescent mesh.
 *
 * **[MeshConvergences] is deliberately NOT armed.** The reconvergence observation subscribes a
 * fold to every replica's delta outlet; these suites measure that outlet's subscriber set, so
 * arming it would put the instrument's own scaffolding inside the quantity under test. Fold
 * equality is read directly off [MeshPeer.foldSnapshot] instead.
 */
internal object GossipRuns {

    const val WRITE_STRIDE = 50
    const val BUDGET = 60_000

    fun config(peers: Int, stepBudget: Int) = ChurnConfig(
        peerCount = peers..peers,
        eventCount = 0,
        departureWeights = mapOf(DepartureMode.EVICT_CLEAN to 1),
        writeConcurrency = 0.0,
        partitionOverlap = 0.5,
        opScriptLength = 0,
        stepBudget = stepBudget,
        suspendWindow = 8,
    )

    fun roster(peers: Int): List<String> = List(peers) { "peer$it" }

    /** [count] writes round-robined over [peers], one every [stride] steps. See the KDoc above. */
    fun writes(
        peers: List<String>,
        count: Int,
        from: Int = 2,
        stride: Int = WRITE_STRIDE,
    ): List<ChurnWrite> = (0 until count).map { i -> ChurnWrite(from + i * stride, peers[i % peers.size], i) }

    fun joinsAt(step: Int, peers: List<String>): List<ChurnEvent> =
        peers.map { peer -> JoinEvent("join-$peer", peer, step) as ChurnEvent }

    fun firedIds(report: DstReport): Set<String> =
        report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet()

    fun plannedIds(plan: ChurnPlan): Set<String> =
        (plan.events.map { it.id } + plan.faults.map { it.id }).toSet()

    fun assertPassed(report: DstReport, plan: ChurnPlan) {
        assertEquals(
            DstOutcome.PASSED,
            report.outcome,
            "${report.summary()} :: ${report.failingCheck?.error?.suppressed?.joinToString()}",
        )
        assertEquals(plannedIds(plan), firedIds(report), "the adversary must have fired every planned event")
    }

    /**
     * Execute [plan] with the gossip observation armed and sampled every step.
     *
     * The world is captured through the [DstCheck] because that is the only place a consumer
     * sees one — which makes a null world a signal rather than an inconvenience: `DstRun` runs no
     * check on a `BUDGET_EXHAUSTED` run, so a missing world means the run never quiesced.
     */
    fun execute(
        plan: ChurnPlan,
        payload: MeshPayload = MeshPayload.SET,
        check: DstCheck = GossipInstruments.checks(),
        budget: Int = BUDGET,
        aliveUntil: Int = aliveHorizon(plan),
        onStep: (DstWorld, Int) -> Unit = { _, _ -> },
    ): Pair<DstReport, GossipObservation> {
        var captured: DstWorld? = null
        val mesh = ChurnMesh.spec(plan, payload = payload, maxPeers = plan.peers.size, aliveUntil = aliveUntil)
        // Wrap rather than edit: `ChurnMesh` is another task's file, and the per-step sampling
        // this instrument needs is this suite's own. The wrapper builds the mesh, then adds the
        // hook — which must run AFTER the mesh's own write hook so a step's writes are already
        // issued when the sample is taken.
        val spec = GraphSpec("${mesh.id}-instrumented") { world ->
            mesh.builder.build(world)
            world.steps.onStep { w, step ->
                GossipInstruments.armOn(w).sample(step)
                onStep(w, step)
            }
        }
        val report = DstRun(
            graph = spec,
            plan = plan.toFaultPlan(),
            budget = budget,
            check = DstCheck { world ->
                captured = world
                check.verify(world)
            },
        ).execute()
        val world = captured ?: fail(
            "the run never quiesced (${report.outcome}, ${report.steps}/${report.budget} steps), " +
                "so no check ran and nothing was observed",
        )
        return report to GossipInstruments.of(world)
    }

    /** Keep the run non-idle past the last event, then let it drain. */
    fun aliveHorizon(plan: ChurnPlan): Int =
        maxOf(
            plan.events.maxOfOrNull { it.atStep } ?: 0,
            plan.writeSchedule.maxOfOrNull { it.atStep } ?: 0,
        ) + 300
}

/**
 * [CHA3-20], [CHA3-23], [CHA3-25]: the instruments themselves — that they read what they claim
 * to read, that the four quantities are reported on every run, and what the measured join-hop
 * distribution actually is.
 */
class GossipInstrumentsTest {

    // -------------------------------------------------------------- the derivation this rests on

    /**
     * The load-bearing pin of the whole file.
     *
     * [GossipInstruments.gossipRefFor] duplicates `Replication.gossipRef`, which is private, and
     * is the honest substitute for the `internal` `Replication.linkCountAmong` that [CHA3-82]
     * forbids widening. A duplicate of a private expression is only trustworthy while it still
     * matches: if the kernel changes the derivation, this test goes red rather than the
     * instruments silently reporting every gossip link as `unattributedConsumers` and every
     * bound as trivially satisfied.
     *
     * So: on a settled three-peer mesh, EVERY consumer of every replica's delta outlet is
     * attributed to a declared peer, none is left over, and the attributed set is exactly the
     * other live replicas.
     */
    @Test
    fun `the derived gossip ref attributes every live consumer to a declared peer`() {
        val peers = GossipRuns.roster(3)
        val plan = ChurnPlan(
            seed = 11L,
            config = GossipRuns.config(peers = 3, stepBudget = 2000),
            peers = peers,
            events = GossipRuns.joinsAt(10, peers),
            writeSchedule = GossipRuns.writes(peers, count = 9, from = 100),
        )
        val (report, obs) = GossipRuns.execute(plan)
        GossipRuns.assertPassed(report, plan)

        val readings = obs.subscriptions()
        assertEquals(3, readings.size, "every peer is live at quiescence")
        readings.forEach { r ->
            assertEquals(0, r.unattributedConsumers, "unattributed consumer on ${r.summary()}")
            assertEquals(2, r.gossipConsumers.size, "one gossip link per other live replica: ${r.summary()}")
            assertEquals(2, r.liveMembership, r.summary())
            assertEquals(2, r.links, "the LinkSupport record agrees with the consumer map: ${r.summary()}")
            assertEquals(emptySet(), r.staleConsumers, r.summary())
            assertEquals(0, r.excess, r.summary())
        }
    }

    /** [CHA3-25]: all four quantities are on the report of an ordinary run, not just assertable. */
    @Test
    fun `every run reports all four instrument quantities`() {
        val peers = GossipRuns.roster(3)
        val plan = ChurnPlan(
            seed = 12L,
            config = GossipRuns.config(peers = 3, stepBudget = 2000),
            peers = peers,
            events = GossipRuns.joinsAt(10, peers),
            writeSchedule = GossipRuns.writes(peers, count = 9, from = 100),
        )
        val (report, obs) = GossipRuns.execute(plan)
        GossipRuns.assertPassed(report, plan)

        val instruments = obs.report()
        assertEquals(3, instruments.subscriptions.size)
        assertEquals(3, instruments.deltas.size)
        assertEquals(0, instruments.staleLinks)
        assertEquals(3, instruments.hops.size)
        assertTrue(
            instruments.deltas.all { it.effectiveDeltas > 0 },
            "every replica must have absorbed or emitted effective deltas: ${instruments.summary()}",
        )
        val text = instruments.summary()
        listOf("staleLinks=", "subscriptions=", "effectiveDeltas=", "hops=").forEach {
            assertTrue(it in text, "the report must carry $it: $text")
        }
    }

    /**
     * A check's *identity* must not move between runs whose numbers differ ([CHA3-48] mechanics):
     * `PlanShrinker` compares check messages to decide whether a candidate reproduced the same
     * failure, so a count in the message silently discards every genuine reduction.
     */
    @Test
    fun `the failure identity is fixed while the detail carries the numbers`() {
        val a = ChurnCheckFailure(GossipInstruments.ORPHANED_SUBSCRIPTION, detail = "peer0 -> [x]")
        val b = ChurnCheckFailure(GossipInstruments.ORPHANED_SUBSCRIPTION, detail = "peer1 -> [y, z]")
        assertEquals(a.message, b.message)
        assertTrue(a.detail != b.detail)
        assertEquals(1, a.suppressed.size)
        assertEquals("peer0 -> [x]", a.suppressed.single().message)
    }

    /** An unarmed world fails loudly with the remedy, rather than reporting an empty instrument. */
    @Test
    fun `an unobserved run fails loudly rather than reporting empty instruments`() {
        val peers = GossipRuns.roster(2)
        val plan = ChurnPlan(
            seed = 13L,
            config = GossipRuns.config(peers = 2, stepBudget = 1200),
            peers = peers,
            events = GossipRuns.joinsAt(10, peers),
            writeSchedule = GossipRuns.writes(peers, count = 4, from = 100),
        )
        val mesh = ChurnMesh.spec(plan, maxPeers = 2, aliveUntil = GossipRuns.aliveHorizon(plan))
        val report = DstRun(
            graph = mesh,
            plan = plan.toFaultPlan(),
            budget = GossipRuns.BUDGET,
            check = GossipInstruments.checks(),
        ).execute()
        assertEquals(DstOutcome.FAILED, report.outcome, report.summary())
        assertTrue(
            report.failingCheck?.error?.message?.contains("GossipInstruments.armOn") == true,
            "the failure must name the remedy: ${report.failingCheck?.error?.message}",
        )
    }

    // ------------------------------------------------------------------------------- [CHA3-23]

    /**
     * [CHA3-23]: the join hop count for a mid-run joiner, MEASURED over a seed/roster sweep, with
     * the bound reported rather than assumed.
     *
     * ## The bound, decided against the data
     *
     * The feature's risk 1 (umx.2-D7) records that "diameter 1" is the wrong bound: during churn
     * the effective topology is transiently not a full mesh. Two candidates were on the table —
     * "one hop per membership-fold generation" and "bounded by the number of peers that had
     * learned the joiner". The measured distribution is printed by this test on every run and is
     * asserted against the SECOND: `hops <= peersThatHadLearned`, i.e. a joiner absorbs at most
     * one effective delta per replica its own registry already listed when it spawned.
     *
     * **What this bound is and is not.** It is a statement about *absorptions*, not about
     * network deliveries: an absorption is a delta that carried information the joiner did not
     * hold, and the catch-up push each existing member makes on link-up is one such delta per
     * member at most, because the joiner's fold is monotone and the second member's push is
     * already partly held. It is asserted only for a joiner that issues no write of its own in
     * its catch-up window — a local write emits on the same outlet and is not a hop — which is
     * how this plan places the writes. It is NOT proven for a joiner arriving into a *partitioned*
     * mesh, where a member may learn late and push again; that case is deliberately outside this
     * sweep and is stated here rather than left for a reader to discover.
     *
     * The other candidate — "one hop per membership-fold generation" — is NOT asserted: nothing
     * in the harness observes a fold generation, so it would be a bound stated about a quantity
     * this rig does not measure.
     */
    @Test
    fun `BS-hop a mid-run joiner's hop count is bounded by the peers that had learned it`() {
        val measured = mutableListOf<HopTrace>()
        val violations = mutableListOf<String>()

        (1L..4L).forEach { seed ->
            (2..4).forEach { size ->
                val peers = GossipRuns.roster(size)
                val incumbents = peers.dropLast(1)
                val joiner = peers.last()
                val plan = ChurnPlan(
                    seed = seed,
                    config = GossipRuns.config(peers = size, stepBudget = 3000),
                    peers = peers,
                    // The incumbents join at 10 and write; the joiner arrives at 900, after the
                    // last write has drained, so nothing it emits in its catch-up window is its
                    // own — which is what makes the count an absorption count.
                    events = GossipRuns.joinsAt(10, incumbents) + GossipRuns.joinsAt(900, listOf(joiner)),
                    writeSchedule = GossipRuns.writes(incumbents, count = 12, from = 100, stride = 50),
                )
                val (report, obs) = GossipRuns.execute(plan)
                GossipRuns.assertPassed(report, plan)

                val trace = assertNotNull(obs.hopTrace(joiner), "no hop trace for the joiner (seed=$seed n=$size)")
                measured += trace
                if (!trace.converged) violations += "seed=$seed n=$size never converged: ${trace.summary()}"
                if (trace.hops > trace.peersThatHadLearned) {
                    violations += "seed=$seed n=$size exceeded the bound: ${trace.summary()}"
                }
            }
        }

        // [CHA3-25]: the distribution is REPORTED, not merely asserted away.
        println(
            "[CHA3-23] measured join-hop distribution over ${measured.size} runs: " +
                measured.joinToString("; ") { "n_learned=${it.peersThatHadLearned} hops=${it.hops}" },
        )
        assertTrue(violations.isEmpty(), "join-hop bound violated:\n${violations.joinToString("\n")}")
        assertTrue(
            measured.any { it.hops > 0 },
            "a sweep in which no joiner ever absorbed anything measures nothing: $measured",
        )
    }
}
