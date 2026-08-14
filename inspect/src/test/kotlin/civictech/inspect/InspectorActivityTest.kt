package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * V2 — the activity feed and the push lifecycle path
 * (`doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V2-BE.md`), against a real
 * in-process graph.
 *
 * What these tests pin, in the ticket's order:
 *
 * 1. the **five kinds**, from their three genuinely different sources — the
 *    kernel's [ManagedHost.onLifecycle] notification, the inspector's own
 *    [Waker], and [Errors]' generation poll — each naming the right ref, in the
 *    order they happened;
 * 2. **catch-up and live agree**: `GET /api/inspect/activity` serves the ring,
 *    the `activity` SSE event carries the same entry, and the ring is bounded;
 * 3. **push, not sample**: a `lifecycle` event now arrives with no scheduler
 *    tick at all, exactly once per real transition — the property the retired
 *    `"lifecycleChanged"` poll used to provide at 1 Hz;
 * 4. **release**: [InspectorServer.close] detaches every kernel listener, so a
 *    stopped inspector leaves nothing on a live host;
 * 5. **P6**: reading `CellDetail.attention` and serving the feed subscribe to
 *    nothing, wake nothing and raise no attention.
 *
 * Determinism: every wait is [awaitUntil] on an observable kernel fact, and the
 * two poll-driven things (restart detection, the coalesced `graphs.changed`)
 * are driven by [InspectorServer.tickAll] rather than by wall clock. Where a
 * *negative* has to be asserted, a second management call on the same host acts
 * as the barrier — management runs on one scheduler queue in submission order,
 * so once the second call's effect is visible the first call's notification has
 * definitively already been delivered.
 */
class InspectorActivityTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()

    /**
     * Owned schedulers, not `ManagedHost`'s own default, purely so [tearDown]
     * can stop them (computenet-4vh) — see `InspectorErrorsTest` for the full
     * rationale.
     */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)

    /** A second host that *does* map attention to resources — see the attention tests. */
    private val attentiveRef = CellRef(UUID.randomUUID())
    private val attentiveScheduler = VirtualThreadScheduler("ManagedHost-${attentiveRef.id}")
    private val attentive = ManagedHost(
        ref = attentiveRef,
        scheduler = attentiveScheduler,
        registry = registry,
        attention = AttentionPolicy(),
    )

    private val server = InspectorServer(registry, mapOf("h" to host, "attentive" to attentive), port = 0).start()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")
    private var tap: SseTap? = null

    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
        probe.close()
        hostScheduler.shutdown()
        attentiveScheduler.shutdown()
    }

    // ------------------------------------------------------------- five kinds

    /**
     * The ticket's headline acceptance case: one cell taken through every
     * transition the kernel can report, plus the one it cannot (a supervision
     * restart, which has no push seam and is still observed as a generation
     * increase).
     */
    @Test
    fun `suspend, resume, drain, host-resume and restart are recorded in order`() {
        val cell = FragileCounterCell().also { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        awaitUntil("the cell is in the view") { server.knowsNow(cell.ref) }
        val api = (HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy).inlet.call

        host.managementInlet.call.suspend(cell.ref)
        awaitUntil("suspended") { host.isSuspended(cell.ref) }
        host.managementInlet.call.resume(cell.ref)
        awaitUntil("resumed") { !host.isSuspended(cell.ref) }
        host.managementInlet.call.drainHost()
        awaitUntil("drained") { host.isDrained }
        host.managementInlet.call.resumeHost()
        awaitUntil("host resumed") { !host.isDrained }

        // seed the restart poller's generation baseline before the restart, so
        // the later poll sees a genuine increase rather than a first sighting
        server.tickAll()
        api.provide(-1) // poisons -> RESTART bumps the generation
        awaitUntil("generation bumped by RESTART") { host.generationOf(cell.ref) == 1L }
        server.tickAll()

        awaitUntil("all five entries recorded") { mine(cell.ref).size >= 5 }
        val entries = mine(cell.ref)
        entries.map { it.kind } shouldContainExactly listOf(
            ActivityEntry.PASSIVATED,
            ActivityEntry.ACTIVATED,
            ActivityEntry.DRAINED,
            ActivityEntry.ACTIVATED,
            ActivityEntry.RESTARTED,
        )
        // `generation` is present on the restart and absent on everything else
        entries.dropLast(1).map { it.generation } shouldContainExactly listOf(null, null, null, null)
        entries.last().generation shouldBe 1L
        entries.forEach { it.atMs shouldBeGreaterThan 0L }
    }

    /**
     * A drain reports one entry per cell the host held, not one for the host —
     * the seam is per cell precisely so a consumer keeping per-cell rows does
     * not have to expand it itself.
     */
    @Test
    fun `a drain records one entry per cell the host held`() {
        val a = spawn(host, A)
        val b = spawn(host, B)
        awaitUntil("both in the view") { server.knowsNow(a) && server.knowsNow(b) }

        host.managementInlet.call.drainHost()
        awaitUntil("drained") { host.isDrained }

        awaitUntil("both drains recorded") { mine(a).isNotEmpty() && mine(b).isNotEmpty() }
        mine(a).map { it.kind } shouldContainExactly listOf(ActivityEntry.DRAINED)
        mine(b).map { it.kind } shouldContainExactly listOf(ActivityEntry.DRAINED)
    }

    /**
     * A repeated `suspend` is a no-op in the kernel, so it is a no-op here:
     * only actual transitions are activity.
     */
    @Test
    fun `a repeated suspend records nothing`() {
        val a = spawn(host, A)
        val b = spawn(host, B)
        awaitUntil("both in the view") { server.knowsNow(a) && server.knowsNow(b) }

        host.managementInlet.call.suspend(a)
        awaitUntil("suspended") { host.isSuspended(a) }
        host.managementInlet.call.suspend(a)
        // barrier: b's suspend is submitted after the second suspend of a, so
        // once it lands the second suspend has definitively been processed
        host.managementInlet.call.suspend(b)
        awaitUntil("the barrier landed") { host.isSuspended(b) }

        mine(a).map { it.kind } shouldContainExactly listOf(ActivityEntry.PASSIVATED)
    }

    // ------------------------------------------------------------------ wake

    /**
     * `woken` is the *user's* claim, recorded separately from the kernel's
     * `activated` — the ticket is explicit that both appear for one wake and
     * neither is suppressed.
     */
    @Test
    fun `a wake records woken for the cells it woke, alongside the kernel's activated`() {
        val (a, b) = pair(host, A, B)
        awaitUntil("both in the view") { server.knowsNow(a) && server.knowsNow(b) }
        suspendCells(a, b)

        wake("g-$A").statusCode() shouldBe 202
        awaitUntil("both cells resumed") { !host.isSuspended(a) && !host.isSuspended(b) }

        awaitUntil("both woken and activated recorded") { mine(a).size >= 3 && mine(b).size >= 3 }
        mine(a).map { it.kind } shouldContainExactly listOf(
            ActivityEntry.PASSIVATED,
            ActivityEntry.WOKEN,
            ActivityEntry.ACTIVATED,
        )
        mine(b).map { it.kind } shouldContainExactly listOf(
            ActivityEntry.PASSIVATED,
            ActivityEntry.WOKEN,
            ActivityEntry.ACTIVATED,
        )
    }

    /** A wake that found nothing cold woke nothing, and says so by recording nothing. */
    @Test
    fun `a no-op wake on a hot component records no activity`() {
        val (a, _) = pair(host, A, B)
        awaitUntil("in the view") { server.knowsNow(a) }

        wake("g-$A").statusCode() shouldBe 202

        entries().shouldBeEmpty()
    }

    // ------------------------------------------------------- catch-up vs live

    @Test
    fun `an entry produced after a client connected arrives as an activity frame and agrees with GET`() {
        val a = spawn(host, A)
        awaitUntil("in the view") { server.knowsNow(a) }
        val events = listen()

        host.managementInlet.call.suspend(a)
        awaitUntil("suspended") { host.isSuspended(a) }

        val frame = events.awaitKind(Event.ACTIVITY, 1).single()
        frame["ref"]!!.jsonPrimitive.content shouldBe encoded(a)
        frame["kind"]!!.jsonPrimitive.content shouldBe ActivityEntry.PASSIVATED
        // absent, not null — the contract V2-FE codes against
        frame["generation"] shouldBe null

        awaitUntil("the same entry is in the ring") { mine(a).isNotEmpty() }
        val row = mine(a).single()
        row.kind shouldBe ActivityEntry.PASSIVATED
        frame["atMs"]!!.jsonPrimitive.content shouldBe row.atMs.toString()
    }

    /** The endpoint is a plain read of the ring; an idle process answers empty, not 404. */
    @Test
    fun `an idle graph serves an empty activity feed`() {
        spawn(host, A)

        activity().entries.shouldBeEmpty()
    }

    /**
     * The whole-process bound, asserted on the collaborator so 250 entries cost
     * 250 map writes rather than 250 management calls. The endpoint serves
     * [Activity.snapshot] verbatim, so this is also what it can return.
     */
    @Test
    fun `the ring is bounded at its capacity, oldest evicted first`() {
        val broadcast = mutableListOf<ActivityEntry>()
        val feed = Activity(registry, emptyList(), knows = { true }, onEntry = { broadcast += it }, onLifecycle = {})
        val refs = (0 until Activity.RING_CAPACITY + 50).map { CellRef(UUID.randomUUID()) }
        try {
            refs.forEach { feed.woken(listOf(it)) }

            val retained = feed.snapshot().entries
            retained.size shouldBe Activity.RING_CAPACITY
            retained.first().ref shouldBe InspectorServer.encodeRef(refs[50])
            retained.last().ref shouldBe InspectorServer.encodeRef(refs.last())
            // the ring bounds what is *retained*, never what was broadcast
            broadcast.size shouldBe refs.size
        } finally {
            feed.close()
        }
    }

    // ------------------------------------------------------------ push, not poll

    /**
     * The mechanism change, stated as a test: no [InspectorServer.tickAll], no
     * wall-clock wait for a 1 Hz sweep — the transition itself carries the
     * event out.
     */
    @Test
    fun `a lifecycle change is announced with no tick at all, exactly once`() {
        val a = spawn(host, A)
        val b = spawn(host, B)
        awaitUntil("both in the view") { server.knowsNow(a) && server.knowsNow(b) }
        val events = listen()

        host.managementInlet.call.suspend(a)
        awaitUntil("suspended") { host.isSuspended(a) }

        events.awaitKind(Event.LIFECYCLE, 1)
        events.lifecyclesOf(encoded(a)) shouldContainExactly listOf(Node.SUSPENDED)

        // a second suspend is not a transition, so it is not a second event
        host.managementInlet.call.suspend(a)
        host.managementInlet.call.suspend(b)
        awaitUntil("the barrier landed") { host.isSuspended(b) }
        events.lifecyclesOf(encoded(a)) shouldContainExactly listOf(Node.SUSPENDED)
    }

    /**
     * `resumeHost` is the one transition two sources can both see — it
     * republishes every cell it holds (reaching `InspectorModel.published`) and
     * reports `HOST_RESUMED` per cell (reaching `lifecycleChanged`). Exactly
     * one `HOT` must come out.
     *
     * The barrier before [listen] is load-bearing, and `host.isDrained` is not
     * it: `ManagedHost.beginDrain` sets `DRAINED` and only *then* notifies
     * `DRAINED` per cell, so a client attached between the two is legitimately
     * told about the drain — which the next case pins deliberately. What *this*
     * case is about is the resume, so it waits for the drain's own announcement
     * to be out ([InspectorServer.announcedLifecycle] flips under the same lock
     * that emits the frame) before it starts listening.
     */
    @Test
    fun `a host resume is announced once, though publish and the listener both see it`() {
        val a = spawn(host, A)
        awaitUntil("in the view") { server.knowsNow(a) }
        host.managementInlet.call.drainHost()
        awaitUntil("drained") { host.isDrained }
        awaitUntil("the drain is announced") { server.announcedLifecycle(a) == Node.SUSPENDED }
        val events = listen()

        host.managementInlet.call.resumeHost()
        awaitUntil("host resumed") { !host.isDrained }

        events.awaitKind(Event.LIFECYCLE, 1)
        awaitUntil("the activity entry landed") { mine(a).any { it.kind == ActivityEntry.ACTIVATED } }
        events.lifecyclesOf(encoded(a)) shouldContainExactly listOf(Node.HOT)
    }

    /**
     * The other side of that barrier, and the reason the case above needs one:
     * a client that attaches *inside* the drain's announcement window is told
     * `SUSPENDED`, because the announcement genuinely had not happened when it
     * connected. That is not a stray transition — it is the drain, reported
     * once, to a client that was there to hear it — and it does not cost the
     * property this pair exists for: the resume that follows is still announced
     * exactly once, by whichever of the two sources sees it first.
     *
     * Forced deterministically rather than waited for: a lifecycle listener
     * registered *before* the inspector's own (hence this case's private host
     * and server) holds the host's scheduler thread inside
     * `ManagedHost.beginDrain`'s window until the SSE client is attached. Left
     * to chance the window is microseconds wide and only a loaded machine ever
     * lands in it — which is exactly how it was first seen, as a one-off CI
     * failure of the case above (computenet-dqy.29).
     */
    @Test
    fun `a client that attaches while a drain is still being announced is told about it`() {
        val ownRef = CellRef(UUID.randomUUID())
        val ownScheduler = VirtualThreadScheduler("ManagedHost-${ownRef.id}")
        val own = ManagedHost(ref = ownRef, scheduler = ownScheduler, registry = registry)
        val reachedWindow = CountDownLatch(1)
        val released = CountDownLatch(1)
        val gate = own.onLifecycle { _, transition ->
            if (transition == ManagedHost.LifecycleTransition.DRAINED) {
                reachedWindow.countDown()
                released.await(30, TimeUnit.SECONDS)
            }
        }
        val inspector = InspectorServer(registry, mapOf("own" to own), port = 0).start()
        try {
            val a = spawn(own, A)
            awaitUntil("in the view") { inspector.knowsNow(a) }
            own.managementInlet.call.drainHost()
            // the host is DRAINED and has not announced it yet: the exact state
            // `awaitUntil { host.isDrained }` can observe
            reachedWindow.await(30, TimeUnit.SECONDS) shouldBe true
            own.isDrained shouldBe true
            inspector.announcedLifecycle(a) shouldBe Node.HOT
            val events = listen(inspector)
            released.countDown()

            awaitUntil("the drain is announced") { inspector.announcedLifecycle(a) == Node.SUSPENDED }
            own.managementInlet.call.resumeHost()
            awaitUntil("host resumed") { !own.isDrained }

            events.awaitKind(Event.LIFECYCLE, 2)
            awaitUntil("the activity entry landed") {
                inspector.activitySnapshot().entries.any { it.ref == encoded(a) && it.kind == ActivityEntry.ACTIVATED }
            }
            events.lifecyclesOf(encoded(a)) shouldContainExactly listOf(Node.SUSPENDED, Node.HOT)
        } finally {
            released.countDown()
            gate.close()
            inspector.close()
            ownScheduler.shutdown()
        }
    }

    /**
     * The card invalidation the retired poll owed the navigator is coalesced
     * into the `"graphsChanged"` tick rather than emitted per cell — a suspend
     * moves no component membership, so nothing else would tell the client its
     * cold pill changed.
     */
    @Test
    fun `a lifecycle change still invalidates the navigator card, coalesced onto the graphs tick`() {
        val (a, b) = pair(host, A, B)
        awaitUntil("both in the view") { server.knowsNow(a) && server.knowsNow(b) }
        server.tickAll()
        val events = listen()

        suspendCells(a, b)
        events.awaitKind(Event.LIFECYCLE, 2)
        events.countOfKind(Event.GRAPHS_CHANGED) shouldBe 0

        server.tickAll()

        events.awaitKind(Event.GRAPHS_CHANGED, 1)
        // and one tick invalidates once, however many cells moved
        server.tickAll()
        events.countOfKind(Event.GRAPHS_CHANGED) shouldBe 1
    }

    // ---------------------------------------------------------------- release

    /**
     * A stopped inspector is off every host it was watching, not merely quiet:
     * the ring stops growing because the kernel listener is genuinely gone.
     */
    @Test
    fun `closing the inspector detaches every kernel lifecycle listener`() {
        val a = spawn(host, A)
        val b = spawn(host, B)
        awaitUntil("both in the view") { server.knowsNow(a) && server.knowsNow(b) }
        host.managementInlet.call.suspend(a)
        awaitUntil("the listener is live") { server.activitySnapshot().entries.isNotEmpty() }
        val retained = server.activitySnapshot().entries

        server.close()

        host.managementInlet.call.resume(a)
        // barrier, as in `a repeated suspend records nothing`: once b is
        // suspended, a's resume notification has definitively been delivered —
        // to nobody, if close() did its job
        host.managementInlet.call.suspend(b)
        awaitUntil("the barrier landed") { host.isSuspended(b) }

        server.activitySnapshot().entries shouldContainExactly retained
    }

    // -------------------------------------------------------------- attention

    /**
     * `CellDetail.attention` stopped being a hard-coded null. The two answers
     * are genuinely different facts, not a default and a fallback: a host with
     * an [AttentionPolicy] has a band in effect, a host without one has none
     * anywhere, and reporting `"normal"` for the latter would invent a
     * scheduling fact.
     */
    @Test
    fun `attention reports the band on a host with a policy, and null on one without`() {
        val plain = spawn(host, A)
        val banded = SetCell<Any>(ref = CellRef(UUID.fromString(B))).also { attentive.managementInlet.call.spawn(it) }
        awaitUntil("both in the view") { server.knowsNow(plain) && server.knowsNow(banded.ref) }

        detail(plain).attention shouldBe null
        detail(banded.ref).attention shouldBe AttentionBand.NORMAL.name.lowercase()
    }

    /** The reported value is the band, not a two-valued rendering of it. */
    @Test
    fun `a raised band is reported by name`() {
        val banded = SetCell<Any>(ref = CellRef(UUID.fromString(B))).also { attentive.managementInlet.call.spawn(it) }
        awaitUntil("in the view") { server.knowsNow(banded.ref) }

        // the causal act is the test's, not the inspector's
        AttentionSupport.of(banded).attend(1f)
        awaitUntil("the band rose") { attentive.attentionOf(banded.ref) == AttentionBand.HIGH }

        detail(banded.ref).attention shouldBe AttentionBand.HIGH.name.lowercase()
    }

    /**
     * P6, the [InspectorColdTest] leak-check idiom applied to what V2 adds:
     * reading the band and serving the feed create no `ObserveCell` sink,
     * publish no ref, add no link, and leave the band exactly where it was.
     */
    @Test
    fun `reading attention and the activity feed subscribes to nothing and raises no attention`() {
        val plain = spawn(host, A)
        val banded = SetCell<Any>(ref = CellRef(UUID.fromString(B))).also { attentive.managementInlet.call.spawn(it) }
        awaitUntil("both in the view") { server.knowsNow(plain) && server.knowsNow(banded.ref) }
        val refsBefore = registry.localRefs()
        val linksBefore = registry.all().size
        val bandBefore = attentive.attentionOf(banded.ref)

        detail(banded.ref).attention shouldBe bandBefore!!.name.lowercase()
        detail(plain).attention shouldBe null
        activity().entries.shouldBeEmpty()

        server.observedRefs shouldBe emptySet()
        registry.localRefs() shouldContainExactly refsBefore
        registry.all().size shouldBe linksBefore
        attentive.attentionOf(banded.ref) shouldBe bandBefore
    }

    // -------------------------------------------------------------- fixtures

    private fun activity(): ActivitySnapshot = json.decodeFromString(probe.state(InspectorServer.ACTIVITY_PATH))

    private fun entries(): List<ActivityEntry> = activity().entries

    private fun mine(ref: CellRef): List<ActivityEntry> = entries().filter { it.ref == encoded(ref) }

    private fun detail(ref: CellRef): CellDetail =
        json.decodeFromString(probe.state("${InspectorServer.CELL_PATH}/${encoded(ref)}"))

    private fun spawn(on: ManagedHost, uuid: String): CellRef {
        val ref = CellRef(UUID.fromString(uuid))
        on.managementInlet.call.spawn(SetCell<Any>(ref = ref))
        return ref
    }

    private fun pair(on: ManagedHost, first: String, second: String): Pair<CellRef, CellRef> {
        val a = spawn(on, first)
        val b = spawn(on, second)
        on.managementInlet.call.connect(a, "outlet", b, "deltaInlet") as LinkResult.Connected
        return a to b
    }

    private fun suspendCells(vararg refs: CellRef) {
        refs.forEach { host.managementInlet.call.suspend(it) }
        awaitUntil("cells suspended") { refs.all { host.isSuspended(it) } }
    }

    private fun wake(graph: String): HttpResponse<String> {
        val request = HttpRequest
            .newBuilder(URI("http://localhost:${server.boundPort}${InspectorServer.GRAPH_PATH}/$graph/wake"))
            .header(InspectorServer.WAKE_HEADER, InspectorServer.WAKE_HEADER_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build()
        val client = HttpClient.newHttpClient()
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        } finally {
            client.shutdownNow()
        }
    }

    private fun encoded(ref: CellRef): String = InspectorServer.encodeRef(ref)

    private fun listen(on: InspectorServer = server): SseTap {
        val opened = SseTap("http://localhost:${on.boundPort}${InspectorServer.EVENTS_PATH}")
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { on.attachedClients > 0 }
        return opened
    }

    /** A live `text/event-stream` reader, retaining each frame's kind and payload. */
    private inner class SseTap(url: String) : AutoCloseable {
        private val frames = LinkedBlockingQueue<Event>()

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
                    if (line.startsWith(DATA)) frames += json.decodeFromString<Event>(line.removePrefix(DATA))
                }
            }

        fun countOfKind(kind: String): Int = frames.count { it.kind == kind }

        fun lifecyclesOf(ref: String): List<String> = frames
            .filter { it.kind == Event.LIFECYCLE && it.payload["ref"]?.jsonPrimitive?.content == ref }
            .map { it.payload["lifecycle"]!!.jsonPrimitive.content }

        fun awaitKind(kind: String, count: Int): List<kotlinx.serialization.json.JsonObject> {
            awaitUntil("$count '$kind' frames (saw ${countOfKind(kind)})", timeoutMs = 10_000) {
                countOfKind(kind) >= count
            }
            return frames.filter { it.kind == kind }.map { it.payload }
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

    private interface CounterProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** Counts everything it accepts; a negative input throws mid-message. */
    private class FragileCounterCell(override val ref: CellRef = CellRef(UUID.fromString(C))) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input < 0) throw IllegalStateException("poison: $input")
                }
            })
        }

        override fun onActivate(ctx: CellContext) {}
        override fun onDeactivate(ctx: CellContext) {}
    }

    private companion object {
        const val DATA = "data: "

        // fixed and ordered: A < B < C lexicographically, so "g-$A" names the
        // component every `pair` builds
        const val A = "0a000000-0000-4000-8000-000000000000"
        const val B = "0b000000-0000-4000-8000-000000000000"
        const val C = "0c000000-0000-4000-8000-000000000000"
    }
}
