package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.data.SetCell
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [LocationRegistry.retire] / [LocationRegistry.unretire] (computenet-vzb,
 * gyvli-D3): a retired ref is refused at [LocationRegistry.deliver] — not
 * delivered, not parked — and counted in [LocationRegistry.retiredRefusals].
 * Every other ref keeps the ordinary contract: no location means park.
 */
class LocationRegistryRetireTest {

    private fun invocation(cellRef: CellRef, arg: Any = "v") =
        HostedPortInvocation(
            cellRef,
            "inlet",
            HostedPortInvocation.Type.PORT_API,
            Invocation("provide", listOf("java.lang.Object"), listOf(arg)),
        )

    @Test
    fun `a retired ref refuses a late invocation instead of parking it, and counts it`() {
        val registry = LocationRegistry()
        val retired = CellRef(UUID.randomUUID())
        registry.retire(listOf(retired))

        registry.deliver(invocation(retired))

        registry.parkedFor(retired).shouldBeEmpty()
        registry.retiredRefusals shouldBe 1L
        registry.retiredRefs() shouldBe setOf(retired)
    }

    @Test
    fun `control - an unretired ref with no location still parks`() {
        val registry = LocationRegistry()
        val absent = CellRef(UUID.randomUUID())

        registry.deliver(invocation(absent))

        registry.parkedFor(absent).size shouldBe 1
        registry.retiredRefusals shouldBe 0L
    }

    @Test
    fun `retiring a ref refuses and counts what was already parked against it`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        registry.deliver(invocation(ref, "1"))
        registry.deliver(invocation(ref, "2"))
        registry.parkedFor(ref).size shouldBe 2

        registry.retire(listOf(ref))

        registry.parkedFor(ref).shouldBeEmpty()
        registry.retiredRefusals shouldBe 2L
    }

    @Test
    fun `retiring a published ref removes its location and announces the unpublish once`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val cell = SetCell<String>()
        host.managementInlet.call.spawn(cell)
        val localUnpublishes = mutableListOf<CellRef>()
        val unpublishes = mutableListOf<CellRef>()
        registry.onLocalUnpublish { localUnpublishes += it }
        registry.onUnpublish { unpublishes += it }

        registry.retire(listOf(cell.ref))
        registry.location(cell.ref).shouldBeNull()
        registry.localRefs().contains(cell.ref) shouldBe false

        // the host's own despawn of the retired cell does not announce it again
        host.managementInlet.call.despawn(cell.ref)
        localUnpublishes shouldContainExactly listOf(cell.ref)
        unpublishes shouldContainExactly listOf(cell.ref)

        registry.deliver(invocation(cell.ref))
        registry.parkedFor(cell.ref).shouldBeEmpty()
        registry.retiredRefusals shouldBe 1L
    }

    @Test
    fun `a refused invocation's exclusives are discharged, not dropped`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        val owned = Owned("payload")
        val returned = mutableListOf<String>()
        val leased = Leased("lease") { returned += it }
        // one parked before the retirement, one arriving after it
        registry.deliver(invocation(ref, owned))
        registry.retire(listOf(ref))
        registry.deliver(invocation(ref, leased))

        registry.retiredRefusals shouldBe 2L
        shouldThrow<IllegalStateException> { owned.take() }
        returned shouldContainExactly listOf("lease")
    }

    @Test
    fun `retire is idempotent`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        registry.retire(listOf(ref))
        registry.retire(listOf(ref))
        registry.deliver(invocation(ref))

        registry.retiredRefusals shouldBe 1L
        registry.retiredRefs() shouldBe setOf(ref)
    }

    @Test
    fun `unretire lifts the tombstone - the ref parks again`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        registry.retire(listOf(ref))
        registry.unretire(listOf(ref))

        registry.retiredRefs().shouldBeEmpty()
        registry.deliver(invocation(ref))
        registry.parkedFor(ref).size shouldBe 1
        registry.retiredRefusals shouldBe 0L
    }
}
