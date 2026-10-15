package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Owned
import civictech.cell.evolve.Effectful
import civictech.cell.host.ActorIngress
import civictech.cell.host.DurableInput
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.Recovery
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Proxy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Ownership across journal recovery (`[22-REC-01]`, `[24-DUR-05]`, z88w1-D9).
 *
 * A journal frame serializes an [Owned] without consuming the live wrapper. Recovery decodes
 * a fresh wrapper for the recovered process: an ordinary inlet takes that owner once, while an
 * `Effectful` inlet at its restored processed frontier suppresses the replay and discharges it.
 * This is PN-2 input replay, which reconstructs a fresh process incarnation. It is deliberately
 * distinct from supervision RESTART (93 I-22 R6), which restores state and never replays inputs.
 */
class ExclusiveReplayOwnershipTest {

    private interface SinkProxy {
        val inlet: Use<Consumer<Owned<String>>>
    }

    private open class OwnedSink(
        override val ref: CellRef,
        private val taken: MutableList<String>,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Owned<String>>>())

        init {
            inlet.serve(object : Consumer<Owned<String>> {
                override fun provide(input: Owned<String>) {
                    taken += input.take()
                }
            })
        }
    }

    private class EffectfulOwnedSink(
        ref: CellRef,
        taken: MutableList<String>,
    ) : OwnedSink(ref, taken), Effectful

    private class World(
        val journal: Journal,
        seed: Long,
        effectful: Boolean = false,
    ) {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val taken = mutableListOf<String>()
        private val cell = if (effectful) {
            EffectfulOwnedSink(SINK_REF, taken)
        } else {
            OwnedSink(SINK_REF, taken)
        }
        private val proxy: SinkProxy
        private val input: DurableInput

        init {
            host.managementInlet.call.spawn(cell)
            proxy = HostedCellProxy.create(SINK_REF, host, SinkProxy::class.java) as SinkProxy
            input = host.durableInput(SINK_REF, INPUT_NAME)
        }

        fun commit(payloads: List<Owned<String>>, ingress: ActorIngress? = null) {
            input.commit {
                payloads.forEach { payload ->
                    if (ingress == null) {
                        proxy.inlet.call.provide(payload)
                    } else {
                        ingress.drive { proxy.inlet.call.provide(payload) }
                    }
                }
                payloads.size
            }
        }

        fun recover(): Recovery {
            val recovery = host.recoverFrom(journal)
            controller.runToIdle()
            recovery.awaitApplied()
            return recovery
        }
    }

    @Test
    fun `a committed but undelivered Owned batch is taken once by the recovered consumer`() {
        val journal = InMemoryJournal()
        val original = ITEMS.map(::Owned)
        val dischargesBefore = Proxy.discharges
        val doubleDischargesBefore = Proxy.doubleDischarges
        val live = World(journal, seed = 1)

        live.commit(original)
        // Crash before a scheduler step: the input record is durable, but the live wrappers
        // are still owned by the dead incarnation and none reached its consumer.
        live.taken.shouldBeEmpty()

        val recovered = World(journal, seed = 2)
        recovered.recover().replayedFrames shouldBe ITEMS.size

        recovered.taken shouldBe ITEMS
        Proxy.discharges - dischargesBefore shouldBe 0L
        Proxy.doubleDischarges - doubleDischargesBefore shouldBe 0L
        // Journal encoding did not move the sender's wrappers. They belong to the dead
        // incarnation and remain independently consumable by this test.
        original.map { it.take() } shouldBe ITEMS
    }

    @Test
    fun `a batch delivered before the crash is re-minted and taken once by the recovered consumer`() {
        val journal = InMemoryJournal()
        val original = ITEMS.map(::Owned)
        val dischargesBefore = Proxy.discharges
        val doubleDischargesBefore = Proxy.doubleDischarges
        val live = World(journal, seed = 3)

        live.commit(original)
        live.controller.runToIdle()
        live.taken shouldBe ITEMS

        val recovered = World(journal, seed = 4)
        recovered.recover().replayedFrames shouldBe ITEMS.size

        // The equal values are not a second owner in one process: decode minted fresh
        // wrappers after the old incarnation (and its already-consumed wrappers) died.
        recovered.taken shouldBe ITEMS
        Proxy.discharges - dischargesBefore shouldBe 0L
        Proxy.doubleDischarges - doubleDischargesBefore shouldBe 0L
    }

    @Test
    fun `an Owned replay behind an Effectful frontier is suppressed and discharged`() {
        val journal = InMemoryJournal()
        val original = ITEMS.map(::Owned)
        val live = World(journal, seed = 5, effectful = true)
        val ingress = ActorIngress(ACTOR_ID)

        live.commit(original, ingress)
        live.controller.runToIdle()
        live.taken shouldBe ITEMS
        live.host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe 0L

        val dischargesBefore = Proxy.discharges
        val doubleDischargesBefore = Proxy.doubleDischarges
        val recovered = World(journal, seed = 6, effectful = true)
        val recovery = recovered.recover()

        recovery.replayedFrames shouldBe ITEMS.size
        recovered.taken.shouldBeEmpty()
        Proxy.discharges - dischargesBefore shouldBe ITEMS.size.toLong()
        Proxy.doubleDischarges - doubleDischargesBefore shouldBe 0L
        recovered.host.supervisionAccounting().effectfulSuppressionsDischarged shouldBe ITEMS.size.toLong()
    }

    private companion object {
        val ITEMS = listOf("item-1", "item-2", "item-3")
        val SINK_REF = CellRef(UUID.nameUUIDFromBytes("exclusive-replay-sink".encodeToByteArray()))
        val ACTOR_ID: UUID = UUID.nameUUIDFromBytes("exclusive-replay-actor".encodeToByteArray())
        const val INPUT_NAME = "exclusive-items"
    }
}
