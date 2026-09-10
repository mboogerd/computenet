package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.host.LeaderMark
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The argument domain is closed, and closing it is a security decision.
 *
 * `RegistryAnnounce` (kernel `wire/Peering.kt`) takes refs, link records, ids
 * and leader marks — nothing else — so anything else arriving here is either a
 * caller bug or
 * an attempt to get bytes signed that this encoding cannot describe. Encoding
 * it best-effort (`toString`, Java serialization) would break injectivity:
 * `toString` collides across types trivially, and Java serialization varies
 * with class metadata unrelated to the announcement's meaning. So the encoder
 * refuses, loudly, naming the offending type.
 */
class AnnouncementCanonicalBytesDomainTest {

    private fun inputWith(vararg args: Any?) = AnnouncementSigningInput(
        mintingPeerId = PeerId("ed25519:test"),
        counter = 1L,
        notAfter = 2L,
        contractId = 3L,
        methodId = 4L,
        cellRef = CellRef(UUID.fromString("00000000-0000-4000-8000-000000000001"), 0L),
        portName = "p",
        args = args.toList(),
    )

    @Test
    fun `the four RegistryAnnounce argument types are accepted`() {
        // Four, not three: `leaderMarked(LeaderMark)` is the fifth
        // `RegistryAnnounce` method and its argument joined the domain under tag
        // 0x04 (`computenet-f7h.2.2`). Before that it hit the `else` arm below,
        // so a keyed peering adopting a leader mark threw at encode.
        val bytes = canonicalBytes(
            inputWith(
                CellRef(UUID.randomUUID(), 1L),
                TopologyLink(UUID.randomUUID(), PortRef(UUID.randomUUID()), PortRef(UUID.randomUUID(), CellRef(UUID.randomUUID()))),
                UUID.randomUUID(),
                LeaderMark(UUID.randomUUID(), 7L, CellRef(UUID.randomUUID(), 3L)),
            ),
        )
        assertTrue(bytes.isNotEmpty())
    }

    /**
     * The mark's own encoding, isolated: tag `04`, then the three fields at
     * their pinned widths. Asserted as *bytes* rather than merely "it encodes",
     * because a `LeaderMark` arm that wrote its fields in a different order, or
     * omitted one, would still leave the accept test above green.
     */
    @Test
    fun `a LeaderMark argument is tag 0x04 followed by logicalId, epoch and leaderRef`() {
        val bytes = canonicalBytes(
            inputWith(
                LeaderMark(
                    logicalId = UUID.fromString("77777777-6666-4555-8444-333333333333"),
                    epoch = 5L,
                    leaderRef = CellRef(UUID.fromString("11111111-2222-4333-8444-555555555555"), 2L),
                ),
            ),
        )
        assertEquals(
            "04" + "77777777666645558444333333333333" + "0000000000000005" +
                "11111111222243338444555555555555" + "0000000000000002",
            bytes.copyOfRange(bytes.size - 49, bytes.size).toHex(),
        )
    }

    /**
     * The tag is what keeps the widened domain injective. A `LeaderMark` whose
     * fields are `(id, epoch, CellRef(refId, instanceId))` writes the same 48
     * payload bytes a bare `UUID` argument followed by... nothing else could —
     * but two *different* marks must still differ, and a mark must not collide
     * with any other accepted argument that happens to share its bytes.
     */
    @Test
    fun `distinct LeaderMarks encode distinctly, and never as another argument type`() {
        val id = UUID.fromString("77777777-6666-4555-8444-333333333333")
        val ref = CellRef(UUID.fromString("11111111-2222-4333-8444-555555555555"), 2L)
        val mark = LeaderMark(id, 5L, ref)

        val variants = listOf(
            mark,
            mark.copy(epoch = 6L),
            mark.copy(logicalId = UUID.fromString("77777777-6666-4555-8444-333333333334")),
            mark.copy(leaderRef = ref.copy(instanceId = 3L)),
            mark.copy(leaderRef = ref.copy(id = UUID.fromString("11111111-2222-4333-8444-555555555556"))),
        )
        val encodings = variants.map { canonicalBytes(inputWith(it)).toHex() }
        assertEquals(variants.size, encodings.toSet().size, "LeaderMark encodings collided: $encodings")

        // ... and against the other three tags over the same underlying values.
        val others = listOf(
            canonicalBytes(inputWith(ref, id)).toHex(),
            canonicalBytes(inputWith(id)).toHex(),
            canonicalBytes(inputWith(ref)).toHex(),
            canonicalBytes(inputWith(id, ref)).toHex(),
        )
        for (other in others) {
            assertTrue(other !in encodings, "a LeaderMark encoding collided with another argument shape")
        }
    }

    @Test
    fun `an argument outside the domain is rejected with its type named`() {
        for (offender in listOf<Any>("a string", 42, 42L, byteArrayOf(1), listOf(UUID.randomUUID()), PortRef(UUID.randomUUID()))) {
            val failure = assertFailsWith<IllegalArgumentException>("accepted ${offender.javaClass.name}") {
                canonicalBytes(inputWith(offender))
            }
            assertTrue(
                failure.message!!.contains(offender.javaClass.name),
                "message does not name the offending type: ${failure.message}",
            )
            // And it names the domain it was measured against, all four of it —
            // a message still naming three would send a reader looking for a
            // bug in a `leaderMarked` call that is in fact perfectly legal.
            assertTrue(
                failure.message!!.contains("(CellRef, TopologyLink, UUID, LeaderMark)"),
                "message does not name the four-type domain: ${failure.message}",
            )
        }
    }

    @Test
    fun `a null argument is rejected rather than encoded as an absence`() {
        val failure = assertFailsWith<IllegalArgumentException> { canonicalBytes(inputWith(null)) }
        assertTrue(failure.message!!.contains("null"), failure.message)
    }

    @Test
    fun `the rejection names the offending argument's position`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            canonicalBytes(inputWith(UUID.randomUUID(), CellRef(UUID.randomUUID()), "bad"))
        }
        assertTrue(failure.message!!.contains("argument 2"), failure.message)
    }

    @Test
    fun `an empty argument list is legal and still length-prefixed`() {
        // Zero args is what `unlinked`-less shapes and future no-arg announcements
        // look like; the count prefix is what keeps it distinguishable from an
        // input whose args were simply omitted.
        val bytes = canonicalBytes(inputWith())
        assertEquals("00000000", bytes.copyOfRange(bytes.size - 4, bytes.size).toHex())
    }
}
