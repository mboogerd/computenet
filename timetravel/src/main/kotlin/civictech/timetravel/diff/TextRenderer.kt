package civictech.timetravel.diff

import civictech.timetravel.fidelity.Reason

/**
 * `toText()`'s renderer for [RunDiffReport] and [MultiJournalDiffReport] (`si0tl-D13`). The tokens
 * named there are the contract — a caller may match on a substring — so wording around them may
 * change, but the tokens themselves may not disappear: journal names; `alignment: <MODE>` plus the
 * note line when present; `aligned prefix: <n>`; a divergence line naming the class and both
 * labels; one line per [CellDelta] naming the ref and both summaries; under
 * [StateDiff.Unavailable], one line per entry naming the run side, its reasons and its message; and
 * — iff any [FidelityDto] in the report is [Verdict.DEGRADED] with [Reason.UNKNOWN_DETERMINISM] —
 * exactly one line naming both, stating the allow-list's pessimism (epic §9.2).
 */
internal object TextRenderer {

    fun render(report: RunDiffReport): String = buildString {
        appendLine("journal A: ${report.journalA}")
        appendLine("journal B: ${report.journalB}")
        appendLine("alignment: ${report.alignment}")
        report.alignmentNote?.let { appendLine(it) }
        appendLine("aligned prefix: ${report.alignedPrefix}")
        report.divergence?.let { d ->
            appendLine("divergence: ${d.kind} ${d.labelA ?: "-"} -> ${d.labelB ?: "-"}")
        }
        when (val stateDiff = report.stateDiff) {
            is StateDiff.Compared -> for (delta in stateDiff.deltas) {
                appendLine("${delta.cellRef}: ${delta.viewA?.summary ?: "-"} -> ${delta.viewB?.summary ?: "-"}")
            }

            is StateDiff.Unavailable -> for (unavailable in stateDiff.runs) {
                val reasons = unavailable.reasons.joinToString(",") { it.name }
                appendLine("state diff unavailable for run ${unavailable.run}: $reasons (${unavailable.message})")
            }
        }
        if (hasAllowListDegradation(report)) {
            appendLine("UNKNOWN_DETERMINISM: the allow-list errs pessimistic")
        }
    }.trimEnd('\n')

    fun render(report: MultiJournalDiffReport): String = buildString {
        for (journalDiff in report.journals) {
            appendLine("journal ${journalDiff.journal}: ${journalDiff.presence}")
            journalDiff.report?.let { appendLine(render(it)) }
        }
    }.trimEnd('\n')

    /** Whether any [FidelityDto] reachable from [report] is [Verdict.DEGRADED] with [Reason.UNKNOWN_DETERMINISM]. */
    private fun hasAllowListDegradation(report: RunDiffReport): Boolean {
        val fidelities = mutableListOf<FidelityDto>()
        val stateDiff = report.stateDiff
        if (stateDiff is StateDiff.Compared) {
            fidelities += stateDiff.runA
            fidelities += stateDiff.runB
            for (delta in stateDiff.deltas) {
                delta.fidelityA?.let { fidelities += it }
                delta.fidelityB?.let { fidelities += it }
            }
        }
        return fidelities.any { it.verdict == Verdict.DEGRADED && Reason.UNKNOWN_DETERMINISM in it.reasons }
    }
}
