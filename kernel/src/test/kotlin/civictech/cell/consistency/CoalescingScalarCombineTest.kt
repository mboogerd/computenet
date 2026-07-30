package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.CounterCell
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.op.CoalescingCombineCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * D-COMBINE (`24-OP-COMBINE-01` / `CTL-GF-01`): the **scalar** fork-join, in the
 * register of [GlitchFreeDiamondTest] — one counter source forked through two
 * identity arms into a summing combine, observed by a cell folding the running
 * sum, under seeded randomized scheduling with partial draining.
 *
 * Each source wave moves both arms by the same amount, so every observed folded
 * value must be **even**: an odd one is a state mixing one arm's post-wave input
 * with the other's pre-wave input, exactly what `[22-GF-01]` forbids.
 *
 * - invariant: [CoalescingCombineCell] emits one coalesced delta per completed
 *   wave — every observation even, one observation per wave, per-source monotone
 *   wave order;
 * - control: the same graph over a per-arm-emitting combine (the shape of the
 *   concord driver's `ScalarSumCombineCell`, replicated locally — kernel tests
 *   must not depend on `:concord`) tears, proving the harness sees the defect
 *   this cell removes;
 * - liveness: nothing stays buffered — including a wave one arm **absorbs**
 *   (emitting nothing, acking on the `Progress` lane), which still coalesces and
 *   releases; and a completed wave whose net is zero absorb-acks rather than
 *   stranding a downstream [GlitchFreeCell].
 */
class CoalescingScalarCombineTest {

    /** One observed emission: the delta, the observer's running fold, and the wave it rode. */
    data class Obs(val amount: Long, val running: Long, val timestamp: Timestamp)

    /** The catalog's `map, fn: identity` over a scalar stream: reactive pass-through, same wave. */
    class IdentityArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

