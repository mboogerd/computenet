package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.UnknownFaultTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BS-16, as a named test: the two ways a churn plan can be **wrong about the graph**, and the
 * different answers each gets.
 *
 *  - A plan naming a peer the mesh never declared is a *broken experiment*: it fails immediately,
 *    before the first step, naming the unknown peer and the whole known set. No run happens, and
 *    there is nothing to mistake for a verdict.
 *  - A plan whose event is scheduled past the point the run quiesces is a *weaker experiment than
 *    it claims*: the run is legitimate, but the event is marked `fired = 0` inert and the report
 *    says so. A suite that meant the event to fire turns that into a failure with
 *    [ChurnMesh.allEventsFired]; one that did not, does not.
 *
 * The distinction matters because collapsing them either way is a real failure mode: aborting on
 * a late event would make an ordinary shrink (`atStep` toward the horizon) unrunnable, and
 * silently tolerating an unknown peer would let a typo'd plan report PASSED having applied
 * nothing.
 */
class ChurnTargetingTest {

    private companion object {
        const val BUDGET = 20_000
    }

    private val peers = List(3) { "peer$it" }

    private fun config(stepBudget: Int = 4000) = ChurnConfig(
        peerCount = 3..3,
        eventCount = 0,
        writeConcurrency = 0.0,
        opScriptLength = 0,
        stepBudget = stepBudget,
        suspendWindow = 4,
    )

    private fun writes(count: Int, from: Int = 2, stride: Int = 50): List<ChurnWrite> =
        (0 until count).map { i -> ChurnWrite(from + i * stride, "peer0", i) }

    private fun joins(step: Int): List<ChurnEvent> = peers.map { JoinEvent("join-$it", it, step) }

    // --------------------------------------------------------- BS-16, half one: unknown peer

    @Test
    fun `a plan naming an unknown peer fails immediately, naming the peer and the known set`() {
        val plan = ChurnPlan(
            seed = 31L,
            config = config(),
            peers = peers,
            events = joins(1),
            writeSchedule = writes(count = 5),
        )
        val spec = ChurnMesh.spec(plan, maxPeers = peers.size)

        // Built as a bare FaultPlan rather than a ChurnPlan on purpose: ChurnPlan's own `init`
        // rejects an event naming a peer off the roster, so the roster check and the *graph*
        // check are two different fences and this test is about the second one.
        val ghost = FaultPlan(plan.seed, listOf(JoinEvent("ghost-join", "ghost", 1)))

        val failure = assertFailsWith<UnknownFaultTargetException> {
            DstRun(spec, ghost, budget = BUDGET).execute()
        }

        assertEquals("ghost-join", failure.faultId)
        assertEquals("ghost", failure.target.name)
        assertEquals("peer", failure.target.kind)
        assertEquals(peers.toSet(), failure.known)
        val message = assertNotNull(failure.message)
        assertTrue(message.contains("\"ghost\""), message)
        peers.forEach { assertTrue(message.contains(it), "the known set is named so the reader need not search: $message") }
    }

    // ------------------------------------------------- BS-16, half two: past quiescence, inert

    @Test
    fun `an event scheduled past quiescence is marked fired=0 inert rather than failing the run`() {
        val plan = pastQuiescencePlan(seed = 32L, departAt = 3000)

        val spec = ChurnMesh.spec(plan, maxPeers = peers.size)
        val report = DstRun(spec, plan.toFaultPlan(), budget = BUDGET).execute()

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertTrue(report.steps < 3000, "the run has to quiesce before the event, or this proves nothing: ${report.steps}")

        // The joins fired; the departure did not, and the report says which.
        assertEquals(
            joins(1).map { it.id }.toSet(),
            report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet(),
            report.summary(),
        )
        assertEquals(listOf("depart-peer1"), report.inertFaults.map { it.id }, report.summary())
        assertEquals(0, report.appliedFaults.single { it.id == "depart-peer1" }.fired)
    }

    /**
     * The same run, under the check a suite installs when it *meant* the event to fire. The
     * report's inert marking is data; turning it into a verdict is the suite's choice, and this
     * is that choice made.
     */
    @Test
    fun `a suite that meant every event to fire turns the inert marking into a failure`() {
        val plan = pastQuiescencePlan(seed = 33L, departAt = 3000)

        val spec = ChurnMesh.spec(plan, maxPeers = peers.size)
        val report = DstRun(
            spec,
            plan.toFaultPlan(),
            budget = BUDGET,
            check = ChurnMesh.allEventsFired(plan),
        ).execute()

        assertEquals(DstOutcome.FAILED, report.outcome, report.summary())
        assertEquals(
            "churn plan truncated: the run quiesced before every planned event fired",
            report.failingCheck?.message,
        )
        val detail = assertNotNull(report.failingCheck?.error as? ChurnCheckFailure).detail
        assertTrue(detail.contains("depart-peer1@3000"), detail)
    }

    /**
     * The identity half of the same rule as [ChurnOverlapTest]: two truncated runs that differ in
     * *how much* was truncated must still report the same failing-check message, or a shrink of
     * the second would be graded against the first as "a different failure".
     */
    @Test
    fun `the truncation failure message is a fixed string across differently truncated runs`() {
        val one = pastQuiescencePlan(seed = 34L, departAt = 3000)
        val two = ChurnPlan(
            seed = 35L,
            config = config(),
            peers = peers,
            events = joins(1) + listOf(
                DepartEvent("depart-peer1", "peer1", 3000, DepartureMode.EVICT_CLEAN),
                DepartEvent("depart-peer2", "peer2", 3500, DepartureMode.CRASH_UNCLEAN),
            ),
            writeSchedule = writes(count = 5),
        )

        val reports = listOf(one, two).map { plan ->
            DstRun(
                ChurnMesh.spec(plan, maxPeers = peers.size),
                plan.toFaultPlan(),
                budget = BUDGET,
                check = ChurnMesh.allEventsFired(plan),
            ).execute()
        }

        assertEquals(DstOutcome.FAILED, reports[0].outcome)
        assertEquals(DstOutcome.FAILED, reports[1].outcome)
        assertEquals(reports[0].failingCheck?.message, reports[1].failingCheck?.message)
        val details = reports.map { assertNotNull(it.failingCheck?.error as? ChurnCheckFailure).detail }
        assertTrue(details[0] != details[1], "the counts differ and must live in detail: $details")
    }

    private fun pastQuiescencePlan(seed: Long, departAt: Int) = ChurnPlan(
        seed = seed,
        config = config(),
        peers = peers,
        events = joins(1) + listOf(DepartEvent("depart-peer1", "peer1", departAt, DepartureMode.EVICT_CLEAN)),
        // Five writes: the workload's horizon is step 202, so the heartbeat stops there and the
        // run drains long before step 3000. That is what makes the late event genuinely past
        // quiescence rather than merely late.
        writeSchedule = writes(count = 5),
    )
}
