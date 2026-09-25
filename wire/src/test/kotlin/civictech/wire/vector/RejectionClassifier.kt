package civictech.wire.vector

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlin.reflect.KClass

/**
 * Maps a decode-time (or, for `leased-at-encode`, a bridge-egress) exception to one word of `wire/corpus/SCHEMA.md`
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
@OptIn(ExperimentalSerializationApi::class)
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
        // Encode direction, WV-NEG-LEASED-AT-ENCODE-01 ([WIR1-I16], epic B3.9): BridgeEgressCell.deliver's
        // require(args.none { it is Leased<*> }) { "Leased payloads must not cross machine boundaries (spec 23) — …" },
        // thrown BEFORE WireCodec.encode runs. Type-specific rows precede any SerializationException row and
        // the null-substring `malformed` catch-all (ncz.6-D8): with the bridge's require removed, encode of an
        // unregistered Leased throws a SerializationException, which must NOT read as this refusal.
        Rule("leased-at-encode", IllegalArgumentException::class, "Leased payloads must not cross machine boundaries"),
        // WV-NEG-MISSING-REQUIRED-01 (portName removed): kotlinx.serialization.MissingFieldException:
        // "Field 'portName' is required for type with serial name 'civictech.cell.wire.WireFrame', but it
        // was missing at path: $" — type alone distinguishes it from every other SerializationException row
        // below, so no substring is needed.
        Rule("missing-required-field", MissingFieldException::class, null),
        // WV-NEG-PROTOCOL-NO-PROTOCOLID-01 / WV-NEG-PROTOCOL-NO-EDGE-01: WireCodec.invocation's
        // checkNotNull(frame.protocolId) / checkNotNull(frame.edge) throw IllegalStateException
        // "PORT_PROTOCOL frame missing protocolId" / "PORT_PROTOCOL frame missing edge" — this row's
        // rejection ordering is pinned-because-current, not normative: both throw AFTER a successful
        // envelope parse, from invocation() rather than from decodeFrame's own checks (ncz.6 table notes).
        Rule("missing-required-field", IllegalStateException::class, "PORT_PROTOCOL frame missing"),
        // WV-NEG-UNKNOWN-TYPE-01 ("type":"PORT_TELEPATHY"): kotlinx.serialization.SerializationException:
        // "civictech.cell.proxy.HostedPortInvocation.Type does not contain element with name 'PORT_TELEPATHY'
        // at path $.type".
        Rule("unknown-frame-type", SerializationException::class, "does not contain element with name"),
        // WV-NEG-UNKNOWN-DISCRIMINATOR-01 (args discriminator "QuantumDelta"): kotlinx.serialization.SerializationException:
        // "Serializer for subclass 'QuantumDelta' is not found in the polymorphic scope of 'Any'." — decodeFrame
        // throws, so an invocation with args == [null] or args == [] never reaches this row ([WIR1-I08]).
        Rule("unknown-discriminator", SerializationException::class, "is not found in the polymorphic scope"),
        // WV-NEG-TRUNCATED-01 (first 120 of the seed's 200 bytes): kotlinx.serialization.SerializationException
        // (JsonDecodingException): "Expected end of the object '}', but had 'EOF' instead at path: $" — the
        // JVM decoder DOES distinguish truncation from other malformed JSON by this substring (D8's
        // pinned-because-current fallback to `malformed` was not needed; `satisfies` still accepts either).
        Rule("truncated", SerializationException::class, "but had 'EOF' instead"),
        // WV-NEG-MALFORMED-01 (bytes = the literal `{"version":2,`): kotlinx.serialization.SerializationException
        // (JsonDecodingException): "Unexpected JSON token at offset 12: Trailing comma before the end of JSON
        // object at path: $.version". CATCH-ALL: any later SerializationException row (e.g. ncz.5's
        // `unknown-envelope-field`) must be inserted ABOVE this line, never below — this null-substring row
        // must stay last so it does not swallow a more specific SerializationException classification.
        Rule("malformed", SerializationException::class, null),
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
