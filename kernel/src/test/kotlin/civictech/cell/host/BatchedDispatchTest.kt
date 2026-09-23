package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.durability.InMemoryJournal
import civictech.cell.link.LinkResult
import civictech.cell.CellContext
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Batched ingress dispatch (KBLK, `computenet-t6b.2.4`, decision
 * `computenet-t6b.2-D4`): `ManagedHost(dispatchBatch = n)` lets one data-band
 * task dispatch up to `n` staged invocations. It must change the number of
 * priority-20 scheduler tasks and nothing else.
 *
 * - BS-20 (`[KBLK-15..18]`): same input, same seed, batched vs unbatched —
 *   identical per-link sequences, contexts, wave positions and quiescent state;
 *   strictly fewer data-band tasks.
 * - BS-21 (`[KBLK-19]`): the stride floor bounds starvation per dispatched
 *   message, not per task.
 * - BS-22 (`[KBLK-21]`): the default is `1`, and `1` is one task per message.
 * - BS-23 (`[KBLK-11]`): journal order still equals acceptance order.
 *
 * Limit: every test here drives [SimulationController] from one thread. The
 * foreign-thread arm/re-arm race in `ManagedHost.armBatchDispatch`/`drainBatch`
 * is argued in their KDoc, not exercised here.
 */
class BatchedDispatchTest {

    // ---- fixtures -----------------------------------------------------------

    interface IntInlet {
        val inlet: Use<Consumer<Int>>
    }

    /** What a consumer saw: the value and the [MessageContext] it was delivered under. */
    private data class Delivery(val value: Int, val context: MessageContext?)

