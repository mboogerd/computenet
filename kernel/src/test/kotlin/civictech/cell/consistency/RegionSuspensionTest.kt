package civictech.cell.consistency

import civictech.cell.*
import civictech.cell.attention.AttentionSupport
import civictech.cell.attention.NonSuspendable
import civictech.cell.host.AttentionPolicy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.*
import civictech.cell.port.*
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Session delta 3 (spec 34 decision 3): on ONE host, the glitch-free region —
 * both diamond branches plus the join — is the atomic unit of suspension.
 * Either the whole region parks (no partial-diamond stall can exist) or,
 * when any member is still attended or [NonSuspendable], nothing parks and
 * the region keeps running. Cross-host branches stay WAIT/DEGRADE territory
 * (GlitchFreeSuspensionTest).
 */
class RegionSuspensionTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    class CollectorCell(
        clazz: Class<Consumer<Pair<String, Int>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val received = mutableListOf<Pair<String, Int>>()
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.serve(object : Consumer<Pair<String, Int>> {
                override fun provide(input: Pair<String, Int>) {
                    received += input
                }
            })
        }
    }

    /** MapperCell is final: a minimal open mapper so a [NonSuspendable] variant can exist. */
    open class TestMapper(
        private val f: (Int) -> Pair<String, Int>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet by input(@Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>))
        val outlet by output(@Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>))

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    outlet.use { provide(f(input)) }
                }
            })
        }
    }

    class NonSuspendableMapper(f: (Int) -> Pair<String, Int>) : TestMapper(f), NonSuspendable

    interface MapperProxy {
        val inlet: Use<Consumer<Int>>
    }

    private inner class Fixture(nonSuspendableBranch: Boolean = false) {
        val controller = SimulationController()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            attention = AttentionPolicy(suspendAfter = 3),
        )

        val a = SourceCell(consumerInt)
        val b: TestMapper =
            if (nonSuspendableBranch) NonSuspendableMapper(f = { "B" to it })
            else TestMapper(f = { "B" to it })
        val c = TestMapper(f = { "C" to it })
        val gf = GlitchFreeCell(consumerPair)
        val sink = CollectorCell(consumerPair)

        init {
            // the whole diamond is co-hosted: b, c, gf on the attention host
            host.managementInlet.call.spawn(b)
            host.managementInlet.call.spawn(c)
            host.managementInlet.call.spawn(gf)

            // source fans into both branches over the host queue
            a.outlet.subscribe(Use.fixed(host.lookup<MapperProxy>(b.ref)!!.inlet.call, PortRef.generate()))
            a.outlet.subscribe(Use.fixed(host.lookup<MapperProxy>(c.ref)!!.inlet.call, PortRef.generate()))
            // branches link the join directly (co-hosted: links carry cell refs for the region walk)
            (b.outlet.linkTo(gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
            (c.outlet.linkTo(gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
            gf.outlet.subscribe(Use.fixed(sink.inlet.call, sink.inlet.ref))

            controller.runToIdle()
        }
    }

    @Test
    fun `the whole region parks together and resumes together — no partial-diamond stall`() {
        val fixture = Fixture()
        // every member loses interest: the region may suspend as a unit
        listOf(fixture.b, fixture.c, fixture.gf).forEach { AttentionSupport.of(it).attend(0f) }

        (1..10).forEach { fixture.a.emit(it) }
        fixture.controller.runToIdle()

        // some prefix ran inside the window (dispatchStep is host-global),
        // then the REGION parked: whatever arrived at the sink arrived as
        // complete waves, never a stalled half — and something did park
        val flushed = fixture.sink.received.size
        (flushed % 2) shouldBe 0
        fixture.sink.received.chunked(2).forEach { it.map { p -> p.first }.toSet() shouldBe setOf("B", "C") }
        (flushed < 10 * 2).shouldBeTrue()

        // renewed interest at the join propagates upstream over the attention
        // protocol; every member's own band listener unparks it — region resume
        // is emergent, not orchestrated
        AttentionSupport.of(fixture.gf).attend(1f)
        fixture.controller.runToIdle()

        fixture.sink.received.size shouldBe 10 * 2 // zero loss
        fixture.sink.received.chunked(2).forEachIndexed { i, wave ->
            wave.map { it.first }.toSet() shouldBe setOf("B", "C")
            wave.map { it.second }.toSet() shouldBe setOf(i + 1)
        }
    }

    @Test
    fun `one NonSuspendable member vetoes suspension for the whole region`() {
        val fixture = Fixture(nonSuspendableBranch = true)
        listOf(fixture.b, fixture.c, fixture.gf).forEach { AttentionSupport.of(it).attend(0f) }

        (1..10).forEach { fixture.a.emit(it) }
        fixture.controller.runToIdle()

        // B is NonSuspendable: nothing in the region may park — every wave flows
        fixture.sink.received.size shouldBe 10 * 2
    }

    @Test
    fun `an attended join vetoes parking its starved branches`() {
        val fixture = Fixture()
        // only the branches lose interest; the join stays wanted
        listOf(fixture.b, fixture.c).forEach { AttentionSupport.of(it).attend(0f) }
        AttentionSupport.of(fixture.gf).attend(1f)

        (1..10).forEach { fixture.a.emit(it) }
        fixture.controller.runToIdle()

        // the attended member vetoes region suspension: no branch parks, no stall
        fixture.sink.received.size shouldBe 10 * 2
    }

    @Test
    fun `a cell with no downstream join still parks alone`() {
        val fixture = Fixture()
        // detach the join side: emit only into C with the GF unlinked from C? —
        // simpler: a fresh standalone mapper on the same host, starved
        val lone = MapperCell<Int, Pair<String, Int>>(f = { "L" to it })
        val loneSink = CollectorCell(consumerPair)
        fixture.host.managementInlet.call.spawn(lone)
        val loneSource = SourceCell(consumerInt)
        loneSource.outlet.subscribe(
            Use.fixed(fixture.host.lookup<MapperProxy>(lone.ref)!!.inlet.call, PortRef.generate())
        )
        lone.outlet.subscribe(Use.fixed(loneSink.inlet.call, loneSink.inlet.ref))
        fixture.controller.runToIdle()
        AttentionSupport.of(lone).attend(0f)

        (1..10).forEach { loneSource.emit(it) }
        fixture.controller.runToIdle()

        // per-cell parking preserved for region-free cells: a prefix ran, the rest parked
        loneSink.received.size shouldBeGreaterThan 0
        (loneSink.received.size < 10).shouldBeTrue()

        AttentionSupport.of(lone).attend(1f)
        fixture.controller.runToIdle()
        loneSink.received.size shouldBe 10 // park never drops
    }
}
