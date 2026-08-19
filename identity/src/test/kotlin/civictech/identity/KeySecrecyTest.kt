package civictech.identity

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Private key material leaves this module through exactly one door: the
 * configured key store's own `peer.key` ([DSC1-KEY-09]).
 *
 * The renderings checked here — [PeerIdentity.toString] and every refusal
 * message — are the ones that end up in logs, dead letters and bug reports,
 * which is where a leaked key actually goes.
 */
class KeySecrecyTest {

    @Test
    fun `PeerIdentity toString carries the PeerId and no private key bytes`() {
        val keys = Ed25519.generateKeyPair()
        val identity = PeerIdentity(keys)
        val rendered = identity.toString()

        assertTrue(identity.peerId.name in rendered, rendered)
        assertTrue("redacted" in rendered, rendered)
        assertNoPrivateMaterial(rendered, keys.private.encoded)
    }

    @Test
    fun `no refusal message carries private key bytes`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        val privateEncoding = Files.readAllBytes(store.privateKeyFile)

        val messages = mutableListOf<String>()

        // World-readable.
        Files.setPosixFilePermissions(
            store.privateKeyFile,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OTHERS_READ),
        )
        messages += assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }.renderFully()
        Files.setPosixFilePermissions(store.privateKeyFile, FilePeerKeyStore.PRIVATE_KEY_PERMISSIONS)

        // Keypair mismatch — the refusal that has a *valid* private key in hand.
        Files.write(store.publicKeyFile, Ed25519.generateKeyPair().public.encoded)
        messages += assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }.renderFully()

        // Malformed — the refusal whose cause is a JDK decode failure over the
        // very bytes in question.
        Files.write(store.privateKeyFile, privateEncoding.copyOf(privateEncoding.size / 2))
        messages += assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }.renderFully()

        assertEquals(3, messages.size)
        messages.forEach { assertNoPrivateMaterial(it, privateEncoding) }
    }

    @Test
    fun `the store writes only under its configured directory`(@TempDir dir: Path) {
        val home = dir.resolve("node")
        val neighbour = Files.createDirectories(dir.resolve("neighbour"))
        val store = FilePeerKeyStore(home)

        val identity = store.loadOrGenerate()

        assertEquals(
            setOf("peer.key", "peer.pub"),
            Files.list(home).use { it.toList() }.map { it.fileName.toString() }.toSet(),
        )
        assertTrue(
            Files.list(neighbour).use { it.toList() }.isEmpty(),
            "nothing is written outside the configured directory",
        )

        // And the one file that does hold private material holds exactly it,
        // owner-only.
        assertEquals(FilePeerKeyStore.PRIVATE_KEY_PERMISSIONS, Files.getPosixFilePermissions(store.privateKeyFile))
        assertContentEquals(identity.publicKey.encoded, Files.readAllBytes(store.publicKeyFile))
        assertNoPrivateMaterial(
            String(Files.readAllBytes(store.publicKeyFile), Charsets.ISO_8859_1),
            Files.readAllBytes(store.privateKeyFile),
        )
    }

    @Test
    fun `the store's own toString names only its directory`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        assertNoPrivateMaterial(store.toString(), Files.readAllBytes(store.privateKeyFile))
        assertTrue(dir.toString() in store.toString())
    }

    private companion object {
        /** Message plus cause chain plus stack-free rendering — everything a log would carry. */
        fun KeyStoreRefusedException.renderFully(): String =
            generateSequence(this as Throwable) { it.cause }.joinToString(" | ") { "${it::class.java.name}: ${it.message}" }

        fun assertNoPrivateMaterial(rendered: String, privateEncoding: ByteArray) {
            val encodings = listOf(
                Base64.getEncoder().encodeToString(privateEncoding),
                Base64.getUrlEncoder().withoutPadding().encodeToString(privateEncoding),
                privateEncoding.joinToString("") { "%02x".format(it) },
                // The 32-byte Ed25519 seed sits at the tail of the PKCS#8 DER;
                // check that too, in case only the raw scalar were rendered.
                privateEncoding.takeLast(32).joinToString("") { "%02x".format(it) },
            )
            encodings.forEach { encoding ->
                assertFalse(encoding in rendered, "private key material appeared in: $rendered")
            }
            // And no long run of the raw bytes interpreted as text.
            assertFalse(String(privateEncoding, Charsets.ISO_8859_1) in rendered, rendered)
        }
    }
}
