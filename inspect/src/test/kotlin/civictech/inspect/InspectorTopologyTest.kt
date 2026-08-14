package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `GET /api/inspect/topology` against a real in-process graph: the snapshot the
 * contract (`20-api-contract.md` §DTOs) promises, built from the registry's
 * local refs and topology index.
 */
class InspectorTopologyTest {

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
        val started = InspectorServer(registry, mapOf("test-host" to host), port = 0, cellNames = names).start()
        server = started
        return HttpProbe("http://localhost:${started.boundPort}").also { probe = it }
    }

    @Test
    fun `snapshot reports the live cells, their descriptors and their placement`() {
        val source = SetCell<String>()
        val replica = SetCell<String>()
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(replica)

        val snapshot = json.decodeFromString<TopologySnapshot>(serve().state(InspectorServer.TOPOLOGY_PATH))

        snapshot.nodes.map { it.ref }.toSet() shouldBe
            setOf("${source.ref.id}:${source.ref.instanceId}", "${replica.ref.id}:${replica.ref.instanceId}")
        val node = snapshot.nodes.first { it.ref.startsWith(source.ref.id.toString()) }
        node.typeFqn shouldBe "civictech.cell.data.SetCell"
        node.color shouldBe "PURE"
        node.manifests shouldContainExactly listOf("DURABLE", "REPLICATED")
        node.ports.map { it.name to it.dir }.toSet() shouldBe setOf(
            "inlet" to "IN",
            "outlet" to "OUT",
            "deltaInlet" to "IN",
        )
        node.ports.first { it.name == "outlet" }.contractFqn shouldBe "civictech.cell.Propagate"
        node.host shouldBe "test-host"
        node.net shouldBe "local"
        node.lifecycle shouldBe "HOT"
        node.generation shouldBe 0L
        // M4: every published cell belongs to a component, and these two are
        // unlinked, so each is a component of one named by its own uuid
        node.graph shouldBe "g-${source.ref.id}"
        snapshot.nodes.map { it.graph }.toSet() shouldBe
            setOf("g-${source.ref.id}", "g-${replica.ref.id}")
        // M0 does not answer this; the contract says null, not a guess
        node.name shouldBe null
    }

    @Test
    fun `snapshot reports links as port-named, consume-role edges`() {
        val source = SetCell<String>()
        val replica = SetCell<String>()
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(replica)
        val link = host.managementInlet.call.connect(source.ref, "outlet", replica.ref, "deltaInlet")
        (link is LinkResult.Connected) shouldBe true

        val body = serve().state(InspectorServer.TOPOLOGY_PATH)
        val snapshot = json.decodeFromString<TopologySnapshot>(body)

        snapshot.edges.size shouldBe 1
        val edge = snapshot.edges.single()
        edge.id shouldBe (link as LinkResult.Connected).link.id.toString()
        edge.from shouldBe Endpoint("${source.ref.id}:${source.ref.instanceId}", "outlet")
        edge.to shouldBe Endpoint("${replica.ref.id}:${replica.ref.instanceId}", "deltaInlet")
        edge.role shouldBe "CONSUME"
        // M3 upgraded M0's `null`: the producing endpoint is a real outlet, so
        // the flow feed has a tap on it and the edge is demonstrably not fused
        edge.fused shouldBe false
        // the contract's client ignores unknown fields but reads declared ones:
        // a field omitted because it equals its default is not the same as null
        body shouldContain "\"fused\":false"
    }

    @Test
    fun `an app-supplied name labels its cell`() {
        val source = SetCell<String>()
        host.managementInlet.call.spawn(source)

        val snapshot = json.decodeFromString<TopologySnapshot>(
            serve(names = mapOf(source.ref to "people")).state(InspectorServer.TOPOLOGY_PATH)
        )

        snapshot.nodes.single().name shouldBe "people"
    }

    @Test
    fun `despawned cells leave the snapshot`() {
        val source = SetCell<String>()
        host.managementInlet.call.spawn(source)
        val probe = serve()
        json.decodeFromString<TopologySnapshot>(probe.state(InspectorServer.TOPOLOGY_PATH)).nodes.size shouldBe 1

        host.managementInlet.call.despawn(source.ref)

        // despawn is an ordinary (asynchronous) management call
        awaitUntil("despawn unpublished ${source.ref}") { registry.locate(source.ref) == null }
        val after = json.decodeFromString<TopologySnapshot>(probe.state(InspectorServer.TOPOLOGY_PATH))
        after.nodes.shouldNotBeNull()
        after.nodes.size shouldBe 0
        // deltas advanced the sequence the client must resume from
        (after.seq > 0) shouldBe true
    }

    @Test
    fun `cells on a registry-less host are invisible`() {
        val detachedScheduler = VirtualThreadScheduler("ManagedHost-detached")
        val detached = ManagedHost(scheduler = detachedScheduler)
        try {
            val hidden = SetCell<String>()
            detached.managementInlet.call.spawn(hidden)

            val snapshot = json.decodeFromString<TopologySnapshot>(serve().state(InspectorServer.TOPOLOGY_PATH))

            snapshot.nodes.size shouldBe 0
            registry.describe(hidden.ref) shouldBe null
        } finally {
            detachedScheduler.shutdown()
        }
    }
}
