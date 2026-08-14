package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import civictech.testkit.boundedHttpClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * The M2 error lane end to end: `GET /api/inspect/errors` plus the
 * `error.deadLetter` / `error.parked` / `error.restart` SSE events, against a
 * real in-process graph — no kernel changes, only the seams
 * `doc/spec/90-roadmap/97-inspector-plan/tickets/M2-BE.md` names.
 */
class InspectorErrorsTest {

    private val json = Json { ignoreUnknownKeys = false }
    private val registry = LocationRegistry()

    /**
     * The host's scheduler, owned here rather than left to [ManagedHost]'s own
     * default, purely so [tearDown] can stop it (computenet-4vh). `ManagedHost`
     * exposes no shutdown of its own, and its default
     * [VirtualThreadScheduler] eagerly starts a virtual thread that parks in
     * `PriorityBlockingQueue.take()` until something interrupts it — nothing
     * ever did. With JUnit's PER_METHOD lifecycle that leaked one live thread,
     * retaining its whole graph, per test *method*; under `setForkEvery(80)`
     * they accumulated for a whole fork. Named exactly as the default names it
     * so a thread dump reads the same either way.
     */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private val server = InspectorServer(registry, mapOf("test-host" to host), port = 0).startUnscheduled()
    private val probe = HttpProbe("http://localhost:${server.boundPort}")
    private var tap: SseTap? = null

    /**
     * Release order matters: the tap and the server first (so no feed is still
     * emitting), then the probe's JDK client, then the host's drain thread last —
     * a hook that fired during `server.close()` may still be enqueuing to the
     * host, and [VirtualThreadScheduler.submit] rejects work after shutdown.
     */
    @AfterEach
    fun tearDown() {
        tap?.close()
        server.close()
        probe.close()
        hostScheduler.shutdown()
    }

    private fun snapshot(): ErrorSnapshot = json.decodeFromString(probe.state(InspectorServer.ERRORS_PATH))

    private fun listen(): SseTap {
        val opened = SseTap("http://localhost:${server.boundPort}${InspectorServer.EVENTS_PATH}")
        tap = opened
        awaitUntil("sse client attached", timeoutMs = 5_000) { server.attachedClients > 0 }
        return opened
    }

    // ------------------------------------------------------------ dead letters

