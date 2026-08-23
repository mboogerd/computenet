package civictech.dialogue.extract

import civictech.dialogue.Segment

/**
 * The pure, zero-dependency demo [Extractor] (epic computenet-2aw DESIGN
 * 2aw-D4). No randomness, no clock, no I/O — `[AGO1-EXTR-01]`'s extractor
 * half. A tiny, deliberately replaceable heuristic:
 *
 * - Every segment yields an [ExtractedClaim] of its trimmed text.
 * - A segment containing " because " (case-insensitive) additionally yields
 *   an [ExtractedRelation] where the text after "because" supports the text
 *   before it: `sourceText` = the reason (after), `targetText` = the claim
 *   being supported (before), `polarity` = `"SUPPORT"`.
 * - A segment opening with a disagreement marker ("no, ", "i disagree",
 *   "that's wrong") yields no relation beyond its own claim: this extractor
 *   sees one segment at a time and deliberately does not invent
 *   cross-segment state to resolve what is being disagreed with.
 */
object RuleExtractor : Extractor {

    private val becauseRegex = Regex(" because ", RegexOption.IGNORE_CASE)

    override fun extract(segment: Segment): List<ExtractedItem> {
        val trimmedText = segment.text.trim()
        val claim = ExtractedClaim(
            text = trimmedText,
            speaker = segment.speaker,
            utteranceId = segment.utteranceId,
        )

        val becauseMatch = becauseRegex.find(trimmedText)
        if (becauseMatch == null) {
            return listOf(claim)
        }

        val target = trimmedText.substring(0, becauseMatch.range.first).trim()
        val source = trimmedText.substring(becauseMatch.range.last + 1).trim()
        if (target.isEmpty() || source.isEmpty()) {
            return listOf(claim)
        }

        val relation = ExtractedRelation(
            sourceText = source,
            targetText = target,
            polarity = "SUPPORT",
            utteranceId = segment.utteranceId,
        )
        return listOf(claim, relation)
    }
}
