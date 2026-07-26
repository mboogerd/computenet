package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.control.Magnitude
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.CycleHead
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.feedbackInlet
import civictech.cell.port.output
import civictech.cell.port.registerPort
import civictech.cell.Propagate
import civictech.cell.verify.InvariantCell
import civictech.cell.verify.Violation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import civictech.cell.Consumer

/**
 * W4.4 (G-50): PromotionPolicy as a serializable artifact evaluated by a
 * [PromotionJudge] — spec 53 "Judgment is declarative policy" (93 I-17).
 * Replaces the imperative hand-checked judgment (e.g.
 * `violations.shouldBeEmpty()` before calling [Promotion.promote] in
 * [ShadowPromotionTest]) with a declarative policy + judge that
 * [Promotion.promote] itself consults during PRECHECK.
 */
class PromotionPolicyTest {

    // ---- 1. A PromotionPolicy artifact drives accept/reject via the judge ----

    @Test
    fun `judge is pending until the observation window is filled, then accepts on zero violations`() {
        val policy = PromotionPolicy(
            gates = listOf("candidate sums are non-decreasing"),
            window = ObservationWindow(waves = 3),
            judge = "sum-judge",
        )
        val judge = PromotionJudge(policy)

        judge.verdict() shouldBe PromotionVerdict.Pending
        judge.observeCandidateWave()
        judge.observeCandidateWave()
        judge.verdict() shouldBe PromotionVerdict.Pending // window not yet filled

        judge.observeCandidateWave()
        judge.verdict() shouldBe PromotionVerdict.Accept // window filled, zero violations
    }

    @Test
    fun `judge rejects once a gate violation is observed within the window`() {
        val policy = PromotionPolicy(
            gates = listOf("candidate sums are non-decreasing"),
            window = ObservationWindow(waves = 3),
            judge = "sum-judge",
        )
        val judge = PromotionJudge(policy)
        repeat(3) { judge.observeCandidateWave() }
        judge.observeCandidateViolation(Violation("candidate sums are non-decreasing", "sum regressed: 1 < 2", 1))

        val verdict = judge.verdict()
        verdict.shouldBeRejectContaining("violat")
    }

    @Test
    fun `an InvariantCell's violations stream wired into the judge drives a reject verdict`() {
        val violations = mutableListOf<Violation>()
        // S = (previous value, current value): InvariantCell folds THEN checks
        // against the post-fold state, so tracking a one-step lag needs a pair.
        val invariant = InvariantCell<Long, Pair<Long, Long>>(
            "candidate sums are non-decreasing", 0L to 0L,
            fold = { (_, cur), value -> cur to value },
            check = { (prev, cur), _ -> if (cur < prev) "sum regressed: $cur < $prev" else null },
        )
        val policy = PromotionPolicy(
            gates = listOf(invariant.name),
            window = ObservationWindow(waves = 2),
            judge = "sum-judge",
        )
        val judge = PromotionJudge(policy)
        invariant.violations.subscribe(Use.fixed(
            object : Propagate<Violation> {
                override fun propagate(value: Violation) {
                    violations += value
                    judge.observeCandidateViolation(value)
                }
            },
            PortRef.generate(),
        ))

        invariant.inlet.call.propagate(1L)
        judge.observeCandidateWave()
        invariant.inlet.call.propagate(0L) // regression -> violation
        judge.observeCandidateWave()

        violations.size shouldBe 1
        judge.verdict().shouldBeRejectContaining("violat")
    }

    // ---- 2. Differential shadow: no-worse-than comparison ----

    @Test
    fun `differential shadow rejects a candidate that is worse than the incumbent baseline`() {
        val policy = PromotionPolicy(
            gates = listOf("g"),
            window = ObservationWindow(waves = 1),
            threshold = SatisfactionCriterion { it <= 2 }, // candidate alone would satisfy this
            judge = "j",
            baseline = true,
        )
        val judge = PromotionJudge(policy)
        judge.observeCandidateWave()
        judge.observeIncumbentViolation(Violation("g", "incumbent hiccup", null)) // baseline: 1 violation
        judge.observeCandidateViolation(Violation("g", "candidate hiccup 1", null))
        judge.observeCandidateViolation(Violation("g", "candidate hiccup 2", null)) // candidate: 2 violations

        judge.verdict().shouldBeRejectContaining("worse than")
    }

