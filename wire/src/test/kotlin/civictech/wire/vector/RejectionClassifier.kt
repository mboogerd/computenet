package civictech.wire.vector

import kotlin.reflect.KClass

/**
 * Maps a decode-time exception to one word of `wire/corpus/SCHEMA.md`
 * §Rejection vocabulary, so a `negative` vector's `expect.reject` can be
 * checked against what the JVM codec actually threw (decision ncz.2-D4).
 *
 * ONE table, matched by exception TYPE first ([KClass.isInstance]) and then by
 * message substring, first match wins. Anything no row matches classifies as
 * [unclassified] — a string that equals no vocabulary word, so the red
 * assertion shows the real exception rather than a plausible-looking word.
 *
 * Seeded with exactly the two rows `WireCodec.decodeFrame` distinguishes by an
 * `IllegalStateException` today. Features computenet-ncz.5 and computenet-ncz.6
 * extend the table (the `kotlinx.serialization.SerializationException` family:
 * `malformed`, `truncated`, `unknown-discriminator`, …) as they author the
 * vectors that exercise each row; every added row cites that vector.
 */
object RejectionClassifier {

    /** One row: [classification] when the throwable is an [exceptionClass] whose message contains [messageSubstring] (null = any message). */
    data class Rule(
        val classification: String,
        val exceptionClass: KClass<out Throwable>,
        val messageSubstring: String?,
    )

    val rules: List<Rule> = listOf(
        // WireCodec.decodeFrame: check(frame.version == VERSION) { "unsupported wire version N" }
        Rule("unsupported-version", IllegalStateException::class, "unsupported wire version"),
        // WireCodec.invocation: checkNotNull(ContractRegistry.method(...)) { "unknown contract/method ids A/B — no local descriptor" }
        Rule("unknown-ids", IllegalStateException::class, "unknown contract/method ids"),
    )

    init {
        val outside = rules.map { it.classification }.filterNot { it in VectorDocument.REJECTIONS }
        check(outside.isEmpty()) { "RejectionClassifier rows outside SCHEMA.md §Rejection vocabulary: $outside" }
    }

    /** The vocabulary word for [thrown], or an [unclassified] string when no row matches. */
    fun classify(thrown: Throwable): String =
        rules.firstOrNull { rule ->
            rule.exceptionClass.isInstance(thrown) &&
                (rule.messageSubstring == null || thrown.message?.contains(rule.messageSubstring) == true)
        }?.classification ?: unclassified(thrown)

    /** `unclassified(<FQCN>: <message>)` — deliberately never a vocabulary word. */
    fun unclassified(thrown: Throwable): String = "unclassified(${thrown.javaClass.name}: ${thrown.message})"

    fun isUnclassified(classification: String): Boolean = classification.startsWith("unclassified(")

    /**
     * Whether [actual] satisfies a vector expecting [expected]. Exact match, with
     * SCHEMA.md's one refinement: `truncated` is also satisfied by `malformed`
     * (a decoder that cannot tell them apart; epic B3.2). The converse does not
     * hold, and nothing else is loosened.
     */
    fun satisfies(expected: String, actual: String): Boolean =
        actual == expected || (expected == "truncated" && actual == "malformed")
}
