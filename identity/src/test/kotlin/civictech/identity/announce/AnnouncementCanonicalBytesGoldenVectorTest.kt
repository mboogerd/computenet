package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.host.LeaderMark
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden vector: one fixed [AnnouncementSigningInput] and its **entire**
 * encoding as a hex literal.
 *
 * This is the test that makes the encoding consumable without re-derivation.
 * Signatures minted over these bytes live on the wire and, once feature .4
 * lands, in peers' memories; a refactor that changes the layout does not break
 * a compile and does not break a determinism or injectivity property — every
 * such property still holds for the *new* layout. What it breaks is every
 * signature already in the field, silently. So the bytes themselves are the
 * assertion.
 *
 * The expected hex was derived independently of the implementation (by hand
 * from the documented grammar, in a separate script), so agreement here is two
 * derivations agreeing rather than the code being compared with itself. The
 * annotated breakdown below is the grammar written out; keep it in step with
 * the literal if the encoding is ever deliberately versioned.
 *
 * **If this test goes red, the correct response is almost never to update the
 * literal.** Either the change to the encoding was unintended, or it is
 * intended and needs an explicit format version and a migration story for
 * signatures minted under the old one.
 *
 * The unpaired-surrogate decision (`computenet-9qgg`) deliberately left this
 * literal untouched, and that is an argument that carried weight in the
 * decision rather than an oversight: rejecting ill-formed strings narrows the
 * accepted domain without redefining the bytes of anything already accepted,
 * whereas the rejected alternative — encoding UTF-16 code units — would have
 * changed the bytes of every string and so every announcement here. The port
 * name below is well-formed UTF-16 (`über`, and no surrogate at all), so it
 * encodes exactly as it did before.
 */
class AnnouncementCanonicalBytesGoldenVectorTest {

    /**
     * Chosen to exercise every branch of the grammar in one input: a non-ASCII
     * port name (pins UTF-8, not UTF-16 or the platform charset), negative
     * longs (pins two's complement big-endian), the first three argument tags,
     * and both states of the nullable [PortRef.cell].
     *
     * **Deliberately left byte-for-byte as it was** when tag `0x04`
     * ([LeaderMark]) joined the domain (`computenet-f7h.2.2`). That is the
     * compatibility claim, and leaving this input and [goldenHex] alone is how
     * it is pinned: adding a *new* tag must not perturb the bytes of anything
     * already encodable, or every signature already in the field is silently
     * invalidated. The mark's own bytes are pinned by [leaderMarkGoldenInput]
     * below, which appends one to this input rather than replacing it.
     */
    private val goldenInput = AnnouncementSigningInput(
        mintingPeerId = PeerId("ed25519:Zm9vYmFy"),
        counter = 7L,
        notAfter = 1_700_000_000_000L,
        contractId = 0x0102030405060708L,
        methodId = -2L,
        cellRef = CellRef(UUID.fromString("00000000-0000-4000-8000-00000000002a"), 42L),
        portName = "orders/über",
        args = listOf(
            CellRef(UUID.fromString("11111111-2222-4333-8444-555555555555"), -1L),
            TopologyLink(
                id = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                from = PortRef(
                    id = UUID.fromString("01234567-89ab-4cde-8f01-234567890abc"),
                    cell = CellRef(UUID.fromString("deadbeef-0000-4000-8000-000000000001"), 9L),
                ),
                to = PortRef(id = UUID.fromString("fedcba98-7654-4321-8fed-cba987654321"), cell = null),
            ),
            UUID.fromString("99999999-8888-4777-8666-555544443333"),
        ),
    )

    /**
     * Field by field, in [AnnouncementSigningInput]'s constructor order:
     *
     * ```
     * 00000010                          mintingPeerId: 16 UTF-8 bytes follow
     * 656432353531393a5a6d3976596d4679  "ed25519:Zm9vYmFy"
     * 0000000000000007                  counter = 7
     * 0000018bcfe56800                  notAfter = 1700000000000
     * 0102030405060708                  contractId
     * fffffffffffffffe                  methodId = -2
     * 0000000000004000 800000000000002a cellRef.id
     * 000000000000002a                  cellRef.instanceId = 42
     * 0000000c                          portName: 12 UTF-8 bytes follow
     * 6f72646572732fc3bc626572          "orders/über" (ü = c3bc)
     * 00000003                          args: 3 elements follow
     * 01                                  tag CellRef
     *   1111111122224333 8444555555555555    .id
     *   ffffffffffffffff                     .instanceId = -1
     * 02                                  tag TopologyLink
     *   aaaaaaaabbbb4ccc 8dddeeeeeeeeeeee    .id
     *   0123456789ab4cde 8f01234567890abc    .from.id
     *   01                                   .from.cell present
     *     deadbeef00004000 8000000000000001     .from.cell.id
     *     0000000000000009                      .from.cell.instanceId = 9
     *   fedcba9876544321 8fedcba987654321     .to.id
     *   00                                   .to.cell absent
     * 03                                  tag UUID
     *   9999999988884777 8666555544443333
     * ```
     */
    private val goldenHex =
        "00000010656432353531393a5a6d3976596d467900000000000000070000018b" +
            "cfe568000102030405060708fffffffffffffffe000000000000400080000000" +
            "0000002a000000000000002a0000000c6f72646572732fc3bc62657200000003" +
            "0111111111222243338444555555555555ffffffffffffffff02aaaaaaaabbbb" +
            "4ccc8dddeeeeeeeeeeee0123456789ab4cde8f01234567890abc01deadbeef00" +
            "00400080000000000000010000000000000009fedcba98765443218fedcba987" +
            "654321000399999999888847778666555544443333"

