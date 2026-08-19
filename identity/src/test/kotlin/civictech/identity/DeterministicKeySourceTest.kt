package civictech.identity

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The test-only deterministic keypair source ([DSC1-KEY-08]): reproducible
 * when explicitly named, unreachable otherwise.
 */
class DeterministicKeySourceTest {

    @Test
    fun `the same seed yields the same keypair`() {
        val a = DeterministicKeySource.keyPairFromSeed("seed-one".toByteArray())
        val b = DeterministicKeySource.keyPairFromSeed("seed-one".toByteArray())

        assertContentEquals(a.public.encoded, b.public.encoded)
        assertContentEquals(a.private.encoded, b.private.encoded)
        assertEquals(fingerprint(a.public), fingerprint(b.public))
    }

    @Test
    fun `different seeds yield different keypairs`() {
        val a = DeterministicKeySource.keyPairFromSeed("seed-one".toByteArray())
        val b = DeterministicKeySource.keyPairFromSeed("seed-two".toByteArray())
        assertNotEquals(fingerprint(a.public), fingerprint(b.public))
    }

    @Test
    fun `a seeded keypair signs and verifies like any other`() {
        val keys = DeterministicKeySource.keyPairFromSeed("seed-signing".toByteArray())
        val signature = Ed25519.sign(keys.private, "payload".toByteArray())
        assertTrue(Ed25519.verify(keys.public, "payload".toByteArray(), signature))
    }

    @Test
    fun `an empty seed is rejected rather than silently fixed`() {
        assertFailsWith<IllegalArgumentException> { DeterministicKeySource.keyPairFromSeed(ByteArray(0)) }
    }

    @Test
    fun `the default generator is not deterministic`() {
        assertNotEquals(
            fingerprint(Ed25519.generateKeyPair().public),
            fingerprint(Ed25519.generateKeyPair().public),
        )
    }

    @Test
    fun `nothing reaches the deterministic source by default`(@TempDir dir: Path) {
        // Behavioural: two fresh stores mint two different identities, so the
        // store's own path cannot be routing through a fixed seed.
        val first = FilePeerKeyStore(dir.resolve("a")).loadOrGenerate().peerId
        val second = FilePeerKeyStore(dir.resolve("b")).loadOrGenerate().peerId
        assertNotEquals(first, second)

        // Structural: the store has exactly one constructor and it takes only
        // the directory. There is no keypair-source parameter, no default
        // argument and no setter, so no configuration path can select the
        // deterministic source — reaching it requires naming
        // DeterministicKeySource at the call site.
        val constructors = FilePeerKeyStore::class.java.constructors
        assertEquals(1, constructors.size, constructors.map { it.toString() }.toString())
        assertEquals(
            listOf(Path::class.java),
            constructors.single().parameterTypes.toList(),
            "FilePeerKeyStore must expose no seam for injecting a keypair source",
        )
        assertTrue(
            FilePeerKeyStore::class.java.methods.none { it.name.contains("KeySource", ignoreCase = true) },
            "no accessor may hand out or accept a keypair source",
        )
    }

    @Test
    fun `the seeded random never prints its seed`() {
        // The seed is caller-supplied test material, but it is also the private
        // key in all but name: whoever holds it holds the key.
        val seed = "a very memorable seed".toByteArray()
        val keys = DeterministicKeySource.keyPairFromSeed(seed)
        val rendered = PeerIdentity(keys).toString()
        assertFalse(String(seed) in rendered, rendered)
    }
}
