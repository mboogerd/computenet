package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.cell.wire.Peering
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * M5-NET — network hosts. Two peered registries in one JVM
 * (`Peering.loopback`, the deterministic P1 shape of a peer connection that
 * `:wire` reproduces over a socket), with the inspector watching side A:
 *
 * - A's own cells report the launcher's network host; B's announced cells
 *   report the peer connection's derived label and no process host;
 * - B's own links arrive as edges, and leave when the peering is severed;
 * - a disconnect retracts B's cells (`unpublishRemotes` notifies `onUnpublish`
 *   since T21, so the retraction rides the removal event), and healing brings
 *   them back;
 * - a declared cross-boundary stream joins the two sides into one component;
 * - a remote cell is topology + placement only: no state, no observation.
 *
 * Every case starts the inspector *before* the peering, which is the pilot's
 * real ordering (a socket peer says hello long after startup). T21 also made
 * the opposite order sound — `LocationRegistry.remoteRefs()` is the catch-up
 * projection `InspectorModel.sync` now reads, so an inspector built after the
 * announcements no longer depends on a mirrored link or the replica index
 * naming the ref.
 */
class InspectorNetTest {

    private val json = Json { ignoreUnknownKeys = false }

    private val registryA = LocationRegistry()
    private val hostA = ManagedHost(registry = registryA)
    private val bridgeA = ManagedHost(registry = registryA)

    private val registryB = LocationRegistry()
    private val hostB = ManagedHost(registry = registryB)
    private val bridgeB = ManagedHost(registry = registryB)

    private var server: InspectorServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun peer(): Peering.Loopback =
        Peering.loopback(Peering.Side(registryA, bridgeA), Peering.Side(registryB, bridgeB))

    private fun serve(names: Map<CellRef, String> = emptyMap()): InspectorServer =
        InspectorServer(
            registry = registryA,
            hosts = mapOf("a-host" to hostA, "a-bridge" to bridgeA),
            port = 0,
            cellNames = names,
            netName = "jvm-a",
        ).start().also { server = it }

    /**
     * T21: no forced sweep any more. Peer arrivals, departures and mirrored
     * edges reach the view as registry events, so the served snapshot is
     * already current — reading it is the whole helper.
     */
    private fun InspectorServer.snapshot(): TopologySnapshot =
        json.decodeFromString(
            HttpProbe("http://localhost:$boundPort").state(InspectorServer.TOPOLOGY_PATH),
        )

    private fun encode(ref: CellRef) = "${ref.id}:${ref.instanceId}"

    /**
     * Announcements cross the loopback bridge asynchronously, so every
     * assertion here waits on the *inspector's* view rather than on the
     * registry: `LocationRegistry.publish` installs the location before it
     * notifies, so "the registry mirrors it" can be true a hook-call before the
     * inspector has been told.
     */
    private fun InspectorServer.awaitNode(ref: CellRef) =
        awaitUntil("the inspector adopted $ref") { knowsNow(ref) }

    /**
     * Strengthened for T21: the barrier is the *inspector's* served edge set,
     * not `registryA.all()`. `mirrorLink` writes the topology index and then
     * notifies, so the registry can already name a mirrored edge one hook-call
     * before the view holds it — the same reason [awaitNode] never waits on the
     * registry either.
     */
    private fun InspectorServer.awaitEdges(count: Int) =
        awaitUntil("the inspector serves $count edge(s)") { snapshot().edges.size == count }

    private fun InspectorServer.awaitEdge(id: java.util.UUID) =
        awaitUntil("the inspector serves edge $id") { snapshot().edges.any { it.id == id.toString() } }

    @Test
    fun `a peer's cells appear with its network host, and no process host`() {
        val mine = SetCell<String>()
        hostA.managementInlet.call.spawn(mine)
        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)

        val inspector = serve()
        peer()
        inspector.awaitNode(theirs.ref)

        val snapshot = inspector.snapshot()
        val local = snapshot.nodes.single { it.ref == encode(mine.ref) }
        // the launcher named this JVM: local cells stop being generically "local"
        local.net shouldBe "jvm-a"
        local.host shouldBe "a-host"

