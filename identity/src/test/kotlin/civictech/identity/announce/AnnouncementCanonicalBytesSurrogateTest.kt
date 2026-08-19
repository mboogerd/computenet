package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.link.PeerId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The string domain is closed too, and closing it is the same security decision
 * the argument domain rests on (`computenet-9qgg`).
 *
 * `String.toByteArray(UTF_8)` substitutes `?` (`0x3f`) for an unpaired
 * surrogate, so before this rule three distinct announcements shared one
 * encoding. Measured against the unfixed encoder, `portName = "\uD800"` and
 * `portName = "?"` both produced
 *
 * ```
 * 0000000c656432353531393a74657374  peer name "ed25519:test", 12 bytes
 * 0000000000000001 0000000000000002  counter, notAfter
 * 0000000000000003 0000000000000004  contractId, methodId
 * 0000000000004000 8000000000000001  cellRef.id
 * 0000000000000000                   cellRef.instanceId
 * 00000001 3f                        portName: one byte, and it is '?'
 * 00000000                           args: empty
 * ```
 *
 * — note the single `3f` where the port name is — and `"\uDC00"` produced the
 * same bytes again. A signature honestly minted for one verified the other two.
 *
 * The decision is to **reject**, not to switch the grammar to UTF-16 code units:
 * rejection leaves the bytes of every accepted input untouched (nothing already
 * signed is invalidated, the golden vector needs no re-pin), keeps UTF-8 as the
 * interoperable canonical form, and refuses once here instead of making a
 * nonsense port name validly signed. Reasoning in full: [canonicalBytes] KDoc.
 */
class AnnouncementCanonicalBytesSurrogateTest {

    private val base = AnnouncementSigningInput(
        mintingPeerId = PeerId("ed25519:test"),
        counter = 1L,
        notAfter = 2L,
        contractId = 3L,
        methodId = 4L,
        cellRef = CellRef(UUID.fromString("00000000-0000-4000-8000-000000000001"), 0L),
        portName = "p",
        args = emptyList(),
    )

    /**
     * The unpaired shapes, each with the index the message must name. A lone
     * surrogate is the obvious one; the reversed pair and the truncated-tail
     * cases are what a naive "does it contain a surrogate followed by anything"
     * or "does it start with a surrogate" check would let through.
     */
    private val illFormed = listOf(
        "\uD800" to 0, // lone high surrogate
        "\uDC00" to 0, // lone low surrogate
        "\uDC00\uD800" to 0, // low then high — a pair in the wrong order
        "orders/\uD800" to 7, // high surrogate at the end of an otherwise valid name
        "\uDC00orders" to 0, // low surrogate before valid text
        "a\uD800b" to 1, // high surrogate followed by a non-surrogate
        "🔑\uD800" to 2, // a well-formed pair (🔑) then a lone high surrogate
        "\uD800𐀀" to 0, // high, then a well-formed pair
    )

    @Test
    fun `an unpaired surrogate in portName is rejected, naming the field and the index`() {
        for ((value, index) in illFormed) {
            val failure = assertFailsWith<IllegalArgumentException>("accepted portName ${value.codeUnits()}") {
                canonicalBytes(base.copy(portName = value))
            }
            val message = failure.message!!
            assertTrue(message.contains("portName"), "message does not name the field: $message")
            assertTrue(message.contains("at index $index"), "message does not name index $index: $message")
        }
    }

    @Test
    fun `an unpaired surrogate in the minting peer name is rejected, naming that field`() {
        for ((value, _) in illFormed) {
            val failure = assertFailsWith<IllegalArgumentException>("accepted peer name ${value.codeUnits()}") {
                canonicalBytes(base.copy(mintingPeerId = PeerId("ed25519:$value")))
            }
            assertTrue(
                failure.message!!.contains("mintingPeerId.name"),
                "message does not name the field: ${failure.message}",
            )
        }
    }

    /**
     * The regression proper: the three values that used to share one encoding.
     * Now exactly one of them is signable, so no signature can cover more than
     * one of the three — which is what injectivity buys.
     */
    @Test
    fun `the three formerly-colliding portNames can no longer share a signature`() {
        val questionMark = canonicalBytes(base.copy(portName = "?")).toHex()
        for (surrogate in listOf("\uD800", "\uDC00")) {
            assertFailsWith<IllegalArgumentException> { canonicalBytes(base.copy(portName = surrogate)) }
        }
        // "?" itself is an ordinary port name and still encodes, unchanged: the
        // rejection narrows the domain, it does not re-define the bytes.
        assertEquals("0000000c656432353531393a74657374", questionMark.take(32))
        assertTrue(questionMark.contains("000000013f"), "the '?' port name no longer encodes as one 0x3f byte")
    }

    @Test
    fun `well-formed UTF-16 including surrogate pairs is accepted and stays injective`() {
        val wellFormed = listOf("", "p", "?", "orders/über", "🔑", "🔑orders🔑", "a".repeat(64))
        val byBytes = LinkedHashMap<String, String>()
        for (value in wellFormed) {
            val previous = byBytes.put(canonicalBytes(base.copy(portName = value)).toHex(), value)
            assertEquals(null, previous, "collision: $previous and ${value.codeUnits()}")
        }
        assertEquals(wellFormed.size, byBytes.size)

        // A surrogate pair is one code point and must survive as its 4 UTF-8
        // bytes, not as a rejected string and not as two '?' — the pair check
        // must not have been implemented as "contains any surrogate".
        val pair = canonicalBytes(base.copy(portName = "🔑")).toHex()
        assertTrue(pair.contains("00000004f09f9491"), "🔑 did not encode as its 4 UTF-8 bytes: $pair")
    }

    @Test
    fun `rejection happens before any bytes are attributed to the offending string`() {
        // Both string fields ill-formed: the FIRST field in constructor order is
        // the one named, so the failure points at the earliest offence rather
        // than at whichever check happens to run first.
        val failure = assertFailsWith<IllegalArgumentException> {
            canonicalBytes(base.copy(mintingPeerId = PeerId("\uD800"), portName = "\uD800"))
        }
        assertTrue(failure.message!!.contains("mintingPeerId.name"), failure.message)
        assertNotEquals(true, failure.message!!.contains("portName"))
    }
}

internal fun String.codeUnits(): String = map { "%04x".format(it.code) }.toString()
