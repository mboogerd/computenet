package civictech.demo.allocatorobserve.view

import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A named string constant for the residual-drift explanation (fpml.3-D4). */
const val RESIDUAL_LABEL: String =
    "starvation attribution unavailable: no socaity draw-exclusion record shape is pinned"

/** The linear month-end burn projection rule named in report output (fpml.3-D3). */
const val PROJECTION_RULE: String = "linear"

/** A half-open time range; `from` and `to` are interpreted as UTC instants (fpml.3-D1). */
data class TimeRange(val from: Instant, val to: Instant) {
    init {
        require(!from.isAfter(to)) { "TimeRange requires from <= to, got from=$from to=$to" }
    }
}

/**
 * The declared-vs-enacted diff for one sub-interval of a window, against the
 * single declaration in force throughout that sub-interval.
 */
data class SubIntervalDiff(
    val range: TimeRange,
    val declaration: AllocationDeclaration,
    val enactedHours: Map<String, Double>,
    val enactedShare: Map<String, Double>,
    val declaredShare: Map<String, Double>,
    val diff: Map<String, Double>,
)

/** Window-level declared-vs-enacted drift for one project, with the residual shape of fpml.3-D4. */
data class ProjectDrift(
    val enactedHours: Double,
    val enactedShare: Double,
    val declaredShare: Double,
    val drift: Double,
    val residual: Double,
    val residualLabel: String,
)

/** The full R6 report content for one queried window. */
data class WindowReport(
    val window: TimeRange,
    val subIntervals: List<SubIntervalDiff>,
    val perProject: Map<String, ProjectDrift>,
    val totalHours: Double,
    val beforeFirstDeclarationHours: Map<String, Double>,
)

/** The R5 monthly cap-tracking content, evaluated at [now]. */
data class CapReport(
    val monthStart: Instant,
    val monthEnd: Instant,
    val now: Instant,
    val capHours: Double?,
    val hoursToDate: Double,
    val elapsedFraction: Double,
    val projectedMonthEndHours: Double?,
    val projectionRule: String,
    val capReached: Boolean,
)

/**
 * Derives the R6 declared-vs-enacted window report as pure arithmetic over
 * [timeline] and the [hours] function. Each sub-interval of [window] is
 * diffed against the single declaration in force throughout it
 * (`timeline.intervalsWithin`); time before the first declaration event is
 * excluded from every share, diff and drift and reported separately in
 * [WindowReport.beforeFirstDeclarationHours] (fpml.3-D8).
 */
fun windowReport(
    window: TimeRange,
    timeline: DeclarationTimeline,
    projects: Set<String>,
    hours: (project: String, from: Instant, to: Instant) -> Double,
): WindowReport {
    val intervals = timeline.intervalsWithin(window.from, window.to)

    val subIntervals =
        intervals.map { interval ->
            val normalizedWeights = interval.declaration.normalizedWeights()
            val projectSet = projects + normalizedWeights.keys
            val enactedHours = projectSet.associateWith { p -> hours(p, interval.from, interval.to) }
            val total = enactedHours.values.sum()
            val enactedShare =
                projectSet.associateWith { p -> if (total == 0.0) 0.0 else enactedHours.getValue(p) / total }
            val declaredShare = projectSet.associateWith { p -> normalizedWeights[p] ?: 0.0 }
            val diff = projectSet.associateWith { p -> enactedShare.getValue(p) - declaredShare.getValue(p) }
            SubIntervalDiff(
                range = TimeRange(interval.from, interval.to),
                declaration = interval.declaration,
                enactedHours = enactedHours,
                enactedShare = enactedShare,
                declaredShare = declaredShare,
                diff = diff,
            )
        }

    val allProjects = projects + subIntervals.flatMap { it.enactedHours.keys }

    val intervalDurationNanos = intervals.map { Duration.between(it.from, it.to).toNanos().toDouble() }
    val totalCoveredDurationNanos = intervalDurationNanos.sum()
    val totalCoveredHours = subIntervals.sumOf { sub -> sub.enactedHours.values.sum() }

    val perProject =
        allProjects.associateWith { project ->
            val enactedHoursForProject = subIntervals.sumOf { sub -> sub.enactedHours[project] ?: 0.0 }
            val enactedShareForProject =
                if (totalCoveredHours == 0.0) 0.0 else enactedHoursForProject / totalCoveredHours
            val declaredShareForProject =
                if (totalCoveredDurationNanos == 0.0) {
                    0.0
                } else {
                    subIntervals.indices.sumOf { i ->
                        val weight = subIntervals[i].declaredShare[project] ?: 0.0
                        weight * intervalDurationNanos[i]
                    } / totalCoveredDurationNanos
                }
            val drift = enactedShareForProject - declaredShareForProject
            ProjectDrift(
                enactedHours = enactedHoursForProject,
                enactedShare = enactedShareForProject,
                declaredShare = declaredShareForProject,
                drift = drift,
                residual = drift,
                residualLabel = RESIDUAL_LABEL,
            )
        }

    val uncovered = timeline.uncoveredBefore(window.from, window.to)
    val beforeFirstDeclarationHours =
        if (uncovered == null) {
            emptyMap()
        } else {
            val (uncoveredFrom, uncoveredTo) = uncovered
            projects.associateWith { p -> hours(p, uncoveredFrom, uncoveredTo) }
        }

    return WindowReport(
        window = window,
        subIntervals = subIntervals,
        perProject = perProject,
        totalHours = totalCoveredHours,
        beforeFirstDeclarationHours = beforeFirstDeclarationHours,
    )
}

/**
 * Derives the R5 cap-tracking report as pure arithmetic, evaluated at [now]
 * (never `Instant.now()` internally — the caller injects [now], fpml.3-D7).
 * Month bounds are computed in UTC (fpml.3-D1). The projection is linear
 * (fpml.3-D3): `hoursToDate / elapsedFraction`, null when [now] is exactly
 * the month start (fpml.3-D9 / D3 edge).
 */
fun capReport(
    now: Instant,
    timeline: DeclarationTimeline,
    hoursToDate: Double,
): CapReport {
    val nowUtc = now.atZone(ZoneOffset.UTC)
    val monthStartZoned = nowUtc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
    val monthStart = monthStartZoned.toInstant()
    val monthEnd = monthStartZoned.plusMonths(1).toInstant()

    val capHours = timeline.inForceAt(now)?.monthlyCapHours

    val monthDurationNanos = Duration.between(monthStart, monthEnd).toNanos().toDouble()
    val elapsedNanos = Duration.between(monthStart, now).toNanos().toDouble()
    val elapsedFraction = if (monthDurationNanos == 0.0) 0.0 else elapsedNanos / monthDurationNanos

    val projectedMonthEndHours = if (elapsedFraction == 0.0) null else hoursToDate / elapsedFraction

    val capReached = capHours != null && hoursToDate >= capHours

    return CapReport(
        monthStart = monthStart,
        monthEnd = monthEnd,
        now = now,
        capHours = capHours,
        hoursToDate = hoursToDate,
        elapsedFraction = elapsedFraction,
        projectedMonthEndHours = projectedMonthEndHours,
        projectionRule = PROJECTION_RULE,
        capReached = capReached,
    )
}
