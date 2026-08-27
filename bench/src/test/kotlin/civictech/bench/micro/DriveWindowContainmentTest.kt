package civictech.bench.micro

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * The TRUNCATED guard goes through [admissibleContainmentTrial] rather than
     * [containmentTrial], and that is a statement about the arm, not a concession to
     * flakiness.
     *
     * The arm's stop rule is keyed to the walk's first page, so what it bounds is the
     * drive's ADD loops. The window `maxGap` is measured over runs on past them through
     * `driveShaped`'s drain fence — a busy spin contending with the pager — so under CPU
     * starvation a walk can outlast every add and still finish inside the drain tail,
     * classifying CONTAINED: ~3% of TRUNCATED trials at 3x oversubscription, never seen at
     * ordinary load (`computenet-fvrm`). That trial is inadmissible for its arm by
     * `ContainmentTrial.isValidForItsArm`, which is the pre-registration's own rule, and
     * discarding it is what the pre-registration says to do with it.
     *
     * Nothing below is weakened by the retry. The assertion is still the arm's own
     * unrelaxed classification, and a knob that stopped working — `stopEarly` neutered,
     * say — produces an inadmissible trial on EVERY attempt and fails at exhaustion with
     * the classifications named. What the retry removes is only the ~3% chance that a
     * green knob is reported red by a host the test does not control.
     */
    @Test
    fun `the TRUNCATED arm forces a walk that outlasts the drive window`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            val trial = admissibleContainmentTrial(
                rig = rig,
                arm = DriveWindowArm.TRUNCATED,
                pageLimit = GUARD_PAGE_LIMIT,
                adds = GUARD_ADDS,
            ).trial

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
            // TRUNCATED through the discard rule, for the reason the guard above states;
            // COVERING direct, because its containment IS structural — the drive extends
            // until the walk is done — so a first-trial failure there is a real defect and
            // must not be retried past.
            val truncated =
                admissibleContainmentTrial(rig, DriveWindowArm.TRUNCATED, GUARD_PAGE_LIMIT, GUARD_ADDS).trial
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
    fun `a trial can outlast the drive's adds and still be contained by its drain tail`() {
        // The `computenet-fvrm` case, as a constructed trial: the walk ends after the last
        // add (so the TRUNCATED arm's stop rule did exactly what it says) and before the
        // drain fence released (so the measured window covers it anyway). The trial is
        // CONTAINED and therefore inadmissible for the TRUNCATED arm, and its own record
        // says which of the two intervals decided that.
        val trial = ContainmentTrial(
            arm = DriveWindowArm.TRUNCATED,
            drive = DriveOutcome(durationMs = 10.0, maxGapMs = 1.0, arrivals = 40),
            driveWindow = Window(0, 10_000_000),
            driveAdds = 40,
            walk = PagedWalkOutcome(125, 1_000, List(125) { 0.05 }, true, emptySet()),
            walkWindow = Window(1_000_000, 8_000_000),
            driveAddsWindow = Window(0, 4_000_000),
        )

        trial.containment shouldBe DriveWindowContainment.CONTAINED
        trial.isValidForItsArm shouldBe false
        // ... and yet the arm's stop rule held: the walk did outlast every add.
        trial.outlastsDriveAdds.shouldBeTrue()
        trial.containmentAgainstDriveAdds shouldBe DriveWindowContainment.WALK_OUTLASTS_DRIVE
        trial.drainTailMs shouldBe 6.0
        trial.describe().contains("vsAdds=WALK_OUTLASTS_DRIVE").shouldBeTrue()
    }

    @Test
    fun `a shaped drive reports the add loops' interval as a prefix of the measured window`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            val timed = rig.driveShaped(DriveShape.burst(200))

            timed.addsWindow.startNanos shouldBe timed.window.startNanos
            (timed.addsWindow.endNanos <= timed.window.endNanos).shouldBeTrue()
            (timed.drainTailMs >= 0.0).shouldBeTrue()
            // An inverted or overhanging adds-window is a fixture error, not a measurement.
            val bad = runCatching {
                TimedDrive(
                    outcome = timed.outcome,
                    window = timed.window,
                    addsWindow = Window(timed.window.startNanos, timed.window.endNanos + 1),
                    adds = timed.adds,
                    shape = timed.shape,
                )
            }
            bad.isFailure.shouldBeTrue()
        }
    }

    @Test
    fun `admissibleContainmentTrial fails at its attempt cap rather than retrying forever`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            rig.seed()
            // A deterministically inadmissible TRUNCATED configuration: a page limit above
            // the target's size makes the walk ONE page, so the arm's "stop at the first
            // page" cut lands at the walk's LAST page and the drive — which still has its
            // drain fence to run — cannot help but outlast it. This is what a knob that
            // stopped forcing non-containment looks like, and the point is that it FAILS
            // here instead of being retried into a pass.
            val outcome = runCatching {
                admissibleContainmentTrial(
                    rig = rig,
                    arm = DriveWindowArm.TRUNCATED,
                    pageLimit = 10_000,
                    adds = GUARD_ADDS,
                    attempts = 2,
                )
            }

            outcome.isFailure.shouldBeTrue()
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            (message.contains("TRUNCATED") && message.contains("2 attempts")).shouldBeTrue()
            message.contains("CONTAINED").shouldBeTrue()
        }
    }

    @Test
    fun `admissibleContainmentTrial rejects a non-positive attempt budget`() {
        BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
            val outcome = runCatching {
                admissibleContainmentTrial(rig, DriveWindowArm.TRUNCATED, GUARD_PAGE_LIMIT, GUARD_ADDS, attempts = 0)
            }
            outcome.isFailure.shouldBeTrue()
        }
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