    private class Relay(val f: (Int) -> Int, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Consumer<Int>>()
        val inlet by input<Consumer<Int>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    outlet.call.provide(f(input))
                }
            })
        }
    }

    private class Recorder(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val deliveries = mutableListOf<Delivery>()
        val inlet by input<Consumer<Int>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    deliveries += Delivery(input, CurrentContext.get())
                }
            })
        }

        /** The consumer's quiescent state: a fold over everything it received. */
        fun state(): Long = deliveries.sumOf { it.value.toLong() }
    }

    /** Counts data-band (priority 20) submissions; delegates everything to [inner]. */
    private class CountingScheduler(private val inner: HostScheduler) : HostScheduler {
        var dataBandTasks = 0
            private set
        override val color: HostColor get() = inner.color
        override fun submit(priority: Int, action: suspend () -> Unit) {
            if (priority == 20) dataBandTasks++
            inner.submit(priority, action)
        }
        override fun <T> await(future: CompletableFuture<T>): T = inner.await(future)
        override fun shutdown() = inner.shutdown()
    }

    /**
     * Counts data invocations the host accepts through its single intake funnel
     * (`enqueueHostedInvocation`), independently of how many tasks dispatch them.
     */
    private class CountingHost(scheduler: HostScheduler, registry: LocationRegistry, dispatchBatch: Int) :
        ManagedHost(scheduler = scheduler, registry = registry, dispatchBatch = dispatchBatch) {
        var acceptedData = 0
            private set
        override fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
            val data = hostedInvocation.type == HostedPortInvocation.Type.PORT_API
            super.enqueueHostedInvocation(hostedInvocation)
            if (data) acceptedData++
        }
    }

    /**
     * Makes contexts from two separate runs comparable. Wave source ids are
     * fresh per outlet construction and port refs are fresh per run, so each is
     * replaced by its first-seen index in a fixed traversal order; everything
     * else in the context (counter, hop, baseline, re-baseline) is kept as is.
     */
    private class Normalizer {
        private val sources = mutableMapOf<UUID, Int>()
        private val ports = mutableMapOf<PortRef, Int>()
        fun of(context: MessageContext?): List<Any?>? = context?.let {
            listOf(
                sources.getOrPut(it.timestamp.sourceId) { sources.size },
                it.timestamp.counter,
                ports.getOrPut(it.sourcePort) { ports.size },
                it.hop,
                it.baseline,
                it.reBaseline,
            )
        }
    }

    // ---- BS-20 --------------------------------------------------------------

    /** One run of the BS-20 graph; everything the comparison needs, already normalized. */
    private data class Bs20Run(
        val perConsumer: Map<String, List<Int>>,
        val contexts: Map<String, List<List<Any?>?>>,
        val states: Map<String, Long>,
        val dataBandTasks: Int,
        val acceptedDataInvocations: Int,
    )

    /**
     * host1: `relay` (x+1) -> `a` (host1), `c` (host2), `doubler` (host2, x*2) -> `d` (host1).
     * Five links, two hosts, a cross-host hop and a cross-host return hop. 200
     * inputs into `relay`: the first 150 interleaved with a few simulation steps
     * so batches form mid-flight, the last 50 as one burst larger than a batch.
     */
    private fun runBs20(dispatchBatch: Int): Bs20Run {
        val controller = SimulationController(seed = 7)
        val registry = LocationRegistry()
        val s1 = CountingScheduler(controller.scheduler())
        val s2 = CountingScheduler(controller.scheduler())
        val host1 = CountingHost(s1, registry, dispatchBatch)
        val host2 = CountingHost(s2, registry, dispatchBatch)

        val relay = Relay({ it + 1 })
        val a = Recorder()
        val c = Recorder()
        val doubler = Relay({ it * 2 })
        val d = Recorder()
        host1.managementInlet.call.spawn(relay)
        host1.managementInlet.call.spawn(a)
        host1.managementInlet.call.spawn(d)
        host2.managementInlet.call.spawn(c)
        host2.managementInlet.call.spawn(doubler)
        controller.runToIdle()

        // same-host links by ref; a cross-host link reaches the downstream inlet
        // through the other host's proxy, under a port ref of its own
        fun remote(host: ManagedHost, ref: CellRef) =
            Use.fixed(host.lookup<IntInlet>(ref)!!.inlet.call, PortRef.generate())
        (host1.managementInlet.call.connect(relay.ref, "outlet", a.ref, "inlet") is LinkResult.Connected) shouldBe true
        host1.managementInlet.call.connect(relay.ref, "outlet", remote(host2, c.ref))
        host1.managementInlet.call.connect(relay.ref, "outlet", remote(host2, doubler.ref))
        host2.managementInlet.call.connect(doubler.ref, "outlet", remote(host1, d.ref))
        controller.runToIdle()

        val tasksBefore = s1.dataBandTasks + s2.dataBandTasks
        val acceptedBefore = host1.acceptedData + host2.acceptedData
        val input = host1.lookup<IntInlet>(relay.ref)!!.inlet.call
        repeat(200) { i ->
            input.provide(i)
            // the last 50 arrive as one burst: a backlog larger than a batch,
            // which only a re-armed task can finish
            if (i < 150 && i % 7 == 0) repeat(3) { controller.step() }
        }
        controller.runToIdle()

        host1.stagedWorkTotal() shouldBe 0
        host2.stagedWorkTotal() shouldBe 0

        val consumers = linkedMapOf("a" to a, "c" to c, "d" to d)
        val normalizer = Normalizer()
        return Bs20Run(
            perConsumer = consumers.mapValues { (_, r) -> r.deliveries.map { it.value } },
            contexts = consumers.mapValues { (_, r) -> r.deliveries.map { normalizer.of(it.context) } },
            states = consumers.mapValues { (_, r) -> r.state() },
            dataBandTasks = s1.dataBandTasks + s2.dataBandTasks - tasksBefore,
            acceptedDataInvocations = host1.acceptedData + host2.acceptedData - acceptedBefore,
        )
    }

    @Test
    fun `BS-20 batched dispatch changes the data-band task count and nothing else`() {
        val unbatched = runBs20(dispatchBatch = 1)
        val batched = runBs20(dispatchBatch = 16)

        // the scenario actually ran end to end
        unbatched.perConsumer.getValue("a") shouldBe (0 until 200).map { it + 1 }
        unbatched.perConsumer.getValue("c") shouldBe (0 until 200).map { it + 1 }
        unbatched.perConsumer.getValue("d") shouldBe (0 until 200).map { (it + 1) * 2 }

        // [KBLK-16] per-link delivered order, [KBLK-17] context, [KBLK-18] state + waves
        batched.perConsumer shouldBe unbatched.perConsumer
        batched.contexts shouldBe unbatched.contexts
        batched.states shouldBe unbatched.states
        // every delivery carried a wave (so the context comparison is not vacuous)
        unbatched.contexts.values.flatten().none { it == null } shouldBe true

        // [KBLK-17] no invocation merged or dropped: the same number accepted either way
        batched.acceptedDataInvocations shouldBe unbatched.acceptedDataInvocations
        // [KBLK-21] unbatched is one task per accepted data invocation
        unbatched.dataBandTasks shouldBe unbatched.acceptedDataInvocations
        // [KBLK-15] only the task count differs, and it is strictly lower
        batched.dataBandTasks shouldBeLessThan unbatched.dataBandTasks
        batched.dataBandTasks shouldBeGreaterThanOrEqual (batched.acceptedDataInvocations + 15) / 16
    }

    // ---- BS-21 --------------------------------------------------------------

    interface StringInlet {
        val inlet: Use<Consumer<String>>
    }

    private class LogSink(val name: String, val log: MutableList<String>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    log += name
                }
            })
        }
    }

    private fun sinkInvocation(cell: CellRef, value: String) = HostedPortInvocation(
        cell, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("provide", listOf("java.lang.Object"), listOf(value)),
    )

    /**
     * A saturated HIGH `hot` cell and a LOW `monitor` on one host; 40 hot and 10
     * monitor messages staged before any dispatch. Returns the global dispatch
     * order as a list of cell names.
     */
    private fun runStride(policy: AttentionPolicy?, dispatchBatch: Int): List<String> {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), attention = policy, dispatchBatch = dispatchBatch)
        val log = mutableListOf<String>()
        val hot = LogSink("hot", log)
        val monitor = LogSink("monitor", log)
        host.managementInlet.call.spawn(hot)
        host.managementInlet.call.spawn(monitor)
        controller.runToIdle()
        if (policy != null) {
            AttentionSupport.of(hot).attend(1f)
            AttentionSupport.of(monitor).attend(0.2f)
        }
        repeat(40) { i ->
            if (i % 4 == 0) host.enqueueHostedInvocation(sinkInvocation(monitor.ref, "m$i"))
            host.enqueueHostedInvocation(sinkInvocation(hot.ref, "h$i"))
        }
        controller.runToIdle()
        log.count { it == "hot" } shouldBe 40
        log.count { it == "monitor" } shouldBe 10
        return log
    }

    /** Longest run of consecutive `hot` dispatches while a monitor message was still pending. */
    private fun longestHotRunWhileMonitorPending(log: List<String>): Int {
        val monitorTotal = log.count { it == "monitor" }
        var monitorsSeen = 0
        var run = 0
        var longest = 0
        for (name in log) {
            if (name == "monitor") {
                monitorsSeen++
                run = 0
            } else if (monitorsSeen < monitorTotal) {
                run++
                longest = maxOf(longest, run)
            }
        }
        return longest
    }

    @Test
    fun `BS-21 the stride floor bounds monitor starvation per message under batching`() {
        val stride = 2
        val unbatched = runStride(AttentionPolicy(stride = stride), dispatchBatch = 1)
        val batched = runStride(AttentionPolicy(stride = stride), dispatchBatch = 8)

        // the monitor is dispatched at least once per `stride` hot messages, batched or not
        longestHotRunWhileMonitorPending(unbatched) shouldBeLessThanOrEqual stride
        longestHotRunWhileMonitorPending(batched) shouldBeLessThanOrEqual stride
        // and not merely within the same bound: the very same dispatch order
        batched shouldBe unbatched
    }

    @Test
    fun `BS-21 control - without a stride floor the detector sees starvation, batched or not`() {
        val starved = runStride(AttentionPolicy(stride = Int.MAX_VALUE), dispatchBatch = 8)
        // every hot message runs before any monitor message: the check above can fail
        starved.take(40).all { it == "hot" } shouldBe true
        longestHotRunWhileMonitorPending(starved) shouldBe 40
        starved shouldBe runStride(AttentionPolicy(stride = Int.MAX_VALUE), dispatchBatch = 1)
    }

    @Test
    fun `BS-21 control - with no attention policy band selection is still per message inside a batch`() {
        val batched = runStride(policy = null, dispatchBatch = 8)
        // oldest head first, per message: the monitor is interleaved, not held behind a batch of hot
        batched shouldBe runStride(policy = null, dispatchBatch = 1)
        longestHotRunWhileMonitorPending(batched) shouldBeLessThanOrEqual 4
    }

    private class ThrowingSink(val log: MutableList<String>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    if (input == "boom") error("boom")
                    log += input
                }
            })
        }
    }

    /**
     * A handler failure mid-batch is accounted exactly as unbatched and strands
     * nothing. Limit: `deliver` absorbs a handler's exception into supervision
     * before it reaches the batch loop, so this does NOT prove `drainBatch`'s
     * `finally` re-arm (removing the `try` leaves this green); that `finally`
     * guards an exception escaping `dispatchUpTo` itself, argued in its KDoc.
     */
    @Test
    fun `a handler that throws mid-batch is accounted as unbatched and strands nothing`() {
        fun run(dispatchBatch: Int): Pair<List<String>, SupervisionAccounting> {
            val controller = SimulationController()
            val host = ManagedHost(scheduler = controller.scheduler(), dispatchBatch = dispatchBatch)
            val log = mutableListOf<String>()
            val sink = ThrowingSink(log)
            host.managementInlet.call.spawn(sink)
            controller.runToIdle()
            val api = host.lookup<StringInlet>(sink.ref)!!.inlet.call
            (0 until 20).forEach { api.provide(if (it == 3) "boom" else "v$it") }
            controller.runToIdle()
            host.stagedWorkTotal() shouldBe 0
            return log.toList() to host.supervisionAccounting()
        }
        val (unbatchedLog, unbatchedAccounting) = run(dispatchBatch = 1)
        val (batchedLog, batchedAccounting) = run(dispatchBatch = 8)
        batchedLog shouldBe (0 until 20).filter { it != 3 }.map { "v$it" }
        batchedLog shouldBe unbatchedLog
        batchedAccounting shouldBe unbatchedAccounting
    }

    // ---- BS-22 --------------------------------------------------------------

    @Test
    fun `BS-22 dispatchBatch defaults to one and must be positive`() {
        val host = ManagedHost(scheduler = SimulationController().scheduler())
        val field = ManagedHost::class.java.getDeclaredField("dispatchBatch").apply { isAccessible = true }
        field.getInt(host) shouldBe 1
        shouldThrow<IllegalArgumentException> {
            ManagedHost(scheduler = SimulationController().scheduler(), dispatchBatch = 0)
        }
    }

    // ---- BS-23 --------------------------------------------------------------

    @Test
    fun `BS-23 journal replay order equals acceptance order under batching`() {
        val controller = SimulationController()
        val journal = InMemoryJournal()
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal, dispatchBatch = 8)
        val log = mutableListOf<String>()
        val left = LogSink("left", log)
        val right = LogSink("right", log)
        host.managementInlet.call.spawn(left)
        host.managementInlet.call.spawn(right)
        controller.runToIdle()

        val cells = setOf(left.ref, right.ref)
        val rng = Random(23)
        val accepted = mutableListOf<Pair<CellRef, String>>()
        repeat(50) { i ->
            val target = if (rng.nextBoolean()) left.ref else right.ref
            // through the @Contract proxy: a journaled frame must be wire-capable
            host.lookup<StringInlet>(target)!!.inlet.call.provide("v$i")
            accepted += target to "v$i"
            // let batches dispatch between acceptances, so appends and batched
            // dispatch genuinely interleave
            if (rng.nextInt(3) == 0) repeat(rng.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        host.stagedWorkTotal() shouldBe 0
        log.size shouldBe 50

        val replayed = journal.replay()
            .filter { it[0] == 1.toByte() } // RECORD_FRAME, as HostDurability.recoverFrom reads it
            .map { WireCodec.decode(it.copyOfRange(1, it.size)) }
            .filter { it.cellRef in cells }
            .map { it.cellRef to it.invocation.args[0] as String }
        replayed shouldBe accepted
        accepted.map { it.first }.toSet() shouldBe cells // both cells were exercised
    }
}
