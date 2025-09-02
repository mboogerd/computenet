package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import civictech.kernel.germ.proxy.callback
import civictech.kernel.germ.proxy.noop
import civictech.kernel.port.PortRef
import kotlin.test.*

class OneToOnePortTest {

    @Test
    fun `subscribing once succeeds`() {
        val port = OneToOnePort.withProxy<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        assertNotNull(port.one(ref))
    }

    @Test
    fun `subscribing twice fails`() {
        val port = OneToOnePort.withProxy<Consumer<String>>()
        val (_, _) = port.attachBufferingPort()
        assertFailsWith<IllegalStateException> {
            port.attachBufferingPort()
        }
    }

    @Test
    fun `unsubscribe removes access`() {
        val port = OneToOnePort.withProxy<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        assertNull(port.one(ref))
    }

    @Test
    fun `broadcast through all works`() {
        val port = OneToOnePort.withProxy<Consumer<String>>()
        val (_, buffer) = port.attachBufferingPort()
        port.all().provide("test")
        assertEquals(listOf("test"), buffer)
    }

    @Test
    fun `unsubscribe is idempotent`() {
        val port = OneToOnePort.withProxy<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash
    }

    @Test
    fun `invalidating listeners are notified`() {
        val port = OneToOnePort.withProxy<Consumer<String>>()
        var invalidated = false
        val tracker = object : Invalidating {
            override fun invalidate() {
                invalidated = true
            }
        }
        port.attach(tracker)
        port.attachBufferingPort()
        assertTrue(invalidated)
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
