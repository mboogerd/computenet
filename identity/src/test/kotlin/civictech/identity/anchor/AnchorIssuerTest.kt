package civictech.identity.anchor

import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.identity.DeterministicKeySource
import civictech.identity.Ed25519
import civictech.identity.FilePeerKeyStore
import civictech.identity.PeerIdentity
import civictech.identity.fingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `AnchorIssuer` (task `computenet-5y8t.2.2`, feature `computenet-5y8t.2`
 * rule 2, decisions D1/D4/D5).
 *
 * No test here is named or readable as stolen-key resistance or revocation
 * (`[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED): every case below is either
 * "a legitimately minted statement verifies" or "a legitimately minted
 * statement fails to verify under the WRONG key/bytes" — never an attacker
 * model.
 */
class AnchorIssuerTest {

    private val anchorA = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("anchor-A".toByteArray())))
    private val anchorB = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("anchor-B".toByteArray())))

    private val presentedKeyId = KeyId("ed25519:presented-key")

    @Test
    fun `bind returns a statement whose issuer, fields and signature match, and the signature verifies`() {
        val statement = anchorA.bind(
            name = PeerId("alice"),
            keyId = presentedKeyId,
            issuance = 7L,
            notBefore = 1_000L,
            notAfter = 2_000L,
        )

        assertEquals(IssuerId(fingerprint(anchorA.publicKey).name), statement.issuer)
        assertEquals(PeerId("alice"), statement.name)
        assertEquals(presentedKeyId, statement.keyId)
        assertEquals(7L, statement.issuance)
        assertEquals(1_000L, statement.notBefore)
        assertEquals(2_000L, statement.notAfter)

        assertEquals(Ed25519.SIGNATURE_LENGTH, statement.signature.size)
        assertTrue(Ed25519.verify(anchorA.publicKey, statementSigningBytes(statement), statement.signature))

        val flipped = statement.signature.copyOf()
        flipped[0] = (flipped[0].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(anchorA.publicKey, statementSigningBytes(statement), flipped))
    }

    @Test
    fun `a statement minted by anchor A does not verify under anchor B's key`() {
        val statement = anchorA.bind(
            name = PeerId("alice"),
            keyId = presentedKeyId,
            issuance = 1L,
            notBefore = 1_000L,
            notAfter = 2_000L,
        )

        assertFalse(Ed25519.verify(anchorB.publicKey, statementSigningBytes(statement), statement.signature))
    }

    @Test
    fun `the four-argument overload counts issuance per name from 1, in the injected clock's window`() {
        var now = 5_000L
        val issuer = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("anchor-A".toByteArray())), clock = { now })

        val first = issuer.bind(PeerId("alice"), presentedKeyId)
        val second = issuer.bind(PeerId("alice"), presentedKeyId)
        val otherName = issuer.bind(PeerId("bob"), presentedKeyId)

        assertEquals(1L, first.issuance)
        assertEquals(2L, second.issuance)
        assertEquals(1L, otherName.issuance)

        assertEquals(now, first.notBefore)
        assertEquals(AnchorIssuer.DEFAULT_VALIDITY_MILLIS, first.notAfter - first.notBefore)

        now = 9_000L
        val laterClock = issuer.bind(PeerId("carol"), presentedKeyId)
        assertEquals(9_000L, laterClock.notBefore)
    }

    @Test
    fun `bind rejects notBefore after notAfter`() {
        assertFailsWith<IllegalArgumentException> {
            anchorA.bind(name = PeerId("alice"), keyId = presentedKeyId, issuance = 1L, notBefore = 2L, notAfter = 1L)
        }
    }

    @Test
    fun `a file-backed anchor mints a statement that verifies under the key on disk, and reloading keeps the same issuerId`(
        @TempDir dir: Path,
    ) {
        val issuer = AnchorIssuer(FilePeerKeyStore(dir).loadOrGenerate())

        val statement = issuer.bind(PeerId("alice"), presentedKeyId, issuance = 1L, notBefore = 0L, notAfter = 1L)

        val publicKeyOnDisk = KeyFactory.getInstance(Ed25519.KEY_FACTORY)
            .generatePublic(X509EncodedKeySpec(Files.readAllBytes(dir.resolve("peer.pub"))))
        assertTrue(Ed25519.verify(publicKeyOnDisk, statementSigningBytes(statement), statement.signature))

        val reloaded = AnchorIssuer(FilePeerKeyStore(dir).loadOrGenerate())
        assertEquals(issuer.issuerId, reloaded.issuerId)
    }

    @Test
    fun `toString names the issuerId and carries no private material, and no public member returns key material`() {
        val rendered = anchorA.toString()
        assertTrue(anchorA.issuerId.name in rendered, rendered)

        val privateEncoding = privateKeyEncodingOf(anchorA)
        assertNoPrivateMaterial(rendered, privateEncoding)

        // Plain java.lang.reflect, not kotlin-reflect: :identity does not depend on
        // kotlin-reflect and this task does not add it.
        val offendingMembers = AnchorIssuer::class.java.methods.filter { method ->
            method.declaringClass == AnchorIssuer::class.java &&
                (method.returnType == java.security.PrivateKey::class.java || method.returnType == java.security.KeyPair::class.java)
        }
        assertTrue(offendingMembers.isEmpty(), "public members returning private key material: $offendingMembers")
    }

    private companion object {
        /** Recovers the private key bytes independently of [AnchorIssuer], purely to feed [assertNoPrivateMaterial]. */
        fun privateKeyEncodingOf(issuer: AnchorIssuer): ByteArray {
            val seed = "anchor-A".toByteArray()
            return DeterministicKeySource.keyPairFromSeed(seed).private.encoded
        }

        /** The `KeySecrecyTest` shape, reused here rather than exported, per the task's own "your call". */
        fun assertNoPrivateMaterial(rendered: String, privateEncoding: ByteArray) {
            val encodings = listOf(
                Base64.getEncoder().encodeToString(privateEncoding),
                Base64.getUrlEncoder().withoutPadding().encodeToString(privateEncoding),
                privateEncoding.joinToString("") { "%02x".format(it) },
                privateEncoding.takeLast(32).joinToString("") { "%02x".format(it) },
            )
            encodings.forEach { encoding ->
                assertFalse(encoding in rendered, "private key material appeared in: $rendered")
            }
            assertFalse(String(privateEncoding, Charsets.ISO_8859_1) in rendered, rendered)
        }
    }
}