    @Test
    fun `a dead letter streams as error_deadLetter and lands in the snapshot`() {
        val events = listen()
        val cell = SetCell<String>().also { host.managementInlet.call.spawn(it) }

        // deliver()'s own "unknown port" branch (a drop, not a thrown
        // exception — DeadLetter.cause stays null) is only reached by the
        // data-path pipeline, not by routerInlet.route (which throws
        // synchronously inside its own handler and dead-letters host-wide,
        // with no HostedPortInvocation to recover a target ref from)
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = cell.ref,
                portName = "nope",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(PROVIDE, arrayOf("x")),
            ),
        )

        val frame = events.awaitKind(Event.ERROR_DEAD_LETTER, 1).single()
        frame["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(cell.ref)
        frame["description"]!!.jsonPrimitive.content shouldContain "nope"
        // this dead letter is a drop (unknown target), not a thrown exception
        frame["cause"] shouldBe JsonNull

        awaitUntil("dead letter reaches the snapshot") { snapshot().deadLetters.isNotEmpty() }
        val row = snapshot().deadLetters.single()
        row.ref shouldBe InspectorServer.encodeRef(cell.ref)
        row.description shouldContain "nope"
        row.cause shouldBe null
        snapshot().counters.deadLetters shouldBe 1L
    }

    @Test
    fun `routerInlet routing failures dead-letter host-wide (no target invocation to recover a cell ref from)`() {
        val events = listen()
        val cell = SetCell<String>().also { host.managementInlet.call.spawn(it) }

        host.routerInlet.call.route(cell.ref, "nope", Invocation.of(PROVIDE, arrayOf("x")))

        val frame = events.awaitKind(Event.ERROR_DEAD_LETTER, 1).single()
        // routerInlet's own handler throws before any HostedPortInvocation
        // exists, so there is nothing to recover a target cell ref from —
        // Errors' documented fallback is the host's own ref
        frame["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(host.ref)
        frame["cause"]!!.jsonPrimitive.content shouldBe "IllegalArgumentException"
    }

    @Test
    fun `a thrown invocation's dead letter carries the exception's simple name as cause`() {
        val events = listen()
        val cell = FragileCounterCell().also { host.managementInlet.call.spawn(it) }
        val api = (HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy).inlet.call

        api.provide(-1)

        val frame = events.awaitKind(Event.ERROR_DEAD_LETTER, 1).single()
        frame["cause"]!!.jsonPrimitive.content shouldBe "IllegalStateException"
        frame["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(cell.ref)
    }

    @Test
    fun `the dead-letter ring buffer evicts the oldest once past its cap, but counters keep the true total`() {
        val cell = SetCell<String>().also { host.managementInlet.call.spawn(it) }
        val captured = mutableListOf<DeadLetterRow>()
        // a small cap so the eviction is exercised without 200 round trips
        val small = Errors(registry, mapOf("test-host" to host), onDeadLetter = { captured += it }, onParked = {}, onRestart = {}, ringCapacity = 3)
        try {
            repeat(5) { n -> host.routerInlet.call.route(cell.ref, "nope-$n", Invocation.of(PROVIDE, arrayOf("x"))) }
            awaitUntil("5 dead letters captured") { captured.size == 5 }

            val snap = small.snapshot()
            snap.deadLetters.size shouldBe 3
            snap.deadLetters.map { it.description } shouldBe captured.takeLast(3).map { it.description }
            // the true total, read off supervisionAccounting(), is not capped by the ring buffer
            snap.counters.deadLetters shouldBe 5L
        } finally {
            small.close()
        }
    }

    // --------------------------------------------------------------- restarts

    @Test
    fun `a supervision restart streams as error_restart and lands in the snapshot`() {
        val events = listen()
        val cell = FragileCounterCell().also { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = (HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy).inlet.call

        // seed the poller's baseline at generation 0 before the restart happens,
        // so the later poll sees a genuine increase rather than a false first-sight
        server.tickAll()

        api.provide(-1) // poisons -> RESTART bumps the generation
        awaitUntil("generation bumped by RESTART") { host.generationOf(cell.ref) == 1L }
        server.tickAll()

        val frame = events.awaitKind(Event.ERROR_RESTART, 1).single()
        frame["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(cell.ref)
        frame["generation"]!!.jsonPrimitive.content shouldBe "1"

        val snap = snapshot()
        snap.restarts.single().generation shouldBe 1L
        snap.restarts.single().ref shouldBe InspectorServer.encodeRef(cell.ref)
        snap.counters.restarts shouldBe 1L
    }

    @Test
    fun `a ref's generation observed for the first time seeds silently, it is not reported as a restart`() {
        val cell = FragileCounterCell().also { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = (HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy).inlet.call

        // restart happens BEFORE the poller has ever seen this ref
        api.provide(-1)
        awaitUntil("generation bumped by RESTART") { host.generationOf(cell.ref) == 1L }

        val captured = mutableListOf<RestartRow>()
        val fresh = Errors(registry, mapOf("test-host" to host), onDeadLetter = {}, onParked = {}, onRestart = { captured += it })
        try {
            fresh.poll()
            captured.shouldBeEmpty()
            fresh.snapshot().restarts.shouldBeEmpty()
        } finally {
            fresh.close()
        }
    }

    // ----------------------------------------------------------------- parked

    @Test
    fun `parked traffic streams as error_parked, including the count 0 clear`() {
        val events = listen()
        val cell = SetCell<String>().also { host.managementInlet.call.spawn(it) }
        @Suppress("UNCHECKED_CAST")
        val api = HostedCellProxy.create(cell.ref, registry, SetApi::class.java) as SetApi<String>

        registry.hold(cell.ref)
        api.inlet.call.add("a")
        api.inlet.call.add("b")
        awaitUntil("two invocations parked") { registry.parkedFor(cell.ref).size == 2 }

        server.tickAll()
        val parkedFrame = events.awaitKind(Event.ERROR_PARKED, 1).single()
        parkedFrame["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(cell.ref)
        parkedFrame["port"]!!.jsonPrimitive.content shouldBe "inlet"
        parkedFrame["count"]!!.jsonPrimitive.content shouldBe "2"

        val snap = snapshot()
        snap.parked.single().count shouldBe 2
        snap.parked.single().port shouldBe "inlet"
        snap.counters.parked shouldBe 2L

        registry.release(cell.ref)
        awaitUntil("the parked traffic drained") { registry.parkedFor(cell.ref).isEmpty() }
        server.tickAll()

        val cleared = events.awaitKind(Event.ERROR_PARKED, 2).last()
        cleared["count"]!!.jsonPrimitive.content shouldBe "0"
        cleared["ref"]!!.jsonPrimitive.content shouldBe InspectorServer.encodeRef(cell.ref)

        snapshot().parked.shouldBeEmpty()
        snapshot().counters.parked shouldBe 0L
    }

    @Test
    fun `an unchanged parked count is not re-emitted`() {
        val events = listen()
        val cell = SetCell<String>().also { host.managementInlet.call.spawn(it) }
        @Suppress("UNCHECKED_CAST")
        val api = HostedCellProxy.create(cell.ref, registry, SetApi::class.java) as SetApi<String>

        registry.hold(cell.ref)
        api.inlet.call.add("a")
        awaitUntil("one invocation parked") { registry.parkedFor(cell.ref).size == 1 }

        server.tickAll()
        events.awaitKind(Event.ERROR_PARKED, 1)

        // a second poll with nothing changed must not emit a second frame
        server.tickAll()
        server.tickAll()
        events.countOfKind(Event.ERROR_PARKED) shouldBe 1

        registry.release(cell.ref)
        awaitUntil("drained") { registry.parkedFor(cell.ref).isEmpty() }
    }

    // ------------------------------------------------------------------ dtos

    /**
     * The zeros have to be *declined*, not merely unreachable. The class runs
     * on [InspectorServer.startUnscheduled], so nothing polls unless a test
     * says so: without a spawned cell and an explicit tick, `Errors.poll()`
     * never runs, `pollRestarts` iterates an empty ref set, and the restart
     * assertions below could not fail whatever the poller did (computenet-4e4a).
     * So: spawn a cell, then tick twice — the first tick makes `pollRestarts`
     * take its first-seen branch (seed the baseline, open no row), the second
     * makes it compare an unchanged generation against that baseline.
     */
    @Test
    fun `an idle graph reports an all-zero snapshot`() {
        host.managementInlet.call.spawn(SetCell<String>())

        server.tickAll()
        server.tickAll()

        val snap = snapshot()

        snap.counters.deadLetters shouldBe 0L
        snap.counters.parked shouldBe 0L
        snap.counters.restarts shouldBe 0L
        snap.counters.drainedOnTeardown shouldBe 0L
        snap.deadLetters.shouldBeEmpty()
        snap.parked.shouldBeEmpty()
        snap.restarts.shouldBeEmpty()
    }

    // ------------------------------------------------------------- fixtures

    private interface CounterProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** Counts everything it accepts; a negative input throws mid-message. */
    private class FragileCounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        var count = 0

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input < 0) throw IllegalStateException("poison: $input")
                    count++
                }
            })
        }

        override fun onActivate(ctx: CellContext) {}
        override fun onDeactivate(ctx: CellContext) {}
    }

    private val PROVIDE = Consumer::class.java.methods.find { it.name == "provide" }

    // ------------------------------------------------------------- sse tap

    private data class Frame(val seq: Long, val kind: String, val payload: JsonObject)

    private inner class SseTap(url: String) : AutoCloseable {
        private val frames = LinkedBlockingQueue<Frame>()

        /**
         * Held so [close] can release it (computenet-4vh). One client per
         * `listen()`, i.e. per test method, each with its own selector thread and
         * executor pool; cancelling [reader] alone left all of that alive.
         */
        private val client: HttpClient = boundedHttpClient()
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

        fun countOfKind(kind: String): Int = frames.count { it.kind == kind }

        fun awaitKind(kind: String, count: Int): List<JsonObject> {
            awaitUntil("$count '$kind' frames", timeoutMs = 10_000) {
                frames.count { it.kind == kind } >= count
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

    private companion object {
        const val DATA = "data: "
    }
}
