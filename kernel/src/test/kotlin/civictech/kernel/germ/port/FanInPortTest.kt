package civictech.kernel.germ.port

import civictech.kernel.germ.Consumer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.fail

class FanInPortTest {

    @Test
    fun `using an uninitialized port throws`() {
        val port = FanInPort<Consumer<String>>()
        assertThrows<IllegalStateException> { port.use { fail("This shouldn't be invoked") } }
    }

    @Test
    fun `default is employed by use if nothing else is linked`() {
        val (consumer, buffer) = Consumer.buffering<String>()
        val port = FanInPort(consumer)
        port.use { provide("first") }
        assertEquals(buffer, listOf("first"))
    }

    @Test
    fun `last serve is returned by use`() {
        val (consumer1, _) = Consumer.buffering<String>()
        val (consumer2, buffer2) = Consumer.buffering<String>()
        val port = FanInPort<Consumer<String>>()
        port.serve(consumer1)
        port.serve(consumer2)
        port.use { provide("first") }
        assertEquals(buffer2, listOf("first"))
    }

    @Test
    fun `using a delegated uninitialized port throws`() {
        val port1 = FanInPort<Consumer<String>>()
        val port2 = FanInPort<Consumer<String>>()
        port1.delegate(port2)
        assertThrows<IllegalStateException> { port1.use { fail("This shouldn't be invoked") } }
    }

    @Test
    fun `using a delegated port obtains the delegated implementation`() {
        val (consumer, buffer) = Consumer.buffering<String>()
        val port1 = FanInPort<Consumer<String>>()
        val port2 = FanInPort(consumer)
        port1.delegate(port2)
        port1.use { provide("first") }
        assertEquals(buffer, listOf("first"))
    }

    @Test
    fun `serve replaces delegate`() {
        val (consumer1, buffer1) = Consumer.buffering<String>()
        val (consumer2, buffer2) = Consumer.buffering<String>()
        val port1 = FanInPort<Consumer<String>>()
        val port2 = FanInPort(consumer1)

        port1.delegate(port2)
        port1.use { provide("first") }
        port1.serve(consumer2)
        port1.use { provide("second") }

        assertEquals(buffer1, listOf("first"))
        assertEquals(buffer2, listOf("second"))
    }

    @Test
    fun `delegate replaces serve`() {
        val (consumer1, buffer1) = Consumer.buffering<String>()
        val (consumer2, buffer2) = Consumer.buffering<String>()

        val port1 = FanInPort<Consumer<String>>()
        val port2 = FanInPort(consumer2)

        port1.serve(consumer1)
        port1.use { provide("first") }
        port1.delegate(port2)
        port1.use { provide("second") }

        assertEquals(buffer1, listOf("first"))
        assertEquals(buffer2, listOf("second"))
    }

    @Test
    fun `an intermediate delegating port can insert itself`() {
        val (consumer2, buffer2) = Consumer.buffering<String>()
        val (consumer3, _) = Consumer.buffering<String>()
        val port1 = FanInPort<Consumer<String>>()
        val port2 = FanInPort<Consumer<String>>()
        val port3 = FanInPort(consumer3)

        port1.delegate(port2)
        port2.delegate(port3)

        port2.serve(consumer2)

        port1.use { provide("I'll arrive at port 2 consumer") }

        assertEquals(buffer2, listOf("I'll arrive at port 2 consumer"))
    }

    @Test
    fun `a port delegated to can itself delegate`() {
        val (consumer3, buffer3) = Consumer.buffering<String>()
        val port1 = FanInPort<Consumer<String>>()
        val port2 = FanInPort<Consumer<String>>()
        val port3 = FanInPort(consumer3)

        port1.delegate(port2)
        port2.serve(Consumer.buffering<String>().first)

        port2.delegate(port3)
        port1.use { provide("propagated to the end") }

        assertEquals(buffer3, listOf("propagated to the end"))
    }
}