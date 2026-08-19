package civictech.bench

/**
 * Thrown when [Findings.entry] refuses to render a markdown entry (`[BEN1-25]`,
 * `[BEN1-30]`..`[BEN1-32]`).
 *
 * A single exception type covers every refusal this writer makes — an
 * [Reportability.Unreportable] result, an incomplete entry (missing date, subject,
 * results table, or per-row labels), a cited gap whose id is blank, or a cited gap
 * whose trigger statement does not state exactly one of FIRES / RETIRES /
 * INCONCLUSIVE. [message] always names what was refused and why, so a caller reading a
 * thrown exception (rather than stepping through the writer's source) still learns
 * which fact was missing or which result was rejected.
 */
class FindingsRefusalException(message: String) : IllegalArgumentException(message)

/**
 * What a findings entry says about a cited gap's trigger question — or that it answers
 * none at all (`[BEN1-31]`, `[BEN1-32]`).
 *
 * [Findings.entry] defaults this parameter to [None], so an entry that answers no
 * trigger question is the entry a caller gets by simply not mentioning one — matching
 * `[BEN1-32]`'s requirement that such an entry be emitted only explicitly marked
 * incomplete, never silently as a full finding.
 */
sealed interface TriggerClaim {

    /**
     * The entry cites no gap and answers no trigger question at all (`[BEN1-32]`).
     * [Findings.entry] renders this as an explicit "MARKED INCOMPLETE" trigger line —
     * never as a plain finding.
     */
    object None : TriggerClaim

    /**
     * The entry cites [gapId] (e.g. `"G-21 phase 3"`) and states [statement] as its
     * verdict on that gap's trigger question.
     *
     * [statement] is free text — a full sentence, e.g. `"FIRES, because retract
     * throughput regressed 40%."` — and [Findings.entry] does not trust the caller to
     * have actually stated a verdict in it: it independently counts how many of the
     * three verdict words (FIRES, RETIRES, INCONCLUSIVE) occur in [statement] as whole
     * words, and refuses unless exactly one does (`[BEN1-31]`).
     *
     * [gapId] is likewise not trusted to be meaningful: [Findings.entry] refuses a
     * blank (empty or all-whitespace) [gapId] rather than rendering an entry that
     * cites nothing while presenting itself as a finding (`[BEN1-31]`). This checks
     * only blankness, not the repository's `G-<digits>` shape convention (e.g.
     * `"G-21 phase 3"`) — see this class's own examples above; a deliberate choice to
     * keep this writer decoupled from a gap-id convention it does not otherwise know
     * about, rather than a stricter shape check.
     */
    data class Cited(val gapId: String, val statement: String) : TriggerClaim
}

/**
 * Renders `doc/bench/findings.md` entries following the epic's template, and refuses to
 * render one that would misrepresent a measurement (`[BEN1-25]`, `[BEN1-30]`..`[BEN1-32]`).
 *
 * This object VALIDATES and RENDERS entry text; it never touches `doc/bench/findings.md`
 * or any other file. Entries are hand-appended to that file by whoever ran the
 * measurement — [entry] hands back a markdown string, or throws
 * [FindingsRefusalException] naming why it would not.
 */
object Findings {

    private val VERDICT_WORDS = listOf("FIRES", "RETIRES", "INCONCLUSIVE")

