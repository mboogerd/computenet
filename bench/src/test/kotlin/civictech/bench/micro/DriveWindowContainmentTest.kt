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
     * The arm's stop rule is keyed to the walk's first page and is polled by the drive's
     * own thread, so what it bounds is the drive's ADD loops, and only while that thread is
     * scheduled. Under 3x CPU oversubscription a TRUNCATED trial comes out CONTAINED about
     * 6% of the time — either because the drive thread was descheduled through the whole
     * walk so the cut had not happened yet (17 of 20 measured cases), or because the walk
     * finished inside `driveShaped`'s drain tail (3 of 20). It is never seen at ordinary
     * load. See `DriveWindowContainment`'s header, residual (4), for the measurement.
     *
     * Such a trial is inadmissible for its arm by `ContainmentTrial.isValidForItsArm`,
     * which is the pre-registration's own rule, and discarding it is what the
     * pre-registration says to do with it.
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
    fun `the discard rule keeps the first admissible trial and reports what it discarded`() {
        // Classifications chosen rather than raced for, so this asserts the RULE and not a
        // machine: two inadmissible TRUNCATED trials, then an admissible one.
        val produced = listOf(containedTruncatedTrial(), containedTruncatedTrial(), outlastingTruncatedTrial())
        var next = 0

        val admissible = admissibleTrial(DriveWindowArm.TRUNCATED, attempts = 4) { produced[next++] }

        admissible.trial shouldBe produced[2]
        admissible.trial.isValidForItsArm.shouldBeTrue()
        admissible.discarded shouldBe produced.take(2)
        // And it stopped as soon as it had one: the fourth attempt was never taken.
        next shouldBe 3
    }

    @Test
    fun `the discard rule fails at its attempt cap rather than retrying forever`() {
        // A knob that stopped forcing non-containment: every trial inadmissible. The point
        // is that this FAILS, naming what it saw, instead of being retried into a pass.
        var taken = 0
        val outcome = runCatching {
            admissibleTrial(DriveWindowArm.TRUNCATED, attempts = 3) {
                taken++
                containedTruncatedTrial()
            }
        }

        outcome.isFailure.shouldBeTrue()
        taken shouldBe 3
        val message = outcome.exceptionOrNull()?.message.orEmpty()
        (message.contains("TRUNCATED") && message.contains("3 attempts")).shouldBeTrue()
        message.contains("CONTAINED").shouldBeTrue()
    }

    @Test
    fun `the discard rule rejects a non-positive attempt budget and a mismatched arm`() {
        runCatching { admissibleTrial(DriveWindowArm.TRUNCATED, attempts = 0) { outlastingTruncatedTrial() } }
            .isFailure.shouldBeTrue()
        // A source handing back the other arm's trial is a fixture error, not a discard:
        // reinterpreting it is exactly what the pre-registration forbids.
        runCatching { admissibleTrial(DriveWindowArm.COVERING, attempts = 2) { outlastingTruncatedTrial() } }
            .isFailure.shouldBeTrue()
    }

    /** A TRUNCATED trial that came out CONTAINED — inadmissible for its arm. */
    private fun containedTruncatedTrial(): ContainmentTrial = ContainmentTrial(
        arm = DriveWindowArm.TRUNCATED,
        drive = DriveOutcome(durationMs = 20.0, maxGapMs = 14.0, arrivals = 47),
        driveWindow = Window(0, 20_000_000),
        driveAdds = 47,
        walk = PagedWalkOutcome(129, 1_000, List(129) { 0.1 }, true, emptySet()),
        walkWindow = Window(1_000_000, 19_000_000),
        driveAddsWindow = Window(0, 2_500_000),
    )

    /** A TRUNCATED trial that came out WALK_OUTLASTS_DRIVE — admissible for its arm. */
    private fun outlastingTruncatedTrial(): ContainmentTrial = ContainmentTrial(
        arm = DriveWindowArm.TRUNCATED,
        drive = DriveOutcome(durationMs = 3.0, maxGapMs = 1.0, arrivals = 40),
        driveWindow = Window(0, 3_000_000),
        driveAdds = 40,
        walk = PagedWalkOutcome(126, 1_000, List(126) { 0.1 }, true, emptySet()),
        walkWindow = Window(1_000_000, 14_000_000),
        driveAddsWindow = Window(0, 2_800_000),
    )

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
 * trials with [OVERSUBSCRIPTION] spinner threads per core at `MAX_PRIORITY` competing for
 * the CPUs, and asserts that none of the trials it returns classifies CONTAINED. It also
 * prints the DISCARD tally — the raw rate at which `containmentTrial` produced an
 * inadmissible TRUNCATED trial — split by whether the discarded trial's walk nonetheless
 * outlasted the drive's ADD loops. That split is the whole diagnostic value of the run: a
 * discard with `vsAdds=WALK_OUTLASTS_DRIVE` is `driveShaped`'s drain fence covering the
 * walk, while one with `vsAdds=CONTAINED` and a `drainTail` near zero is the DRIVE THREAD
 * having been descheduled through the walk, so the truncation had not happened yet.
 *
 * **Measured 2026-08-27, 16 cores, 48 spinners, host otherwise idle** — two independent
 * runs of this method, the second on this file's committed configuration
 * ([CONTAINMENT_TRIAL_ATTEMPTS] attempts):
 *
 * ```
 * run 1: trials=150 returned={WALK_OUTLASTS_DRIVE=150} discarded= 7 of 157 raw (4.5%)
 * run 2: trials=150 returned={WALK_OUTLASTS_DRIVE=150} discarded=13 of 163 raw (8.0%)
 * pooled discards, 20 of 320 raw (6.3%), by mechanism:
 *    3 of 20  drainTail 4.7-41.7 ms, vsAdds=WALK_OUTLASTS_DRIVE  -- the drain fence
 *   17 of 20  drainTail < 0.003 ms,  vsAdds=CONTAINED            -- the drive thread
 *             1-33 adds realised across drive windows of 3.6-55 ms   descheduled
 * ```
 *
 * Zero CONTAINED among the 300 returned trials, which is the acceptance. No trial needed
 * more than 3 of its [CONTAINMENT_TRIAL_ATTEMPTS] attempts in either run.
 *
 * So `computenet-fvrm`'s filed candidate mechanism (the drain fence) is real but is the
 * MINORITY cause at ~15% of inadmissible trials; the dominant one is the drive thread not
 * running, which no change to the drain fence would touch.
 *
 * A zero result here is a statement about this host under this load and nothing more. It
 * does not bound the rate on other hardware, and the number of trials is the whole content
 * of the bound: [SAMPLE_TRIALS] returned trials with zero CONTAINED bounds the RETURNED
 * rate at roughly 2% with 95% confidence — a far weaker claim than the arithmetic of the
 * discard rule, under which a 4.5% raw rate misses [CONTAINMENT_TRIAL_ATTEMPTS] independent
 * attempts at ~1e-8. The raw discard tally, where the real rate lives, is printed rather
 * than asserted away for exactly that reason.
 */
