package civictech.cell.host

import civictech.cell.data.SetCell
import civictech.nature.ContractRegistry
import civictech.testkit.awaitUntil
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `describe(ref)` — the metadata seam the topology inspector reads (M0-BE): the
 * concrete class of a published cell, captured on the rare publish path so no
 * consumer has to reflect at read time. Its only sanctioned join is
 * [ContractRegistry.cellDescriptor], the generated (authoritative) metadata.
 */
class LocationRegistryDescribeTest {

    @Test
    fun `a published cell describes as its concrete class`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val cell = SetCell<String>()

        host.managementInlet.call.spawn(cell)

        val type = registry.describe(cell.ref)
        type shouldBe SetCell::class.java
        val descriptor = ContractRegistry.cellDescriptor(type!!)
        descriptor.shouldNotBeNull()
        descriptor.ports.map { it.name }.toSet() shouldBe setOf("inlet", "outlet", "deltaInlet")
    }

    @Test
    fun `despawning drops the description with the location`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val cell = SetCell<String>()
        host.managementInlet.call.spawn(cell)

        host.managementInlet.call.despawn(cell.ref)

        // despawn is an ordinary (asynchronous) management call
        awaitUntil("despawn unpublished ${cell.ref}") { registry.locate(cell.ref) == null }
        registry.describe(cell.ref) shouldBe null
    }

    @Test
    fun `a registry-less host is invisible, not an error`() {
        val registry = LocationRegistry()
        val detached = ManagedHost()
        val cell = SetCell<String>()

        detached.managementInlet.call.spawn(cell)

        // nothing was published anywhere: no location, no description, no throw
        registry.describe(cell.ref) shouldBe null
        registry.locate(cell.ref) shouldBe null
        registry.localRefs().isEmpty() shouldBe true
    }

    @Test
    fun `a ref this registry never saw describes as null`() {
        val registry = LocationRegistry()
        registry.describe(civictech.cell.CellRef(java.util.UUID.randomUUID())) shouldBe null
    }

    @Test
    fun `a host resumed after a drain keeps its descriptions`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val cell = SetCell<String>()
        host.managementInlet.call.spawn(cell)

        host.managementInlet.call.drainHost()
        host.managementInlet.call.resumeHost()

        // resumeHost republishes without the instance in hand; the capture from
        // the original publish must survive it
        registry.describe(cell.ref) shouldBe SetCell::class.java
    }
}
