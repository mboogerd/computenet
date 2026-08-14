package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.testkit.HttpProbe
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `GET /api/inspect/cell/{ref}` — the contract's `CellDetail`: everything the
 * topology snapshot's `Node` carries, plus the attention band and the link
 * census.
 */
class InspectorCellDetailTest {

    // strict: an unknown key here means the server invented a field, and a
    // missing one means CellDetail drifted from Node
    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()

    /**
     * The host's scheduler, owned here rather than left to [ManagedHost]'s own
     * default, purely so [tearDown] can stop it (computenet-4vh) — see
     * `InspectorErrorsTest` for the full rationale.
     */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private var server: InspectorServer? = null
    private var probe: HttpProbe? = null

    @AfterEach
    fun tearDown() {
        probe?.close()
        server?.close()
        hostScheduler.shutdown()
    }

    private fun serve(names: Map<CellRef, String> = emptyMap()): HttpProbe {
        val started = InspectorServer(registry, mapOf("test-host" to host), port = 0, cellNames = names).startUnscheduled()
        server = started
        return HttpProbe("http://localhost:${started.boundPort}").also { probe = it }
    }

    private fun path(ref: CellRef) = "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}"

    @Test
    fun `detail repeats the node and adds the attention band and link counts`() {
        val source = SetCell<String>()
        val sink = SetCell<String>()
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(sink)
        host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")

        val probe = serve(names = mapOf(source.ref to "people"))
        val detail = json.decodeFromString<CellDetail>(probe.state(path(source.ref)))

        // the Node half — identical to what the snapshot reports for this cell
        val node = json.decodeFromString<TopologySnapshot>(probe.state(InspectorServer.TOPOLOGY_PATH))
            .nodes.single { it.ref == InspectorServer.encodeRef(source.ref) }
        detail.ref shouldBe node.ref
        detail.name shouldBe "people"
        detail.typeFqn shouldBe node.typeFqn
        detail.color shouldBe node.color
        detail.manifests shouldBe node.manifests
        detail.ports shouldBe node.ports
        detail.host shouldBe node.host
        detail.net shouldBe node.net
        detail.lifecycle shouldBe node.lifecycle
        detail.generation shouldBe node.generation
        detail.graph shouldBe node.graph

        // the M1 half
        detail.links shouldBe LinkCounts(inbound = 0, outbound = 1, taps = 0)
        // V2 made the band readable (`ManagedHost.attentionOf`), and null is
        // now a *fact* rather than a placeholder: this host runs without an
        // `AttentionPolicy`, so no band is in effect for it anywhere. A host
        // that has one reports the band by name — see [InspectorActivityTest].
        detail.attention shouldBe null
    }

    @Test
    fun `link counts are directional`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        listOf(a, b, c).forEach { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.connect(a.ref, "outlet", b.ref, "deltaInlet")
        host.managementInlet.call.connect(c.ref, "outlet", b.ref, "deltaInlet")

        val probe = serve()

        json.decodeFromString<CellDetail>(probe.state(path(b.ref))).links shouldBe
            LinkCounts(inbound = 2, outbound = 0, taps = 0)
        json.decodeFromString<CellDetail>(probe.state(path(a.ref))).links shouldBe
            LinkCounts(inbound = 0, outbound = 1, taps = 0)
    }

    @Test
    fun `an unlinked edge stops being counted`() {
        val source = SetCell<String>()
        val sink = SetCell<String>()
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(sink)
        val link = host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")

        val probe = serve()
        json.decodeFromString<CellDetail>(probe.state(path(source.ref))).links.outbound shouldBe 1

        (link as LinkResult.Connected).link.unlink()

        json.decodeFromString<CellDetail>(probe.state(path(source.ref))).links.outbound shouldBe 0
    }

    @Test
    fun `an unknown or malformed ref is a 404, never a fabricated cell`() {
        val probe = serve()

        probe.get("${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(CellRef(java.util.UUID.randomUUID()))}")
            .statusCode() shouldBe 404
        probe.get("${InspectorServer.CELL_PATH}/not-a-ref").statusCode() shouldBe 404
        probe.get(InspectorServer.CELL_PATH).statusCode() shouldBe 404
    }

    @Test
    fun `a URL-encoded ref separator resolves to the same cell`() {
        val source = SetCell<String>()
        host.managementInlet.call.spawn(source)
        val probe = serve()

        val encoded = "${source.ref.id}%3A${source.ref.instanceId}"
        json.decodeFromString<CellDetail>(probe.state("${InspectorServer.CELL_PATH}/$encoded")).ref shouldBe
            InspectorServer.encodeRef(source.ref)
    }
}
