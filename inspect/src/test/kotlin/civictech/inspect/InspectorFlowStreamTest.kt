package civictech.inspect

import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.LinkResult
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

/**
 * The flow vertical as a client sees it: `Edge.fused` in the topology snapshot,
 * and `flow.rates` on the SSE stream — the contract's envelope and payload,
 * carried by the same monotonic `seq` as every other event.
 *
 * [InspectorFlowTest] covers what a window *contains*; this covers that the
 * server wires the collector to the registry hooks and the broadcaster at all.
 */
class InspectorFlowStreamTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val server = InspectorServer(registry, host, port = 0).start()
    private var events: SseTap? = null

    @AfterEach
    fun tearDown() {
        events?.close()
        server.close()
    }

    private fun spawnSet(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun get(path: String): String = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://localhost:${server.boundPort}$path")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    @Test
    fun `a tapped edge is reported not fused, and its rates stream as flow rates`() {
        val source = spawnSet()
        val sink = spawnSet()
        val link = (host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
            as LinkResult.Connected).link

        val snapshot = json.decodeFromString<TopologySnapshot>(get(InspectorServer.TOPOLOGY_PATH))
        // M0 could only answer null here; M3 has a tap on the producing outlet
        snapshot.edges.single().fused shouldBe false
        server.tappedOutlets shouldBe setOf(source.outlet.ref)

        val stream = listen()
        val ops = host.lookup<SetApi<String>>(source.ref)!!.inlet.call
        repeat(3) { ops.add("e$it") }
        awaitUntil("the deltas were emitted") { source.outlet.waveState().highWater >= 3 }

        server.sampleFlowNow()

        val frame = stream.awaitKind(Event.FLOW_RATES)
        (frame.seq > snapshot.seq) shouldBe true
        val batch = json.decodeFromJsonElement(FlowBatch.serializer(), frame.payload)
        batch.window shouldBe FlowCollector.WINDOW_MS
        batch.edges.map { it.id } shouldContainExactly listOf(link.id.toString())
        batch.edges.single().rate shouldBe 3.0
        batch.edges.single().hop shouldBe 0
    }

    @Test
    fun `an unlinked edge releases its tap through the topology hook`() {
        val source = spawnSet()
        val sink = spawnSet()
        val link = (host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
            as LinkResult.Connected).link
        server.tappedOutlets shouldBe setOf(source.outlet.ref)

        link.unlink()

        awaitUntil("the unlink reached the collector") { server.tappedOutlets.isEmpty() }
    }

    @Test
    fun `a despawned producer releases its tap through the unpublish hook`() {
        val source = spawnSet()
        val sink = spawnSet()
        host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
        server.tappedOutlets shouldBe setOf(source.outlet.ref)

        // despawn unpublishes without unlinking, so the untap has to ride the
        // unpublish hook rather than the topology one
        host.managementInlet.call.despawn(source.ref)

        awaitUntil("the despawn reached the collector") { server.tappedOutlets.isEmpty() }
    }

    @Test
    fun `closing the server untaps the graph it was watching`() {
        val source = spawnSet()
        val sink = spawnSet()
        host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
        server.tappedOutlets shouldBe setOf(source.outlet.ref)

        server.close()

        server.tappedOutlets shouldBe emptySet()
    }

    private fun listen(): SseTap {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}")
        events = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    private data class Frame(val seq: Long, val kind: String, val payload: kotlinx.serialization.json.JsonObject)

    /** A live `text/event-stream` reader collecting `data:` frames off the wire. */
    private inner class SseTap(url: String) : AutoCloseable {
        private val frames = LinkedBlockingQueue<Frame>()
        private val reader: CompletableFuture<Void> = HttpClient.newHttpClient()
            .sendAsync(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                response.body().forEach { line ->
                    if (line.startsWith(DATA)) {
                        val event = json.parseToJsonElement(line.removePrefix(DATA)).jsonObject
                        frames += Frame(
                            seq = event["seq"]!!.jsonPrimitive.content.toLong(),
                            kind = event["kind"]!!.jsonPrimitive.content,
                            payload = event["payload"]!!.jsonObject,
                        )
                    }
                }
            }

        fun awaitKind(kind: String): Frame {
            awaitUntil("an sse frame of kind $kind", timeoutMs = 10_000) { frames.any { it.kind == kind } }
            return frames.first { it.kind == kind }
        }

        override fun close() {
            reader.cancel(true)
        }
    }

    private companion object {
        const val DATA = "data: "
    }
}
