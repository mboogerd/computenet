package civictech.identity

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Load-or-generate ([DSC1-KEY-04..05]): a node mints its identity once and
 * keeps it.
 */
class FilePeerKeyStoreTest {

    @Test
    fun `first start generates and persists both halves before returning`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        assertFalse(Files.exists(store.privateKeyFile))

        val identity = store.loadOrGenerate()

        // "Before returning" — the files are on disk by the time the caller has
        // the identity, not written lazily afterwards.
        assertTrue(Files.exists(store.privateKeyFile), "peer.key must exist on return")
        assertTrue(Files.exists(store.publicKeyFile), "peer.pub must exist on return")
        assertContentEquals(
            identity.publicKey.encoded,
            Files.readAllBytes(store.publicKeyFile),
            "the persisted SPKI must be the key that was returned",
        )
        assertEquals(fingerprint(identity.publicKey), identity.peerId)
    }

    @Test
    fun `the generated private key file is owner-only`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        store.loadOrGenerate()

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(store.privateKeyFile),
        )
    }

    @Test
    fun `restart loads the identical key and writes nothing`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)
        val first = store.loadOrGenerate()

        val before = snapshot(dir)

        // A second store over the same directory — the restart case; the object
        // holds no cached state that could make this pass vacuously.
        val second = FilePeerKeyStore(dir).loadOrGenerate()

        assertEquals(first.peerId, second.peerId)
        assertContentEquals(first.publicKey.encoded, second.publicKey.encoded)
        assertEquals(
            before,
            snapshot(dir),
            "loading must not create, rewrite or re-time any file",
        )
    }

    @Test
    fun `a key persisted by one store is signed for by the next`(@TempDir dir: Path) {
        val message = "announcement-ish bytes".toByteArray()
        val signature = FilePeerKeyStore(dir).loadOrGenerate().sign(message)

        val reloaded = FilePeerKeyStore(dir).loadOrGenerate()
        assertTrue(reloaded.verify(message, signature), "the reloaded public half must verify the earlier signature")
    }

    @Test
    fun `two directories are two identities`(@TempDir dir: Path) {
        assertNotEquals(
            FilePeerKeyStore(dir.resolve("node-a")).loadOrGenerate().peerId,
            FilePeerKeyStore(dir.resolve("node-b")).loadOrGenerate().peerId,
        )
    }

    @Test
    fun `the store creates its directory when it is missing`(@TempDir dir: Path) {
        val nested = dir.resolve("var").resolve("identity")
        val identity = FilePeerKeyStore(nested).loadOrGenerate()
        assertEquals(identity.peerId, FilePeerKeyStore(nested).loadOrGenerate().peerId)
    }

    private companion object {
        /** File name -> (content, last-modified) for every file under [dir]. */
        fun snapshot(dir: Path): Map<String, Pair<String, Long>> =
            Files.list(dir).use { stream ->
                stream.toList().associate { path ->
                    path.fileName.toString() to Pair(
                        Files.readAllBytes(path).joinToString("") { "%02x".format(it) },
                        Files.getLastModifiedTime(path).toMillis(),
                    )
                }
            }
    }
}
