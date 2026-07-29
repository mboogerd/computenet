package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.lookup
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * V3-BE part 1 end to end: the wave-health heuristic over a real in-process
 * graph, the real `GET /api/inspect/errors` body and the real
 * `error.waveHealth` SSE stream.
 *
 * ### The topology every scenario here uses, and why
 *
 * `SetCell -> FilterCell`, with the **filter** observed:
 *
 * - `SetCell.add` originates one wave per add on its own outlet, so the
 *   producing site's counter is exactly "how many deltas this source has
 *   emitted" — a driveable, deterministic watermark;
 * - `FilterCell` re-emits with `outlet.call` (transparent flow), so the wave
 *   the inspector's `ObserveCell` folds under carries the *producer's*
 *   timestamp. That is what makes the two stamps comparable at all: guard 1
 *   refuses two different `sourceId`s outright, and a re-originating consumer
 *   (`SetCell.deltaInlet`, a replica gossip boundary) would be refused with it;
 * - the filter absorbs everything its predicate rejects, which is precisely the
 *   honest-lag shape the class cannot distinguish from a fault. Driving the
 *   *length* of a drop run is therefore the only lever that separates a
 *   diagnostic from a false positive, and these tests drive it from both sides.
 *
 * ### No sleeping, no scheduler assertions
 *
 * Time is [InspectorServer.inspectorClock], set by the test; evaluation is
 * [InspectorServer.tickAll], called by the test. `awaitUntil` appears only
 * where a real asynchronous graph is being waited on (a delta reaching a fold,
 * an SSE frame reaching a client) and never to wait *out* a threshold.
 */
class InspectorWaveHealthTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val server = InspectorServer(registry, mapOf("test-host" to host), port = 0).start()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")
    private var tap: SseTap? = null

    /** The pinned "now" every time-shaped collaborator reads through. */
    private var now = 1_700_000_000_000L

    init {
        server.inspectorClock = { now }
    }

    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
    }

    // ------------------------------------------------------------ scaffolding

    private fun source(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun filter(predicate: (String) -> Boolean): FilterCell<String> =
        FilterCell<String>(predicate = predicate).also { host.managementInlet.call.spawn(it) }

    private fun connect(from: CellRef, to: CellRef) {
        host.managementInlet.call.connect(from, "outlet", to, "inlet")
        awaitUntil("the edge is tapped") { server.tappedOutlets.isNotEmpty() }
    }

    private fun add(cell: SetCell<String>, element: String) {
        host.lookup<SetApi<String>>(cell.ref)!!.inlet.call.add(element)
    }

    /** Drive [n] adds and wait for the producing outlet's own watermark to prove they emitted. */
    private fun drive(cell: SetCell<String>, n: Int, prefix: String) {
        val before = cell.outlet.waveState().highWater
        repeat(n) { add(cell, "$prefix-$it") }
        awaitUntil("$n deltas emitted") { cell.outlet.waveState().highWater >= before + n }
    }

    private fun observe(ref: CellRef) {
        probe.postForm("", "${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/observe")
            .statusCode() shouldBe 204
    }

    private fun snapshot(): ErrorSnapshot = json.decodeFromString(probe.state(InspectorServer.ERRORS_PATH))

    private fun state(ref: CellRef): CellState =
        json.decodeFromString(probe.state("${InspectorServer.CELL_PATH}/${InspectorServer.encodeRef(ref)}/state"))

    /** Wait until the observed fold has stamped a frontier at or past [counter]. */
    private fun awaitFrontier(ref: CellRef, counter: Long) =
        awaitUntil("$ref's frontier reached $counter") {
            (state(ref).frontier?.counter ?: -1) >= counter
        }

    private fun listen(): SseTap {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}")
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    /**
     * A `SetCell -> FilterCell` pair with the filter observed and one live
     * effective delta already folded, so the observation carries a real frontier
     * (guard 2 refuses a null one). Answers the pair and the wave counter the
     * frontier now sits at.
     */
    private fun started(predicate: (String) -> Boolean = { it.startsWith("keep") }): Started {
        val src = source()
        val sink = filter(predicate)
        connect(src.ref, sink.ref)
        observe(sink.ref)
        add(src, "keep-first")
        awaitFrontier(sink.ref, 1)
        return Started(src, sink, 1)
    }

    private data class Started(val src: SetCell<String>, val sink: FilterCell<String>, val frontier: Long)

    // -------------------------------------------------------- (a) frontierLag

    /**
     * The driven `frontierLag` scenario, reproducible step by step:
     *
     * 1. `SetCell -> FilterCell(keep*)`, the filter observed, one `keep-first`
     *    add folded — the filter's frontier is wave 1.
     * 2. 40 `drop-*` adds. Every one emits from the source (its watermark reaches
     *    41) and every one is absorbed by the filter, so the frontier stays at 1.
     * 3. One `tickAll()` at `t0`: the lag is 40, past `LAG_THRESHOLD_WAVES`, so a
     *    run starts. Nothing opens — the grace period has not been served.
     * 4. Five more `drop-*` adds, so *something* moved (the liveness gate).
     * 5. `tickAll()` at `t0 + LAG_GRACE_MS`: the row opens.
     * 6. One `keep-later` add: the filter passes it, the frontier jumps to 47,
     *    and the next `tickAll()` clears the row.
     */
    @Test
    fun `a driven frontier lag opens a row on the errors route and the stream, and clears when it catches up`() {
        val events = listen()
        val (src, sink, _) = started()

        drive(src, 40, "drop")
        server.tickAll()
        // the run has started, not matured: a diagnostic that fired on first
        // sight would report every burst in the graph
        snapshot().waveHealth.shouldBeEmpty()
        snapshot().counters.waveHealth shouldBe 0L

        drive(src, 5, "more-drop")
        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()

        val snap = snapshot()
        val row = snap.waveHealth.single()
        row.kind shouldBe WaveHealthRow.FRONTIER_LAG
        row.state shouldBe WaveHealthRow.OPEN
        row.ref shouldBe InspectorServer.encodeRef(sink.ref)
        row.heuristic shouldBe true
        row.description shouldContain "heuristic"
        row.edge.isNotEmpty() shouldBe true
        row.wave.shouldNotBeNull().counter shouldBe 46L
        row.frontier.shouldNotBeNull().counter shouldBe 1L
        row.lagWaves shouldBe 45L
        row.heldMs shouldBe WaveHealth.LAG_GRACE_MS
        snap.counters.waveHealth shouldBe 1L

        val open = events.awaitKind(Event.ERROR_WAVE_HEALTH, 1).single()
        open["state"]!!.jsonPrimitive.content shouldBe WaveHealthRow.OPEN
        open["kind"]!!.jsonPrimitive.content shouldBe WaveHealthRow.FRONTIER_LAG
        open["heuristic"]!!.jsonPrimitive.content shouldBe "true"
        open["id"]!!.jsonPrimitive.content shouldBe row.id

        // resolve it: one delta the filter actually passes
        add(src, "keep-later")
        awaitFrontier(sink.ref, 47)
        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()

        snapshot().waveHealth.shouldBeEmpty()
        snapshot().counters.waveHealth shouldBe 0L
        val cleared = events.awaitKind(Event.ERROR_WAVE_HEALTH, 2).last()
        cleared["state"]!!.jsonPrimitive.content shouldBe WaveHealthRow.CLEARED
        // the same id the open row carried — that is the whole clearing contract
        cleared["id"]!!.jsonPrimitive.content shouldBe row.id
    }

    // ------------------------------------------------------- (b) stalledWave

    /**
     * The driven `stalledWave` scenario. The same topology, driven so the *lag*
     * never approaches `LAG_THRESHOLD_WAVES` — only (b) can fire:
     *
     * 1. as above, frontier at wave 1;
     * 2. one `drop-*` add: the source is at wave 2, the filter absorbed it;
     * 3. `tickAll()` at `t0` pins wave 2 as the one to watch;
     * 4. one more `drop-*` add (the liveness gate) and `tickAll()` at
     *    `t0 + STALL_WINDOW_MS`: the row opens, naming the *pinned* wave 2;
     * 5. one `keep-*` add lands wave 4 on the frontier — past the pin — and the
     *    next `tickAll()` clears it.
     */
    @Test
    fun `a driven stalled wave opens and clears its row`() {
        val events = listen()
        val (src, sink, _) = started()

        drive(src, 1, "drop")
        server.tickAll()
        snapshot().waveHealth.shouldBeEmpty()

        drive(src, 1, "more-drop")
        now += WaveHealth.STALL_WINDOW_MS
        server.tickAll()

        val row = snapshot().waveHealth.single()
        row.kind shouldBe WaveHealthRow.STALLED_WAVE
        row.state shouldBe WaveHealthRow.OPEN
        row.ref shouldBe InspectorServer.encodeRef(sink.ref)
        row.heuristic shouldBe true
        row.description shouldContain "heuristic"
        row.wave.shouldNotBeNull().counter shouldBe 2L
        row.frontier.shouldNotBeNull().counter shouldBe 1L
        row.lagWaves shouldBe 1L
        row.heldMs shouldBe WaveHealth.STALL_WINDOW_MS
        snapshot().counters.waveHealth shouldBe 1L

        events.awaitKind(Event.ERROR_WAVE_HEALTH, 1).single()["state"]!!
            .jsonPrimitive.content shouldBe WaveHealthRow.OPEN

        add(src, "keep-later")
        awaitFrontier(sink.ref, 4)
        server.tickAll()

        snapshot().waveHealth.shouldBeEmpty()
        val cleared = events.awaitKind(Event.ERROR_WAVE_HEALTH, 2).last()
        cleared["state"]!!.jsonPrimitive.content shouldBe WaveHealthRow.CLEARED
        cleared["id"]!!.jsonPrimitive.content shouldBe row.id
    }

    // ------------------------------------------------- false-positive guards

    /**
     * Guard 7's liveness half. A graph that has stopped is not a graph that is
     * stuck: however far behind the frontier is and however long that holds in
     * wall-clock, no site published anything, so there is nothing to diagnose.
     */
    @Test
    fun `a quiet graph produces no rows however long its lag has stood`() {
        val (src, _, _) = started()
        drive(src, 40, "drop")
        server.tickAll()

        // hours of clock, dozens of evaluations, not one new wave anywhere
        repeat(20) {
            now += WaveHealth.ROW_TTL_MS
            server.tickAll()
        }

        snapshot().waveHealth.shouldBeEmpty()
        snapshot().counters.waveHealth shouldBe 0L
    }

    /**
     * Guard 4. A busy graph whose downstream absorbs most of what reaches it —
     * three deltas swallowed for every one that changes the fold — never opens a
     * row, because absorption in the shape it actually occurs leaves a *bounded*
     * lag, not a growing one.
     */
    @Test
    fun `a busy graph with an absorbing, mostly effective-change-free fold produces no rows`() {
        var passed = 0
        val (src, sink, _) = started(predicate = { it.startsWith("keep") })

        repeat(15) { round ->
            drive(src, 3, "absorbed-$round")
            add(src, "keep-$round")
            passed++
            awaitFrontier(sink.ref, 1L + round * 4L + 4L)
            now += WaveHealth.LAG_GRACE_MS
            server.tickAll()
            snapshot().waveHealth.shouldBeEmpty()
        }

        passed shouldBe 15
        snapshot().counters.waveHealth shouldBe 0L
    }

    /**
     * Guard 5. The same defence stated for the operator the ticket names: a
     * `FilterCell` downstream of a busy source drops most waves by construction
     * and lags permanently, and that is correct behaviour. What bounds it is the
     * longest *consecutive* drop run, not the drop rate — here one delta in six
     * passes, so the lag never approaches [WaveHealth.LAG_THRESHOLD_WAVES].
     */
    @Test
    fun `a filtering operator with a legitimate permanent lag produces no rows`() {
        val (src, sink, _) = started(predicate = { it.startsWith("keep") })

        repeat(10) { round ->
            drive(src, 5, "filtered-$round")
            add(src, "keep-$round")
            awaitFrontier(sink.ref, 1L + round * 6L + 6L)
            now += WaveHealth.STALL_WINDOW_MS
            server.tickAll()
            snapshot().waveHealth.shouldBeEmpty()
        }

        // 60 waves emitted, 50 of them dropped, zero rows
        src.outlet.waveState().highWater shouldBe 61L
        snapshot().counters.waveHealth shouldBe 0L
    }

    /**
     * Guard 2. A freshly opened observation reports `frontier: null` by design —
     * its state arrived as a catch-up baseline, and a baseline is deliberately
     * not a wave position. A cell with no wave position is not "at wave zero",
     * it is ineligible, however far ahead the upstream watermark has run.
     */
    @Test
    fun `a freshly opened observation with a null frontier is never a subject`() {
        val src = source()
        val sink = filter { it.startsWith("keep") }
        connect(src.ref, sink.ref)

        // 60 waves before anyone is watching; the filter has folded them all
        add(src, "keep-first")
        drive(src, 60, "drop")
        awaitUntil("the source emitted 61 waves") { src.outlet.waveState().highWater >= 61 }

        // only now does a client subscribe: the fold's state arrives as a
        // baseline, so it carries no wave position at all
        observe(sink.ref)
        state(sink.ref).frontier shouldBe null

        repeat(5) {
            drive(src, 3, "later-$it")
            now += WaveHealth.STALL_WINDOW_MS
            server.tickAll()
        }

        state(sink.ref).frontier shouldBe null
        snapshot().waveHealth.shouldBeEmpty()
        snapshot().counters.waveHealth shouldBe 0L
    }

    /**
     * Guard 1, end to end and through a **genuine supervision RESTART** rather
     * than a synthetic epoch swap: the poisoned invocation dead-letters, the
     * generation bumps, and `ManagedHost` mints a fresh `sourceId` on every one
     * of the cell's outlets — including the one the inspector has tapped.
     *
     * Two `sourceId`s are incomparable, so the open row for the dead epoch is
     * cleared and the fresh epoch — whose counter restarts at 1 against a
     * frontier still stamped with the old source — opens nothing, ever.
     */
    @Test
    fun `a producer restarted mid-run clears its open row and opens no new one`() {
        val events = listen()
        val feed = Feed().also { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.supervise(feed.ref, SupervisionPolicy.RESTART)
        val sink = filter { it.startsWith("keep") }
        connect(feed.ref, sink.ref)
        observe(sink.ref)

        feed(feed, 1, "keep")
        awaitFrontier(sink.ref, 1)
        val deadEpoch = feed.outlet.waveState().sourceId

        feed(feed, 40, "drop")
        server.tickAll()
        feed(feed, 5, "more-drop")
        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()
        val row = snapshot().waveHealth.single()
        row.wave.shouldNotBeNull().source shouldBe deadEpoch.toString()

        // the restart, driven the only way the kernel reaches it: an invocation
        // that throws under SupervisionPolicy.RESTART
        val api = HostedCellProxy.create(feed.ref, host, FeedProxy::class.java) as FeedProxy
        api.control.call.provide(Feed.POISON)
        awaitUntil("generation bumped by RESTART") { host.generationOf(feed.ref) == 1L }
        awaitUntil("a fresh emission epoch was minted") {
            feed.outlet.waveState().sourceId != deadEpoch
        }
        feed(feed, 3, "after-restart")

        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()

        snapshot().waveHealth.shouldBeEmpty()
        snapshot().counters.waveHealth shouldBe 0L
        val cleared = events.awaitKind(Event.ERROR_WAVE_HEALTH, 2).last()
        cleared["state"]!!.jsonPrimitive.content shouldBe WaveHealthRow.CLEARED
        cleared["id"]!!.jsonPrimitive.content shouldBe row.id

        // and it stays clear: nothing about the new epoch is comparable to a
        // frontier stamped with the dead one
        repeat(6) {
            feed(feed, 10, "epoch2-$it")
            now += WaveHealth.STALL_WINDOW_MS
            server.tickAll()
            snapshot().waveHealth.shouldBeEmpty()
        }
        events.countOfKind(Event.ERROR_WAVE_HEALTH) shouldBe 2
    }

    // ------------------------------------------------------ P6: no new reach

    /**
     * The `InspectorDataSearchTest` leak check, applied to a full evaluation
     * cycle rather than to one request: rows open, rows clear, and across all of
     * it the inspector's reach into the graph is byte-for-byte what it was —
     * the same observed refs, the same tapped outlets, the same published cells,
     * the same edges, and exactly one `ObserveCell` (the one the client asked
     * for). A diagnostic that changed the graph to diagnose it is worthless.
     */
    @Test
    fun `a full evaluation cycle subscribes to nothing, taps nothing and spawns no sink`() {
        val (src, sink, _) = started()
        val observedBefore = server.observedRefs
        val tappedBefore = server.tappedOutlets
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all().map { it.id }.toSet()

        drive(src, 40, "drop")
        server.tickAll()
        drive(src, 5, "more-drop")
        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()
        snapshot().waveHealth.size shouldBe 1

        add(src, "keep-later")
        awaitFrontier(sink.ref, 47)
        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()
        snapshot().waveHealth.shouldBeEmpty()

        // several more passes, including ones that re-derive nothing at all
        repeat(5) {
            now += WaveHealth.ROW_TTL_MS
            server.tickAll()
        }

        server.observedRefs shouldBe observedBefore
        server.observedRefs shouldBe setOf(sink.ref)
        server.tappedOutlets shouldBe tappedBefore
        registry.localRefs() shouldBe refsBefore
        registry.all().map { it.id }.toSet() shouldBe linksBefore
        // exactly one instrument in the whole process: the observation the
        // client opened, never one this evaluation created
        registry.localRefs().count { it !in setOf(src.ref, sink.ref, host.ref) } shouldBe 1
    }

    // -------------------------------------------------- the honest residual

    /**
     * The decision boundary, pinned by a test rather than only by prose: a run
     * of absorbed deltas longer than [WaveHealth.LAG_THRESHOLD_WAVES] and older
     * than [WaveHealth.LAG_GRACE_MS] **does** open a row, even though absorption
     * is legitimate. That is the residual this class cannot remove without the
     * per-edge watermarks and absorb-acks G-40 names, and it is exactly why
     * every row carries `heuristic: true` and says so in its description.
     */
    @Test
    fun `a long enough absorption run is indistinguishable from a fault, and says so`() {
        val (src, _, _) = started(predicate = { it.startsWith("keep") })

        drive(src, WaveHealth.LAG_THRESHOLD_WAVES.toInt() + 1, "absorbed")
        server.tickAll()
        drive(src, 2, "absorbed-more")
        now += WaveHealth.LAG_GRACE_MS
        server.tickAll()

        val row = snapshot().waveHealth.single()
        row.kind shouldBe WaveHealthRow.FRONTIER_LAG
        row.heuristic shouldBe true
        // the row never claims the wave was lost, only that it looks stuck
        row.description shouldContain "heuristic"
        row.description shouldContain "absorption"
    }

    // ------------------------------------------------------------ fixtures

    /**
     * A hand-rolled tagged-set producer with a **poisonable** inlet — the one
     * shape no kernel data cell offers, and the only way to reach a genuine
     * `SupervisionPolicy.RESTART` on a cell whose outlet the inspector taps.
     *
     * Tags are minted per emission from one stable source, so the deltas are
     * ordinary new-tag information exactly as a `SetCell`'s are; the difference
     * is only that this cell can be made to throw.
     */
    private interface FeedProxy {
        val control: Use<Consumer<String>>
    }

    private class Feed(override val ref: CellRef = CellRef(java.util.UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        @Suppress("UNCHECKED_CAST")
        val control = registerPort("control", FanInlet(Consumer::class.java as Class<Consumer<String>>))

        private val tagSource = java.util.UUID.randomUUID()
        private var tag = 0L

        init {
            control.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    check(input != POISON) { "poison: $input" }
                    outlet.call.propagate(
                        SetDelta(adds = mapOf(input to setOf(Timestamp(tagSource, ++tag)))),
                    )
                }
            })
        }

        companion object {
            const val POISON = "poison"
        }
    }

    /** Drive [n] emissions through [cell]'s control inlet and wait for its watermark. */
    private fun feed(cell: Feed, n: Int, prefix: String) {
        val api = HostedCellProxy.create(cell.ref, host, FeedProxy::class.java) as FeedProxy
        val before = cell.outlet.waveState().highWater
        repeat(n) { api.control.call.provide("$prefix-$it") }
        awaitUntil("$n deltas emitted") { cell.outlet.waveState().highWater >= before + n }
    }

    // -------------------------------------------------------------- sse tap

    private data class Frame(val seq: Long, val kind: String, val payload: JsonObject)

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

        fun countOfKind(kind: String): Int = frames.count { it.kind == kind }

        fun awaitKind(kind: String, count: Int): List<JsonObject> {
            awaitUntil("$count '$kind' frames", timeoutMs = 10_000) {
                frames.count { it.kind == kind } >= count
            }
            return frames.filter { it.kind == kind }.map { it.payload }
        }

        override fun close() {
            reader.cancel(true)
        }
    }

    private companion object {
        const val DATA = "data: "
    }
}
