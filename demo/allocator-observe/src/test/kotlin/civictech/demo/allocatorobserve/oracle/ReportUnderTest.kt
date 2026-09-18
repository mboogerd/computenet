package civictech.demo.allocatorobserve.oracle

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.http.toReportJson
import civictech.demo.allocatorobserve.ingest.SpendLogIngester
import civictech.demo.allocatorobserve.view.AllocatorReportViews
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Computes what this module SERVES for a given spend log and declaration
 * history — the "actual" side of the F5 external-oracle comparison (design
 * entry fpml.5-D7), driven through the exact production path
 * [ExternalOracleComparisonTest] and its self-test both use: a real
 * [SpendLogIngester] poll over bytes on disk, [AllocatorReportViews] folding
 * the same two [SetCell]s [civictech.demo.allocatorobserve.AllocatorObserveApp]
 * wires together, and the same [civictech.demo.allocatorobserve.http.AllocatorJson]
 * encoder `GET /state/report` serves through (`AllocatorReport.toReportJson`,
 * `computenet-fpml.5.4`). Unlike [ReferenceReport], this object is the
 * *implementation* side of the oracle and is free to import it.
 */
object ReportUnderTest {

    /**
     * [log]'s content, folded once, under [history] (fed directly into a fresh
     * declaration cell — this harness is given the history, not a
     * `DeclarationIngester` polling a YAML file), published at [now] over
     * [windowLength]: the `report` document, as `GET /state/report` would
     * encode it for a process that had ingested exactly this.
     *
     * A fresh run directory is used for the checkpoint, so every call is a
     * `FirstStart` whole read of [log] (`unverified:` per the bead — a log
     * larger than one hand-off is still one `poll()`, because
     * `SpendLogTailReader` hands off in bounded batches within a single poll).
     */
    fun compute(
        log: Path,
        history: List<DeclarationSpec>,
        now: Instant,
        windowLength: Duration,
    ): JsonElement {
        val runDir = Files.createTempDirectory("report-under-test-run")
        val records = SetCell<SpendRecord>()
        val declarations = SetCell<DeclarationEvent>()
        val views = AllocatorReportViews.derivedFrom(records, declarations, windowLength, now = { now })

        SpendLogIngester(log, runDir, records).poll()
        history.forEach { spec ->
            declarations.inlet.call.add(
                DeclarationEvent(
                    observedAt = spec.observedAt,
                    declaration = AllocationDeclaration(spec.weights, spec.monthlyCapHours, spec.window),
                ),
            )
        }

        val report = views.publish()
        return Json.parseToJsonElement(report.toReportJson())
    }

    /**
     * Parses [file] as the JSON-array declaration-history format fpml.5-D7
     * defines: an array of `{"observedAt", "declaration": {"weights",
     * "monthlyCapHours", "window"}}` objects, in the shape the README's
     * "Running against the socaity replay script" section documents. Order in
     * the file is irrelevant — [ReferenceReport] and [compute] both sort by
     * `observedAt` themselves.
     */
    fun parseHistory(file: Path): List<DeclarationSpec> {
        val text = Files.readString(file)
        val entries = Json.decodeFromString(ListSerializer(HistoryEntryDto.serializer()), text)
        return entries.map { it.toSpec() }
    }
}

/**
 * The declaration-history file's per-event shape (fpml.5-D7). Deliberately a
 * separate, private DTO rather than a reuse of `restart/DeclarationHistoryJournal`'s
 * (which is `private` to its own file and lives in `src/main`, one layer this
 * test-only parser has no reason to depend on) or `http/AllocatorJson.kt`'s
 * `DeclarationDto` (the HTTP exchange's own type) — the field names and
 * nesting match both by design (fpml.5-D7 pins them to the exchange shape),
 * and that agreement is exactly what a divergence in this harness would catch.
 */
@Serializable
private data class HistoryEntryDto(
    val observedAt: String,
    val declaration: HistoryDeclarationDto,
)

@Serializable
private data class HistoryDeclarationDto(
    val weights: Map<String, Double>,
    val monthlyCapHours: Double,
    val window: String? = null,
)

private fun HistoryEntryDto.toSpec(): DeclarationSpec = DeclarationSpec(
    observedAt = Instant.parse(observedAt),
    weights = declaration.weights,
    monthlyCapHours = declaration.monthlyCapHours,
    window = declaration.window,
)
