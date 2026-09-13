package civictech.wire.vector

import civictech.cell.wire.WireCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The seams of the corpus driver's foundation (computenet-ncz.2.1): the loader
 * enforces `wire/corpus/SCHEMA.md`, and [NeutralValues] builds the codec value
 * a vector's `decoded` denotes.
 *
 * No per-vector data lives here: seeds are read through [VectorLoader], and
 * every refused document is a copy of a loaded seed altered by `JsonObject`
 * manipulation. The two map examples are SCHEMA.md's own literals, quoted as
 * the contract's worked examples, not vectors.
 */
class NeutralValuesTest {

    private val loader = VectorLoader.locate()

    private fun seed(id: String): VectorDocument =
        loader.documents().single { it.id == id }

    private fun text(element: JsonElement): String = NeutralValues.wireText(element)

    // --- E1: the two seed vectors ---------------------------------------------

    @Test
    fun `E1 - each seed vector's decoded builds a value whose encoding equals its pinned bytes`() {
        val ids = loader.documents().map { it.id }
        assertTrue("WV-PORT-API-STALL-SUSPENDED-01" in ids && "WV-PORT-API-STALL-RESUME-01" in ids, "seeds missing from manifest: $ids")
        for (id in listOf("WV-PORT-API-STALL-SUSPENDED-01", "WV-PORT-API-STALL-RESUME-01")) {
            val doc = seed(id)
            val pinned = checkNotNull(doc.encoded?.utf8) { "$id has no encoded.utf8" }
            assertEquals(pinned, NeutralValues.encodedBytesOf(doc).decodeToString(), "$id: encode(decoded) != encoded.utf8")
            assertEquals(
                NeutralValues.frameOf(doc).frame,
                WireCodec.decodeFrame(pinned.toByteArray(Charsets.UTF_8)).frame,
                "$id: decodeFrame(encoded).frame != frameOf(decoded).frame",
            )
        }
    }

    // --- rewrite pinned by SCHEMA.md's own examples ------------------------------

    @Test
    fun `structured-key MapDelta example from SCHEMA Maps rewrites to the exact text SCHEMA prints`() {
        // SCHEMA.md §The neutral `decoded` grammar → §Maps, "Structured-key map" (literal quoted verbatim).
        val neutral = """
            {
              "type": "MapDelta",
              "fields": {
                "puts": {
                  "entries": [
                    {"key": {"type": "kotlin.String", "value": "a"}, "value": {"type": "kotlin.Long", "value": 1}},
                    {"key": {"type": "kotlin.String", "value": "b"}, "value": {"type": "kotlin.Long", "value": 2}}
                  ]
                },
                "removals": [{"type": "kotlin.String", "value": "c"}]
              }
            }
        """.trimIndent()
        val printed =
            """["MapDelta",{"puts":[["kotlin.String","a"],["kotlin.Long",1],["kotlin.String","b"],["kotlin.Long",2]],"removals":[["kotlin.String","c"]]}]"""
        assertEquals(printed, text(Json.parseToJsonElement(neutral)))
    }

