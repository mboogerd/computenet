package civictech.identity.announce

import civictech.cell.CellRef
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
 * `RegistryAnnounce` (kernel `wire/Peering.kt`) takes refs, link records and
 * ids — nothing else — so anything else arriving here is either a caller bug or
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
    fun `the three RegistryAnnounce argument types are accepted`() {
        val bytes = canonicalBytes(
            inputWith(
                CellRef(UUID.randomUUID(), 1L),
                TopologyLink(UUID.randomUUID(), PortRef(UUID.randomUUID()), PortRef(UUID.randomUUID(), CellRef(UUID.randomUUID()))),
                UUID.randomUUID(),
            ),
        )
        assertTrue(bytes.isNotEmpty())
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
