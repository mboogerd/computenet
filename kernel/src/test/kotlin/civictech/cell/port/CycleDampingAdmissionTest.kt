package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.Magnitude
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.gen.wire.MergeClass
import civictech.gen.wire.Monotonicity
import civictech.gen.wire.NatureVector
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * FU-8 — cycle admission must witness *damping*, not just *headedness* (ADR 1
 * feature 8, spec 21 §Cycles). A locally-visible cycle-closing edge landing on
 * a [FeedbackInlet] is admitted only when the loop carries a damping witness:
 * a [Magnitude]-typed payload (quiescence damper live), a producer declaring
 * [Monotonicity.MONOTONE] / [MergeClass.IDEMPOTENT] (fixpoint convergence), or
 * an explicit quiescence override on the head. None ⇒
 * `Rejected("CycleWithoutDamping: …")`, same family as `CycleWithoutHead`.
 */
class CycleDampingAdmissionTest {

    // ---- payloads: one per witness class ----

    /** Weak-tier damper payload (witness 1). */
    private data class Delta(val value: Double) : Magnitude {
        override fun size() = abs(value)
    }

    /** Idempotent (set-union) merge, NON-[Magnitude] (witness 2, IDEMPOTENT). */
    private data class GrowSet(val items: Set<Int>)

    /** A plain counter — NON-[Magnitude], non-idempotent, non-monotone (NO witness). */
    private data class Tick(val n: Int)

    // ---- cells ----