@Tag("bench")
class DriveWindowContainmentStarvedSampleTest {

    /** Entries per page — the fast guards' limit, so a 10^3 walk takes ~125 pages. */
    private val GUARD_PAGE_LIMIT = 8

    /** Nominal budget per drive, matching the fast guards'. */
    private val GUARD_ADDS = 400

    /**
     * Trials in the sample. 150 is `computenet-fvrm`'s own sample size, kept so this run and
     * the one that filed the bead are directly comparable. A literal rather than a system
     * property on purpose: `:bench:test` forwards only an enumerated list of properties to
     * the forked test JVM (see bench/build.gradle.kts), so a `-D` here would be set on the
     * Gradle daemon and silently ignored — the sample size would then be whatever the
     * default is while the log said otherwise.
     */
    private val SAMPLE_TRIALS: Int = 150

    /** Spinner threads per core. 3x is the acceptance's ">= 3x cores". */
    private val OVERSUBSCRIPTION: Int = 3

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
        // Indexed by the trial that produced them, so a reader can see whether the
        // inadmissible ones cluster in the cold opening trials or are spread through the run.
        val discarded = ArrayList<Pair<Int, ContainmentTrial>>()
        try {
            threads.forEach { it.start() }
            repeat(SAMPLE_TRIALS) { index ->
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
                    attempt.discarded.forEach { discarded += index to it }
                }
            }
        } finally {
            running.set(false)
            threads.forEach { it.join(10_000) }
        }

        val byClass = returned.groupingBy { it.containment }.eachCount()
        val discardsCoveredByDrain = discarded.count { (_, t) -> t.outlastsDriveAdds }
        println(
            "computenet-fvrm starved sample: cores=$cores spinners=$spinners " +
                "trials=${returned.size} returned=$byClass " +
                "discarded=${discarded.size} " +
                "(walk outlasted the ADDS in $discardsCoveredByDrain of them, i.e. the drain " +
                "fence covered it) " +
                "raw inadmissible rate=" +
                "%.4f".format(discarded.size.toDouble() / (returned.size + discarded.size)),
        )
        discarded.forEach { (index, t) -> println("  DISCARDED trial#$index ${t.describe()}") }

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
