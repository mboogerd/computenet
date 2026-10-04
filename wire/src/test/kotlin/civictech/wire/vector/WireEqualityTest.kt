package civictech.wire.vector

import civictech.cell.Borrowed
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.wire.WireCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WireEqualityTest {

    private val seed: VectorDocument =
        VectorLoader.locate().documents().first { it.kind == VectorKind.FRAME && it.encoded?.utf8 != null }

    @Test
    fun `independent wrapper decodes compare structurally without consuming the sender's Owned value`() {
        val senderOwned = Owned("payload")
        val base = NeutralValues.frameOf(seed).invocation
        val invocation = base.copy(
            invocation = base.invocation.copy(
                args = listOf(
                    senderOwned,
                    Frozen(42L),
                    Borrowed(Owned("inner")),
                ),
            ),
        )

        val encoded = WireCodec.encode(invocation)
        val first = WireCodec.decodeFrame(encoded)
        val second = WireCodec.decodeFrame(encoded)

        assertNotEquals(first, second, "plain data-class equality must expose the wrappers' identity equality")
        assertEquals(
            WireEquality.normalize(first),
            WireEquality.normalize(second),
            "normalization must compare wrapper kind and recursively normalized contents",
        )
        assertNotEquals(WireEquality.normalize(Owned("x")), WireEquality.normalize(Owned("y")))
        assertNotEquals(WireEquality.normalize(Owned("x")), WireEquality.normalize(Frozen("x")))
        assertEquals(
            listOf(WireEquality.WrapperView("Owned", "x")),
            WireEquality.normalize(listOf(Owned("x"))),
        )
        assertEquals(
            listOf(
                WireEquality.WrapperView("Frozen", listOf(1L, 2L)),
                WireEquality.WrapperView(
                    "Borrowed",
                    linkedMapOf("k" to WireEquality.WrapperView("Owned", "inner")),
                ),
            ),
            WireEquality.normalize(
                listOf(
                    Frozen(listOf(1L, 2L)),
                    Borrowed(linkedMapOf("k" to Owned("inner"))),
                ),
            ),
            "normalization must recurse through List and Map values",
        )
        assertEquals("payload", senderOwned.take(), "normalization and codec encoding must not consume the sender's Owned value")
    }
}
