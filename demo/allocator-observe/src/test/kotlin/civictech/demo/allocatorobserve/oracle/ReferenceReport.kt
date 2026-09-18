package civictech.demo.allocatorobserve.oracle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The independent R6 reference fold (design entry fpml.5-D6): raw spend-log
 * lines plus an explicit declaration history in, the `report` exchange document
 * out.
 *
 * ## Why it may not touch the implementation
 *
 * This is one half of a differential oracle. Its value is entirely in being a
 * *second*, independently written answer to the same question — if it reached
 * into `civictech.demo.allocatorobserve.view` for a timeline, a session ledger
 * or a rounding decision, a comparison against the served report would be a
 * tautology rather than a check. So this file imports only `kotlinx.serialization.json`,
 * `java.time`, `java.math` and the Kotlin standard library; it parses lines with
 * its own parser, builds its own declaration timeline, and does its own
 * arithmetic. [ReferenceIndependenceTest] enforces that lexically, and the
 * enforcement is the point: the rule is easy to break by accident, months from
 * now, in a one-line "reuse" that would silently dissolve the oracle.
 *
 * Correspondingly, it is written from `demo/allocator-observe/README.md`'s
 * *Oracle exchange shape* section (the R6 wording plus the conventions that make
 * the numbers well defined), not from reading the views. The README and this
 * file are meant to be re-derivable from each other; the socaity replay script
 * is written from the same section.
 *
 * ## What it does not model
 *
 * It computes the `report` document only — not the `ingest` member (poll counts,
 * checkpoint offsets, re-baseline counts), which is a property of the ingest
 * machinery and has no specification-level definition to re-derive.
 * `publishedAt` is `now`, because the reference is a pure function of its
 * inputs.
 */
object ReferenceReport {

    /**
     * The residual-drift explanation carried on every project (fpml.3-D4).
     *
     * Deliberately a copy of the production constant's text rather than an
     * import of it: the string is part of the *exchange shape*, so the oracle
     * has to assert the implementation still emits this exact sentence. An
     * import would make the assertion vacuous.
     */
    const val RESIDUAL_LABEL: String =
        "starvation attribution unavailable: no socaity draw-exclusion record shape is pinned"

    /** The month-end burn projection rule this reference implements. */
    const val PROJECTION_RULE: String = "linear"

    private val json = Json

