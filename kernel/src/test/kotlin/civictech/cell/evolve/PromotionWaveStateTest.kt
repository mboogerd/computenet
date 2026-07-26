package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.Propagate
import civictech.cell.data.UnionSetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import civictech.cell.data.delta.SetDelta

/**
 * W2.1 (G-42 + G-43): a promotion whose state transfer carries the outlet's
 * `(sourceId, highWater)` inside the buffered swap window (spec 20/22
 * §Source identity, 93 I-11/I-27 default) is a **preserved-epoch**
 * continuation — invisible to downstream completeness, no `ReBaseline`, the
 * same source lane continues monotonically. This is the "promotion adoption
 * keeps the glitch-free frontier invariant" half of the ticket: the
 * candidate's outlet exactly inherits the incumbent's emission epoch, so a
 * downstream convergent consumer (the tag fold, [UnionSetCell]) sees an
 * unbroken, non-duplicated, non-retracted source — the physical-source
 * quantification the frontier relies on (spec 20/22 Rule S5) never observes
 * the swap.
 */
class PromotionWaveStateTest {

    /** Mints OR-set tags from its outlet's current emission epoch (spec 20/24 §Tag continuity). */
    class TaggedProducerCell(override val ref: CellRef) : Cell, Stateful, StateMigrating {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<String>>))

        private val adds = mutableMapOf<String, MutableSet<Timestamp>>()
        private var counter = 0L

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    // a local add is a fresh origination point (spec 20/22 Rule
                    // S4 generalized): mint under this outlet's own epoch even
                    // when driven reactively from behind a gate
                    outlet.originate {
                        val tag = Timestamp(outlet.waveState().sourceId, ++counter)
                        adds.getOrPut(input) { mutableSetOf() } += tag
                        propagate(SetDelta(adds = mapOf(input to setOf(tag))))
                    }
                }
            })
        }

        override fun snapshot(): Serializable = HashMap(adds.mapValues { HashSet(it.value) })

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            adds.clear()
            (state as Map<String, Set<Timestamp>>).forEach { (e, tags) -> adds[e] = tags.toMutableSet() }
        }

        @Suppress("UNCHECKED_CAST")
        override fun importFrom(prior: Serializable) {
            (prior as Map<String, Set<Timestamp>>).forEach { (e, tags) -> adds.getOrPut(e) { mutableSetOf() } += tags }
        }
    }

    interface GateProxy {
        val dataInlet: Use<Consumer<String>>
    }

    @Test
    fun `promotion adoption preserves the outlet's emission epoch and keeps convergence unbroken`() {
        val controller = SimulationController(seed = 3)
        val host = ManagedHost(scheduler = controller.scheduler())

        val logicalId = UUID.randomUUID()
        val gate = TrafficLightCell.create<Consumer<String>>()
        val incumbent = TaggedProducerCell(CellRef(logicalId, instanceId = 0))
        val candidate = TaggedProducerCell(CellRef(logicalId, instanceId = 1))
        val union = UnionSetCell<String>()

        listOf(gate, incumbent, union).forEach { host.managementInlet.call.spawn(it) }
        host.managementInlet.call.spawn(candidate)

        val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
        gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<String>>)
        incumbent.outlet.subscribe(union.inlet as Use<Propagate<SetDelta<String>>>)
        gate.controlInlet.call.setGreen()

        val received = mutableListOf<SetDelta<String>>()
        union.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                received += value
            }
        }, PortRef.generate()))

        routedGate.provide("alpha")
        routedGate.provide("beta")
        controller.runToIdle()

        val preSourceId = incumbent.outlet.waveState().sourceId
        val preHighWater = incumbent.outlet.waveState().highWater
        preHighWater shouldBe 2L

        Promotion.promote(
            host, gate, incumbent, candidate, "outlet",
            downstream = listOf(union.inlet),
        )
        // Promotion.promote only relinks the outlet (downstream) side; the
        // upstream input rewiring is the caller's concern (normally done via
        // a live shadow feed pre-promotion, spec 52) — done here explicitly
        // since this test has no shadow subgraph.
        gate.dataOutlet.unsubscribe(incumbent.inlet.ref)
        gate.dataOutlet.subscribe(candidate.inlet as Use<Consumer<String>>)

        // preserved-epoch adoption (93 I-11/I-27): the candidate's outlet
        // exactly inherits the incumbent's (sourceId, highWater) — no fresh
        // epoch, so the frontier's per-source bookkeeping is untouched
        candidate.outlet.waveState().sourceId shouldBe preSourceId
        candidate.outlet.waveState().highWater shouldBe preHighWater

        routedGate.provide("gamma")
        controller.runToIdle()

        // the source lane continued monotonically: counter 3 under the SAME
        // sourceId as pre-promotion — no aliasing, no gap
        candidate.outlet.waveState().sourceId shouldBe preSourceId
        candidate.outlet.waveState().highWater shouldBe 3L

        // no retraction/re-baseline noise: every element observed, none dropped
        val membership = mutableMapOf<String, MutableSet<Timestamp>>()
        received.forEach { delta ->
            delta.adds.forEach { (e, tags) -> membership.getOrPut(e) { mutableSetOf() } += tags }
            delta.dels.forEach { (e, tags) -> membership[e]?.let { it -= tags; if (it.isEmpty()) membership.remove(e) } }
        }
        membership.keys shouldBe setOf("alpha", "beta", "gamma")
        // every tag rides the one preserved source lane — the physical-source
        // quantification the glitch-free frontier relies on (spec 20/22 Rule S5)
        membership.values.flatten().map { it.sourceId }.toSet() shouldBe setOf(preSourceId)
    }
}
