package civictech.cell.wire

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** ServiceLoader discovery of [PeerTransportProvider]s (gyvli-D1). */
class PeerTransportsTest {

    @Test
    fun `the loopback scheme resolves to a LoopbackPeerTransport through ServiceLoader`() {
        PeerTransports.providers()["loopback"].shouldBeInstanceOf<LoopbackPeerTransportProvider>()
        val transport = PeerTransports.forScheme("loopback")
        transport.shouldBeInstanceOf<LoopbackPeerTransport>()
        transport.scheme shouldBe "loopback"
    }

    @Test
    fun `an unknown scheme fails naming the schemes found`() {
        val e = assertThrows<IllegalArgumentException> { PeerTransports.forScheme("carrier-pigeon") }
        e.message.orEmpty() shouldContain "'carrier-pigeon'"
        e.message.orEmpty() shouldContain "schemes found: [loopback"
    }

    @Test
    fun `a loopback transport refuses a foreign scheme's address`() {
        val transport = LoopbackPeerTransport()
        assertThrows<IllegalArgumentException> { transport.parseAddress("ws://localhost:1") }
        transport.parseAddress("loopback://x").text shouldBe "loopback://x"
    }
}