    /**
     * Renders one findings entry, or refuses.
     *
     * @param date the entry's date, e.g. `"2026-08-18"`. Blank or missing is refused
     *   (`[BEN1-30]`).
     * @param subject what was measured, filling the template's `<what was measured>`.
     *   Blank or missing is refused (`[BEN1-30]`).
     * @param results the entry's results, as a [FindingsTable] — the ONLY way results
     *   enter an entry. There is no overload taking a raw `List<BenchResult>`: a table
     *   that mixes [Drive.SIM] and [Drive.REAL] results, or whose results do not all
     *   share one [RunEnvironment], cannot be constructed in the first place
     *   (`[BEN1-27]`; the environment case mirrors it for the same reason — this
     *   entry renders a single environment line taken from `results.first().env`, so a
     *   table that let a later result carry a different environment would have that
     *   result silently reported under the first result's JVM/harness/JMH config), so
     *   this writer inherits both refusals rather than re-implementing them. Every
     *   result in [results] must be
     *   [Reportability.Reportable] — the first [Reportability.Unreportable] one found
     *   refuses the whole entry, and the refusal message names it (`[BEN1-25]`).
     *   [FindingsTable.labels] must be non-`null` — the results table's per-row
     *   subject/label, distinct from [BenchResult.unit], that makes an insert row and a
     *   retract row of the same operator distinguishable and a before/after pair
     *   labellable; a table missing it is refused as incomplete (`[BEN1-30]`), the same
     *   as a missing date or subject. Missing (`null`) [results] is likewise refused as
     *   an incomplete entry (`[BEN1-30]`).
     * @param trigger what the entry says about a cited gap's trigger question, or
     *   [TriggerClaim.None] if it answers none. Defaults to [TriggerClaim.None].
     * @throws FindingsRefusalException naming the refused result or the missing/malformed
     *   field.
     */
    fun entry(
        date: String?,
        subject: String?,
        results: FindingsTable?,
        trigger: TriggerClaim = TriggerClaim.None,
    ): String {
        if (date.isNullOrBlank()) {
            throw FindingsRefusalException(
                "Findings entry refused: incomplete — date is missing or blank"
            )
        }
        if (subject.isNullOrBlank()) {
            throw FindingsRefusalException(
                "Findings entry refused: incomplete — subject (what was measured) is " +
                    "missing or blank"
            )
        }
        if (results == null) {
            throw FindingsRefusalException(
                "Findings entry refused: incomplete — results table is missing"
            )
        }
        if (results.labels == null) {
            throw FindingsRefusalException(
                "Findings entry refused: incomplete — results table has no per-row labels; " +
                    "each row must carry a caller-supplied subject/label distinct from its " +
                    "unit (construct FindingsTable with labels = ...)"
            )
        }

        val unreportable = results.results.firstOrNull { classify(it) == Reportability.Unreportable }
        if (unreportable != null) {
            throw FindingsRefusalException(
                "Findings entry refused: result is Unreportable (relative dispersion " +
                    "${unreportable.relativeDispersion} exceeds NOISE_FLOOR $NOISE_FLOOR) " +
                    "- value=${unreportable.value}${unreportable.unit} " +
                    "dispersion=${unreportable.dispersion}${unreportable.unit} " +
                    "drive=${unreportable.drive} env=${unreportable.env}"
            )
        }

        // Fail fast on a malformed trigger claim before spending any effort rendering
        // the rest of the template.
        val triggerLine = renderTriggerLine(trigger)

        val env = results.results.first().env
        return buildString {
            appendLine("## $date — $subject")
            appendLine(
                "Harness: ${env.harnessCommitSha} · JVM ${env.jvmVendor}/${env.jvmVersion} " +
                    "· heap ${env.heapSettings} · ${env.cpuModel}, ${env.coreCount} cores, " +
                    env.os
            )
            appendLine(
                "JMH: mode=${env.jmhMode} forks=${env.forkCount} " +
                    "warmup=${env.warmupIterations} iters=${env.measurementIterations} " +
                    "· drive=${results.drive}"
            )
            appendLine(renderTable(results))
            append(triggerLine)
        }
    }

    private fun renderTriggerLine(trigger: TriggerClaim): String = when (trigger) {
        is TriggerClaim.None ->
            "Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding"
        is TriggerClaim.Cited -> {
            if (trigger.gapId.isBlank()) {
                throw FindingsRefusalException(
                    "Findings entry refused: cited trigger's gapId is missing or blank"
                )
            }
            val hits = VERDICT_WORDS.count { word -> containsWholeWord(trigger.statement, word) }
            if (hits != 1) {
                throw FindingsRefusalException(
                    "Findings entry refused: trigger citing ${trigger.gapId} must state " +
                        "exactly one of FIRES / RETIRES / INCONCLUSIVE, found $hits in " +
                        "statement '${trigger.statement}'"
                )
            }
            "Trigger: ${trigger.gapId} — ${trigger.statement}"
        }
    }

    private fun containsWholeWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(text)

    /**
     * Renders [results] with a caller-supplied per-row subject in column 1 and each
     * row's own [BenchResult.unit] carried alongside its value in column 2 — never a
     * unit hard-coded in the header (`[BEN1-30]`, the reviewer finding on
     * `computenet-x9e.3`). `entry` has already refused a `null` [FindingsTable.labels]
     * before this is called, so the `!!` below never fires in practice; it documents
     * that invariant rather than re-deriving it.
     */
    private fun renderTable(results: FindingsTable): String {
        val labels = results.labels!!
        val header = "| subject | value | notes |"
        val separator = "| --- | --- | --- |"
        val rows = results.results.zip(labels).joinToString(separator = "\n") { (result, label) ->
            "| $label | ${result.value} ± ${result.dispersion} ${result.unit} | |"
        }
        return listOf(header, separator, rows).joinToString(separator = "\n")
    }
}
