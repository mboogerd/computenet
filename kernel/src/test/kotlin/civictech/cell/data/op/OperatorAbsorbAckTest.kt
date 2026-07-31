package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.MapDiffPublisher
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T05 finding 2: [IntersectSetCell], [QuorumSetCell], and [CountCell]
 * previously dropped a membership/count-neutral reactive wave without an
 * absorb-ack ([civictech.cell.control.absorbAck], CP-A3) — a
 * [GlitchFreeCell] downstream of the operator, fan-in with an always-real
 * sibling arm, would stall forever on such a wave (the frontier never
 * completes for the edge that silently dropped it). This suite drives each
 * operator through exactly that fan-in shape and confirms the wave now
 * settles. **Behavior change: all three now ack.**
 *
 * E2-GATE extends the same register to the two **value-equal-swallow** cells,
 * 96 §E2.2's genuine residual (20/22 §Completeness over silent or stuck edges
 * flagged `CombineLatestCell` as the one divergence from its MUST;
 * [LookupJoinCell] carried the identical [MapDiffPublisher] shape, unflagged
 * and equally bound). A recompute that leaves every touched key's value
 * unchanged returns a null delta from the publisher, which pre-fix rode
 * `?.let { propagate }` into silence — [AckLessMapArm] below is that shape
 * verbatim, and the control test proves this harness detects the stall it
 * causes. **Behavior change: both now ack.**
 */
class OperatorAbsorbAckTest {

    /** A bare, hand-fed [Propagate] source — full control over the delta content and tags. */
    private class RawSetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    /**
     * Reactive pass-through that ALWAYS emits a fresh, non-empty delta for
     * every incoming wave — the "always-real" sibling arm of the fan-in, so
     * the join genuinely has to wait on TWO edges rather than trivially
     * completing an already-empty wave.
     */
    private class AlwaysEmitSet(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<String>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        private var n = 0
        init {
            inlet.onEach {
                outlet.call.propagate(SetDelta(adds = mapOf("marker-${n++}" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
            }
        }
    }

    private class AlwaysEmitCounter(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<String>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())
        init {
            inlet.onEach { outlet.call.propagate(CounterDelta(0)) }
        }
    }

    private class Observer<T>(clazz: Class<Propagate<T>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val received = mutableListOf<T>()
        init { inlet.onEach { received += it } }
    }

    /** A bare, hand-fed `MapDelta` source — the keyed-stream twin of [RawSetSource]. */
    private class RawMapSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Int>>>())
        fun send(delta: MapDelta<String, Int>) = outlet.call.propagate(delta)
    }

