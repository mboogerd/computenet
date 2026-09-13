package civictech.identity.anchor

import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.UnboundReason
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.fingerprint
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * The one-token hello encoding of an `IdentityStatement` (task
 * `computenet-5y8t.3.1`, feature decision 5y8t.F3-D3).
 *
 * The line this suite draws is "malformed token" (null from the decoder)
 * versus "well-formed token carrying a statement that does not vouch"
 * (decodes, then the binding refuses). **No test here is named or readable as
 * stolen-key resistance or revocation**: `[DSC1-NV-01]` stays EXPLICITLY
 * UNVERIFIED.
 */
class IdentityStatementTokenTest {

    private val anchorA = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("anchor-A".toByteArray())))
    private val k: KeyId = fingerprint(DeterministicKeySource.keyPairFromSeed("peer-k".toByteArray()).public)
    private val alice = PeerId("alice")

    private val statement: IdentityStatement = anchorA.bind(alice, k, 3, NOT_BEFORE, NOT_AFTER)
    private val binding = AnchorVouchedBinding(mapOf(anchorA.issuerId to anchorA.publicKey), clock = { 1_500L })

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** The token layout assembled by hand from raw field bytes, so a test can put anything in any field. */
    private fun rawToken(
        tag: ByteArray = IDENTITY_BINDING_DOMAIN_TAG.toByteArray(),
        name: ByteArray = statement.name.name.toByteArray(),
        keyId: ByteArray = statement.keyId.name.toByteArray(),
        issuer: ByteArray = statement.issuer.name.toByteArray(),
        longs: List<Long> = listOf(statement.issuance, statement.notBefore, statement.notAfter),
        signature: ByteArray = statement.signature,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) { for (s in 24 downTo 0 step 8) out.write((v ushr s) and 0xFF) }
        fun field(b: ByteArray) { int(b.size); out.write(b) }
        field(tag); field(name); field(keyId); field(issuer)
        for (l in longs) for (s in 56 downTo 0 step 8) out.write(((l ushr s) and 0xFF).toInt())
        out.write(signature)
        return out.toByteArray()
    }

    private fun assertDecodesNull(token: String, what: String) {
        assertNull(assertDoesNotThrow { decodeIdentityStatementToken(token) }, what)
    }

    @Test
    fun `a statement minted by AnchorIssuer round-trips field for field`() {
        val decoded = assertNotNull(decodeIdentityStatementToken(encodeIdentityStatementToken(statement)))

        assertEquals(statement.name, decoded.name)
        assertEquals(statement.keyId, decoded.keyId)
        assertEquals(statement.issuer, decoded.issuer)
        assertEquals(statement.issuance, decoded.issuance)
        assertEquals(statement.notBefore, decoded.notBefore)
        assertEquals(statement.notAfter, decoded.notAfter)
        assertContentEquals(statement.signature, decoded.signature)
    }

    @Test
    fun `the token is the unpadded base64url of the signing bytes followed by the signature`() {
        val token = encodeIdentityStatementToken(statement)

        assertEquals(b64(statementSigningBytes(statement) + statement.signature), token)
        assertEquals(b64(rawToken()), token, "the hand-assembled layout matches")
        for (c in listOf(' ', '=', '+', '/')) assertFalse(c in token, "token contains '$c'")
    }

    @Test
    fun `a decoded token resolves Bound through a real AnchorVouchedBinding`() {
        val decoded = assertNotNull(decodeIdentityStatementToken(encodeIdentityStatementToken(statement)))

        assertEquals(IdentityResolution.Bound(alice, anchorA.issuerId, decoded), binding.resolve(k, listOf(decoded)))
    }

    @Test
    fun `a flipped byte inside the signature still decodes and then resolves Unbound BAD_SIGNATURE`() {
        val bytes = rawToken()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        val decoded = assertNotNull(decodeIdentityStatementToken(b64(bytes)), "the codec does not verify")
        assertEquals(IdentityResolution.Unbound(UnboundReason.BAD_SIGNATURE), binding.resolve(k, listOf(decoded)))
    }

    @Test
    fun `a v2 tag spliced into otherwise valid bytes is refused`() {
        val v2 = IDENTITY_BINDING_DOMAIN_TAG.replace("/v1", "/v2")
        assertEquals(IDENTITY_BINDING_DOMAIN_TAG.length, v2.length, "precondition: same-length splice")
        assertDecodesNull(b64(rawToken(tag = v2.toByteArray())), "v2 tag")
    }

    @Test
    fun `a truncated buffer is refused at every length short of the signature`() {
        val bytes = rawToken()
        val signedLength = statementSigningBytes(statement).size
        for (length in 0..signedLength) {
            assertDecodesNull(b64(bytes.copyOf(length)), "truncated to $length of ${bytes.size}")
        }
    }

    @Test
    fun `a flipped byte in a length prefix is refused`() {
        val bytes = rawToken()
        // Byte 0 is the high byte of the tag's length prefix: flipping it claims ~2^31 bytes.
        assertDecodesNull(b64(bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }), "negative tag length")
        assertDecodesNull(b64(bytes.copyOf().also { it[1] = (it[1].toInt() xor 0x01).toByte() }), "overrun tag length")
        // The name's length prefix sits right after the tag field.
        val namePrefix = Int.SIZE_BYTES + IDENTITY_BINDING_DOMAIN_TAG.length
        assertDecodesNull(
            b64(bytes.copyOf().also { it[namePrefix + 2] = (it[namePrefix + 2].toInt() xor 0x01).toByte() }),
            "overrun name length",
        )
    }

    @Test
    fun `an invalid UTF-8 name field is refused, not replaced`() {
        for (bad in listOf(byteArrayOf(0x61, 0xFF.toByte()), byteArrayOf(0xC3.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            assertDecodesNull(b64(rawToken(name = bad)), "name bytes ${bad.joinToString { "%02x".format(it) }}")
        }
    }

    @Test
    fun `an empty signature is refused by the decoder and by the encoder`() {
        assertDecodesNull(b64(rawToken(signature = ByteArray(0))), "empty signature")
        assertFailsWith<IllegalArgumentException> {
            encodeIdentityStatementToken(statement.copy(signature = ByteArray(0)))
        }
    }

    @Test
    fun `an encoder input that is not well-formed UTF-16 throws what statementSigningBytes throws`() {
        assertFailsWith<IllegalArgumentException> {
            encodeIdentityStatementToken(statement.copy(name = PeerId("alice\uD800")))
        }
    }

    @Test
    fun `a token that is not canonical unpadded base64url is refused`() {
        val token = encodeIdentityStatementToken(statement)
        val std = Base64.getEncoder().encodeToString(rawToken())

        assertDecodesNull("$token=", "trailing padding")
        assertDecodesNull(Base64.getUrlEncoder().encodeToString(rawToken() + byteArrayOf(1)), "padded url encoding")
        if ('+' in std || '/' in std) assertDecodesNull(std.trimEnd('='), "standard alphabet")
        assertDecodesNull(token.replaceRange(10, 11, "+"), "a '+' inside")
        assertDecodesNull(token.replaceRange(10, 11, "/"), "a '/' inside")
        assertDecodesNull(token.replaceRange(10, 11, " "), "a space inside")
        assertDecodesNull("$token\n", "trailing newline")
        val oneMod4 = token + "A".repeat((1 - token.length).mod(4))
        assertEquals(1, oneMod4.length % 4, "precondition")
        assertDecodesNull(oneMod4, "a length no encoder produces")
        assertDecodesNull("", "empty token")
        assertDecodesNull("not base64url!", "punctuation")
    }

    @Test
    fun `non-zero trailing bits are refused so one statement has one token`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var checked = 0
        // Only a byte length not divisible by 3 leaves spare bits in the last symbol;
        // pad the signature so both such remainders are exercised.
        for (extra in 0..2) {
            val bytes = rawToken(signature = statement.signature + ByteArray(extra) { 7 })
            if (bytes.size % 3 == 0) continue
            val token = b64(bytes)
            assertNotNull(decodeIdentityStatementToken(token), "precondition: canonical token decodes")
            val bumped = token.dropLast(1) + alphabet[alphabet.indexOf(token.last()) or 1]
            assertFalse(bumped == token, "precondition: the last symbol's spare bits were zero")
            assertDecodesNull(bumped, "non-zero trailing bits, byte length ${bytes.size}")
            checked++
        }
        assertEquals(2, checked)
    }

    private companion object {
        const val NOT_BEFORE = 1_000L
        const val NOT_AFTER = 2_000L
    }
}
