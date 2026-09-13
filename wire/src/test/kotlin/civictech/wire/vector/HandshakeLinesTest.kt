package civictech.wire.vector

import civictech.cell.link.PeerId
import civictech.wire.HELLO2_PREFIX
import civictech.wire.HELLO2_TOKEN_COUNT
import civictech.wire.Hello2
import civictech.wire.HelloParse
import civictech.wire.LEGACY_HELLO_PREFIX
import civictech.wire.MIN_HELLO_NONCE_BYTES
import civictech.wire.PROOF_PREFIX
import civictech.wire.Proof
import civictech.wire.parseHello2
import civictech.wire.parseProof
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.UUID

/**
 * [HandshakeLines] and the driver's `handshake-text` arm, on documents built
 * here — no `handshake-text` vector exists at this base.
 *
 * The values are pinned fixtures for a line builder, not corpus vectors;
 * cryptographic validity is out of scope (ncz.5-D1). The claimed id has the
 * key-derived shape (`ed25519:` + base64url of 32 bytes) because [parseHello2]
 * refuses any other form as `CLAIMED_ID_NOT_KEY_DERIVED`.
 *
 * Every expected line is assembled from the `HelloProtocol.kt` prefixes and the
 * token TEXT (SCHEMA.md: token values are the exact token text), independently
 * of [HandshakeLines]' decode-then-encode path.
 */
class HandshakeLinesTest {

    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val root = VectorLoader.locateRoot()

    private val mirrorRef = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val claimedPeerId = "ed25519:" + b64url.encodeToString(ByteArray(32) { it.toByte() })
    private val spki = ByteArray(4) { (0xA0 + it).toByte() }
    private val nonce = ByteArray(MIN_HELLO_NONCE_BYTES) { (0x10 + it).toByte() }
    private val signature = ByteArray(8) { (0xF0 + it).toByte() }

    private fun handshake(id: String, type: String, fields: JsonObject, line: String, direction: String? = null): VectorDocument {
        val obj = buildJsonObject {
            put("id", id)
            put("title", "HandshakeLinesTest fixture")
            put("category", "handshake")
            put("kind", "handshake-text")
            putJsonArray("covers") { add(JsonPrimitive("WIR1-C07")) }
            put("codecVersion", 2)
            put("messageKind", "text")
            if (direction != null) put("direction", direction)
            putJsonObject("decoded") {
                put("type", type)
                put("fields", fields)
            }
            putJsonObject("encoded") {
                put("utf8", line)
                put("base64", Base64.getEncoder().encodeToString(line.toByteArray(Charsets.UTF_8)))
            }
            put("notes", "built in HandshakeLinesTest")
        }
        return VectorDocument.parse(obj, root.resolve("handshake/$id.json"), root)
    }

    private fun hello(peerName: String?): Pair<VectorDocument, String> {
        val line = LEGACY_HELLO_PREFIX + mirrorRef + (peerName?.let { " $it" } ?: "")
        val fields = buildJsonObject {
            put("mirrorRef", mirrorRef.toString())
            if (peerName != null) put("peerName", peerName)
        }
        return handshake(if (peerName == null) "WV-HELLO-LEGACY-BARE-01" else "WV-HELLO-LEGACY-NAMED-01", "HELLO", fields, line) to line
    }

    private fun hello2Tokens(): List<String> =
        listOf(mirrorRef.toString(), claimedPeerId, b64url.encodeToString(spki), b64url.encodeToString(nonce))

    private fun hello2Doc(line: String = HELLO2_PREFIX + hello2Tokens().joinToString(" ")): VectorDocument {
        val (m, c, k, n) = hello2Tokens()
        val fields = buildJsonObject {
            put("mirrorRef", m)
            put("claimedPeerId", c)
            put("publicKeySpki", k)
            put("nonce", n)
        }
        return handshake("WV-HELLO-HELLO2-01", "HELLO2", fields, line)
    }

    private fun proofDoc(): VectorDocument {
        val token = b64url.encodeToString(signature)
        return handshake("WV-HELLO-PROOF-01", "PROOF", buildJsonObject { put("signature", token) }, PROOF_PREFIX + token)
    }

    @Test
    fun `legacy HELLO without a name is prefix plus mirrorRef, no trailing space, no terminator`() {
        val (doc, expected) = hello(null)
        val line = HandshakeLines.lineOf(doc)
        assertEquals(expected, line)
        assertEquals(LEGACY_HELLO_PREFIX + mirrorRef, line)
        assertFalse(line.endsWith(" "), "a nameless legacy HELLO must not end in a space: \"$line\"")
        assertFalse(line.contains('\n'))
        assertNull(HandshakeLines.messageOf(doc))
    }

    @Test
    fun `legacy HELLO with a name appends one space and the name`() {
        val (doc, expected) = hello("alpha")
        assertEquals(expected, HandshakeLines.lineOf(doc))
        assertEquals("$LEGACY_HELLO_PREFIX$mirrorRef alpha", HandshakeLines.lineOf(doc))
    }

    @Test
    fun `HELLO2 line has exactly HELLO2_TOKEN_COUNT tokens and parses back to an equal Hello2`() {
        val doc = hello2Doc()
        val line = HandshakeLines.lineOf(doc)
        assertEquals(doc.encoded?.utf8, line)
        assertTrue(line.startsWith(HELLO2_PREFIX))
        assertEquals(HELLO2_TOKEN_COUNT, line.substring(HELLO2_PREFIX.length).split(" ").size)
        val built = Hello2(mirrorRef, PeerId(claimedPeerId), spki, nonce)
        assertEquals(built, HandshakeLines.messageOf(doc))
        assertEquals(HelloParse.Ok(built), parseHello2(line))
    }

    @Test
    fun `PROOF line parses back to an equal Proof`() {
        val doc = proofDoc()
        val line = HandshakeLines.lineOf(doc)
        assertEquals(doc.encoded?.utf8, line)
        assertEquals(Proof(signature), HandshakeLines.messageOf(doc))
        assertEquals(HelloParse.Ok(Proof(signature)), parseProof(line))
    }

    @Test
    fun `driver passes every handshake shape whose line matches`() {
        WireVectorConformanceTest.verify(hello(null).first)
        WireVectorConformanceTest.verify(hello("alpha").first)
        WireVectorConformanceTest.verify(hello2Doc())
        WireVectorConformanceTest.verify(proofDoc())
    }

    @Test
    fun `driver reds a handshake vector whose pinned line differs from the built one`() {
        val trailing = hello2Doc(HELLO2_PREFIX + hello2Tokens().joinToString(" ") + " ")
        val red = assertThrows<AssertionError> { WireVectorConformanceTest.verify(trailing) }
        assertTrue(red.message!!.contains("lineOf"), red.message)
    }

    @Test
    fun `an unknown handshake keyword is refused, not guessed`() {
        val doc = handshake("WV-HELLO-BYE-01", "BYE", buildJsonObject {}, "BYE")
        assertThrows<VectorSchemaException> { HandshakeLines.lineOf(doc) }
    }
}
