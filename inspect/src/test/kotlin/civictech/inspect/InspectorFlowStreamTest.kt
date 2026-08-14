package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
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
import java.util.UUID
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

    /**
     * The host's scheduler, owned here rather than left to [ManagedHost]'s own
     * default, purely so [tearDown] can stop it (computenet-4vh) — see
     * `InspectorErrorsTest` for the full rationale.
     */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private val server = InspectorServer(registry, host, port = 0).startUnscheduled()
    private var events: SseTap? = null

    @AfterEach
    fun tearDown() {
        events?.close()
        server.close()
        hostScheduler.shutdown()
    }

    private fun spawnSet(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun get(path: String): String {
        val client = HttpClient.newHttpClient()
        try {
            return client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort}$path")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
        } finally {
            client.shutdownNow()
        }
    }

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

        server.tickAll()

        val frame = stream.awaitKind(Event.FLOW_RATES)
        (frame.seq > snapshot.seq) shouldBe true
        val batch = json.decodeFromJsonElement(FlowBatch.serializer(), frame.payload)
        batch.window shouldBe FlowCollector.WINDOW_MS
        batch.edges.map { it.id } shouldContainExactly listOf(link.id.toString())
        batch.edges.single().rate shouldBe 3.0
        batch.edges.single().hop shouldBe 0
    }

    @Test
    fun `the feed samples with no client attached, and a client leaving mid-load takes nothing with it`() {
        val source = spawnSet()
        val sink = spawnSet()
        host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
        val ops = host.lookup<SetApi<String>>(source.ref)!!.inlet.call

        // nobody is watching: the window is still built and simply goes nowhere
        repeat(3) { ops.add("pre$it") }
        awaitUntil("pre-load flowed") { source.outlet.waveState().highWater >= 3 }
        server.tickAll()

        val stream = listen()
        repeat(2) { ops.add("mid$it") }
        awaitUntil("mid-load flowed") { source.outlet.waveState().highWater >= 5 }
        server.tickAll()
        stream.awaitKind(Event.FLOW_RATES).kind shouldBe Event.FLOW_RATES

        // the client goes away mid-load — the graph must not notice
        stream.close()
        // computenet-md6w.1: a client is removed only when a *write* to its
        // socket throws ([SseBroadcaster.Client.pump]), so "detached" is not
        // something the server discovers on its own — something has to publish
        // first. This used to be satisfied by whichever armed schedule fired
        // next (the 1 Hz `"flowSample"`, or `"heartbeat"` at 15 s), which is
        // exactly the wall-clock dependence that made this test the suite's
        // second `:inspect:` flake: on a loaded runner no background frame
        // landed inside the 30 s budget and it failed here (run 31774126595).
        // Ticking inside the loop supplies the frame the detection needs.
        awaitUntil("the client detached") {
            server.tickAll()
            server.attachedClients == 0
        }
        repeat(4) { ops.add("post$it") }
        awaitUntil("post-load flowed") { source.outlet.waveState().highWater >= 9 }
        server.tickAll()

        awaitUntil("every delta reached the sink") { sink.membership().size == 9 }
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
    fun `an inspector started against a running graph taps what is already linked`() {
        val source = spawnSet()
        val sink = spawnSet()
        val link = (host.managementInlet.call.connect(source.ref, "outlet", sink.ref, "deltaInlet")
            as LinkResult.Connected).link

        // a second inspector, constructed after the graph was already wired:
        // the startup sync must bind the edges it adopts, not only the ones it
        // later sees appear
        InspectorServer(registry, host, port = 0).use { late ->
            late.tappedOutlets shouldBe setOf(source.outlet.ref)
            val client = HttpClient.newHttpClient()
            val body = try {
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${late.startUnscheduled().boundPort}${InspectorServer.TOPOLOGY_PATH}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
            } finally {
                client.shutdownNow()
            }
            json.decodeFromString<TopologySnapshot>(body)
                .edges.single { it.id == link.id.toString() }.fused shouldBe false
        }
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

        /**
         * Held so [close] can release it (computenet-4vh): one client per
         * `listen()`, i.e. per test method, each with its own selector thread and
         * executor pool; cancelling [reader] alone left all of that alive.
         */
        private val client: HttpClient = HttpClient.newHttpClient()
        private val reader: CompletableFuture<Void> = client
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

        /**
         * `shutdownNow()`, never `close()`: this client is deliberately parked on
         * an SSE response that never ends, so `close()` — which awaits
         * termination of in-flight exchanges — would turn this teardown into the
         * unbounded wait the suite is being audited for.
         */
        override fun close() {
            reader.cancel(true)
            client.shutdownNow()
        }
    }

    private companion object {
        const val DATA = "data: "
    }
}
