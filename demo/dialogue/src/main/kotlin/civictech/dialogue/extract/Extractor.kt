package civictech.dialogue.extract

import civictech.dialogue.Segment
import java.security.MessageDigest

/**
 * The determinism firewall (epic computenet-2aw DESIGN 2aw-D4, R1,
 * `[AGO1-EXTR-01]`): every non-deterministic or external operation the
 * pipeline performs — network calls, randomness, wall-clock reads — is
 * confined behind this single SPI. No gating test invokes a live model
 * through it; gating tests use [RuleExtractor] or [CassetteExtractor].
 *
 * Exactly this shape, per the epic:
 * `fun interface Extractor { fun extract(segment: Segment): List<ExtractedItem> }`
 */
fun interface Extractor {
    fun extract(segment: Segment): List<ExtractedItem>
}

/**
 * The content hash a [CassetteExtractor] keys its recorded results by, and
 * the identifier an [Extractor] failure ([CassetteMissException]) names.
 *
 * SHA-256 (JDK-only, `java.security.MessageDigest`) over `segment.text`
 * encoded as UTF-8, rendered as lowercase hex. Deliberately over `text`
 * only — not `speaker`, `utteranceId` or `id` — because `[AGO1-EXTR-02]`'s
 * scenario is "the same segment content in two different utterances", so
 * the hash must collide exactly when `text` is equal and must be
 * insensitive to every other field. Extraction is thereby a pure function
 * of segment *content*.
 */
fun segmentContentHash(segment: Segment): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(segment.text.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
