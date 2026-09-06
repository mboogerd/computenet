package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.RejoinEvent
import civictech.testkit.dst.TraceDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The falsifiable half of `doc/dst-rig.md` §4's "A peering that re-opens mid-run is outside the
 * determinism contract" and of [DstRun.assertDeterministic]'s own caveat (computenet-l0gd).
 *
 * The two tests are one **controlled A/B on a single generated plan**, and that is the whole
 * point: a bare "the churn mesh is non-deterministic" assertion would be satisfied by any
 * entropy anywhere, which is exactly the reading the diagnosis had to rule out. Here the plan,
 * the seed, the graph shape, the budget and the write schedule are held byte-identical and
 * **only the departure mode changes**, so a difference in reproducibility is attributable to the
 * `PARTITION_SUSPEND` -> heal path and to nothing else.
 *
 * Seed 87 is the fixture because it draws no `PARTITION_SUSPEND` of its own — its two departures
 * are `EVICT_NO_CLOSE` — so the "without" arm needs no surgery and the "with" arm is exactly one
 * substitution plus the heal [healDanglingPartitions] appends for it.
 *
 * **[aHealedPartitionMakesTheSameMeshIrreproducible] asserts that a limitation still holds.** If
 * a later kernel change derives the identities `Peering.announceTo`'s catch-up sweep iterates —
 * or gives that sweep a total order — this test goes RED, and that is the intended signal: the
 * documentation it pins would then be stale and must be retracted rather than the test relaxed.
 * Four runs are compared, not two, because the outcome space is small (a few distinct
 * announcement orders over a 3-peer mesh) and two runs can coincide.
 */
class ChurnMeshDeterminismLimitTest {

    /** `StableFrontierChurnSweep.config`, the configuration the limitation was measured on. */
    private val config = ChurnConfig(
        peerCount = 3..3,
        eventCount = 6,
        opScriptLength = 24,
        writeConcurrency = 0.3,
        partitionOverlap = 0.3,
        stepBudget = 6000,
        suspendWindow = 60,
    )

    private companion object {
        const val SEED: Long = 87L
        const val BUDGET: Int = 40_000
        const val ALIVE_UNTIL: Int = 7000
        const val RUNS: Int = 4
    }

    /**
     * `StableFrontierChurnSweep.healDanglingPartitions` / `ChurnReconvergenceSweep`'s, copied for
     * the same reason both of those copied it: it is `private` in a test source file. A peer left
     * `PARTITION_SUSPEND`ed at the end of a plan never returns, and an unhealed partition is not
     * the shape under test here — the *healed* one is.
     */
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

    private fun generated(): ChurnPlan = ChurnSeeds.plans(SEED..SEED, config).single()

    /** [plan] with every [from] departure re-labelled [to]; steps, ids and roster untouched. */
    private fun swapMode(plan: ChurnPlan, from: DepartureMode, to: DepartureMode): ChurnPlan =
        plan.copy(events = plan.events.map { e -> if (e is DepartEvent && e.mode == from) e.copy(mode = to) else e })

    /** [RUNS] executions of one `(graph, plan)`, as trace digests. */
    private fun digests(plan: ChurnPlan): List<TraceDigest> {
        val spec = ChurnMesh.spec(plan, payload = MeshPayload.SET, maxPeers = 3, aliveUntil = ALIVE_UNTIL)
        val run = DstRun(spec, plan.toFaultPlan(), budget = BUDGET)
        return (0 until RUNS).map { TraceDigest.of(run.execute().trace) }
    }

    @Test
    fun aChurnPlanWithNoHealedPartitionIsTraceReproducible() {
        val plan = healDanglingPartitions(generated())
        check(plan.events.none { it is DepartEvent && it.mode == DepartureMode.PARTITION_SUSPEND }) {
            "fixture premise: seed $SEED must draw no PARTITION_SUSPEND, or this arm is not the control"
        }

        val observed = digests(plan)

        assertEquals(
            1,
            observed.toSet().size,
            "the churn mesh IS reproducible without a healed partition; $RUNS runs gave ${observed.map { it.hex }}",
        )
    }

    @Test
    fun aHealedPartitionMakesTheSameMeshIrreproducible() {
        val plan = healDanglingPartitions(
            swapMode(generated(), DepartureMode.EVICT_NO_CLOSE, DepartureMode.PARTITION_SUSPEND),
        )
        check(plan.events.any { it is DepartEvent && it.mode == DepartureMode.PARTITION_SUSPEND }) {
            "fixture premise: the swapped plan must carry a PARTITION_SUSPEND"
        }
        check(plan.events.any { it.id.startsWith("heal-") }) {
            "fixture premise: the suspension must be HEALED — an unhealed partition never re-opens the peering"
        }

        val observed = digests(plan)

        assertNotEquals(
            1,
            observed.toSet().size,
            "doc/dst-rig.md §4 says a healed partition re-opens the peering and costs trace " +
                "reproducibility. $RUNS runs of this plan agreed, so either the limitation is gone — " +
                "retract the documentation, do not relax this test — or the fixture stopped reaching " +
                "the heal. Digests: ${observed.map { it.hex }}",
        )
    }
}
