package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import civictech.testkit.bounded
import civictech.testkit.boundedHttpClient
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * `GET /api/inspect/events`: the delta stream that keeps a fetched snapshot
 * current. Asserts the contract's envelope (`seq`/`kind`/`payload`), the
 * `topology.node` / `topology.link` payload shapes, and that `seq` advances
 * strictly — the client's gap detector.
 */
class InspectorEventsTest {

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
    private val server = InspectorServer(registry, host, port = 0).startUnscheduled()
    private var tap: SseTap<Frame>? = null

    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
        hostScheduler.shutdown()
    }

    private fun listen(): SseTap<Frame> {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}", ::frame)
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    @Test
    fun `spawn, link, unlink and despawn stream as topology deltas`() {
        val events = listen()

        val source = SetCell<String>()
        val replica = SetCell<String>()
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(replica)
        val link = (host.managementInlet.call.connect(source.ref, "outlet", replica.ref, "deltaInlet")
            as LinkResult.Connected).link
        link.unlink()
        host.managementInlet.call.despawn(source.ref)

        val frames = events.await(5)

        frames.map { it.kind } shouldBe listOf(
            "topology.node", "topology.node", "topology.link", "topology.link", "topology.node",
        )
        frames.map { it.seq } shouldBe listOf(1L, 2L, 3L, 4L, 5L)

        val added = frames[0].payload
        added["op"]!!.jsonPrimitive.content shouldBe "added"
        val node = json.decodeFromJsonElement(Node.serializer(), added["node"]!!)
        node.ref shouldBe "${source.ref.id}:${source.ref.instanceId}"
        node.typeFqn shouldBe "civictech.cell.data.SetCell"

        val linked = frames[2].payload
        linked["op"]!!.jsonPrimitive.content shouldBe "added"
        val edge = json.decodeFromJsonElement(Edge.serializer(), linked["edge"]!!)
        edge.id shouldBe link.id.toString()
        edge.from.port shouldBe "outlet"
        edge.to.port shouldBe "deltaInlet"

        val unlinked = frames[3].payload
        unlinked["op"]!!.jsonPrimitive.content shouldBe "removed"
        // a removal carries only the id — the client already has the rest
        unlinked["edge"]!!.jsonObject["id"]!!.jsonPrimitive.content shouldBe link.id.toString()

        val removed = frames[4].payload
        removed["op"]!!.jsonPrimitive.content shouldBe "removed"
        json.decodeFromJsonElement(Node.serializer(), removed["node"]!!).ref shouldBe
            "${source.ref.id}:${source.ref.instanceId}"
    }

    @Test
    fun `events resume exactly where the snapshot left off`() {
        val before = SetCell<String>()
        host.managementInlet.call.spawn(before)

        val events = listen()
        val client = boundedHttpClient()
        val body = try {
            client.send(
                HttpRequest.newBuilder(
                    URI("http://localhost:${server.boundPort}${InspectorServer.TOPOLOGY_PATH}")
                ).bounded().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
        } finally {
            client.shutdownNow()
        }
        val snapshot = json.decodeFromString<TopologySnapshot>(body)
        snapshot.nodes.size shouldBe 1

        val after = SetCell<String>()
        host.managementInlet.call.spawn(after)

        val frame = events.await(1).single()
        (frame.seq > snapshot.seq) shouldBe true
    }

    /** One SSE envelope, parsed. */
    private data class Frame(
        val seq: Long,
        val kind: String,
        val payload: kotlinx.serialization.json.JsonObject,
    )

    /** One `data:` payload as this class asserts on it. */
    private fun frame(data: String): Frame {
        val event = json.parseToJsonElement(data).jsonObject
        return Frame(
            seq = event["seq"]!!.jsonPrimitive.content.toLong(),
            kind = event["kind"]!!.jsonPrimitive.content,
            payload = event["payload"]!!.jsonObject,
        )
    }

    /** Block (bounded) until [count] frames have arrived, then return them. */
    private fun SseTap<Frame>.await(count: Int): List<Frame> = awaitAtLeast(count)
}
