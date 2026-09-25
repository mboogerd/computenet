package civictech.wire.vector

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

/**
 * `expect.observed` (`wire/corpus/SCHEMA.md` §Observed divergence, decision
 * ncz.6-D6): what [VectorDocument.parse] accepts and refuses, and how the
 * driver's decode-negative arm treats it.
 *
 * No frame literal lives here: every probe is derived from the seed vector
 * `WV-PORT-API-STALL-SUSPENDED-01` loaded through [VectorLoader], as
 * [RejectionClassifierTest] does.
 */
class ObservedDivergenceTest {

    private val loader = VectorLoader.locate()

    private val seed: VectorDocument =
        loader.documents().first { it.kind == VectorKind.FRAME && it.encoded?.utf8 != null }

    private val seedBytes: ByteArray get() = checkNotNull(seed.encoded).bytes

    /** The seed's bytes with a lone continuation byte 0x80 inserted inside the `portName` value, after its first two bytes. */
    private fun splicedIntoPortName(): ByteArray {
        val marker = "\"portName\":\"".toByteArray(Charsets.UTF_8)
        val at = indexOf(seedBytes, marker)
        assertTrue(at >= 0, "${seed.id}: no portName in the seed's bytes")
        val cut = at + marker.size + 2
        return seedBytes.copyOfRange(0, cut) + byteArrayOf(0x80.toByte()) + seedBytes.copyOfRange(cut, seedBytes.size)
    }

    /** The seed's bytes with an explicit differing `version` spliced after the opening brace — decodeFrame THROWS on these. */
    private fun versionProbe(): ByteArray {
        val utf8 = checkNotNull(seed.encoded?.utf8)
        assertTrue(utf8.startsWith("{"), "${seed.id}: encoded.utf8 is not a JSON object")
        return ("{\"version\":99," + utf8.substring(1)).toByteArray(Charsets.UTF_8)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1

    private fun document(
        id: String,
        direction: String,
        bytes: ByteArray?,
        decoded: JsonElement?,
        expect: JsonObject,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("title", "observed-divergence probe")
        put("category", "negative")
        put("kind", "negative")
        putJsonArray("covers") { add(JsonPrimitive("WIR1-I07")) }
        put("codecVersion", 2)
        put("direction", direction)
        put("expect", expect)
        if (bytes != null) {
            put("messageKind", "binary")
            putJsonObject("encoded") { put("base64", Base64.getEncoder().encodeToString(bytes)) }
        }
        if (decoded != null) put("decoded", decoded)
        put("notes", "built in ObservedDivergenceTest from ${seed.id}")
    }

    private fun parse(obj: JsonObject): VectorDocument =
        VectorDocument.parse(obj, loader.root.resolve("negative/${(obj["id"] as JsonPrimitive).content}.json"), loader.root)

    private fun expect(reject: String, observed: String? = null, extra: Pair<String, String>? = null): JsonObject = buildJsonObject {
        put("reject", reject)
        if (observed != null) put("observed", observed)
        if (extra != null) put(extra.first, extra.second)
    }

    // --- parse level ------------------------------------------------------------

    @Test
    fun `observed is accepted beside reject on a decode negative and surfaced`() {
        val doc = parse(document("WV-NEG-PROBE-OBS-01", "decode", splicedIntoPortName(), null, expect("invalid-utf8", "accepted-with-substitution")))
        assertEquals("invalid-utf8", doc.reject)
        assertEquals("accepted-with-substitution", doc.observed)
    }

    @Test
    fun `a negative without observed parses with observed null`() {
        val doc = parse(document("WV-NEG-PROBE-OBS-02", "decode", splicedIntoPortName(), null, expect("invalid-utf8")))
        assertNull(doc.observed)
    }

    @Test
    fun `an observation word outside the closed vocabulary is refused`() {
        val refused = assertThrows<VectorSchemaException> {
            parse(document("WV-NEG-PROBE-OBS-03", "decode", splicedIntoPortName(), null, expect("invalid-utf8", "accepted-silently")))
        }
        assertTrue(refused.message!!.contains("accepted-silently") && refused.message!!.contains("§Observed divergence"), refused.message)
    }

    @Test
    fun `observed is refused on an encode-direction negative`() {
        val decoded = checkNotNull(seed.decoded)
        val refused = assertThrows<VectorSchemaException> {
            parse(document("WV-NEG-PROBE-OBS-04", "encode", null, decoded, expect("leased-at-encode", "accepted-with-substitution")))
        }
        assertTrue(refused.message!!.contains("direction: decode"), refused.message)
    }

    @Test
    fun `expect carrying any key besides reject and observed is refused`() {
        val refused = assertThrows<VectorSchemaException> {
            parse(
                document(
                    "WV-NEG-PROBE-OBS-05", "decode", splicedIntoPortName(), null,
                    expect("invalid-utf8", "accepted-with-substitution", "because" to "reasons"),
                ),
            )
        }
        assertTrue(refused.message!!.contains("because"), refused.message)
        // …and without observed too: the only other allowed shape is {reject}.
        assertThrows<VectorSchemaException> {
            parse(document("WV-NEG-PROBE-OBS-06", "decode", splicedIntoPortName(), null, expect("invalid-utf8", extra = "because" to "reasons")))
        }
    }

    @Test
    fun `a non-string observed is refused`() {
        val refused = assertThrows<VectorSchemaException> {
            parse(
                document(
                    "WV-NEG-PROBE-OBS-07", "decode", splicedIntoPortName(), null,
                    buildJsonObject {
                        put("reject", "invalid-utf8")
                        put("observed", 1)
                    },
                ),
            )
        }
        assertTrue(refused.message!!.contains("`expect.observed` must be a string"), refused.message)
    }

    // --- driver level -----------------------------------------------------------

    @Test
    fun `driver passes invalid UTF-8 inside portName marked accepted-with-substitution`() {
        val doc = parse(document("WV-NEG-PROBE-OBS-10", "decode", splicedIntoPortName(), null, expect("invalid-utf8", "accepted-with-substitution")))
        WireVectorConformanceTest.verify(doc)
    }

    @Test
    fun `driver reds accepted-with-substitution when decodeFrame throws, saying the divergence has closed`() {
        val doc = parse(document("WV-NEG-PROBE-OBS-11", "decode", versionProbe(), null, expect("invalid-utf8", "accepted-with-substitution")))
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
        assertTrue(red.message!!.contains("divergence has closed"), red.message)
        assertTrue(red.message!!.contains("remove expect.observed"), red.message)
    }

    @Test
    fun `driver reds the same spliced bytes without observed - the plain decode arm is untouched`() {
        val doc = parse(document("WV-NEG-PROBE-OBS-12", "decode", splicedIntoPortName(), null, expect("invalid-utf8")))
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
        assertTrue(red.message!!.contains("returned one"), red.message)
    }

    @Test
    fun `driver reds accepted-with-substitution when the invalid sequence is not inside portName`() {
        // 0x80 before the opening brace: no returned portName can carry the U+FFFD, so the arm must not go green.
        // Note: the substituted text is not JSON, so decodeFrame THROWS and this reds via the "divergence has
        // closed" path; it does not reach the portName-U+FFFD guard (task review: neutralising the guard left it green).
        val bytes = byteArrayOf(0x80.toByte()) + seedBytes
        val doc = parse(document("WV-NEG-PROBE-OBS-13", "decode", bytes, null, expect("invalid-utf8", "accepted-with-substitution")))
        assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
    }
}