    /** The always-real sibling arm for the keyed diamonds. */
    private class AlwaysEmitMap(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, Int>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Int>>>())
        private var n = 0
        init {
            inlet.onEach { outlet.call.propagate(MapDelta(puts = mapOf("marker-${n++}" to 1), removals = emptySet())) }
        }
    }

    /**
     * The **pre-fix** value-equal-swallow shape, verbatim:
     * `publisher.publish(touched, ::recompute)?.let { outlet.call.propagate(it) }`
     * — no `absorbAck` on the null branch. This is the control's teeth: with an
     * arm of this shape in the diamond, the downstream join never settles the
     * swallowed wave, which is exactly the stall the two real cells no longer
     * cause.
     */
    private class AckLessMapArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, Int>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<String, Int>>>())
        private val latest = mutableMapOf<String, Int>()
        private val publisher = MapDiffPublisher<String, Int>()
        init {
            inlet.onEach { delta ->
                delta.puts.forEach { (k, v) -> latest[k] = v }
                delta.removals.forEach { latest.remove(it) }
                publisher.publish(delta.puts.keys + delta.removals) { k -> latest[k]?.let { minOf(it, SATURATE) } }
                    ?.let { outlet.call.propagate(it) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val setApi = Propagate::class.java as Class<Propagate<SetDelta<String>>>
    @Suppress("UNCHECKED_CAST")
    private val counterApi = Propagate::class.java as Class<Propagate<CounterDelta>>
    @Suppress("UNCHECKED_CAST")
    private val mapApi = Propagate::class.java as Class<Propagate<MapDelta<String, Int>>>

    @Test
    fun `CountCell absorb-acks a size-neutral wave so a downstream glitch-free join settles it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source = RawSetSource()
        val opArm = CountCell<String>()
        val passArm = AlwaysEmitCounter()
        val gf = GlitchFreeCell(counterApi)
        val observer = Observer(counterApi)
        listOf(source, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        source.outlet.linkTo(opArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        source.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<CounterDelta>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<CounterDelta>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        val src = UUID.randomUUID()
        source.send(SetDelta(adds = mapOf("a" to setOf(Timestamp(src, 1L)))))
        controller.runToIdle()
        observer.received shouldBe listOf(CounterDelta(1), CounterDelta(0)) // opArm's real +1, passArm's marker

        // final wave: net size change is zero (one add, one del) — CountCell
        // absorbs; the always-real sibling still emits. Pre-fix this edge's
        // absorbed wave would never complete and the pass arm's delta would
        // never flush either.
        source.send(
            SetDelta(
                adds = mapOf("b" to setOf(Timestamp(src, 2L))),
                dels = mapOf("a" to setOf(Timestamp(src, 1L))),
            ),
        )
        controller.runToIdle()

        observer.received shouldBe listOf(CounterDelta(1), CounterDelta(0), CounterDelta(0))
    }

    @Test
    fun `IntersectSetCell absorb-acks a membership-neutral wave so a downstream glitch-free join settles it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val left = RawSetSource()
        val right = RawSetSource()
        val opArm = IntersectSetCell<String>()
        val passArm = AlwaysEmitSet()
        val gf = GlitchFreeCell(setApi)
        val observer = Observer(setApi)
        listOf(left, right, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        left.outlet.linkTo(opArm.left as LinkFrom<Propagate<SetDelta<String>>>)
        right.outlet.linkTo(opArm.right as LinkFrom<Propagate<SetDelta<String>>>)
        left.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        // right always has "a" — intersection tracks left's "a" membership exactly
        right.send(SetDelta(adds = mapOf("a" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()

        val srcId = UUID.randomUUID()
        left.send(SetDelta(adds = mapOf("a" to setOf(Timestamp(srcId, 1L)))))
        controller.runToIdle()
        observer.received.size shouldBe 2 // opArm's real intersection add, passArm's marker

        // final wave: "z" enters LEFT's own tracked state but right never has
        // it — intersection membership never changes, so IntersectSetCell
        // absorbs; the always-real sibling still emits. Pre-fix ("ack
        // divergence, owner decision pending" TODO) this wave was dropped
        // silently and the join stalled on this edge forever.
        left.send(SetDelta(adds = mapOf("z" to setOf(Timestamp(srcId, 2L)))))
        controller.runToIdle()

        observer.received.size shouldBe 3
        observer.received.last() shouldBe SetDelta(adds = mapOf("marker-1" to observer.received.last().adds.getValue("marker-1")))
    }

    @Test
    fun `QuorumSetCell absorb-acks a threshold-neutral wave so a downstream glitch-free join settles it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source1 = RawSetSource()
        val source2 = RawSetSource()
        val opArm = QuorumSetCell.union<String>() // threshold = 1: any single live source suffices
        val passArm = AlwaysEmitSet()
        val gf = GlitchFreeCell(setApi)
        val observer = Observer(setApi)
        listOf(source1, source2, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        source1.outlet.linkTo(opArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        source2.outlet.linkTo(opArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        // passArm mirrors BOTH sources too — every wave, from either source,
        // must reach every one of gf.inlet's linked edges, or that edge's
        // frontier for a wave it structurally can never see would stall
        // regardless of this fix.
        source1.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        source2.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        source1.send(SetDelta(adds = mapOf("a" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()
        observer.received.size shouldBe 2 // opArm's real quorum add ("a" meets threshold 1), passArm's marker

        // final wave: source2 also asserts "a" — a real presence-lane change
        // (count 1 -> 2), but the quorum verdict (meets >= 1) doesn't flip,
        // so QuorumSetCell absorbs; the always-real sibling still emits.
        // Pre-fix this wave was dropped silently and the join stalled on
        // this edge forever.
        source2.send(SetDelta(adds = mapOf("a" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()

        observer.received.size shouldBe 3
    }

    // ---------------------------------------------------------------- E2-GATE
    // The two value-equal-swallow cells (96 §E2.2's residual). `combine`
    // saturates at [SATURATE], so a genuinely different input wave recomputes
    // to the *same* R and the publisher has nothing to emit — the swallow the
    // MUST binds.

    @Test
    fun `CombineLatestCell absorb-acks a value-equal wave so a downstream glitch-free join settles it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source = RawMapSource()
        val opArm = CombineLatestCell<String, Int, Int, Int>(combine = { _, v, w -> minOf((v ?: 0) + (w ?: 0), SATURATE) })
        val passArm = AlwaysEmitMap()
        val gf = GlitchFreeCell(mapApi)
        val observer = Observer(mapApi)
        listOf(source, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        source.outlet.linkTo(opArm.left as LinkFrom<Propagate<MapDelta<String, Int>>>)
        source.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        source.send(MapDelta(puts = mapOf("a" to 6), removals = emptySet()))
        controller.runToIdle()
        observer.received.size shouldBe 2 // opArm's real combine (6 saturated to 5), passArm's marker
        observer.received.first() shouldBe MapDelta(puts = mapOf("a" to SATURATE), removals = emptySet())

        // final wave: a genuinely different left value that saturates to the
        // SAME combined R — the value-equal swallow. Pre-fix this edge's
        // absorbed wave never completed and the pass arm's delta never flushed.
        source.send(MapDelta(puts = mapOf("a" to 7), removals = emptySet()))
        controller.runToIdle()

        observer.received.size shouldBe 3
        observer.received.last() shouldBe MapDelta(puts = mapOf("marker-1" to 1), removals = emptySet())
    }

    @Test
    fun `LookupJoinCell absorb-acks a value-equal wave so a downstream glitch-free join settles it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val factSrc = RawMapSource()
        val dimSrc = RawMapSource()
        // `combine` ignores the dimension value, so a dimension delta touching a
        // live fact recomputes to the same R — the fan-out-then-swallow wave.
        val opArm = LookupJoinCell<String, Int, String, Int, Int>(fk = { it }, combine = { _, v, _ -> v })
        val passArm = AlwaysEmitMap()
        val gf = GlitchFreeCell(mapApi)
        val observer = Observer(mapApi)
        listOf(factSrc, dimSrc, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        factSrc.outlet.linkTo(opArm.fact as LinkFrom<Propagate<MapDelta<String, Int>>>)
        dimSrc.outlet.linkTo(opArm.dimension as LinkFrom<Propagate<MapDelta<String, Int>>>)
        // the pass arm mirrors BOTH sources: every wave, from either, must reach
        // every one of gf.inlet's edges (the QuorumSetCell case's reasoning).
        factSrc.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        dimSrc.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        factSrc.send(MapDelta(puts = mapOf("a" to 1), removals = emptySet()))
        controller.runToIdle()
        observer.received.size shouldBe 2 // opArm's enriched fact, passArm's marker
        observer.received.first() shouldBe MapDelta(puts = mapOf("a" to 1), removals = emptySet())

        // final wave: a dimension row the fact references, whose value `combine`
        // ignores — the reverse index fans out to fact "a", the recompute is
        // value-equal, and LookupJoinCell absorbs the wave.
        dimSrc.send(MapDelta(puts = mapOf("a" to 99), removals = emptySet()))
        controller.runToIdle()

        observer.received.size shouldBe 3
        observer.received.last() shouldBe MapDelta(puts = mapOf("marker-1" to 1), removals = emptySet())
    }

    @Test
    fun `control - an ack-less value-equal swallow strands the downstream join on the final wave`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source = RawMapSource()
        val opArm = AckLessMapArm() // the pre-fix shape
        val passArm = AlwaysEmitMap()
        val gf = GlitchFreeCell(mapApi)
        val observer = Observer(mapApi)
        listOf(source, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        source.outlet.linkTo(opArm.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        source.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<MapDelta<String, Int>>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        source.send(MapDelta(puts = mapOf("a" to 6), removals = emptySet()))
        controller.runToIdle()
        observer.received.size shouldBe 2

        source.send(MapDelta(puts = mapOf("a" to 7), removals = emptySet()))
        controller.runToIdle()

        // if the harness had no teeth this would be 3, as it is for the two
        // fixed cells above: the ack-less arm strands the wave forever.
        observer.received.size shouldBe 2
    }

    private companion object {
        /** The saturation point that makes a different input recompute to an equal output. */
        const val SATURATE = 5
    }
}
