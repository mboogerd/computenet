package civictech.demo.allocatorobserve.http

import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.view.AllocatorReport
import civictech.demo.allocatorobserve.view.CapReport
import civictech.demo.allocatorobserve.view.ProjectDrift
import civictech.demo.allocatorobserve.view.SubIntervalDiff
import civictech.demo.allocatorobserve.view.TimeRange
import civictech.demo.allocatorobserve.view.WindowReport
import civictech.demo.shell.esc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `@Serializable` wire shape [ServedState] is served as (fpml.4-D8), and
 * the mapping functions that build it. This document IS the exchange shape
 * `computenet-fpml.5-D2` (the oracle harness) refers to: the `GET /state`
 * body and every `/events` SSE frame (task `computenet-fpml.4.2`) are the
 * same document, byte-for-byte, produced by [ServedState.toJson].
 *
 * One module-level [json] instance backs every encoder here so the three
 * served documents ([ServedState.toJson], [ServedState.ingestJson],
 * [ServedState.reportJson]) are guaranteed to agree — `ingestJson`/`reportJson`
 * encode the exact same [IngestDto]/[ReportDto] values [toJson] nests under
 * `ingest`/`report`, never a re-derived copy.
 *
 * Field conventions (fpml.4-D8):
 * - [java.time.Instant] fields are encoded via `toString()` (ISO-8601).
 * - `Double` fields (hours, shares, fractions) are encoded raw, never rounded
 *   or formatted — the F5 oracle normalises at comparison time.
 * - Every project-keyed map is built from `toSortedMap()` so its JSON object
 *   has keys in sorted order — required for the document to be deterministic
 *   for equal [ServedState] values (this task's acceptance criteria).
 * - [AllocatorReport.unattributable] is keyed by [civictech.demo.allocatorobserve.view.UnattributableReason.name],
 *   sorted the same way.
 * - `records` / `unattributableRecords` arrays are sorted by
 *   `(project, started, ended, machine, workItem)` so their order is a
 *   function of membership alone — a `Set` has none of its own.
 */
private val json = Json { encodeDefaults = true }

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

@Serializable
data class SpendRecordDto(
    val v: Int,
    val project: String,
    val machine: String,
    val workItem: String,
    val started: String,
    val ended: String,
)

@Serializable
data class IngestFailureCountsDto(
    val malformed: Long,
    val unknownVersion: Long,
    val declarationParseFailed: Long,
)

/** The `ingest` member of [ServedStateDto]: [IngestHealth]'s fields plus the live record set. */
@Serializable
data class IngestDto(
    val recordCount: Int,
    val checkpointOffset: Long?,
    val reBaselineCount: Long,
    val polls: Long,
    val lastPollAt: String?,
    val declarationEvents: Int,
    val declarationReplayFailures: Long,
    val failures: IngestFailureCountsDto,
    val records: List<SpendRecordDto>,
)

@Serializable
data class RangeDto(val from: String, val to: String)

@Serializable
data class DeclarationDto(
    val weights: Map<String, Double>,
    val monthlyCapHours: Double,
    val window: String?,
)

@Serializable
data class SubIntervalDto(
    val range: RangeDto,
    val declaration: DeclarationDto,
    val enactedHours: Map<String, Double>,
    val enactedShare: Map<String, Double>,
    val declaredShare: Map<String, Double>,
    val diff: Map<String, Double>,
)

@Serializable
data class ProjectDriftDto(
    val enactedHours: Double,
    val enactedShare: Double,
    val declaredShare: Double,
    val drift: Double,
    val residual: Double,
    val residualLabel: String,
)

@Serializable
data class WindowReportDto(
    val window: RangeDto,
    val subIntervals: List<SubIntervalDto>,
    val perProject: Map<String, ProjectDriftDto>,
    val totalHours: Double,
    val beforeFirstDeclarationHours: Map<String, Double>,
)

@Serializable
data class CapReportDto(
    val monthStart: String,
    val monthEnd: String,
    val now: String,
    val capHours: Double?,
    val hoursToDate: Double,
    val elapsedFraction: Double,
    val projectedMonthEndHours: Double?,
    val projectionRule: String,
    val capReached: Boolean,
)

/** The `report` member of [ServedStateDto]: [AllocatorReport] mapped field for field. */
@Serializable
data class ReportDto(
    val publishedAt: String,
    val window: WindowReportDto,
    val cap: CapReportDto,
    val unattributable: Map<String, Long>,
    val unattributableRecords: List<SpendRecordDto>,
)

/** The whole `GET /state` / `/events` document: `{"ingest": ..., "report": ...}`. */
@Serializable
data class ServedStateDto(
    val ingest: IngestDto,
    val report: ReportDto,
)

// ---------------------------------------------------------------------------
// Domain -> DTO mapping
// ---------------------------------------------------------------------------

private val recordOrder =
    compareBy<SpendRecord>({ it.project }, { it.started }, { it.ended }, { it.machine }, { it.workItem })

private fun SpendRecord.toDto(): SpendRecordDto = SpendRecordDto(v, project, machine, workItem, started, ended)

private fun Collection<SpendRecord>.toSortedDtoList(): List<SpendRecordDto> =
    sortedWith(recordOrder).map { it.toDto() }

private fun IngestFailureCounts.toDto(): IngestFailureCountsDto =
    IngestFailureCountsDto(malformed, unknownVersion, declarationParseFailed)

private fun IngestHealth.toDto(records: Set<SpendRecord>): IngestDto = IngestDto(
    recordCount = recordCount,
    checkpointOffset = checkpointOffset,
    reBaselineCount = reBaselineCount,
    polls = polls,
    lastPollAt = lastPollAt?.toString(),
    declarationEvents = declarationEvents,
    declarationReplayFailures = declarationReplayFailures,
    failures = failures.toDto(),
    records = records.toSortedDtoList(),
)

private fun TimeRange.toDto(): RangeDto = RangeDto(from.toString(), to.toString())

private fun AllocationDeclaration.toDto(): DeclarationDto =
    DeclarationDto(weights = weights.toSortedMap(), monthlyCapHours = monthlyCapHours, window = window)

private fun SubIntervalDiff.toDto(): SubIntervalDto = SubIntervalDto(
    range = range.toDto(),
    declaration = declaration.toDto(),
    enactedHours = enactedHours.toSortedMap(),
    enactedShare = enactedShare.toSortedMap(),
    declaredShare = declaredShare.toSortedMap(),
    diff = diff.toSortedMap(),
)

private fun ProjectDrift.toDto(): ProjectDriftDto =
    ProjectDriftDto(enactedHours, enactedShare, declaredShare, drift, residual, residualLabel)

private fun WindowReport.toDto(): WindowReportDto = WindowReportDto(
    window = window.toDto(),
    subIntervals = subIntervals.map { it.toDto() },
    perProject = perProject.toSortedMap().mapValues { it.value.toDto() },
    totalHours = totalHours,
    beforeFirstDeclarationHours = beforeFirstDeclarationHours.toSortedMap(),
)

private fun CapReport.toDto(): CapReportDto = CapReportDto(
    monthStart = monthStart.toString(),
    monthEnd = monthEnd.toString(),
    now = now.toString(),
    capHours = capHours,
    hoursToDate = hoursToDate,
    elapsedFraction = elapsedFraction,
    projectedMonthEndHours = projectedMonthEndHours,
    projectionRule = projectionRule,
    capReached = capReached,
)

private fun AllocatorReport.toDto(): ReportDto = ReportDto(
    publishedAt = publishedAt.toString(),
    window = window.toDto(),
    cap = cap.toDto(),
    unattributable = unattributable.entries.sortedBy { it.key.name }.associate { (k, v) -> k.name to v },
    unattributableRecords = unattributableRecords.toSortedDtoList(),
)

private fun ServedState.toDto(): ServedStateDto = ServedStateDto(
    ingest = ingest.toDto(records),
    report = report.toDto(),
)

/** The whole `GET /state` / `/events` document for this [ServedState]. */
fun ServedState.toJson(): String = json.encodeToString(ServedStateDto.serializer(), toDto())

/** The `ingest` member alone, for `GET /state/ingest` — byte-identical to `toJson`'s `ingest` field. */
fun ServedState.ingestJson(): String = json.encodeToString(IngestDto.serializer(), ingest.toDto(records))

/**
 * The `report` exchange document for one [AllocatorReport] alone — the same
 * encoder [ServedState.reportJson] delegates to, so the F5 external-oracle
 * harness (`oracle/ReportUnderTest`) and `GET /state/report` provably encode
 * one way: neither can drift into a second, undetected serialisation of the
 * same value.
 */
fun AllocatorReport.toReportJson(): String = json.encodeToString(ReportDto.serializer(), toDto())

/** The `report` member alone, for `GET /state/report` — byte-identical to `toJson`'s `report` field. */
fun ServedState.reportJson(): String = report.toReportJson()

/**
 * The same frozen-fold envelope `AllocatorRoutes`' `/state*` 503 body carries
 * (fpml.4-D6) — `{"ingest":"frozen","failure":"<class>: <message>","stale_status":200,
 * "stale":<this document>}` — reused by `AllocatorObserveApp`'s `/events` SSE frame
 * so a client can tell a frozen fold from a quiet one by the SSE stream alone
 * (computenet-w20a4). D6's own text ("SSE simply stops receiving frames") covers
 * only an already-connected subscriber; this is the connect-after-death and
 * still-connected-at-death cases D6 did not decide.
 *
 * Not a call to `AllocatorRoutes`' private `respondFold` — that class is not in
 * this task's file claim — but byte-for-byte the same shape over the same
 * [ServedState.toJson] body, built the same way (`esc` on `failure.toString()`).
 */
fun ServedState.frozenJson(frozen: PollLoopStopped): String = buildString {
    append("""{"ingest":"frozen",""")
    append("\"failure\":").append(esc(frozen.failure.toString()))
    append(",\"stale_status\":200")
    append(",\"stale\":").append(toJson())
    append('}')
}
