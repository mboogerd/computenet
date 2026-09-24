package civictech.timetravel.cli

import civictech.cell.CellRef
import civictech.timetravel.diff.CANONICAL_JSON
import civictech.timetravel.diff.FidelityDto
import civictech.timetravel.diff.StateView
import civictech.timetravel.diff.Verdict
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.BaselineDischargeRecord
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.FrontierRecord
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalRecord
import civictech.timetravel.journal.JournalSummary
import civictech.timetravel.journal.OutletWaveRecord
import civictech.timetravel.reconstruct.CellReconstruction
import civictech.timetravel.reconstruct.Reconstruction
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

// The CLI's `--json` DTOs for `inspect` and `reconstruct` (computenet-3qkx1 D8, `[TTD1-48]`):
// `JournalReading` and `Reconstruction` are not `@Serializable`, so each is mirrored by a DTO that
// is a total function of the API object, under si0tl-D11's discipline — only String/Int/Long/
// Boolean/enum/nullable/List/nested DTO; never `Map`, `UUID`, `CellRef`, `Fidelity` or
// `CellStateView`. `diff` needs none: `RunDiffReport`/`MultiJournalDiffReport` are already DTOs.

/** A cell ref as every report renders it: its id's string (the diff reports' `cellRef` form). */
private fun CellRef.render(): String = id.toString()

private fun Collection<Reason>.sortedReasons(): List<Reason> = sortedBy { it.name }

private fun List<Reason>.bracketed(): String = joinToString(",", "[", "]") { it.name }

/** The epic §9.2 pessimism line (same wording as the diff text renderer, si0tl-D13). */
private const val ALLOW_LIST_LINE = "UNKNOWN_DETERMINISM: the allow-list errs pessimistic"

private fun FidelityDto.isAllowListDegraded(): Boolean =
    verdict == Verdict.DEGRADED && Reason.UNKNOWN_DETERMINISM in reasons

/**
 * One journal record (D8). [kind] is the record class's simple name; [cellRef]/[portName] are
 * `null` where the record names none; [type]/[contractId]/[methodId]/[methodName] are frames' only;
 * [sourceId]/[counter] are a frame's wave coordinate, `null` without a context; [label] is
 * [RunTimeline.labels]; [reasons] sorted by name.
 */
@Serializable
data class RecordDto(
    val index: Int,
    val kind: String,
    val cellRef: String?,
    val portName: String?,
    val type: String?,
    val contractId: Long?,
    val methodId: Long?,
    val sourceId: String?,
    val counter: Long?,
    val methodName: String?,
    val label: String,
    val reasons: List<Reason>,
) {
    companion object {
        fun of(record: JournalRecord, timeline: RunTimeline): RecordDto {
            val frame = record as? FrameRecord
            val wave = frame?.context?.timestamp
            val (cellRef, portName) = when (record) {
                is FrameRecord -> record.cellRef to record.portName
                is FrontierRecord -> record.cellRef to record.portName
                is OutletWaveRecord -> record.cellRef to record.portName
                is BaselineDischargeRecord -> record.cellRef to record.portName
                else -> null to null
            }
            return RecordDto(
                index = record.index,
                kind = record.javaClass.simpleName,
                cellRef = cellRef?.render(),
                portName = portName,
                type = frame?.type?.name,
                contractId = frame?.contractId,
                methodId = frame?.methodId,
                sourceId = wave?.sourceId?.toString(),
                counter = wave?.counter,
                methodName = frame?.hydrated?.methodName,
                label = timeline.labels(record.index),
                reasons = record.reasons.sortedReasons(),
            )
        }
    }
}

/**
 * One journal of a reading (D8): [journal] is `File(journalId).name`, never the host path
 * (si0tl-D12); [tearLastIntactIndex]/[tearTrailingBytes] mirror `JournalFileScan.Tear`, both
 * `null` when intact.
 */
@Serializable
data class JournalDto(
    val journal: String,
    val declaredFormatVersion: Int?,
    val expectedFormatVersion: Int,
    val recordCount: Int,
    val tearLastIntactIndex: Int?,
    val tearTrailingBytes: Long?,
    val refusal: String?,
    val reasons: List<Reason>,
    val records: List<RecordDto>,
) {
    companion object {
        fun of(summary: JournalSummary, timeline: RunTimeline): JournalDto = JournalDto(
            journal = File(summary.journalId).name,
            declaredFormatVersion = summary.declaredFormatVersion,
            expectedFormatVersion = summary.expectedFormatVersion,
            recordCount = summary.recordCount,
            tearLastIntactIndex = summary.tear?.lastIntactIndex,
            tearTrailingBytes = summary.tear?.trailingBytes,
            refusal = summary.refusal,
            reasons = summary.reasons.sortedReasons(),
            records = timeline.positions.map { RecordDto.of(it.record, timeline) },
        )
    }
}

