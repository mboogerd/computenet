package civictech.testkit.dst.churn

import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.RejoinEvent
import civictech.testkit.dst.dstSweep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * [CHA3-80], BS-1, BS-15, BS-16: the churn mesh at **sweep scale** — every seed in a range,
 * density reported, not first-failure abort — the deliverable this task adds on top of the
 * single-plan tests the sibling tasks already shipped (`ReconvergenceCheckTest`,
 * `ChurnMeshTest`, `GossipInstrumentsTest`, `DepartureGatesTest`, all of which run one
 * hand-built or generated plan per test method, never a `dstSweep` range).
 *
 * ## Why the graph is built from one template plan and reused across every seed
 *
 * [civictech.testkit.dst.dstSweep] takes a single [GraphSpec] for the whole seed range —
 * `planFor` varies the *fault plan* per seed, not the graph. [ChurnMesh.spec] bakes a specific
 * plan's roster size and write schedule into the graph it returns, so reusing it across seeds
 * needs both held fixed: [PEER_COUNT] fixes the roster (`peerCount = PEER_COUNT..PEER_COUNT`,
 * so every seed draws the same-sized roster under the same peer names `peer0`, `peer1`,
 * `peer2`), and the replicated write schedule comes from [templatePlan] alone. That schedule is
 * baked into the graph at construction time and is genuinely fixed across every seed — but
 * that is not a loss of coverage: [ChurnPlan.toFaultPlan] never carries `writeSchedule` at all
 * (only `events` and `faults` reach a [FaultPlan]), so a per-seed plan's own generated write
 * schedule was never going to reach a `DstRun` through this path regardless. What genuinely
 * varies seed to seed is the churn itself — which peer joins, departs, rejoins or is
 * reassigned, by which [DepartureMode], at which step — which is what `[CHA3-80]`'s density is
 * measuring the sweep against.
 */
object ChurnReconvergenceSweep {

    const val ID: String = "churn-reconvergence-sweep"

    private const val PEER_COUNT: Int = 3
    private const val EVENT_COUNT: Int = 6
    private const val OP_SCRIPT_LENGTH: Int = 24
    private const val STEP_BUDGET: Int = 6000

    /**
     * The heartbeat outlives [STEP_BUDGET] by this much. A churn event may generate right up to
     * `stepBudget - 1` ([ChurnGenerator.nextStep]'s own horizon clamp), and gossip propagation
     * between per-peer registries is itself a chain of scheduled hops — measured to need real
     * slack after the last event, not merely a heartbeat tick at the same step, or quiescence
     * arrives before every peer's registry has converged and [ReconvergenceCheck] reads a
     * transient disagreement as a genuine one (`assertOneMembership`, `ReconvergenceCheck.kt:256`).
     */
    private const val DRAIN_MARGIN: Int = 1000

    val config: ChurnConfig = ChurnConfig(
        peerCount = PEER_COUNT..PEER_COUNT,
        eventCount = EVENT_COUNT,
        opScriptLength = OP_SCRIPT_LENGTH,
        writeConcurrency = 0.3,
        partitionOverlap = 0.3,
        stepBudget = STEP_BUDGET,
        suspendWindow = 60,
    )

    private const val WRITE_START: Int = 300
    private const val WRITE_STRIDE: Int = 200

    /**
     * Sizes the graph (roster, write schedule) only — see the class KDoc.
     *
     * The write schedule is **hand-built**, not [ChurnGenerator]'s own — measured directly
     * (a throwaway per-seed `ChurnMesh.overlapOf` read, discarded after use): the generator's
     * `writeSchedule` packs every write into the first `~opScriptLength` steps regardless of
     * `stepBudget` (`ChurnGenerator.writeSchedule`'s own `step` only advances by ~1 per
     * non-concurrent write), while membership events are spread across the WHOLE horizon
     * (`nextStep`'s `maxGap = horizon / eventCount`, here ~1000). Against a 6000-step horizon
     * that left writes landing before most peers had even joined — achieved overlap measured
     * at 0% on 19 of 20 sampled seeds. `ReconvergenceCheckTest`'s own fixture already avoids
     * this the same way (its `WRITE_STRIDE` constant, `writes()` helper): stride writes across
     * the horizon instead of packing them at the start.
     */
    private val templatePlan: ChurnPlan = ChurnGenerator.generate(0L, config).let { plan ->
        plan.copy(
            writeSchedule = (0 until OP_SCRIPT_LENGTH).map { i ->
                ChurnWrite(WRITE_START + i * WRITE_STRIDE, plan.peers[i % plan.peers.size], i)
            },
        )
    }

    val graph: GraphSpec = GraphSpec(ID) { world ->
        ChurnMesh.spec(
            templatePlan,
            payload = MeshPayload.SET,
            maxPeers = PEER_COUNT,
            aliveUntil = STEP_BUDGET + DRAIN_MARGIN,
        ).builder.build(world)
    }

