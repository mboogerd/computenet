package civictech.testkit.dst

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **BS-2 — same seed, same trace** ([CHA1-04], [CHA1-05], [CHA1-30], [CHA1-33]).
 *
 * The empty-plan half of BS-2 is the one that can go quietly wrong, so it is asserted three
 * ways: the rig's digest equals the bare-controller drive's digest, the two agree on step
 * count, and *both* agree with a literal `SimulationController.runToIdle` of the same graph.
 * The third is what keeps the first from comparing the rig with itself — see [DstBaseline] for
 * why a digest cannot be collected from the literal kernel call, and what that pair can and
 * cannot detect.
 */
class TraceDigestTest {

    @Test
    fun `same seed and plan produce the same trace digest`() {
        val run = DstRun(SelfTestGraphs.crossTalk(), FaultPlan.empty(41))
        val first = run.execute()
        val second = run.execute()

        assertEquals(first.traceDigest, second.traceDigest)
        assertEquals(first.steps, second.steps)
        assertTrue(first.trace.isNotEmpty(), "a digest over an empty trace would prove nothing")
    }

    @Test
    fun `the digest self-check is available to any consumer suite`() {
        val report = DstRun(SelfTestGraphs.crossTalk(), FaultPlan.empty(41)).assertDeterministic(runs = 3)
        assertEquals(DstOutcome.PASSED, report.outcome)
    }

    @Test
    fun `the self-check actually discriminates - a non-deterministic run fails it`() {
        var call = 0
        val failure = assertFailsWith<AssertionError> {
            TraceDigests.assertSameDigest(runs = 2, label = "deliberately unstable") { _ ->
                call++
                listOf(TraceEvent(step = 0, host = "h"), TraceEvent(step = call, host = "h"))
            }
        }
        assertTrue(failure.message!!.contains("is not deterministic"))
        assertTrue(failure.message!!.contains("first divergence at event 1"), failure.message!!)
    }

    @Test
    fun `with an empty plan the rig's digest equals a bare controller drive of the same graph`() {
        val graph = SelfTestGraphs.crossTalk()
        val seed = 41L

        val rig = DstRun(graph, FaultPlan.empty(seed)).execute()
        val baseline = DstBaseline.run(graph, seed)
        val kernelSteps = DstBaseline.runToIdleSteps(graph, seed)

        assertEquals(baseline.digest, rig.traceDigest, "observation must not perturb what the controller does")
        assertEquals(baseline.steps, rig.steps)
        assertEquals(
            kernelSteps,
            rig.steps,
            "the rig's own loop is SimulationController.runToIdle's loop, step for step ([CHA1-04])",
        )
    }

    @Test
    fun `empty-plan equality holds across seeds, and the seed genuinely changes the run`() {
        val graph = SelfTestGraphs.crossTalk(chains = 5, rounds = 8)
        val digests = mutableSetOf<TraceDigest>()
        for (seed in 1L..8L) {
            val rig = DstRun(graph, FaultPlan.empty(seed)).execute()
            assertEquals(DstBaseline.run(graph, seed).digest, rig.traceDigest, "seed $seed")
            assertEquals(DstBaseline.runToIdleSteps(graph, seed), rig.steps, "seed $seed")
            digests += rig.traceDigest
        }
        assertTrue(
            digests.size > 1,
            "if every seed produced one digest the equality above would be vacuous — the graph must interleave",
        )
    }

    @Test
    fun `a fault that fires changes the digest`() {
        val graph = SelfTestGraphs.crossTalk()
        val clean = DstRun(graph, FaultPlan.empty(41)).execute()
        val faulted = DstRun(
            graph,
            FaultPlan.of(
                41,
                ScriptedFault(
                    id = "drop-a->b",
                    targets = listOf(FaultTarget.Edge("a->b")),
                    onInstall = { world ->
                        world.edges.intercept("a->b") { _, _ ->
                            world.trace.fault("drop-a->b", port = "a->b")
                            emptyList()
                        }
                    },
                ),
            ),
        ).execute()

        assertNotEquals(clean.traceDigest, faulted.traceDigest)
        assertTrue(faulted.appliedFaults.single().fired > 0, "the injector must actually inject")
        assertTrue(faulted.steps < clean.steps, "dropped frames mean strictly less work")
    }

    @Test
    fun `the digest is a function of the ordered trace, not of its contents as a set`() {
        val a = TraceEvent(step = 1, host = "h", port = "p")
        val b = TraceEvent(step = 2, host = "h", port = "q")
        assertNotEquals(TraceDigest.of(listOf(a, b)), TraceDigest.of(listOf(b, a)))
        assertEquals(TraceDigest.of(listOf(a, b)), TraceDigest.of(listOf(a, b)))
        assertEquals(TraceDigest.EMPTY, TraceDigest.of(emptyList()))
    }
}