        init {
            inlet.onEach { outlet.call.propagate(it) }
        }
    }

    /**
     * An arm that **absorbs** the waves [absorbs] selects (odd ones by default):
     * it emits nothing and acks on the metadata plane (CP-A3, spec 20/22
     * §Completeness over silent or stuck edges) exactly as an absorbing operator
     * does. The combine must still complete those waves from the other arm's
     * contribution alone.
     */
    class AbsorbingArm(
        private val absorbs: (Long) -> Boolean = { counter -> counter % 2 != 0L },
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

        init {
            inlet.onEach { delta ->
                val ctx = CurrentContext.get()!!
                if (!absorbs(ctx.timestamp.counter)) {
                    outlet.call.propagate(delta)
                } else {
                    outlet.linking.links.forEach {
                        Protocols.sendDownstream(
                            it,
                            Protocols.Progress,
                            Progress(ctx.timestamp.sourceId, ctx.timestamp.counter),
                        )
                    }
                }
            }
        }
    }

    /**
     * The control combine — the defect's shape, replicated from the concord
     * driver's `ScalarSumCombineCell` (per-arm running totals; every arrival
     * emits the change in the sum immediately). One source wave therefore
     * produces one emission **per arm**, and the intermediate sum between them
     * is observable.
     */
    class PerArmSumCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

        private val armTotals = mutableMapOf<PortRef, Long>()
        private var lastSum = 0L

        init {
            inlet.onEach { delta ->
                val arm = CurrentContext.get()!!.sourcePort
                armTotals.merge(arm, delta.amount, Long::plus)
                val sum = armTotals.values.sum()
                val diff = sum - lastSum
                lastSum = sum
                if (diff != 0L) outlet.call.propagate(CounterDelta(diff))
            }
        }
    }

    /** The `value-view` fold: a running scalar total, recording every value it ever exposes. */
    class SumObserver(
        private val observations: MutableList<Obs>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        private var running = 0L

        init {
            inlet.onEach {
                running += it.amount
                observations += Obs(it.amount, running, CurrentContext.get()!!.timestamp)
            }
        }
    }

    /** Always emits, on every wave — the sibling arm that makes a downstream frontier genuinely wait on two edges. */
    class AlwaysEmitArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

        init {
            inlet.onEach { outlet.call.propagate(CounterDelta(0)) }
        }
    }

    /** Negates every contribution — with an [IdentityArm] sibling it makes each wave's net zero. */
    class NegateArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

        init {
            inlet.onEach { outlet.call.propagate(CounterDelta(-it.amount)) }
        }
    }

    private interface CounterInlet {
        val inlet: Use<Propagate<CounterDelta>>
    }

    /** The combine under test, reduced to the three handles the graph wiring needs. */
    private class Combine(
        val cell: Cell,
        val inlet: FanInlet<Propagate<CounterDelta>>,
        val outlet: FanOutlet<Propagate<CounterDelta>>,
    )

    private fun combine(coalescing: Boolean): Combine =
        if (coalescing) CoalescingCombineCell().let { Combine(it, it.inlet, it.outlet) }
        else PerArmSumCell().let { Combine(it, it.inlet, it.outlet) }

    @Suppress("UNCHECKED_CAST")
    private fun linkArm(arm: FanOutlet<Propagate<CounterDelta>>, into: FanInlet<Propagate<CounterDelta>>) {
        (arm.linkTo(into as LinkFrom<Propagate<CounterDelta>>) is LinkResult.Connected).shouldBeTrue()
    }

    /**
     * `CTL-GF-01`'s graph: `n → (l, r) → s → v`. Both arms link into the
     * combine's fan-in inlet (a real handshake, so the edges announce
     * themselves) and — when [routeCombine] — deliver over the combine host's
     * queue, so the arms' contributions interleave across waves under the
     * seeded schedule.
     */
    private fun runDiamond(
        seed: Long,
        waves: Int,
        coalescing: Boolean,
        absorbingRightArm: Boolean = false,
        routeCombine: Boolean = true,
    ): List<Obs> {
        val controller = SimulationController(seed)
        val hostL = ManagedHost(scheduler = controller.scheduler())
        val hostR = ManagedHost(scheduler = controller.scheduler())
        val hostS = ManagedHost(scheduler = controller.scheduler())

        val n = CounterCell()
        val l = IdentityArm()
        val r: Cell = if (absorbingRightArm) AbsorbingArm() else IdentityArm()
        val rOutlet = if (r is AbsorbingArm) r.outlet else (r as IdentityArm).outlet
        val observations = mutableListOf<Obs>()
        val v = SumObserver(observations)
        val s = combine(coalescing)

        hostL.managementInlet.call.spawn(l)
        hostR.managementInlet.call.spawn(r)
        hostS.managementInlet.call.spawn(s.cell)

        // the source fans out to both arms through their host queues
        n.outlet.subscribe(Use.fixed(hostL.lookup<CounterInlet>(l.ref)!!.inlet.call, PortRef.generate()))
        n.outlet.subscribe(Use.fixed(hostR.lookup<CounterInlet>(r.ref)!!.inlet.call, PortRef.generate()))

        // both arms link into the combine's fan-in inlet (fires EdgeOpen)
        linkArm(l.outlet, s.inlet)
        linkArm(rOutlet, s.inlet)
        if (routeCombine) {
            // keep the links, queue the delivery over the combine's host
            val routed = hostS.lookup<CounterInlet>(s.cell.ref)!!.inlet.call
            l.outlet.unsubscribe(s.inlet.ref)
            rOutlet.unsubscribe(s.inlet.ref)
            l.outlet.subscribe(Use.fixed(routed, s.inlet.ref))
            rOutlet.subscribe(Use.fixed(routed, s.inlet.ref))
        }
        linkArm(s.outlet, v.inlet)
        controller.runToIdle()

        val rnd = Random(seed)
        for (wave in 1..waves) {
            n.inlet.call.increment(1)
            repeat(rnd.nextInt(4)) { controller.step() } // partial, seed-randomized draining
        }
        controller.runToIdle()
        return observations
    }

    @Test
    fun `the scalar diamond is glitch-free for every seed`() {
        val waves = 50
        for (seed in 0L until 200L) {
            val obs = runDiamond(seed, waves, coalescing = true)

            // exactly one coalesced delta per completed wave — never a per-arm intermediate
            obs.size shouldBe waves
            obs.forEach { it.amount shouldBe 2L }
            obs.forEach { (it.running % 2 == 0L).shouldBeTrue() } // the CTL-GF-01 invariant
            obs.last().running shouldBe 2L * waves

            // per-source monotone wave order, under the input waves' own identities
            obs.map { it.timestamp.sourceId }.toSet().size shouldBe 1
            obs.map { it.timestamp.counter } shouldBe (1L..waves).toList()
        }
    }

    @Test
    fun `control - the per-arm-emitting combine tears on at least one seed`() {
        var torn = 0
        for (seed in 0L until 200L) {
            val obs = runDiamond(seed, waves = 50, coalescing = false)
            if (obs.any { it.running % 2 != 0L }) torn++
        }
        // if this fails the harness cannot see the defect the coalescing cell removes
        (torn > 0).shouldBeTrue()
    }

    @Test
    fun `an absorbed arm still completes the wave, and nothing stays buffered`() {
        val waves = 40
        for (seed in 0L until 50L) {
            // the right arm absorbs every odd wave (Progress ack, no delta), so
            // completeness is reached with fewer deltas than open edges
            val obs = runDiamond(seed, waves, coalescing = true, absorbingRightArm = true, routeCombine = false)

            // every wave still coalesces into exactly one delta and releases
            obs.size shouldBe waves
            obs.map { it.timestamp.counter } shouldBe (1L..waves).toList()
            obs.forEach { it.amount shouldBe if (it.timestamp.counter % 2 == 0L) 2L else 1L }
            // liveness: the full expected total, no wave left permanently buffered
            obs.last().running shouldBe waves + waves / 2L
        }
    }

    /** A combine arm, reduced to the handles the graph wiring needs. */
    private class Arm(
        val cell: Cell,
        val inlet: FanInlet<Propagate<CounterDelta>>,
        val outlet: FanOutlet<Propagate<CounterDelta>>,
    )

    private fun identityArm() = IdentityArm().let { Arm(it, it.inlet, it.outlet) }

    private fun negateArm() = NegateArm().let { Arm(it, it.inlet, it.outlet) }

    private fun absorbEveryWaveArm() = AbsorbingArm({ true }).let { Arm(it, it.inlet, it.outlet) }

    /**
     * The swallowed-wave shape (OperatorAbsorbAckTest's): `n` feeds [left],
     * [right] and an always-real sibling; the combine over the two arms and the
     * sibling both fan into a downstream [GlitchFreeCell]. When the combine
     * swallows a wave without acking it, that join stalls on the combine's edge
     * forever and the sibling's delta never flushes — so what the observer sees
     * is exactly the CP-A3 evidence.
     */
    private fun runSwallowedWaves(left: Arm, right: Arm, waves: Int): List<Obs> {
        val controller = SimulationController(11)
        val host = ManagedHost(scheduler = controller.scheduler())

        val n = CounterCell()
        val sibling = AlwaysEmitArm()
        val s = CoalescingCombineCell()
        @Suppress("UNCHECKED_CAST")
        val gf = GlitchFreeCell(Propagate::class.java as Class<Propagate<CounterDelta>>)
        val observations = mutableListOf<Obs>()
        val v = SumObserver(observations)
        listOf(left.cell, right.cell, sibling, s, gf, v).forEach { host.managementInlet.call.spawn(it) }

        @Suppress("UNCHECKED_CAST")
        fun feed(arm: FanInlet<Propagate<CounterDelta>>) =
            n.outlet.linkTo(arm as LinkFrom<Propagate<CounterDelta>>)
        feed(left.inlet)
        feed(right.inlet)
        feed(sibling.inlet)
        linkArm(left.outlet, s.inlet)
        linkArm(right.outlet, s.inlet)
        @Suppress("UNCHECKED_CAST")
        val gfInlet = gf.inlet as LinkFrom<Propagate<CounterDelta>>
        (s.outlet.linkTo(gfInlet) is LinkResult.Connected).shouldBeTrue()
        (sibling.outlet.linkTo(gfInlet) is LinkResult.Connected).shouldBeTrue()
        gf.outlet.subscribe(Use.fixed(v.inlet.call, v.inlet.ref))
        controller.runToIdle()

        repeat(waves) {
            n.inlet.call.increment(1)
            controller.runToIdle()
        }
        return observations
    }

    /**
     * The effective-only half (CP-A3): a completed wave whose net is zero emits
     * nothing but absorb-acks, so a downstream [GlitchFreeCell] fanning in the
     * combine plus an always-real sibling arm still settles the wave instead of
     * stalling on the edge the combine swallowed.
     */
    @Test
    fun `a zero-net completed wave absorb-acks instead of stranding a downstream glitch-free join`() {
        // +1 and -1 for every wave: complete, and net zero
        val obs = runSwallowedWaves(identityArm(), negateArm(), waves = 2)

        // no torn or empty delta from the combine — only the sibling's markers,
        // released because the swallowed waves were acked
        obs.map { it.amount } shouldBe listOf(0L, 0L)
        obs.map { it.timestamp.counter } shouldBe listOf(1L, 2L)
    }

    /**
     * The same discipline one hop up: a wave **every** arm absorbs reaches this
     * cell only as [Progress] acks. It still completes, and is still acked
     * onward, so an absorbing chain does not strand the downstream join on its
     * final wave.
     */
    @Test
    fun `a wave every arm absorbs is completed and acked onward`() {
        val obs = runSwallowedWaves(absorbEveryWaveArm(), absorbEveryWaveArm(), waves = 2)

        obs.map { it.amount } shouldBe listOf(0L, 0L)
        obs.map { it.timestamp.counter } shouldBe listOf(1L, 2L)
    }
}
