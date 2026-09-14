package civictech.identity.anchor

import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.UnboundReason
import civictech.cell.wire.DEFAULT_ANNOUNCEMENT_SKEW_MILLIS
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.fingerprint
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * `AnchorVouchedBinding` (task `computenet-5y8t.2.3`, feature
 * `computenet-5y8t.2` rules 3-6 and 8).
 *
 * Every case is a legitimately minted statement presented to a binding that
 * does or does not accept its issuer, at a clock inside or outside its
 * window, or with bytes that no longer match what was signed. **No test here
 * is named or readable as stolen-key resistance or revocation**:
 * `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED, and revocation (epic residual
 * R4) is not built — one test below pins that absence.
 */
class AnchorVouchedBindingTest {

    private val anchorA = anchor("anchor-A")
    private val anchorB = anchor("anchor-B")
    private val anchorC = anchor("anchor-C")
    private val k: KeyId = keyIdFor("peer-k")
    private val k2: KeyId = keyIdFor("peer-k2")
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    private fun anchor(seed: String) =
        AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray())))

    private fun keyIdFor(seed: String): KeyId =
        fingerprint(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()).public)

    private fun accepting(vararg issuers: AnchorIssuer, clock: Long): AnchorVouchedBinding =
        AnchorVouchedBinding(issuers.associate { it.issuerId to it.publicKey }, clock = { clock })

    private fun unbound(reason: UnboundReason) = IdentityResolution.Unbound(reason)

    @Test
    fun `an accepted issuer's in-window statement for the key resolves to its name, issuer and the same statement instance`() {
        val s = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val result = accepting(anchorA, clock = 1_500).resolve(k, listOf(s))

        assertEquals(IdentityResolution.Bound(alice, anchorA.issuerId, s), result)
        assertSame(s, (result as IdentityResolution.Bound).statement)
    }

    @Test
    fun `the window is strict below notBefore and allows skewMillis of lag past notAfter`() {
        val s = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)

        assertEquals(unbound(UnboundReason.NOT_YET_VALID), accepting(anchorA, clock = 999).resolve(k, listOf(s)))
        assertTrue(accepting(anchorA, clock = 1_000).resolve(k, listOf(s)) is IdentityResolution.Bound)
        assertTrue(
            accepting(anchorA, clock = NOT_AFTER + SKEW).resolve(k, listOf(s)) is IdentityResolution.Bound,
            "notAfter < now - skew is false at the boundary",
        )
        assertEquals(
            unbound(UnboundReason.EXPIRED),
            accepting(anchorA, clock = NOT_AFTER + SKEW + 1).resolve(k, listOf(s)),
        )
    }

    @Test
    fun `a signature that does not verify over the canonical bytes is BAD_SIGNATURE and never throws`() {
        val s = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val flipped = s.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val binding = accepting(anchorA, clock = 1_500)

        for (signature in listOf(flipped, ByteArray(0), ByteArray(3))) {
            val result = assertDoesNotThrow { binding.resolve(k, listOf(s.copy(signature = signature))) }
            assertEquals(unbound(UnboundReason.BAD_SIGNATURE), result, "signature of ${signature.size} bytes")
        }
        // A field changed after signing: the signature no longer covers these bytes.
        assertEquals(
            unbound(UnboundReason.BAD_SIGNATURE),
            binding.resolve(k, listOf(s.copy(name = bob))),
        )
    }

    @Test
    fun `a statement whose strings cannot be canonicalised is BAD_SIGNATURE and never throws`() {
        val s = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val illFormed = s.copy(name = PeerId("alice\uD800"))

        val result = assertDoesNotThrow { accepting(anchorA, clock = 1_500).resolve(k, listOf(illFormed)) }
        assertEquals(unbound(UnboundReason.BAD_SIGNATURE), result)
    }

    @Test
    fun `a statement from an issuer the binding does not accept is ISSUER_NOT_ACCEPTED even though it verifies under that issuer`() {
        val fromC = anchorC.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        assertTrue(
            accepting(anchorC, clock = 1_500).resolve(k, listOf(fromC)) is IdentityResolution.Bound,
            "precondition: the statement verifies under C",
        )

        assertEquals(
            unbound(UnboundReason.ISSUER_NOT_ACCEPTED),
            assertDoesNotThrow { accepting(anchorA, clock = 1_500).resolve(k, listOf(fromC)) },
        )
    }

    @Test
    fun `a verified statement binding a different key is KEY_MISMATCH`() {
        val forK2 = anchorA.bind(alice, k2, 1, NOT_BEFORE, NOT_AFTER)
        assertEquals(unbound(UnboundReason.KEY_MISMATCH), accepting(anchorA, clock = 1_500).resolve(k, listOf(forK2)))
    }

    @Test
    fun `no presented statement is NO_STATEMENT`() {
        assertEquals(unbound(UnboundReason.NO_STATEMENT), accepting(anchorA, clock = 1_500).resolve(k, emptyList()))
    }

    /**
     * Two anchors, constructed identically, both accepted: each resolves
     * naming its own issuer, whatever the map's insertion order, and an
     * unaccepted third is refused. Nothing distinguishes "our" anchor from
     * another — the production-side check is that
     * `git grep -nE 'IssuerId\("' -- 'identity/src/main'` is empty (no
     * default issuer, no constant anchor id compiled in).
     */
    @Test
    fun `two accepted anchors each resolve naming the issuer that vouched, independent of map order`() {
        val fromA = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val fromB = anchorB.bind(bob, k2, 1, NOT_BEFORE, NOT_AFTER)
        val fromC = anchorC.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)

        val maps: List<Map<IssuerId, PublicKey>> = listOf(
            linkedMapOf(anchorA.issuerId to anchorA.publicKey, anchorB.issuerId to anchorB.publicKey),
            linkedMapOf(anchorB.issuerId to anchorB.publicKey, anchorA.issuerId to anchorA.publicKey),
        )
        for (map in maps) {
            val binding = AnchorVouchedBinding(map, clock = { 1_500 })
            assertEquals(IdentityResolution.Bound(alice, anchorA.issuerId, fromA), binding.resolve(k, listOf(fromA)))
            assertEquals(IdentityResolution.Bound(bob, anchorB.issuerId, fromB), binding.resolve(k2, listOf(fromB)))
            assertEquals(unbound(UnboundReason.ISSUER_NOT_ACCEPTED), binding.resolve(k, listOf(fromC)))
        }
    }

    @Test
    fun `across several statements the refusal names the one that got furthest, and a later valid one still binds`() {
        val fromC = anchorC.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val fromA = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)

        assertEquals(
            unbound(UnboundReason.EXPIRED),
            accepting(anchorA, clock = 5_000 + SKEW).resolve(k, listOf(fromC, fromA)),
        )
        assertEquals(
            IdentityResolution.Bound(alice, anchorA.issuerId, fromA),
            accepting(anchorA, clock = 1_500).resolve(k, listOf(fromC, fromA)),
        )
        // Ties keep the earliest presented: an expired and a not-yet-valid statement fail equally deep.
        val expired = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val notYet = anchorA.bind(alice, k, 1, 100_000, 200_000)
        val clock = NOT_AFTER + SKEW + 1
        assertEquals(unbound(UnboundReason.EXPIRED), accepting(anchorA, clock = clock).resolve(k, listOf(expired, notYet)))
        assertEquals(unbound(UnboundReason.NOT_YET_VALID), accepting(anchorA, clock = clock).resolve(k, listOf(notYet, expired)))
    }

    /**
     * Pins the ABSENCE of supersession: epic residual R4 — revocation is not
     * built in DSC4, and this binding compares `issuance` with nothing and
     * remembers nothing between calls. This test exists so that nobody reads
     * DSC4 as delivering revocation; when a revocation design lands, this
     * test is expected to change with it.
     */
    @Test
    fun `a lower issuance seen after a higher one still resolves - no supersession in DSC4 (residual R4)`() {
        val s2 = anchorA.bind(alice, k, 2, NOT_BEFORE, NOT_AFTER)
        val s1 = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val binding = accepting(anchorA, clock = 1_500)

        assertEquals(IdentityResolution.Bound(alice, anchorA.issuerId, s2), binding.resolve(k, listOf(s2)))
        assertEquals(IdentityResolution.Bound(alice, anchorA.issuerId, s1), binding.resolve(k, listOf(s1)))
    }

    /**
     * **The OFFLINE criterion of epic `computenet-5y8t`.**
     *
     * What this shows: (i) verification succeeded from in-memory inputs
     * alone — the accepted-issuer map is built from deterministic in-memory
     * keys, and no socket, file or service was involved in constructing the
     * binding or the statement; (ii) structurally, the class's source file
     * imports nothing from `java.net`, `java.nio.channels`, `java.nio.file`
     * or `java.io`, so it can reach no I/O API by import.
     *
     * What this does NOT show: it is not a network sandbox (nothing here
     * intercepts a connection attempt); it says nothing about the JDK
     * security provider's internals; and `[DSC1-NV-01]` (stolen-key
     * resistance) remains EXPLICITLY UNVERIFIED.
     */
    @Test
    fun `offline criterion of epic computenet-5y8t - resolution succeeds from in-memory keys and the class imports no IO API`() {
        val s = anchorA.bind(alice, k, 1, NOT_BEFORE, NOT_AFTER)
        val binding = AnchorVouchedBinding(mapOf(anchorA.issuerId to anchorA.publicKey), clock = { 1_500 })
        assertEquals(IdentityResolution.Bound(alice, anchorA.issuerId, s), binding.resolve(k, listOf(s)))

        val source = File(repoRoot(), "identity/src/main/kotlin/civictech/identity/anchor/AnchorVouchedBinding.kt")
        assertTrue(source.isFile, "missing ${source.path}")
        val imports = source.readLines().map { it.trim() }.filter { it.startsWith("import ") }
        assertTrue(imports.isNotEmpty(), "found no import lines at all — the scan is reading the wrong file")
        val forbidden = listOf("java.net", "java.nio.channels", "java.nio.file", "java.io")
        val offending = imports.filter { line ->
            val target = line.removePrefix("import ").trim()
            forbidden.any { target == it || target.startsWith("$it.") }
        }
        assertEquals(emptyList<String>(), offending,"AnchorVouchedBinding must import no I/O API")
    }

    @Test
    fun `a non-Ed25519 accepted issuer key is refused at construction`() {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair().public
        for (key in listOf(rsa, ed448)) {
            assertFailsWith<IllegalArgumentException>(key.algorithm) {
                AnchorVouchedBinding(mapOf(anchorA.issuerId to key))
            }
        }
    }

    @Test
    fun `toString names the accepted issuer ids only`() {
        val text = accepting(anchorA, anchorB, clock = 0).toString()
        assertTrue(anchorA.issuerId.name in text && anchorB.issuerId.name in text, text)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private companion object {
        const val NOT_BEFORE = 1_000L
        const val NOT_AFTER = 2_000L
        const val SKEW = DEFAULT_ANNOUNCEMENT_SKEW_MILLIS
    }
}
