package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.handshake
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * FU-6: an opt-in single-writer (strict point-to-point) input port. A [FanInlet]
 * constructed with `singleWriter = true` refuses a second Consume producer,
 * mirroring [FeedbackInlet]'s point-to-point refusal — while Observe taps (read
 * cardinality) stay unrestricted. Default `singleWriter = false` leaves every
 * existing inlet byte-identical (unconditional fan-in).
 */
class SingleWriterInletTest {

    private fun inlet(singleWriter: Boolean): FanInlet<Consumer<String>> =
        FanInlet.create(singleWriter = singleWriter)

    @Test
    fun `single-writer inlet admits the first Consume producer and refuses a second`() {
        val port = inlet(singleWriter = true)

        val first = port.linkFrom(FanOutlet.create<Consumer<String>>())
        first.shouldBeInstanceOf<LinkResult.Connected>()

        val second = port.linkFrom(FanOutlet.create<Consumer<String>>())
        second.shouldBeInstanceOf<LinkResult.Rejected>()
    }

    @Test
    fun `single-writer inlet still admits an Observe tap alongside its producer`() {
        val port = inlet(singleWriter = true)

        // A negotiated tap (Observe) registers on the inlet — read cardinality
        // is unrestricted, so it does not count as a producer.
        val tap = handshake(
            portOut = FanOutlet.create<Consumer<String>>(),
            target = port,
            targetRef = port.ref,
            role = LinkRole.Observe,
            install = {},
            uninstall = {},
        )
        tap.shouldBeInstanceOf<LinkResult.Connected>()

        // With only an Observe link present, a Consume producer is still admitted.
        port.linkFrom(FanOutlet.create<Consumer<String>>())
            .shouldBeInstanceOf<LinkResult.Connected>()

        // A further tap remains admitted even now that a producer exists.
        val secondTap = handshake(
            portOut = FanOutlet.create<Consumer<String>>(),
            target = port,
            targetRef = port.ref,
            role = LinkRole.Observe,
            install = {},
            uninstall = {},
        )
        secondTap.shouldBeInstanceOf<LinkResult.Connected>()

        // But a second producer is refused — the single-writer slot is taken.
        port.linkFrom(FanOutlet.create<Consumer<String>>())
            .shouldBeInstanceOf<LinkResult.Rejected>()
    }

    @Test
    fun `single-writer slot frees after unlink, admitting a replacement writer`() {
        val port = inlet(singleWriter = true)

        val first = port.linkFrom(FanOutlet.create<Consumer<String>>())
        val link = first.shouldBeInstanceOf<LinkResult.Connected>().link

        port.linkFrom(FanOutlet.create<Consumer<String>>())
            .shouldBeInstanceOf<LinkResult.Rejected>()

        link.unlink()

        port.linkFrom(FanOutlet.create<Consumer<String>>())
            .shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `control - guard off default inlet admits two producers whose writes interleave`() {
        val (served, buffer) = Consumer.buffering<String>()
        val port = inlet(singleWriter = false)
        port.serve(served)

        val a = FanOutlet.create<Consumer<String>>()
        val b = FanOutlet.create<Consumer<String>>()

        port.linkFrom(a).shouldBeInstanceOf<LinkResult.Connected>()
        port.linkFrom(b).shouldBeInstanceOf<LinkResult.Connected>()

        a.call.provide("a1")
        b.call.provide("b1")
        a.call.provide("a2")

        buffer shouldBe listOf("a1", "b1", "a2")
    }
}
