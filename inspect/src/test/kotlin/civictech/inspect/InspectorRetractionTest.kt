package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.cell.wire.Peering
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T21 — the two exclusions in `InspectorModel.retractDangling`, one test each.
 *
 * Retraction is driven by the removal event now (a peer disconnect, an
 * announced eviction, a local despawn) rather than by the retired 1 Hz sweep,
 * and it retracts an incident edge only when BOTH of these hold:
 *
 * - the edge is **not local** (`LocationRegistry.isLocalLink`) — a local edge is
 *   authoritative on its own: "a despawn unpublishes but does not unlink", so
 *   only an explicit `Link.unlink` may retract one, however many of its
 *   endpoints have left;
 * - the edge is **no longer anchored** — a mirrored edge is only as live as the
 *   cells this view still holds, so it survives one endpoint's departure and
 *   goes with the last.
 *
 * `InspectorNetTest`'s disconnect case exercises the retraction itself, but it
 * drops a whole peer at once — both endpoints of the mirrored edge leave in one
 * `unpublishRemotes`, so neither exclusion is load-bearing there and deleting
 * either one leaves that suite green. These two cases are built so that
 * deleting one fails exactly one of them.
 */
class InspectorRetractionTest {

    private val json = Json { ignoreUnknownKeys = false }

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

    private var server: InspectorServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
        hostAScheduler.shutdown()
        bridgeAScheduler.shutdown()
        hostBScheduler.shutdown()
        bridgeBScheduler.shutdown()
    }

    private fun serve(): InspectorServer =
        InspectorServer(
            registry = registryA,
            hosts = mapOf("a-host" to hostA, "a-bridge" to bridgeA),
            port = 0,
            netName = "jvm-a",
        ).startUnscheduled().also { server = it }

    private fun peer(): Peering.Loopback =
        Peering.loopback(Peering.Side(registryA, bridgeA), Peering.Side(registryB, bridgeB))

    private fun InspectorServer.snapshot(): TopologySnapshot {
        val probe = HttpProbe("http://localhost:$boundPort")
        return try {
            json.decodeFromString(probe.state(InspectorServer.TOPOLOGY_PATH))
        } finally {
            probe.close()
        }
    }

    private fun encode(ref: CellRef) = "${ref.id}:${ref.instanceId}"

    private fun InspectorServer.awaitNode(ref: CellRef) =
        awaitUntil("the inspector adopted $ref") { knowsNow(ref) }

    private fun InspectorServer.awaitEdge(id: java.util.UUID) =
        awaitUntil("the inspector serves edge $id") { snapshot().edges.any { it.id == id.toString() } }

    private fun InspectorServer.awaitNodeGone(ref: CellRef) =
        awaitUntil("the inspector retracted $ref") { !knowsNow(ref) }

    /**
     * The `!isLocalLink` exclusion. Both endpoints of a **local** edge despawn,
     * so the `anchored` test alone would let the second despawn retract it —
     * only the scope question keeps it. Deleting `!registry.isLocalLink(it.id)`
     * from the guard fails this test on the last assertion.
     *
     * That the edge survives at all is the documented local-edge rule, not an
     * oversight: `ManagedHost.despawn` unpublishes and never unlinks, the
     * registry still names the link, and an inspector that dropped it would be
     * reporting a topology change the kernel never made.
     */
    @Test
    fun `a local edge survives both its endpoints despawning - only an unlink retracts one`() {
        val from = SetCell<String>()
        val to = SetCell<String>()
        hostA.managementInlet.call.spawn(from)
        hostA.managementInlet.call.spawn(to)
        val link = hostA.managementInlet.call.connect(from.ref, "outlet", to.ref, "deltaInlet")
        (link is LinkResult.Connected) shouldBe true
        val linkId = (link as LinkResult.Connected).link.id

        val inspector = serve()
        inspector.awaitNode(from.ref)
        inspector.awaitNode(to.ref)
        inspector.awaitEdge(linkId)

        // first endpoint goes: the edge is still anchored on `to` anyway
        hostA.managementInlet.call.despawn(from.ref)
        inspector.awaitNodeGone(from.ref)
        inspector.snapshot().edges.map { it.id } shouldContain linkId.toString()

        // second endpoint goes: nothing anchors it now, and the ONLY thing left
        // holding it is that it is this registry's own edge
        hostA.managementInlet.call.despawn(to.ref)
        inspector.awaitNodeGone(to.ref)
        registryA.isLocalLink(linkId) shouldBe true
        registryA.all().map { it.id } shouldContain linkId
        inspector.snapshot().edges.map { it.id } shouldContain linkId.toString()
    }

    /**
     * The `!anchored` exclusion. A **mirrored** edge between two of the peer's
     * cells loses its endpoints one at a time (the peer despawns them, each
     * announcing its own `unpublished`), so the `isLocalLink` test alone would
     * let the first departure retract it — only the anchor test keeps it until
     * the second. Deleting `!anchored(it)` from the guard fails this test on
     * the mid-way assertion.
     */
    @Test
    fun `a mirrored edge survives its first endpoint departing and goes with the second`() {
        val from = SetCell<String>()
        val to = SetCell<String>()
        hostB.managementInlet.call.spawn(from)
        hostB.managementInlet.call.spawn(to)
        val link = hostB.managementInlet.call.connect(from.ref, "outlet", to.ref, "deltaInlet")
        val linkId = (link as LinkResult.Connected).link.id

        val inspector = serve()
        peer()
        inspector.awaitNode(from.ref)
        inspector.awaitNode(to.ref)
        inspector.awaitEdge(linkId)
        registryA.isLocalLink(linkId) shouldBe false

        // one endpoint departs — announced as an ordinary eviction, not a
        // disconnect, so exactly one of the two refs leaves this registry
        hostB.managementInlet.call.despawn(from.ref)
        inspector.awaitNodeGone(from.ref)
        // the peer never unlinked, so the edge is still in the topology index,
        // and `to` still anchors it in this view
        registryA.all().map { it.id } shouldContain linkId
        inspector.snapshot().edges.map { it.id } shouldContain linkId.toString()

        // the last endpoint departs: now nothing this view holds names the edge
        hostB.managementInlet.call.despawn(to.ref)
        inspector.awaitNodeGone(to.ref)
        awaitUntil("the inspector retracted the unanchored mirrored edge $linkId") {
            inspector.snapshot().edges.none { it.id == linkId.toString() }
        }
        // retracted because both endpoints left, not because anything unlinked
        registryA.all().map { it.id } shouldContain linkId
        inspector.snapshot().nodes.map { it.ref } shouldNotContain encode(to.ref)
    }
}
