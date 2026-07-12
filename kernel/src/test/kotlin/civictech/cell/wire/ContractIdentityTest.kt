package civictech.cell.wire

import civictech.cell.BlockingCell
import civictech.cell.CellRef
import civictech.cell.data.Magnitude
import civictech.cell.data.Replicable
import civictech.cell.data.SetOps
import civictech.cell.proxy.Invocation
import civictech.gen.wire.ContractRegistry
import civictech.gen.wire.CellColor
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import civictech.gen.wire.StableHash
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

private data class SizedDelta(val value: Double) : Magnitude { override fun size() = value }
private interface MergeDelta : Replicable<Any>
private data class DeltaEnvelope<T>(val delta: T)

@Contract(effect = true)
private interface DescriptorBitsContract {
    fun sized(@Key delta: DeltaEnvelope<SizedDelta>)
    fun merged(@Key delta: DeltaEnvelope<MergeDelta>)
}

private class DescriptorBlockingCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : BlockingCell

/**
 * M5.1 (G-15 start, C-5): every kernel port contract has a generated
 * descriptor with ids derived only from FQN + erased JVM signature — the
 * stability contract the wire format (M5.2) builds on.
 */
class ContractIdentityTest {

    @Test
    fun `all kernel contracts are registered`() {
        ContractRegistry.contracts.map { it.fqn } shouldContainAll listOf(
            "civictech.cell.Consumer",
            "civictech.cell.data.SetOps",
            "civictech.cell.data.CounterOps",
            "civictech.cell.data.ListOps",
            "civictech.cell.data.MapOps",
            "civictech.cell.data.Propagate",
            "civictech.cell.membrane.TrafficLightControl",
            "civictech.cell.host.HostManagementApi",
            "civictech.cell.host.HostRoutingApi",
        )
    }

    @Test
    fun `ids derive from fqn and erased jvm signature only`() {
        val setOps = ContractRegistry.contracts.first { it.fqn == "civictech.cell.data.SetOps" }
        setOps.contractId shouldBe StableHash.of("civictech.cell.data.SetOps")
        setOps.methods.map { it.name }.toSet() shouldBe setOf("add", "remove")
        setOps.methods.first { it.name == "add" }.methodId shouldBe
            StableHash.of("civictech.cell.data.SetOps#add(Ljava/lang/Object;)V")

        // primitives erase to JVM primitive descriptors, not boxes
        val counterOps = ContractRegistry.contracts.first { it.fqn == "civictech.cell.data.CounterOps" }
        counterOps.methods.first { it.name == "increment" }.jvmDescriptor shouldBe "(J)V"
    }

    @Test
    fun `no id collisions across the registry`() {
        val contractIds = ContractRegistry.contracts.map { it.contractId }
        contractIds.toSet().size shouldBe contractIds.size

        val methodIds = ContractRegistry.contracts.flatMap { c -> c.methods.map { it.methodId } }
        methodIds.toSet().size shouldBe methodIds.size
    }

    @Test
    fun `management flag rides the descriptor`() {
        ContractRegistry.contracts.first { it.fqn == "civictech.cell.host.HostManagementApi" }
            .management shouldBe true
        ContractRegistry.contracts.first { it.fqn == "civictech.cell.data.SetOps" }
            .management shouldBe false
    }

    @Test
    fun `cycle key effect and cell color bits ride generated descriptors`() {
        val descriptor = ContractRegistry.descriptor(DescriptorBitsContract::class.java).shouldNotBeNull()
        descriptor.effect shouldBe true
        descriptor.methods.first { it.name == "sized" }.also {
            it.magnitude shouldBe true
            it.idempotentMerge shouldBe false
            it.keyIndex shouldBe 0
        }
        descriptor.methods.first { it.name == "merged" }.idempotentMerge shouldBe true
        ContractRegistry.cellDescriptor(DescriptorBlockingCell::class.java).shouldNotBeNull().color shouldBe CellColor.BLOCKING
    }

    @Test
    fun `reflective capture resolves to wire ids`() {
        val add = SetOps::class.java.getMethod("add", Any::class.java)
        val ids = ContractRegistry.idsOf(add).shouldNotBeNull()
        ids.first shouldBe StableHash.of("civictech.cell.data.SetOps")
        ids.second shouldBe StableHash.of("civictech.cell.data.SetOps#add(Ljava/lang/Object;)V")

        val invocation = Invocation.of(add, arrayOf("x"))
        invocation.contractId shouldBe ids.first
        invocation.methodId shouldBe ids.second
    }

    @Test
    fun `overloads get distinct method ids`() {
        val listOps = ContractRegistry.contracts.first { it.fqn == "civictech.cell.data.ListOps" }
        val adds = listOps.methods.filter { it.name == "add" }
        adds.size shouldBe 2
        adds.map { it.methodId }.toSet().size shouldBe 2
    }
}
