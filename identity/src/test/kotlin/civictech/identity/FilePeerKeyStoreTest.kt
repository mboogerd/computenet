package civictech.identity

import civictech.cell.link.PeerId
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.encodeIdentityStatementToken
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(fingerprint(identity.publicKey), identity.keyId)
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

    // ---- named load (computenet-5y8t.3.2): peer.statements beside the keypair ----
    //
    // The statements are held, not judged: nothing here verifies a signature or
    // compares issuance, and no case is revocation, rotation or stolen-key
    // resistance ([DSC1-NV-01] stays EXPLICITLY UNVERIFIED).

    @Test
    fun `stored statements load back as the named identity on a fresh store over the same directory`(@TempDir dir: Path) {
        val keyed = FilePeerKeyStore(dir).loadOrGenerate()
        val alice = PeerId("alice")
        val statements = listOf(
            anchor.bind(alice, keyed.keyId, 1, 1_000L, 2_000L),
            anchor.bind(alice, keyed.keyId, 2, 1_500L, 3_000L),
        )
        FilePeerKeyStore(dir).storeStatements(statements)

        val named = FilePeerKeyStore(dir).loadNamed()

        assertEquals(alice, named.peerId)
        assertEquals(keyed.keyId, named.keyId)
        assertContentEquals(keyed.publicKey.encoded, named.publicKey.encoded)
        assertEquals(statements.size, named.statements.size)
        // One shared empty array: the data-class equality compares ByteArray by identity.
        val unsigned = ByteArray(0)
        statements.zip(named.statements).forEach { (stored, loaded) ->
            assertEquals(stored.copy(signature = unsigned), loaded.copy(signature = unsigned))
            assertContentEquals(stored.signature, loaded.signature)
        }
        val message = "named after reload".toByteArray()
        assertTrue(keyed.verify(message, named.sign(message)), "the named load signs with the persisted key")
        assertEquals(
            statements.joinToString("") { encodeIdentityStatementToken(it) + "\n" },
            Files.readString(dir.resolve(FilePeerKeyStore.STATEMENTS_FILE)),
            "one token per line, LF-terminated",
        )
    }

    @Test
    fun `loadOrGenerate ignores peer statements and still yields the key-derived identity`(@TempDir dir: Path) {
        val keyed = FilePeerKeyStore(dir).loadOrGenerate()
        FilePeerKeyStore(dir).storeStatements(listOf(anchor.bind(PeerId("alice"), keyed.keyId, 1, 1_000L, 2_000L)))
        val before = snapshot(dir)

        val reloaded = FilePeerKeyStore(dir).loadOrGenerate()

        assertEquals(keyed.peerId, reloaded.peerId)
        assertEquals(keyed.keyId.name, reloaded.peerId.name)
        assertTrue(reloaded.statements.isEmpty())
        assertEquals(before, snapshot(dir), "loadOrGenerate must not touch any file, peer.statements included")
    }

    @Test
    fun `loadNamed on an empty directory generates the keypair then refuses STATEMENTS_MISSING`(@TempDir dir: Path) {
        val store = FilePeerKeyStore(dir)

        val refusal = assertFailsWith<KeyStoreRefusedException> { store.loadNamed() }

        assertEquals(KeyStoreRefusal.STATEMENTS_MISSING, refusal.reason)
        assertEquals(store.statementsFile, refusal.path)
        assertTrue(Files.exists(store.privateKeyFile), "the keypair is generated before the statements are required")
        assertTrue(Files.exists(store.publicKeyFile))
        assertFalse(Files.exists(store.statementsFile), "a refusal writes no statements")
    }

    private companion object {
        val anchor = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("store-anchor".toByteArray())))

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

