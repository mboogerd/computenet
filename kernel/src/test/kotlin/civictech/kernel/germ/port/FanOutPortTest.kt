package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import civictech.kernel.germ.proxy.callback
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FanOutPortTest {

    @Test
    fun `broadcasting on an empty port doesn't do anything`() {
        val fanOutPort = FanOutPort.withProxy<Consumer<String>>()
        fanOutPort.use { provide("does not throw, but doesn't do anything either") }
    }

    @Test
    fun `using a non-existing downstream api completes without error`() {
        val fanOutPort = FanOutPort.withProxy<Consumer<String>>()
        fanOutPort.use(PortRef.generate()) { provide("test") }
    }

    @Test
    fun `retrieving an existing downstream api returns that entry`() {
        val fanOutPort = FanOutPort.withProxy<Consumer<String>>()
        val (portRef1, buffer1) = fanOutPort.attachBufferingPort()
        val (_, buffer2) = fanOutPort.attachBufferingPort()

        fanOutPort.use(portRef1) { provide("first") }
        fanOutPort.use(portRef1) { provide("second") }

        assertEquals(listOf("first", "second"), buffer1)
        assertEquals(emptyList(), buffer2)
    }

    @Test
    fun `broadcasting reaches all active subscriptions`() {
        val fanOutPort = FanOutPort.withProxy<Consumer<String>>()

        // first
        val (_, buffer1) = fanOutPort.attachBufferingPort()
        fanOutPort.use { provide("first") }

        val (_, buffer2) = fanOutPort.attachBufferingPort()
        fanOutPort.use { provide("second") }

        val (_, buffer3) = fanOutPort.attachBufferingPort()
        fanOutPort.use { provide("third") }

        assertEquals(listOf("first", "second", "third"), buffer1)
        assertEquals(listOf("second", "third"), buffer2)
        assertEquals(listOf("third"), buffer3)
    }

    @Test
    fun `unsubscribed downstream api is no longer available`() {
        val fanOutPort = FanOutPort.withProxy<Consumer<String>>()
        val (portRef1, buffer1) = fanOutPort.attachBufferingPort()

        fanOutPort.use { provide("first") }
        fanOutPort.unsubscribe(portRef1)
        fanOutPort.use { provide("second") }
        fanOutPort.use(portRef1) { provide("third") }

        assertEquals(listOf("first"), buffer1)
    }

    @Test
    fun `re-subscribing a PortRef overwrites the previous handler`() {
        val port = FanOutPort.withProxy<Consumer<String>>()
        val ref = PortRef.generate()
        val buffer1 = mutableListOf<String>()
        val buffer2 = mutableListOf<String>()

        val proxy1 = callback<Consumer<String>> { buffer1 += it.args[0] as String }
        port.subscribe(ref, Use.fixed(proxy1))
        port.use { provide("first") }
        assertEquals(listOf("first"), buffer1)

        val proxy2 = callback<Consumer<String>> { buffer2 += it.args[0] as String }
        port.subscribe(ref, Use.fixed(proxy2))
        port.use { provide("second") }

        assertEquals(listOf("first"), buffer1)
        assertEquals(listOf("second"), buffer2)
    }

    @Test
    fun `multiple unsubscribe calls do not crash`() {
        val port = FanOutPort.withProxy<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash or side effect
    }

    fun FanOutPort<Consumer<String>>.attachBufferingPort(): Pair<PortRef, List<String>> {
        val portRef = PortRef.generate()
        val buffer = mutableListOf<String>()
        val proxy = callback<Consumer<String>> {
            buffer += it.args[0] as String
        }
        subscribe(portRef, Use.fixed(proxy))
        return portRef to buffer
    }
}

