package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.Protocols
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.*

/**
 * KE4.3 nt17o-D2 (spec 20/22 §Local glitch-freedom `[22-GF-01]`/`[22-GF-02]`;
 * the mechanism B16 / `[KE4-42]` needs): one [WaveFrontier] spanning two inlets
 * of one cell through per-inlet [WaveFrontier.arm]s. One completeness fold over
 * the union of both inlets' edges; a ready wave's invocations are released
 * together, each to its own inlet, in arm-attach order.
 *
 * Payload-agnostic: a two-inlet cell whose handlers log `(inlet, value, wave)`;
 * waves are driven by calling an inlet under an explicit [MessageContext]
 * whose `sourcePort` is the real linked outlet, so each arrival's arm is chosen
 * by the test.
 */
class WaveFrontierMultiInletTest {

    data class Entry(val inlet: String, val value: Int, val wave: Timestamp)

    class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
    }

    class TwoInletCell(val log: MutableList<Entry>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val a = registerPort("a", FanInlet.create<Propagate<Int>>())
        val b = registerPort("b", FanInlet.create<Propagate<Int>>())

        init {
            a.serve(recorder("a"))
            b.serve(recorder("b"))
        }

        private fun recorder(name: String) = object : Propagate<Int> {
            override fun propagate(value: Int) {
                log += Entry(name, value, CurrentContext.get()!!.timestamp)
            }
        }
    }

    private class Rig(armOrder: List<String>) {
        val log = mutableListOf<Entry>()
        val cell = TwoInletCell(log)
        val src = Source()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val sourceId: UUID = UUID.randomUUID()

        init {
            // arms first, so each inlet's EdgeOpen reaches the shared fold
            armOrder.forEach { inlet(it).install(frontier.arm()) }
            link(src.outlet, cell.a)
            link(src.outlet, cell.b)
        }

        fun inlet(name: String): FanInlet<Propagate<Int>> = if (name == "a") cell.a else cell.b

        fun deliver(name: String, counter: Long, value: Int = counter.toInt()) =
            CurrentContext.with(MessageContext(Timestamp(sourceId, counter), src.outlet.ref)) {
                inlet(name).call.propagate(value)
            }

        fun linkInto(name: String): Link = src.outlet.linking.links.single { it.to == inlet(name).ref }
    }

    private companion object {
        fun link(outlet: FanOutlet<Propagate<Int>>, inlet: FanInlet<Propagate<Int>>) {
            @Suppress("UNCHECKED_CAST")
            (outlet.linkTo(inlet as LinkFrom<Propagate<Int>>) is LinkResult.Connected).shouldBeTrue()
        }
    }

    @Test
    fun `case 1 - a wave delivered on one arm is buffered until the other arm delivers it`() {
        val rig = Rig(listOf("a", "b"))

        rig.deliver("a", 1)
        rig.log.shouldBeEmpty()

        rig.deliver("b", 1)
        val wave = Timestamp(rig.sourceId, 1)
        rig.log shouldBe listOf(Entry("a", 1, wave), Entry("b", 1, wave))
    }

    @Test
    fun `case 2 - a wave releases in arm-attach order, and reversing the install order reverses it`() {
        // b's arm attached first: b releases first, although a delivered first
        val bFirst = Rig(listOf("b", "a"))
        bFirst.deliver("a", 1)
        bFirst.deliver("b", 1)
        bFirst.log.map { it.inlet } shouldBe listOf("b", "a")

        // control: a's arm attached first — same arrival order, released a then b
        val aFirst = Rig(listOf("a", "b"))
        aFirst.deliver("a", 1)
        aFirst.deliver("b", 1)
        aFirst.log.map { it.inlet } shouldBe listOf("a", "b")
    }

    @Test
    fun `case 3 - a Progress absorb-ack on the other arm completes the wave`() {
        val rig = Rig(listOf("b", "a"))

        rig.deliver("a", 2)
        rig.log.shouldBeEmpty()

        // b's upstream swallowed wave 2 and acked it on b's edge (AbsorbAck idiom)
        Protocols.sendDownstream(rig.linkInto("b"), Protocols.Progress, Progress(rig.sourceId, 2))

        rig.log shouldBe listOf(Entry("a", 2, Timestamp(rig.sourceId, 2)))
    }

    @Test
    fun `case 4 - a frontier installed directly on one inlet is the single-inlet fold`() {
        // Mirrors InletFrontierPolicyTest's diamond: two outlets into one inlet,
        // one wave (S, n) is released only once both arrived, in arrival order.
        val log = mutableListOf<Entry>()
        val cell = TwoInletCell(log)
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        cell.a.install(frontier)
        val left = Source()
        val right = Source()
        link(left.outlet, cell.a)
        link(right.outlet, cell.a)
        val s = UUID.randomUUID()

        for (n in 1..3) {
            CurrentContext.with(MessageContext(Timestamp(s, n.toLong()), right.outlet.ref)) { cell.a.call.propagate(10 * n) }
            log.size shouldBe 2 * (n - 1)
            CurrentContext.with(MessageContext(Timestamp(s, n.toLong()), left.outlet.ref)) { cell.a.call.propagate(n) }
            log.size shouldBe 2 * n
        }
        log.map { it.value } shouldBe listOf(10, 1, 20, 2, 30, 3)
        log.map { it.wave.counter } shouldBe listOf(1L, 1L, 2L, 2L, 3L, 3L)
        frontier.unmatchedDrops shouldBe 0L

        // the bare frontier cannot silently span a second inlet
        val error = shouldThrow<IllegalStateException> { cell.b.install(frontier) }
        error.message!! shouldContain "arm()"
    }

    @Test
    fun `case 5 - one outlet feeding both arms releases both with no unmatched drop`() {
        val rig = Rig(listOf("a", "b"))

        // a real origination: the one outlet fans wave (S', 1) to both inlets
        rig.src.outlet.originate { propagate(3) }

        rig.log.map { it.inlet to it.value } shouldBe listOf("a" to 3, "b" to 3)
        rig.log.map { it.wave }.toSet().size shouldBe 1
        rig.frontier.unmatchedDrops shouldBe 0L
    }

    @Test
    fun `case 6 - reset drops the buffered arm invocation and a later wave still releases`() {
        val rig = Rig(listOf("a", "b"))

        rig.deliver("a", 1)
        rig.log.shouldBeEmpty()

        // RESTART shape: the inlet drops its policies' transient buffers
        rig.cell.a.resetPolicies()
        rig.deliver("a", 2)
        rig.log.shouldBeEmpty()
        rig.deliver("b", 2)

        val wave2 = Timestamp(rig.sourceId, 2)
        rig.log shouldBe listOf(Entry("a", 2, wave2), Entry("b", 2, wave2))
    }
}
