package civictech.bench.micro

import civictech.bench.Drive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fast, **untagged** guards on the late-join catch-up fixtures — the companion to
 * `LateJoinCatchUpProbeTest`, which is `@Tag("bench")` and never runs by default.
 *
 * The division of labour is the one `BoundedReadFixturesTest` sets out: the probe is a
 * measurement entry point that takes minutes at 10^5 and is excluded from `:bench:test`
 * unconditionally by F2's gate (`[BEN1-10]`), so a regression in the instrument would
 * otherwise go unobserved between deliberate sweeps. These tests run on every
 * `./gradlew :bench:test` and stay fast, because they work at 10^3 elements with 10-add
 * drives rather than the probe's 8,000-add bursts. What they check is that the instrument
 * still *works*, not what it measures.
 *
 * Three of them are negative controls, and they are the reason this file exists rather than
 * being a formality:
 *
 * - **A join that never happens** must report [CatchUpOutcome.NoBaseline]. Every catch-up
 *   observable is read at the joiner's own collector, so a rig whose `connect` silently
 *   failed would spawn, wait, quiesce and throw nothing — and a fixture that reported a
 *   duration anyway would publish a beautifully fast catch-up of nothing.
 * - **Joining an empty source** must also report `NoBaseline`, which is `CatchUp.kt`'s own
 *   empty-state guard observed rather than assumed.
 * - **The trial teardown** must really detach the joiner. If `unlink()` were a no-op the
 *   probe's fan-out degree would climb trial over trial (`CatchUpFixtures.kt`'s header,
 *   drift 2) and the occupancy column would carry a systematic trend that reads as
 *   dispersion. Nothing else in the suite would notice.
 */
class CatchUpFixturesTest {

    @Test
    fun `a late joiner catches up the whole state as one delta-from-empty`() {
        CatchUpFixtures.rig(TINY).use { rig ->
            rig.seed()
            rig.elementsAdded shouldBe TINY.elements

            val outcome = rig.joinSubscriber().shouldBeInstanceOf<CatchUpOutcome.Baseline>()
            // The catch-up path sends the WHOLE tag state in ONE delta-from-empty
            // (SetCell.kt:264 -> CatchUp.kt's catchUpOnLinked -> FanOutlet.at().propagate),
            // so on a quiescent rig the joiner's very first and only arrival carries every
            // element. An implementation that dribbled the state out in pieces, or shipped
            // a since-filtered slice, would fail here rather than be averaged into a
            // suspiciously cheap number.
            outcome.arrivals shouldBe 1
            outcome.adds shouldBe TINY.elements
            outcome.expectedElements shouldBe TINY.elements
            // Nothing is ever removed in this rig, so there are no tombstones to ship —
            // asserted rather than ignored, because catch-up ships `dels` too and a
            // non-zero count here would mean the rig is not the shape the probe assumes.
            outcome.dels shouldBe 0
            outcome.catchUpMs shouldBeGreaterThan 0.0
        }
    }

    @Test
    fun `a join that never happens reports no baseline arrival, not a fast catch-up`() {
        CatchUpFixtures.rig(TINY, joinWiring = JoinWiring.UNLINKED).use { rig ->
            rig.seed()

            val outcome = rig.joinSubscriber().shouldBeInstanceOf<CatchUpOutcome.NoBaseline>()
            // The joiner was spawned and the host's queue provably drained (the fence, not
            // a poll), and still nothing arrived — because nothing was linked. A rig that
            // produced a `Baseline` here would mean the join switch does nothing and the
            // negative control is decorative.
            outcome.arrivals shouldBe 0
            outcome.largestArrivalAdds shouldBe 0
            outcome.expectedElements shouldBe TINY.elements
            // The state was there to be caught up; the link was not. That distinction is
            // the whole content of this control.
            rig.elementsAdded shouldBe TINY.elements
        }
    }

    @Test
    fun `joining an empty source reports no baseline, which is catchUpOnLinked's own empty-state guard`() {
        // Deliberately NOT seeded. CatchUp.kt: "snapshot returns null when there is
        // nothing to catch up (the empty-state guard every hand-rolled copy of this block
        // carried)", and SetCell's installed snapshot returns null while adds and dels are
        // both empty. So a link against an empty source sends NOTHING — and a fixture that
        // reported a 0.0 ms catch-up for it would be reporting the guard as a measurement.
        CatchUpFixtures.rig(TINY).use { rig ->
            rig.elementsAdded shouldBe 0

            val outcome = rig.joinSubscriber().shouldBeInstanceOf<CatchUpOutcome.NoBaseline>()
            outcome.expectedElements shouldBe 0
            outcome.arrivals shouldBe 0
        }
    }

