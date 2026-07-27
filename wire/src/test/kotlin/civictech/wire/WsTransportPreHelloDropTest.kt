package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * T05 finding 7: a binary frame arriving before an admitted hello is
 * correctly refused — [WsTransport.Session] has nowhere to route it yet —
 * but was previously dropped with no signal at all. Now counted via
 * [WsTransport.Session.preHelloDrops]; the drop behavior itself is
 * unchanged (still refused, never queued as if a hello had happened).
 */
class WsTransportPreHelloDropTest {

    @Test
    fun `a binary frame before hello is dropped and counted, not delivered`() {
        val side = Peering.Side(LocationRegistry(), ManagedHost())
        val sent = mutableListOf<ByteArray>()
        val session = WsTransport.Session(side, send = { sent += it }, refuse = {})

        session.preHelloDrops shouldBe 0L
        session.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        session.preHelloDrops shouldBe 1L
        session.onFrame(ByteBuffer.wrap(byteArrayOf(4, 5, 6)))
        session.preHelloDrops shouldBe 2L

        // once helloed, frames route through the ingress instead of dropping
        session.onText(session.hello())
        session.onFrame(ByteBuffer.wrap(byteArrayOf(7, 8, 9)))
        session.preHelloDrops shouldBe 2L // unchanged — this one was admitted
    }
}
