package civictech.cell.host

import civictech.cell.CellRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Standalone unit coverage for [DeliveryHold]: no [LocationRegistry] involved,
 * just the hold set and its release callback, mirroring the pre-extraction
 * `held -= ref; locations[ref]?.let { replay(ref, it) }` behaviour.
 */
class DeliveryHoldTest {

    @Test
    fun `hold then isHeld reports true, release then isHeld reports false`() {
        val released = mutableListOf<CellRef>()
        val hold = DeliveryHold { released += it }
        val cellRef = CellRef(UUID.randomUUID())

        hold.isHeld(cellRef) shouldBe false
        hold.hold(cellRef)
        hold.isHeld(cellRef) shouldBe true

        hold.release(cellRef)
        hold.isHeld(cellRef) shouldBe false
    }

    @Test
    fun `release invokes the callback exactly once per release call`() {
        val released = mutableListOf<CellRef>()
        val hold = DeliveryHold { released += it }
        val cellRef = CellRef(UUID.randomUUID())

        hold.hold(cellRef)
        hold.release(cellRef)

        released shouldBe listOf(cellRef)

        hold.release(cellRef)

        released shouldBe listOf(cellRef, cellRef)
    }

    @Test
    fun `release of a never-held ref does not throw and still invokes the callback`() {
        val released = mutableListOf<CellRef>()
        val hold = DeliveryHold { released += it }
        val cellRef = CellRef(UUID.randomUUID())

        hold.isHeld(cellRef) shouldBe false
        hold.release(cellRef)

        released shouldBe listOf(cellRef)
        hold.isHeld(cellRef) shouldBe false
    }
}
