package civictech.identity.anchor

import civictech.cell.link.IssuerId
import civictech.identity.DeterministicKeySource
import civictech.identity.Ed25519
import civictech.identity.KeyStoreRefusal
import civictech.identity.KeyStoreRefusedException
import civictech.identity.fingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/**
 * Fail-closed loading of accepted anchor issuers from a directory of public
 * halves (`computenet-5y8t.2`, epic decision 5y8t-D2, feature decision
 * 5y8t.2-D6): configuration, never discovery.
 */
class FileAcceptedIssuerStoreTest {

    @Test
    fun `two issuer files load to exactly those two issuers`(@TempDir dir: Path) {
        val a = DeterministicKeySource.keyPairFromSeed("issuer-a".toByteArray())
        val b = DeterministicKeySource.keyPairFromSeed("issuer-b".toByteArray())
        val idA = IssuerId(fingerprint(a.public).name)
        val idB = IssuerId(fingerprint(b.public).name)
        Files.write(dir.resolve(issuerFileName(idA)), a.public.encoded)
        Files.write(dir.resolve(issuerFileName(idB)), b.public.encoded)

        val loaded = FileAcceptedIssuerStore(dir).load()

        assertEquals(setOf(idA, idB), loaded.keys)
        assertContentEquals(a.public.encoded, loaded.getValue(idA).encoded)
        assertContentEquals(b.public.encoded, loaded.getValue(idB).encoded)
    }

    @Test
    fun `an existing empty directory loads an empty map`(@TempDir dir: Path) {
        assertEquals(emptyMap(), FileAcceptedIssuerStore(dir).load())
    }

