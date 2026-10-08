package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.evolve.StateMigrating
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.CellFactory
import civictech.cell.graph.Direction
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * WKB2-52/53 policy-window integration. The asynchronous cases retain a
 * [SimulationController]: the applying thread blocks only on the policy
 * condition while the test thread emits and deterministically steps the host.
 */
class PromotionObservationWindowTest {
    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    private class SourceCell(
        clazz: Class<Consumer<Int>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(value: Int) = outlet.call.provide(value)
    }

    private class SummerV1(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
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

    private class SummerV2(override val ref: CellRef) : Cell, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.provide(sum)
                }
            })
        }

        override fun importFrom(prior: Serializable) {
            sum = prior as Long
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

    private inner class Fixture(seed: Long) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, instanceId = 0))
        val collector = CollectorCell()
        lateinit var candidate: SummerV2
            private set
        private var now = 1_000L

        init {
            listOf(source, gate, incumbent, collector).forEach(host.managementInlet.call::spawn)
            controller.runToIdle()
            connect(source.ref, "outlet", gate.ref, "dataInlet")
            connect(gate.ref, "dataOutlet", incumbent.ref, "inlet")
            connect(incumbent.ref, "outlet", collector.ref, "inlet")
            gate.controlInlet.call.setGreen()
        }

        fun applier() = StagedApplier(
            hosts = mapOf(HOST to host),
            registry = registry,
            clock = { now++ },
        )

        fun policy(
            waves: Int,
            gates: List<String> = emptyList(),
            baseline: Boolean = false,
        ) = PromotionPolicy(gates, ObservationWindow(waves), judge = "workbench", baseline = baseline)

        fun draft(policy: PromotionPolicy) = Draft(
            host = HOST,
            spec = GraphSpec(
                listOf(
                    SpawnStep(
                        CANDIDATE,
                        CellFactory { ref -> SummerV2(ref).also { candidate = it } },
                        identity = IdentityBinding.NewInstanceOf(logicalId),
                    ),
                ),
            ),
            boundary = listOf(
                BoundaryLink(gate.ref, "dataOutlet", CANDIDATE, "inlet", Direction.INBOUND),
            ),
            promotions = listOf(
                PromotionRequest(
                    incumbent = incumbent.ref,
                    gate = gate.ref,
                    candidateHandle = CANDIDATE,
                    policy = policy,
                ),
            ),
        )

        fun emit(vararg values: Int) {
            values.forEach {
                source.emit(it)
                controller.runToIdle()
            }
        }

        fun emitFromDetachedCandidate(value: Int) {
            candidate.inlet.call.provide(value)
            controller.runToIdle()
        }

        private fun connect(from: CellRef, outlet: String, to: CellRef, inlet: String) {
            host.managementInlet.call.connect(from, outlet, to, inlet).shouldBeInstanceOf<LinkResult.Connected>()
        }
    }

    private data class RunningApply(
        val thread: Thread,
        val result: AtomicReference<ApplyRecord?>,
        val failure: AtomicReference<Throwable?>,
    ) {
        fun awaitResult(): ApplyRecord {
            thread.join(5_000)
            thread.isAlive shouldBe false
            failure.get()?.let { throw AssertionError("apply thread failed", it) }
            return result.get().shouldNotBeNull()
        }
    }

    @Test
    fun `WKB2-52 a policy with a filled window is accepted and the swap commits`() {
        val f = Fixture(seed = 52)
        val applier = f.applier()
        applier.beforePromotion = { f.emit(1, 2, 3) }
        val running = startApply(applier, f.draft(f.policy(waves = 3)), "filled")
        try {
            awaitUntil("the pre-filled promotion either commits or incorrectly awaits") {
                applier.record("filled")?.let { it.outcome != null || it.awaiting != null } == true
            }
            if (applier.record("filled")?.awaiting != null) applier.abort("filled")
            val record = running.awaitResult()
            f.emit(4)

            record.outcome shouldBe ApplyOutcome.Committed
            record.promotions.single().status shouldBe PromotionRecord.Status.COMMITTED
            f.collector.received shouldContainExactly listOf(1L, 3L, 6L, 10L)
        } finally {
            stopIfRunning(applier, "filled", running)
        }
    }

    @Test
    fun `WKB2-52 gates or a baseline are refused POLICY_DENIAL at plan time`() {
        val f = Fixture(seed = 53)
        val applier = f.applier()

        listOf(
            f.draft(f.policy(waves = 1, gates = listOf("invariant"))),
            f.draft(f.policy(waves = 1, baseline = true)),
        ).forEach { draft ->
            val refusal = applier.plan(draft).steps.single { it.action == "PROMOTE" }.refusal.shouldNotBeNull()
            refusal.code shouldBe "POLICY_DENIAL"
            refusal.reason shouldContain "no InvariantCell gates and no baseline twin"
        }
    }

    @Test
    fun `WKB2-53 an unfilled window is awaiting and fills from live waves`() {
        val f = Fixture(seed = 54)
        val applier = f.applier()
        val attempts = AtomicInteger()
        val secondAttemptStarted = CountDownLatch(1)
        val allowSecondAttempt = CountDownLatch(1)
        applier.beforePromotionAttempt = {
            if (attempts.incrementAndGet() == 2) {
                secondAttemptStarted.countDown()
                allowSecondAttempt.await()
            }
        }
        val running = startApply(applier, f.draft(f.policy(waves = 2)), "await-live")
        try {
            val firstAwaiting = awaitAwaiting(applier, "await-live")
            firstAwaiting.outcome.shouldBeNull()

            f.emit(1)
            awaitUntil("the first wave was classified before another primitive attempt") {
                val current = applier.record("await-live")
                (current !== firstAwaiting && current?.awaiting == OBSERVATION_WINDOW) ||
                    secondAttemptStarted.count == 0L
            }
            attempts.get() shouldBe 1

            f.emit(2)
            awaitUntil("the accepted window reached its second primitive attempt") {
                secondAttemptStarted.count == 0L
            }
            allowSecondAttempt.countDown()
            awaitUntil("the two-wave window completed") { applier.record("await-live")?.outcome != null }

            val record = running.awaitResult()
            record.outcome shouldBe ApplyOutcome.Committed
            record.awaiting.shouldBeNull()
            record.promotions.single().status shouldBe PromotionRecord.Status.COMMITTED
            attempts.get() shouldBe 2
            f.collector.received shouldContainExactly listOf(1L, 3L)
        } finally {
            allowSecondAttempt.countDown()
            if (running.thread.isAlive) f.emit(2)
            stopIfRunning(applier, "await-live", running)
        }
    }

    @Test
    fun `the policy observation tap is removed after commit and abort`() {
        val committed = Fixture(seed = 56)
        val committedApplier = committed.applier()
        val committedWaves = AtomicInteger()
        committedApplier.onPromotionWaveObserved = { committedWaves.incrementAndGet() }
        val committing = startApply(
            committedApplier,
            committed.draft(committed.policy(waves = 1)),
            "tap-commit",
        )
        try {
            awaitAwaiting(committedApplier, "tap-commit")
            committed.emit(1)
            committing.awaitResult().outcome shouldBe ApplyOutcome.Committed
            committedWaves.get() shouldBe 1

            committed.emit(2)
            committedWaves.get() shouldBe 1
        } finally {
            stopIfRunning(committedApplier, "tap-commit", committing)
        }

        val aborted = Fixture(seed = 57)
        val abortedApplier = aborted.applier()
        val abortedWaves = AtomicInteger()
        abortedApplier.onPromotionWaveObserved = { abortedWaves.incrementAndGet() }
        val aborting = startApply(
            abortedApplier,
            aborted.draft(aborted.policy(waves = 2)),
            "tap-abort",
        )
        try {
            awaitAwaiting(abortedApplier, "tap-abort")
            abortedApplier.abort("tap-abort") shouldBe true
            aborting.awaitResult().outcome shouldBe ApplyOutcome.UnwoundClean
            abortedWaves.get() shouldBe 0

            aborted.emitFromDetachedCandidate(1)
            abortedWaves.get() shouldBe 0
        } finally {
            stopIfRunning(abortedApplier, "tap-abort", aborting)
        }
    }

    @Test
    fun `WKB2-53 abort while awaiting unwinds the staged set`() {
        val f = Fixture(seed = 55)
        val applier = f.applier()
        val running = startApply(applier, f.draft(f.policy(waves = 2)), "abort-awaiting")
        try {
            awaitAwaiting(applier, "abort-awaiting")
            applier.abort("abort-awaiting") shouldBe true
            awaitUntil("the aborted apply became terminal") { applier.record("abort-awaiting")?.outcome != null }

            val record = running.awaitResult()
            record.outcome shouldBe ApplyOutcome.UnwoundClean
            record.awaiting.shouldBeNull()
            record.promotions.single().status shouldBe PromotionRecord.Status.ABORTED
            applier.abort("abort-awaiting") shouldBe false

            f.emit(4)
            f.collector.received shouldContainExactly listOf(4L)
        } finally {
            stopIfRunning(applier, "abort-awaiting", running)
        }
    }

    private fun startApply(applier: StagedApplier, draft: Draft, id: String): RunningApply {
        val result = AtomicReference<ApplyRecord?>()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                result.set(applier.apply(draft, id, "operator", 1))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, "promotion-window-$id")
        thread.start()
        return RunningApply(thread, result, failure)
    }

    private fun awaitAwaiting(applier: StagedApplier, id: String): ApplyRecord {
        awaitUntil("apply $id awaits its observation window") {
            applier.record(id)?.let {
                it.phase == ApplyPhase.CUT_OVER &&
                    it.awaiting == OBSERVATION_WINDOW &&
                    it.promotions.single().status == PromotionRecord.Status.AWAITING_OBSERVATION_WINDOW &&
                    it.outcome == null
            } == true
        }
        return applier.record(id).shouldNotBeNull()
    }

    private fun stopIfRunning(applier: StagedApplier, id: String, running: RunningApply) {
        if (running.thread.isAlive) {
            applier.abort(id)
            running.thread.join(1_000)
        }
    }

    private companion object {
        const val HOST = "h"
        const val CANDIDATE = "v2"
        const val OBSERVATION_WINDOW = "observation-window"
    }
}