        val remote = snapshot.nodes.single { it.ref == encode(theirs.ref) }
        // one label per peer connection, derived from the bridge egress its
        // refs route through (no PeerId reaches the registry — see Peers)
        remote.net shouldStartWith "peer-"
        remote.net shouldNotBe "jvm-a"
        // a mirrored location names a bridge, not a host — the contract's null
        remote.host shouldBe null
        // an announcement carries a CellRef and nothing else: no captured
        // class, so no descriptor, so no type/color/manifests/ports
        remote.typeFqn shouldBe "<unknown>"
        remote.color shouldBe null
        remote.ports shouldBe emptyList()
        remote.lifecycle shouldBe "HOT"
    }

    @Test
    fun `the peer's own links arrive as edges`() {
        val from = SetCell<String>()
        val to = SetCell<String>()
        hostB.managementInlet.call.spawn(from)
        hostB.managementInlet.call.spawn(to)
        val link = hostB.managementInlet.call.connect(from.ref, "outlet", to.ref, "deltaInlet")
        (link is LinkResult.Connected) shouldBe true

        val inspector = serve()
        peer()
        val linkId = (link as LinkResult.Connected).link.id
        inspector.awaitNode(to.ref)
        inspector.awaitEdge(linkId)

        val edge = inspector.snapshot().edges.single { it.id == linkId.toString() }
        edge.from.ref shouldBe encode(from.ref)
        edge.to.ref shouldBe encode(to.ref)
        edge.role shouldBe "CONSUME"
        // the producer is not hosted here, so there is nothing to tap and
        // nothing honest to say about fusion — the contract's null
        edge.fused shouldBe null
        // port names are derived from a generated descriptor this side never
        // saw, so the endpoint keeps the raw port id rather than a fabricated
        // name (the FE anchors such an endpoint on the card itself)
        edge.from.port shouldNotBe "outlet"
    }

    @Test
    fun `a disconnect retracts the peer's cells and its links, and healing restores them`() {
        val theirs = SetCell<String>()
        val alsoTheirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)
        hostB.managementInlet.call.spawn(alsoTheirs)
        hostB.managementInlet.call.connect(theirs.ref, "outlet", alsoTheirs.ref, "deltaInlet")

        val inspector = serve()
        val loopback = peer()
        inspector.awaitNode(theirs.ref)
        inspector.awaitNode(alsoTheirs.ref)
        inspector.awaitEdges(1)
        inspector.snapshot().nodes.map { it.ref } shouldContain encode(theirs.ref)

        // the transport's close path, called synchronously from this thread:
        // since T21 it clears the locations and notifies `onUnpublish` for each
        // one, so the view is current the moment `partition()` returns — no
        // sweep, and no wait
        loopback.partition()
        val severed = inspector.snapshot()
        severed.nodes.map { it.ref } shouldNotContain encode(theirs.ref)
        severed.nodes.map { it.ref } shouldNotContain encode(alsoTheirs.ref)
        // the peer's edge went with its endpoints — `unpublishRemotes` leaves
        // the mirrored links behind in the topology index, so the retraction is
        // the removal event's own work (InspectorModel.retractDangling)
        severed.edges shouldBe emptyList()
        // and the registry still holds that stale mirrored edge: the view
        // dropped it because both endpoints left, not because anything unlinked
        registryA.all().isNotEmpty() shouldBe true

        loopback.heal()
        inspector.awaitNode(theirs.ref)
        inspector.awaitEdges(1)
        val healed = inspector.snapshot()
        healed.nodes.map { it.ref } shouldContain encode(theirs.ref)
        healed.edges.size shouldBe 1
    }

    @Test
    fun `a declared cross-boundary stream is an edge, and joins the two sides into one graph`() {
        val mine = SetCell<String>()
        hostA.managementInlet.call.spawn(mine)
        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)

        val inspector = serve()
        peer()
        inspector.awaitNode(theirs.ref)
        // before declaring, the two JVMs' cells are unrelated components
        val before = inspector.snapshot()
        before.nodes.single { it.ref == encode(mine.ref) }.graph shouldNotBe
            before.nodes.single { it.ref == encode(theirs.ref) }.graph

        inspector.declareLink(mine.ref, "outlet", theirs.ref, "inlet")

        val snapshot = inspector.snapshot()
        val edge = snapshot.edges.single()
        edge.from.ref shouldBe encode(mine.ref)
        edge.to.ref shouldBe encode(theirs.ref)
        edge.role shouldBe "CONSUME"
        // the producing endpoint IS hosted here, so the ordinary M3 tap applies
        edge.fused shouldBe false
        // one component now spans both network hosts
        val joined = snapshot.nodes.single { it.ref == encode(mine.ref) }.graph
        snapshot.nodes.single { it.ref == encode(theirs.ref) }.graph shouldBe joined
        val graph = inspector.componentsNow().single { it.id == joined }
        graph.nodes.map { it.net }.toSet().size shouldBe 2

        // re-declaring the same stream is idempotent — one edge, same id
        inspector.declareLink(mine.ref, "outlet", theirs.ref, "inlet")
        inspector.snapshot().edges.map { it.id } shouldBe listOf(edge.id)
    }

    @Test
    fun `a remote cell is topology and placement only — no state, no observation`() {
        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)

        val inspector = serve()
        peer()
        inspector.awaitNode(theirs.ref)

        val probe = HttpProbe("http://localhost:${inspector.boundPort}")
        val ref = encode(theirs.ref)

        // descriptor + placement are served (a 200, not a 404): the cell is in
        // this view, it simply lives elsewhere
        val detail = json.decodeFromString<CellDetail>(probe.state("${InspectorServer.CELL_PATH}/$ref"))
        detail.host shouldBe null
        detail.net shouldStartWith "peer-"

        // nothing to fold and nothing to snapshot: the honest answer, not an
        // empty-looking value
        val state = json.decodeFromString<CellState>(probe.state("${InspectorServer.CELL_PATH}/$ref/state"))
        state.kind shouldBe CellState.UNAVAILABLE

        // and observing it is refused rather than silently promising summaries
        probe.postForm("", "${InspectorServer.CELL_PATH}/$ref/observe").statusCode() shouldBe 409
        inspector.observedRefs shouldBe emptySet()
    }
}
