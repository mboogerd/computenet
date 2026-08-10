package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-dqy.5 — the disconnect fence
 * ([RegistryMirrorCell.detach]/[RegistryMirrorCell.attach]).
 *
 * **The race.** A peer's announcements are applied *asynchronously*, two
 * scheduler hops behind the connection: the transport hands a frame to a hosted
 * [BridgeIngressCell] ([Peering.hostIngress]), which decodes it on the bridge
 * host and hands the invocation back to [LocationRegistry.deliver], which
 * queues it again for the connection's [RegistryMirrorCell]. The disconnect, by
 * contrast, was retracted straight from the transport's IO thread
 * (`WsTransport.Session.onClose` → [LocationRegistry.unpublishRemotes]). So an
 * announcement decoded *before* a close could be *applied* after it, and
 * re-install [LocationRegistry.Remote] locations routed through an egress whose
 * socket is gone. Nothing retracted them afterwards: the close had already run,
 * and the departed peer never announces again on that connection. The
 * `:demo:shopping` `TwoJvmInspectorTest` relaunch case is where that surfaced —
 * peer A's inspector kept reporting the killed peer's mirrored cells, so
 * "peer A retracted B's cells" could never come true.
 *
 * These are deterministic, not timing tests: applying an announcement after the
 * close is what the two queue hops make ordinary, and both halves are driven
 * directly (`mirror.inlet.call`) or through a [SimulationController], never by
 * racing threads.
 */
class MirrorCloseFenceTest {

    private fun mirror(registry: LocationRegistry, egress: BridgeEgressCell, peer: PeerId? = PeerId("jvm-b")) =
        Peering.spawnMirror(
            Peering.Side(registry, ManagedHost(registry = registry)),
            toPeer = egress,
            peer = peer,
        )

    // ------------------------------------------------------------- the fence

    @Test
    fun `an announcement applied after the close does not resurrect the peer's locations`() {
        val registry = LocationRegistry()
        val egress = BridgeEgressCell()
        val mirror = mirror(registry, egress)

        val early = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(early)
        registry.location(early).shouldNotBeNull()

        // the socket dies; the transport fences the connection off
        mirror.detach()
        registry.location(early).shouldBeNull()

        // the frames the bridge host was still holding when the close ran land
        // now — the batch the test's kill lands in the middle of
        val late = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(late)
        mirror.inlet.call.published(early)

        registry.location(late).shouldBeNull()
        registry.location(early).shouldBeNull()
        registry.remoteRefs().shouldBeEmpty()
    }

    @Test
    fun `a fenced connection also stops mirroring its links, unlinks and evictions`() {
        val registry = LocationRegistry()
        val egress = BridgeEgressCell()
        val mirror = mirror(registry, egress)

        val theirs = CellRef(UUID.randomUUID())
        val link = TopologyLink(UUID.randomUUID(), PortRef.of(theirs, "outlet"), PortRef.of(theirs, "inlet"))
        mirror.inlet.call.published(theirs)
        mirror.inlet.call.linked(link)
        registry.all().map { it.id } shouldContainExactly listOf(link.id)

        mirror.detach()

        // every announcement method is gated, not just `published`: a mirrored
        // edge installed after the fence would name endpoints no location
        // covers, and a mirrored eviction would be a removal on behalf of a
        // peer this connection no longer speaks for
        val laterLink = TopologyLink(UUID.randomUUID(), PortRef.of(theirs, "outlet"), PortRef.of(theirs, "inlet"))
        mirror.inlet.call.linked(laterLink)
        mirror.inlet.call.unlinked(link.id)
        mirror.inlet.call.unpublished(theirs)

        // the fence dropped the edge the peer's departure orphaned…
        registry.all().map { it.id } shouldContainExactly listOf(link.id)
        // …and added nothing on its behalf afterwards
        registry.all().none { it.id == laterLink.id } shouldBe true
    }

    @Test
    fun `the fence retracts only its own connection's locations`() {
        val registry = LocationRegistry()
        val dying = BridgeEgressCell()
        val living = BridgeEgressCell()
        val leaving = mirror(registry, dying, peer = PeerId("jvm-b"))
        val staying = mirror(registry, living, peer = PeerId("jvm-c"))

        val theirs = CellRef(UUID.randomUUID())
        val others = CellRef(UUID.randomUUID())
        leaving.inlet.call.published(theirs)
        staying.inlet.call.published(others)

        leaving.detach()

        registry.location(theirs).shouldBeNull()
        registry.remoteRefs() shouldBe setOf(others)
        // and the surviving peer keeps mirroring, gate untouched
        val more = CellRef(UUID.randomUUID())
        staying.inlet.call.published(more)
        (registry.location(more) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-c")
    }

    // ---------------------------------------------------------- the recovery

    @Test
    fun `a re-attached mirror mirrors again, under the name its re-hello asserts`() {
        val registry = LocationRegistry()
        val egress = BridgeEgressCell()
        val mirror = mirror(registry, egress)

        val theirs = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(theirs)
        mirror.detach()
        registry.location(theirs).shouldBeNull()

        // a client keeps one Session — hence one mirror — across reconnects, so
        // the gate has to re-open on the re-hello or the returning peer would
        // announce into a mirror that ignores it forever
        mirror.peer = PeerId("jvm-b")
        mirror.attach()
        mirror.inlet.call.published(theirs)

        (registry.location(theirs) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")
    }

    // ------------------------------------------- the same fence, in-process

    @Test
    fun `a loopback partition fences an announcement that was still queued when it severed`() {
        // The in-process analogue of the socket case, and the reason
        // `Loopback.partition` goes through the mirrors rather than calling
        // `unpublishRemotes` on the two registries: a loopback announcement
        // also crosses a hosted ingress, so it is applied on the bridge host's
        // scheduler — here, not until `runToIdle`.
        val controller = SimulationController(11)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val loopback = Peering.loopback(
            Peering.Side(registryA, ManagedHost(scheduler = controller.scheduler(), registry = registryA), peer = PeerId("jvm-a")),
            Peering.Side(registryB, ManagedHost(scheduler = controller.scheduler(), registry = registryB), peer = PeerId("jvm-b")),
        )
        controller.runToIdle()

        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)
        // deliberately NOT drained: the announcement is in flight, exactly as
        // it is when a peer is killed mid-burst
        loopback.partition()
        controller.runToIdle()

        registryA.location(theirs.ref).shouldBeNull()
        registryA.remoteRefs().shouldBeEmpty()

        // and healing brings it back through the ordinary catch-up
        loopback.heal()
        controller.runToIdle()
        (registryA.location(theirs.ref) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")
    }
}