    @Test
    fun `the trial teardown really detaches the joiner, so fan-out degree does not drift`() {
        CatchUpFixtures.rig(TINY).use { rig ->
            rig.seed()
            val pending = rig.prepareJoiner()
            pending.join().shouldBeInstanceOf<CatchUpOutcome.Baseline>()
            pending.arrivals shouldBe 1L

            // While linked, the joiner is an ordinary subscriber and sees live traffic.
            // Asserted first so the assertion after the detach is a CHANGE and not a
            // property the joiner had all along — without this half, a joiner that never
            // received anything would pass the detach check vacuously.
            rig.drive(DRIVE)
            pending.arrivals shouldBe (1L + DRIVE)
            val attached = pending.arrivals

            pending.close()
            pending.detached shouldBe true

            // After the detach the source fans out to the pre-existing subscriber only.
            // `Link.unlink()` runs FanOutlet.unsubscribe -> removeConsumer, so this is the
            // consumer entry being gone rather than deliveries being dropped somewhere.
            val after = rig.drive(DRIVE)
            after.arrivals shouldBe DRIVE
            pending.arrivals shouldBe attached
            // close() is idempotent — the probe calls it through `use` as well as
            // explicitly, and a second detach must not throw or re-fence.
            pending.close()
            pending.arrivals shouldBe attached
        }
    }

    @Test
    fun `the completeness fence is armed at the settled count, while the baseline ships the grown state`() {
        // The regression guard for the defect the probe's own refusal branch caught: the
        // fence used to be armed at `elementsAdded` read just before `connect`, which counts
        // adds the driving thread has ENQUEUED, not adds the source has APPLIED. Under a
        // concurrent burst the source runs thousands behind its inbox, so the threshold was
        // unsatisfiable and every trial waited out the drain timeout reporting no baseline
        // (measured at 1e5: fence 110,442, baseline 109,554).
        //
        // Growing the source between `prepareJoiner()` and `join()` reproduces the same
        // ordering sequentially: the joiner's threshold must be the count settled at prepare
        // time, while the baseline it receives must carry the larger, current state.
        CatchUpFixtures.rig(TINY).use { rig ->
            rig.seed()
            val pending = rig.prepareJoiner()
            pending.settledElements shouldBe TINY.elements

            rig.drive(DRIVE)

            val outcome = pending.join().shouldBeInstanceOf<CatchUpOutcome.Baseline>()
            // Armed at the settled prepare-time count...
            outcome.expectedElements shouldBe TINY.elements
            // ...and satisfied by a baseline carrying everything the source holds NOW. A
            // fence armed at the current count would be the defect; a baseline that only
            // carried the prepare-time state would mean catch-up ships a stale snapshot.
            outcome.adds shouldBe (TINY.elements + DRIVE)
        }
    }

    @Test
    fun `a joiner cannot be joined twice, because a link fires catch-up exactly once`() {
        CatchUpFixtures.rig(TINY).use { rig ->
            rig.seed()
            rig.prepareJoiner().use { pending ->
                pending.join().shouldBeInstanceOf<CatchUpOutcome.Baseline>()
                // A second join would establish nothing and time an empty wait to the
                // drain timeout, then report NoBaseline for a joiner that HAD caught up.
                shouldThrow<IllegalStateException> { pending.join() }
                    .message!! shouldContain "once per joiner"
            }
        }
    }

    @Test
    fun `the pre-existing subscriber observes one arrival per live add`() {
        CatchUpFixtures.rig(TINY).use { rig ->
            val outcome = rig.drive(DRIVE)
            outcome.arrivals shouldBe DRIVE
            rig.existingArrivals shouldBe DRIVE.toLong()
            assertTrue(outcome.durationMs > 0.0, "drive duration must be positive")
            // maxGap is widened to cover the interval from the drive's start to the first
            // arrival, so it is strictly positive for any drive that observed anything —
            // and it is the occupancy statistic the probe reports, so a zero here would
            // mean the instrument reports nothing.
            assertTrue(outcome.maxGapMs > 0.0, "maxGap must be positive when arrivals exist")
        }
    }

