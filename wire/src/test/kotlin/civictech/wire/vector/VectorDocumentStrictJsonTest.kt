package civictech.wire.vector

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `computenet-v7s6t`: `VectorDocument.parse` parses with `Json.parseToJsonElement`,
 * whose default, lenient parser accepts unquoted literals that are not RFC 8259
 * JSON — `01`, `.5`, `1.`, `NaN`, a bare word. A corpus document carrying one of
 * these would load (and, per `NeutralValues.wireText`'s "preserve numeric
 * spelling verbatim" contract, even round-trip) on the JVM while a non-JVM
 * driver — or `python3 -c 'json.load(...)'`, the check `SCHEMA.md` itself
 * prints — refuses it, making the JVM loader the most permissive reader of a
 * corpus meant to be implementation-neutral.
 *
 * Every probe here is a copy of a real corpus document (loaded through
 * [VectorLoader]) with its `decoded` tree replaced by `{"probe": <literal>}`,
 * where `<literal>` is spliced in via [JsonUnquotedLiteral] — [JsonObject]
 * manipulation, never a frame literal in source, per this bead's acceptance
 * criteria. `VectorDocument.parse(JsonObject, Path, Path)` only requires
 * `decoded` to be present for a positive kind (SCHEMA.md's frame-envelope
 * shape is [NeutralValues.frameOf]'s concern, not the parser's), so a
 * single-key probe object is enough to reach the literal walk.
 */
class VectorDocumentStrictJsonTest {

    private val loader = VectorLoader.locate()

    /** Any positive-kind vector: only its shape (kind, `decoded` present) matters here. */
    private val seed: VectorDocument = loader.documents().first { it.kind.positive && it.decoded != null }

    /** [seed]'s source document with `decoded` replaced by `{"probe": literal}`. */
    private fun withProbe(literal: JsonElement): JsonObject = buildJsonObject {
        for ((key, value) in seed.source) {
            put(key, if (key == "decoded") buildJsonObject { put("probe", literal) } else value)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun refusalOf(literal: String): String {
        val probe = withProbe(JsonUnquotedLiteral(literal))
        val ex = assertThrows<VectorSchemaException> { VectorDocument.parse(probe, seed.file, loader.root) }
        return ex.message ?: ""
    }

    @Test
    fun `refuses a leading-zero integer`() {
        val message = refusalOf("01")
        assertTrue(message.contains("\$.decoded.probe"), message)
        assertTrue(message.contains("\"01\""), message)
    }

    @Test
    fun `refuses a bare word`() {
        assertTrue(refusalOf("abc").contains("\$.decoded.probe"))
    }

    @Test
    fun `refuses NaN`() {
        assertTrue(refusalOf("NaN").contains("\$.decoded.probe"))
    }

    @Test
    fun `refuses a trailing-dot number`() {
        assertTrue(refusalOf("1.").contains("\$.decoded.probe"))
    }

    @Test
    fun `refuses a leading-dot number`() {
        assertTrue(refusalOf(".5").contains("\$.decoded.probe"))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `accepts RFC 8259 numeric spellings and NeutralValues wireText preserves them verbatim`() {
        for (literal in listOf("1e21", "1E+21", "-0", "0.1", "-996426215734216040")) {
            val doc = VectorDocument.parse(withProbe(JsonUnquotedLiteral(literal)), seed.file, loader.root)
            val wire = NeutralValues.wireText(checkNotNull(doc.decoded))
            assertTrue(wire.contains(literal), "expected \"$literal\" preserved verbatim in wireText output: $wire")
        }
    }

    @Test
    fun `accepts true, false, and null`() {
        for (literal in listOf(JsonPrimitive(true), JsonPrimitive(false), JsonNull)) {
            VectorDocument.parse(withProbe(literal), seed.file, loader.root)
        }
    }
}