    @Test
    fun `a missing directory refuses as ISSUER_DIRECTORY_MISSING`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(missing).load() }

        assertEquals(KeyStoreRefusal.ISSUER_DIRECTORY_MISSING, refusal.reason)
        assertEquals(missing, refusal.path)
    }

    @Test
    fun `a file named for a different key refuses as ISSUER_ID_MISMATCH, and loads once fixed`(@TempDir dir: Path) {
        val real = DeterministicKeySource.keyPairFromSeed("issuer-real".toByteArray())
        val impostorName = IssuerId(fingerprint(DeterministicKeySource.keyPairFromSeed("issuer-impostor".toByteArray()).public).name)
        val path = dir.resolve(issuerFileName(impostorName))
        Files.write(path, real.public.encoded)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }
        assertEquals(KeyStoreRefusal.ISSUER_ID_MISMATCH, refusal.reason)
        assertEquals(path, refusal.path)

        // Fix the name and it loads.
        Files.delete(path)
        val realId = IssuerId(fingerprint(real.public).name)
        Files.write(dir.resolve(issuerFileName(realId)), real.public.encoded)
        assertEquals(mapOf(realId to real.public), FileAcceptedIssuerStore(dir).load().mapValues { it.value })
    }

    @Test
    fun `a private key saved as a pub file refuses as ISSUER_PRIVATE_KEY_PRESENT`(@TempDir dir: Path) {
        val keyPair = DeterministicKeySource.keyPairFromSeed("issuer-leak".toByteArray())
        val path = dir.resolve("anything.pub")
        Files.write(path, keyPair.private.encoded)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }

        assertEquals(KeyStoreRefusal.ISSUER_PRIVATE_KEY_PRESENT, refusal.reason)
        assertEquals(path, refusal.path)
        assertNoPrivateMaterial(refusal.message.orEmpty(), keyPair.private.encoded)
    }

    @Test
    fun `the same private key bytes named peer key also refuse as ISSUER_PRIVATE_KEY_PRESENT`(@TempDir dir: Path) {
        val keyPair = DeterministicKeySource.keyPairFromSeed("issuer-leak-2".toByteArray())
        val path = dir.resolve("peer.key")
        Files.write(path, keyPair.private.encoded)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }

        assertEquals(KeyStoreRefusal.ISSUER_PRIVATE_KEY_PRESENT, refusal.reason)
        assertNoPrivateMaterial(refusal.message.orEmpty(), keyPair.private.encoded)
    }

    @Test
    fun `truncated SPKI refuses as MALFORMED`(@TempDir dir: Path) {
        val keyPair = DeterministicKeySource.keyPairFromSeed("issuer-truncated".toByteArray())
        val whole = keyPair.public.encoded
        val path = dir.resolve(issuerFileName(IssuerId(fingerprint(keyPair.public).name)))
        Files.write(path, whole.copyOf(whole.size / 2))

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }
        assertEquals(KeyStoreRefusal.MALFORMED, refusal.reason)
        assertEquals(path, refusal.path)
    }

    @Test
    fun `an Ed448 key is refused as UNSUPPORTED, not ISSUER_ID_MISMATCH, even when named by its own hash`(
        @TempDir dir: Path,
    ) {
        val ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(ed448.public.encoded)
        val stem = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        val path = dir.resolve("$stem.pub")
        Files.write(path, ed448.public.encoded)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }

        assertEquals(KeyStoreRefusal.UNSUPPORTED, refusal.reason)
        assertEquals(path, refusal.path)
    }

    @Test
    fun `a DS_Store-style dotfile with junk bytes is ignored`(@TempDir dir: Path) {
        Files.write(dir.resolve(".DS_Store"), "junk, not a key".toByteArray())

        assertEquals(emptyMap(), FileAcceptedIssuerStore(dir).load())
    }

    @Test
    fun `a dotfile holding a private key still refuses as ISSUER_PRIVATE_KEY_PRESENT`(@TempDir dir: Path) {
        val keyPair = DeterministicKeySource.keyPairFromSeed("issuer-dotfile".toByteArray())
        val path = dir.resolve(".hidden-key")
        Files.write(path, keyPair.private.encoded)

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }
        assertEquals(KeyStoreRefusal.ISSUER_PRIVATE_KEY_PRESENT, refusal.reason)
    }

    @Test
    fun `a non-dotfile not ending in pub refuses as MALFORMED`(@TempDir dir: Path) {
        val path = dir.resolve("readme.txt")
        Files.write(path, "not a key at all".toByteArray())

        val refusal = assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }
        assertEquals(KeyStoreRefusal.MALFORMED, refusal.reason)
        assertEquals(path, refusal.path)
    }

    @Test
    fun `subdirectories are ignored`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("nested"))
        val keyPair = DeterministicKeySource.keyPairFromSeed("issuer-nested".toByteArray())
        Files.write(
            dir.resolve("nested").resolve(issuerFileName(IssuerId(fingerprint(keyPair.public).name))),
            keyPair.public.encoded,
        )

        assertEquals(emptyMap(), FileAcceptedIssuerStore(dir).load())
    }

    @Test
    fun `the store writes nothing, including on refusal`(@TempDir dir: Path) {
        val keyPair = DeterministicKeySource.keyPairFromSeed("issuer-nowrite".toByteArray())
        Files.write(dir.resolve(issuerFileName(IssuerId(fingerprint(keyPair.public).name))), keyPair.public.encoded)
        val before = listing(dir)

        FileAcceptedIssuerStore(dir).load()
        assertEquals(before, listing(dir))

        Files.write(dir.resolve("bogus.pub"), "not decodable".toByteArray())
        val beforeRefusal = listing(dir)
        assertFailsWith<KeyStoreRefusedException> { FileAcceptedIssuerStore(dir).load() }
        assertEquals(beforeRefusal, listing(dir))
    }

    @Test
    fun `KeyStoreRefusal ends with the three new entries in order`() {
        // Appended, contiguous and in order right after the entry that ended the
        // enum before them — without requiring them to stay the enum's tail, so a
        // later append (e.g. computenet-5y8t.3.2's STATEMENTS_*) does not break it.
        val names = KeyStoreRefusal.entries.map { it.name }
        val start = names.indexOf("INCARNATION_EXHAUSTED")
        assertEquals(
            listOf("INCARNATION_EXHAUSTED", "ISSUER_DIRECTORY_MISSING", "ISSUER_PRIVATE_KEY_PRESENT", "ISSUER_ID_MISMATCH"),
            names.subList(start.coerceAtLeast(0), (start + 4).coerceIn(0, names.size)),
        )
    }

    private companion object {
        fun listing(dir: Path): Map<String, String> =
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .toList()
                    .associate { path ->
                        dir.relativize(path).toString() to
                            Files.readAllBytes(path).joinToString("") { "%02x".format(it) }
                    }
            }

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
