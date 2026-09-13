package civictech.identity

import civictech.cell.link.PeerId
import civictech.identity.anchor.AnchorIssuer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The named [PeerIdentity] (task `computenet-5y8t.3.2`, epic decision D9):
 * an explicit name plus the anchor-signed statements held for it.
 *
 * Nothing here verifies a statement's signature or compares its `issuance` —
 * the constructor holds statements, it does not judge them. No test is named
 * or readable as stolen-key resistance, revocation or rotation:
 * `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED.
 */
class PeerIdentityNamedTest {

    private val anchorA = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("named-anchor-A".toByteArray())))
    private val keyPair = DeterministicKeySource.keyPairFromSeed("named-peer".toByteArray())
    private val otherKeyPair = DeterministicKeySource.keyPairFromSeed("named-other-peer".toByteArray())
    private val keyId = fingerprint(keyPair.public)
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    private fun forAlice() = anchorA.bind(alice, keyId, 1, NOT_BEFORE, NOT_AFTER)

    @Test
    fun `the named constructor makes peerId the name, keeps keyId, and holds the statements given`() {
        val statements = listOf(forAlice(), anchorA.bind(alice, keyId, 2, NOT_BEFORE, NOT_AFTER))

        val named = PeerIdentity(keyPair, alice, statements)

        assertEquals(alice, named.peerId)
        assertEquals(keyId, named.keyId)
        assertEquals(statements.size, named.statements.size)
        statements.zip(named.statements).forEach { (given, held) -> assertSame(given, held) }
    }

    @Test
    fun `the four-argument constructor is the same named identity`() {
        val statement = forAlice()
        val named = PeerIdentity(keyPair.public, keyPair.private, alice, listOf(statement))
        assertEquals(alice, named.peerId)
        assertEquals(keyId, named.keyId)
        assertSame(statement, named.statements.single())
    }

    @Test
    fun `control - the same keypair unnamed keeps its key-derived peerId and holds no statements`() {
        val unnamed = PeerIdentity(keyPair)
        assertEquals(keyId.name, unnamed.peerId.name)
        assertNotEquals(alice, unnamed.peerId)
        assertTrue(unnamed.statements.isEmpty())
        assertTrue(PeerIdentity(keyPair.public, keyPair.private).statements.isEmpty())
    }

    @Test
    fun `an empty statement list is refused at construction`() {
        val e = assertFailsWith<IllegalArgumentException> { PeerIdentity(keyPair, alice, emptyList()) }
        assertNoKeyBytes(e.message)
        assertTrue(e.message!!.contains("alice"), e.message)
    }

    @Test
    fun `a statement bound to another key is refused at construction, naming ids only`() {
        val otherKeyId = fingerprint(otherKeyPair.public)
        val foreign = anchorA.bind(alice, otherKeyId, 1, NOT_BEFORE, NOT_AFTER)

        val e = assertFailsWith<IllegalArgumentException> { PeerIdentity(keyPair, alice, listOf(forAlice(), foreign)) }

        assertTrue(e.message!!.contains(otherKeyId.name), e.message)
        assertTrue(e.message!!.contains(keyId.name), e.message)
        assertNoKeyBytes(e.message)
    }

    @Test
    fun `a statement naming another peer is refused at construction, naming ids only`() {
        val forBob = anchorA.bind(bob, keyId, 1, NOT_BEFORE, NOT_AFTER)

        val e = assertFailsWith<IllegalArgumentException> { PeerIdentity(keyPair, alice, listOf(forBob)) }

        assertTrue(e.message!!.contains("bob"), e.message)
        assertTrue(e.message!!.contains("alice"), e.message)
        assertNoKeyBytes(e.message)
    }

    @Test
    fun `a signature under the named identity verifies under its public key`() {
        val named = PeerIdentity(keyPair, alice, listOf(forAlice()))
        val message = "named signing bytes".toByteArray()
        val signature = named.sign(message)
        assertTrue(Ed25519.verify(keyPair.public, message, signature))
        assertTrue(named.verify(message, signature))
    }

    @Test
    fun `toString names the peer and carries no private key encoding`() {
        val rendered = PeerIdentity(keyPair, alice, listOf(forAlice())).toString()
        assertTrue(rendered.contains("alice"), rendered)
        assertNoKeyBytes(rendered)
    }

    /** No rendering of the private key (or the public one) in any of the forms a leak would take. */
    private fun assertNoKeyBytes(text: String?) {
        val t = text ?: return
        for (encoded in listOf(keyPair.private.encoded, keyPair.public.encoded, otherKeyPair.private.encoded)) {
            assertFalse(t.contains(Base64.getEncoder().encodeToString(encoded)), "base64 key bytes in: $t")
            assertFalse(t.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)), "base64url key bytes in: $t")
            assertFalse(t.contains(encoded.joinToString("") { "%02x".format(it) }), "hex key bytes in: $t")
        }
    }

    private companion object {
        const val NOT_BEFORE = 1_000L
        const val NOT_AFTER = 2_000L
    }
}
