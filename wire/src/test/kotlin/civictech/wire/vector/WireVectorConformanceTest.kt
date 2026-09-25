package civictech.wire.vector

import civictech.cell.Leased
import civictech.cell.Propagate
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.WireCodec
import civictech.wire.HelloParse
import civictech.wire.parseHello2
import civictech.wire.parseProof
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * The JVM corpus driver (`[WIR1-I01]`–`[WIR1-I03]`, epic B1.5): one JUnit
 * dynamic test per `wire/corpus/manifest.json` entry, named exactly by the
 * vector id, dispatched on `kind` then `direction` (`wire/corpus/SCHEMA.md`
 * §Kinds; decision ncz.2-D4 — every kind is declared here so later features
 * add data, not driver code).
 *
 * This file holds no expected bytes and no per-vector data: every value comes
 * from the loaded document.
 *
 * - `frame`/`both`: (1) `encode(frameOf(doc).invocation)` == `encoded.utf8`;
 *   (2) `decodeFrame(encoded)` == `frameOf(doc)`, frame and invocation;
 *   (3) `decodeFrame(encode(v))` == `v`.
 * - `frame`/`decode`: (2) only.
 * - `handshake-text`/`both`: [HandshakeLines.lineOf] == `encoded.utf8`, and for
 *   `HELLO2`/`PROOF` the production parser returns an equal message.
 *   `handshake-text`/`decode`: the parse half only.
 * - `negative`/`decode`: decoding `encoded.base64`'s bytes must throw, and
 *   [RejectionClassifier] must classify the throwable as `expect.reject`.
 * - `negative`/`encode`: every lease wrapper (SCHEMA.md §Encode-direction
 *   negatives), which must be a DIRECT `args` element, is stripped to its
 *   value; the invocation is built from the stripped `decoded` and those args
 *   re-wrapped in [Leased]; [BridgeEgressCell.deliver] must throw, the throwable
 *   must classify as `expect.reject`, and the egress outlet must have seen zero
 *   byte arrays (`[WIR1-I16]`, epic B3.9, decision ncz.6-D7). A wrapper in any
 *   other position, or none at all, is a schema refusal naming the position.
 *
 * A `deprecated` vector runs exactly like any other (SCHEMA.md §Ids).
 */
class WireVectorConformanceTest {

    @TestFactory
    fun `every manifest vector conforms`(): List<DynamicTest> {
        val documents = VectorLoader.locate().documents()
        assertTrue(documents.isNotEmpty(), "wire/corpus/manifest.json lists no vectors — the driver would run nothing")
        return documents.map { doc -> DynamicTest.dynamicTest(doc.id) { verify(doc) } }
    }

