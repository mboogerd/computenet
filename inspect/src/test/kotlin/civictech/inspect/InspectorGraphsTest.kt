package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
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
 * M4 — the multi-graph navigator's backend: connected components over the
 * topology, the `g-<min uuid>` identity heuristic, the opt-in naming hook,
 * `GET /api/inspect/graphs`, and `GET /api/inspect/topology?graph=`.
 *
 * Cell uuids are fixed rather than random so "lexicographically-min member"
 * is a fact the assertions can state, not a coin flip they have to recompute.
 */
class InspectorGraphsTest {

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
    private val server = InspectorServer(registry, mapOf("test-host" to host), port = 0).start()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")
    private var tap: SseTap? = null

    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
        probe.close()
        hostScheduler.shutdown()
    }

    // ------------------------------------------------------------ components

    @Test
    fun `two disjoint graphs are two components, each named by its lexicographically-min member uuid`() {
        val (a, b) = pair(A, B)
        val (c, d) = pair(C, D)

        val graphs = graphs()

        graphs.graphs.map { it.id } shouldContainExactly listOf("g-$A", "g-$C")
        graphs.graphs.map { it.cells } shouldContainExactly listOf(2, 2)
        // the whole point of the id: it names the component, not the cell
        nodeGraphs() shouldBe mapOf(
            encoded(a) to "g-$A", encoded(b) to "g-$A",
            encoded(c) to "g-$C", encoded(d) to "g-$C",
        )
    }

    @Test
    fun `an unlinked cell is a component of one`() {
        val lonely = spawn(A)

        val graphs = graphs()

        graphs.graphs.single().id shouldBe "g-$A"
        graphs.graphs.single().cells shouldBe 1
        nodeGraphs()[encoded(lonely)] shouldBe "g-$A"
    }

    @Test
    fun `linking across two components merges them, and the merge is announced as graphs_changed`() {
        val events = listen()
        val (a, _) = pair(A, B)
        val (c, _) = pair(C, D)
        graphs().graphs.size shouldBe 2
        // the build itself moved the partition; take that hint out of the way
        // so the assertion below is about the merge and nothing else
        server.tickAll()
        val before = events.countOfKind(Event.GRAPHS_CHANGED)

        connect(a, c)
        server.tickAll()

        events.awaitKind(Event.GRAPHS_CHANGED, before + 1)
        val merged = graphs().graphs.single()
        // four cells now share the smallest uuid among them
        merged.id shouldBe "g-$A"
        merged.cells shouldBe 4
    }

    @Test
    fun `unlinking splits a component back in two`() {
        val (a, _) = pair(A, B)
        val (c, _) = pair(C, D)
        val bridge = connect(a, c)
        graphs().graphs.single().cells shouldBe 4

        bridge.unlink()

        graphs().graphs.map { it.id to it.cells } shouldContainExactly listOf("g-$A" to 2, "g-$C" to 2)
    }

    @Test
    fun `a component id survives a member joining and leaving above its minimum`() {
        val (a, _) = pair(A, B)
        graphs().graphs.single().id shouldBe "g-$A"

        // D sorts above A, so growing the component leaves its identity alone
        val late = spawn(D)
        connect(a, late)
        graphs().graphs.single().let { it.id shouldBe "g-$A"; it.cells shouldBe 3 }

        host.managementInlet.call.despawn(late)
        awaitUntil("despawn unpublished $late") { registry.locate(late) == null }

        graphs().graphs.single().let { it.id shouldBe "g-$A"; it.cells shouldBe 2 }
    }

    @Test
    fun `an idle partition announces nothing`() {
        val events = listen()
        pair(A, B)
        server.tickAll()
        // the SSE frame for this tick's one graphs_changed is delivered on a
        // background reader thread — wait for it before taking the baseline,
        // or it can land late and get misattributed to the idle window below.
        events.awaitKind(Event.GRAPHS_CHANGED, 1)
        val announced = events.countOfKind(Event.GRAPHS_CHANGED)

        // nothing moved between these two ticks
        server.tickAll()
        server.tickAll()

        events.countOfKind(Event.GRAPHS_CHANGED) shouldBe announced
    }

    // ---------------------------------------------------------------- naming

    @Test
    fun `nameGraph labels the component holding its anchor, and invents nothing for the rest`() {
        val (a, _) = pair(A, B)
        pair(C, D)

        server.nameGraph(a, "skillmatch")

        graphs().graphs.map { it.id to it.name } shouldContainExactly
            listOf("g-$A" to "skillmatch", "g-$C" to null)
    }

    @Test
    fun `a name follows its anchor cell when the component around it is renamed by a merge`() {
        val (a, _) = pair(A, B)
        val (c, _) = pair(C, D)
        // anchored on the *later* component, whose id disappears in the merge
        server.nameGraph(c, "side")
        graphs().graphs.map { it.name } shouldContainExactly listOf(null, "side")

        connect(a, c)

        graphs().graphs.single().let { it.id shouldBe "g-$A"; it.name shouldBe "side" }
    }

    @Test
    fun `a merge of two named components resolves deterministically to the min-uuid anchor`() {
        val (a, _) = pair(A, B)
        val (c, _) = pair(C, D)
        server.nameGraph(c, "side")
        server.nameGraph(a, "main")

        connect(a, c)

        // the same tie-break the id uses, so the answer does not depend on
        // which host annotated first
        graphs().graphs.single().name shouldBe "main"
    }

    @Test
    fun `naming announces graphs_changed even though the partition did not move`() {
        val events = listen()
        val (a, _) = pair(A, B)
        server.tickAll()
        val before = events.countOfKind(Event.GRAPHS_CHANGED)

        server.nameGraph(a, "skillmatch")

        events.awaitKind(Event.GRAPHS_CHANGED, before + 1)
    }

    // ------------------------------------------------------------ GET /graphs

    @Test
    fun `a graph card counts cells, hosts and nets and is hot until M5`() {
        pair(A, B)

        val card = graphs().graphs.single()

        card.cells shouldBe 2
        card.hosts shouldBe 1
        card.nets shouldBe 1
        card.lifecycle shouldBe "hot"
        card.health shouldBe GraphHealth(deadLetters = 0, parked = 0, restarts = 0)
    }

    @Test
    fun `a component spanning two process hosts counts both`() {
        val secondRef = CellRef(UUID.randomUUID())
        val secondScheduler = VirtualThreadScheduler("ManagedHost-${secondRef.id}")
        val second = ManagedHost(ref = secondRef, scheduler = secondScheduler, registry = registry)
        val moved = InspectorServer(registry, mapOf("one" to host, "two" to second), port = 0).start()
        try {
            val cellA = SetCell<String>(ref = CellRef(UUID.fromString(A)))
            val cellB = SetCell<String>(ref = CellRef(UUID.fromString(B)))
            host.managementInlet.call.spawn(cellA)
            host.managementInlet.call.spawn(cellB)
            connect(cellA.ref, cellB.ref)
            // relocation: re-spawning a published cell on another host of the
            // same registry moves its location without retracting the link
            second.managementInlet.call.spawn(cellB)
            awaitUntil("relocated to the second host") { registry.locate(cellB.ref) === second }

            val card = moved.componentsNow().single()
            card.nodes.mapNotNull { it.host }.toSet() shouldBe setOf("one", "two")
            Graphs.list(moved.componentsNow(), moved.errorSnapshot()).graphs.single().hosts shouldBe 2
        } finally {
            moved.close()
            secondScheduler.shutdown()
        }
    }

    @Test
    fun `health counters are scoped to the component's own refs`() {
        val (a, _) = pair(A, B)
        pair(C, D)

        // park two invocations on a cell of the first graph only
        registry.hold(a)
        @Suppress("UNCHECKED_CAST")
        val api = HostedCellProxy.create(a, registry, SetApi::class.java) as SetApi<String>
        api.inlet.call.add("x")
        awaitUntil("one invocation parked") { registry.parkedFor(a).size == 1 }
        server.tickAll()

        val cards = graphs().graphs.associateBy { it.id }
        cards.getValue("g-$A").health.parked shouldBe 1
        cards.getValue("g-$C").health.parked shouldBe 0

        registry.release(a)
        awaitUntil("parked traffic drained") { registry.parkedFor(a).isEmpty() }
    }

    // ------------------------------------------------- GET /topology?graph=

    @Test
    fun `the topology filter scopes nodes and edges to one component`() {
        val (a, b) = pair(A, B)
        val (c, d) = pair(C, D)

        val scoped = snapshot("g-$A")

        scoped.nodes.map { it.ref }.toSet() shouldBe setOf(encoded(a), encoded(b))
        scoped.edges.size shouldBe 1
        scoped.edges.single().from.ref shouldBe encoded(a)
        // the unfiltered snapshot is still the whole process, and both share seq
        val whole = snapshot(null)
        whole.nodes.map { it.ref }.toSet() shouldBe setOf(encoded(a), encoded(b), encoded(c), encoded(d))
        whole.edges.size shouldBe 2
        scoped.seq shouldBe whole.seq
    }

    @Test
    fun `an id no component carries scopes to an empty snapshot, not an error`() {
        pair(A, B)

        val response = probe.get("${InspectorServer.TOPOLOGY_PATH}?graph=g-$C")

        response.statusCode() shouldBe 200
        val scoped = json.decodeFromString<TopologySnapshot>(response.body())
        scoped.nodes.size shouldBe 0
        scoped.edges.size shouldBe 0
    }

    @Test
    fun `cell detail carries the same component id as the snapshot`() {
        val (a, _) = pair(A, B)

        val detail = json.decodeFromString<CellDetail>(
            probe.state("${InspectorServer.CELL_PATH}/${encoded(a)}"),
        )

        detail.graph shouldBe "g-$A"
        detail.graph shouldBe snapshot(null).nodes.first { it.ref == encoded(a) }.graph
    }

    // ------------------------------------------------- instruments (M5-EVAL)

    @Test
    fun `the inspector's own observation sink never joins the component it observes`() {
        // Both uuids start with ff…, so a random sink uuid would displace the
        // lexicographic minimum with near-certainty if it were admitted — the
        // component would be *renamed* by the act of selecting a cell in it,
        // and the client (filtered on the old id) kicked out of the graph it
        // is looking at: the flip-flop M5-COLD's report reproduced. An
        // instrument is not a subject.
        val (x, y) = pair(X_HIGH, Y_HIGH)
        // The pair itself moved the partition, so a `graphs.changed` is owed for
        // it — emitted by whichever `"graphsChanged"` tick runs first, the 1 Hz
        // scheduled one or the explicit `tickAll()` below (computenet-rzq0).
        // Settle that debt *before* the tap attaches, or the zeroes below hold
        // only for as long as the setup's own announcement is still in flight:
        // the count is read on this thread and the frame arrives on the
        // reader's, so a test slow enough to lose that race fails on a frame it
        // was never asserting about. Same move the merge and idle-partition
        // tests above make, except they baseline the count instead — here the
        // point of the assertion is that the number is absolutely zero.
        server.tickAll()
        val events = listen()
        val before = snapshot(null)
        before.nodes.map { it.ref }.toSet() shouldBe setOf(encoded(x), encoded(y))

        probe.postForm("", "${InspectorServer.CELL_PATH}/${encoded(x)}/observe").statusCode() shouldBe 204
        awaitUntil("observation sink linked to $x") { registry.swapSet(x).size == 2 }

        // the sink is real in the registry…
        registry.localRefs().size shouldBe 3
        // …and absent from the view: same nodes, same edges, same id, same size
        val during = snapshot(null)
        during.nodes.map { it.ref }.toSet() shouldBe before.nodes.map { it.ref }.toSet()
        during.edges.map { it.id }.toSet() shouldBe before.edges.map { it.id }.toSet()
        val card = graphs().graphs.single()
        card.id shouldBe "g-$X_HIGH"
        card.cells shouldBe 2

        // the observation itself still works — exclusion broke no reading
        json.decodeFromString<CellState>(
            probe.state("${InspectorServer.CELL_PATH}/${encoded(x)}/state"),
        ).kind shouldBe CellState.VIEW

        // nothing was announced either: no node/link deltas, no graphs.changed
        server.tickAll()
        events.drained(1)
        events.countOfKind(Event.TOPOLOGY_NODE) shouldBe 0
        events.countOfKind(Event.TOPOLOGY_LINK) shouldBe 0
        events.countOfKind(Event.GRAPHS_CHANGED) shouldBe 0

        probe.delete("${InspectorServer.CELL_PATH}/${encoded(x)}/observe").statusCode() shouldBe 204
        awaitUntil("observation sink despawned") { registry.localRefs().size == 2 }
        snapshot(null).nodes.map { it.ref }.toSet() shouldBe before.nodes.map { it.ref }.toSet()
        // releasing a sink emits topology deltas for anything that is not an
        // instrument, so this second absence needs the same barrier as the first
        events.drained(2)
        events.countOfKind(Event.TOPOLOGY_NODE) shouldBe 0
        events.countOfKind(Event.TOPOLOGY_LINK) shouldBe 0
        events.countOfKind(Event.GRAPHS_CHANGED) shouldBe 0
    }

    // -------------------------------------------------------------- fixtures

    private fun spawn(uuid: String): CellRef =
        SetCell<String>(ref = CellRef(UUID.fromString(uuid))).also { host.managementInlet.call.spawn(it) }.ref

    /** Two fresh cells with the given uuids, linked into one component. */
    private fun pair(first: String, second: String): Pair<CellRef, CellRef> {
        val a = spawn(first)
        val b = spawn(second)
        connect(a, b)
        return a to b
    }

    private fun connect(from: CellRef, to: CellRef): Link =
        (host.managementInlet.call.connect(from, "outlet", to, "deltaInlet") as LinkResult.Connected).link

    private fun graphs(): GraphList = json.decodeFromString(probe.state(InspectorServer.GRAPHS_PATH))

    private fun snapshot(graph: String?): TopologySnapshot = json.decodeFromString(
        probe.state(InspectorServer.TOPOLOGY_PATH + if (graph == null) "" else "?graph=$graph"),
    )

    /** Every node's `ref -> graph` from the unfiltered snapshot. */
    private fun nodeGraphs(): Map<String, String?> = snapshot(null).nodes.associate { it.ref to it.graph }

    private fun encoded(ref: CellRef): String = InspectorServer.encodeRef(ref)

    private fun listen(): SseTap {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}")
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    /** A live `text/event-stream` reader counting `data:` frames by kind. */
    private inner class SseTap(url: String) : AutoCloseable {
        private val kinds = LinkedBlockingQueue<String>()

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
                        kinds += json.decodeFromString<Event>(line.removePrefix(DATA)).kind
                    }
                }
            }

        fun countOfKind(kind: String): Int = kinds.count { it == kind }

        /**
         * A read barrier for the *absence* assertions (computenet-rzq0). Frames
         * are delivered on [reader]'s own thread, so counting a kind straight
         * after the tick that could have emitted it asserts nothing: a frame
         * still in flight reads as a frame never sent, and the test passes for
         * the wrong reason until a loaded machine slows the count down enough
         * to see it.
         *
         * [InspectorServer.tickAll] runs `"heartbeat"` first and
         * `"graphsChanged"` last, and one SSE stream delivers in order — so
         * once the *next* tick's heartbeat has been read, everything the
         * previous tick emitted has been read too. [ticksSoFar] is how many
         * `tickAll()` calls this tap has already been attached for — ticks that
         * ran before it attached delivered their heartbeat to nobody and so do
         * not count.
         */
        fun drained(ticksSoFar: Int) {
            server.tickAll()
            awaitKind(Event.HEARTBEAT, ticksSoFar + 1)
        }

        fun awaitKind(kind: String, count: Int) =
            awaitUntil("$count '$kind' frames (saw ${countOfKind(kind)})", timeoutMs = 10_000) {
                countOfKind(kind) >= count
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

        // fixed and ordered: A < B < C < D lexicographically
        const val A = "0a000000-0000-4000-8000-000000000000"
        const val B = "0b000000-0000-4000-8000-000000000000"
        const val C = "0c000000-0000-4000-8000-000000000000"
        const val D = "0d000000-0000-4000-8000-000000000000"

        // deliberately at the top of the uuid ordering — see the instrument test
        const val X_HIGH = "ff000000-0000-4000-8000-000000000000"
        const val Y_HIGH = "ff000000-0000-4000-8000-000000000001"
    }
}
