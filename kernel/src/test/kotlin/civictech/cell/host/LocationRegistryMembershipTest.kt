package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Registry-level membership/listener ordering (KX-08, KX-09/KX-30, KX-13,
 * KX-14): the [InstanceIndex] composed into [LocationRegistry] must already
 * reflect a publish, and must already have forgotten an unpublish, at the
 * exact moment a listener fires for it — `Replication.maybeLink` reads
 * [LocationRegistry.replicasOf] from inside the publish hook
 * (`Replication.kt` ~L205), so a regression here silently stops gossip links
 * forming.
 *
 * [InstanceIndex]'s own standalone unit tests (`InstanceIndexTest`) prove the
 * index half in isolation, with no registry present. These tests prove the
 * registry composes it at the right lexical position relative to its
 * listener notifications.
 */
class LocationRegistryMembershipTest {

    private class PeerSink(private val name: String) : InvocationSink {
        override fun deliver(invocation: HostedPortInvocation) = Unit
        override fun toString() = "peer($name)"
    }

    private fun sink(name: String = UUID.randomUUID().toString()): InvocationSink = PeerSink(name)

    // ------------------------------------------------------------------ BS-1

    @Test
    fun `instancesOf already reports a ref when the onPublish listener fires for it`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val ref = CellRef(UUID.randomUUID())
        val seenAtPublish = mutableListOf<Set<CellRef>>()
        registry.onPublish { seenAtPublish += registry.instancesOf(it.id) }

        registry.publish(ref, host)

        seenAtPublish shouldBe listOf(setOf(ref))
    }

    // ------------------------------------------------------------------ BS-2

    @Test
    fun `replicasOf reflects only the survivor when the onUnpublish listener fires`() {
        val registry = LocationRegistry()
        val logicalId = UUID.randomUUID()
        val survivor = CellRef(logicalId, instanceId = 1)
        val dropped = CellRef(logicalId, instanceId = 2)
        registry.publish(survivor, sink())
        registry.publish(dropped, sink())
        val seenAtUnpublish = mutableListOf<Set<CellRef>>()
        registry.onUnpublish { seenAtUnpublish += registry.replicasOf(logicalId) }

        registry.unpublish(dropped)

        seenAtUnpublish shouldBe listOf(setOf(survivor))
    }

    @Test
    fun `mirrorUnpublish also drops membership before the onUnpublish listener fires`() {
        val registry = LocationRegistry()
        val logicalId = UUID.randomUUID()
        val survivor = CellRef(logicalId, instanceId = 1)
        val dropped = CellRef(logicalId, instanceId = 2)
        registry.publish(survivor, sink())
        registry.publish(dropped, sink())
        val seenAtUnpublish = mutableListOf<Set<CellRef>>()
        registry.onUnpublish { seenAtUnpublish += registry.replicasOf(logicalId) }

        registry.mirrorUnpublish(dropped)

        seenAtUnpublish shouldBe listOf(setOf(survivor))
    }

    // -------------------------------------------------------------- BS-3 (registry half)

    @Test
    fun `unpublishing the last instance leaves instancesOf empty at the registry`() {
        val registry = LocationRegistry()
        val logicalId = UUID.randomUUID()
        val ref = CellRef(logicalId)
        registry.publish(ref, sink())

        registry.unpublish(ref)

        registry.instancesOf(logicalId).shouldBeEmpty()
        registry.replicasOf(logicalId).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ BS-4

    @Test
    fun `re-publishing to a new host without an intervening unpublish reports the ref exactly once and locates the new host`() {
        val registry = LocationRegistry()
        val hostA = ManagedHost(registry = registry)
        val hostB = ManagedHost(registry = registry)
        val ref = CellRef(UUID.randomUUID())

        registry.publish(ref, hostA)
        registry.publish(ref, hostB)

        registry.instancesOf(ref.id) shouldBe setOf(ref)
        registry.locate(ref) shouldBe hostB
    }
}
