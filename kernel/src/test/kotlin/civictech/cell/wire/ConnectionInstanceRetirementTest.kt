package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [Peering.ConnectionInstance] (computenet-vzb, gyvli-D3): a retired
 * connection instance's mirror and ingress are despawned, and the fence the
 * spawned-but-detached mirror used to give — "dropped at the gate, however late
 * it decodes" — is kept by [LocationRegistry.retire]'s tombstone instead: a late
 * invocation addressed to a retired ref is neither delivered nor parked, and is
 * counted in [LocationRegistry.retiredRefusals].
 */
class ConnectionInstanceRetirementTest {

    private class Rig {
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = PeerId("jvm-a"))
        val egress = BridgeEgressCell()

        /** Does the bridge host still hold [ref]? ([ManagedHost.lookup] answers null for a ref it does not host.) */
        fun hosted(ref: CellRef) = bridgeHost.lookup(ref, Peering.AnnounceInletProxy::class.java) != null

        /** A socket-shaped instance: its own mirror, its own ingress. */
        fun open(): Peering.ConnectionInstance =
            Peering.openInstance(side, toPeer = egress, peer = PeerId("jvm-b")).also {
                it.hostIngress(fromPeer = PeerId("jvm-b"))
            }

        /** What the peer's announcer sends: a `published` call addressed to [mirror], through this registry. */
        fun lateAnnouncement(mirror: CellRef, announced: CellRef) =
            (HostedCellProxy.create(mirror, registry, Peering.AnnounceInletProxy::class.java) as Peering.AnnounceInletProxy)
                .inlet.call.published(announced)