/**
 * The durable incarnation source ([DSC1-ANN-04], `computenet-tdcx`): a number
 * that is strictly greater on every run, persisted before it is returned, and
 * read back with no reliance on the wall clock.
 *
 * The end-to-end clause — a signer restarting under a clock that stepped
 * *backwards* still has its catch-up burst accepted — is pinned over the socket
 * in `:wire`'s `WsReconnectSmokeTest`. What is pinned here is the store's own
 * contract, including the refusals it makes machine-distinguishable.
 */
class FilePeerIncarnationStoreTest {

    @Test
    fun `the first incarnation is seeded and persisted before it is returned`(@TempDir dir: Path) {
        val store = FilePeerIncarnationStore(dir, initial = { 4_200L })
        assertFalse(Files.exists(store.incarnationFile))

        val first = store.nextIncarnation()

        assertEquals(4_200L, first)
        assertEquals("4200", Files.readString(store.incarnationFile).trim(), "persisted before return")
    }

    @Test
    fun `a later run resumes strictly above the last, with the clock running backwards`(@TempDir dir: Path) {
        // The clock is read only to seed a directory that has no incarnation
        // yet. Both later "runs" hand it a value far BELOW the first, so a
        // sequence that still climbed cannot have consulted it.
        val first = FilePeerIncarnationStore(dir, initial = { 1_700_000_000_000L }).nextIncarnation()
        val second = FilePeerIncarnationStore(dir, initial = { 0L }).nextIncarnation()
        val third = FilePeerIncarnationStore(dir, initial = { 0L }).nextIncarnation()

        assertEquals(1_700_000_000_000L, first)
        assertEquals(first + 1, second)
        assertEquals(second + 1, third)
    }