    @Test
    fun `primitive-keyed WatermarkDelta example from SCHEMA Maps rewrites to a substring of the pre-KE3 fixture`() {
        // SCHEMA.md §The neutral `decoded` grammar → §Maps, "Primitive-keyed map" (literal quoted verbatim).
        val neutral = """
            {
              "type": "WatermarkDelta",
              "fields": {
                "rows": {
                  "00000000-0000-0000-0000-0000000000a1": {
                    "00000000-0000-0000-0000-0000000000b1": 3,
                    "00000000-0000-0000-0000-0000000000b2": 1
                  },
                  "00000000-0000-0000-0000-0000000000a2": {"00000000-0000-0000-0000-0000000000b1": 2}
                },
                "closed": ["00000000-0000-0000-0000-0000000000a2"],
                "suspended": {"00000000-0000-0000-0000-0000000000a1": 1},
                "members": ["00000000-0000-0000-0000-0000000000a1", "00000000-0000-0000-0000-0000000000a2"]
              }
            }
        """.trimIndent()
        val rewritten = text(Json.parseToJsonElement(neutral))
        val fixture = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/watermark-delta-pre-ke3-full.bin")) {
            "fixture not found on classpath: fixtures/watermark-delta-pre-ke3-full.bin"
        }.use { it.readBytes() }.decodeToString()
        assertTrue(rewritten in fixture, "rewrite\n  $rewritten\nis not a substring of the fixture\n  $fixture")
    }

    @Test
    fun `numeric literals keep their spelling through the rewrite`() {
        val neutral = """{"a": -996426215734216040, "b": 1e21, "c": 1.0, "d": {"type": "kotlin.Double", "value": 0.1}}"""
        assertEquals(
            """{"a":-996426215734216040,"b":1e21,"c":1.0,"d":["kotlin.Double",0.1]}""",
            text(Json.parseToJsonElement(neutral)),
        )
    }

    // --- refusals, each on a copy of a loaded seed --------------------------------

    private fun refusal(copy: JsonObject, from: VectorDocument): VectorSchemaException =
        assertThrows { VectorDocument.parse(copy, from.file, loader.root) }

    private fun JsonObject.with(vararg changes: Pair<String, JsonElement?>): JsonObject {
        val m = LinkedHashMap(this)
        for ((k, v) in changes) if (v == null) m.remove(k) else m[k] = v
        return JsonObject(m)
    }

    @Test
    fun `an unmodified seed copy parses, so each refusal below is caused by its one change`() {
        val doc = seed("WV-PORT-API-STALL-SUSPENDED-01")
        assertEquals(doc.id, VectorDocument.parse(doc.source, doc.file, loader.root).id)
    }

    @Test
    fun `a document with an unknown top-level key is refused naming the key and the file`() {
        val doc = seed("WV-PORT-API-STALL-SUSPENDED-01")
        val e = refusal(doc.source.with("line" to JsonPrimitive("x")), doc)
        assertTrue("line" in e.message!! && doc.file.fileName.toString() in e.message!!, e.message)
    }

    @Test
    fun `a document whose base64 disagrees with utf8 by one character is refused`() {
        val doc = seed("WV-PORT-API-STALL-SUSPENDED-01")
        val encoded = doc.source.getValue("encoded") as JsonObject
        val b64 = (encoded.getValue("base64") as JsonPrimitive).content
        // 'e' -> 'f' on the first character: still valid base64, different first byte.
        check(b64.startsWith("e")) { "seed base64 no longer starts with 'e': $b64" }
        val altered = encoded.with("base64" to JsonPrimitive("f" + b64.substring(1)))
        val e = refusal(doc.source.with("encoded" to altered), doc)
        assertTrue("base64" in e.message!! && "utf8" in e.message!! && doc.file.fileName.toString() in e.message!!, e.message)
    }

    @Test
    fun `a negative document without direction is refused`() {
        val doc = seed("WV-PORT-API-STALL-SUSPENDED-01")
        val copy = doc.source.with(
            "kind" to JsonPrimitive("negative"),
            "decoded" to null,
            "expect" to JsonObject(mapOf("reject" to JsonPrimitive("malformed"))),
        )
        // Control: the same copy WITH direction parses, so the refusal is direction's alone.
        VectorDocument.parse(copy.with("direction" to JsonPrimitive("decode")), doc.file, loader.root)
        val e = refusal(copy, doc)
        assertTrue("direction" in e.message!! && doc.file.fileName.toString() in e.message!!, e.message)
    }

    @Test
    fun `a positive document with direction encode is refused`() {
        val doc = seed("WV-PORT-API-STALL-SUSPENDED-01")
        val e = refusal(doc.source.with("direction" to JsonPrimitive("encode")), doc)
        assertTrue("direction" in e.message!! && "encode" in e.message!! && doc.file.fileName.toString() in e.message!!, e.message)
    }

    // --- loader ------------------------------------------------------------------

    @Test
    fun `the corpus root resolves from wire and from the repository root, and an explicit root is accepted`() {
        val root = loader.root
        assertEquals(root, VectorLoader.locateRoot(root.parent)) // wire/
        assertEquals(root, VectorLoader.locateRoot(root.parent.parent)) // repository root
        assertEquals(loader.documents().map { it.id }, VectorLoader(root).documents().map { it.id })
        assertTrue(loader.pending().isNotEmpty())
    }

    private fun copyCorpus(dir: Path): Path {
        for (entry in loader.entries()) {
            val target = dir.resolve(entry.file)
            Files.createDirectories(target.parent)
            Files.copy(loader.root.resolve(entry.file), target)
        }
        Files.copy(loader.root.resolve("manifest.json"), dir.resolve("manifest.json"))
        return dir
    }

    @Test
    fun `a manifest entry whose kind disagrees with its document is refused`(@TempDir tmp: Path) {
        val root = copyCorpus(tmp)
        val manifest = root.resolve("manifest.json")
        val original = Files.readString(manifest)
        VectorLoader(root).documents() // control: the verbatim copy loads
        val first = loader.entries().first()
        val needle = "\"kind\": \"${first.kind}\""
        check(needle in original) { "manifest spelling changed; cannot find $needle" }
        Files.writeString(manifest, original.replaceFirst(needle, "\"kind\": \"negative\""))
        val e = assertThrows<VectorSchemaException> { VectorLoader(root).documents() }
        assertTrue("kind" in e.message!! && first.id in e.message!!, e.message)
    }

    @Test
    fun `a manifest entry naming a missing file is refused`(@TempDir tmp: Path) {
        val root = copyCorpus(tmp)
        val first = loader.entries().first()
        Files.delete(root.resolve(first.file))
        val e = assertThrows<VectorSchemaException> { VectorLoader(root).documents() }
        assertTrue("missing" in e.message!! && first.id in e.message!!, e.message)
    }
}
