package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.wire.Peering
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T21 — a closed inspector is *off* the registry, not merely disarmed.
 *
 * Until T21, `LocationRegistry.onPublish`/`onUnpublish` returned no
 * deregistration handle, so [InspectorServer] kept a `@Volatile attached` flag
 * that [InspectorServer.close] flipped to false: the listeners stayed
 * registered on the registry for its whole lifetime and merely no-opped. Both
 * hooks return `AutoCloseable` now and are held in the server's `hooks` list
 * with the rest, so closing detaches them for real.
 *
 * The observable form of "detached" this test can assert without reaching into
 * the server is that no mutation of any class the inspector subscribes to moves
 * the model afterwards — a local publish, a peer-announced publish, a peer
 * disconnect, and a topology link, one per feed.
 */
class InspectorDetachTest {

    /**
     * Owned schedulers, not `ManagedHost`'s own default, purely so [tearDown]
     * can stop them (computenet-4vh) — see `InspectorErrorsTest` for the full
     * rationale.
     */
    private val registryA = LocationRegistry()
    private val hostARef = CellRef(UUID.randomUUID())
    private val hostAScheduler = VirtualThreadScheduler("ManagedHost-${hostARef.id}")
    private val hostA = ManagedHost(ref = hostARef, scheduler = hostAScheduler, registry = registryA)
    private val bridgeARef = CellRef(UUID.randomUUID())
    private val bridgeAScheduler = VirtualThreadScheduler("ManagedHost-${bridgeARef.id}")
    private val bridgeA = ManagedHost(ref = bridgeARef, scheduler = bridgeAScheduler, registry = registryA)

    private val registryB = LocationRegistry()
    private val hostBRef = CellRef(UUID.randomUUID())
    private val hostBScheduler = VirtualThreadScheduler("ManagedHost-${hostBRef.id}")
    private val hostB = ManagedHost(ref = hostBRef, scheduler = hostBScheduler, registry = registryB)
    private val bridgeBRef = CellRef(UUID.randomUUID())
    private val bridgeBScheduler = VirtualThreadScheduler("ManagedHost-${bridgeBRef.id}")
    private val bridgeB = ManagedHost(ref = bridgeBRef, scheduler = bridgeBScheduler, registry = registryB)

    @AfterEach
    fun tearDown() {
        hostAScheduler.shutdown()
        bridgeAScheduler.shutdown()
        hostBScheduler.shutdown()
        bridgeBScheduler.shutdown()
    }

    private fun encode(ref: CellRef) = "${ref.id}:${ref.instanceId}"

    /** The component id the view currently places [ref] in. */
    private fun InspectorServer.graphOf(ref: CellRef): String =
        componentsNow().single { component -> component.nodes.any { it.ref == encode(ref) } }.id

    @Test
    fun `a closed inspector stops seeing every registry feed it subscribed to`() {
        val mine = SetCell<String>()
        val alsoMine = SetCell<String>()
        hostA.managementInlet.call.spawn(mine)
        hostA.managementInlet.call.spawn(alsoMine)
        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)

        val server = InspectorServer(
            registry = registryA,
            hosts = mapOf("a-host" to hostA, "a-bridge" to bridgeA),
            port = 0,
        ).start()
        Peering.loopback(Peering.Side(registryA, bridgeA), Peering.Side(registryB, bridgeB))
        awaitUntil("the inspector adopted the peer's cell") { server.knowsNow(theirs.ref) }
        awaitUntil("the inspector adopted its own cells") { server.knowsNow(mine.ref) && server.knowsNow(alsoMine.ref) }
        // unlinked, so two components — the baseline case 3 checks against
        (server.graphOf(mine.ref) == server.graphOf(alsoMine.ref)) shouldBe false

        server.close()

        // 1. a local publish (onLocalPublish)
        val late = SetCell<String>()
        hostA.managementInlet.call.spawn(late)
        awaitUntil("the late spawn published") { registryA.locate(late.ref) != null }
        server.knowsNow(late.ref) shouldBe false

        // 2. a peer-announced publish (onPublish)
        val alsoTheirs = SetCell<String>()
        hostB.managementInlet.call.spawn(alsoTheirs)
        awaitUntil("the peer announced it") { registryA.location(alsoTheirs.ref) != null }
        server.knowsNow(alsoTheirs.ref) shouldBe false

        // 3. a topology link (onTopology)
        hostA.managementInlet.call.connect(mine.ref, "outlet", alsoMine.ref, "deltaInlet")
        awaitUntil("the link reached the registry") { registryA.all().isNotEmpty() }
        // the two cells would be one component if the hook had fired
        (server.graphOf(mine.ref) == server.graphOf(alsoMine.ref)) shouldBe false

        // 4. an unpublish — both the ordinary path and the peer-disconnect one
        //    (`unpublishRemotes`, which notifies `onUnpublish` since T21)
        hostA.managementInlet.call.despawn(alsoMine.ref)
        awaitUntil("the despawn unpublished") { registryA.locate(alsoMine.ref) == null }
        server.knowsNow(alsoMine.ref) shouldBe true
        registryA.unpublishRemotes(registryA.location(theirs.ref).let { (it as LocationRegistry.Remote).sink })
        registryA.location(theirs.ref) shouldBe null
        server.knowsNow(theirs.ref) shouldBe true
    }
}
