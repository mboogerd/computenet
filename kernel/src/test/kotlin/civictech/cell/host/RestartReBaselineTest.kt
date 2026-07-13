package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.data.Propagate
import civictech.cell.data.SetDelta
import civictech.cell.data.UnionSetCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * W2.1 (G-42 + G-43, fixes C-12): a producer's outlet mints a fresh emission
 * epoch on every RESTART (spec 20/22 §Source identity, 93 I-14 Rule S1) and
 * announces the supersession via a `ReBaseline` over the ordinary catch-up
 * path (93 I-22 R2/R4) — never the landed silent same-`sourceId` rollback
 * (C-12). A downstream convergent consumer ([UnionSetCell], the tag fold)
 * drops the un-reasserted pre-crash tags and fences the dead source id
 * (93 I-22 R5), converging to the re-baselined (reverted) state.
 */
class RestartReBaselineTest {

    interface ProducerProxy {
        val inlet: Use<Consumer<String>>
    }

    /**
     * Mints its own OR-set tags from its outlet's *current emission epoch*
     * (spec 20/24 §Tag continuity: "a genuinely new local add mints its tag
     * under the cell's current source epoch") — unlike [civictech.cell.data.SetCell]'s
     * deliberately replay-stable tag source, this test producer exercises the
     * epoch-scoped tag minting the RESTART re-baseline depends on.
     */
    class TaggedProducerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) :
        Cell, Stateful, ReBaselineEmitting {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<String>>))

        private val adds = mutableMapOf<String, MutableSet<Timestamp>>()
        private var counter = 0L

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    if (input == "poison") throw IllegalStateException("poison: $input")
                    // a local add is a fresh origination point (spec 20/22
                    // Rule S4 generalized): mint under this outlet's own epoch
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

        /** RESTART re-baseline (93 I-22 R2): re-emit restored state, flagged over the ordinary catch-up path. */
        override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
            val delta = SetDelta(adds = adds.mapValues { it.value.toSet() })
            outlet.reBaseline(supersedes, supersede) { propagate(delta) }
        }
    }

    private class Fixture {
        val controller = SimulationController(seed = 7)
        val host = ManagedHost(scheduler = controller.scheduler())
        val producer = TaggedProducerCell()
        val union = UnionSetCell<String>()
        val received = mutableListOf<SetDelta<String>>()
        val api: Consumer<String>

        init {
            host.managementInlet.call.spawn(producer)
            host.managementInlet.call.spawn(union)
            producer.outlet.subscribe(union.inlet as Use<Propagate<SetDelta<String>>>)
            union.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    received += value
                }
            }, PortRef.generate()))
            host.managementInlet.call.supervise(producer.ref, SupervisionPolicy.RESTART)
            api = (HostedCellProxy.create(producer.ref, host, ProducerProxy::class.java) as ProducerProxy).inlet.call
        }

        /** Reconstructs the downstream union's live membership from every delta it forwarded. */
        fun downstreamMembership(): Set<String> {
            val live = mutableMapOf<String, MutableSet<Timestamp>>()
            received.forEach { delta ->
                delta.adds.forEach { (e, tags) -> live.getOrPut(e) { mutableSetOf() } += tags }
                delta.dels.forEach { (e, tags) ->
                    live[e]?.let { it -= tags; if (it.isEmpty()) live.remove(e) }
                }
            }
            return live.keys
        }
    }

    @Test
    fun `RESTART mid-stream mints a fresh epoch, no tag or wave aliasing, downstream converges to the re-baselined state`() {
        val f = Fixture()

        f.api.provide("alpha")
        f.api.provide("beta")
        f.controller.runToIdle()
        f.downstreamMembership() shouldBe setOf("alpha", "beta")

        val preRestartSourceId = f.producer.outlet.waveState().sourceId

        // crash mid-stream: RESTART reverts the producer to its spawn-time
        // (empty) checkpoint — the non-durable degenerate case of R3
        f.api.provide("poison")
        f.controller.runToIdle()

        f.host.supervisionAccounting().restarts shouldBe 1
        f.host.generationOf(f.producer.ref) shouldBe 1L

        val postRestartSourceId = f.producer.outlet.waveState().sourceId
        (postRestartSourceId == preRestartSourceId) shouldBe false // fresh epoch (93 I-14 Rule S1)

        // the ReBaseline retracted the un-reasserted pre-crash tags — the
        // downstream convergent consumer (UnionSetCell) reverted to empty
        f.downstreamMembership() shouldBe emptySet()

        // post-restart traffic mints tags under the fresh epoch; counters
        // restart from 1 under the new sourceId — no aliasing with the
        // pre-crash (preRestartSourceId, 1) tag despite the reused counter value
        f.api.provide("gamma")
        f.controller.runToIdle()
        f.downstreamMembership() shouldBe setOf("gamma")

        // a late-arriving straggler stamped with the dead pre-crash sourceId
        // is fenced (93 I-22 R5c) — it must never resurrect "alpha"
        val staleTag = Timestamp(preRestartSourceId, 99L)
        f.union.inlet.call.propagate(SetDelta(adds = mapOf("alpha" to setOf(staleTag))))
        f.controller.runToIdle()
        f.downstreamMembership() shouldBe setOf("gamma")
    }
}
