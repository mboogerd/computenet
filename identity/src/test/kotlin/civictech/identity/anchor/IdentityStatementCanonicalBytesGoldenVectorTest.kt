package civictech.identity.anchor

import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.identity.announce.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden vector for [statementSigningBytes] (task `computenet-5y8t.2.1`).
 *
 * One fixed [IdentityStatement] and its entire encoding as a hex literal, the
 * `AnnouncementCanonicalBytesGoldenVectorTest` precedent: the bytes are the
 * assertion, not merely a property of them, because a signature honestly
 * minted under this encoding is worthless the moment a refactor moves a byte
 * without anyone noticing.
 *
 * The expected hex was derived independently of this implementation, by a
 * Python script over the grammar documented on [statementSigningBytes]
 * (`struct.pack('>I'/'>q')` + UTF-8 encoding), run by the breakdown agent on
 * `computenet-5y8t.2.1` on 2026-09-13 — so agreement here is two derivations
 * agreeing, not the code compared with itself.
 *
 * `unverified:` until run (breakdown did not build the code) — verified in
 * this task by the mutation below: deleting the tag write made this test
 * fail on a hex/length mismatch, and restoring it made the test pass again.
 */
class IdentityStatementCanonicalBytesGoldenVectorTest {

    /**
     * Chosen to exercise every field: a non-ASCII [PeerId] name (pins UTF-8,
     * `ü` = `c3bc`), realistic `ed25519:`-prefixed [KeyId] and [IssuerId]
     * names, and small distinct longs for `issuance`/`notBefore`/`notAfter`
     * so a field-order bug would not hide behind equal values.
     *
     * `signature = ByteArray(0)` on purpose: [statementSigningBytes] never
     * writes it, so an empty array here is exactly as valid a fixture as any
     * other — see the second test below, which pins that explicitly.
     */
    private val goldenStatement = IdentityStatement(
        name = PeerId("alice-ü"),
        keyId = KeyId("ed25519:Zm9vYmFy"),
        issuer = IssuerId("ed25519:YW5jaG9y"),
        issuance = 1L,
        notBefore = 1_000L,
        notAfter = 2_000L,
        signature = ByteArray(0),
    )

    /**
     * Field by field, in [statementSigningBytes]'s grammar order:
     *
     * ```
     * 00000023                                                            tag: 35 UTF-8 bytes follow
     * 636f6d707574656e65742f445343342f6964656e746974792d62696e64696e672f7631  "computenet/DSC4/identity-binding/v1"
     * 00000008                                                            name: 8 UTF-8 bytes follow
     * 616c6963652dc3bc                                                    "alice-ü" (ü = c3bc)
     * 00000010                                                            keyId: 16 UTF-8 bytes follow
     * 656432353531393a5a6d3976596d4679                                    "ed25519:Zm9vYmFy"
     * 00000010                                                            issuer: 16 UTF-8 bytes follow
     * 656432353531393a5957356a61473979                                    "ed25519:YW5jaG9y"
     * 0000000000000001                                                    issuance = 1
     * 00000000000003e8                                                    notBefore = 1000
     * 00000000000007d0                                                    notAfter = 2000
     * ```
     */
    private val goldenHex =
        "00000023636f6d707574656e65742f445343342f6964656e746974792d62696e" +
            "64696e672f763100000008616c6963652dc3bc00000010656432353531393a" +
            "5a6d3976596d467900000010656432353531393a5957356a6147397900000" +
            "0000000000100000000000003e800000000000007d0"

    @Test
    fun `the canonical encoding of the golden statement is byte-for-byte the pinned literal`() {
        assertEquals(goldenHex, statementSigningBytes(goldenStatement).toHex())
    }

    @Test
    fun `the golden encoding has the length its grammar predicts`() {
        // 4+35 tag, 4+8 name, 4+16 keyId, 4+16 issuer, 3x8 longs.
        assertEquals(115, statementSigningBytes(goldenStatement).size)
        assertEquals(115 * 2, goldenHex.length)
    }

    /**
     * [IdentityStatement.signature] is outside the signed region: a copy
     * differing only in `signature` MUST encode to the same bytes, or a
     * verifier could never reproduce the bytes a signer actually signed
     * without already knowing the signature it is trying to verify.
     */
    @Test
    fun `a statement differing only in signature encodes to the same bytes`() {
        val resigned = goldenStatement.copy(signature = byteArrayOf(1, 2, 3))
        assertEquals(
            statementSigningBytes(goldenStatement).toHex(),
            statementSigningBytes(resigned).toHex(),
        )
    }
}