    companion object {
        /** Runs every assertion [doc]'s kind and direction call for; throws on the first that fails. */
        fun verify(doc: VectorDocument) {
            when (doc.kind) {
                VectorKind.FRAME -> verifyFrame(doc)
                VectorKind.HANDSHAKE_TEXT -> verifyHandshake(doc)
                VectorKind.NEGATIVE -> verifyNegative(doc)
            }
        }

        private fun verifyFrame(doc: VectorDocument) {
            val id = doc.id
            val encoded = checkNotNull(doc.encoded) { "$id: frame vector without `encoded` passed the loader" }
            val expected = NeutralValues.frameOf(doc)
            if (doc.direction == VectorDirection.BOTH) {
                // (1) [WIR1-I01] — Strings, so a failure prints both.
                assertEquals(
                    encoded.utf8,
                    NeutralValues.encodedBytesOf(doc).decodeToString(),
                    "$id [WIR1-I01]: WireCodec.encode(frameOf(decoded).invocation) != encoded.utf8",
                )
            }
            // (2) [WIR1-I02]
            val decoded = WireCodec.decodeFrame(encoded.bytes)
            assertEquals(expected.frame, decoded.frame, "$id [WIR1-I02]: decodeFrame(encoded).frame != frameOf(decoded).frame")
            assertEquals(
                expected.invocation,
                decoded.invocation,
                "$id [WIR1-I02]: decodeFrame(encoded).invocation != frameOf(decoded).invocation",
            )
            if (doc.direction == VectorDirection.BOTH) {
                // (3) [WIR1-I03]
                assertEquals(
                    expected,
                    WireCodec.decodeFrame(WireCodec.encode(expected.invocation)),
                    "$id [WIR1-I03]: decodeFrame(encode(v)) != v",
                )
            }
        }

        private fun verifyHandshake(doc: VectorDocument) {
            val id = doc.id
            val line = checkNotNull(doc.encoded?.utf8) { "$id: handshake-text vector without `encoded.utf8` passed the loader" }
            assertEquals("text", doc.messageKind, "$id: a handshake-text vector travels as a text WebSocket message")
            if (doc.direction == VectorDirection.BOTH) {
                assertEquals(line, HandshakeLines.lineOf(doc), "$id: HandshakeLines.lineOf(decoded) != encoded.utf8")
            }
            val message = HandshakeLines.messageOf(doc)
            when (HandshakeLines.typeOf(doc)) {
                HandshakeLines.HELLO2 -> assertEquals(
                    HelloParse.Ok(message),
                    parseHello2(line),
                    "$id: parseHello2(encoded.utf8) is not Ok(the Hello2 built from decoded)",
                )
                HandshakeLines.PROOF -> assertEquals(
                    HelloParse.Ok(message),
                    parseProof(line),
                    "$id: parseProof(encoded.utf8) is not Ok(the Proof built from decoded)",
                )
                HandshakeLines.HELLO -> if (doc.direction == VectorDirection.DECODE) {
                    fail<Unit>(
                        "$id: a decode-direction legacy HELLO vector has no public parser to check against — " +
                            "WsTransport.Session.hello() is internal; tying the line to the session is feature computenet-ncz.5's",
                    )
                }
            }
        }

        private fun verifyNegative(doc: VectorDocument) {
            val id = doc.id
            val expected = checkNotNull(doc.reject) { "$id: negative vector without `expect.reject` passed the loader" }
            when (doc.direction) {
                VectorDirection.DECODE -> {
                    // base64, not utf8: an invalid-utf8 vector carries base64 alone.
                    val bytes = checkNotNull(doc.encoded) { "$id: decode-direction negative without `encoded` passed the loader" }.bytes
                    val thrown = assertThrows<Throwable>(
                        "$id: decodeFrame(encoded) must refuse with `$expected` and return no invocation, but it returned one",
                    ) { WireCodec.decodeFrame(bytes) }
                    val actual = RejectionClassifier.classify(thrown)
                    assertTrue(
                        RejectionClassifier.satisfies(expected, actual),
                        "$id: expect.reject `$expected` but the refusal classified as `$actual` " +
                            "(no invocation was returned — decodeFrame threw)",
                    )
                }
                VectorDirection.ENCODE -> verifyEncodeNegative(doc, expected)
                VectorDirection.BOTH -> fail<Unit>("$id: a negative vector with direction `both` passed the loader")
            }
        }

        private fun verifyEncodeNegative(doc: VectorDocument, expected: String) {
            val id = doc.id
            val (stripped, leasedAt) = stripLeaseWrappers(doc)
            val built = NeutralValues.frameOf(stripped).invocation
            val args = built.invocation.args
            check(leasedAt.all { it < args.size }) { "$id: built invocation has ${args.size} args, lease wrappers at $leasedAt" }
            val invocation: HostedPortInvocation = built.copy(
                invocation = built.invocation.copy(
                    args = args.mapIndexed { i, a ->
                        if (i in leasedAt) Leased(checkNotNull(a) { "$id: args[$i] inside a lease wrapper built to null" }) else a
                    },
                ),
            )

            val recorded = mutableListOf<ByteArray>()
            val egress = BridgeEgressCell()
            egress.outlet.subscribe(Use.fixed(Propagate<ByteArray> { recorded += it }, PortRef.generate()))
            val thrown = assertThrows<Throwable>(
                "$id: BridgeEgressCell.deliver must refuse with `$expected`, but it accepted the send",
            ) { egress.deliver(invocation) }
            val actual = RejectionClassifier.classify(thrown)
            assertTrue(
                RejectionClassifier.satisfies(expected, actual),
                "$id: expect.reject `$expected` but the egress refusal classified as `$actual`",
            )
            assertTrue(
                recorded.isEmpty(),
                "$id [WIR1-I16] B3.9: the egress refused, yet ${recorded.size} byte array(s) left it on the outlet",
            )
        }

        private const val LEASED = "Leased"

        /**
         * [doc] with every lease wrapper that is a DIRECT element of
         * `decoded.fields.args` replaced by its `value`, plus those args indexes.
         * Refuses (schema) a `Leased` node in any other position — the only one
         * `BridgeEgressCell.deliver` inspects is a top-level arg (ncz.6-D7) — a
         * malformed wrapper, and an encode negative carrying no wrapper at all.
         */
        internal fun stripLeaseWrappers(doc: VectorDocument): Pair<VectorDocument, Set<Int>> {
            fun refuse(rule: String): Nothing = throw VectorSchemaException("${doc.file}: $rule (SCHEMA.md §Encode-direction negatives)")
            val decoded = doc.decoded as? JsonObject ?: refuse("an encode negative's `decoded` must be the frame envelope")
            val fields = decoded["fields"] as? JsonObject ?: refuse("an encode negative's `decoded.fields` must be an object")
            val args = fields["args"] as? JsonArray ?: refuse("an encode negative's `decoded.fields.args` must be an array")

            val leasedAt = linkedSetOf<Int>()
            val strippedArgs = args.mapIndexed { i, arg ->
                if (arg.isLeasedNode()) {
                    val wrapper = arg as JsonObject
                    val inner = (wrapper["fields"] as? JsonObject)?.takeIf { wrapper.keys == setOf("type", "fields") && it.keys == setOf("value") }
                        ?: refuse("$.fields.args[$i]: a lease wrapper must be exactly {\"type\": \"Leased\", \"fields\": {\"value\": …}}")
                    leasedAt += i
                    inner.getValue("value")
                } else {
                    arg
                }
            }
            val strippedDecoded = JsonObject(decoded + ("fields" to JsonObject(fields + ("args" to JsonArray(strippedArgs)))))
            findLeasedNode(strippedDecoded, "$")?.let { path ->
                refuse("$path: a Leased wrapper is allowed only as a direct element of `decoded.fields.args` — the only position BridgeEgressCell.deliver inspects (ncz.6-D7)")
            }
            if (leasedAt.isEmpty()) refuse("an encode negative carries no lease wrapper in `decoded.fields.args` — nothing for the egress to refuse")

            val stripped = VectorDocument(
                file = doc.file, source = doc.source, id = doc.id, title = doc.title, category = doc.category,
                kind = doc.kind, covers = doc.covers, codecVersion = doc.codecVersion, notes = doc.notes,
                decoded = strippedDecoded, encoded = doc.encoded, reject = doc.reject, direction = doc.direction,
                messageKind = doc.messageKind, deprecated = doc.deprecated,
            )
            return stripped to leasedAt
        }

        private fun JsonElement.isLeasedNode(): Boolean =
            this is JsonObject && (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == LEASED

        /** The path of the first `{"type": "Leased", …}` object under [element], or null. */
        private fun findLeasedNode(element: JsonElement, path: String): String? = when {
            element.isLeasedNode() -> path
            element is JsonObject -> element.entries.firstNotNullOfOrNull { (k, v) -> findLeasedNode(v, "$path.$k") }
            element is JsonArray -> element.withIndex().firstNotNullOfOrNull { (i, v) -> findLeasedNode(v, "$path[$i]") }
            else -> null
        }
    }
}