    @Test
    fun `an unlinked rig observes nothing, which is what makes the occupancy assertion mean something`() {
        CatchUpFixtures.rig(TINY, wiring = RigWiring.UNLINKED).use { rig ->
            val outcome = rig.drive(DRIVE)
            // Adds enqueued, host's queue provably drained, nothing arrived. A rig that
            // reported arrivals here would mean the wiring switch does nothing and the
            // occupancy column is measured over an unwired graph.
            outcome.arrivals shouldBe 0
            outcome.maxGapMs shouldBe 0.0
            rig.existingArrivals shouldBe 0L
        }
    }

    @Test
    fun `a Baseline cannot claim a payload smaller than the state it caught up`() {
        // The completeness fence is `adds >= expectedElements`. If a future change armed
        // the threshold after the link, or armed it at the wrong size, the first live add
        // could satisfy it and be reported as the baseline — so the type refuses the
        // arithmetic that would make that observable rather than trusting the caller.
        shouldThrow<IllegalArgumentException> {
            CatchUpOutcome.Baseline(
                catchUpMs = 1.0,
                adds = 1,
                dels = 0,
                expectedElements = 1_000,
                arrivals = 1,
            )
        }.message!! shouldContain "completeness fence"

        shouldThrow<IllegalArgumentException> {
            CatchUpOutcome.Baseline(
                catchUpMs = -1.0,
                adds = 1_000,
                dels = 0,
                expectedElements = 1_000,
                arrivals = 1,
            )
        }.message!! shouldContain "non-negative"
    }

    @Test
    fun `every catch-up result is REAL-driven, because there is no simulated variant`() {
        // Occupancy of a REAL execution context is the question BS-9 asks; a
        // SimulationController interleaves the join and the live traffic on one
        // deterministic thread and dissolves the contention entirely.
        CatchUpFixtures.DRIVE shouldBe Drive.REAL
    }

    @Test
    fun `the probe's drive shape is the bounded-read probe's, by reference and not by copy`() {
        // The occupancy column here and E2's are the same statistic over the same shape of
        // drive, and they are only comparable while the two cannot diverge. Pinned so a
        // later edit to one probe's burst size cannot silently decouple them.
        CatchUpFixtures.DRIVE_ADDS shouldBe BoundedReadFixtures.DRIVE_ADDS
        CatchUpFixtures.WARMUP_ADDS shouldBe BoundedReadFixtures.WARMUP_ADDS
        CatchUpFixtures.JOIN_DELAY_MS shouldBe BoundedReadFixtures.READ_DELAY_MS
        CatchUpFixtures.DRAIN_TIMEOUT_MS shouldBe BoundedReadFixtures.DRAIN_TIMEOUT_MS
        // BS-9's own pre-seed size.
        CatchUpFixtures.DEFAULT_SCALE shouldBe SetScale.N1E5
        // TrialStats refuses to state a dispersion over fewer than two samples, so a trial
        // constant below two would make the probe unable to construct a BenchResult at all.
        assertTrue(
            CatchUpFixtures.TRIALS >= 2,
            "TrialStats refuses fewer than 2 samples, was ${CatchUpFixtures.TRIALS}",
        )
        assertTrue(CatchUpFixtures.WARMUP_TRIALS > 0, "RunEnvironment refuses a non-positive warmup")
    }

    @Test
    fun `the probe environment states the statistic, the trial counts and this process as measurer`() {
        val previous = System.getProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY)
        try {
            System.setProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY, "cafebabe")
            val env = CatchUpFixtures.probeRunEnvironment("catch-up ms per late join")
            env.harnessCommitSha shouldBe "cafebabe"
            env.measurementIterations shouldBe CatchUpFixtures.TRIALS
            env.warmupIterations shouldBe CatchUpFixtures.WARMUP_TRIALS
            env.forkCount shouldBe 1
            env.jmhMode shouldContain "catch-up ms per late join"
            env.jmhMode shouldContain "not JMH"
        } finally {
            if (previous == null) System.clearProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY)
            else System.setProperty(BoundedReadFixtures.HARNESS_SHA_PROPERTY, previous)
        }
    }

    private companion object {
        /**
         * The scale these guards run at: 10^3 elements, the smallest [SetScale], not the
         * probe's 10^5. These tests check the instrument and every one of them runs on the
         * default `:bench:test`, which `[BEN1-10]`'s gate exists to keep fast.
         */
        val TINY: SetScale = SetScale.N1E3

        /** Adds per drive in these guards: 10, not the probe's 8,000. */
        const val DRIVE: Int = 10
    }
}
