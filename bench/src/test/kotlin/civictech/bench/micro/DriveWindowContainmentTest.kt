package civictech.bench.micro

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

/**
 * Untagged guards on the drive-window containment knob — the fixture capability
 * `computenet-juid`'s entry names as the prerequisite for its discriminating run
 * (`computenet-otsg`).
 *
 * These run on every `./gradlew :bench:test`, deliberately, for the reason
 * `BoundedReadFixturesTest`'s KDoc gives: the probes that would exercise this are
 * `@Tag("bench")` and never run by default, so a knob checked only there would rot
 * unobserved between sweeps. They stay sub-second by walking a 10^3-element target at a
 * [GUARD_PAGE_LIMIT]-entry page limit rather than E3's 200.
 *
 * **What they pin is that the knob does what it says** — a forced non-contained
 * configuration actually produces non-containment and its matched contained control
 * actually produces containment. They pin nothing about the mechanism: no assertion here
 * compares `maxGap` against a page, and none may be added, because whether containment
 * explains the sign-unstable §6 reduction column is exactly the open question the
 * discriminating run on a quiesced host has to answer.
 *
 * The small page limit is not a convenience. Truncation cuts the drive at the walk's FIRST
 * page, so what makes the walk outlast the drive is the pages it still has to take; a
 * many-paged walk makes that remainder large against the drive's drain tail, which is what
 * turns a timing race into a structural one.
 */
class DriveWindowContainmentTest {

    /** Entries per page for the guards: small, so a 10^3 walk takes ~125 pages. */
    private val GUARD_PAGE_LIMIT = 8

    /** Nominal budget per guard drive — a fraction of `CONTAINMENT_ADDS`, for speed. */
    private val GUARD_ADDS = 400

    /**
     * The covering arm's guard budget: **one add**, so containment cannot come from the
     * budget and can only come from the extension.
     *
     * A larger budget made the covering guard vacuous, and it was caught by mutation: with
     * a 400-add budget at the shared 50 us spacing the nominal window is ~20 ms, which a
     * 10^3 walk at [GUARD_PAGE_LIMIT] finishes inside anyway — so the test passed with
     * `extendWhile` neutered to `{ false }` and pinned nothing. At one add the window would
     * close before the walk's first page without the extension, so the assertion below is
     * about the knob rather than about this machine. The nominal budget is NOT the quantity
     * the two arms match on; the arrival rate is, and each arm's real length is set by its
     * own stop rule.
     */
    private val COVERING_GUARD_ADDS = 1

    @Test
    fun `classify names contained, walk outlasting the drive, and a walk that precedes it`() {
        val drive = Window(startNanos = 1_000, endNanos = 2_000)

        classify(drive, Window(1_200, 1_800)) shouldBe DriveWindowContainment.CONTAINED
        // Endpoints included: a walk exactly filling the window is contained.
        classify(drive, Window(1_000, 2_000)) shouldBe DriveWindowContainment.CONTAINED
        classify(drive, Window(1_200, 2_001)) shouldBe DriveWindowContainment.WALK_OUTLASTS_DRIVE
        classify(drive, Window(999, 1_800)) shouldBe DriveWindowContainment.WALK_PRECEDES_DRIVE
        // Both defects at once reports the head, per `classify`'s KDoc.
        classify(drive, Window(999, 2_001)) shouldBe DriveWindowContainment.WALK_PRECEDES_DRIVE
    }

    @Test
    fun `Window contains is inclusive at both ends and rejects an inverted window`() {
        val outer = Window(10, 20)
        outer.contains(Window(10, 20)).shouldBeTrue()
        outer.contains(Window(11, 19)).shouldBeTrue()
        outer.contains(Window(9, 19)) shouldBe false
        outer.contains(Window(11, 21)) shouldBe false
        outer.durationMs shouldBe 10 / 1_000_000.0

        val inverted = runCatching { Window(startNanos = 20, endNanos = 10) }
        inverted.isFailure.shouldBeTrue()
    }

    @Test
    fun `the TRUNCATED arm forces a walk that outlasts the drive window`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            val trial = containmentTrial(
                rig = rig,
                arm = DriveWindowArm.TRUNCATED,
                pageLimit = GUARD_PAGE_LIMIT,
                adds = GUARD_ADDS,
            )

