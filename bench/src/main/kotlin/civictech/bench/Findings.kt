package civictech.bench

/**
 * Thrown when [Findings.entry] refuses to render a markdown entry (`[BEN1-25]`,
 * `[BEN1-30]`..`[BEN1-32]`).
 *
 * A single exception type covers every refusal this writer makes — a [ComparisonClaim]
 * whose effect does not exceed the combined error bars of the rows it names, an
 * incomplete entry (missing date, subject, results table, or per-row labels), a cited
 * gap whose id is blank, or a cited gap whose trigger statement does not state exactly
 * one of FIRES / RETIRES / INCONCLUSIVE. [message] always names what was refused and
 * why, so a caller reading a thrown exception (rather than stepping through the
 * writer's source) still learns which fact was missing or which claim was rejected.
 *
 * **A dispersed standalone row is no longer among them** (`computenet-785b`). Until
 * 2026-08-22 this writer refused any entry containing a result whose relative dispersion
 * exceeded [NOISE_FLOOR]; that gate is gone, because it was refusing 66 of 72 throughput
 * rows and all 10 fan-out rows for being noisier than the cheapest possible benchmark on
 * an idle host. A number is now rendered with its error bar attached and the reader
 * discounts it themselves. What is refused instead is the claim that two such numbers
 * *differ*, when the difference is inside their combined error bars — see
 * [ComparisonClaim] and [resolveEffect].
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
 * A claim that two rows of an entry's table DIFFER, and the reading drawn from that
 * difference (`computenet-785b`).
 *
 * This is the unit the reportability criterion now applies to. A standalone row is
 * always rendered — `value ± dispersion unit` states its own precision — but a claim
 * that one row is faster, larger or worse than another asserts something the two error
 * bars may not support, and [Findings.entry] refuses such a claim rather than rendering
 * it: `|left.value - right.value|` must exceed [combinedError] of the two rows, scaled
 * by [COMBINED_ERROR_MARGIN]. See [resolveEffect].
 *
 * @param leftLabel the [FindingsTable.labels] entry naming one of the two rows. Must
 *   appear in the table exactly once, and must differ from [rightLabel] — a row compared
 *   with itself has an effect of zero and is refused by the criterion anyway, but is
 *   refused by name here so the message says what the caller actually did.
 * @param rightLabel the label naming the other row.
 * @param statement the reading drawn from the difference, free text — e.g. `"insert
 *   outruns retract by ~3x on this graph"`. Unlike [TriggerClaim.Cited.statement] no
 *   vocabulary is imposed on it; what is checked is the arithmetic the claim rests on,
 *   not its wording. Blank is refused.
 */
data class ComparisonClaim(
    val leftLabel: String,
    val rightLabel: String,
    val statement: String,
)

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
     *   this writer inherits both refusals rather than re-implementing them. No result
     *   in [results] is refused for its dispersion: every row is rendered with its own
     *   error bar attached, and the criterion applies to [comparisons] instead
     *   (`computenet-785b`, amending `[BEN1-25]`'s reach).
     *   [FindingsTable.labels] must be non-`null` — the results table's per-row
     *   subject/label, distinct from [BenchResult.unit], that makes an insert row and a
     *   retract row of the same operator distinguishable and a before/after pair
     *   labellable; a table missing it is refused as incomplete (`[BEN1-30]`), the same
     *   as a missing date or subject. Missing (`null`) [results] is likewise refused as
     *   an incomplete entry (`[BEN1-30]`).
     * @param trigger what the entry says about a cited gap's trigger question, or
     *   [TriggerClaim.None] if it answers none. Defaults to [TriggerClaim.None].
     * @param comparisons claims that two rows of [results] differ. Each is checked
     *   against the combined error bars of the two rows it names and refused when the
     *   claimed effect does not exceed them (`computenet-785b`). Defaults to empty — an
     *   entry that draws no comparison makes no claim for this criterion to check, which
     *   is the ordinary case for a sweep that only reports numbers.
     * @throws FindingsRefusalException naming the refused claim or the missing/malformed
     *   field.
     */
    fun entry(
        date: String?,
        subject: String?,
        results: FindingsTable?,
        trigger: TriggerClaim = TriggerClaim.None,
        comparisons: List<ComparisonClaim> = emptyList(),
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

        // Fail fast on a malformed trigger claim or an unsupported comparison before
        // spending any effort rendering the rest of the template.
        val triggerLine = renderTriggerLine(trigger)
        val comparisonLines = renderComparisons(results, comparisons)

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
            if (comparisonLines != null) {
                appendLine(comparisonLines)
            }
            append(triggerLine)
        }
    }

    /**
     * Renders the entry's `Comparisons:` block, or `null` when no comparison is claimed.
     *
     * Every claim is checked before any is rendered, so an entry with one unsupported
     * claim among several is refused whole rather than emitted with the bad one dropped —
     * the same posture [FindingsTable] takes towards a mixed table.
     */
    private fun renderComparisons(
        results: FindingsTable,
        comparisons: List<ComparisonClaim>,
    ): String? {
        if (comparisons.isEmpty()) {
            return null
        }
        val labels = results.labels!!
        val byLabel = labels.zip(results.results).toMap()
        val rendered = comparisons.map { claim ->
            if (claim.statement.isBlank()) {
                throw FindingsRefusalException(
                    "Findings entry refused: comparison of '${claim.leftLabel}' with " +
                        "'${claim.rightLabel}' states nothing — statement is blank"
                )
            }
            if (claim.leftLabel == claim.rightLabel) {
                throw FindingsRefusalException(
                    "Findings entry refused: comparison names the same row on both sides " +
                        "('${claim.leftLabel}'); a row does not differ from itself"
                )
            }
            val left = byLabel[claim.leftLabel] ?: throw FindingsRefusalException(
                "Findings entry refused: comparison names '${claim.leftLabel}', which is " +
                    "not a row of this table; rows are ${labels.sorted()}"
            )
            val right = byLabel[claim.rightLabel] ?: throw FindingsRefusalException(
                "Findings entry refused: comparison names '${claim.rightLabel}', which is " +
                    "not a row of this table; rows are ${labels.sorted()}"
            )
            if (left.unit != right.unit) {
                throw FindingsRefusalException(
                    "Findings entry refused: comparison of '${claim.leftLabel}' " +
                        "(${left.unit}) with '${claim.rightLabel}' (${right.unit}) " +
                        "subtracts across units, which yields a number with no meaning"
                )
            }
            val effect = kotlin.math.abs(left.value - right.value)
            val bar = COMBINED_ERROR_MARGIN * combinedError(left, right)
            if (resolveEffect(left, right) == EffectResolution.Unresolved) {
                throw FindingsRefusalException(
                    "Findings entry refused: comparison of '${claim.leftLabel}' " +
                        "(${left.value} ± ${left.dispersion} ${left.unit}) with " +
                        "'${claim.rightLabel}' (${right.value} ± ${right.dispersion} " +
                        "${right.unit}) claims an effect of $effect ${left.unit}, which " +
                        "does not exceed the combined 99.9% error bars $bar ${left.unit} " +
                        "(margin ${COMBINED_ERROR_MARGIN}x). These measurements do not " +
                        "establish that the two rows differ — not even the sign. Report " +
                        "each row on its own with its error bar, or measure more"
                )
            }
            "- ${claim.leftLabel} vs ${claim.rightLabel}: |Δ| = $effect ${left.unit} > " +
                "combined 99.9% error $bar ${left.unit} — ${claim.statement}"
        }
        return listOf("Comparisons (effect vs combined error bars):").plus(rendered)
            .joinToString(separator = "\n")
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
