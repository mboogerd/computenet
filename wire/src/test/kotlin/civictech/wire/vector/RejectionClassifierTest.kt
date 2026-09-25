package civictech.wire.vector

import civictech.cell.wire.WireCodec
import kotlinx.serialization.json.JsonArray
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
 * [RejectionClassifier]'s rows and the driver's `negative` arm, both directions.
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

    /** The seed's bytes with an envelope key `WireFrame` does not declare spliced after the opening brace. */
    private fun unknownEnvelopeKeyProbe(): String {
        assertTrue(seedUtf8.startsWith("{"), "${seed.id}: encoded.utf8 is not a JSON object")
        return "{\"telepathy\":true," + seedUtf8.substring(1)
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
    fun `an unknown envelope key classifies as unknown-envelope-field, and the malformed probe still classifies as malformed`() {
        assertEquals("unknown-envelope-field", RejectionClassifier.classify(thrownBy(unknownEnvelopeKeyProbe())))
        val malformed = loader.documents().first { it.id == "WV-NEG-MALFORMED-01" }
        val malformedBytes = checkNotNull(malformed.encoded) { "${malformed.id}: no `encoded`" }.bytes
        assertEquals(
            "malformed",
            RejectionClassifier.classify(assertThrows<Throwable> { WireCodec.decodeFrame(malformedBytes) }),
        )
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
        WireVectorConformanceTest.verify(negative("WV-NEG-PROBE-UNKNOWN-KEY-01", "unknown-envelope-field", "decode", unknownEnvelopeKeyProbe(), null))
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

    // --- the driver's encode arm (ncz.6-D7), on the seed with a lease wrapper ---

    private fun leaseWrapper(value: JsonElement): JsonObject = buildJsonObject {
        put("type", "Leased")
        putJsonObject("fields") { put("value", value) }
    }

    /** The seed's `decoded` with `fields.args` replaced by [args]. */
    private fun seedDecodedWithArgs(args: List<JsonElement>): JsonObject {
        val decoded = checkNotNull(seed.decoded) as JsonObject
        val fields = decoded.getValue("fields") as JsonObject
        return JsonObject(decoded + ("fields" to JsonObject(fields + ("args" to JsonArray(args)))))
    }

    private val seedArg: JsonElement
        get() = ((checkNotNull(seed.decoded) as JsonObject).getValue("fields") as JsonObject).getValue("args").let { (it as JsonArray).first() }

    @Test
    fun `the bridge's spec-23 refusal classifies as leased-at-encode`() {
        val refusal = IllegalArgumentException("Leased payloads must not cross machine boundaries (spec 23) — freeze or copy first")
        assertEquals("leased-at-encode", RejectionClassifier.classify(refusal))
    }

    @Test
    fun `driver accepts an encode negative whose lease wrapper the egress refuses before any byte`() {
        val doc = negative("WV-NEG-PROBE-LEASED-01", "leased-at-encode", "encode", null, seedDecodedWithArgs(listOf(leaseWrapper(seedArg))))
        WireVectorConformanceTest.verify(doc)
    }

    @Test
    fun `driver reds an encode negative expecting another word, naming leased-at-encode`() {
        val doc = negative("WV-NEG-PROBE-LEASED-02", "malformed", "encode", null, seedDecodedWithArgs(listOf(leaseWrapper(seedArg))))
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(doc) }
        assertTrue(red.message!!.contains("leased-at-encode"), "the red must show the actual classification: ${red.message}")
    }

    @Test
    fun `driver refuses as a schema violation a lease wrapper nested below a direct args element`() {
        // Stall(reason = <lease wrapper>): the wrapper sits at $.fields.args[0].fields.reason, a position the
        // bridge never inspects — the driver must not silently build and pass it through.
        val stall = seedArg as JsonObject
        val nested = JsonObject(stall + ("fields" to buildJsonObject { put("reason", leaseWrapper(JsonPrimitive("SUSPENDED"))) }))
        val doc = negative("WV-NEG-PROBE-LEASED-03", "leased-at-encode", "encode", null, seedDecodedWithArgs(listOf(nested)))
        val refused = assertThrows<VectorSchemaException> { WireVectorConformanceTest.verify(doc) }
        assertTrue(refused.message!!.contains("$.fields.args[0].fields.reason"), "the refusal must name the position: ${refused.message}")
    }

    @Test
    fun `driver refuses as a schema violation an encode negative carrying no lease wrapper`() {
        val doc = negative("WV-NEG-PROBE-LEASED-04", "leased-at-encode", "encode", null, checkNotNull(seed.decoded))
        val refused = assertThrows<VectorSchemaException> { WireVectorConformanceTest.verify(doc) }
        assertTrue(refused.message!!.contains("no lease wrapper"), refused.message)
    }
}
