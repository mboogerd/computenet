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
 *
 * ### Trailing full stops, and what is deliberately NOT normalized (computenet-9bip)
 *
 * [civictech.dialogue.Segmentation] splits on `(?<=[.!?])\s+`, a lookbehind,
 * so the sentence terminator stays attached to the sentence. A "because"
 * split therefore hands the endpoint AFTER "because" a trailing "." whenever
 * the reason falls at the end of its sentence, while the endpoint BEFORE
 * "because" never has one. `[civictech.dialogue.mint.claimKey]` hashes the
 * text as given, so the SAME proposition uttered once as a reason and once as
 * a conclusion minted two claim keys. Measured on `bs20-because.jsonl`: of
 * three endpoint texts the transcript deliberately repeats across speakers,
 * only "The budget is too high" — the one that is a conclusion both times —
 * merged; "travel costs increased." / "Travel costs increased" and "we hired
 * more contractors." / "We hired more contractors" stayed separate.
 *
 * Three separate decisions, each made on its own merits:
 *
 * - **Trailing full stops (`.`, `…`, and runs of them) are stripped.** A full
 *   stop is punctuation the segmenter contributed, not content: whether a
 *   proposition carries one depends only on where it happened to fall in its
 *   sentence, which is exactly the accident that must not fork identity. The
 *   stripped text is what is emitted for the claim AND for the relation
 *   endpoint, so display and key agree and `RelationMint` still resolves
 *   against a key these same claims mint.
 * - **`?` and `!` are preserved.** They are not filler: they change what the
 *   segment asserts — "the budget is too high!" and the question "is the
 *   budget too high?" are not the proposition "the budget is too high", and a
 *   demo whose claim text is user-visible should not fold a question into an
 *   assertion. This is a narrower rule than "strip sentence punctuation", and
 *   deliberately so.
 * - **Capitalization is NOT touched here.** The obvious reading of the defect
 *   blames sentence-initial case as well, and that reading is wrong:
 *   `claimKey` already lowercases (2aw.F3-D1), so identity is *already*
 *   case-insensitive and the two unmerged pairs above differ, after
 *   canonicalization, only by the full stop. The text this extractor emits is
 *   what the reader sees on the map, so it keeps the speaker's own
 *   capitalization; normalizing the KEY while preserving the DISPLAYED text
 *   is the existing division of labour, and case-folding here would damage
 *   the display without changing a single key.
 *
 * The strip is applied to the ENDPOINT claims only, not to the whole-segment
 * claim a non-"because" segment yields, and that asymmetry is deliberate. An
 * endpoint's full stop is an artifact of *where the because-split fell*: the
 * same proposition carries one as a reason and not as a conclusion, which is
 * precisely the accident that forks identity. A whole-segment claim's
 * terminator is its own sentence's and is present uniformly, so it never
 * forks two whole-segment claims against each other. It does still fork a
 * whole-segment claim from an endpoint claim of the same proposition
 * ("Travel costs increased." as its own utterance vs "... because travel
 * costs increased") — a real residual, left open deliberately rather than
 * fixed here, because stripping it changes the canonical key of every plain
 * claim in the demo and belongs with `claimKey`'s own canonicalization rule
 * (2aw.F3-D1), not with this extractor's split. Filed as its own item.
 */
object RuleExtractor : Extractor {

    private val becauseRegex = Regex(" because ", RegexOption.IGNORE_CASE)

    /**
     * Trailing full stops only — see the class KDoc's "Trailing full stops":
     * `?` and `!` are deliberately absent from this class.
     */
    private val trailingFullStop = Regex("[.…]+$")

    /**
     * Drops the sentence-final full stop the segmenter left on [text]. A text
     * that is *nothing but* full stops is returned unchanged rather than
     * emptied, so this can never turn a non-blank endpoint blank.
     */
    private fun withoutTrailingFullStop(text: String): String {
        val trimmed = text.trim()
        val stripped = trimmed.replace(trailingFullStop, "").trim()
        return stripped.ifBlank { trimmed }
    }

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

        // Both endpoints go through the same normalization — see the class
        // KDoc's "Trailing full stops" (computenet-9bip). In practice only
        // `source` can carry a sentence-final full stop, but normalizing
        // both keeps the rule one rule rather than a positional special case.
        val target = withoutTrailingFullStop(trimmedText.substring(0, becauseMatch.range.first))
        val source = withoutTrailingFullStop(trimmedText.substring(becauseMatch.range.last + 1))
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
