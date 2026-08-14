package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Leased
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.lookup
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * V3-BE parts 2 and 3: the supervision timeline
 * (`RestartRow.cause`/`.causeAtMs`/`.reBaselineAtMs`) and the richer
 * dead-letter detail (`DeadLetterRow.invocation`/`.disposition`).
 *
 * Both are *enrichment of an existing capture point* rather than new machinery,
 * so the shape of these tests is the shape `InspectorErrorsTest` established: a
 * real in-process graph, real host supervision, and the error lane read back
 * through `GET /api/inspect/errors`. What is new here is what the rows say once
 * they arrive — and, for part 3, what they must **never** say: no argument
 * value, in any form, reaches the wire.
 */
class InspectorErrorDetailTest {

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

    private var now = 1_700_000_000_000L

    init {
        server.inspectorClock = { now }
    }

    @AfterEach
    fun tearDown() {
        server.close()
        probe.close()
        hostScheduler.shutdown()
    }

    private fun rawErrors(): String = probe.state(InspectorServer.ERRORS_PATH)
    private fun snapshot(): ErrorSnapshot = json.decodeFromString(rawErrors())

    /**
     * Drive one dead letter through the host's *own* undeliverable-port branch,
     * carrying [args] on the failing invocation. This is the recipe
     * `InspectorErrorsTest` already uses to reach a dead letter that still holds
     * its `HostedPortInvocation`; the only thing this file changes is what the
     * invocation carries.
     */
    private fun deadLetter(ref: CellRef, args: Array<Any?>, context: MessageContext? = null) {
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = ref,
                portName = "no-such-port",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(PROVIDE, args).copy(context = context),
            ),
        )
        awaitUntil("the dead letter was captured") { snapshot().deadLetters.isNotEmpty() }
    }

    private fun spawnCounter(): FragileCounterCell =
        FragileCounterCell().also { host.managementInlet.call.spawn(it) }

    // ------------------------------------------------- part 3: invocation

    @Test
    fun `a dead letter from a failing invocation carries the whole invocation block`() {
        val cell = spawnCounter()
        val stamp = Timestamp(UUID.randomUUID(), 12)
        val context = MessageContext(stamp, PortRef.of(cell.ref, "outlet"), hop = 2)

        deadLetter(cell.ref, arrayOf<Any?>("a-plain-value"), context)

        val row = snapshot().deadLetters.single()
        val invocation = row.invocation.shouldNotBeNull()
        invocation.port shouldBe "no-such-port"
        invocation.type shouldBe HostedPortInvocation.Type.PORT_API.name
        invocation.method shouldBe "provide"
        // declared parameter *types*, never values
        invocation.parameterTypes shouldContainExactly listOf("java.lang.Object")
        invocation.argCount shouldBe 1
        invocation.hop shouldBe 2
        row.wave.shouldNotBeNull().counter shouldBe 12L
        row.disposition.single().let {
            it.index shouldBe 0
            it.ownership shouldBe ArgDisposition.PLAIN
            it.reason shouldBe null
        }
    }

    @Test
    fun `a plain host-level drop carries no invocation and no disposition`() {
        val cell = spawnCounter()

        // routerInlet's own handler throws before any HostedPortInvocation
        // exists — the host-wide drop InspectorErrorsTest documents
        host.routerInlet.call.route(cell.ref, "no-such-port", Invocation.of(PROVIDE, arrayOf("x")))
        awaitUntil("the host-level drop was captured") { snapshot().deadLetters.isNotEmpty() }

        val row = snapshot().deadLetters.single()
        row.ref shouldBe InspectorServer.encodeRef(host.ref)
        row.invocation shouldBe null
        row.disposition.shouldBeEmpty()
    }

    @Test
    fun `an invocation with no arguments reports an empty disposition list`() {
        val cell = spawnCounter()

        deadLetter(cell.ref, arrayOf())

        val row = snapshot().deadLetters.single()
        row.invocation.shouldNotBeNull().argCount shouldBe 0
        row.disposition.shouldBeEmpty()
    }

    // ------------------------------------------------- part 3: ownership

    /**
     * The kernel's own sanitization outcome, read back. `DeadLetters` degenerates
     * an `Owned` to `Frozen` before the dead-letter outlet — a fan-out, which a
     * live exclusive handle must never enter — and the row names that outcome
     * rather than inventing one.
     */
    @Test
    fun `an Owned argument is reported frozen, and its value never reaches the wire`() {
        val cell = spawnCounter()

        deadLetter(cell.ref, arrayOf<Any?>(Owned(OWNED_SECRET)))

        val row = snapshot().deadLetters.single()
        row.disposition.single().let {
            it.index shouldBe 0
            it.ownership shouldBe ArgDisposition.FROZEN
            // only a Redacted stand-in carries a reason; a frozen value has none
            it.reason shouldBe null
        }
        rawErrors() shouldNotContain OWNED_SECRET
    }

    /**
     * A `Leased` is *released* at capture and replaced by a `Redacted` marker,
     * so what the row can honestly say is the kernel's own release reason — and
     * nothing else about it.
     */
    @Test
    fun `a Leased argument is reported redacted with the kernel's release reason`() {
        val cell = spawnCounter()
        var returned = false

        deadLetter(cell.ref, arrayOf<Any?>(Leased(LEASED_SECRET) { returned = true }))

        val row = snapshot().deadLetters.single()
        row.disposition.single().let {
            it.index shouldBe 0
            it.ownership shouldBe ArgDisposition.REDACTED
            // authored by the kernel (DeadLetters.sanitizeForDeadLetter), copied
            // verbatim — this is the one payload-side string on the row
            it.reason.shouldNotBeNull() shouldContain "Leased payload released"
        }
        // the lease obligation really was discharged, not merely relabelled
        returned shouldBe true
        rawErrors() shouldNotContain LEASED_SECRET
    }

    @Test
    fun `a mixed argument list reports one disposition per argument, in argument order`() {
        val cell = spawnCounter()

        deadLetter(
            cell.ref,
            arrayOf<Any?>(Owned(OWNED_SECRET), "plain", Leased(LEASED_SECRET), null),
        )

        val row = snapshot().deadLetters.single()
        row.invocation.shouldNotBeNull().argCount shouldBe 4
        row.disposition.map { it.index } shouldContainExactly listOf(0, 1, 2, 3)
        row.disposition.map { it.ownership } shouldContainExactly listOf(
            ArgDisposition.FROZEN,
            ArgDisposition.PLAIN,
            ArgDisposition.REDACTED,
            ArgDisposition.PLAIN,
        )
        val body = rawErrors()
        body shouldNotContain OWNED_SECRET
        body shouldNotContain LEASED_SECRET
        body shouldNotContain "plain-argument-value"
    }

    // -------------------------------------------- part 2: cause correlation

    @Test
    fun `a supervision restart correlates the dead letter that preceded it`() {
        val cell = spawnCounter()
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy

        // seed the poller's generation baseline before the failure
        server.tickAll()

        api.inlet.call.provide(-1)
        awaitUntil("generation bumped by RESTART") { host.generationOf(cell.ref) == 1L }
        awaitUntil("the failure's dead letter was captured") { snapshot().deadLetters.isNotEmpty() }
        server.tickAll()

        val restart = snapshot().restarts.single()
        restart.ref shouldBe InspectorServer.encodeRef(cell.ref)
        restart.generation shouldBe 1L
        restart.cause shouldBe "IllegalStateException"
        restart.causeAtMs shouldBe now
        // FragileCounterCell is not ReBaselineEmitting: null is "not observed"
        restart.reBaselineAtMs shouldBe null
    }

    /**
     * The correlation is a *time window*, and it says so by refusing to guess:
     * a dead letter older than `RESTART_CAUSE_WINDOW_MS` is not a candidate, so
     * the restart reports `null` rather than the most recent thing it can find.
     */
    @Test
    fun `a restart with no dead letter inside the window reports a null cause`() {
        val cell = spawnCounter()
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy

        val restarts = mutableListOf<RestartRow>()
        val errors = Errors(
            registry = registry,
            hosts = mapOf("test-host" to host),
            onDeadLetter = {},
            onParked = {},
            onRestart = { restarts += it },
            clock = { now },
        )
        try {
            errors.poll() // baseline at generation 0

            api.inlet.call.provide(-1)
            awaitUntil("generation bumped by RESTART") { host.generationOf(cell.ref) == 1L }
            awaitUntil("the dead letter was captured") { errors.snapshot().deadLetters.isNotEmpty() }

            // the bump is only noticed a full window later
            now += Errors.RESTART_CAUSE_WINDOW_MS + 1
            errors.poll()

            val restart = restarts.single()
            restart.generation shouldBe 1L
            restart.cause shouldBe null
            restart.causeAtMs shouldBe null
        } finally {
            errors.close()
        }
    }

    // ------------------------------------------- part 2: the re-baseline beat

    /**
     * `reBaselineAtMs` populated: `UnionSetCell` is the kernel's only
     * `ReBaselineEmitting` implementation, and `ManagedHost`'s RESTART branch
     * completes by re-baselining it over the ordinary catch-up path. The notice
     * rides the minted `MessageContext`, so the payload-agnostic tap the flow
     * feed already installs sees the beat with one null check.
     *
     * The cell needs a tapped *outgoing* edge for that to be visible at all,
     * which is exactly why `null` means "not observed" rather than "did not
     * happen": the two tests below are the two halves of that sentence.
     */
    @Test
    fun `a restarted ReBaselineEmitting cell with a tapped outgoing edge reports its re-baseline beat`() {
        val upstream = SetCell<String>().also { host.managementInlet.call.spawn(it) }
        val union = UnionSetCell<String>().also { host.managementInlet.call.spawn(it) }
        val downstream = FilterCell<String>(predicate = { true })
            .also { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.connect(upstream.ref, "outlet", union.ref, "inlet")
        host.managementInlet.call.connect(union.ref, "outlet", downstream.ref, "inlet")
        awaitUntil("the outgoing edge is tapped") { union.outlet.ref in server.tappedOutlets }
        host.managementInlet.call.supervise(union.ref, SupervisionPolicy.RESTART)

        // give the union some state, so its re-baseline asserts something
        host.lookup<SetApi<String>>(upstream.ref)!!.inlet.call.add("a")
        awaitUntil("the union folded a delta") { downstream.snapshot().toString().contains("a") }
        server.tickAll()

        // a null delta into a non-null parameter: the simplest invocation this
        // cell's own inlet genuinely fails on, so RESTART is reached the way the
        // kernel reaches it rather than being simulated
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = union.ref,
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(PROPAGATE, arrayOf<Any?>(null)),
            ),
        )
        awaitUntil("generation bumped by RESTART") { host.generationOf(union.ref) == 1L }
        awaitUntil("the re-baseline beat was observed") { server.reBaselineAtMsOf(union.ref) != null }
        server.tickAll()

        val restart = snapshot().restarts.single { it.ref == InspectorServer.encodeRef(union.ref) }
        restart.generation shouldBe 1L
        restart.reBaselineAtMs shouldBe now
    }

    @Test
    fun `a restarted cell that is not ReBaselineEmitting reports a null re-baseline`() {
        val cell = spawnCounter()
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = HostedCellProxy.create(cell.ref, host, CounterProxy::class.java) as CounterProxy
        server.tickAll()

        api.inlet.call.provide(-1)
        awaitUntil("generation bumped by RESTART") { host.generationOf(cell.ref) == 1L }
        server.tickAll()

        snapshot().restarts.single().reBaselineAtMs shouldBe null
    }

    // ------------------------------------------------------------ fixtures

    private interface CounterProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** `InspectorErrorsTest`'s poison cell, verbatim: a negative input throws mid-message. */
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

    private companion object {
        val PROVIDE: java.lang.reflect.Method =
            Consumer::class.java.methods.first { it.name == "provide" }

        val PROPAGATE: java.lang.reflect.Method =
            Propagate::class.java.methods.first { it.name == "propagate" }

        /** Values that must never appear in a serialized row, in any form. */
        const val OWNED_SECRET = "owned-payload-value-must-not-appear"
        const val LEASED_SECRET = "leased-payload-value-must-not-appear"
    }
}