    @Test
    fun `every call within one run is strictly increasing too`(@TempDir dir: Path) {
        val store = FilePeerIncarnationStore(dir, initial = { 10L })
        val seen = (1..5).map { store.nextIncarnation() }
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), seen)
    }

    @Test
    fun `the incarnation lives beside the key material, and neither disturbs the other`(@TempDir dir: Path) {
        val keys = FilePeerKeyStore(dir)
        val identity = keys.loadOrGenerate()
        val incarnation = FilePeerIncarnationStore(dir, initial = { 7L })
        incarnation.nextIncarnation()

        assertEquals(identity.peerId, FilePeerKeyStore(dir).loadOrGenerate().peerId)
        assertEquals(8L, FilePeerIncarnationStore(dir, initial = { 0L }).nextIncarnation())
        assertEquals(
            setOf(FilePeerKeyStore.PRIVATE_KEY_FILE, FilePeerKeyStore.PUBLIC_KEY_FILE, FilePeerIncarnationStore.INCARNATION_FILE),
            Files.list(dir).use { it.toList() }.map { it.fileName.toString() }.toSet(),
            "no temp file survives a successful write",
        )
    }

    // ---- refusal, never a silent fallback to the clock ----------------------

    @Test
    fun `an unparseable incarnation is refused, not restarted`(@TempDir dir: Path) {
        FilePeerIncarnationStore(dir, initial = { 5L }).nextIncarnation()
        Files.writeString(dir.resolve(FilePeerIncarnationStore.INCARNATION_FILE), "not-a-number")

        val refusal = assertFailsWith<KeyStoreRefusedException> {
            FilePeerIncarnationStore(dir, initial = { 1_700_000_000_000L }).nextIncarnation()
        }
        assertEquals(KeyStoreRefusal.INCARNATION_MALFORMED, refusal.reason)
        assertEquals(dir.resolve(FilePeerIncarnationStore.INCARNATION_FILE), refusal.path)
    }

    @Test
    fun `an empty incarnation file is refused`(@TempDir dir: Path) {
        Files.writeString(dir.resolve(FilePeerIncarnationStore.INCARNATION_FILE), "")
        val refusal = assertFailsWith<KeyStoreRefusedException> {
            FilePeerIncarnationStore(dir, initial = { 5L }).nextIncarnation()
        }
        assertEquals(KeyStoreRefusal.INCARNATION_MALFORMED, refusal.reason)
    }

    @Test
    fun `an out-of-range incarnation is refused rather than wrapping a counter floor negative`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve(FilePeerIncarnationStore.INCARNATION_FILE),
            (FilePeerIncarnationStore.MAX_INCARNATION + 1).toString(),
        )
        val refusal = assertFailsWith<KeyStoreRefusedException> {
            FilePeerIncarnationStore(dir, initial = { 5L }).nextIncarnation()
        }
        assertEquals(KeyStoreRefusal.INCARNATION_MALFORMED, refusal.reason)
    }

    @Test
    fun `an exhausted incarnation is refused`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve(FilePeerIncarnationStore.INCARNATION_FILE),
            FilePeerIncarnationStore.MAX_INCARNATION.toString(),
        )
        val refusal = assertFailsWith<KeyStoreRefusedException> {
            FilePeerIncarnationStore(dir, initial = { 5L }).nextIncarnation()
        }
        assertEquals(KeyStoreRefusal.INCARNATION_EXHAUSTED, refusal.reason)
    }

    @Test
    fun `an unreadable incarnation file is refused`(@TempDir dir: Path) {
        val store = FilePeerIncarnationStore(dir, initial = { 5L })
        store.nextIncarnation()
        Files.setPosixFilePermissions(store.incarnationFile, emptySet())
        try {
            val refusal = assertFailsWith<KeyStoreRefusedException> { store.nextIncarnation() }
            assertEquals(KeyStoreRefusal.INCARNATION_UNREADABLE, refusal.reason)
        } finally {
            Files.setPosixFilePermissions(
                store.incarnationFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    @Test
    fun `an unwritable directory is refused, and nothing is returned`(@TempDir dir: Path) {
        val nested = dir.resolve("locked")
        Files.createDirectory(nested)
        val store = FilePeerIncarnationStore(nested, initial = { 5L })
        Files.setPosixFilePermissions(nested, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        try {
            val refusal = assertFailsWith<KeyStoreRefusedException> { store.nextIncarnation() }
            assertEquals(KeyStoreRefusal.INCARNATION_UNWRITABLE, refusal.reason)
        } finally {
            Files.setPosixFilePermissions(
                nested,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
    }

    @Test
    fun `the refusals are distinguishable from one another`(@TempDir dir: Path) {
        val reasons = mutableSetOf<KeyStoreRefusal>()

        val malformed = Files.createTempDirectory(dir, "malformed")
        Files.writeString(malformed.resolve(FilePeerIncarnationStore.INCARNATION_FILE), "x")
        reasons += assertFailsWith<KeyStoreRefusedException> {
            FilePeerIncarnationStore(malformed).nextIncarnation()
        }.reason

        val exhausted = Files.createTempDirectory(dir, "exhausted")
        Files.writeString(
            exhausted.resolve(FilePeerIncarnationStore.INCARNATION_FILE),
            FilePeerIncarnationStore.MAX_INCARNATION.toString(),
        )
        reasons += assertFailsWith<KeyStoreRefusedException> {
            FilePeerIncarnationStore(exhausted).nextIncarnation()
        }.reason

        assertEquals(
            setOf(KeyStoreRefusal.INCARNATION_MALFORMED, KeyStoreRefusal.INCARNATION_EXHAUSTED),
            reasons,
        )
    }

    /**
     * `persist()`'s directory-fsync hardening (computenet-96eg) is
     * best-effort: this platform's JDK is the thing that decides whether it
     * is anything more than a no-op, and that cannot be asserted from inside
     * `FilePeerIncarnationStore` itself. This test pins the assumption
     * directly, independent of the store: if a future JDK or CI image makes
     * opening a directory as a [FileChannel] and calling `force(true)`
     * throw, this fails and says so, rather than the hardening silently
     * degrading to a no-op everywhere without any signal.
     */
    @Test
    fun `this platform's JDK supports fsyncing a directory, which persist()'s best-effort hardening relies on`(
        @TempDir dir: Path,
    ) {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    }
}
