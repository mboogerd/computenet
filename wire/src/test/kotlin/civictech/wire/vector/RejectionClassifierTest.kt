package civictech.wire.vector

import civictech.cell.wire.WireCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

/**
 * [RejectionClassifier]'s two seeded rows and the driver's `negative` arm.
 *
 * No frame literal lives here: both probes are derived from a seed vector loaded
 * through [VectorLoader] — the same two alterations `WireCodecTest` makes at
 * kernel level ("u5gb - …" splices a differing `version`; "unknown ids are
 * rejected at decode" rewrites an id).
 */
class RejectionClassifierTest {

    private val loader = VectorLoader.locate()

    private val seed: VectorDocument =
        loader.documents().first { it.kind == VectorKind.FRAME && it.encoded?.utf8 != null }

    private val seedUtf8: String get() = checkNotNull(seed.encoded?.utf8)

    /** The seed's bytes with an explicit differing `version` spliced after the opening brace. */
    private fun versionProbe(): String {
        assertTrue(seedUtf8.startsWith("{"), "${seed.id}: encoded.utf8 is not a JSON object")
        return "{\"version\":99," + seedUtf8.substring(1)
    }

    /** The seed's bytes with its methodId rewritten to an id no descriptor carries. */
    private fun unknownIdsProbe(): String {
        val probe = Regex("\"methodId\":-?\\d+").replace(seedUtf8, "\"methodId\":1")
        assertNotEquals(seedUtf8, probe, "${seed.id}: no methodId to rewrite")
        return probe
    }

    private fun thrownBy(utf8: String): Throwable =
        assertThrows<Throwable> { WireCodec.decodeFrame(utf8.toByteArray(Charsets.UTF_8)) }

    @Test
    fun `an explicit differing version classifies as unsupported-version`() {
        assertEquals("unsupported-version", RejectionClassifier.classify(thrownBy(versionProbe())))
    }

    @Test
    fun `an unregistered contract-method id pair classifies as unknown-ids`() {
        assertEquals("unknown-ids", RejectionClassifier.classify(thrownBy(unknownIdsProbe())))
    }

    @Test
    fun `anything no row matches is unclassified and names the real exception`() {
        val unrelated = RejectionClassifier.classify(IllegalArgumentException("x"))
        assertEquals("unclassified(java.lang.IllegalArgumentException: x)", unrelated)
        assertFalse(unrelated in VectorDocument.REJECTIONS)
        // Right type, wrong message: the substring is part of the match.
        assertTrue(RejectionClassifier.isUnclassified(RejectionClassifier.classify(IllegalStateException("something else"))))
    }

    @Test
    fun `truncated is satisfied by malformed, and nothing else is loosened`() {
        assertTrue(RejectionClassifier.satisfies("truncated", "truncated"))
        assertTrue(RejectionClassifier.satisfies("truncated", "malformed"))
        assertFalse(RejectionClassifier.satisfies("malformed", "truncated"))
        assertFalse(RejectionClassifier.satisfies("unknown-ids", "unsupported-version"))
        assertFalse(RejectionClassifier.satisfies("malformed", RejectionClassifier.unclassified(IllegalStateException("x"))))
    }

    // --- the driver's negative arm, on documents built from the seed ------------

    private fun negative(id: String, reject: String, direction: String, encodedUtf8: String?, decoded: JsonElement?): VectorDocument {
        val obj: JsonObject = buildJsonObject {
            put("id", id)
            put("title", "driver probe")
            put("category", "negative")
            put("kind", "negative")
            putJsonArray("covers") { add(JsonPrimitive("WIR1-I04")) }
            put("codecVersion", 2)
            put("direction", direction)
            putJsonObject("expect") { put("reject", reject) }
            if (encodedUtf8 != null) {
                put("messageKind", "binary")
                putJsonObject("encoded") {
                    put("utf8", encodedUtf8)
                    put("base64", Base64.getEncoder().encodeToString(encodedUtf8.toByteArray(Charsets.UTF_8)))
                }
            }
            if (decoded != null) put("decoded", decoded)
            put("notes", "built in RejectionClassifierTest from ${seed.id}")
        }
        return VectorDocument.parse(obj, loader.root.resolve("negative/$id.json"), loader.root)
    }

    @Test
    fun `driver accepts a decode negative whose refusal classifies as expected`() {
        WireVectorConformanceTest.verify(negative("WV-NEG-PROBE-VERSION-01", "unsupported-version", "decode", versionProbe(), null))
        WireVectorConformanceTest.verify(negative("WV-NEG-PROBE-IDS-01", "unknown-ids", "decode", unknownIdsProbe(), null))
    }

    @Test
    fun `driver reds a decode negative whose refusal classifies differently`() {
        val doc = negative("WV-NEG-PROBE-WRONG-01", "unknown-ids", "decode", versionProbe(), null)
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
        assertTrue(red.message!!.contains("unsupported-version"), "the red must show the actual classification: ${red.message}")
    }

    @Test
    fun `driver reds a decode negative whose bytes decode`() {
        val doc = negative("WV-NEG-PROBE-ACCEPTED-01", "malformed", "decode", seedUtf8, null)
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
        assertTrue(red.message!!.contains("returned one"), red.message)
    }

    @Test
    fun `driver fails an encode negative loudly, naming computenet-ncz_6`() {
        val doc = negative("WV-NEG-PROBE-LEASED-01", "leased-at-encode", "encode", null, checkNotNull(seed.decoded))
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
        assertTrue(red.message!!.contains("computenet-ncz.6"), red.message)
    }
}
