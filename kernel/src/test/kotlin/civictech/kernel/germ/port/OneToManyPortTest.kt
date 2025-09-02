package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import civictech.kernel.germ.proxy.callback
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneToManyPortTest {

    @Test
    fun `broadcasting on an empty port doesn't do anything`() {
        val oneToManyPort = OneToManyPort.withProxy<Consumer<String>>()
        oneToManyPort.all().provide("does not throw, but doesn't do anything either")
    }

    @Test
    fun `retrieving a non-existing downstream api returns null`() {
        val oneToManyPort = OneToManyPort.withProxy<Consumer<String>>()
        assertNull(oneToManyPort.one(PortRef.generate()))
    }

    @Test
    fun `retrieving an existing downstream api returns the entry`() {
        val oneToManyPort = OneToManyPort.withProxy<Consumer<String>>()
        val (portRef1, buffer1) = oneToManyPort.attachBufferingPort()
        assertNotNull(oneToManyPort.one(portRef1))
    }

    @Test
    fun `retrieving an existing downstream api returns that entry`() {
        val oneToManyPort = OneToManyPort.withProxy<Consumer<String>>()
        val (portRef1, buffer1) = oneToManyPort.attachBufferingPort()
        val (_, buffer2) = oneToManyPort.attachBufferingPort()

        oneToManyPort.one(portRef1)!!.provide("first")
        oneToManyPort.one(portRef1)!!.provide("second")

        assertEquals(listOf("first", "second"), buffer1)
        assertEquals(emptyList(), buffer2)
    }

    @Test
    fun `broadcasting reaches all active subscriptions`() {
        val oneToManyPort = OneToManyPort.withProxy<Consumer<String>>()

        // first
        val (_, buffer1) = oneToManyPort.attachBufferingPort()
        oneToManyPort.all().provide("first")

        val (_, buffer2) = oneToManyPort.attachBufferingPort()
        oneToManyPort.all().provide("second")

        val (_, buffer3) = oneToManyPort.attachBufferingPort()
        oneToManyPort.all().provide("third")

        assertEquals(listOf("first", "second", "third"), buffer1)
        assertEquals(listOf("second", "third"), buffer2)
        assertEquals(listOf("third"), buffer3)
    }

    @Test
    fun `unsubscribed downstream api is no longer available`() {
        val oneToManyPort = OneToManyPort.withProxy<Consumer<String>>()
        val (portRef1, buffer1) = oneToManyPort.attachBufferingPort()

        oneToManyPort.all().provide("first")
        oneToManyPort.unsubscribe(portRef1)
        oneToManyPort.all().provide("second")

        assertEquals(listOf("first"), buffer1)
        assertNull(oneToManyPort.one(portRef1))
    }

    @Test
    fun `re-subscribing a PortRef overwrites the previous handler`() {
        val port = OneToManyPort.withProxy<Consumer<String>>()
        val ref = PortRef.generate()
        val buffer1 = mutableListOf<String>()
        val buffer2 = mutableListOf<String>()

        val proxy1 = callback<Consumer<String>> { buffer1 += it.args[0] as String }
        port.subscribe(ref, Use.fixed(proxy1))
        port.all().provide("first")
        assertEquals(listOf("first"), buffer1)

        val proxy2 = callback<Consumer<String>> { buffer2 += it.args[0] as String }
        port.subscribe(ref, Use.fixed(proxy2))
        port.all().provide("second")

        assertEquals(listOf("first"), buffer1)
        assertEquals(listOf("second"), buffer2)
    }

    @Test
    fun `multiple unsubscribe calls do not crash`() {
        val port = OneToManyPort.withProxy<Consumer<String>>()
        val (ref, _) = port.attachBufferingPort()
        port.unsubscribe(ref)
        port.unsubscribe(ref) // no crash or side effect
    }

    @Test
    fun `invalidate triggers tracker when subscriptions change`() {
        val port = OneToManyPort.withProxy<Consumer<String>>()
        var invalidated = false
        val tracker = object : Invalidating {
            override fun invalidate() {
                invalidated = true
            }
        }
        port.attach(tracker)
        port.attachBufferingPort()
        assert(invalidated) // should be invalidated after subscribe
    }

    fun OneToManyPort<Consumer<String>>.attachBufferingPort(): Pair<PortRef, List<String>> {
        val portRef = PortRef.generate()
        val buffer = mutableListOf<String>()
        val proxy = callback<Consumer<String>> {
            buffer += it.args[0] as String
        }
        subscribe(portRef, Use.fixed(proxy))
        return portRef to buffer
    }
}

