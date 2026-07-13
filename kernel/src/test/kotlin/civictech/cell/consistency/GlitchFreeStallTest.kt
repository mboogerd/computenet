package civictech.cell.consistency

import civictech.cell.CellContext
import civictech.cell.CellError
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.attention.Progress
import civictech.cell.attention.StallNotice
import civictech.cell.attention.StallReason
import civictech.cell.data.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.port.Link
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * W2.7 (G-40): the explicit per-edge/per-source watermark and the typed
 * Stall(reason, recoverable) family extending M6.4's WAIT/DEGRADE machinery.
 * Spec 20/22 "Completeness over silent or stuck edges", 30/34 decision 3,
 * 30/31 rule 5 (dead-letter emits Stall(DEAD_LETTERED)).
 */
class GlitchFreeStallTest {
    companion object {
        private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    }

    private class Fixture(mode: GlitchFreeCell.WaveMode = GlitchFreeCell.WaveMode.WAIT) {
        val received = mutableListOf<Pair<Int, Timestamp>>()
        val errors = mutableListOf<CellError>()
        val gf = GlitchFreeCell(consumerInt, mode = mode)
        val a = FanOutlet(consumerInt)
        val b = FanOutlet(consumerInt)

        init {
            gf.outlet.subscribe(Use.fixed(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input to CurrentContext.get()!!.timestamp
                }
            }, PortRef.generate()))
            gf.errorOutlet.subscribe(Use.fixed(object : Propagate<CellError> {
                override fun propagate(value: CellError) { errors += value }
            }, PortRef.generate()))
        }

        fun link(outlet: FanOutlet<Consumer<Int>>): Link =
            (outlet.linkTo(gf.inlet as LinkFrom<Consumer<Int>>) as LinkResult.Connected).link

        fun send(outlet: FanOutlet<Consumer<Int>>, source: UUID, counter: Long, value: Int) {
            CurrentContext.with(MessageContext(Timestamp(source, counter), PortRef.generate())) {
                outlet.call.provide(value)
            }
        }
    }

    @Test
    fun `a later wave's watermark retroactively completes an earlier wave an edge silently absorbed`() {
        val fixture = Fixture()
        val source = UUID(40, 1)
        fixture.link(fixture.a)
        fixture.link(fixture.b)

        fixture.send(fixture.a, source, 1, 101) // waits on b
        fixture.received shouldBe emptyList()

        // b silently absorbs wave 1 (no delta) and next produces at wave 3 directly —
        // per-link FIFO means wave 1 on b is retired by the monotone watermark advance,
        // not stuck forever.
        fixture.send(fixture.b, source, 3, 303)
        fixture.send(fixture.a, source, 3, 103)

        fixture.received.map { it.first }.toSet() shouldBe setOf(101, 103, 303)
        fixture.received.map { it.second.counter } shouldBe listOf(1L, 3L, 3L)
    }

    @Test
    fun `a Progress absorb-ack advances the watermark and unblocks a stuck wave`() {
        val fixture = Fixture()
        val source = UUID(40, 2)
        fixture.link(fixture.a)
        val linkB = fixture.link(fixture.b)

        fixture.send(fixture.a, source, 5, 105)
        fixture.received shouldBe emptyList()

        ProtocolSupport.of(fixture.gf.inlet).deliver(Protocols.Progress, linkB, Progress(source, 5))

        fixture.received.map { it.first } shouldBe listOf(105)
    }

    @Test
    fun `a terminal Stall RE-SCOPEs only the poisoned wave, surfaces a GlitchViolation, and keeps the edge alive`() {
        val fixture = Fixture()
        val source = UUID(40, 3)
        fixture.link(fixture.a)
        val linkB = fixture.link(fixture.b)

        fixture.send(fixture.a, source, 1, 110)
        fixture.received shouldBe emptyList()

        ProtocolSupport.of(fixture.gf.inlet)
            .deliver(Protocols.Suspension, linkB, StallNotice.Stall(StallReason.DEAD_LETTERED, Timestamp(source, 1)))

        fixture.received.map { it.first } shouldBe listOf(110)
        fixture.errors.size shouldBe 1
        fixture.errors.single().cause.shouldBeInstanceOf<GlitchViolation>()

        // the edge is not closed: the next wave still needs both contributions
        fixture.send(fixture.a, source, 2, 111)
        fixture.received.map { it.first } shouldBe listOf(110)
        fixture.send(fixture.b, source, 2, 211)
        fixture.received.map { it.first } shouldBe listOf(110, 111, 211)
    }

    @Test
    fun `RESTART drops the transient buffer and re-enters by catch-up`() {
        val fixture = Fixture()
        val source = UUID(40, 4)
        fixture.link(fixture.a)
        fixture.link(fixture.b)

        fixture.send(fixture.a, source, 1, 1)
        fixture.received shouldBe emptyList()

        fixture.gf.onDeactivate(object : CellContext {})
        fixture.gf.onActivate(object : CellContext {})

        // the stranded wave never completes (RESTART is state-restore, never
        // input-replay: b will never re-produce its consumed input) ...
        fixture.send(fixture.b, source, 2, 22)
        fixture.send(fixture.a, source, 2, 21)

        // ... but the cell re-enters by catch-up: fresh waves complete normally
        fixture.received.map { it.first }.toSet() shouldBe setOf(21, 22)
        fixture.received.any { it.first == 1 } shouldBe false
    }
}
