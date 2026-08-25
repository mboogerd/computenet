package civictech.testkit

import civictech.testkit.dst.SweepFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `forEachSeed`'s thrown summary used to embed the run-varying failure count and first-failing
 * seed directly in `Throwable.message` — `"failed on N of M seeds; first: seed=K — <message>"`.
 * `FailurePredicate.sameFailingCheck` (`PlanShrinker.kt`) compares exactly that string to decide
 * whether a shrink candidate still reproduces the same failure, so a message that varies with
 * density defeats shrinking the moment a `DstCheck` body calls `forEachSeed` (computenet-e0to,
 * found while reviewing computenet-umx.4's PR).
 *
 * This pins the fix: the thrown message is the failure **mode** alone (here, the first failure's
 * own message) — identical across two runs of the same underlying assertion that differ only in
 * how many seeds failed — while the density stays visible to a human, via [SweepFailure.detail]
 * and as a suppressed throwable, exactly as computenet-umx.4 did for `DstSweepReport`.
 */
class ForEachSeedTest {

    /**
     * Two runs of the identical underlying assertion, same first-failing seed, different
     * densities (1 of 5 vs. 2 of 5). Before the fix, `failures.size` sits directly in the thrown
     * message, so these two messages differ — that is the failure this test is written to catch.
     * Quoted in the task report: the exact assertion message this produces against the unfixed
     * helper.
     */
    @Test
    fun `same failure mode at different densities produces byte-identical messages`() {
        val failureOneOfFive = assertFailsWith<AssertionError> {
            forEachSeed(0L..4L) { seed ->
                if (seed == 2L) throw AssertionError("expected 9 but got 3")
            }
        }

        val failureTwoOfFive = assertFailsWith<AssertionError> {
            forEachSeed(0L..4L) { seed ->
                if (seed == 2L || seed == 4L) throw AssertionError("expected 9 but got 3")
            }
        }

        assertEquals(
            failureOneOfFive.message,
            failureTwoOfFive.message,
            "same failure mode, different density (1 of 5 vs 2 of 5): messages must be byte-identical " +
                "so PlanShrinker's FailurePredicate.sameFailingCheck does not treat a shrink candidate " +
                "as a different failure",
        )
    }

    /**
     * [CHA1-39]'s density must stay visible to a human running the suite — split out of the
     * message, not deleted. `SweepFailure.detail` (and the suppressed `SweepDetail` it carries)
     * is where it lives, in exactly `forEachSeed`'s historical shape.
     */
    @Test
    fun `density and first-failing seed stay visible in the failure detail`() {
        val thrown = assertFailsWith<SweepFailure> {
            forEachSeed(0L..4L) { seed ->
                if (seed == 2L || seed == 4L) throw AssertionError("expected 9 but got 3")
            }
        }

        assertEquals("expected 9 but got 3", thrown.message, "the message is the failure mode alone")
        assertTrue(
            thrown.detail.startsWith("failed on 2 of 5 seeds; first: seed=2 — expected 9 but got 3"),
            "density and first-failing seed are preserved in the detail: ${thrown.detail}",
        )
        assertTrue(
            thrown.suppressed.any { it.message?.contains("failed on 2 of 5 seeds") == true },
            "the density is also printed as a suppressed throwable, so a plain test run shows it",
        )
    }

    /** The first failure remains [Throwable.cause], so an IDE's jump-to-failure still works. */
    @Test
    fun `the first failure is the thrown cause`() {
        val firstFailure = AssertionError("expected 9 but got 3")
        val thrown = assertFailsWith<AssertionError> {
            forEachSeed(0L..2L) { seed ->
                if (seed == 1L) throw firstFailure
                if (seed == 2L) throw AssertionError("expected 1 but got 2")
            }
        }

        assertEquals(firstFailure, thrown.cause, "the FIRST failure is the cause, not the last")
    }

    @Test
    fun `every seed still runs even after an earlier failure`() {
        val ran = mutableListOf<Long>()
        assertFailsWith<AssertionError> {
            forEachSeed(0L..4L) { seed ->
                ran += seed
                throw AssertionError("fails on every seed")
            }
        }

        assertEquals((0L..4L).toList(), ran, "forEachSeed never aborts early")
    }
}