        /** A frame reaching [ingress] after its instance retired — a reader that raced the close. */
        fun lateFrame(ingress: CellRef) =
            (HostedCellProxy.create(ingress, registry, Peering.FrameInletProxy::class.java) as Peering.FrameInletProxy)
                .inlet.call.propagate(ByteArray(4))
    }

    // ------------------------------------------------------------- the fence

    @Test
    fun `a late announcement to a retired mirror is refused - not delivered, not parked, counted once`() {
        val rig = Rig()
        val instance = rig.open()
        val mirror = instance.mirrorRef

        instance.retire()

        val announced = CellRef(UUID.randomUUID())
        rig.lateAnnouncement(mirror, announced)

        rig.registry.location(announced).shouldBeNull() // not delivered
        rig.registry.parkedFor(mirror).shouldBeEmpty() // not parked
        rig.registry.retiredRefusals shouldBe 1L // refused, and counted
    }

    @Test
    fun `a late frame to a retired ingress is refused the same way`() {
        val rig = Rig()
        val instance = rig.open()
        val ingress = checkNotNull(instance.ingressRef)

        instance.retire()
        rig.lateFrame(ingress)

        rig.registry.parkedFor(ingress).shouldBeEmpty()
        rig.registry.retiredRefusals shouldBe 1L
    }

    @Test
    fun `control - the same late announcement to a live instance is delivered`() {
        val rig = Rig()
        val instance = rig.open()
        val announced = CellRef(UUID.randomUUID())

        rig.lateAnnouncement(instance.mirrorRef, announced)
        awaitUntil("the live mirror applies the announcement", 5_000) { rig.registry.location(announced) != null }

        (rig.registry.location(announced) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")
        rig.registry.retiredRefusals shouldBe 0L
    }

    // ---------------------------------------------------------- the residue

    @Test
    fun `retiring despawns both cells and takes them out of localRefs`() {
        val rig = Rig()
        val before = rig.registry.localRefs()
        val instance = rig.open()
        val refs = instance.refs
        rig.registry.localRefs() shouldContainAll refs
        refs.forEach { ref -> rig.hosted(ref) shouldBe true }

        instance.retire()

        rig.registry.localRefs() shouldBe before
        refs.forEach { ref ->
            // despawned (a management despawn is enqueued on the host, so it lands shortly after)
            awaitUntil("$ref is despawned from the bridge host", 5_000) { !rig.hosted(ref) }
            rig.registry.location(ref).shouldBeNull()
        }
        instance.isRetired shouldBe true
    }

    @Test
    fun `retiring also retracts what the instance mirrored`() {
        val rig = Rig()
        val instance = rig.open()
        val theirs = CellRef(UUID.randomUUID())
        rig.lateAnnouncement(instance.mirrorRef, theirs)
        awaitUntil("the live mirror applies the announcement", 5_000) { rig.registry.remoteRefs() == setOf(theirs) }

        instance.retire()

        rig.registry.remoteRefs().shouldBeEmpty()
    }

    @Test
    fun `retire is idempotent and safe from another thread`() {
        val rig = Rig()
        val instance = rig.open()
        val threads = List(4) { Thread { instance.retire() } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        instance.retire()

        rig.registry.retiredRefs() shouldBe instance.refs.toSet()
        rig.lateAnnouncement(instance.mirrorRef, CellRef(UUID.randomUUID()))
        rig.registry.retiredRefusals shouldBe 1L
    }

    @Test
    fun `a retired instance mints nothing more`() {
        val rig = Rig()
        val instance = Peering.openInstance(rig.side, toPeer = rig.egress)
        instance.retire()

        shouldThrow<IllegalStateException> { instance.hostIngress() }
        shouldThrow<IllegalStateException> { instance.announceTo(CellRef(UUID.randomUUID()), rig.egress) }
    }

    // ------------------------------------------------------- supersession

    @Test
    fun `supersededBy lifts the retired instance's tombstones once the next one is admitted`() {
        val rig = Rig()
        val first = rig.open()
        first.retire()
        val next = rig.open()

        first.supersededBy(next)

        rig.registry.retiredRefs().shouldBeEmpty()
        rig.registry.localRefs() shouldBe next.refs.toSet()
    }

    @Test
    fun `an instance that is still live cannot be superseded`() {
        val rig = Rig()
        val first = rig.open()
        val next = rig.open()

        shouldThrow<IllegalStateException> { first.supersededBy(next) }
    }

    // ------------------------------------------------- the loopback shape

    @Test
    fun `ten loopback partition-heal cycles leave each side at its post-hello refs`() {
        val controller = SimulationController(29)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val a = Peering.Side(registryA, ManagedHost(scheduler = controller.scheduler(), registry = registryA), peer = PeerId("jvm-a"))
        val b = Peering.Side(registryB, ManagedHost(scheduler = controller.scheduler(), registry = registryB), peer = PeerId("jvm-b"))
        val loopback = Peering.loopback(a, b)
        controller.runToIdle()
        fun counts() = listOf(
            registryA.localRefs().size,
            registryA.remoteRefs().size,
            registryB.localRefs().size,
            registryB.remoteRefs().size,
        )
        // computenet-vzb's probe numbers: an ingress and a mirror per side
        val baseline = counts()
        baseline shouldBe listOf(2, 2, 2, 2)

        repeat(10) {
            loopback.partition()
            loopback.heal()
            controller.runToIdle()
        }

        counts() shouldBe baseline
        // the residue is one tombstoned ref per side per retired instance —
        // no cell, no location, nothing announced
        registryA.retiredRefs().size shouldBe 10
        registryB.retiredRefs().size shouldBe 10
    }

    @Test
    fun `a frame a severed loopback instance left staged decodes only after heal returns`() {
        // Register B's schedulers first. A seedless SimulationController drains
        // the first busy host, so each awaited priority-0 spawn on B completes
        // without stepping A's already-staged priority-20 frame. The matching
        // spawn on A then overtakes that frame and returns as soon as the
        // management task completes. This leaves the old frame queued after
        // heal() returns, just as a production scheduler may when its caller
        // resumes between drains.
        val controller = SimulationController()
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val bridgeHostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val loopback = Peering.loopback(
            Peering.Side(registryA, hostA, peer = PeerId("jvm-a")),
            Peering.Side(registryB, bridgeHostB, peer = PeerId("jvm-b")),
        )
        controller.runToIdle()
        val severed = loopback.mirrorRefOnA

        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs) // announced, not yet decoded on A
        loopback.partition()
        loopback.heal()

        loopback.mirrorRefOnA shouldNotBe severed
        // The refusal has not happened inside heal(): the staged frame has not
        // decoded yet, so this assertion pins the post-return interleaving.
        registryA.retiredRefusals shouldBe 0L

        controller.runToIdle()

        registryA.parkedFor(severed).shouldBeEmpty()
        registryA.retiredRefusals shouldBe 1L
        // and the healed instance's catch-up carried it the ordinary way
        (registryA.location(theirs.ref) as LocationRegistry.Remote).peer shouldBe PeerId("jvm-b")
    }
}
