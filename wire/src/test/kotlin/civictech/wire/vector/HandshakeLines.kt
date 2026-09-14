package civictech.wire.vector

import civictech.cell.link.PeerId
import civictech.wire.Hello2
import civictech.wire.LEGACY_HELLO_PREFIX
import civictech.wire.Proof
import civictech.wire.encodeHello2
import civictech.wire.encodeProof
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.UUID

/**
 * Builds the text line a `handshake-text` vector's `decoded` denotes
 * (`wire/corpus/SCHEMA.md` §Kinds, handshake table; decision ncz.2-D4).
 *
 * - `HELLO`  → [LEGACY_HELLO_PREFIX] + mirrorRef + (" " + peerName)?, no terminator.
 *   Built from the `HelloProtocol.kt` constant, never a `"HELLO "` literal:
 *   this builder is what feature computenet-ncz.5 ties to
 *   `WsTransport.Session.hello()` (which is `internal` and spawns a mirror, so
 *   it is not reached from here).
 * - `HELLO2` → [encodeHello2] of the [Hello2] the fields describe.
 * - `PROOF`  → [encodeProof] of the [Proof] the fields describe.
 *
 * Token fields are base64url text (SCHEMA.md: "base64url stays base64url"); they
 * are decoded to bytes and re-encoded by the production encoder, so a vector
 * whose token is not what the encoder emits (padding, say) is a red vector.
 * `mirrorRef` is parsed as a [UUID] and rendered by [UUID.toString], which is
 * how a JVM session writes one.
 */
object HandshakeLines {

    const val HELLO = "HELLO"
    const val HELLO2 = "HELLO2"
    const val PROOF = "PROOF"

    /** The exact line (no terminator) [doc]'s `decoded` denotes. */
    fun lineOf(doc: VectorDocument): String {
        val (type, fields) = shape(doc)
        return when (type) {
            HELLO -> {
                allowOnly(doc, type, fields, required = setOf("mirrorRef"), optional = setOf("peerName"))
                val mirrorRef = uuid(doc, fields, "mirrorRef")
                val peerName = fields["peerName"]?.let { str(doc, fields, "peerName") }
                LEGACY_HELLO_PREFIX + mirrorRef + (peerName?.let { " $it" } ?: "")
            }
            HELLO2 -> encodeHello2(hello2(doc, fields))
            PROOF -> encodeProof(proof(doc, fields))
            else -> refuse(doc, "handshake `type` \"$type\" is not HELLO | HELLO2 | PROOF (SCHEMA.md §Kinds)")
        }
    }

    /** The [Hello2] or [Proof] [lineOf] built for [doc], or null for a legacy `HELLO` (no message object exists). */
    fun messageOf(doc: VectorDocument): Any? {
        val (type, fields) = shape(doc)
        return when (type) {
            HELLO -> null
            HELLO2 -> hello2(doc, fields)
            PROOF -> proof(doc, fields)
            else -> refuse(doc, "handshake `type` \"$type\" is not HELLO | HELLO2 | PROOF (SCHEMA.md §Kinds)")
        }
    }

    /** The handshake keyword of [doc]'s `decoded` (`HELLO`, `HELLO2` or `PROOF`). */
    fun typeOf(doc: VectorDocument): String = shape(doc).first

    private fun hello2(doc: VectorDocument, fields: JsonObject): Hello2 {
        allowOnly(doc, HELLO2, fields, required = setOf("mirrorRef", "claimedPeerId", "publicKeySpki", "nonce"))
        return Hello2(
            mirrorRef = uuid(doc, fields, "mirrorRef"),
            claimedPeerId = PeerId(str(doc, fields, "claimedPeerId")),
            publicKeySpki = base64url(doc, fields, "publicKeySpki"),
            nonce = base64url(doc, fields, "nonce"),
        )
    }

    private fun proof(doc: VectorDocument, fields: JsonObject): Proof {
        allowOnly(doc, PROOF, fields, required = setOf("signature"))
        return Proof(base64url(doc, fields, "signature"))
    }

    private fun shape(doc: VectorDocument): Pair<String, JsonObject> {
        if (doc.kind != VectorKind.HANDSHAKE_TEXT) refuse(doc, "kind `${doc.kind.word}` is not `handshake-text`")
        val decoded = doc.decoded as? JsonObject
            ?: refuse(doc, "a handshake-text `decoded` must be an object {\"type\", \"fields\"}")
        if (decoded.keys != setOf("type", "fields")) {
            refuse(doc, "a handshake-text `decoded` carries exactly {type, fields}, found ${decoded.keys}")
        }
        val type = (decoded["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: refuse(doc, "handshake `decoded.type` must be a string")
        val fields = decoded["fields"] as? JsonObject ?: refuse(doc, "handshake `decoded.fields` must be an object")
        return type to fields
    }

    private fun allowOnly(doc: VectorDocument, type: String, fields: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val missing = required - fields.keys
        val extra = fields.keys - required - optional
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            refuse(doc, "$type fields: missing $missing, not allowed $extra (SCHEMA.md §Kinds handshake table)")
        }
    }

    private fun str(doc: VectorDocument, fields: JsonObject, key: String): String =
        (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: refuse(doc, "handshake field `$key` must be a JSON string")

    private fun uuid(doc: VectorDocument, fields: JsonObject, key: String): UUID =
        try {
            UUID.fromString(str(doc, fields, key))
        } catch (e: IllegalArgumentException) {
            refuse(doc, "handshake field `$key` is not a UUID: ${e.message}")
        }

    private fun base64url(doc: VectorDocument, fields: JsonObject, key: String): ByteArray =
        try {
            Base64.getUrlDecoder().decode(str(doc, fields, key))
        } catch (e: IllegalArgumentException) {
            refuse(doc, "handshake field `$key` is not base64url: ${e.message}")
        }

    private fun refuse(doc: VectorDocument, rule: String): Nothing = throw VectorSchemaException("${doc.file}: $rule")
}