    @Test
    fun `the canonical encoding of the golden input is byte-for-byte the pinned literal`() {
        assertEquals(goldenHex, canonicalBytes(goldenInput).toHex())
    }

    @Test
    fun `the golden encoding has the length its grammar predicts`() {
        // 4+16 peerId, 4x8 longs, 24 cellRef, 4+12 portName, 4 arg count,
        // 1+24 CellRef arg, 1+16+(16+1+24)+(16+1) TopologyLink arg, 1+16 UUID arg.
        assertEquals(213, canonicalBytes(goldenInput).size)
        assertEquals(213 * 2, goldenHex.length)
    }

    // ------------------------------------------------ tag 0x04, LeaderMark

    /**
     * [goldenInput] with one [LeaderMark] appended as a fourth argument — the
     * second golden vector (`computenet-f7h.2.2`).
     *
     * Appending rather than substituting is the point: the first 209 bytes of
     * [leaderMarkGoldenHex] are [goldenHex]'s first 209 bytes verbatim, and the
     * only pre-existing byte that moves is the four-byte argument count. That
     * relationship is itself asserted below, so this vector pins both the mark's
     * bytes and the claim that tag `0x04` disturbs nothing that came before it.
     */
    private val leaderMarkGoldenInput = goldenInput.copy(
        args = goldenInput.args + LeaderMark(
            logicalId = UUID.fromString("77777777-6666-4555-8444-333333333333"),
            epoch = 5L,
            leaderRef = CellRef(UUID.fromString("11111111-2222-4333-8444-555555555555"), 2L),
        ),
    )

    /**
     * [goldenHex] with `00000003` -> `00000004` and 49 bytes appended:
     *
     * ```
     * 00000004                          args: 4 elements follow
     * ... the three arguments of goldenHex, unchanged ...
     * 04                                  tag LeaderMark
     *   7777777766664555 8444333333333333    .logicalId
     *   0000000000000005                     .epoch = 5
     *   1111111122224333 8444555555555555    .leaderRef.id
     *   0000000000000002                     .leaderRef.instanceId = 2
     * ```
     *
     * Derived by hand from the grammar first (1 + 16 + 8 + 24 = 49 bytes,
     * 213 + 49 = 262) and then observed from the function; the two agreed.
     */
    private val leaderMarkGoldenHex =
            "00000010656432353531393a5a6d3976596d467900000000000000070000018b" +
            "cfe568000102030405060708fffffffffffffffe000000000000400080000000" +
            "0000002a000000000000002a0000000c6f72646572732fc3bc62657200000004" +
            "0111111111222243338444555555555555ffffffffffffffff02aaaaaaaabbbb" +
            "4ccc8dddeeeeeeeeeeee0123456789ab4cde8f01234567890abc01deadbeef00" +
            "00400080000000000000010000000000000009fedcba98765443218fedcba987" +
            "6543210003999999998888477786665555444433330477777777666645558444" +
            "3333333333330000000000000005111111112222433384445555555555550000" +
            "000000000002"

    @Test
    fun `the canonical encoding of the LeaderMark golden input is byte-for-byte the pinned literal`() {
        assertEquals(leaderMarkGoldenHex, canonicalBytes(leaderMarkGoldenInput).toHex())
    }

    @Test
    fun `the LeaderMark golden encoding has the length its grammar predicts`() {
        // 213 as above, plus 1 tag + 16 logicalId + 8 epoch + 24 leaderRef = 49.
        assertEquals(262, canonicalBytes(leaderMarkGoldenInput).size)
        assertEquals(262 * 2, leaderMarkGoldenHex.length)
    }

    /**
     * The compatibility claim, stated as bytes rather than as prose: adding tag
     * `0x04` moved **nothing** except the argument count. Everything up to and
     * including the port name is identical, and so is every byte of the three
     * pre-existing arguments.
     *
     * This is the assertion that would go red if a later change to the encoding
     * were smuggled in alongside a domain widening — the case the class KDoc
     * says must never be quietly accepted.
     */
    @Test
    fun `appending a LeaderMark leaves every pre-existing byte where it was`() {
        // Up to the argument count: the prefix is shared verbatim.
        val countAt = goldenHex.indexOf("00000003", goldenHex.indexOf("6f72646572732fc3bc626572"))
        assertEquals(goldenHex.substring(0, countAt), leaderMarkGoldenHex.substring(0, countAt))
        // The count itself, and only it, changes.
        assertEquals("00000003", goldenHex.substring(countAt, countAt + 8))
        assertEquals("00000004", leaderMarkGoldenHex.substring(countAt, countAt + 8))
        // The three original arguments, then the 49 new bytes and nothing else.
        val originalArgs = goldenHex.substring(countAt + 8)
        assertEquals(originalArgs, leaderMarkGoldenHex.substring(countAt + 8, countAt + 8 + originalArgs.length))
        assertEquals(49 * 2, leaderMarkGoldenHex.length - (countAt + 8 + originalArgs.length))
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