    @Test
    fun `differential shadow accepts a candidate that is no worse than the incumbent baseline`() {
        val policy = PromotionPolicy(
            gates = listOf("g"),
            window = ObservationWindow(waves = 1),
            threshold = SatisfactionCriterion { it <= 1 },
            judge = "j",
            baseline = true,
        )
        val judge = PromotionJudge(policy)
        judge.observeCandidateWave()
        judge.observeIncumbentViolation(Violation("g", "incumbent hiccup 1", null))
        judge.observeIncumbentViolation(Violation("g", "incumbent hiccup 2", null)) // baseline: 2
        judge.observeCandidateViolation(Violation("g", "candidate hiccup", null)) // candidate: 1 <= 2

        judge.verdict() shouldBe PromotionVerdict.Accept
    }

    // ---- 3. Cycle promotion gates on quiescence ----

    private data class Delta(val value: Double) : Magnitude {
        override fun size() = abs(value)
    }

    private class HeadCell(
        quiescence: Double,
        private val factor: Double,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, CycleHead<Delta> {
        val outlet by output<Consumer<Delta>>()
        override val feedbackInput by feedbackInlet<Delta>(quiescence) { delta ->
            outlet.call.provide(Delta(delta.value * factor))
        }
    }

    @Test
    fun `a cyclic candidate's promotion is deferred until no delta has been observed yet`() {
        val policy = PromotionPolicy(gates = emptyList(), window = ObservationWindow(waves = 1), judge = "j")
        val head = HeadCell(quiescence = 0.01, factor = 0.4)
        val judge = PromotionJudge(policy, cycleHead = head)
        judge.observeCandidateWave()

        // no lap observed yet: G-19 throttling has not confirmed quiescence,
        // so per spec 53 promotion is deferred, not attempted
        judge.verdict().shouldBeRejectContaining("quiescen")
    }

    @Test
    fun `a cyclic candidate gates on quiescence and accepts only once the cycle has damped below threshold`() {
        val policy = PromotionPolicy(gates = emptyList(), window = ObservationWindow(waves = 1), judge = "j")
        val head = HeadCell(quiescence = 0.01, factor = 0.4)
        val judge = PromotionJudge(policy, cycleHead = head)
        judge.observeCandidateWave()

        head.feedbackInput.call.provide(Delta(1.0)) // 1.0 > 0.01: still diverging
        judge.verdict().shouldBeRejectContaining("quiescen")

        head.feedbackInput.call.provide(Delta(0.005)) // <= 0.01: quiescent
        judge.verdict() shouldBe PromotionVerdict.Accept
    }

    private fun PromotionVerdict.shouldBeRejectContaining(fragment: String) {
        val reject = this as? PromotionVerdict.Reject ?: error("expected Reject, got $this")
        (reject.reason.contains(fragment, ignoreCase = true)) shouldBe true
    }

    // ---- Integration: Promotion.promote consults the judge during PRECHECK ----

    @Test
    fun `Promotion promote aborts at PRECHECK when the judge has not accepted`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())

        val logicalId = UUID.randomUUID()
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = PlainSummer(CellRef(logicalId, instanceId = 0))
        val candidate = PlainSummer(CellRef(logicalId, instanceId = 1))

        listOf(gate, incumbent).forEach { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.spawn(candidate)
        gate.controlInlet.call.setGreen()

        val policy = PromotionPolicy(gates = listOf("g"), window = ObservationWindow(waves = 5), judge = "j")
        val judge = PromotionJudge(policy) // no waves observed yet: Pending

        val aborted = shouldThrow<Promotion.PromotionAborted> {
            Promotion.promote(
                host, gate, incumbent, candidate, "outlet",
                downstream = emptyList(),
                judge = judge,
            )
        }
        aborted.message!!.contains("PRECHECK", ignoreCase = true) shouldBe true
    }

    @Test
    fun `Promotion promote proceeds when the judge accepts`() {
        val controller = SimulationController(seed = 12)
        val host = ManagedHost(scheduler = controller.scheduler())

        val logicalId = UUID.randomUUID()
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = PlainSummer(CellRef(logicalId, instanceId = 0))
        val candidate = PlainSummer(CellRef(logicalId, instanceId = 1))

        listOf(gate, incumbent).forEach { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.spawn(candidate)
        gate.controlInlet.call.setGreen()

        val policy = PromotionPolicy(gates = listOf("g"), window = ObservationWindow(waves = 1), judge = "j")
        val judge = PromotionJudge(policy)
        judge.observeCandidateWave() // window filled, zero violations -> Accept

        Promotion.promote(
            host, gate, incumbent, candidate, "outlet",
            downstream = emptyList(),
            judge = judge,
        )
        // no exception: promotion proceeded past PRECHECK
    }

    private class PlainSummer(override val ref: CellRef) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Long>>())
    }
}
