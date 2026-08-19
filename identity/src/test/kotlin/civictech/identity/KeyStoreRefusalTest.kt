package civictech.identity

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Fail-closed loading ([DSC1-KEY-06..07]).
 *
 * Every case here asserts the *distinguishing* [KeyStoreRefusal], not merely
 * that something threw: a store that refused everything with one reason would
 * pass a "throws" assertion and be useless to an operator. The reasons are
 * enum values so callers can branch on them; the message adds the path.
 *
 * The other half of each case is what did **not** happen: no refusal may
 * generate a replacement key. Silent regeneration renames the node, which is
 * strictly worse than not starting (BS-16).
 */
class KeyStoreRefusalTest {

    // ---- BS-15: a world-readable private key ------------------------------

    @Test
    fun `BS-15 world-readable key file refuses load naming the path and loads no key`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        val minted = store.loadOrGenerate().peerId
        Files.setPosixFilePermissions(
            store.privateKeyFile,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )
        val before = contents(dir)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }

        assertEquals(KeyStoreRefusal.WORLD_READABLE, refusal.reason)
        assertEquals(store.privateKeyFile, refusal.path)
        assertTrue(store.privateKeyFile.toString() in refusal.message.orEmpty(), refusal.message.orEmpty())
        assertTrue("OTHERS_READ" in refusal.message.orEmpty(), refusal.message.orEmpty())
        // No key was loaded: the identity is nowhere in the diagnostic, and
        // nothing on disk changed.
        assertTrue(minted.name !in refusal.message.orEmpty())
        assertEquals(before, contents(dir))
    }

    @Test
    fun `a group-writable private key is refused too`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        Files.setPosixFilePermissions(
            store.privateKeyFile,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE,
            ),
        )

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }
        assertEquals(KeyStoreRefusal.WORLD_READABLE, refusal.reason)
    }

    @Test
    fun `the permission check is on the private key file, not the public one`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        val identity = store.loadOrGenerate()
        Files.setPosixFilePermissions(
            store.publicKeyFile,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )
        // A public key being public is not a defect.
        assertEquals(identity.peerId, FilePeerKeyStore(dir).loadOrGenerate().peerId)
    }

    // ---- BS-16: a corrupt key does not self-heal --------------------------

    @Test
    fun `BS-16 truncated key file refuses load and generates no replacement`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        val whole = Files.readAllBytes(store.privateKeyFile)
        writePrivate(store.privateKeyFile, whole.copyOf(whole.size / 2))
        val before = contents(dir)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }

        assertEquals(KeyStoreRefusal.MALFORMED, refusal.reason)
        assertEquals(store.privateKeyFile, refusal.path)
        assertTrue(store.privateKeyFile.toString() in refusal.message.orEmpty(), refusal.message.orEmpty())
        // The defect, not just the file: the cause names the decode failure.
        assertTrue("PKCS#8" in refusal.message.orEmpty(), refusal.message.orEmpty())

        // The whole point of BS-16: the directory is byte-for-byte as it was.
        // A store that quietly re-minted would leave a *valid* peer.key here
        // and a different PeerId — indistinguishable from a healthy node.
        assertEquals(before, contents(dir), "a refusal must not write anything")
        assertContentEquals(whole.copyOf(whole.size / 2), Files.readAllBytes(store.privateKeyFile))
    }

    @Test
    fun `garbage and empty key files are refused as malformed`(@TempDir dir: Path) {
        for (bytes in listOf("this is not a key".toByteArray(), ByteArray(0))) {
            val store = FilePeerKeyStore(Files.createTempDirectory(dir, "case"))
            store.loadOrGenerate()
            Files.delete(store.privateKeyFile)
            writePrivate(store.privateKeyFile, bytes)

            val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(store.privateKeyFile.parent).loadOrGenerate() }
            assertEquals(KeyStoreRefusal.MALFORMED, refusal.reason, "for ${bytes.size} bytes")
        }
    }

    @Test
    fun `a corrupt public key file is refused as malformed and named`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        Files.write(store.publicKeyFile, "not an SPKI".toByteArray())

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }
        assertEquals(KeyStoreRefusal.MALFORMED, refusal.reason)
        assertEquals(store.publicKeyFile, refusal.path, "the diagnostic must name the file that is broken")
    }

    // ---- the public half does not match the private half ------------------

    @Test
    fun `a swapped public key refuses load as KEYPAIR_MISMATCH`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        val minted = store.loadOrGenerate()
        val stranger = Ed25519.generateKeyPair().public
        Files.write(store.publicKeyFile, stranger.encoded)
        val before = contents(dir)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }

        assertEquals(KeyStoreRefusal.KEYPAIR_MISMATCH, refusal.reason)
        assertNotEquals(KeyStoreRefusal.WORLD_READABLE, refusal.reason)
        assertNotEquals(KeyStoreRefusal.MALFORMED, refusal.reason)
        assertEquals(store.publicKeyFile, refusal.path)
        // The refused pair would have claimed the stranger's name, not the
        // node's own — which is exactly the substitution being caught.
        assertTrue(fingerprint(stranger).name in refusal.message.orEmpty(), refusal.message.orEmpty())
        assertTrue(minted.peerId.name !in refusal.message.orEmpty())
        assertEquals(before, contents(dir), "a refusal must not write anything")
    }

    // ---- an unsupported algorithm -----------------------------------------

    @Test
    fun `an Ed448 keypair is refused as UNSUPPORTED, distinguishable from malformed`(@TempDir dir: Path) {
        val ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair()
        Files.createDirectories(dir)
        val store = FilePeerKeyStore(dir)
        writePrivate(store.privateKeyFile, ed448.private.encoded)
        Files.write(store.publicKeyFile, ed448.public.encoded)

        val refusal = assertFailsWith<KeyStoreRefusedException> { store.loadOrGenerate() }

        assertEquals(KeyStoreRefusal.UNSUPPORTED, refusal.reason)
        assertTrue("Ed448" in refusal.message.orEmpty(), refusal.message.orEmpty())
        assertEquals(store.privateKeyFile, refusal.path)
    }

    // ---- half a pair -------------------------------------------------------

    @Test
    fun `a private key with no public half refuses rather than minting a second identity`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        Files.delete(store.publicKeyFile)
        val before = contents(dir)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }
        assertEquals(KeyStoreRefusal.INCOMPLETE_PAIR, refusal.reason)
        assertEquals(store.publicKeyFile, refusal.path)
        assertEquals(before, contents(dir))
    }

    @Test
    fun `a public key with no private half refuses`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()
        Files.delete(store.privateKeyFile)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FilePeerKeyStore(dir).loadOrGenerate() }
        assertEquals(KeyStoreRefusal.INCOMPLETE_PAIR, refusal.reason)
        assertEquals(store.privateKeyFile, refusal.path)
    }

    // ---- the reasons are actually distinguishable --------------------------

    @Test
    fun `each defect yields its own reason`(@TempDir dir: Path) {
        val reasons = mutableListOf<KeyStoreRefusal>()

        val worldReadable = FilePeerKeyStore(Files.createTempDirectory(dir, "perm"))
        worldReadable.loadOrGenerate()
        Files.setPosixFilePermissions(
            worldReadable.privateKeyFile,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OTHERS_READ),
        )
        reasons += refusalOf(worldReadable)

        val malformed = FilePeerKeyStore(Files.createTempDirectory(dir, "corrupt"))
        malformed.loadOrGenerate()
        Files.delete(malformed.privateKeyFile)
        writePrivate(malformed.privateKeyFile, ByteArray(9) { 0x7f })
        reasons += refusalOf(malformed)

        val mismatched = FilePeerKeyStore(Files.createTempDirectory(dir, "mismatch"))
        mismatched.loadOrGenerate()
        Files.write(mismatched.publicKeyFile, Ed25519.generateKeyPair().public.encoded)
        reasons += refusalOf(mismatched)

        val incomplete = FilePeerKeyStore(Files.createTempDirectory(dir, "half"))
        incomplete.loadOrGenerate()
        Files.delete(incomplete.publicKeyFile)
        reasons += refusalOf(incomplete)

        val unsupported = FilePeerKeyStore(Files.createTempDirectory(dir, "curve"))
        val ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair()
        writePrivate(unsupported.privateKeyFile, ed448.private.encoded)
        Files.write(unsupported.publicKeyFile, ed448.public.encoded)
        reasons += refusalOf(unsupported)

        assertEquals(
            listOf(
                KeyStoreRefusal.WORLD_READABLE,
                KeyStoreRefusal.MALFORMED,
                KeyStoreRefusal.KEYPAIR_MISMATCH,
                KeyStoreRefusal.INCOMPLETE_PAIR,
                KeyStoreRefusal.UNSUPPORTED,
            ),
            reasons,
        )
        assertEquals(reasons.size, reasons.toSet().size, "five defects, five distinguishable reasons")
    }

    private companion object {
        fun refusalOf(store: FilePeerKeyStore): KeyStoreRefusal =
            assertFailsWith<KeyStoreRefusedException> { store.loadOrGenerate() }.reason

        /** Every file under [dir] as name -> hex, so "nothing was written" is checkable. */
        fun contents(dir: Path): Map<String, String> =
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .toList()
                    .associate { path ->
                        dir.relativize(path).toString() to
                            Files.readAllBytes(path).joinToString("") { "%02x".format(it) }
                    }
            }

        /** Writes a private key file the way the store would: owner-only. */
        fun writePrivate(path: Path, bytes: ByteArray) {
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
            Files.setPosixFilePermissions(path, FilePeerKeyStore.PRIVATE_KEY_PERMISSIONS)
        }
    }
}