    /**
     * The real per-seed churn: same roster and horizon, seed-drawn events, with every
     * still-[DepartureMode.PARTITION_SUSPEND]-ed peer healed before the run's own horizon.
     *
     * [ChurnGenerator]'s own coherence promise (its class KDoc) is about the *generated
     * sequence* — a peer never departs before it joins, never rejoins while a member — not
     * about every departure resolving before the plan ends. A peer whose *last* drawn event is
     * a `PARTITION_SUSPEND` departure stays suspended for the rest of the run whenever no later
     * draw redraws that same peer, and a suspended peer is still `member == true`
     * ([MeshPeer.partitionAway] never clears it), so [ReconvergenceCheck.verify]'s
     * `assertOneMembership` legitimately reads its registry as disagreeing forever — that is
     * the correct, expected shape of an *unhealed* partition (`BS-9`: "on heal it resumes and
     * converges", which only happens on heal), not a property this sweep is asking about.
     * `[CHA3-01]`'s generator draws it independently by design, and this task cannot touch
     * `ChurnGenerator.kt` to make it self-heal — so the seam is here: any peer left suspended
     * gets one appended [RejoinEvent] just past the last generated step, which calls
     * [MeshPeer.heal] rather than a fresh join (`MeshPeer.rejoin`'s own `if (suspended)` arm).
     */
    fun churnPlan(seed: Long): ChurnPlan = healDanglingPartitions(ChurnGenerator.generate(seed, config))

    private fun healDanglingPartitions(plan: ChurnPlan): ChurnPlan {
        val suspended = linkedSetOf<String>()
        for (event in plan.events) {
            when (event) {
                is JoinEvent -> suspended -= event.peer
                is RejoinEvent -> suspended -= event.peer
                is DepartEvent -> if (event.mode == DepartureMode.PARTITION_SUSPEND) {
                    suspended += event.peer
                } else {
                    suspended -= event.peer
                }
                else -> Unit
            }
        }
        if (suspended.isEmpty()) return plan
        val lastStep = plan.events.maxOfOrNull { it.atStep } ?: 0
        val healStep = (lastStep + 1).coerceAtMost(plan.stepBudget - 1)
        val heals: List<ChurnEvent> = suspended.map { peer -> RejoinEvent("heal-$peer", peer, healStep) }
        return plan.copy(events = plan.events + heals)
    }

    fun plan(seed: Long): FaultPlan = churnPlan(seed).toFaultPlan()

    /** [ReconvergenceCheck.of] over [MeshPayload.SET], registered under its own stable id. */
    fun check(): DstCheck = ReconvergenceCheck.registered(MeshPayload.SET)
}

/**
 * BS-1 at sweep scale: every live replica reconverges, over a real seed range, with density
 * reported rather than the run stopping at the first failure ([CHA3-80]).
 */
class ChurnSweepTest {

    /**
     * The sweep itself must run inside [MeshConvergences.observing] — `dstSweep` calls
     * `DstRun.execute()` directly with no wrapper of its own, and [ReconvergenceCheck] refuses
     * loudly on a world nothing observed rather than judging an empty fold set.
     *
     * Non-vacuity is asserted two ways, per seed: every planned churn event fired
     * ([CHA3-47] — an event past the run's own quiescence point is a truncated plan, not a
     * clean run), and at least one seed in the range drew every [DepartureMode] at least once
     * across the whole sweep — so this is not a sweep whose adversary happened to draw only the
     * gentle departure modes twenty times running.
     */
    @Test
    fun everyLiveReplicaReconvergesAcrossAChurnSweep_BS1() {
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "churn-reconvergence",
                seeds = SEEDS,
                graph = ChurnReconvergenceSweep.graph,
                checkId = ReconvergenceCheck.idFor(MeshPayload.SET),
                artifactRoot = root,
                planFor = ChurnReconvergenceSweep::plan,
            )
        }
        sweep.assertAllPassed()

        val drawnModes = mutableSetOf<DepartureMode>()
        sweep.entries.forEach { entry ->
            val plan = ChurnReconvergenceSweep.churnPlan(entry.seed)
            val fired = entry.report?.appliedFaults.orEmpty().filter { !it.inert }.map { it.id }.toSet()
            val plannedIds = plan.events.map { it.id }.toSet()
            assertTrue(
                plannedIds.all { it in fired },
                "seed ${entry.seed}: every planned churn event must fire, or the plan truncated silently " +
                    "([CHA3-47]); planned=$plannedIds fired=$fired",
            )
            plan.events.filterIsInstance<DepartEvent>().forEach { drawnModes += it.mode }
        }
        assertTrue(
            drawnModes.containsAll(DepartureMode.entries),
            "the sweep must exercise every departure mode across its range, or the adversary is " +
                "narrower than the config claims: drawn=$drawnModes",
        )
    }

    companion object {
        /** ~7.5ms/seed measured locally (see the findings entry) — 60 seeds runs in ~0.45s. */
        private val SEEDS = 1L..60L
        private val root = File("build/dst-churn/reconvergence-sweep")

        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(ChurnReconvergenceSweep.graph)
            ChurnReconvergenceSweep.check()
            root.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ChurnReconvergenceSweep.ID)
            CheckRegistry.unregister(ReconvergenceCheck.idFor(MeshPayload.SET))
        }
    }
}



