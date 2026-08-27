package civictech.bench.micro

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * The **fixture capability** `computenet-juid`'s findings entry names as the missing
 * prerequisite for its discriminating run: deliberately forcing a concurrent paged walk to
 * fall OUTSIDE the drive window that `DriveOutcome.maxGapMs` is measured over, against a
 * matched arm in which it falls inside.
 *
 * ## What this file is for, and what it deliberately is not
 *
 * `doc/bench/findings.md`'s V1C-BENCH replication publishes a "reduction, this run" column
 * for §6 E2-vs-E3 whose SIGN flips between re-runs of its own documented command
 * (`computenet-xlst`'s caveat). `computenet-juid` published a CANDIDATE mechanism for that
 * instability: E3's `maxGap` is measured over the drive's window only, so whether the
 * walk's expensive closing O(n) page lands inside that window is a race, and `maxGap`
 * therefore measures a different FRACTION of the walk on each run. juid's 360 trials could
 * not settle it — 120/120 were contained at 1e5, so the race is not NECESSARY for the
 * magnitude instability, and no non-contained trial reproduced, so it is not EXCLUDED as
 * the cause of the sign flips either.
 *
 * Settling it needs a trial whose containment is chosen rather than observed. That is what
 * [containmentTrial] provides.
 *
 * **This file makes no claim about the mechanism, and must not be read as one.** It is the
 * instrument, pre-registered before any number it produces exists — the discipline
 * `NOISE_FLOOR`, `IterationLengthCriterion` and `ClassNoiseFloor` are held to in this
 * module. Whether drive-window containment explains the sign instability is an open
 * empirical question, answerable only by running both arms on a quiesced host
 * (`run-series.sh`'s quiescence guard, `--host-state quiesced`) and appending what they
 * showed. Nothing here anticipates that answer, and in particular
 * `DriveOutcome.maxGapMs`'s window definition is untouched: redefining it is conditional on
 * the mechanism being CONFIRMED, and confirming it requires the run this file only makes
 * possible.
 *
 * ## The pre-registration: what counts as each arm, fixed before the numbers
 *
 * Both arms drive the same rig, at the same scale, at **the same instantaneous arrival
 * rate** ([CONTAINMENT_SPACING_NANOS] between adds), with the walk fired the same
 * `BoundedReadFixtures.READ_DELAY_MS` into the drive. They differ in **when the drive's
 * window closes relative to the walk**, and in nothing else:
 *
 * - [DriveWindowArm.COVERING] — *the matched contained control.* The drive keeps emitting
 *   at the shared spacing until the walk has returned its LAST page, then drains and
 *   closes. Containment is therefore structural, not lucky: the window cannot close before
 *   the subject it covers. A trial of this arm is a valid control only if it classifies
 *   [DriveWindowContainment.CONTAINED].
 * - [DriveWindowArm.TRUNCATED] — *the forced non-contained trial.* The same drive, stopped
 *   the moment the walk returns its FIRST page — juid's "shortening the drive", expressed
 *   against the walk's own progress rather than against a wall-clock guess, so the arm does
 *   not depend on a machine's speed to do what it says. Every page after the first, the
 *   closing frontier's O(n) page above all, then falls outside the measured window. A trial
 *   of this arm is a valid forced non-contained trial only if it classifies
 *   [DriveWindowContainment.WALK_OUTLASTS_DRIVE].
 *
 * A trial that does not classify as its arm requires is **discarded, never reinterpreted**:
 * [ContainmentTrial.isValidForItsArm] is the predicate, and a discriminating run reports
 * how many trials it discarded. The two arms are compared on `DriveOutcome.maxGapMs`
 * against `PagedWalkOutcome.maxSinglePageMs` per trial — the same pairing
 * `BoundedReadProbeTest.gapVersusPage` already prints — because the mechanism's prediction
 * is precisely that the two agree when contained and diverge when not.
 *
 * **Residuals, stated because a reader of the eventual entry needs them.** (1) The two arms
 * deliver different total add counts by construction: the covering arm runs as long as the
 * walk, the truncated arm stops early, so the target's size at trial end differs and
 * [ContainmentTrial.driveAdds] is reported per trial rather than assumed equal. What is
 * matched is the arrival RATE the walk contends with, which is what a page's cost depends
 * on, not the total. (2) The walk freezes its enumeration order at open, so both arms walk
 * the size the target had when the trial began. (3) Containment here is a statement about
 * two wall-clock intervals on one `System.nanoTime` clock, not about which page a given gap
 * belongs to — that attribution remains impossible for the reason
 * `PagedWalkOutcome.maxSinglePagePosition`'s KDoc gives.
 *
 * **(4) The TRUNCATED arm's non-containment is not structural, and cannot be made so here.**
 * The arm's stop rule is keyed to the walk's first page, so what it can bound is the drive's
 * ADD loops — `TimedDrive.addsWindow` — and not the window `maxGap` is measured over, which
 * runs on past the last add through `driveShaped`'s drain fence. Under CPU starvation a
 * TRUNCATED trial can therefore come out [DriveWindowContainment.CONTAINED], which is
 * inadmissible for its arm. Measured at ~3% by `computenet-ttjs`'s sample and at 7 of 157
 * raw trials (4.5%) by `computenet-fvrm`'s, both at 3x oversubscription; never seen at
 * ordinary load, where 50 consecutive whole-suite runs were clean.
 *
 * **TWO distinct mechanisms produce it, and the one the bug was filed on is the minority.**
 * `computenet-fvrm` filed a candidate — the drive holding its window open through the drain
 * fence's busy spin while the walk finishes inside the tail — and the sample above bears it
 * out in 1 of its 7 inadmissible trials (`drainTail=17.8 ms`, walk outlasting the adds). The
 * other 6 have `drainTail` under 0.003 ms and a walk contained by the ADD loops themselves,
 * with 1, 1, 1, 2, 18 and 22 adds realised across drive windows of 12 to 64 ms. That is the
 * DRIVE THREAD being descheduled: `DriveShape.stopEarly` is polled only by the thread
 * emitting the adds, so a drive that does not run cannot cut its own window, and the walk
 * completes inside a truncation that has not happened yet. No drain-fence change touches
 * that case, and nothing in a fixture can make a starved thread poll.
 *
 * So the arm's real guarantee is conditional, and stating it plainly: *when the driving
 * thread is scheduled, the walk outlasts the drive's ADD loop.* Neither half of that is
 * repairable here — the first is the host's, the second would need the measured window
 * redefined, which this file will not do before the mechanism above it is confirmed. An
 * inadmissible trial is therefore discarded, per the rule already stated: see
 * [admissibleContainmentTrial], which is that rule executed, and
 * [ContainmentTrial.containmentAgainstDriveAdds], which is how a trial's own record says
 * which of the two mechanisms produced it.
 */
enum class DriveWindowArm {
    /** The matched contained control — the drive outlives the walk by construction. */
    COVERING,

    /** The forced non-contained trial — the drive is cut at the walk's first page. */
    TRUNCATED,
}

/**
 * A half-open-in-spirit wall-clock interval on `System.nanoTime`, used only to relate two
 * intervals measured by the same process on the same clock.
 */
data class Window(val startNanos: Long, val endNanos: Long) {
    init {
        require(endNanos >= startNanos) {
            "a window cannot end before it starts (start=$startNanos end=$endNanos)"
        }
    }

    val durationMs: Double get() = (endNanos - startNanos) / 1_000_000.0

    /** Whether [other] lies wholly within this window, endpoints included. */
    fun contains(other: Window): Boolean =
        other.startNanos >= startNanos && other.endNanos <= endNanos
}

/**
 * How a drive's window and a concurrent walk's window relate — the fact `maxGap`'s meaning
 * turns on.
 */
enum class DriveWindowContainment {
    /**
     * The walk ran wholly inside the drive's window, so every page it took was exposed to
     * the collector's arrival stream and `maxGap` had the chance to see the worst of them.
     */
    CONTAINED,

    /**
     * The walk was still running when the drive's window closed. Its late pages — the
     * closing frontier's above all — cannot appear as a gap however expensive they were, so
     * a small `maxGap` in such a trial is containment failing, not a cheap page.
     */
    WALK_OUTLASTS_DRIVE,

    /**
     * The walk had already begun before the drive's window opened. Not produced by either
     * arm here (the walk is fired from inside the drive) and classified rather than
     * ignored, because a silent reclassification of this case as CONTAINED would be the
     * same measurement error in the other direction.
     */
    WALK_PRECEDES_DRIVE,
}

/**
 * The shape of one drive: how many adds, how fast, and when it stops.
 *
 * Constructed through [burst] for the unpaced E2/E3 drive and through [paced] for a
 * containment arm; the constructor is public so a future probe can state a shape neither
 * covers, but the two factories are what this file's arms use.
 *
 * @param adds the nominal add budget. The realised count is reported by
 *   [TimedDrive.adds] — [stopEarly] can cut it short and [extendWhile] can exceed it.
 * @param spacingNanos target interval between successive adds; `0` is the original's
 *   unpaced burst. Pacing is enforced against the drive's own start instant rather than
 *   per-add, so a slow add does not push every later one back.
 * @param stopEarly polled before each add; `true` ends the drive with the adds it has.
 * @param extendWhile polled after the budget is exhausted; while `true` the drive keeps
 *   emitting at [spacingNanos].
 * @param extensionCapAdds hard ceiling on the realised add count, so a predicate that never
 *   goes false fails as a bounded drive rather than hanging.
 */
class DriveShape(
    val adds: Int,
    val spacingNanos: Long = 0,
    val stopEarly: () -> Boolean = { false },
    val extendWhile: () -> Boolean = { false },
    val extensionCapAdds: Int = adds,
) {
    init {
        require(spacingNanos >= 0) { "spacingNanos must not be negative, was $spacingNanos" }
        require(extensionCapAdds >= adds) {
            "extensionCapAdds ($extensionCapAdds) must be at least adds ($adds)"
        }
    }

    /** Park until add number [emitted] is due, relative to the drive's start [t0]. */
    internal fun parkUntilDue(t0: Long, emitted: Int) {
        if (spacingNanos == 0L) return
        val due = t0 + spacingNanos * emitted
        var remaining = due - System.nanoTime()
        while (remaining > 0) {
            LockSupport.parkNanos(remaining)
            remaining = due - System.nanoTime()
        }
    }

    companion object {
        /** The original's unpaced burst: [adds] adds as fast as the driving thread can. */
        fun burst(adds: Int): DriveShape = DriveShape(adds = adds)

        /** A paced drive at [spacingNanos] between adds, with the given stop rules. */
        fun paced(
            adds: Int,
            spacingNanos: Long,
            stopEarly: () -> Boolean = { false },
            extendWhile: () -> Boolean = { false },
            extensionCapAdds: Int = adds,
        ): DriveShape = DriveShape(adds, spacingNanos, stopEarly, extendWhile, extensionCapAdds)
    }
}

/**
 * One drive's outcome together with the wall-clock [window] `DriveOutcome.maxGapMs` was
 * measured over, and the add count actually realised under [shape].
 *
 * @param window the interval `maxGap` is measured over, `[t0, t1]`, where `t1` is taken
 *   AFTER the drain fence. Unchanged, and deliberately so: redefining it is conditional on
 *   the containment mechanism being confirmed, which is the discriminating run's job.
 * @param addsWindow the interval the drive's ADD loops occupied, `[t0, addsEnd]` — i.e. the
 *   part of [window] a [DriveShape.stopEarly] rule actually governs. Always a prefix of
 *   [window]; the difference between them is the drain tail. Measured because a stop rule
 *   keyed to a concurrent subject (the TRUNCATED arm's) bounds this interval and NOT
 *   [window], and only a fixture that reports both can say which of the two a given trial's
 *   classification turned on (`computenet-fvrm`).
 */
data class TimedDrive(
    val outcome: DriveOutcome,
    val window: Window,
    val addsWindow: Window,
    val adds: Int,
    val shape: DriveShape,
) {
    init {
        require(addsWindow.startNanos == window.startNanos && addsWindow.endNanos <= window.endNanos) {
            "the add loops' interval must be a prefix of the measured window " +
                "(adds=[${addsWindow.startNanos}, ${addsWindow.endNanos}] " +
                "window=[${window.startNanos}, ${window.endNanos}])"
        }
    }

    /** How long the drain fence held the window open past the last add, in milliseconds. */
    val drainTailMs: Double get() = (window.endNanos - addsWindow.endNanos) / 1_000_000.0
}

/**
 * One containment trial: an arm, its drive, its concurrent walk, and the classification the
 * two windows produce.
 */
data class ContainmentTrial(
    val arm: DriveWindowArm,
    val drive: DriveOutcome,
    val driveWindow: Window,
    val driveAdds: Int,
    val walk: PagedWalkOutcome,
    val walkWindow: Window,
    /**
     * The prefix of [driveWindow] the drive's ADD loops occupied — `TimedDrive.addsWindow`.
     *
     * Defaulted to [driveWindow] so a constructed trial that is only exercising
     * [classify]/[isValidForItsArm] need not state it; every trial [containmentTrial]
     * produces states it, because for those the two differ by the drain tail and the
     * difference is the thing [outlastsDriveAdds] is about.
     */
    val driveAddsWindow: Window = driveWindow,
) {
    /** How the walk's window sits inside — or outside — the drive's. */
    val containment: DriveWindowContainment get() = classify(driveWindow, walkWindow)

    /**
     * How the walk's window sits against the drive's ADD loops alone, ignoring the drain
     * tail — the classification the TRUNCATED arm's stop rule can actually control.
     *
     * This is a DIAGNOSTIC, not a second definition of containment, and nothing asserts on
     * it: `maxGap` is measured over [driveWindow] and [containment] is the fact that bears
     * on it. It exists because the two can disagree — a TRUNCATED trial whose walk outlasts
     * the adds but finishes inside the drain tail classifies CONTAINED and is inadmissible
     * for its arm — and telling that case apart from a stop rule that simply did not fire
     * is otherwise impossible from a trial's own record (`computenet-fvrm`).
     */
    val containmentAgainstDriveAdds: DriveWindowContainment
        get() = classify(driveAddsWindow, walkWindow)

    /** Whether the walk outlasted the drive's ADD loops, whatever the drain tail did. */
    val outlastsDriveAdds: Boolean
        get() = walkWindow.endNanos > driveAddsWindow.endNanos

    /** How long the drain fence held the drive's window open past its last add, in ms. */
    val drainTailMs: Double get() = (driveWindow.endNanos - driveAddsWindow.endNanos) / 1_000_000.0

    /**
     * Whether this trial is admissible as evidence for the arm that produced it, per this
     * file's pre-registration: a COVERING trial must be CONTAINED and a TRUNCATED trial
     * must be WALK_OUTLASTS_DRIVE. An inadmissible trial is discarded and counted, never
     * reinterpreted as evidence for the other arm.
     */
    val isValidForItsArm: Boolean
        get() = containment == when (arm) {
            DriveWindowArm.COVERING -> DriveWindowContainment.CONTAINED
            DriveWindowArm.TRUNCATED -> DriveWindowContainment.WALK_OUTLASTS_DRIVE
        }

    /** The line a run prints per trial — the arm, the classification, and the pairing. */
    fun describe(): String =
        ("%s %s maxGap=%.4f page=%.4f @%d/%d walk=[%.4f ms] drive=[%.4f ms] adds=%d")
            .format(
                arm.name,
                containment.name,
                drive.maxGapMs,
                walk.maxSinglePageMs,
                walk.maxSinglePagePosition,
                walk.pages,
                walkWindow.durationMs,
                driveWindow.durationMs,
                driveAdds,
            ) + " drainTail=%.4f ms vsAdds=%s".format(drainTailMs, containmentAgainstDriveAdds.name)
}

/**
 * One trial that is admissible for its arm, and every trial discarded on the way to it.
 *
 * @param trial the trial to use as evidence — `trial.isValidForItsArm` holds.
 * @param discarded the inadmissible trials, in the order they were produced. Reported
 *   rather than swallowed: the pre-registration requires a discriminating run to say how
 *   many trials it discarded, and a caller that never sees them cannot.
 */
data class AdmissibleContainmentTrial(
    val trial: ContainmentTrial,
    val discarded: List<ContainmentTrial>,
)

/**
 * Attempts allowed per admissible trial before the fixture gives up and fails.
 *
 * Sized against a measured inadmissibility rate rather than picked. `computenet-fvrm`'s
 * filing sample put it at ~3% of TRUNCATED trials under 3x CPU oversubscription; a cold
 * short sample on the same host measured 3 in 18, so ~17% is the pessimistic end and is
 * what this is sized against. Six independent attempts miss at ~2e-5 there and are
 * unreachable on an idle host, where no trial in a 50-run whole-suite sample was
 * inadmissible at all.
 *
 * The cap is not a retry budget to be enlarged when a run gets unlucky. Its point is that a
 * knob which stopped working — a `stopEarly` rule that never fires, say — is inadmissible on
 * EVERY attempt and so fails LOUDLY at exhaustion, naming what it saw, instead of being
 * retried into a green test. Raising it trades that promptness for nothing, because the
 * probabilities above are already dominated by the arm working.
 */
const val CONTAINMENT_TRIAL_ATTEMPTS: Int = 6

/**
 * Run trials of [arm] until one is admissible for it, and return it with what was discarded.
 *
 * **Why a retry is the honest shape here, and not a weakening.** The TRUNCATED arm cuts the
 * drive at the walk's FIRST page, and the cut is made by the drive's own thread polling
 * `DriveShape.stopEarly`. So the arm can bound `TimedDrive.addsWindow` — and only while
 * that thread is scheduled. Two things follow under CPU starvation, both measured (this
 * file's header, residual 4): the walk can outlast every add and still finish inside
 * `driveShaped`'s drain tail, and — the commoner case, 6 of 7 inadmissible trials — the
 * drive thread can be descheduled clean through the walk, so the truncation has not
 * happened yet when the walk ends. Either way the trial classifies
 * [DriveWindowContainment.CONTAINED] and is inadmissible for its arm. Neither is repairable
 * by rewriting the arm: the second is the host's scheduler, and the first would need the
 * window `maxGap` is measured over redefined, which the header rules out until the
 * containment mechanism itself is confirmed.
 *
 * The pre-registration already says what to do with a trial that does not classify as its
 * arm requires: it is "discarded, never reinterpreted", and a run reports how many it
 * discarded. This function is that rule, executed. It weakens nothing the arm asserts — the
 * returned trial's classification is the arm's own, unrelaxed — and it cannot rescue a
 * broken knob, because a knob that never produces an admissible trial exhausts [attempts]
 * and fails naming what it saw.
 *
 * @param attempts how many trials to run before failing; see [CONTAINMENT_TRIAL_ATTEMPTS].
 */
fun admissibleContainmentTrial(
    rig: LiveTrafficRig,
    arm: DriveWindowArm,
    pageLimit: Int = BoundedReadFixtures.PAGE_LIMIT,
    adds: Int = CONTAINMENT_ADDS,
    spacingNanos: Long = CONTAINMENT_SPACING_NANOS,
    attempts: Int = CONTAINMENT_TRIAL_ATTEMPTS,
): AdmissibleContainmentTrial =
    admissibleTrial(arm, attempts) { containmentTrial(rig, arm, pageLimit, adds, spacingNanos) }

/**
 * The discard rule itself, over any source of trials of [arm].
 *
 * Separated from [admissibleContainmentTrial] so the rule can be exercised against trials
 * whose classification is CHOSEN rather than raced for. The behaviour that matters — that
 * exhausting [attempts] fails loudly instead of retrying forever — is otherwise only
 * reachable through a rig configuration that is inadmissible *usually*, which is not a
 * property a test can assert on. (Measured: a one-page TRUNCATED walk, the obvious "the
 * cut lands at the last page" construction, is admissible often enough to make such a test
 * flaky — the drive can observe `firstPageReturned`, which fires DURING the walk's last
 * page, and drain before the walk's own window closes.)
 *
 * @param nextTrial produces one independent trial of [arm] per call.
 */
fun admissibleTrial(
    arm: DriveWindowArm,
    attempts: Int = CONTAINMENT_TRIAL_ATTEMPTS,
    nextTrial: () -> ContainmentTrial,
): AdmissibleContainmentTrial {
    require(attempts > 0) { "attempts must be positive, was $attempts" }
    val discarded = ArrayList<ContainmentTrial>()
    repeat(attempts) {
        val trial = nextTrial()
        require(trial.arm == arm) {
            "asked for a ${arm.name} trial and was handed a ${trial.arm.name} one"
        }
        if (trial.isValidForItsArm) return AdmissibleContainmentTrial(trial, discarded)
        discarded += trial
    }
    error(
        "the ${arm.name} arm produced no trial admissible for it in $attempts attempts — " +
            "this is the knob failing, not a scheduling accident, because each attempt is " +
            "an independent trial. What they classified as: " +
            discarded.joinToString("; ") { it.describe() },
    )
}

/**
 * Classify a walk's window against the drive window `maxGap` was measured over.
 *
 * A walk that both starts before the drive and ends after it is reported as
 * [DriveWindowContainment.WALK_PRECEDES_DRIVE]: the head is the earlier defect and naming
 * the later one would suggest the drive at least saw the walk's opening, which it did not.
 */
fun classify(driveWindow: Window, walkWindow: Window): DriveWindowContainment = when {
    walkWindow.startNanos < driveWindow.startNanos -> DriveWindowContainment.WALK_PRECEDES_DRIVE
    walkWindow.endNanos > driveWindow.endNanos -> DriveWindowContainment.WALK_OUTLASTS_DRIVE
    else -> DriveWindowContainment.CONTAINED
}

/**
 * Adds per containment-arm drive before any extension or truncation — the nominal budget.
 *
 * Deliberately NOT `BoundedReadFixtures.DRIVE_ADDS`. At [CONTAINMENT_SPACING_NANOS] an
 * 8,000-add budget would be a 400 ms floor on every trial of both arms, and neither arm's
 * length is set by the budget: the covering arm's is set by the walk and the truncated
 * arm's by its first page. The budget is here only so a drive that reaches neither stop
 * rule still terminates.
 */
const val CONTAINMENT_ADDS: Int = 2_000

/**
 * The arrival rate both arms share: one add per 50 microseconds, i.e. 20,000 adds/second.
 *
 * Chosen, before any arm was run, to be a rate the rig sustains without the driving thread
 * spinning (an unpaced 8,000-add burst drains in ~100-180 ms on the reference M2 Pro, so
 * ~50,000-80,000 adds/second is the burst rate; this is comfortably under it) while still
 * keeping the collector's arrival stream dense enough that `maxGap` measures stalls in the
 * target rather than the pacer's own idle intervals. The rate is what the two arms MATCH
 * on; changing it changes both arms together, which is why it is one constant.
 */
const val CONTAINMENT_SPACING_NANOS: Long = 50_000

/**
 * Hard ceiling on the covering arm's realised add count.
 *
 * At [CONTAINMENT_SPACING_NANOS] this is 10 seconds of drive — two orders of magnitude
 * beyond any walk this fixture takes, and reached only if the walk never completes, in
 * which case the drive fails saying so rather than covering nothing for five minutes.
 */
const val CONTAINMENT_EXTENSION_CAP_ADDS: Int = 200_000

/**
 * Run ONE containment trial of [arm] against [rig], and return the drive, the walk, and how
 * their windows relate.
 *
 * Returns whatever the trial classified as, including a classification inadmissible for the
 * arm — this is the raw instrument, and a caller that needs a trial it can use as evidence
 * for its arm goes through [admissibleContainmentTrial], which applies the
 * pre-registration's discard rule. See this file's header, residual (4), for the case where
 * a TRUNCATED trial comes out CONTAINED.
 *
 * The caller owns the rig, its seeding and its warmup, exactly as `BoundedReadProbeTest`'s
 * E3 does — this runs one trial and measures it, so a probe can interleave arms (the
 * interleaving `computenet-bzwx` established for a paired comparison) rather than running
 * all of one arm and then all of the other.
 *
 * @param pageLimit entries per page. A discriminating run uses
 *   `BoundedReadFixtures.PAGE_LIMIT` so the walk is E3's walk; a fast guard uses a small
 *   limit to make the walk many-paged and cheap.
 */
fun containmentTrial(
    rig: LiveTrafficRig,
    arm: DriveWindowArm,
    pageLimit: Int = BoundedReadFixtures.PAGE_LIMIT,
    adds: Int = CONTAINMENT_ADDS,
    spacingNanos: Long = CONTAINMENT_SPACING_NANOS,
): ContainmentTrial {
    val firstPageReturned = AtomicBoolean(false)
    val walkDone = AtomicBoolean(false)
    val walkStart = AtomicLong(0)
    val walkEnd = AtomicLong(0)
    var walk: PagedWalkOutcome? = null

    /**
     * Released by the drive's first poll of its stop rule, i.e. after `driveShaped` has
     * stamped the instant `driveWindow.startNanos` reports. The pager waits on it, so
     * `walkWindow.startNanos > driveWindow.startNanos` holds by construction and
     * [DriveWindowContainment.WALK_PRECEDES_DRIVE] cannot be produced by either arm — which
     * is what this file's KDoc already claims ("Not produced by either arm here"), and what
     * was in fact only true on an idle machine. Measured before this gate existed: 1 of 60
     * COVERING trials classified WALK_PRECEDES_DRIVE with the CPU deliberately
     * oversubscribed 3x (`computenet-ttjs`, filed on a 1-in-7 whole-suite observation).
     *
     * The gate does not change either arm's pre-registered definition. It makes the
     * pre-registration's "the walk fired the same `BoundedReadFixtures.READ_DELAY_MS` into
     * the drive" literally true: the delay is now measured from the drive's own start
     * instead of from a thread start that merely tended to precede it.
     */
    val driveOpen = CountDownLatch(1)

    val pager = Thread {
        try {
            // Wait for the drive's window to be OPEN before timing anything. Without this,
            // the only thing keeping the walk inside the window is that `READ_DELAY_MS`
            // (1 ms) is longer than the main thread takes to get from `Thread.start()` to
            // `driveShaped`'s `t0` — a wall-clock assumption about an unloaded machine,
            // not a structural guarantee. On a starved host the main thread loses that
            // race, `walkStart` predates the drive, and the trial classifies
            // WALK_PRECEDES_DRIVE. See [driveOpen] for the measured rate.
            check(driveOpen.await(BoundedReadFixtures.DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the ${arm.name} arm's drive did not open its window within " +
                    "${BoundedReadFixtures.DRAIN_TIMEOUT_MS}ms"
            }
            Thread.sleep(BoundedReadFixtures.READ_DELAY_MS)
            walkStart.set(System.nanoTime())
            walk = BoundedReadFixtures.pagedWalk(
                host = rig.host,
                ref = rig.targetRef,
                limit = pageLimit,
                onPage = { index, _ -> if (index == 1) firstPageReturned.set(true) },
            )
        } finally {
            walkEnd.set(System.nanoTime())
            // Set LAST, and in a finally: the covering arm's drive stops on this flag, so a
            // walk that threw must still release the drive — otherwise a fixture error
            // surfaces as the extension cap rather than as its own message. The finally
            // covers the gate above as well, so a trial whose drive never opened releases
            // the drive too rather than deadlocking against it. Ordering matters as much as
            // the flag: `walkEnd` is written before `walkDone`, so a drive that observes the
            // flag observes an end instant that is already final.
            walkDone.set(true)
        }
    }.apply { isDaemon = true; name = "containment-pager-${arm.name}"; start() }

    // Opened from inside the drive loop, which `driveShaped` reaches only after it has
    // stamped its `t0`; `driveShaped` requires `adds > 0` and evaluates `stopEarly` before
    // its first add, so this fires exactly once per trial and always. Composed with each
    // arm's own stop rule rather than replacing it — the arms are unchanged.
    val openOnFirstPoll: (() -> Boolean) -> () -> Boolean = { stop ->
        { driveOpen.countDown(); stop() }
    }

    val shape = when (arm) {
        // Cut at the walk's first page: every later page falls outside the window.
        DriveWindowArm.TRUNCATED -> DriveShape.paced(
            adds = adds,
            spacingNanos = spacingNanos,
            stopEarly = openOnFirstPoll { firstPageReturned.get() },
        )
        // Run until the walk has finished: the window closes after it, by construction.
        DriveWindowArm.COVERING -> DriveShape.paced(
            adds = adds,
            spacingNanos = spacingNanos,
            stopEarly = openOnFirstPoll { false },
            extendWhile = { !walkDone.get() },
            extensionCapAdds = CONTAINMENT_EXTENSION_CAP_ADDS,
        )
    }

    val timed = rig.driveShaped(shape)
    pager.join(BoundedReadFixtures.DRAIN_TIMEOUT_MS)
    val completed = checkNotNull(walk) {
        "the ${arm.name} arm's paged walk did not complete within " +
            "${BoundedReadFixtures.DRAIN_TIMEOUT_MS}ms"
    }

    return ContainmentTrial(
        arm = arm,
        drive = timed.outcome,
        driveWindow = timed.window,
        driveAdds = timed.adds,
        walk = completed,
        walkWindow = Window(startNanos = walkStart.get(), endNanos = walkEnd.get()),
        driveAddsWindow = timed.addsWindow,
    )
}