    /**
     * The `report` exchange document for [lines] under [history], published at
     * [now] over a window of [windowLength].
     *
     * @param lines raw spend-log lines, exactly as they appear in the log; lines
     *   that are not v1 records are excluded and contribute nothing.
     * @param history the declaration events, each with the instant it was
     *   observed; order is irrelevant (it is sorted here).
     */
    fun compute(
        lines: List<String>,
        history: List<DeclarationSpec>,
        now: Instant,
        windowLength: Duration,
    ): JsonObject {
        val records = lines.mapNotNull(::parseRecord)
        val classified = records.map { it to classify(it) }
        val sessions = classified.mapNotNull { (_, outcome) -> (outcome as? Attribution.Attributed)?.session }
        val unattributed =
            classified.mapNotNull { (record, outcome) ->
                (outcome as? Attribution.Unattributable)?.let { record to it.reason }
            }

        val windowFrom = now.minus(windowLength)
        val subIntervals = subIntervalsOf(history, windowFrom, now)

        // The GLOBAL project set (README "Oracle exchange shape" pin): every
        // project with any attributed session anywhere in the log, plus every
        // project named in any declaration in the given history — regardless
        // of window or coverage. Every project-keyed object below is keyed
        // over this one set, with explicit zeros for a project that has no
        // hours in a particular sub-range. This mirrors production's
        // `AllocatorReportViews.publish` (`ledger.projects() + declaredProjects`),
        // not the per-sub-interval "only projects that appear here" set this
        // file used before (computenet-nv04w).
        val globalProjects =
            (sessions.map { it.project }.toSet() + history.flatMap { it.weights.keys }).toSortedSet()

        val coverageStart = subIntervals.firstOrNull()?.range?.first ?: now
        val beforeFirst = hoursByProject(sessions, windowFrom, coverageStart, globalProjects)

        val subIntervalReports = subIntervals.map { interval -> subIntervalReport(interval, sessions, globalProjects) }
        val totalHours = subIntervalReports.sumOf { it.totalHours }
        val coveredNanos = subIntervalReports.sumOf { it.nanos }

        val projects = globalProjects

        // (`projects` is `globalProjects`: every project-keyed object in this
        // document is keyed over the same set — see the comment above.)
        return buildJsonObject {
            put("publishedAt", JsonPrimitive(now.toString()))
            put(
                "window",
                buildJsonObject {
                    put("window", rangeJson(windowFrom, now))
                    put("subIntervals", JsonArray(subIntervalReports.map { it.json }))
                    put(
                        "perProject",
                        buildJsonObject {
                            projects.forEach { project ->
                                val hours = subIntervalReports.sumOf { it.enactedHours[project] ?: 0.0 }
                                val enactedShare = if (totalHours == 0.0) 0.0 else hours / totalHours
                                val declaredShare =
                                    if (coveredNanos == 0L) {
                                        0.0
                                    } else {
                                        subIntervalReports.sumOf {
                                            (it.declaredShare[project] ?: 0.0) * it.nanos.toDouble()
                                        } / coveredNanos.toDouble()
                                    }
                                val drift = enactedShare - declaredShare
                                put(
                                    project,
                                    buildJsonObject {
                                        put("enactedHours", JsonPrimitive(hours))
                                        put("enactedShare", JsonPrimitive(enactedShare))
                                        put("declaredShare", JsonPrimitive(declaredShare))
                                        put("drift", JsonPrimitive(drift))
                                        put("residual", JsonPrimitive(drift))
                                        put("residualLabel", JsonPrimitive(RESIDUAL_LABEL))
                                    },
                                )
                            }
                        },
                    )
                    put("totalHours", JsonPrimitive(totalHours))
                    put(
                        "beforeFirstDeclarationHours",
                        buildJsonObject {
                            beforeFirst.toSortedMap().forEach { (project, hours) ->
                                put(project, JsonPrimitive(hours))
                            }
                        },
                    )
                },
            )
            put("cap", capJson(sessions, history, now))
            put(
                "unattributable",
                buildJsonObject {
                    unattributed
                        .groupingBy { (_, reason) -> reason.name }
                        .eachCount()
                        .toSortedMap()
                        .forEach { (reason, count) -> put(reason, JsonPrimitive(count.toLong())) }
                },
            )
            put(
                "unattributableRecords",
                buildJsonArray {
                    unattributed
                        .map { (record, _) -> record }
                        .sortedWith(recordOrder)
                        .forEach { add(recordJson(it)) }
                },
            )
        }
    }

    // -----------------------------------------------------------------------
    // Line parsing (the v1 schema, re-derived)
    // -----------------------------------------------------------------------

    private val recordOrder =
        compareBy<RawRecord>({ it.project }, { it.started }, { it.ended }, { it.machine }, { it.workItem })

    /**
     * A v1 spend-log line: a JSON object with exactly `v, project, machine,
     * work_item, started, ended`, `v == 1`, every other field a string. Anything
     * else is not a record at all — not a malformed one — so it contributes
     * nothing and appears nowhere in the report.
     */
    private fun parseRecord(line: String): RawRecord? {
        val element =
            try {
                json.parseToJsonElement(line)
            } catch (_: Exception) {
                return null
            }
        val obj = element as? JsonObject ?: return null
        if (obj.keys != V1_KEYS) return null
        val v = obj["v"]?.jsonPrimitive ?: return null
        if (v.isString || v.content.toIntOrNull() != 1) return null
        fun str(key: String): String? =
            obj[key]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        return RawRecord(
            v = 1,
            project = str("project") ?: return null,
            machine = str("machine") ?: return null,
            workItem = str("work_item") ?: return null,
            started = str("started") ?: return null,
            ended = str("ended") ?: return null,
        )
    }

    private val V1_KEYS = setOf("v", "project", "machine", "work_item", "started", "ended")