            trial.containment shouldBe DriveWindowContainment.WALK_OUTLASTS_DRIVE
            trial.isValidForItsArm.shouldBeTrue()
            // The truncation is what shortened it: the drive stopped well inside its
            // nominal budget, so this is a cut drive rather than a budget that happened to
            // run out. Without this the test would pass against a knob that did nothing.
            trial.driveAdds shouldBeGreaterThan 0
            (trial.driveAdds < GUARD_ADDS).shouldBeTrue()
            // And the walk really did have pages left to pay for after the cut.
            trial.walk.pages shouldBeGreaterThan 1
        }
    }

    @Test
    fun `the matched COVERING arm produces containment against the same rig and rate`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            val trial = containmentTrial(
                rig = rig,
                arm = DriveWindowArm.COVERING,
                pageLimit = GUARD_PAGE_LIMIT,
                adds = COVERING_GUARD_ADDS,
            )

            trial.containment shouldBe DriveWindowContainment.CONTAINED
            trial.isValidForItsArm.shouldBeTrue()
            trial.driveWindow.contains(trial.walkWindow).shouldBeTrue()
            trial.walk.pages shouldBeGreaterThan 1
            // The extension is what covered the walk — see COVERING_GUARD_ADDS.
            trial.driveAdds shouldBeGreaterThan COVERING_GUARD_ADDS
        }
    }

    @Test
    fun `the walk is fired READ_DELAY_MS into the drive, not into its own pager thread`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            val trial = containmentTrial(
                rig = rig,
                arm = DriveWindowArm.COVERING,
                pageLimit = GUARD_PAGE_LIMIT,
                adds = COVERING_GUARD_ADDS,
            )

            // An arithmetic floor, not a timing guess, in the manner of the paced-shape
            // guard below: the pager waits for the drive's window to OPEN and only then
            // sleeps `READ_DELAY_MS`, so the walk cannot begin sooner than that after the
            // window opened, whatever the machine is doing. No upper bound is asserted — a
            // loaded host may take arbitrarily longer, and a test that failed for that
            // would be measuring the machine.
            //
            // What this pins is `computenet-ttjs`: before the gate the delay ran from the
            // pager's own `Thread.start()`, which PRECEDES the drive, so a main thread
            // descheduled for more than a millisecond let the walk begin before the window
            // existed and the trial classified WALK_PRECEDES_DRIVE — 1 in 60 COVERING
            // trials with the CPU oversubscribed 3x, and 1 in 7 whole-suite runs as filed.
            // It says nothing about `maxGap`, and is a statement about the knob's
            // construction only.
            val delayNanos = trial.walkWindow.startNanos - trial.driveWindow.startNanos
            val floorNanos = BoundedReadFixtures.READ_DELAY_MS * 1_000_000
            (delayNanos >= floorNanos).shouldBeTrue()
            trial.containment shouldBe DriveWindowContainment.CONTAINED
        }
    }

    @Test
    fun `both arms run on one rig, at one matched rate, and classify oppositely`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            // Interleaved on the same rig, the pairing a discriminating run uses: the two
            // arms must differ in containment and in nothing the fixture chooses for them.
            val truncated = containmentTrial(rig, DriveWindowArm.TRUNCATED, GUARD_PAGE_LIMIT, GUARD_ADDS)
            val covering =
                containmentTrial(rig, DriveWindowArm.COVERING, GUARD_PAGE_LIMIT, COVERING_GUARD_ADDS)

            truncated.containment shouldBe DriveWindowContainment.WALK_OUTLASTS_DRIVE
            covering.containment shouldBe DriveWindowContainment.CONTAINED
            truncated.drive.arrivals shouldBe truncated.driveAdds
            covering.drive.arrivals shouldBe covering.driveAdds
            // The covering arm outlasts the walk it covers; the truncated one does not.
            (covering.driveWindow.endNanos > covering.walkWindow.endNanos).shouldBeTrue()
            (truncated.driveWindow.endNanos < truncated.walkWindow.endNanos).shouldBeTrue()
        }
    }

    @Test
    fun `a paced shape spaces its adds while a burst does not`() {
        val spacingNanos = 200_000L
        val adds = 20
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            val burst = rig.driveShaped(DriveShape.burst(adds))
            val paced = rig.driveShaped(DriveShape.paced(adds = adds, spacingNanos = spacingNanos))

            burst.adds shouldBe adds
            paced.adds shouldBe adds
            // The floor is arithmetic, not a timing guess: adds emitted `spacingNanos`
            // apart cannot complete sooner than (adds - 1) * spacing, whatever the machine
            // is doing. No upper bound is asserted — a loaded host may take arbitrarily
            // longer, and a test that failed for that would be measuring the machine.
            val floorMs = (adds - 1) * spacingNanos / 1_000_000.0
            (paced.window.durationMs >= floorMs).shouldBeTrue()
        }
    }

    @Test
    fun `a shaped drive whose extension never releases fails at its cap rather than hanging`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            val cap = 40
            val outcome = runCatching {
                rig.driveShaped(
                    DriveShape.paced(
                        adds = 10,
                        spacingNanos = 1_000,
                        extendWhile = { true },
                        extensionCapAdds = cap,
                    ),
                )
            }
            outcome.isFailure.shouldBeTrue()
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            (message.contains("extension cap") && message.contains("$cap")).shouldBeTrue()
        }
    }

    @Test
    fun `pagedWalk reports every page to onPage, in walk order, with its own latency`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            val seen = ArrayList<Pair<Int, Double>>()
            val walk = BoundedReadFixtures.pagedWalk(
                host = rig.host,
                ref = rig.targetRef,
                limit = GUARD_PAGE_LIMIT,
                onPage = { index, latencyMs -> seen += index to latencyMs },
            )

            seen.map { it.first } shouldBe (1..walk.pages).toList()
            seen.map { it.second } shouldBe walk.pageLatenciesMs
        }
    }

    @Test
    fun `describe names the arm, the classification and the pairing`() {
        val trial = ContainmentTrial(
            arm = DriveWindowArm.TRUNCATED,
            drive = DriveOutcome(durationMs = 12.0, maxGapMs = 3.5, arrivals = 100),
            driveWindow = Window(0, 12_000_000),
            driveAdds = 100,
            walk = PagedWalkOutcome(
                pages = 3,
                entries = 24,
                pageLatenciesMs = listOf(9.0, 0.1, 7.0),
                frontierStable = true,
                caveats = emptySet(),
            ),
            walkWindow = Window(1_000_000, 30_000_000),
        )

        trial.containment shouldBe DriveWindowContainment.WALK_OUTLASTS_DRIVE
        trial.isValidForItsArm.shouldBeTrue()
        val line = trial.describe()
        (line.contains("TRUNCATED") && line.contains("WALK_OUTLASTS_DRIVE")).shouldBeTrue()
        (line.contains("maxGap=3.5000") && line.contains("page=9.0000 @1/3")).shouldBeTrue()
    }

    @Test
    fun `a COVERING trial that came out non-contained is not admissible for its arm`() {
        // The discard rule, pinned on a constructed trial: the arm is what was ASKED for,
        // the classification is what happened, and only their agreement makes a trial
        // evidence. Nothing reinterprets a failed control as a forced non-contained trial.
        val trial = ContainmentTrial(
            arm = DriveWindowArm.COVERING,
            drive = DriveOutcome(durationMs = 1.0, maxGapMs = 1.0, arrivals = 1),
            driveWindow = Window(0, 1_000_000),
            driveAdds = 1,
            walk = PagedWalkOutcome(1, 1, listOf(1.0), true, emptySet()),
            walkWindow = Window(500_000, 9_000_000),
        )

        trial.containment shouldBe DriveWindowContainment.WALK_OUTLASTS_DRIVE
        trial.isValidForItsArm shouldBe false
    }

    @Test
    fun `the guards stay fast enough to belong in the untagged suite`() {
        val nanos = measureNanoTime {
            BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
                rig.seed()
                containmentTrial(rig, DriveWindowArm.TRUNCATED, GUARD_PAGE_LIMIT, GUARD_ADDS)
            }
        }
        // Generous by two orders of magnitude against the measured cost; this exists to
        // catch a knob that started waiting on a drain timeout, not to time the machine.
        (nanos < 60_000_000_000L).shouldBeTrue()
    }
}
