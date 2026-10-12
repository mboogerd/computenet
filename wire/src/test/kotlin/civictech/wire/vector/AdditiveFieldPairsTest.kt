package civictech.wire.vector

import civictech.cell.wire.WireCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Named coverage for the additive envelope fields ([WIR1-C06], [WIR1-I19]).
 * The decode-only vectors pin bytes the current encoder does not produce; each
 * is tied to the positive absent twin so the test proves both the decoded
 * invocation and the encoder's omission policy without naming a PORT_PROTOCOL
 * vector from the sibling corpus task.
 */
class AdditiveFieldPairsTest {

    private data class DecodeTwin(
        val decodeId: String,
        val absentId: String,
        val decodeOnlyFields: Set<String>,
    )

    private val loader = VectorLoader.locate()

    @Test
    fun `each decode-only additive vector equals its absent twin and re-encodes without decode-only fields`() {
        val documents = loader.documents().associateBy { it.id }
        val pairs = listOf(
            DecodeTwin(
                "WV-ADDITIVE-NATURES-EMPTY-01",
                "WV-ADDITIVE-NATURES-ABSENT-01",
                setOf("natures"),
            ),
            DecodeTwin(
                "WV-ADDITIVE-NATURES-UNKNOWN-AXIS-01",
                "WV-ADDITIVE-NATURES-ABSENT-01",
                setOf("natures"),
            ),
            DecodeTwin(
                "WV-ADDITIVE-ROUTINGEPOCH-DECODE-01",
                "WV-ADDITIVE-ROUTINGEPOCH-ABSENT-01",
                setOf("routingEpoch"),
            ),
            DecodeTwin(
                "WV-ADDITIVE-SIGNED-DECODE-01",
                "WV-ADDITIVE-SIGNED-ABSENT-01",
                setOf("signature", "signerKeyId", "sigCounter", "notAfter"),
            ),
        )

        assertEquals(
            7,
            pairs.flatMap { listOf(it.decodeId, it.absentId) }.toSet().size,
            "[WIR1-C06] pair table must resolve four decode ids and three absent twins",
        )
        for (pair in pairs) {
            val decode = documents[pair.decodeId]
            val absent = documents[pair.absentId]
            assertTrue(decode != null, "${pair.decodeId} [WIR1-C06]: decode vector is missing from manifest")
            assertTrue(absent != null, "${pair.absentId} [WIR1-C06]: absent twin is missing from manifest")
            val decodeDoc = checkNotNull(decode)
            val absentDoc = checkNotNull(absent)
            assertEquals(
                VectorDirection.DECODE,
                decodeDoc.direction,
                "${pair.decodeId} [WIR1-I02]: a decode-only pair member must use direction decode",
            )
            val decodeInvocation = WireEquality.normalize(NeutralValues.frameOf(decodeDoc)).invocation
            val absentInvocation = WireEquality.normalize(NeutralValues.frameOf(absentDoc)).invocation
            assertEquals(
                absentInvocation,
                decodeInvocation,
                "${pair.decodeId} / ${pair.absentId} [WIR1-I02]: normalized invocations differ across the additive twin",
            )

            val reencoded = WireCodec.encode(NeutralValues.frameOf(decodeDoc).invocation).decodeToString()
            assertEquals(
                checkNotNull(absentDoc.encoded).utf8,
                reencoded,
                "${pair.decodeId} / ${pair.absentId} [WIR1-I19]: decode-only invocation does not re-encode as the absent twin",
            )
            for (field in pair.decodeOnlyFields) {
                assertFalse(
                    "\"$field\":" in reencoded,
                    "${pair.decodeId} / ${pair.absentId} [WIR1-I19]: re-encoding unexpectedly carries \"$field\"",
                )
            }
        }
    }

    @Test
    fun `every additive envelope field has a positive present and absent frame vector`() {
        val fields = listOf(
            "context",
            "protocolId",
            "protocolMessage",
            "edge",
            "routingEpoch",
            "natures",
            "signature",
            "signerKeyId",
            "sigCounter",
            "notAfter",
        )
        val frames = loader.documents().filter { it.kind == VectorKind.FRAME }
        for (field in fields) {
            val present = frames.filter { field in encodedObject(it).keys }
            val absent = frames.filter { field !in encodedObject(it).keys }
            assertTrue(
                present.isNotEmpty(),
                "[WIR1-C06] field \"$field\" has no positive frame carrying \"$field\":; " +
                    "present vector ids=${present.map { it.id }}",
            )
            assertTrue(
                absent.isNotEmpty(),
                "[WIR1-C06] field \"$field\" has no positive frame omitting \"$field\":; " +
                    "absent vector ids=${absent.map { it.id }}",
            )
        }
    }

    @Test
    fun `positive frame bytes omit version and top-level null envelope values`() {
        val frames = loader.documents().filter { it.kind == VectorKind.FRAME }
        for (doc in frames) {
            val encoded = encodedObject(doc)
            assertFalse(
                "version" in encoded.keys,
                "${doc.id} [WIR1-I19]: positive frame bytes must not carry the unemitted version key",
            )
            assertTrue(
                encoded.values.none { it is JsonNull },
                "${doc.id} [WIR1-I19]: positive frame bytes carry a top-level null envelope value",
            )
        }
    }

    private fun encodedObject(doc: VectorDocument): JsonObject {
        val encoded = checkNotNull(doc.encoded?.utf8) { "${doc.id}: positive frame has no encoded.utf8" }
        return Json.parseToJsonElement(encoded) as? JsonObject
            ?: error("${doc.id}: positive frame encoded.utf8 is not a JSON object")
    }
}
