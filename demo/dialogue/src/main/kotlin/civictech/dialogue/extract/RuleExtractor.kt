package civictech.dialogue.extract

import civictech.dialogue.Segment

/**
 * The pure, zero-dependency demo [Extractor] (epic computenet-2aw DESIGN
 * 2aw-D4). No randomness, no clock, no I/O — `[AGO1-EXTR-01]`'s extractor
 * half. A tiny, deliberately replaceable heuristic:
 *
 * - A segment with no " because " (case-insensitive) match yields a single
 *   [ExtractedClaim] of its trimmed text.
 * - A segment containing " because " whose split succeeds (both sides
 *   non-blank) yields **only** the two endpoint claims — see "Why only the
 *   endpoint claims" below — plus an [ExtractedRelation] where the text
 *   after "because" supports the text before it: `sourceText` = the reason
 *   (after), `targetText` = the claim being supported (before), `polarity` =
 *   `"SUPPORT"`. It does **not** also mint a claim of the whole segment.
 * - A segment opening with a disagreement marker ("no, ", "i disagree",
 *   "that's wrong") yields no relation beyond its own claim: this extractor
 *   sees one segment at a time and deliberately does not invent
 *   cross-segment state to resolve what is being disagreed with.
 *
 * ### Why only the endpoint claims (computenet-xwl0 then computenet-i6hp)
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
 * computenet-xwl0 fixed this by minting the two endpoint substrings ("A",
 * "B") as their own `ExtractedClaim`s **in addition to** the whole-segment
 * claim, so their keys land in `claimKeys` and the relation resolves with no
 * change to `claimKey` itself (which AGO3 replaces wholesale, not this
 * extractor). That shipped a real, admitted cost: a "because" segment then
 * minted **three** canonical claims (the whole sentence, its conclusion, its
 * reason) where a human reading the transcript would count two propositions.
 * At the time (F3 review, PR #635) `DialogueApp.kt` wired no extractor, so
 * the cost was evaluated only on paper.
 *
 * computenet-i6hp evaluated it on an actual rendered argument map, once F4
 * (the applier) and F5 (the HTTP surface) existed to build one: driving
 * `RuleExtractor` through `DialoguePipeline` over the checked-in
 * `bs20-because.jsonl` fixture (six utterances, four "because" segments) and
 * inspecting `AgoraService.graph()` showed **4 of the resulting 13 claim
 * nodes were the whole-segment claim, and every one of those 4 was an
 * unconnected orphan** — the map's genuinely freestanding claims (segments
 * with no "because") numbered only 2. The whole-segment claim was not a rare
 * edge case on this fixture; it was the largest single category of node on
 * the map, and every instance of it carried zero relation information. That
 * measurement is what "every segment yields a claim of its trimmed text"
 * was costing on a real map, not a hypothetical one, so the decision changed:
 * for a segment whose because-split succeeds, `RuleExtractor` now emits
 * *only* the two endpoint claims and the relation — dropping the
 * whole-segment claim — at the price of a "because" segment's claim-output
 * shape diverging from every other segment's. The endpoint claims still
 * carry the segment's own `utteranceId`, so provenance is not fabricated,
 * and the stance leg is unaffected (`RuleExtractor` never emits an
 * `ExtractedStance`). Recorded in `doc/demo-findings.md` F-14.
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

        // The endpoint claims (see the class KDoc's "Why only the endpoint
        // claims"): minted so the relation below has a canonical claim key
        // to resolve against on BOTH sides, INSTEAD OF the whole-segment
        // claim built above — `claim` is deliberately not included in the
        // returned list once the because-split succeeds (computenet-i6hp).
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
        return listOf(targetClaim, sourceClaim, relation)
    }
}