/**
 * The starved repetition sample `computenet-fvrm`'s acceptance is stated in: the TRUNCATED
 * arm, driven through the discard rule, under deliberate CPU oversubscription.
 *
 * `@Tag("bench")` and therefore NOT part of a default `./gradlew :bench:test` — it
 * deliberately pins every core on the machine for minutes, which is the one thing a test in
 * the fast suite must never do. Run it explicitly:
 *
 * ```
 * ./gradlew :bench:test -PbenchOnly=true \
 *   --tests 'civictech.bench.micro.DriveWindowContainmentStarvedSampleTest' --rerun
 * ```
 *
 * **What it measures, and what it cannot.** It runs [SAMPLE_TRIALS] admissible TRUNCATED
 * trials with [oversubscription] spinner threads at `MAX_PRIORITY` competing for the CPUs,
 * and asserts that none of the trials it returns classifies CONTAINED. It also prints the
 * DISCARD tally — the raw rate at which `containmentTrial` produced an inadmissible
 * TRUNCATED trial — split by whether the discarded trial's walk nonetheless outlasted the
 * drive's ADD loops. That split is the evidence for `computenet-fvrm`'s mechanism: a
 * discard with `vsAdds=WALK_OUTLASTS_DRIVE` is the drain fence covering the walk, while one
 * with `vsAdds=CONTAINED` would mean the stop rule itself failed to fire in time and is a
 * different defect.
 *
 * A zero result here is a statement about this host under this load and nothing more. It
 * does not bound the rate on other hardware, and the number of trials is the whole content
 * of the bound: [SAMPLE_TRIALS] returned trials with zero CONTAINED bounds the per-trial
 * rate at roughly 2% with 95% confidence, which is why the raw discard tally — where the
 * ~3% actually lives — is printed rather than only asserted away.
 */
@Tag("bench")
class DriveWindowContainmentStarvedSampleTest {

    /** Entries per page — the fast guards' limit, so a 10^3 walk takes ~125 pages. */
    private val GUARD_PAGE_LIMIT = 8

    /** Nominal budget per drive, matching the fast guards'. */
    private val GUARD_ADDS = 400

    /**
     * Trials in the sample. 150 is `computenet-fvrm`'s own sample size, kept so the two are
     * directly comparable; overridable for a longer soak.
     */
    private val SAMPLE_TRIALS: Int =
        System.getProperty("civictech.bench.containmentSampleTrials")?.toInt() ?: 150

    /** Spinner threads per core. 3x is the acceptance's ">= 3x cores". */
    private val OVERSUBSCRIPTION: Int =
        System.getProperty("civictech.bench.containmentOversubscription")?.toInt() ?: 3

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    fun `the TRUNCATED arm yields no contained trial under 3x CPU oversubscription`() {
        val cores = Runtime.getRuntime().availableProcessors()
        val spinners = cores * OVERSUBSCRIPTION
        val running = AtomicBoolean(true)
        val threads = (0 until spinners).map { index ->
            Thread {
                // A pure register spin: no allocation, no syscall, nothing the JIT can
                // hoist away, and nothing that yields the CPU. `blackhole` is volatile so
                // the loop cannot be optimised out.
                var local = index.toLong()
                while (running.get()) {
                    local = local * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
                    blackhole = local
                }
            }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; name = "starve-$index" }
        }

        val returned = ArrayList<ContainmentTrial>(SAMPLE_TRIALS)
        val discarded = ArrayList<ContainmentTrial>()
        try {
            threads.forEach { it.start() }
            repeat(SAMPLE_TRIALS) {
                // A fresh rig per trial, deliberately. `LiveTrafficRig.elementsAdded` drifts
                // upward across drives on one rig (that file's item 4), so reusing a rig for
                // 150 trials would grow the target from 10^3 to ~10^4 and the walk from
                // ~125 pages to ~10x that — the sample would be measuring a moving subject
                // rather than 150 repetitions of one.
                BoundedReadFixtures.rig(SetScale.N1E3).use { rig ->
                    rig.seed()
                    val attempt = admissibleContainmentTrial(
                        rig = rig,
                        arm = DriveWindowArm.TRUNCATED,
                        pageLimit = GUARD_PAGE_LIMIT,
                        adds = GUARD_ADDS,
                    )
                    returned += attempt.trial
                    discarded += attempt.discarded
                }
            }
        } finally {
            running.set(false)
            threads.forEach { it.join(10_000) }
        }

        val byClass = returned.groupingBy { it.containment }.eachCount()
        val discardsCoveredByDrain = discarded.count { it.outlastsDriveAdds }
        println(
            "computenet-fvrm starved sample: cores=$cores spinners=$spinners " +
                "trials=${returned.size} returned=$byClass " +
                "discarded=${discarded.size} " +
                "(walk outlasted the ADDS in $discardsCoveredByDrain of them, i.e. the drain " +
                "fence covered it) " +
                "raw inadmissible rate=" +
                "%.4f".format(discarded.size.toDouble() / (returned.size + discarded.size)),
        )
        discarded.forEach { println("  DISCARDED ${it.describe()}") }

        returned.size shouldBe SAMPLE_TRIALS
        byClass[DriveWindowContainment.CONTAINED] shouldBe null
        byClass[DriveWindowContainment.WALK_PRECEDES_DRIVE] shouldBe null
        returned.all { it.isValidForItsArm }.shouldBeTrue()
    }

    private companion object {
        /** Sink for the spinner loop, so nothing about it can be optimised away. */
        @Volatile
        @JvmStatic
        var blackhole: Long = 0
    }
}
