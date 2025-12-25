package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import civictech.kernel.germ.proxy.callback
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class OutletTest {

    @Test
    fun `subscribing once succeeds`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val (ref, buffer) = port.attachBufferingPort()
        port.use(ref) { provide("test") }
        assertEquals(listOf("test"), buffer)
    }

    @Test
    fun `subscribing twice fails without unsubscribe`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        port.attachBufferingPort()
        assertThrows<IllegalStateException> {
            port.attachBufferingPort()
        }
    }

    @Test
    fun `subscribing twice succeeds with unsubscribe`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        assertDoesNotThrow {
            port.attachBufferingPort()
        }
    }

    @Test
    fun `linkTo fails if already linked`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val use1 = Use.fixed(Consumer.buffering<String>().first, PortRef.generate())
        val use2 = Use.fixed(Consumer.buffering<String>().first, PortRef.generate())

        port.linkTo(use1)
        assertThrows<IllegalStateException> {
            port.linkTo(use2)
        }
    }

    @Test
    fun `linkFrom fails if already linked`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val inlet1 = Inlet.withNoOp<Consumer<String>>()
        val inlet2 = Inlet.withNoOp<Consumer<String>>()

        port.linkFrom(inlet1)
        assertThrows<IllegalStateException> {
            port.linkFrom(inlet2)
        }
    }

    @Test
    fun `unsubscribe removes access`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val (ref, buffer) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.use(ref) { provide("test") }
        assertEquals(emptyList(), buffer)
    }

    @Test
    fun `broadcast through all works`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val (_, buffer) = port.attachBufferingPort()
        port.use { provide("test") }
        assertEquals(listOf("test"), buffer)
    }

    @Test
    fun `unsubscribe is idempotent`() {
        val port = Outlet.withNoOp<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash
    }

    fun Outlet<Consumer<String>>.attachBufferingPort(): Pair<PortRef, List<String>> {
        val portRef = PortRef.generate()
        val buffer = mutableListOf<String>()
        val proxy = callback<Consumer<String>> {
            buffer += it.args[0] as String
        }
        subscribe(Use.fixed(proxy, portRef))
        return portRef to buffer
    }
}
