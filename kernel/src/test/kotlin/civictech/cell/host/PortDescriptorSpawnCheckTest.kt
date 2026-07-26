package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.gen.wire.CellColor
import civictech.gen.wire.CellDescriptor
import civictech.gen.wire.ContractModule
import civictech.gen.wire.ContractRegistry
import civictech.gen.wire.PortDescriptor
import civictech.gen.wire.PortDirection
import civictech.gen.wire.StableHash
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class PortDescriptorSpawnCheckTest {

    @Test
    fun `generated cell descriptors carry the port table`() {
        val descriptor = ContractRegistry.cellDescriptor(SetCell::class.java)
        descriptor.shouldNotBeNull()
        // set comparison: declaration order shifts between subclass-declared and
        // base-inherited ports and is not part of the descriptor contract
        descriptor.ports.map { it.name to it.direction }.toSet() shouldBe setOf(
            "inlet" to PortDirection.IN,
            "outlet" to PortDirection.OUT,
            "deltaInlet" to PortDirection.IN,
        )
        descriptor.ports.map { it.contractFqn } shouldContain "civictech.cell.Propagate"
    }

    /** Deliberately mis-registered: property `left`, registry name `lft`. */
    private class MisregisteredCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val left = registerPort("lft", FanInlet.create<Propagate<SetDelta<String>>>())
    }

    @Test
    fun `spawn rejects a cell whose registry names miss a declared port`() {
        val fqn = MisregisteredCell::class.java.name.replace('$', '.')
        ContractRegistry.register(object : ContractModule {
            override val contracts = emptyList<civictech.gen.wire.ContractDescriptor>()
            override val cells = listOf(
                CellDescriptor(
                    fqn, CellColor.PURE,
                    ports = listOf(
                        PortDescriptor(
                            "left", PortDirection.IN,
                            "civictech.cell.Propagate", StableHash.of("civictech.cell.Propagate"),
                        )
                    ),
                )
            )
        })

        val host = ManagedHost()
        val ex = assertThrows<IllegalArgumentException> {
            host.managementInlet.call.spawn(MisregisteredCell())
        }
        ex.message!!.let {
            it shouldContainText "left"
            it shouldContainText "lft"
        }
    }
}
