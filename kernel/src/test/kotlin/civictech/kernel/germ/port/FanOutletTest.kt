package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import civictech.kernel.germ.proxy.callback
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FanOutletTest {

    @Test
    fun `broadcasting on an empty port doesn't do anything`() {
        val fanOutlet = FanOutlet<Consumer<String>>()
        fanOutlet.use { provide("does not throw, but doesn't do anything either") }
    }

    @Test
    fun `using a non-existing downstream api completes without error`() {
        val fanOutlet = FanOutlet<Consumer<String>>()
        fanOutlet.use(PortRef.generate()) { provide("test") }
    }

    @Test
    fun `retrieving an existing downstream api returns that entry`() {
        val fanOutlet = FanOutlet<Consumer<String>>()
        val (portRef1, buffer1) = fanOutlet.attachBufferingPort()
        val (_, buffer2) = fanOutlet.attachBufferingPort()

        fanOutlet.use(portRef1) { provide("first") }
        fanOutlet.use(portRef1) { provide("second") }

        assertEquals(listOf("first", "second"), buffer1)
        assertEquals(emptyList(), buffer2)
    }

    @Test
    fun `broadcasting reaches all active subscriptions`() {
        val fanOutlet = FanOutlet<Consumer<String>>()

        // first
        val (_, buffer1) = fanOutlet.attachBufferingPort()
        fanOutlet.use { provide("first") }

        val (_, buffer2) = fanOutlet.attachBufferingPort()
        fanOutlet.use { provide("second") }

        val (_, buffer3) = fanOutlet.attachBufferingPort()
        fanOutlet.use { provide("third") }

        assertEquals(listOf("first", "second", "third"), buffer1)
        assertEquals(listOf("second", "third"), buffer2)
        assertEquals(listOf("third"), buffer3)
    }

    @Test
    fun `unsubscribed downstream api is no longer available`() {
        val fanOutlet = FanOutlet<Consumer<String>>()
        val (portRef1, buffer1) = fanOutlet.attachBufferingPort()

        fanOutlet.use { provide("first") }
        fanOutlet.unsubscribe(portRef1)
        fanOutlet.use { provide("second") }
        fanOutlet.use(portRef1) { provide("third") }

        assertEquals(listOf("first"), buffer1)
    }

    @Test
    fun `re-subscribing a PortRef overwrites the previous handler`() {
        val port = FanOutlet<Consumer<String>>()
        val buffer1 = mutableListOf<String>()
        val buffer2 = mutableListOf<String>()

        val fixedPortRef = PortRef.generate()

        val proxy1 = callback<Consumer<String>> { buffer1 += it.args[0] as String }
        port.subscribe(Use.fixed(proxy1, fixedPortRef))
        port.use { provide("first") }
        assertEquals(listOf("first"), buffer1)

        val proxy2 = callback<Consumer<String>> { buffer2 += it.args[0] as String }
        port.subscribe(Use.fixed(proxy2, fixedPortRef))
        port.use { provide("second") }

        assertEquals(listOf("first"), buffer1)
        assertEquals(listOf("second"), buffer2)
    }

    @Test
    fun `multiple unsubscribe calls do not crash`() {
        val port = FanOutlet<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash or side effect
    }

    fun FanOutlet<Consumer<String>>.attachBufferingPort(): Pair<PortRef, List<String>> {
        val portRef = PortRef.generate()
        val buffer = mutableListOf<String>()
        val proxy = callback<Consumer<String>> {
            buffer += it.args[0] as String
        }
        subscribe(Use.fixed(proxy, portRef))
        return portRef to buffer
    }
}

