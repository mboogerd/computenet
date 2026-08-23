package civictech.testkit.dst

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **BS-12 — a fault aimed at nothing fails the run, loudly** ([CHA1-23]).
 *
 * The failure this pins is not the typo; it is the *silent* run that follows one. A plan
 * naming `loop01` when the graph declared `loop-01` would otherwise apply nothing, quiesce,
 * pass its check, and be reported as a graph that survived a partition it never saw.
 */
class FaultTargetValidationTest {

    private fun planFor(target: FaultTarget) =
        FaultPlan.of(1, ScriptedFault(id = "typo", targets = listOf(target)))

    @Test
    fun `an unknown edge, host, cell or journal fails the run, naming the target and the known set`() {
        val cases = listOf(
            FaultTarget.Edge("no-such-edge") to ("edge" to "e"),
            FaultTarget.Host("no-such-host") to ("host" to "h"),
            FaultTarget.Cell("no-such-cell") to ("cell" to "c"),
            FaultTarget.Journal("no-such-journal") to ("journal" to "j"),
        )
        for ((target, expectation) in cases) {
            val (kind, known) = expectation
            val failure = assertFailsWith<UnknownFaultTargetException> {
                DstRun(SelfTestGraphs.inert(), planFor(target)).execute()
            }
            assertEquals("typo", failure.faultId)
            assertEquals(target, failure.target)
            assertEquals(setOf(known), failure.known, "the known set is what the graph declared")

            val message = failure.message!!
            assertTrue(message.contains(target.name), message)
            assertTrue(message.contains("unknown $kind"), message)
            assertTrue(message.contains("known ${kind}s: [$known]"), message)
        }
    }

    @Test
    fun `validation runs before any fault is installed`() {
        var installed = false
        val plan = FaultPlan.of(
            1,
            ScriptedFault(id = "installs-first", onInstall = { installed = true }),
            ScriptedFault(id = "typo", targets = listOf(FaultTarget.Edge("no-such-edge"))),
        )

        assertFailsWith<UnknownFaultTargetException> { DstRun(SelfTestGraphs.inert(), plan).execute() }
        assertFalse(installed, "a run with a typo'd target must not half-apply its plan")
    }

    @Test
    fun `a fault naming a declared target of every kind is accepted`() {
        val plan = FaultPlan.of(
            1,
            ScriptedFault(
                id = "well-aimed",
                targets = listOf(
                    FaultTarget.Edge("e"),
                    FaultTarget.Host("h"),
                    FaultTarget.Cell("c"),
                    FaultTarget.Journal("j"),
                ),
            ),
        )
        val report = DstRun(SelfTestGraphs.inert(), plan).execute()
        assertEquals(DstOutcome.PASSED, report.outcome)
        assertTrue(report.appliedFaults.single().inert, "aimed at something real, and still never fired")
    }

    @Test
    fun `a plan cannot carry two faults with the same id`() {
        val clash = assertFailsWith<IllegalArgumentException> {
            FaultPlan.of(1, ScriptedFault(id = "dup"), ScriptedFault(id = "dup"))
        }
        assertTrue(clash.message!!.contains("dup"))
    }

    @Test
    fun `the plan's seed is the run's seed, and shrinking a plan cannot change it`() {
        val plan = FaultPlan.of(41, ScriptedFault(id = "a"), ScriptedFault(id = "b"))
        assertEquals(41L, DstRun(SelfTestGraphs.inert(), plan).seed)

        val shrunk = plan.without("a")
        assertEquals(41L, shrunk.seed)
        assertEquals(listOf("b"), shrunk.faults.map { it.id })
    }
}
