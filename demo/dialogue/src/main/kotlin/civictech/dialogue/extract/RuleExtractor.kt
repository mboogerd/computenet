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
 *   being supported (before), `polarity` = `"SUPPORT"`. It ALSO yields an
 *   [ExtractedClaim] for each of the two endpoint substrings — see
 *   "Why the endpoint claims" below.
 * - A segment opening with a disagreement marker ("no, ", "i disagree",
 *   "that's wrong") yields no relation beyond its own claim: this extractor
 *   sees one segment at a time and deliberately does not invent
 *   cross-segment state to resolve what is being disagreed with.
 *
 * ### Why the endpoint claims (computenet-xwl0, AGO1 F3 follow-up)
 *
 * `[civictech.dialogue.mint.claimKey]` only trims, collapses whitespace and
 * lowercases (2aw.F3-D1) — it does not, and per that seam's own KDoc must
 * not, understand that a relation's `sourceText`/`targetText` are substrings
 * of the segment that produced them. Emitting only the whole-segment claim
 * ("A because B") therefore left the relation's endpoints — `claimKey("A")`
 * and `claimKey("B")` — permanently unminted: F3's pending/resolvable
 * semijoin split (`DialoguePipeline` stages 5d/5e) held every such candidate
 * PENDING forever, so `RuleExtractor` minted claims and provenance but ZERO
 * canonical relations. Since the pipeline's differentiated output — the
 * whole reason an argument map is worth building — is the relations between
 * claims, a demo extractor that can never produce one demonstrates only
 * half the system.
 *
 * The fix mints the two endpoint substrings ("A", "B") as their own
 * `ExtractedClaim`s **in addition to** the whole-segment claim, so their
 * keys land in `claimKeys` and the relation resolves with no change to
 * `claimKey` itself (which AGO3 replaces wholesale, not this extractor).
 * This is a deliberate, measured trade, not a free lunch: a "because"
 * segment now mints **three** canonical claims (the whole sentence, its
 * conclusion, its reason) where a human reading the transcript would count
 * two propositions — nobody asserted "A because B" as a standalone claim
 * distinct from asserting A and B. Verified before choosing this over
 * leaving `RuleExtractor` claim-only: no F3 test text contains "because",
 * so this changes no existing F3 assertion, the endpoint claims share the
 * segment's own `utteranceId` so provenance is not fabricated, and the
 * stance leg is untouched (`RuleExtractor` never emits an `ExtractedStance`).
 * The remaining cost — the extra whole-sentence claim sitting alongside its
 * own decomposition on the map — is accepted here rather than removed by
 * dropping the whole-segment claim for "because" segments, because that
 * would make a "because" segment's claim-output shape diverge from every
 * other segment's ("every segment yields a claim of its trimmed text") for
 * a demo heuristic that is explicitly meant to stay tiny and replaceable.
 * Recorded in `doc/demo-findings.md` F-14.
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

        // The endpoint claims (see the class KDoc's "Why the endpoint
        // claims"): minted so the relation below has a canonical claim key
        // to resolve against on BOTH sides, in addition to — not instead
        // of — the whole-segment claim above.
        val targetClaim = ExtractedClaim(
            text = target,
            speaker = segment.speaker,
            utteranceId = segment.utteranceId,
        )
        val sourceClaim = ExtractedClaim(
            text = source,
            speaker = segment.speaker,
            utteranceId = segment.utteranceId,
        )
        val relation = ExtractedRelation(
            sourceText = source,
            targetText = target,
            polarity = "SUPPORT",
            utteranceId = segment.utteranceId,
        )
        return listOf(claim, targetClaim, sourceClaim, relation)
    }
}
