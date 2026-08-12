package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-dqy.5 — the disconnect fence ([RegistryMirrorCell.detach]) — and
 * computenet-dqy.20, which made it total on the loopback path by minting a
 * fresh mirror per connection instance instead of re-opening the shut one.
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
    fun `a returning peer is served by a fresh mirror, while the one it superseded stays shut`() {
        val registry = LocationRegistry()
        val egress = BridgeEgressCell()
        val retired = mirror(registry, egress)

        val theirs = CellRef(UUID.randomUUID())
        retired.inlet.call.published(theirs)
        retired.detach()
        registry.location(theirs).shouldBeNull()

        // the returning peer's connection instance mints its own mirror (a
        // socket's `hello`, a loopback's `heal`) and re-announces into that —
        // and the returning peer loses nothing, because its (re-)announcement is
        // a full localRefs catch-up
        val fresh = mirror(registry, egress, peer = PeerId("jvm-b"))
        fresh.inlet.call.published(theirs)
        (registry.location(theirs) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")

        // …and the superseded instance is fenced off for good: there is no
        // `attach`, so a frame it staged before the close can never install a
        // location behind the fresh instance, however late it decodes
        val dropped = CellRef(UUID.randomUUID())
        retired.inlet.call.published(dropped)
        registry.location(dropped).shouldBeNull()
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

    // ------------------------------- a heal is a new connection instance (dqy.20)

    /**
     * The frame link with a switch: a severed one carries nothing, exactly as a
     * dead socket does. This is what makes the peer's retraction *lost* rather
     * than merely late — the case in which a heal's catch-up is the only thing
     * that can still tell A what B holds.
     *
     * [Peering.loopback] wires the two egresses straight to the two hosted
     * ingresses; this test does the same wiring by hand with the switch spliced
     * in, and then hands the pair to [Peering.Loopback] — the composition under
     * test — unchanged.
     */
    private class SeverableLink(private val target: Propagate<ByteArray>) : Propagate<ByteArray> {
        @Volatile
        var severed = false

        override fun propagate(value: ByteArray) {
            if (!severed) target.propagate(value)
        }
    }

    @Test
    fun `a heal does not apply the announcement a severed connection instance left queued`() {
        // One controller per side, because the two sides of a partition do not
        // stop running in lockstep: A's bridge host is starved across the whole
        // outage (its queue is the "staged frame"), while B goes on to drop the
        // ref it had already announced.
        val controllerA = SimulationController(23)
        val controllerB = SimulationController(24)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val bridgeA = ManagedHost(scheduler = controllerA.scheduler(), registry = registryA)
        val bridgeB = ManagedHost(scheduler = controllerB.scheduler(), registry = registryB)
        val hostB = ManagedHost(scheduler = controllerB.scheduler(), registry = registryB)
        val a = Peering.Side(registryA, bridgeA, peer = PeerId("jvm-a"))
        val b = Peering.Side(registryB, bridgeB, peer = PeerId("jvm-b"))

        val linkAtoB = SeverableLink(Peering.hostIngress(b, fromPeer = a.peer))
        val linkBtoA = SeverableLink(Peering.hostIngress(a, fromPeer = b.peer))
        val aToB = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(linkAtoB, PortRef.generate())) }
        val bToA = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(linkBtoA, PortRef.generate())) }
        val loopback = Peering.Loopback(a, b, aToB, bToA)
        controllerA.runToIdle()
        controllerB.runToIdle()
        val severedInstance = loopback.mirrorRefOnA

        // B announces a ref, and the frame is left staged on A's bridge host —
        // deliberately not drained, so it is decoded no further than the ingress
        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)
        registryB.localRefs() shouldContain theirs.ref
        registryA.location(theirs.ref).shouldBeNull()

        linkAtoB.severed = true
        linkBtoA.severed = true
        loopback.partition()

        // the peer drops that ref while severed, so its retraction is LOST, not
        // merely late — the only repair left is the heal's own catch-up
        hostB.managementInlet.call.despawn(theirs.ref)
        controllerB.runToIdle()
        registryB.localRefs() shouldNotContain theirs.ref

        linkAtoB.severed = false
        linkBtoA.severed = false
        loopback.heal()
        controllerB.runToIdle()
        controllerA.runToIdle()

        // the catch-up is authoritative: B no longer holds `theirs`, so nothing
        // re-announces it, and the superseded instance's queued announcement
        // must not install it behind the heal
        registryA.location(theirs.ref).shouldBeNull()
        registryA.remoteRefs() shouldNotContain theirs.ref
        // …because the heal addressed the returning peering to a *fresh* mirror,
        // leaving the one that queued delivery names shut for good
        loopback.mirrorRefOnA shouldNotBe severedInstance

        // control: the healed peering is live, so the assertion above cannot
        // pass by the peering having been broken outright
        val alsoTheirs = SetCell<String>()
        hostB.managementInlet.call.spawn(alsoTheirs)
        controllerB.runToIdle()
        controllerA.runToIdle()
        (registryA.location(alsoTheirs.ref) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")
    }
}
