package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.JoinEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [CHA3-60] and BS-15: the churn/write overlap a run **achieved** is measured and reported per
 * run, and a run that configured 50% and achieved a tenth of that is a red result rather than a
 * green one with a disappointing log line.
 *
 * ## Why this is worth its own file
 *
 * Every other assertion in this feature is about what the adversary did. This one is about
 * whether the adversary and the workload were ever in the same place at the same time. A churn
 * harness whose writes all drain before the first departure produces perfectly green runs that
 * exercise a quiescent mesh — the plan looks adversarial, the report says PASSED, and nothing
 * about churn was tested. `ChurnOverlap` puts the number on the report so a sweep can refuse
 * such a run ([ChurnMesh.overlapAtLeast]) instead of a human noticing.
 */
class ChurnOverlapTest {

    private companion object {
        const val BUDGET = 20_000

        /** See `ChurnMeshTest.writes`: a replicated write costs tens of controller steps. */
        const val WRITE_STRIDE = 50

        /**
         * Wide enough to cover the steps a departure's consequences actually play out over. The
         * config's own `suspendWindow` (4 by default) is a *plan* knob about where events land
         * relative to each other; the in-flight window is an *observation* knob about how long a
         * churn event is still disturbing the mesh, and the two are not the same number.
         */
        const val IN_FLIGHT = 100
    }

    private fun config(stepBudget: Int = 4000, partitionOverlap: Double = 0.5) = ChurnConfig(
        peerCount = 3..3,
        eventCount = 0,
        writeConcurrency = 0.0,
        partitionOverlap = partitionOverlap,
        opScriptLength = 0,
        stepBudget = stepBudget,
        suspendWindow = 4,
    )

    private val peers = List(3) { "peer$it" }

    private fun joins(step: Int): List<ChurnEvent> = peers.map { JoinEvent("join-$it", it, step) }

    /**
     * [count] writes on **peer0 only**, one every [stride] steps from [from].
     *
     * One writer rather than a round robin so that `opsScheduled == opsIssued`: a write aimed at
     * a peer that has departed is skipped by design, and mixing that into an overlap measurement
     * would make the denominator move for a reason that has nothing to do with overlap.
     */
    private fun writes(count: Int, from: Int, stride: Int = WRITE_STRIDE): List<ChurnWrite> =
        (0 until count).map { i -> ChurnWrite(from + i * stride, "peer0", i) }

    private fun execute(
        plan: ChurnPlan,
        check: DstCheck = DstCheck.none,
    ): Pair<DstReport, DstWorld> {
        var captured: DstWorld? = null
        val spec = ChurnMesh.spec(plan, maxPeers = peers.size, inFlightWindow = IN_FLIGHT)
        val report = DstRun(
            graph = spec,
            plan = plan.toFaultPlan(),
            budget = BUDGET,
            check = DstCheck { world ->
                captured = world
                check.verify(world)
            },
        ).execute()
        val world = captured ?: fail("the run never quiesced (${report.outcome}), so nothing was observed")
        return report to world
    }

    /** Writes far from every event: the shape BS-15 calls red. */
    private fun sparselyOverlappingPlan(seed: Long) = ChurnPlan(
        seed = seed,
        config = config(),
        peers = peers,
        events = joins(1) + listOf(DepartEvent("depart-peer1", "peer1", 600, DepartureMode.EVICT_CLEAN)),
        writeSchedule = writes(count = 40, from = 2),
    )

    @Test
    fun `the achieved overlap is measured per run, not merely configured`() {
        val plan = sparselyOverlappingPlan(seed = 21L)

        val (report, world) = execute(plan)
        val overlap = ChurnMesh.overlapOf(world)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(plan.events.map { it.id }.toSet(), report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet())

        // Writes sit at steps 2, 52, 102, ...; the in-flight windows are [1, 101) around the
        // joins and [600, 700) around the departure. Two writes fall in the first, two in the
        // second — every one of the forty is issued, because peer0 never departs.
        assertEquals(40, overlap.opsScheduled)
        assertEquals(40, overlap.opsIssued)
        assertEquals(0, ChurnMesh.observerOf(world).opsSkipped)
        assertEquals(4, overlap.opsDuringChurn, overlap.summary())
        assertEquals(0.1, overlap.achieved, 1e-9, overlap.summary())
        assertEquals(0.5, overlap.configured, 1e-9)
        assertTrue(overlap.shortfall > 0.0, overlap.summary())
    }

