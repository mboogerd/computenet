package civictech.identity.anchor

import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the `AnnouncementCanonicalBytesSurrogateTest` regression case for
 * [statementSigningBytes] (task `computenet-5y8t.2.1`, `computenet-9qgg`):
 * `"\uD800"` (a lone high surrogate) and `"\uDC00"` (a lone low surrogate)
 * are both refused, while `"?"` — the character `String.toByteArray(UTF_8)`
 * would otherwise have substituted for either — is an ordinary well-formed
 * name and encodes normally. So none of the three can ever share a
 * signature: exactly one of them is signable at all.
 */
class IdentityStatementCanonicalBytesSurrogateTest {

    private val base = IdentityStatement(
        name = PeerId("alice"),
        keyId = KeyId("ed25519:Zm9vYmFy"),
        issuer = IssuerId("ed25519:YW5jaG9y"),
        issuance = 1L,
        notBefore = 1_000L,
        notAfter = 2_000L,
        signature = ByteArray(0),
    )

    @Test
    fun `a lone high surrogate in name is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            statementSigningBytes(base.copy(name = PeerId("\uD800")))
        }
        kotlin.test.assertTrue(failure.message!!.contains("name"), failure.message)
    }

    @Test
    fun `a lone low surrogate in name is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            statementSigningBytes(base.copy(name = PeerId("\uDC00")))
        }
        kotlin.test.assertTrue(failure.message!!.contains("name"), failure.message)
    }

    @Test
    fun `a question mark in name does not share bytes with either surrogate, and encodes`() {
        // Both surrogates are refused, never encoded — so there is nothing to
        // compare "?" against for them; the claim is that "?" is a completely
        // ordinary, encodable name, unlike either surrogate.
        assertFailsWith<IllegalArgumentException> { statementSigningBytes(base.copy(name = PeerId("\uD800"))) }
        assertFailsWith<IllegalArgumentException> { statementSigningBytes(base.copy(name = PeerId("\uDC00"))) }

        val encoded = statementSigningBytes(base.copy(name = PeerId("?")))
        // tag (4+35) then name: 4-byte length prefix of 1, then the byte 0x3f.
        val nameOffset = 4 + IDENTITY_BINDING_DOMAIN_TAG.toByteArray(Charsets.UTF_8).size
        assertEquals(0, encoded[nameOffset].toInt())
        assertEquals(0, encoded[nameOffset + 1].toInt())
        assertEquals(0, encoded[nameOffset + 2].toInt())
        assertEquals(1, encoded[nameOffset + 3].toInt())
        assertEquals(0x3f, encoded[nameOffset + 4].toInt())
    }
}