/** `inspect`'s report (D8): every journal of the reading, in reading order, and the reading's reasons. */
@Serializable
data class InspectReport(val journals: List<JournalDto>, val reasons: List<Reason>) {

    fun toJson(): String = CANONICAL_JSON.encodeToString(this)

    /**
     * D8 tokens: `journal <name>: <n> records`, its refusal, tear and reasons when present, then
     * one line per record — its label and its reasons.
     */
    fun toText(): String = buildString {
        for (journal in journals) {
            appendLine("journal ${journal.journal}: ${journal.recordCount} records")
            journal.refusal?.let { appendLine("  refused: $it") }
            if (journal.tearLastIntactIndex != null || journal.tearTrailingBytes != null) {
                appendLine(
                    "  torn: last intact record #${journal.tearLastIntactIndex}, " +
                        "${journal.tearTrailingBytes} trailing bytes",
                )
            }
            if (journal.reasons.isNotEmpty()) appendLine("  reasons: ${journal.reasons.bracketed()}")
            for (record in journal.records) {
                val reasons = if (record.reasons.isEmpty()) "" else " ${record.reasons.bracketed()}"
                appendLine("  ${record.label}$reasons")
            }
        }
    }.trimEnd('\n')

    companion object {
        fun of(reading: JournalReading): InspectReport {
            val timelines = RunTimeline.of(reading)
            return InspectReport(
                journals = reading.journals.map { JournalDto.of(it, timelines.getValue(it.journalId)) },
                reasons = reading.reasons.sortedReasons(),
            )
        }
    }
}

/** One reconstructed cell (D8): [view] is `null` iff the cell is `Unreconstructible`. */
@Serializable
data class CellDto(val cellRef: String, val cellClass: String?, val fidelity: FidelityDto, val view: StateView?) {
    companion object {
        fun of(ref: CellRef, cell: CellReconstruction): CellDto = CellDto(
            cellRef = ref.render(),
            cellClass = cell.cellClass,
            fidelity = FidelityDto.of(cell.fidelity),
            view = (cell as? CellReconstruction.Reconstructed)?.let { StateView.of(it.view) },
        )
    }
}

/**
 * `reconstruct`'s report (D8): [requested] is `index:<n>` or `cut:<uuid>=<counter>,…` (sorted by
 * uuid string); [cells] sorted by `cellRef`; [details] each `ReconstructionDetail.toString()`,
 * sorted.
 */
@Serializable
data class ReconstructReport(
    val journal: String,
    val requested: String,
    val prefixEnd: Int,
    val anchor: Int,
    val replayedRecords: Int,
    val run: FidelityDto,
    val cells: List<CellDto>,
    val details: List<String>,
) {

    fun toJson(): String = CANONICAL_JSON.encodeToString(this)

    /**
     * D8 tokens: `position #<prefixEnd-1> (anchor <a>, replayed <r>)`, `run: <VERDICT> [<reasons>]`,
     * one `<cellRef>: <cellClass> <VERDICT> [<reasons>] <summary or ->` line per cell, one
     * `detail:` line per detail, and — iff any fidelity is `DEGRADED` with `UNKNOWN_DETERMINISM` —
     * exactly one line naming both `UNKNOWN_DETERMINISM` and the allow-list.
     */
    fun toText(): String = buildString {
        appendLine("journal $journal at $requested")
        appendLine("position #${prefixEnd - 1} (anchor $anchor, replayed $replayedRecords)")
        appendLine("run: ${run.verdict} ${run.reasons.bracketed()}")
        for (cell in cells) {
            appendLine(
                "${cell.cellRef}: ${cell.cellClass ?: "?"} ${cell.fidelity.verdict} " +
                    "${cell.fidelity.reasons.bracketed()} ${cell.view?.summary ?: "-"}",
            )
        }
        for (detail in details) appendLine("detail: $detail")
        if (run.isAllowListDegraded() || cells.any { it.fidelity.isAllowListDegraded() }) appendLine(ALLOW_LIST_LINE)
    }.trimEnd('\n')

    companion object {
        fun of(reconstruction: Reconstruction, journal: String): ReconstructReport = ReconstructReport(
            journal = journal,
            requested = render(reconstruction.position.requested),
            prefixEnd = reconstruction.position.prefixEnd,
            anchor = reconstruction.position.anchor,
            replayedRecords = reconstruction.position.replayedRecords,
            run = FidelityDto.of(reconstruction.run),
            cells = reconstruction.cells.entries
                .map { (ref, cell) -> CellDto.of(ref, cell) }
                .sortedBy { it.cellRef },
            details = reconstruction.details.map { it.toString() }.sorted(),
        )

        private fun render(position: Position): String = when (position) {
            is Position.Index -> "index:${position.index}"
            is Position.Cut -> "cut:" + position.perSource.entries
                .map { (source, counter) -> source.toString() to counter }
                .sortedBy { it.first }
                .joinToString(",") { (source, counter) -> "$source=$counter" }
        }
    }
}