    /**
     * BS-15: configured 50%, achieved 10% — the run is FAILED, not PASSED-with-a-caveat.
     *
     * The control in the same test is what stops this being a check that always fires: the same
     * run, asserted against a target the run actually met, passes.
     */
    @Test
    fun `a run that configured 50 percent overlap and achieved 10 is red`() {
        val plan = sparselyOverlappingPlan(seed = 22L)

        val (red, _) = execute(plan, ChurnMesh.overlapAtLeast(0.5))
        val (green, _) = execute(plan, ChurnMesh.overlapAtLeast(0.1))

        assertEquals(DstOutcome.FAILED, red.outcome, red.summary())
        assertEquals(
            "achieved churn/write overlap is below the configured target",
            red.failingCheck?.message,
        )
        assertEquals(DstOutcome.PASSED, green.outcome, green.summary())
    }

    /**
     * `doc/dst-rig.md` §3: the failing check's message is the failure's *identity* and must not
     * move with the run, or `PlanShrinker` discards genuine reductions as "a different failure".
     * Two runs whose overlap differs must therefore produce the **same** message and different
     * details.
     */
    @Test
    fun `the overlap failure message is a fixed string and the varying half is in detail`() {
        val sparse = sparselyOverlappingPlan(seed = 23L)
        val sparser = ChurnPlan(
            seed = 24L,
            config = config(),
            peers = peers,
            events = joins(1) + listOf(DepartEvent("depart-peer1", "peer1", 2500, DepartureMode.EVICT_CLEAN)),
            writeSchedule = writes(count = 20, from = 300),
        )

        val (a, _) = execute(sparse, ChurnMesh.overlapAtLeast(0.9))
        val (b, _) = execute(sparser, ChurnMesh.overlapAtLeast(0.9))

        assertEquals(DstOutcome.FAILED, a.outcome, a.summary())
        assertEquals(DstOutcome.FAILED, b.outcome, b.summary())
        assertEquals(a.failingCheck?.message, b.failingCheck?.message, "the identity must not move with the run")

        val detailA = assertNotNull(a.failingCheck?.error as? ChurnCheckFailure).detail
        val detailB = assertNotNull(b.failingCheck?.error as? ChurnCheckFailure).detail
        assertTrue(detailA != detailB, "the run-varying half must still be visible, and it differs: $detailA / $detailB")
        assertTrue(detailA.contains("achieved="), detailA)
        assertTrue(
            (a.failingCheck?.error?.suppressed?.singleOrNull() as? ChurnDetail)?.message == detailA,
            "the detail reaches a human as a suppressed throwable",
        )
    }

    /** The positive control: writes clustered around the departure genuinely overlap it. */
    @Test
    fun `writes clustered around a departure achieve the overlap they configured`() {
        val plan = ChurnPlan(
            seed = 25L,
            config = config(),
            peers = peers,
            events = joins(1) + listOf(DepartEvent("depart-peer1", "peer1", 600, DepartureMode.EVICT_CLEAN)),
            writeSchedule = writes(count = 40, from = 590, stride = 1),
        )

        val (report, world) = execute(plan, ChurnMesh.overlapAtLeast(0.5))
        val overlap = ChurnMesh.overlapOf(world)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        // The exact set of fired ids, for the same reason every green test in this feature
        // carries it: `inFlight` is computed from the PLAN's event steps, not from what the
        // adversary did, so a departure that never fired leaves this test's own claim — that
        // the writes overlapped a *departure* — false while every number below still holds.
        // Measured: with `DepartEvent.onStep` silenced this test stayed green without it.
        assertEquals(
            plan.events.map { it.id }.toSet(),
            report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet(),
            report.summary(),
        )
        // Writes at steps 590..629; the departure's window is [600, 700), so thirty of forty
        // land while the departure is in flight.
        assertEquals(30, overlap.opsDuringChurn, overlap.summary())
        assertEquals(0.75, overlap.achieved, 1e-9, overlap.summary())
    }
}
