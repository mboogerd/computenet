package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.control.Magnitude
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.CycleHead
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.feedbackInlet
import civictech.cell.port.registerPort
import civictech.cell.verify.InvariantCell
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class EvolveOrchestratorTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    private class EchoCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Int>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                    outlet.call.provide(input)
                }
            })
        }
    }

    @Test
    fun `FanOutlet re-subscribe of the same non-null port ref is idempotent`() {
        val upstream = FanOutlet(consumerInt)
        val cell = EchoCell()

        upstream.subscribe(cell.inlet as Use<Consumer<Int>>)
        upstream.subscribe(cell.inlet as Use<Consumer<Int>>)
        upstream.call.provide(7)

        cell.received shouldBe listOf(7)
    }

    @Test
    fun `a subscription installed before shadow spawn survives hosting`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = EchoCell()
        val observed = mutableListOf<Int>()
        cell.outlet.subscribe(Use.fixed(object : Consumer<Int> {
            override fun provide(input: Int) {
                observed += input
            }
        }, PortRef.generate()))

        Shadow.spawn(host, cell)
        controller.runToIdle()
        cell.inlet.call.provide(11)

        observed shouldBe listOf(11)
    }

    @Test
    fun `accept promotes after three observed waves without changing the production stream`() {
        val run = Run(seed = 2)
        val control = ControlRun(seed = 2)
        val hooks = RecordingHooks()
        val handle = run.start(hooks = hooks)

        (1..3).forEach { value ->
            run.emit(value)
            control.emit(value)
        }

        handle.advance() shouldBe EvolutionHandle.State.PROMOTED
        run.idle()
        run.host.portAt(run.incumbent.ref, "outlet") shouldBe null
        (run.host.portAt(run.candidate.ref, "outlet") != null) shouldBe true
        hooks.promoted shouldBe listOf(run.incumbent.ref)

        (4..6).forEach { value ->
            run.emit(value)
            control.emit(value)
        }
        run.view.received shouldBe control.view.received
    }

    @Test
    fun `pending remains shadowing and leaves the traffic light green`() {
        val run = Run(seed = 3)
        val handle = run.start()

        run.emit(1)
        run.emit(2)
        handle.advance() shouldBe EvolutionHandle.State.SHADOWING
        handle.verdict() shouldBe PromotionVerdict.Pending
        handle.await(0) shouldBe EvolutionHandle.State.SHADOWING

        run.emit(3)
        run.view.received shouldBe listOf(1L, 3L, 6L)
    }

    @Test
    fun `a candidate gate violation rejects and despawns the shadow without starting a swap`() {
        val run = Run(seed = 4, candidateFactory = ::RegressingSummer)
        val hooks = RecordingHooks()
        val handle = run.start(hooks = hooks)

        (1..3).forEach(run::emit)
        val beforeReject = run.view.received.toList()

        handle.advance() shouldBe EvolutionHandle.State.REJECTED
        run.idle()
        handle.reason!!.shouldContain("violated the promotion policy")
        hooks.despawned shouldBe listOf(run.candidate.ref)
        run.host.portAt(run.candidate.ref, "outlet") shouldBe null
        run.view.received shouldBe beforeReject

        run.emit(4)
        run.view.received shouldBe listOf(1L, 3L, 6L, 10L)
    }

    @Test
    fun `a commit failure reports rollback and leaves the candidate shadow live`() {
        val run = Run(seed = 5, candidateFactory = ::FailingSummerV2)
        val handle = run.start()

        (1..3).forEach(run::emit)

        handle.advance() shouldBe EvolutionHandle.State.ROLLED_BACK
        run.idle()
        handle.reason!!.shouldContain("candidate state transfer boom")
        (run.host.portAt(run.candidate.ref, "outlet") != null) shouldBe true

        run.emit(4)
        run.view.received shouldBe listOf(1L, 3L, 6L, 10L)
    }

    @Test
    fun `a differential baseline distinguishes worse candidate from a shared threshold failure`() {
        val differentialPolicy = policy(
            baseline = true,
            threshold = SatisfactionCriterion { violations -> violations <= 1 },
        )
        val worse = Run(
            seed = 6,
            candidateFactory = ::RegressingSummer,
            baselineFactory = ::SummerV2,
            policy = differentialPolicy,
        )
        val worseHooks = RecordingHooks()
        val worseHandle = worse.start(hooks = worseHooks)
        (1..3).forEach(worse::emit)

        worseHandle.advance() shouldBe EvolutionHandle.State.REJECTED
        worseHandle.reason!!.shouldContain("differential shadow")
        worseHooks.despawned shouldBe listOf(worse.candidate.ref, worse.baselineTwin!!.ref)

        val bothRegress = Run(
            seed = 7,
            candidateFactory = ::RegressingSummer,
            baselineFactory = ::RegressingSummer,
            policy = policy(baseline = true),
        )
        val bothHandle = bothRegress.start()
        (1..3).forEach(bothRegress::emit)

        bothHandle.advance() shouldBe EvolutionHandle.State.REJECTED
        bothHandle.reason!!.shouldContain("violated the promotion policy")
        bothHandle.reason!!.shouldNotContain("differential shadow")
    }

    @Test
    fun `remote and custom authority refusals happen before shadow spawn`() {
        val remote = Run(seed = 8)
        val remoteRefusal = CurrentPeer.with(PeerId("mallory")) {
            shouldThrow<Evolve.Refused> { remote.start() }
        }

        remoteRefusal.message!!.shouldContain("authority")
        remote.host.portAt(remote.candidate.ref, "outlet") shouldBe null
        remote.emit(1)
        remote.view.received shouldBe listOf(1L)

        val custom = Run(seed = 9)
        val customRefusal = shouldThrow<Evolve.Refused> {
            custom.start(authority = EvolutionAuthority { "nobody" })
        }
        customRefusal.message!!.shouldContain("authority")
        custom.host.portAt(custom.candidate.ref, "outlet") shouldBe null
    }

    @Test
    fun `authority is checked again before an accepted handle can advance`() {
        var denied = false
        val authority = EvolutionAuthority { if (denied) "authority was revoked" else null }
        val run = Run(seed = 10)
        val handle = run.start(authority = authority)
        (1..3).forEach(run::emit)

        denied = true
        val refusal = shouldThrow<Evolve.Refused> { handle.advance() }

        refusal.message!!.shouldContain("authority")
        handle.state shouldBe EvolutionHandle.State.SHADOWING
        (run.host.portAt(run.incumbent.ref, "outlet") != null) shouldBe true
        run.emit(4)
        run.view.received shouldBe listOf(1L, 3L, 6L, 10L)
    }

    @Test
    fun `gate name mismatch refuses before shadow spawn`() {
        val run = Run(seed = 11, policy = policy(gates = listOf("other")))

        val refusal = shouldThrow<Evolve.Refused> { run.start() }

        refusal.message!!.shouldContain("gates")
        run.host.portAt(run.candidate.ref, "outlet") shouldBe null
        run.emit(1)
        run.view.received shouldBe listOf(1L)
    }

    @Test
    fun `a promotion precheck refusal is rethrown and remains retryable at judged accept`() {
        val run = Run(seed = 12, candidateFactory = ::NonIdempotentSummer)
        val handle = run.start()
        (1..3).forEach(run::emit)

        val aborted = shouldThrow<Promotion.PromotionAborted> { handle.advance() }

        aborted.message!!.shouldContain("PRECHECK")
        handle.state shouldBe EvolutionHandle.State.JUDGED_ACCEPT
        (run.host.portAt(run.candidate.ref, "outlet") != null) shouldBe true
        run.emit(4)
        run.view.received shouldBe listOf(1L, 3L, 6L, 10L)
    }

    @Test
    fun `cycle quiescence defers the handle but remains a promotion precheck refusal`() {
        val cyclePolicy = PromotionPolicy(
            gates = listOf(GATE_NAME),
            window = ObservationWindow(4),
            judge = "judge",
        )
        val run = Run(seed = 13, incumbentFactory = ::CyclicSummerV1, policy = cyclePolicy)
        val hooks = RecordingHooks()
        val handle = run.start(hooks = hooks)
        val incumbent = run.incumbent as CyclicSummerV1
        (1..3).forEach(run::emit)

        handle.advance() shouldBe EvolutionHandle.State.SHADOWING
        handle.reason shouldBe null
        hooks.despawned shouldBe emptyList()
        hooks.promoted shouldBe emptyList()
        (run.host.portAt(run.incumbent.ref, "outlet") != null) shouldBe true
        (run.host.portAt(run.candidate.ref, "outlet") != null) shouldBe true

        val directJudge = PromotionJudge(cyclePolicy, cycleHead = incumbent)
        repeat(4) { directJudge.observeCandidateWave() }
        val aborted = shouldThrow<Promotion.PromotionAborted> {
            Promotion.promote(
                host = run.host,
                gate = run.gate,
                incumbent = run.incumbent,
                candidate = run.candidate,
                outletName = "outlet",
                downstream = listOf(run.view.inlet),
                judge = directJudge,
            )
        }
        aborted.message!!.shouldContain("PRECHECK")
        aborted.message!!.shouldContain("cycle promotion deferred")

        run.emit(4)
        run.view.received shouldBe listOf(1L, 3L, 6L, 10L)
        incumbent.feedbackInput.call.provide(CycleDelta(0.001))

        handle.advance() shouldBe EvolutionHandle.State.PROMOTED
        hooks.promoted shouldBe listOf(run.incumbent.ref)
    }

    @Test
    fun `a window-filling wave cannot promote before its queued violation is judged`() {
        val run = Run(
            seed = 14,
            candidateFactory = ::ThirdWaveRegressingSummer,
            stagedCandidateGate = true,
        )
        val hooks = RecordingHooks()
        val handle = run.start(hooks = hooks)
        run.emit(1)
        run.emit(2)

        run.submit(3)
        run.controller.step() shouldBe true

        handle.advance() shouldBe EvolutionHandle.State.SHADOWING
        hooks.promoted shouldBe emptyList()
        run.idle()
        handle.advance() shouldBe EvolutionHandle.State.REJECTED
        hooks.promoted shouldBe emptyList()
        (run.host.portAt(run.incumbent.ref, "outlet") != null) shouldBe true
    }

    @Test
    fun `candidate waves share one pending settlement fence`() {
        val run = Run(seed = 15)
        val handle = run.start()

        (1..3).forEach(run::submit)
        run.idle()

        run.scheduler.lowestPrioritySubmissions.get() shouldBe 1
        handle.verdict() shouldBe PromotionVerdict.Accept
    }

    @Test
    fun `a terminated scheduler cannot throw from the candidate observation tap`() {
        val run = Run(seed = 16)
        val handle = run.start()
        run.scheduler.refuseSubmissions()

        val failure = runCatching { run.candidate.outlet.call.provide(1L) }.exceptionOrNull()

        failure shouldBe null
        handle.verdict() shouldBe PromotionVerdict.Pending
    }

    @Test
    fun `recovery succeeds while an evolution settlement fence is pending`() {
        val run = Run(seed = 17)
        val handle = run.start()
        run.submit(1)
        while (run.scheduler.lowestPrioritySubmissions.get() == 0) {
            run.controller.step() shouldBe true
        }

        val recovery = run.host.recoverFrom(InMemoryJournal())

        run.idle()
        recovery.isApplied shouldBe true
        handle.verdict() shouldBe PromotionVerdict.Pending
    }

    @Test
    fun `a settlement fence that runs during recovery waits for gated deliveries and re-arms`() {
        val scheduler = RecordingScheduler(VirtualThreadScheduler("evolve-recovery-settlement"))
        val host = ManagedHost(scheduler = scheduler)
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, instanceId = 0))
        val candidate = BlockingAfterEmitSummer(CellRef(logicalId, instanceId = 1))
        val candidateGate = nonDecreasingGate()
        val adapter = InvariantAdapterCell(candidateGate)
        val view = CollectorCell()
        val journal = BlockingReplayJournal()
        val recoveryFailure = AtomicReference<Throwable>()
        val recoveryDone = CountDownLatch(1)

        try {
            listOf<Cell>(source, gate, incumbent, candidateGate, adapter, view).forEach {
                host.managementInlet.call.spawn(it)
            }
            host.quiescence().await(5_000, "spawn recovery-settlement graph")
            val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
            val routedInvariant =
                (HostedCellProxy.create(adapter.ref, host, LongConsumerProxy::class.java) as LongConsumerProxy).inlet.call
            source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
            gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
            incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
            candidate.outlet.subscribe(Use.fixed(routedInvariant, PortRef.generate()))
            gate.controlInlet.call.setGreen()

            val handle = Evolve.run(
                host = host,
                gate = gate,
                incumbent = incumbent,
                candidate = candidate,
                outletName = "outlet",
                downstream = listOf(view.inlet),
                policy = policy().copy(window = ObservationWindow(1)),
                gates = listOf(candidateGate),
            )
            val submissionsBeforeWave = scheduler.lowestPrioritySubmissions.get()
            val settlementFenceRan = scheduler.expectLowestPriorityRun()
            source.emit(1)
            candidate.emitted.await(5, TimeUnit.SECONDS) shouldBe true
            scheduler.lowestPrioritySubmissions.get() shouldBe submissionsBeforeWave + 1

            val recoveryFenceSubmitted = scheduler.expectManagementSubmission()
            Thread.ofVirtual().start {
                try {
                    host.recoverFrom(journal).awaitApplied(5_000)
                } catch (failure: Throwable) {
                    recoveryFailure.set(failure)
                } finally {
                    recoveryDone.countDown()
                }
            }
            recoveryFenceSubmitted.await(5, TimeUnit.SECONDS) shouldBe true
            candidate.release()
            journal.replayEntered.await(5, TimeUnit.SECONDS) shouldBe true
            settlementFenceRan.await(5, TimeUnit.SECONDS) shouldBe true

            handle.verdict() shouldBe PromotionVerdict.Pending

            journal.release()
            recoveryDone.await(5, TimeUnit.SECONDS) shouldBe true
            recoveryFailure.get() shouldBe null
            handle.verdict().let { verdict ->
                (verdict as PromotionVerdict.Reject).reason.shouldContain("violated the promotion policy")
            }
        } finally {
            candidate.release()
            journal.release()
            scheduler.shutdown()
        }
    }

    @Test
    fun `recovery can start during a candidate emission without leaking settlement refusal`() {
        val scheduler = RecordingScheduler(VirtualThreadScheduler("evolve-recovery-overlap"))
        val host = ManagedHost(scheduler = scheduler)
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, instanceId = 0))
        val candidate = BlockingBeforeEmitSummer(CellRef(logicalId, instanceId = 1))
        val candidateGate = nonDecreasingGate()
        val view = CollectorCell()
        val recoveryFailure = AtomicReference<Throwable>()
        val recoveryDone = CountDownLatch(1)

        try {
            listOf<Cell>(source, gate, incumbent, candidateGate, view).forEach {
                host.managementInlet.call.spawn(it)
            }
            val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
            source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
            gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
            incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
            connectGate(candidate, candidateGate)
            gate.controlInlet.call.setGreen()

            val handle = Evolve.run(
                host = host,
                gate = gate,
                incumbent = incumbent,
                candidate = candidate,
                outletName = "outlet",
                downstream = listOf(view.inlet),
                policy = policy(),
                gates = listOf(candidateGate),
            )
            val recoveryFenceSubmitted = scheduler.expectManagementSubmission()
            source.emit(1)
            candidate.entered.await(5, TimeUnit.SECONDS) shouldBe true

            Thread.ofVirtual().start {
                try {
                    host.recoverFrom(InMemoryJournal()).awaitApplied(5_000)
                } catch (failure: Throwable) {
                    recoveryFailure.set(failure)
                } finally {
                    recoveryDone.countDown()
                }
            }
            recoveryFenceSubmitted.await(5, TimeUnit.SECONDS) shouldBe true
            candidate.release()

            recoveryDone.await(5, TimeUnit.SECONDS) shouldBe true
            recoveryFailure.get() shouldBe null
            candidate.emissionFailure.get() shouldBe null
            handle.verdict() shouldBe PromotionVerdict.Pending
        } finally {
            candidate.release()
            scheduler.shutdown()
        }
    }

    @Test
    fun `await on a live host rejects after the window-filling violation leaves the queue`() {
        val host = ManagedHost()
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, instanceId = 0))
        val candidate = ThirdWaveRegressingSummer(CellRef(logicalId, instanceId = 1))
        val candidateGate = nonDecreasingGate()
        val adapter = BlockingInvariantAdapterCell(candidateGate)
        val view = CollectorCell()
        val hooks = RecordingHooks()
        val polling = AtomicBoolean(false)
        val pollCount = AtomicInteger()
        val secondPoll = CountDownLatch(1)
        val authority = EvolutionAuthority {
            if (polling.get() && pollCount.incrementAndGet() == 2) secondPoll.countDown()
            null
        }

        listOf<Cell>(source, gate, incumbent, candidateGate, adapter, view).forEach {
            host.managementInlet.call.spawn(it)
        }
        val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
        val routedInvariant =
            (HostedCellProxy.create(adapter.ref, host, LongConsumerProxy::class.java) as LongConsumerProxy).inlet.call
        source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
        gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
        incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
        candidate.outlet.subscribe(Use.fixed(routedInvariant, PortRef.generate()))
        gate.controlInlet.call.setGreen()

        val handle = Evolve.run(
            host = host,
            gate = gate,
            incumbent = incumbent,
            candidate = candidate,
            outletName = "outlet",
            downstream = listOf(view.inlet),
            policy = policy(),
            gates = listOf(candidateGate),
            authority = authority,
            hooks = hooks,
        )
        source.emit(1)
        host.quiescence().await(5_000, "first live evolution wave")
        source.emit(2)
        host.quiescence().await(5_000, "second live evolution wave")

        val awaitResult = AtomicReference<EvolutionHandle.State>()
        val awaitFailure = AtomicReference<Throwable>()
        val awaitDone = CountDownLatch(1)
        var waiter: Thread? = null
        try {
            adapter.arm()
            source.emit(3)
            adapter.entered.await(5, TimeUnit.SECONDS) shouldBe true

            polling.set(true)
            waiter = Thread.ofVirtual().start {
                try {
                    awaitResult.set(handle.await(5_000))
                } catch (failure: Throwable) {
                    awaitFailure.set(failure)
                } finally {
                    awaitDone.countDown()
                }
            }

            // A second poll proves the first one saw only the last drained prefix. Before the
            // fix the first poll accepted immediately while the adapter held the violation.
            secondPoll.await(5, TimeUnit.SECONDS) shouldBe true
            handle.state shouldBe EvolutionHandle.State.SHADOWING
            hooks.promoted shouldBe emptyList()

            adapter.release()
            awaitDone.await(5, TimeUnit.SECONDS) shouldBe true
            awaitFailure.get() shouldBe null
            awaitResult.get() shouldBe EvolutionHandle.State.REJECTED
            host.quiescence().await(5_000, "live rejected shadow teardown")
            hooks.promoted shouldBe emptyList()
            (host.portAt(incumbent.ref, "outlet") != null) shouldBe true
        } finally {
            adapter.release()
            waiter?.join(5_000)
        }
    }

    private interface SummingCell : Cell {
        val inlet: FanInlet<Consumer<Int>>
        val outlet: FanOutlet<Consumer<Long>>
    }

    private class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(value: Int) = outlet.call.provide(value)
    }

    private open class SummerV1(override val ref: CellRef) : SummingCell, Stateful {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.provide(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum

        override fun restore(state: Serializable) {
            sum = state as Long
        }
    }

    private data class CycleDelta(val value: Double) : Magnitude {
        override fun size(): Double = abs(value)
    }

    private class CyclicSummerV1(ref: CellRef) : SummerV1(ref), CycleHead<CycleDelta> {
        override val feedbackInput by feedbackInlet<CycleDelta>(quiescence = 0.01) {}
    }

    private open class SummerV2(override val ref: CellRef) : SummingCell, StateMigrating {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var representation = "sum=0"

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    representation = "sum=${sum() + input}"
                    outlet.call.provide(sum())
                }
            })
        }

        private fun sum(): Long = representation.removePrefix("sum=").toLong()

        override fun importFrom(prior: Serializable) {
            representation = "sum=${prior as Long}"
        }
    }

    private class FailingSummerV2(ref: CellRef) : SummerV2(ref) {
        override fun importFrom(prior: Serializable) {
            throw IllegalStateException("candidate state transfer boom")
        }
    }

    private open class RegressingSummer(override val ref: CellRef) : SummingCell {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L
        private var inputs = 0

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    inputs++
                    sum += input
                    outlet.call.provide(if (inputs == 2) sum - 100 else sum)
                }
            })
        }
    }

    private class ThirdWaveRegressingSummer(override val ref: CellRef) : SummingCell {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L
        private var inputs = 0

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    inputs++
                    sum += input
                    outlet.call.provide(if (inputs == 3) sum - 100 else sum)
                }
            })
        }
    }

    private class BlockingBeforeEmitSummer(override val ref: CellRef) : SummingCell {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        val entered = CountDownLatch(1)
        val emissionFailure = AtomicReference<Throwable>()
        private val release = CountDownLatch(1)
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test did not release candidate emission" }
                    try {
                        outlet.call.provide(sum)
                    } catch (failure: Throwable) {
                        emissionFailure.set(failure)
                    }
                }
            })
        }

        fun release() {
            release.countDown()
        }
    }

    private class BlockingAfterEmitSummer(override val ref: CellRef) : SummingCell {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        val emitted = CountDownLatch(1)
        private val release = CountDownLatch(1)

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    outlet.call.provide(-input.toLong())
                    emitted.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test did not release candidate after emission" }
                }
            })
        }

        fun release() {
            release.countDown()
        }
    }

    private class BlockingReplayJournal : Journal by InMemoryJournal() {
        val replayEntered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun replay(): List<ByteArray> {
            replayEntered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release journal replay" }
            return emptyList()
        }

        fun release() {
            release.countDown()
        }
    }

    private class NonIdempotentSummer(override val ref: CellRef) : SummingCell, Promotion.NonIdempotentCatchUp {
        override val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        override val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.provide(sum)
                }
            })
        }
    }

    private class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Long>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Long>>())

        init {
            inlet.serve(object : Consumer<Long> {
                override fun provide(input: Long) {
                    received += input
                }
            })
        }
    }

    private class InvariantAdapterCell(
        invariant: InvariantCell<Long, Pair<Long, Long>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Long>>())

        init {
            inlet.serve(object : Consumer<Long> {
                override fun provide(input: Long) {
                    invariant.inlet.call.propagate(input)
                }
            })
        }
    }

    private class BlockingInvariantAdapterCell(
        invariant: InvariantCell<Long, Pair<Long, Long>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Long>>())
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val armed = AtomicBoolean()

        init {
            inlet.serve(object : Consumer<Long> {
                override fun provide(input: Long) {
                    if (armed.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "test did not release the invariant adapter" }
                    }
                    invariant.inlet.call.propagate(input)
                }
            })
        }

        fun arm() {
            armed.set(true)
        }

        fun release() {
            release.countDown()
        }
    }

    private interface GateProxy {
        val dataInlet: Use<Consumer<Int>>
    }

    private interface LongConsumerProxy {
        val inlet: Use<Consumer<Long>>
    }

    private class RecordingHooks : EvolutionHooks {
        val despawned = mutableListOf<CellRef>()
        val promoted = mutableListOf<CellRef>()

        override fun despawnShadow(host: ManagedHost, ref: CellRef) {
            despawned += ref
            host.managementInlet.call.despawn(ref)
        }

        override fun promoted(incumbent: CellRef) {
            promoted += incumbent
        }
    }

    private class RecordingScheduler(private val delegate: HostScheduler) : HostScheduler by delegate {
        val lowestPrioritySubmissions = AtomicInteger()
        private val refusing = AtomicBoolean()
        private val nextManagementSubmission = AtomicReference<CountDownLatch>()
        private val nextLowestPriorityRun = AtomicReference<CountDownLatch>()

        override fun submit(priority: Int, action: suspend () -> Unit) {
            check(!refusing.get()) { "host scheduler terminated" }
            if (priority == Int.MAX_VALUE) lowestPrioritySubmissions.incrementAndGet()
            if (priority == 0) nextManagementSubmission.getAndSet(null)?.countDown()
            delegate.submit(priority) {
                action()
                if (priority == Int.MAX_VALUE) nextLowestPriorityRun.getAndSet(null)?.countDown()
            }
        }

        fun refuseSubmissions() {
            refusing.set(true)
        }

        fun expectManagementSubmission(): CountDownLatch = CountDownLatch(1).also { latch ->
            check(nextManagementSubmission.compareAndSet(null, latch)) {
                "a management submission expectation is already armed"
            }
        }

        fun expectLowestPriorityRun(): CountDownLatch = CountDownLatch(1).also { latch ->
            check(nextLowestPriorityRun.compareAndSet(null, latch)) {
                "a lowest-priority run expectation is already armed"
            }
        }
    }

    private inner class Run(
        seed: Long,
        incumbentFactory: (CellRef) -> SummingCell = ::SummerV1,
        candidateFactory: (CellRef) -> SummingCell = ::SummerV2,
        baselineFactory: ((CellRef) -> SummingCell)? = null,
        val policy: PromotionPolicy = policy(baseline = baselineFactory != null),
        stagedCandidateGate: Boolean = false,
    ) {
        val controller = SimulationController(seed)
        val scheduler = RecordingScheduler(controller.scheduler())
        val host = ManagedHost(scheduler = scheduler)
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = incumbentFactory(CellRef(logicalId, instanceId = 0))
        val candidate = candidateFactory(CellRef(logicalId, instanceId = 1))
        val baselineTwin = baselineFactory?.invoke(CellRef(logicalId, instanceId = 2))
        val candidateGate = nonDecreasingGate()
        val candidateGateAdapter = if (stagedCandidateGate) InvariantAdapterCell(candidateGate) else null
        val baselineGate = baselineTwin?.let { nonDecreasingGate() }
        val view = CollectorCell()

        init {
            listOfNotNull<Cell>(source, gate, incumbent, view, candidateGate, candidateGateAdapter, baselineGate).forEach {
                host.managementInlet.call.spawn(it)
            }
            controller.runToIdle()

            val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
            source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
            gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
            incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
            if (stagedCandidateGate) connectGateStaged(candidate)
            else connectGate(candidate, candidateGate)
            baselineTwin?.let { twin -> connectGate(twin, baselineGate!!) }
            gate.controlInlet.call.setGreen()
        }

        fun start(
            authority: EvolutionAuthority = EvolutionAuthority.LocalTrustedOnly,
            hooks: EvolutionHooks? = null,
        ): EvolutionHandle = Evolve.run(
            host = host,
            gate = gate,
            incumbent = incumbent,
            candidate = candidate,
            outletName = "outlet",
            downstream = listOf(view.inlet),
            policy = policy,
            gates = listOf(candidateGate),
            baseline = baselineTwin?.let { Evolve.Baseline(it, listOf(baselineGate!!)) },
            authority = authority,
            hooks = hooks,
        )

        fun emit(value: Int) {
            source.emit(value)
            controller.runToIdle()
        }

        fun submit(value: Int) {
            source.emit(value)
        }

        fun idle() {
            controller.runToIdle()
        }

        private fun connectGateStaged(cell: SummingCell) {
            @Suppress("UNCHECKED_CAST")
            val adapter = requireNotNull(candidateGateAdapter)
            val routed = (HostedCellProxy.create(adapter.ref, host, LongConsumerProxy::class.java) as LongConsumerProxy)
                .inlet.call
            cell.outlet.subscribe(Use.fixed(routed, PortRef.generate()))
        }
    }

    private inner class ControlRun(seed: Long) {
        private val controller = SimulationController(seed)
        private val host = ManagedHost(scheduler = controller.scheduler())
        private val source = SourceCell(consumerInt)
        private val gate = TrafficLightCell.create<Consumer<Int>>()
        private val incumbent = SummerV1(CellRef(UUID.randomUUID(), instanceId = 0))
        val view = CollectorCell()

        init {
            listOf(source, gate, incumbent, view).forEach { host.managementInlet.call.spawn(it) }
            controller.runToIdle()
            val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
            source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
            gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
            incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
            gate.controlInlet.call.setGreen()
        }

        fun emit(value: Int) {
            source.emit(value)
            controller.runToIdle()
        }
    }

    private fun connectGate(cell: SummingCell, invariant: InvariantCell<Long, Pair<Long, Long>>) {
        cell.outlet.subscribe(Use.fixed(object : Consumer<Long> {
            override fun provide(input: Long) {
                invariant.inlet.call.propagate(input)
            }
        }, PortRef.generate()))
    }

    private fun nonDecreasingGate(): InvariantCell<Long, Pair<Long, Long>> =
        InvariantCell(
            name = GATE_NAME,
            initial = 0L to 0L,
            fold = { (_, current), value -> current to value },
            check = { (previous, current), _ ->
                if (current < previous) "sum regressed: $current < $previous" else null
            },
        )

    private fun policy(
        gates: List<String> = listOf(GATE_NAME),
        baseline: Boolean = false,
        threshold: SatisfactionCriterion = SatisfactionCriterion.ZERO_VIOLATIONS,
    ): PromotionPolicy = PromotionPolicy(
        gates = gates,
        window = ObservationWindow(3),
        threshold = threshold,
        judge = "judge",
        baseline = baseline,
    )

    private companion object {
        const val GATE_NAME = "non-decreasing"
    }
}
