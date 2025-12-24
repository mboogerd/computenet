package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import civictech.kernel.germ.proxy.callback
import civictech.kernel.port.PortRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneToOnePortTest {

    @Test
    fun `subscribing once succeeds`() {
        val port = OneToOnePort.withNoOp<Consumer<String>>()
        val (ref, buffer) = port.attachBufferingPort()
        port.use(ref) { provide("test") }
        assertEquals(listOf("test"), buffer)
    }

    @Test
    fun `subscribing twice fails`() {
        val port = OneToOnePort.withNoOp<Consumer<String>>()
        val (_, _) = port.attachBufferingPort()
        assertFailsWith<IllegalStateException> {
            port.attachBufferingPort()
        }
    }

    @Test
    fun `unsubscribe removes access`() {
        val port = OneToOnePort.withNoOp<Consumer<String>>()
        val (ref, buffer) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.use(ref) { provide("test") }
        assertEquals(emptyList(), buffer)
    }

    @Test
    fun `broadcast through all works`() {
        val port = OneToOnePort.withNoOp<Consumer<String>>()
        val (_, buffer) = port.attachBufferingPort()
        port.use { provide("test") }
        assertEquals(listOf("test"), buffer)
    }

    @Test
    fun `unsubscribe is idempotent`() {
        val port = OneToOnePort.withNoOp<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash
    }

    fun OneToOnePort<Consumer<String>>.attachBufferingPort(): Pair<PortRef, List<String>> {
        val portRef = PortRef.generate()
        val buffer = mutableListOf<String>()
        val proxy = callback<Consumer<String>> {
            buffer += it.args[0] as String
        }
        subscribe(portRef, Use.fixed(proxy))
        return portRef to buffer
    }
}
