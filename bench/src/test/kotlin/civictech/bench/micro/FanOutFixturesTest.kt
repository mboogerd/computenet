package civictech.bench.micro

import civictech.bench.Drive
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Fast, **untagged** guards on [FanOutFixtures] — the companion to
 * [FanOutScalingBenchmark], which is a JMH artifact and never runs by default.
 *
 * These exercise the rig at a tiny [FanDegree] (D4) with a handful of deltas rather than
 * the benchmark's parameterized sweep up to D256, so what they check is that the
 * instrument still *works*, not what it measures. Every test here runs on every
 * `./gradlew :bench:test` and stays sub-second.
 *
 * The one that matters most is the unlinked-rig negative control. Every observable this
 * file's rig offers is read at the collectors, so a rig whose links silently failed to
 * establish would seed, apply and quiesce without throwing and report a beautifully
 * plausible-looking zero — indistinguishable from "nothing happened yet" unless something
 * asserts the difference. [Wiring.UNLINKED] (reused from `Graphs.kt` — see
 * `FanOutFixtures`' header for why it is not redeclared here) is what lets these tests
 * assert that difference on every run.
 */
class FanOutFixturesTest {

    @Test
    fun `an unlinked SIM rig observes nothing, though the source drains cleanly`() {
        FanOutFixtures.rig(FanDegree.D4, Drive.SIM, Wiring.UNLINKED).use { rig ->
            rig.applyOneAndQuiesce()
            rig.totalArrivals shouldBe 0L
            rig.collectors.forEach { it.total.get() shouldBe 0L }
            // The add itself still happened — this is a wiring control, not a claim that
            // nothing was driven at all.
            rig.elementsAdded shouldBe 1
        }
    }

    @Test
    fun `an unlinked REAL rig observes nothing, though the host queue provably drained`() {
        FanOutFixtures.rig(FanDegree.D4, Drive.REAL, Wiring.UNLINKED).use { rig ->
            rig.applyOneAndQuiesce()
            rig.totalArrivals shouldBe 0L
            rig.collectors.forEach { it.total.get() shouldBe 0L }
            rig.elementsAdded shouldBe 1
        }
    }

    @Test
    fun `every one of D4's collectors observes every delta exactly once under SIM`() {
        FanOutFixtures.rig(FanDegree.D4, Drive.SIM).use { rig ->
            rig.collectors.size shouldBe FanDegree.D4.subscribers
            repeat(TRIALS) { rig.applyOneAndQuiesce() }
            rig.elementsAdded shouldBe TRIALS
            // Not just the sum: EACH collector, individually, saw EVERY delta — a fan-out
            // that silently dropped one attachment while the others kept working would
            // still pass a total-arrivals-only check.
            rig.collectors.forEach { it.total.get() shouldBe TRIALS.toLong() }
            rig.totalArrivals shouldBe TRIALS.toLong() * FanDegree.D4.subscribers
        }
    }

    @Test
    fun `every one of D4's collectors observes every delta exactly once under REAL`() {
        FanOutFixtures.rig(FanDegree.D4, Drive.REAL).use { rig ->
            rig.collectors.size shouldBe FanDegree.D4.subscribers
            repeat(TRIALS) { rig.applyOneAndQuiesce() }
            rig.elementsAdded shouldBe TRIALS
            rig.collectors.forEach { it.total.get() shouldBe TRIALS.toLong() }
            rig.totalArrivals shouldBe TRIALS.toLong() * FanDegree.D4.subscribers
        }
    }

    @Test
    fun `a linked D1 rig behaves like a plain single-consumer link`() {
        // The degree-1 edge: fan-out of one is not a special case in the rig's own code,
        // but it is worth pinning that the smallest FanDegree still wires and drives
        // cleanly, since FanOutScalingBenchmark's sweep starts here.
        FanOutFixtures.rig(FanDegree.D1, Drive.REAL).use { rig ->
            rig.collectors.size shouldBe 1
            rig.applyOneAndQuiesce() shouldBe 1L
            rig.collectors.single().total.get() shouldBe 1L
        }
    }

    @Test
    fun `applyOneAndQuiesce reports positive elapsed work each call`() {
        // Not a timing assertion — a correctness one: the returned arrival count must
        // actually reflect the drive, not a stale read taken before quiescence.
        FanOutFixtures.rig(FanDegree.D16, Drive.REAL).use { rig ->
            val first = rig.applyOneAndQuiesce()
            first shouldBeGreaterThan 0L
            val second = rig.applyOneAndQuiesce()
            second shouldBeGreaterThan first
        }
    }

    @Test
    fun `FanDegree spans at least four degrees over at least two orders of magnitude`() {
        val degrees = FanDegree.values().map { it.subscribers }
        degrees shouldBe listOf(1, 4, 16, 64, 256)
        degrees.size shouldBe 5
        // BEN1-19's own bar: "at least four degrees spanning at least two orders of
        // magnitude" — 256/1 = 256, i.e. ~2.4 decades.
        (degrees.max().toDouble() / degrees.min().toDouble()) shouldBe 256.0
    }

    @Test
    fun `every fan-out rig carries the drive it was built with`() {
        FanOutFixtures.rig(FanDegree.D1, Drive.SIM).use { rig -> rig.drive shouldBe Drive.SIM }
        FanOutFixtures.rig(FanDegree.D1, Drive.REAL).use { rig -> rig.drive shouldBe Drive.REAL }
    }

    private companion object {
        /** Deltas applied per condition in these guards — small, since they check the instrument, not a curve. */
        const val TRIALS: Int = 3
    }
}
