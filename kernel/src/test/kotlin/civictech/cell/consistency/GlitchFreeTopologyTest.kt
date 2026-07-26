package civictech.cell.consistency

import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.port.FanOutlet
import civictech.cell.link.Link
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class GlitchFreeTopologyTest {
    companion object {
        private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    }

    private class Fixture {
        val received = mutableListOf<Pair<Int, Timestamp>>()
        val gf = GlitchFreeCell(consumerInt)
        val a = FanOutlet(consumerInt)
        val b = FanOutlet(consumerInt)

        init {
            gf.outlet.subscribe(Use.fixed(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input to CurrentContext.get()!!.timestamp
                }
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
    fun `seed 12 cross-source joins keep independent prospective floors`() {
        val fixture = Fixture()
        val sourceX = UUID(12, 1)
        val sourceY = UUID(12, 2)
        fixture.link(fixture.a)

        fixture.send(fixture.a, sourceX, 1, 101)
        fixture.send(fixture.a, sourceY, 1, 201)
        fixture.link(fixture.b) // floors both source lanes at counter 1

        fixture.send(fixture.b, sourceY, 1, 299) // lagging pre-join arrivals
        fixture.send(fixture.b, sourceX, 1, 199)
        fixture.send(fixture.a, sourceX, 2, 102)
        fixture.send(fixture.b, sourceX, 2, 112)
        fixture.send(fixture.a, sourceY, 2, 202)
        fixture.send(fixture.b, sourceY, 2, 212)

        fixture.received.map { it.first } shouldBe listOf(101, 201, 102, 112, 202, 212)
        fixture.received.map { it.second } shouldBe listOf(
            Timestamp(sourceX, 1), Timestamp(sourceY, 1),
            Timestamp(sourceX, 2), Timestamp(sourceX, 2),
            Timestamp(sourceY, 2), Timestamp(sourceY, 2),
        )
    }

    @Test
    fun `unlink during a wave closes its pending frontier and does not stall`() {
        val fixture = Fixture()
        val source = UUID(12, 3)
        fixture.link(fixture.a)
        val closing = fixture.link(fixture.b)

        fixture.send(fixture.a, source, 1, 10)
        fixture.received shouldBe emptyList()

        closing.unlink()

        fixture.received.map { it.first } shouldBe listOf(10)
        fixture.send(fixture.a, source, 2, 20)
        fixture.received.map { it.first } shouldBe listOf(10, 20)
    }
}