    private fun classify(record: RawRecord): Attribution {
        val started = parseInstant(record.started) ?: return Attribution.Unattributable(Reason.UNPARSEABLE_STARTED)
        val ended = parseInstant(record.ended) ?: return Attribution.Unattributable(Reason.UNPARSEABLE_ENDED)
        if (ended.isBefore(started)) return Attribution.Unattributable(Reason.ENDED_BEFORE_STARTED)
        return Attribution.Attributed(Session(record.project, started, ended))
    }

    private fun parseInstant(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    // -----------------------------------------------------------------------
    // Timeline and attribution
    // -----------------------------------------------------------------------

    /**
     * The declaration in force over each maximal sub-range of `[from, to)`:
     * declaration `i` holds from its own `observedAt` until the next one's,
     * clipped to the window. A declaration observed before the window is in
     * force at the window's start; one observed at or after `to` never appears.
     */
    private fun subIntervalsOf(
        history: List<DeclarationSpec>,
        from: Instant,
        to: Instant,
    ): List<SubInterval> {
        val sorted = history.sortedBy { it.observedAt }
        return sorted.indices.mapNotNull { i ->
            val declaration = sorted[i]
            val nextBoundary = sorted.getOrNull(i + 1)?.observedAt ?: to
            val start = maxOf(declaration.observedAt, from)
            val end = minOf(nextBoundary, to)
            if (start < end) SubInterval(start to end, declaration) else null
        }
    }

    /** Overlap of a session with `[from, to)`, in hours; half-open, so a touch at an edge is zero. */
    private fun overlapHours(session: Session, from: Instant, to: Instant): Double =
        overlapNanos(session, from, to) / NANOS_PER_HOUR

    private fun overlapNanos(session: Session, from: Instant, to: Instant): Long {
        val start = maxOf(session.started, from)
        val end = minOf(session.ended, to)
        return if (start >= end) 0L else Duration.between(start, end).toNanos()
    }

    /**
     * Hours per project overlapping `[from, to)`, keyed over [projects] with
     * an explicit 0.0 for a project with no overlap — the GLOBAL key set, not
     * "only projects seen in this sub-range" (computenet-nv04w).
     */
    private fun hoursByProject(sessions: List<Session>, from: Instant, to: Instant, projects: Set<String>): Map<String, Double> {
        if (from >= to) return emptyMap()
        val byProject = projects.associateWithTo(mutableMapOf()) { 0.0 }
        sessions.forEach { session ->
            if (session.project !in byProject) return@forEach
            val hours = overlapHours(session, from, to)
            if (hours > 0.0) byProject.merge(session.project, hours, Double::plus)
        }
        return byProject
    }

    private fun subIntervalReport(interval: SubInterval, sessions: List<Session>, projects: Set<String>): SubIntervalReport {
        val (from, to) = interval.range
        val enacted = hoursByProject(sessions, from, to, projects)
        val weights = interval.declaration.weights
        val weightSum = weights.values.sum()
        val totalHours = enacted.values.sum()
        val enactedHours = projects.associateWith { enacted[it] ?: 0.0 }
        val declaredShare =
            projects.associateWith { project ->
                if (weightSum == 0.0) 0.0 else (weights[project] ?: 0.0) / weightSum
            }
        val enactedShare =
            projects.associateWith { project ->
                if (totalHours == 0.0) 0.0 else (enactedHours.getValue(project)) / totalHours
            }
        val diff = projects.associateWith { enactedShare.getValue(it) - declaredShare.getValue(it) }

        val json =
            buildJsonObject {
                put("range", rangeJson(from, to))
                put(
                    "declaration",
                    buildJsonObject {
                        put(
                            "weights",
                            buildJsonObject {
                                weights.toSortedMap().forEach { (p, w) -> put(p, JsonPrimitive(w)) }
                            },
                        )
                        put("monthlyCapHours", JsonPrimitive(interval.declaration.monthlyCapHours))
                        put(
                            "window",
                            interval.declaration.window?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull,
                        )
                    },
                )
                put("enactedHours", doubleMapJson(enactedHours))
                put("enactedShare", doubleMapJson(enactedShare))
                put("declaredShare", doubleMapJson(declaredShare))
                put("diff", doubleMapJson(diff))
            }

        return SubIntervalReport(
            json = json,
            projects = projects,
            enactedHours = enactedHours,
            declaredShare = declaredShare,
            totalHours = totalHours,
            nanos = Duration.between(from, to).toNanos(),
        )
    }

    // -----------------------------------------------------------------------
    // Cap tracking
    // -----------------------------------------------------------------------

    private fun capJson(
        sessions: List<Session>,
        history: List<DeclarationSpec>,
        now: Instant,
    ): JsonObject {
        val zoned = now.atZone(ZoneOffset.UTC)
        val monthStart = zoned.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val monthEnd = zoned.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val inForce = history.filter { !it.observedAt.isAfter(now) }.maxByOrNull { it.observedAt }
        val capHours = inForce?.monthlyCapHours
        val hoursToDate = sessions.sumOf { overlapHours(it, monthStart, now) }
        val monthNanos = Duration.between(monthStart, monthEnd).toNanos().toDouble()
        val elapsedFraction = Duration.between(monthStart, now).toNanos().toDouble() / monthNanos
        val projected = if (elapsedFraction == 0.0) null else hoursToDate / elapsedFraction
        return buildJsonObject {
            put("monthStart", JsonPrimitive(monthStart.toString()))
            put("monthEnd", JsonPrimitive(monthEnd.toString()))
            put("now", JsonPrimitive(now.toString()))
            put("capHours", capHours?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("hoursToDate", JsonPrimitive(hoursToDate))
            put("elapsedFraction", JsonPrimitive(elapsedFraction))
            put("projectedMonthEndHours", projected?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("projectionRule", JsonPrimitive(PROJECTION_RULE))
            put("capReached", JsonPrimitive(capHours != null && hoursToDate >= capHours))
        }
    }

    // -----------------------------------------------------------------------
    // Small JSON helpers
    // -----------------------------------------------------------------------

    private fun rangeJson(from: Instant, to: Instant): JsonObject =
        buildJsonObject {
            put("from", JsonPrimitive(from.toString()))
            put("to", JsonPrimitive(to.toString()))
        }

    private fun doubleMapJson(values: Map<String, Double>): JsonObject =
        buildJsonObject {
            values.toSortedMap().forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

    private fun recordJson(record: RawRecord): JsonObject =
        buildJsonObject {
            put("v", JsonPrimitive(record.v))
            put("project", JsonPrimitive(record.project))
            put("machine", JsonPrimitive(record.machine))
            put("workItem", JsonPrimitive(record.workItem))
            put("started", JsonPrimitive(record.started))
            put("ended", JsonPrimitive(record.ended))
        }

    private const val NANOS_PER_HOUR: Double = 3_600_000_000_000.0

    // -----------------------------------------------------------------------
    // Internal model
    // -----------------------------------------------------------------------

    private data class RawRecord(
        val v: Int,
        val project: String,
        val machine: String,
        val workItem: String,
        val started: String,
        val ended: String,
    )

    private data class Session(val project: String, val started: Instant, val ended: Instant)

    private data class SubInterval(val range: Pair<Instant, Instant>, val declaration: DeclarationSpec)

    private class SubIntervalReport(
        val json: JsonObject,
        val projects: Set<String>,
        val enactedHours: Map<String, Double>,
        val declaredShare: Map<String, Double>,
        val totalHours: Double,
        val nanos: Long,
    )

    /** Why a record carries no hours. Names match the exchange document's `unattributable` keys. */
    private enum class Reason { UNPARSEABLE_STARTED, UNPARSEABLE_ENDED, ENDED_BEFORE_STARTED }

    private sealed interface Attribution {
        data class Attributed(val session: Session) : Attribution

        data class Unattributable(val reason: Reason) : Attribution
    }
}

/**
 * One declaration event of the history the reference is given: the instant it
 * was observed, the raw (un-normalised) project weights, the monthly cap and the
 * declared window string.
 *
 * Raw weights, not shares: the exchange document echoes the declaration as
 * written, and the reference normalises to shares itself.
 */
data class DeclarationSpec(
    val observedAt: Instant,
    val weights: Map<String, Double>,
    val monthlyCapHours: Double,
    val window: String?,
)

/** The `report` document as a [JsonObject], for callers that already hold text. */
fun parseReport(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