    private class MagnitudeHead(
        quiescence: Double,
        private val factor: Double,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, CycleHead<Delta> {
        val outlet by output<Consumer<Delta>>()
        val laps = mutableListOf<Double>()
        override val feedbackInput by feedbackInlet<Delta>(quiescence) { d ->
            laps += d.value
            outlet.call.provide(Delta(d.value * factor))
        }
    }

    private class DeltaRelay(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<Delta>>()
        val outlet by output<Consumer<Delta>>()
        override fun onActivate(ctx: CellContext) = inlet.serve(object : Consumer<Delta> {
            override fun provide(input: Delta) = outlet.call.provide(input)
        })
    }

    private class GrowSetHead(
        quiescence: Double = 0.0,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, CycleHead<GrowSet> {
        val outlet by output<Consumer<GrowSet>>()
        val laps = mutableListOf<Set<Int>>()
        private var seen = emptySet<Int>()
        override val feedbackInput by feedbackInlet<GrowSet>(quiescence) { g ->
            laps += g.items
            val merged = seen union g.items // idempotent fold — union reaches a fixpoint
            if (merged != seen) {           // effective-only: re-emit only on real growth
                seen = merged
                outlet.call.provide(GrowSet(merged))
            }
        }
    }

    private class GrowSetRelay(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<GrowSet>>()
        val outlet by output<Consumer<GrowSet>>()
        override fun onActivate(ctx: CellContext) = inlet.serve(object : Consumer<GrowSet> {
            override fun provide(input: GrowSet) = outlet.call.provide(input)
        })
    }

    /** A counter head with a lap cap so the runaway control is executable, not infinite. */
    private class TickHead(
        quiescence: Double = 0.0,
        private val step: Int = 1,
        private val lapCap: Int = LAP_BUDGET * 4,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, CycleHead<Tick> {
        val outlet by output<Consumer<Tick>>()
        val laps = mutableListOf<Int>()
        override val feedbackInput by feedbackInlet<Tick>(quiescence) { t ->
            laps += t.n
            if (laps.size < lapCap) outlet.call.provide(Tick(t.n + step)) // never quiesces
        }
    }

    private class TickRelay(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<Tick>>()
        val outlet by output<Consumer<Tick>>()
        override fun onActivate(ctx: CellContext) = inlet.serve(object : Consumer<Tick> {
            override fun provide(input: Tick) = outlet.call.provide(input)
        })
    }

    private fun newHost(): Pair<SimulationController, ManagedHost> {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry())
        return controller to host
    }

    // ---- (i) Magnitude-payload loop ⇒ admitted, quiesces ----

    @Test
    fun `a Magnitude-payload loop with quiescence is admitted and quiesces`() {
        val (controller, host) = newHost()
        val head = MagnitudeHead(quiescence = 0.01, factor = 0.4)
        val relay = DeltaRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()

        head.outlet.originate { provide(Delta(1.0)) }
        controller.runToIdle()

        head.laps.isNotEmpty() shouldBe true
        (head.laps.size < LAP_BUDGET) shouldBe true // quiesced far under the runaway budget
    }

    @Test
    fun `a Magnitude-payload loop is admitted even with zero quiescence (the payload-type witness alone)`() {
        // quiescence == 0.0 ⇒ witnesses 2 and 3 are both absent; only the
        // Magnitude payload-type witness can admit this (mirrors CycleHeadTest's
        // `admits a locally-visible cycle closing on a declared CycleHead`).
        val (_, host) = newHost()
        val head = MagnitudeHead(quiescence = 0.0, factor = 1.0)
        val relay = DeltaRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()
    }

    // ---- (ii) idempotent/monotone loop ⇒ admitted, reaches fixpoint ----

    @Test
    fun `an idempotent (non-Magnitude) loop is admitted and reaches a fixpoint`() {
        val (controller, host) = newHost()
        val head = GrowSetHead(quiescence = 0.0)
        val relay = GrowSetRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        // Producer of the closing edge declares an idempotent merge — the
        // fixpoint-convergence damping witness (no KSP on test cells, so stamp
        // the nature directly via the CP-F2 test seam).
        PortNatures.stamp(relay.outlet, NatureVector.of(MergeClass.IDEMPOTENT))
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()

        head.outlet.originate { provide(GrowSet(setOf(1, 2, 3))) }
        controller.runToIdle()

        head.laps.isNotEmpty() shouldBe true
        (head.laps.size < LAP_BUDGET) shouldBe true // union folds to a fixpoint
    }

    @Test
    fun `a monotone (non-Magnitude) producer loop is admitted`() {
        val (_, host) = newHost()
        val head = GrowSetHead(quiescence = 0.0)
        val relay = GrowSetRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        PortNatures.stamp(relay.outlet, NatureVector.of(Monotonicity.MONOTONE))
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()
    }

    // ---- (iii) plain counter loop ⇒ Rejected ----

    @Test
    fun `a plain counter loop (non-Magnitude, non-idempotent, no override) is rejected`() {
        val (_, host) = newHost()
        val head = TickHead(quiescence = 0.0, step = 1)
        val relay = TickRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        val rejected = closing.shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.reason shouldContain "CycleWithoutDamping"
        head.laps.isEmpty() shouldBe true // never wired ⇒ never ran
    }

    // ---- (iii-b) explicit quiescence override rescues the same counter loop ----

    @Test
    fun `an explicit quiescence override admits a loop that would otherwise be rejected`() {
        // Same non-Magnitude, non-idempotent counter shape as (iii), but the
        // head is constructed with an explicit quiescence > 0 ⇒ witness 3.
        val (_, host) = newHost()
        val head = TickHead(quiescence = 0.5, step = 1)
        val relay = TickRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()
    }

    // ---- Control (a): damping check OFF (admission bypassed) ⇒ the counter runs away ----

    @Test
    fun `control - the same counter loop wired past admission runs away beyond the lap budget`() {
        val (controller, host) = newHost()
        val head = TickHead(quiescence = 0.0, step = 1)
        val relay = TickRelay()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        // Bypass ManagedHost.connect's damping guard entirely by wiring the
        // ports directly: this is "damping check off". The lap cap makes the
        // runaway executable rather than infinite.
        head.outlet.linkTo(relay.inlet)
        relay.outlet.linkTo(head.feedbackInput)

        head.outlet.originate { provide(Tick(0)) }
        controller.runToIdle()

        // Divergence: guarded (case iii) never laps; unguarded blows the budget.
        (head.laps.size >= LAP_BUDGET) shouldBe true
    }

    // ---- Control (b): cases (i)/(ii) are byte-identical to today (still admitted, still terminate) ----

    @Test
    fun `control - damped cases stay admitted and terminate, unchanged from today`() {
        // (i)
        run {
            val (controller, host) = newHost()
            val head = MagnitudeHead(quiescence = 0.01, factor = 0.4)
            val relay = DeltaRelay()
            host.managementInlet.call.spawn(head)
            host.managementInlet.call.spawn(relay)
            host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")
            host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
                .shouldBeInstanceOf<LinkResult.Connected>()
            head.outlet.originate { provide(Delta(1.0)) }
            controller.runToIdle()
            (head.laps.size in 1 until LAP_BUDGET) shouldBe true
        }
        // (ii)
        run {
            val (controller, host) = newHost()
            val head = GrowSetHead()
            val relay = GrowSetRelay()
            host.managementInlet.call.spawn(head)
            host.managementInlet.call.spawn(relay)
            PortNatures.stamp(relay.outlet, NatureVector.of(MergeClass.IDEMPOTENT))
            host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")
            host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
                .shouldBeInstanceOf<LinkResult.Connected>()
            head.outlet.originate { provide(GrowSet(setOf(1, 2, 3))) }
            controller.runToIdle()
            (head.laps.size in 1 until LAP_BUDGET) shouldBe true
        }
    }

    // ---- 100 seeds: generative loop bodies ----

    @Test
    fun `100 seeds - damped loops admit and terminate, counter loops are refused`() {
        repeat(100) { seed ->
            val rng = Random(seed)

            // (i) Magnitude, strictly contracting factor in [0.1, 0.9)
            run {
                val (controller, host) = newHost()
                val factor = 0.1 + rng.nextDouble() * 0.8
                val head = MagnitudeHead(quiescence = 0.01, factor = factor)
                val relay = DeltaRelay()
                host.managementInlet.call.spawn(head)
                host.managementInlet.call.spawn(relay)
                host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")
                host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
                    .shouldBeInstanceOf<LinkResult.Connected>()
                head.outlet.originate { provide(Delta(1.0)) }
                controller.runToIdle()
                (head.laps.size in 1 until LAP_BUDGET) shouldBe true
            }

            // (ii) idempotent union of a random seed set
            run {
                val (controller, host) = newHost()
                val head = GrowSetHead()
                val relay = GrowSetRelay()
                host.managementInlet.call.spawn(head)
                host.managementInlet.call.spawn(relay)
                PortNatures.stamp(relay.outlet, NatureVector.of(MergeClass.IDEMPOTENT))
                host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")
                host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
                    .shouldBeInstanceOf<LinkResult.Connected>()
                val items = (0..rng.nextInt(1, 6)).map { rng.nextInt(0, 20) }.toSet()
                head.outlet.originate { provide(GrowSet(items)) }
                controller.runToIdle()
                (head.laps.size in 1 until LAP_BUDGET) shouldBe true
            }

            // (iii) plain counter with a random step ⇒ always refused
            run {
                val (_, host) = newHost()
                val head = TickHead(quiescence = 0.0, step = rng.nextInt(1, 9))
                val relay = TickRelay()
                host.managementInlet.call.spawn(head)
                host.managementInlet.call.spawn(relay)
                host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")
                val rejected = host.managementInlet.call
                    .connect(relay.ref, "outlet", head.ref, "feedbackInput")
                    .shouldBeInstanceOf<LinkResult.Rejected>()
                rejected.reason shouldContain "CycleWithoutDamping"
            }
        }
    }

    private companion object {
        /** A lap count no *damped* loop should ever reach; the runaway blows past it. */
        const val LAP_BUDGET = 100
    }
}
