package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-1 (plan §2 F1 root, §4 PN-1): a hosted cell's port [PortRef] is DERIVED
 * from `(ownerRef, portName)` — the SetCell/MintedTags/watermark M10.1 pattern —
 * so it survives a rebuild. `MessageContext.sourcePort` therefore keys the same
 * durable identity across the rebuilt graph. Anonymous/test ports keep a fresh
 * random ref.
 */
class StablePortIdentityTest {

    class ProducerCell(override val ref: CellRef) : Cell {
        val outlet by output<Consumer<Int>>()
    }

    class ConsumerCell(override val ref: CellRef) : Cell {
        val inlet by input<Consumer<Int>>()
    }

    @Test
    fun `hosted port refs are equal across two builds of the same graph`() {
        val pRef = CellRef(UUID.randomUUID())
        val cRef = CellRef(UUID.randomUUID())

        val build1 = ProducerCell(pRef) to ConsumerCell(cRef)
        val build2 = ProducerCell(pRef) to ConsumerCell(cRef)

        // Fresh JVM objects, same refs + names → identical derived port identity.
        build2.first.outlet.ref shouldBe build1.first.outlet.ref
        build2.second.inlet.ref shouldBe build1.second.inlet.ref

        // Distinct (cellRef, name) pairs derive distinct refs — no collisions.
        build1.first.outlet.ref shouldNotBe build1.second.inlet.ref
    }

    @Test
    fun `anonymous ports keep a fresh random ref`() {
        // Not registered on a Cell → no PortIdentity → no derivation.
        FanOutlet.create<Consumer<Int>>().ref shouldNotBe FanOutlet.create<Consumer<Int>>().ref
        FanInlet.create<Consumer<Int>>().ref shouldNotBe FanInlet.create<Consumer<Int>>().ref
    }
}
