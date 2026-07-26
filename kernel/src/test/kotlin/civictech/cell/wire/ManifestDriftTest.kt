package civictech.cell.wire

import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.durability.InMemoryJournal
import civictech.cell.manifestOf
import civictech.gen.wire.ContractRegistry
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * PN-12. The **declared** manifest (KSP-derived, on the generated
 * [civictech.gen.wire.CellDescriptor]) must equal the **installed** manifest (the
 * runtime marker scan [manifestOf]) for every registered cell — the two are twin
 * scans over the same marker interfaces, and this pins that they never drift. It
 * also exercises the spawn-time consumption of the manifest (a durable cell placed
 * volatile is counted, not silent).
 */
class ManifestDriftTest {

    @Test
    fun `declared manifest equals the installed marker scan for every registered cell`() {
        val checked = ContractRegistry.cells.mapNotNull { descriptor ->
            val clazz = runCatching { Class.forName(descriptor.fqn) }.getOrNull() ?: return@mapNotNull null
            descriptor.fqn to (descriptor.manifest to manifestOf(clazz))
        }
        // sanity: the scan actually reached the durable/replicated/partitioned cells
        checked.isNotEmpty() shouldBe true
        checked.forEach { (fqn, pair) ->
            val (declared, installed) = pair
            withClue(fqn) { declared shouldBe installed }
        }
    }

    @Test
    fun `a durable cell spawned onto a null journal selector is counted, not silently lost`() {
        // volatile deployment (no journal): the durable writer's DURABLE manifest
        // is surfaced at spawn (the previously-silent durability gap).
        val volatileHost = ManagedHost()
        volatileHost.managementInlet.call.spawn(SetCell<String>())
        volatileHost.volatileDurableSpawns() shouldBe 1L

        // durable deployment (journal selected for the cell): not counted.
        val journal = InMemoryJournal()
        val durableHost = ManagedHost(journal = journal)
        durableHost.managementInlet.call.spawn(SetCell<String>())
        durableHost.volatileDurableSpawns() shouldBe 0L
    }
}
