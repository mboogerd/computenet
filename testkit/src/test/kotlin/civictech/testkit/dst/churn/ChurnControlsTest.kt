package civictech.testkit.dst.churn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The blocking discrimination set ([CHA3-70]…[CHA3-74], feature computenet-umx.2 §4.8,
 * decomposition item umx.2-D5): "A green churn sweep without these is not evidence." Every
 * control here proves a specific check CAN fail, before trusting any sweep in which it passes.
 *
 * The scenarios themselves — what each control does and why it is expected to diverge — are
 * documented on [ControlSeams], the file this task's own claim also amends alongside
 * [ReconvergenceCheck] and [MeshPeer] (see their KDoc for the two control seams:
 * [MeshPeer.suppressOutboundDeliveries]/[MeshPeer.accumulateDuplicateSubscription] and
 * [ReconvergenceCheck.disablingDepartedStreamRule]). This file only drives them and asserts.
 *
 * Every test below runs the SAME hand-built scenario across a small handful of seeds — mirroring
 * `PartitionFaultTest`'s own `[CHA1-62]`/`[CHA1-63]` diverging-control precedent
 * (`forEachSeed(0L until 8L)`) — rather than asserting one fixed seed diverges: the class-level
 * KDoc on [ControlSeams] explains why the *topology* is fixed rather than generated (so there is
 * no traffic-vs-window sizing risk to begin with), and the seed sweep is the belt-and-braces
 * check that nothing about a specific seed's derived ids is secretly load-bearing.
 */
class ChurnControlsTest {

    private companion object {
        /** Seeds distinct from every other churn-controls suite's own seed choices. */
        val SEEDS = 9001L..9005L
    }

    // ------------------------------------------------------------------------------- BS-3

    @Test
    fun `BS-3 suppressed final catch-up is detected as a fold mismatch on every seed`() {
        val results = SEEDS.map { seed -> seed to ControlSeams.suppressedFinalCatchUp(seed) }
        val inert = results.filter { (_, failure) -> failure == null }
        assertTrue(
            inert.isEmpty(),
            "suppressed-catch-up control was inert (converged instead of diverging) at seeds: " +
                inert.map { it.first },
        )
        results.forEach { (seed, failure) ->
            requireNotNull(failure) { "seed $seed" }
            assertEquals(ReconvergenceCheck.DIVERGED, failure.message, "seed $seed :: ${failure.detail}")
            assertTrue(
                failure.detail.contains("peer0-9001"),
                "seed $seed :: the failure must name the specific lost write: ${failure.detail}",
            )
        }
    }

    // ------------------------------------------------------------------------------- BS-4

    @Test
    fun `BS-4 disabling the departed-stream rule breaks the check on every seed`() {
        val results = SEEDS.map { seed -> seed to ControlSeams.departedStreamRuleDisabled(seed) }
        val inert = results.filter { (_, failure) -> failure == null }
        assertTrue(
            inert.isEmpty(),
            "departed-stream-rule-disabled control was inert at seeds: ${inert.map { it.first }}",
        )
        results.forEach { (seed, failure) ->
            requireNotNull(failure) { "seed $seed" }
            assertEquals(ReconvergenceCheck.DIVERGED, failure.message, "seed $seed :: ${failure.detail}")
        }
    }

    // ---------------------------------------------------------------------------- [CHA3-73]

    @Test
    fun `CHA3-73 an accumulating rejoin fails the subscription-bound check on every seed`() {
        val results = SEEDS.map { seed -> seed to ControlSeams.accumulatingRejoin(seed) }
        val inert = results.filter { (_, failure) -> failure == null }
        assertTrue(inert.isEmpty(), "accumulating-rejoin control was inert at seeds: ${inert.map { it.first }}")
        results.forEach { (seed, failure) ->
            requireNotNull(failure) { "seed $seed" }
            assertEquals(
                GossipInstruments.SUBSCRIPTIONS_EXCEED_MEMBERSHIP,
                failure.message,
                "seed $seed :: ${failure.detail}",
            )
        }
    }

    // ------------------------------------------------------------------------------ BS-20

    /**
     * [CHA3-74]'s positive form: at a representative seed, every one of the four controls
     * (the three above plus the departure-gates task's PN-0c control) diverges — the harness's
     * self-test PASSES because nothing is unproven.
     */
    @Test
    fun `BS-20 every control diverges, so the self-test itself passes`() {
        SEEDS.forEach { seed ->
            val results = ControlSeams.selfTest(seed)
            assertEquals(4, results.size, "seed $seed :: expected all four controls to report")
            val inert = results.filterNot { it.diverged }
            assertTrue(
                inert.isEmpty(),
                "seed $seed :: the following controls did not diverge: ${inert.map { it.name }}; full: $results",
            )
            // assertAllDiverge is the mechanism a sweep composes with; exercise it too so a
            // regression in its own inert-detection logic is caught here rather than only when
            // some future control silently goes inert.
            ControlSeams.assertAllDiverge(seed)
        }
    }

    /**
     * [CHA3-74]'s negative form — the self-test's OWN failure mode, mirroring
     * `PartitionFaultTest`'s `[CHA1-63]` shape ("If this fails, the rig's own self-test fails").
     * A control that does NOT diverge must fail [ControlSeams.assertAllDiverge] loudly, naming
     * itself, rather than being silently absorbed into a passing sweep.
     *
     * This calls [ControlSeams.assertNoneInert] — the exact function [ControlSeams.assertAllDiverge]
     * delegates to — directly, against a synthetic result set, rather than reimplementing its
     * `check` inline: an inline reimplementation only proves the *rule* is right, not that
     * production code runs it. A prior version of this test did reimplement it, and review found
     * that a broken [ControlSeams.assertAllDiverge] (its own `check` weakened to always pass) left
     * every test in this class green, including both BS-20 tests — an aggregator that can only
     * pass. This form fails if that regresses.
     */
    @Test
    fun `BS-20 a control that does not diverge fails the harness's self-test`() {
        val fakeInert = ControlSeams.ControlOutcome(
            name = "synthetic inert control",
            diverged = false,
            detail = "stand-in: never actually run against the mesh",
        )
        val allDiverged = listOf(
            ControlSeams.ControlOutcome("BS-3", diverged = true, detail = "ok"),
            ControlSeams.ControlOutcome("BS-4", diverged = true, detail = "ok"),
            ControlSeams.ControlOutcome("CHA3-73", diverged = true, detail = "ok"),
        )
        val withOneInert = allDiverged + fakeInert

        val message = try {
            ControlSeams.assertNoneInert(seed = 9001L, results = withOneInert)
            fail("expected assertNoneInert to fail loudly given an inert control")
        } catch (e: IllegalStateException) {
            e.message.orEmpty()
        }
        assertTrue(
            message.contains(fakeInert.name),
            "the self-test failure must NAME the control that did not diverge: $message",
        )

        // The all-diverging case must NOT throw — otherwise the assertion above would pass for
        // the wrong reason (assertNoneInert always throwing, inert control or not).
        ControlSeams.assertNoneInert(seed = 9001L, results = allDiverged)
    }
}
