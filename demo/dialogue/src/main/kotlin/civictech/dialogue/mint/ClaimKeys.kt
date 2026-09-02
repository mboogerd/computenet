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
 * runs to one space, lowercase. Content-derived identity is genuinely weak
 * (epic §8/R3) — paraphrases mint separate keys. That is the expected
 * behavior of this seam, not a defect to fix here.
 */
fun claimKey(text: String): ClaimKey =
    ClaimKey(text.trim().replace(Regex("\\s+"), " ").lowercase())
