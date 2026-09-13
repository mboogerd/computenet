package civictech.identity.anchor

import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.identity.announce.toHex
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The seeded property test for [statementSigningBytes] (task
 * `computenet-5y8t.2.1`), the `AnnouncementCanonicalBytesPropertyTest`
 * precedent applied to the six-field `IdentityStatement` tuple.
 *
 * Four properties, over inputs drawn from a fixed seed so a failure is
 * reproducible by re-running the test rather than by re-rolling the dice:
 *
 * 1. **Determinism** — encoding the same statement twice is byte-identical.
 * 2. **Pairwise distinctness** — distinct `(name, keyId, issuer, issuance,
 *    notBefore, notAfter)` tuples give distinct bytes across the sample.
 * 3. **Single-field sensitivity** — mutating any ONE of the six signed
 *    fields changes the bytes; the sharper check, because a random sample
 *    differing in many fields at once would still look injective under an
 *    encoding that silently ignored one of them.
 * 4. **Rejection of ill-formed strings** — a generated string carrying an
 *    unpaired surrogate in `name`, `keyId` or `issuer` is refused rather
 *    than encoded, with the exception naming that field.
 */
class IdentityStatementCanonicalBytesPropertyTest {

    companion object {
        /**
         * The seed. **Keep it.** If a run ever discovers a statement that
         * violates one of these properties, the seed that found it is
         * evidence and gets pinned here — never replaced with a friendlier
         * one that happens to pass (repo rule; AGENTS.md "Preserve
         * deterministic simulation tests").
         */
        private const val SEED: Long = 0x5A17_C0DE_18L

        private const val SAMPLES: Int = 400
    }

    @Test
    fun `encoding is deterministic`() {
        val rnd = Random(SEED)
        repeat(SAMPLES) {
            val statement = randomStatement(rnd)
            val once = statementSigningBytes(statement).toHex()
            assertEquals(once, statementSigningBytes(statement).toHex(), "not deterministic for $statement")
        }
    }

    @Test
    fun `distinct statements encode to distinct bytes, pairwise across the sample`() {
        val rnd = Random(SEED)
        val byStatement = LinkedHashMap<IdentityStatement, String>()
        repeat(SAMPLES) {
            val statement = randomStatement(rnd)
            byStatement[statement] = statementSigningBytes(statement).toHex()
        }

        val byBytes = LinkedHashMap<String, IdentityStatement>()
        for ((statement, hex) in byStatement) {
            val previous = byBytes.put(hex, statement)
            assertEquals(null, previous, "collision: $previous and $statement both encode to $hex")
        }
        assertEquals(byStatement.size, byBytes.size)
        assertTrue(byStatement.size > SAMPLES / 2, "sample degenerated to ${byStatement.size} distinct statements")
    }

    @Test
    fun `every single-field mutation of the six signed fields changes the bytes`() {
        val rnd = Random(SEED)
        repeat(SAMPLES) {
            val statement = randomStatement(rnd)
            val base = statementSigningBytes(statement).toHex()

            fun assertDiffers(what: String, mutated: IdentityStatement) {
                assertNotEquals(statement, mutated, "$what did not actually mutate the statement")
                assertNotEquals(base, statementSigningBytes(mutated).toHex(), "$what left the bytes unchanged: $statement")
            }

            assertDiffers("renaming name", statement.copy(name = PeerId(statement.name.name + "x")))
            assertDiffers("renaming keyId", statement.copy(keyId = KeyId(statement.keyId.name + "x")))
            assertDiffers("renaming issuer", statement.copy(issuer = IssuerId(statement.issuer.name + "x")))
            assertDiffers("bumping issuance", statement.copy(issuance = statement.issuance + 1))
            assertDiffers("bumping notBefore", statement.copy(notBefore = statement.notBefore + 1))
            assertDiffers("bumping notAfter", statement.copy(notAfter = statement.notAfter + 1))
        }
    }

    /**
     * The fourth property, exactly `computenet-9qgg`'s rule applied here:
     * every generated ill-formed string is refused in EACH of the three
     * string positions, with the exception naming that field.
     */
    @Test
    fun `every generated ill-formed string is refused, in each of the three string positions`() {
        val rnd = Random(SEED)
        val seen = LinkedHashSet<String>()

        repeat(SAMPLES) {
            val illFormed = illFormedName(rnd)
            seen.add(illFormed)
            val base = randomStatement(rnd)

            for ((field, statement) in listOf(
                "name" to base.copy(name = PeerId(illFormed)),
                "keyId" to base.copy(keyId = KeyId(illFormed)),
                "issuer" to base.copy(issuer = IssuerId(illFormed)),
            )) {
                val failure = assertFailsWith<IllegalArgumentException>("accepted $field $illFormed") {
                    statementSigningBytes(statement)
                }
                assertTrue(
                    failure.message!!.contains(field),
                    "rejection does not name $field: ${failure.message}",
                )
            }
        }

        assertTrue(seen.size > 12, "ill-formed generator degenerated to ${seen.size} distinct strings")
    }

    // --- generators -------------------------------------------------------

    private val peerNames = listOf("alice", "bob", "ed25519:aaa", "", "ünïcode-peer", "a".repeat(64))
    private val keyIdNames = listOf("ed25519:Zm9vYmFy", "ed25519:YW5jaG9y", "ed25519:aab", "")
    private val issuerNames = listOf("ed25519:YW5jaG9y", "ed25519:b3RoZXI", "ed25519:aac", "")

    private val unpairedUnits = listOf('\uD800', '\uDBFF', '\uDC00', '\uDFFF')
    private val spliceBases = listOf("", "alice", "ed25519:aaa", "über", "a🔑b")

    private fun illFormedName(rnd: Random): String {
        val base = spliceBases[rnd.nextInt(spliceBases.size)]
        val unit = unpairedUnits[rnd.nextInt(unpairedUnits.size)]
        val at = rnd.nextInt(base.length + 1)
        return base.substring(0, at) + unit + base.substring(at)
    }

    private fun randomStatement(rnd: Random): IdentityStatement = IdentityStatement(
        name = PeerId(peerNames[rnd.nextInt(peerNames.size)]),
        keyId = KeyId(keyIdNames[rnd.nextInt(keyIdNames.size)]),
        issuer = IssuerId(issuerNames[rnd.nextInt(issuerNames.size)]),
        issuance = rnd.nextLong(),
        notBefore = rnd.nextLong(),
        notAfter = rnd.nextLong(),
        signature = ByteArray(0),
    )
}
