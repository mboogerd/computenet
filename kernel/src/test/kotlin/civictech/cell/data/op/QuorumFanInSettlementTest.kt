package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.observe.AdmissionVerdict
import civictech.cell.observe.AlignedAdmissionException
import civictech.cell.observe.observeAligned
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random
import java.util.UUID

/** Regression for one source reaching a frontier through two lanes of one fan-in operator. */
class QuorumFanInSettlementTest {
    interface SetArmProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    @Suppress("UNCHECKED_CAST")
    private val setApi = Propagate::class.java as Class<Propagate<SetDelta<String>>>

    private class SetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    private class SetArm(
        clazz: Class<Propagate<SetDelta<String>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
        private val keep: (String) -> Boolean,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { delta ->
                val filtered = SetDelta(
                    adds = delta.adds.filterKeys(keep),
                    dels = delta.dels.filterKeys(keep),
                )
                if (filtered.adds.isEmpty() && filtered.dels.isEmpty()) {
                    outlet.absorbAck()
                } else {
                    outlet.call.propagate(filtered)
                }
            }
        }
    }

    private fun assertRejected(seed: Long, waves: Int) {
        val controller = SimulationController(seed)
        val sourceHost = ManagedHost(scheduler = controller.scheduler())
        val xHost = ManagedHost(scheduler = controller.scheduler())
        val mirrorHost = ManagedHost(scheduler = controller.scheduler())
        val sinkRegistry = LocationRegistry()
        val sinkHost = ManagedHost(registry = sinkRegistry, scheduler = controller.scheduler())

        val source = SetSource()
        val xOnly = SetArm(setApi) { it.startsWith("x") }
        val mirror = SetArm(setApi) { true }
        val quorum = QuorumSetCell.union<String>()

        sourceHost.managementInlet.call.spawn(source)
        xHost.managementInlet.call.spawn(xOnly)
        mirrorHost.managementInlet.call.spawn(mirror)
        sinkHost.managementInlet.call.spawn(quorum)

        source.outlet.subscribe(
            Use.fixed(xHost.lookup<SetArmProxy>(xOnly.ref)!!.inlet.call, PortRef.generate()),
        )
        source.outlet.subscribe(
            Use.fixed(mirrorHost.lookup<SetArmProxy>(mirror.ref)!!.inlet.call, PortRef.generate()),
        )
        @Suppress("UNCHECKED_CAST")
        xOnly.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        mirror.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        controller.runToIdle()

        val random = Random(seed)
        val tagSource = UUID.randomUUID()
        for (counter in 1..waves) {
            val prefix = if (counter % 2 == 0) "x" else "y"
            val element = "$prefix$counter"
            source.send(SetDelta(adds = mapOf(element to setOf(Timestamp(tagSource, counter.toLong())))))
            repeat(random.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        val refsBefore = sinkRegistry.localRefs().size
        val linksBefore = quorum.outlet.linking.links.size
        val error = assertThrows<AlignedAdmissionException> {
            sinkHost.observeAligned { set("quorum", quorum.ref) }
        }
        val verdict = error.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>()
        verdict.view shouldBe "quorum"
        verdict.cell shouldBe quorum.ref
        verdict.cellClass shouldBe QuorumSetCell::class.java
        verdict.outlet shouldBe quorum.outlet.ref
        error.message!! shouldContain "QuorumSetCell"
        error.message!! shouldContain "outlet"

        // Admission happens before the aligned sink is spawned or linked, so
        // no composite — and no premature Progress for one — can be published.
        sinkRegistry.localRefs().size shouldBe refsBefore
        quorum.outlet.linking.links.size shouldBe linksBefore
    }

    @Test
    fun `aligned observation rejects a two-lane quorum fan-in before publishing over 200 seeds`() {
        for (seed in 0L until 200L) {
            withClue("seed $seed") {
                assertRejected(seed, waves = 8)
            }
        }
    }
}
