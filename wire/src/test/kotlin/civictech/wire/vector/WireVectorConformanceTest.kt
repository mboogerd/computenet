package civictech.wire.vector

import civictech.cell.wire.WireCodec
import civictech.wire.HelloParse
import civictech.wire.parseHello2
import civictech.wire.parseProof
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
 * - `negative`/`encode`: FAILS loudly — the refusal hook is feature
 *   computenet-ncz.6's; never skipped.
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
        const val ENCODE_NEGATIVE_OWNER_MESSAGE: String =
            "encode-direction negative vectors (leased-at-encode) are declared by SCHEMA.md; the refusal hook is " +
                "feature computenet-ncz.6's — no such vector exists at this base, and one authored before that hook " +
                "lands must be red, never skipped"

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
                else -> fail<Unit>("$id: $ENCODE_NEGATIVE_OWNER_MESSAGE")
            }
        }
    }
}
