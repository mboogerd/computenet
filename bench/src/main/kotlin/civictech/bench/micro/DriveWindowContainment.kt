package civictech.bench.micro

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
 */
data class TimedDrive(
    val outcome: DriveOutcome,
    val window: Window,
    val adds: Int,
    val shape: DriveShape,
)

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
) {
    /** How the walk's window sits inside — or outside — the drive's. */
    val containment: DriveWindowContainment get() = classify(driveWindow, walkWindow)

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

    val pager = Thread {
        Thread.sleep(BoundedReadFixtures.READ_DELAY_MS)
        walkStart.set(System.nanoTime())
        try {
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
            // surfaces as the extension cap rather than as its own message. Ordering
            // matters as much as the flag: `walkEnd` is written before `walkDone`, so a
            // drive that observes the flag observes an end instant that is already final.
            walkDone.set(true)
        }
    }.apply { isDaemon = true; name = "containment-pager-${arm.name}"; start() }

    val shape = when (arm) {
        // Cut at the walk's first page: every later page falls outside the window.
        DriveWindowArm.TRUNCATED -> DriveShape.paced(
            adds = adds,
            spacingNanos = spacingNanos,
            stopEarly = { firstPageReturned.get() },
        )
        // Run until the walk has finished: the window closes after it, by construction.
        DriveWindowArm.COVERING -> DriveShape.paced(
            adds = adds,
            spacingNanos = spacingNanos,
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
    )
}
