package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.control.Magnitude
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
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

    private interface GateProxy {
        val dataInlet: Use<Consumer<Int>>
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

    private inner class Run(
        seed: Long,
        incumbentFactory: (CellRef) -> SummingCell = ::SummerV1,
        candidateFactory: (CellRef) -> SummingCell = ::SummerV2,
        baselineFactory: ((CellRef) -> SummingCell)? = null,
        val policy: PromotionPolicy = policy(baseline = baselineFactory != null),
    ) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = incumbentFactory(CellRef(logicalId, instanceId = 0))
        val candidate = candidateFactory(CellRef(logicalId, instanceId = 1))
        val baselineTwin = baselineFactory?.invoke(CellRef(logicalId, instanceId = 2))
        val candidateGate = nonDecreasingGate()
        val baselineGate = baselineTwin?.let { nonDecreasingGate() }
        val view = CollectorCell()

        init {
            listOfNotNull<Cell>(source, gate, incumbent, view, candidateGate, baselineGate).forEach {
                host.managementInlet.call.spawn(it)
            }
            controller.runToIdle()

            val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
            source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
            gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
            incumbent.outlet.subscribe(view.inlet as Use<Consumer<Long>>)
            connectGate(candidate, candidateGate)
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

        fun idle() {
            controller.runToIdle()
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
