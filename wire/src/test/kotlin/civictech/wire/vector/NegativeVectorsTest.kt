package civictech.wire.vector

import civictech.cell.wire.WireCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Data-free checks on every `kind: negative` manifest vector — no vector id,
 * byte literal or classification word appears in this file, so a vector added
 * or dropped from `wire/corpus/negative/` is covered without a matching edit
 * here (task computenet-ncz.6.1).
 *
 * - [WIR1-I06]: an `unknown-ids` vector's own `contractId`/`methodId` (read
 *   from its `encoded.utf8`, never a Kotlin literal) both appear in the
 *   thrown rejection's message.
 * - [WIR1-C16]: every negative vector's `notes` names its source vector and
 *   mutation and says whether the rejection is normative or
 *   pinned-because-current.
 * - a manifest-drop guard: at least one vector per rejection word this task's
 *   table introduced still exists in the manifest.
 * - [WIR1-I04] (task computenet-ncz.5): an `unsupported-version` vector's
 *   rejection message names the offending version number, read from its own
 *   `encoded.utf8`.
 */
class NegativeVectorsTest {

    private val documents by lazy { VectorLoader.locate().documents() }

    private val negatives get() = documents.filter { it.kind == VectorKind.NEGATIVE }

    @TestFactory
    fun `an unknown-ids vector's rejection names both ids from its own bytes`(): List<DynamicTest> =
        negatives.filter { it.reject == "unknown-ids" }.map { doc ->
            DynamicTest.dynamicTest(doc.id) {
                val bytes = checkNotNull(doc.encoded) { "${doc.id}: unknown-ids vector without `encoded`" }.bytes
                val sourceUtf8 = checkNotNull(doc.encoded.utf8) { "${doc.id}: unknown-ids vector without `encoded.utf8`" }
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(sourceUtf8) as JsonObject
                val contractId = (parsed.getValue("contractId") as JsonPrimitive).content
                val methodId = (parsed.getValue("methodId") as JsonPrimitive).content
                val thrown = assertThrows<Throwable>("${doc.id}: decodeFrame(encoded) must throw") {
                    WireCodec.decodeFrame(bytes)
                }
                val message = thrown.message.orEmpty()
                assertTrue(
                    message.contains(contractId),
                    "${doc.id}: rejection message \"$message\" does not name contractId $contractId",
                )
                assertTrue(
                    message.contains(methodId),
                    "${doc.id}: rejection message \"$message\" does not name methodId $methodId",
                )
            }
        }

    @TestFactory
    fun `an unsupported-version vector's rejection names the version from its own bytes`(): List<DynamicTest> =
        negatives.filter { it.reject == "unsupported-version" }.map { doc ->
            DynamicTest.dynamicTest(doc.id) {
                val sourceUtf8 = checkNotNull(doc.encoded?.utf8) { "${doc.id}: unsupported-version vector without `encoded.utf8`" }
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(sourceUtf8) as JsonObject
                val version = (parsed.getValue("version") as JsonPrimitive).content
                val bytes = checkNotNull(doc.encoded).bytes
                val thrown = assertThrows<Throwable>("${doc.id}: decodeFrame(encoded) must throw") {
                    WireCodec.decodeFrame(bytes)
                }
                val message = thrown.message.orEmpty()
                assertTrue(
                    message.contains("unsupported wire version $version"),
                    "${doc.id}: rejection message \"$message\" does not contain `unsupported wire version $version`",
                )
            }
        }

    @TestFactory
    fun `every negative vector's notes names its source, mutation and rejection provenance`(): List<DynamicTest> =
        negatives.map { doc ->
            DynamicTest.dynamicTest(doc.id) {
                assertTrue(doc.notes.contains("source:"), "${doc.id}: notes must contain `source:` (SCHEMA.md/ncz.6-D1): ${doc.notes}")
                assertTrue(doc.notes.contains("mutation:"), "${doc.id}: notes must contain `mutation:` (SCHEMA.md/ncz.6-D1): ${doc.notes}")
                val lower = doc.notes.lowercase()
                assertTrue(
                    lower.contains("normative") || lower.contains("pinned-because-current"),
                    "${doc.id}: notes must say normative or pinned-because-current ([WIR1-C16]): ${doc.notes}",
                )
            }
        }

    @TestFactory
    fun `every rejection word this task introduced still has a manifest vector`(): List<DynamicTest> =
        listOf(
            "malformed", "truncated", "unknown-frame-type", "unknown-discriminator",
            "missing-required-field", "unknown-ids", "unsupported-version", "unknown-envelope-field",
        ).map { word ->
            DynamicTest.dynamicTest(word) {
                assertTrue(
                    negatives.any { it.reject == word },
                    "no manifest vector classifies as `$word` — was one silently dropped from manifest.json?",
                )
            }
        }
}
