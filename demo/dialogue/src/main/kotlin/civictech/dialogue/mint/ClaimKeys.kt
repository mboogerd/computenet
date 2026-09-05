package civictech.dialogue.mint

import civictech.dialogue.ClaimKey

/**
 * The claim-identity canonicalization seam (2aw.F3-D1, epic computenet-2aw
 * §2.2 stage 4): the single, named function anywhere in this codebase that
 * derives a [ClaimKey] from claim text. AGO3 (needs KE1) replaces this
 * function wholesale with a stronger identity scheme — nothing else may
 * canonicalize claim text in the meantime, so every consumer that needs a
 * claim's key calls this rather than reimplementing the rule.
 *
 * Canonicalization is deliberately weak: trim, collapse internal whitespace
 * runs to one space, drop the trailing sentence terminator (see below),
 * lowercase. Content-derived identity is genuinely weak (epic §8/R3) —
 * paraphrases mint separate keys. That is the expected behavior of this seam,
 * not a defect to fix here.
 *
 * ### Trailing terminators (computenet-lv25, from computenet-9bip/-qoei)
 *
 * [civictech.dialogue.Segmentation] splits on `(?<=[.!?])\s+`, a lookbehind,
 * so a sentence's terminator stays attached to it. Whether a given
 * proposition carries one therefore depends only on where it happened to fall
 * in its sentence: uttered as a standalone segment it keeps the full stop,
 * while the same proposition reached as the reason endpoint of a "because"
 * split does not. That is a positional accident of the segmenter, not
 * content, and it forked identity — "Travel costs increased." and "... because
 * travel costs increased" minted two canonical claims for one proposition.
 *
 * **Why here and not in the extractor** (2aw.F3-D1 decided by computenet-lv25).
 * [civictech.dialogue.extract.RuleExtractor] already strips the terminator
 * from its *because-endpoints*, and computenet-9bip deliberately left the
 * whole-segment half open because fixing it there would be narrower and
 * differently shaped. Three reasons put the rule in this seam instead:
 *
 * - It is a rule about **identity**, and this function is by construction the
 *   only place identity is derived — so a rule that lives here holds for every
 *   producer, including [civictech.dialogue.extract.CassetteExtractor],
 *   [civictech.dialogue.extract.LlmExtractor] and any future one, without each
 *   having to rediscover it.
 * - It is **key-only**, so the text a reader sees on the claim node keeps the
 *   speaker's own punctuation. That is this codebase's existing division of
 *   labour — `RuleExtractor`'s KDoc states it for capitalization in exactly
 *   these terms ("normalizing the KEY while preserving the DISPLAYED text") —
 *   whereas the extractor-side alternative would have edited the displayed
 *   text of every plain claim in the demo.
 * - **"Deliberately weak" is not "closed to normalization", and "AGO3 replaces
 *   this wholesale" is not "do not touch it".** The weakness this KDoc claims
 *   is *semantic*: paraphrases fork, and resolving them is AGO3's job with a
 *   stronger identity scheme. This addition is *typographic*, the same class
 *   as the trim, the whitespace collapse and the lowercasing already here, and
 *   it does not make the seam any less weak semantically. Being replaced
 *   wholesale is an argument against building machinery here, not against one
 *   more character class in a one-line function: it costs one regex and is
 *   discarded with the rest.
 *
 * `?` is deliberately excluded from the class, per computenet-9bip as narrowed
 * by computenet-qoei: an interrogative asserts something different from its
 * declarative ("is the budget too high?" is not "the budget is too high"),
 * while `!` is reachable through the identical split-position accident `.` is
 * and only varies emphasis. Folding an interrogative into an assertion would
 * be a different defect, not a fix for this one.
 */
fun claimKey(text: String): ClaimKey =
    ClaimKey(withoutTrailingTerminator(text.trim().replace(Regex("\\s+"), " ")).lowercase())

/**
 * The terminator class the segmenter itself contributes — `.`, `…`, `!`, and
 * runs of them — kept in step with
 * `civictech.dialogue.extract.RuleExtractor.trailingTerminator`. `?` is
 * absent by decision, not oversight; see [claimKey]'s KDoc.
 *
 * `private` (computenet-8ojp, reverting computenet-2qkn's `internal`):
 * `ClaimMintTest` pins this class against `RuleExtractor`'s directionally —
 * every terminator the extractor strips must also be stripped here, so the
 * two cannot fork identity again the way computenet-9bip/-qoei/-lv25 each
 * had to fix in turn — but does so through the public surface (`extract` and
 * [claimKey]) rather than by reaching in here directly, per the same
 * computenet-if9j principle cited on `RuleExtractor.trailingTerminator`'s
 * KDoc: `internal`-for-a-direct-unit-test is not justified once the
 * behaviour is reachable publicly, and it was measured to be. The reverse
 * direction (this class stripping something the extractor does not) is
 * deliberately left unpinned: it only changes canonicalization of the KEY,
 * never the displayed text, so it is harmless by this seam's own division of
 * labour — see [claimKey]'s KDoc.
 */
private val trailingTerminator = Regex("[.…!]+$")

/**
 * Drops the sentence-final terminator from an already trimmed, whitespace-
 * collapsed [text]. A text that is *nothing but* terminator punctuation is
 * returned unchanged rather than emptied, so canonicalization can never
 * collapse every such claim onto one empty key.
 *
 * `private` for the same reason as [trailingTerminator] (computenet-8ojp).
 */
private fun withoutTrailingTerminator(text: String): String =
    text.replace(trailingTerminator, "").trim().ifBlank { text }
