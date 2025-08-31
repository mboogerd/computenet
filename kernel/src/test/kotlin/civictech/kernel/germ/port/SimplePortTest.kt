package civictech.kernel.germ.port

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimplePortTest {
    companion object {
        private const val SOME_STRING = "some string"
        private const val ANOTHER_STRING = "another string"
    }

    @Test
    fun `using an uninitialized port fails`() {
        val port = SimplePort<String>()
        assertThrows<IllegalStateException> { port.use() }
    }

    @Test
    fun `default is returned by use`() {
        val port = SimplePort(SOME_STRING)
        assertSame(SOME_STRING, port.use())
    }

    @Test
    fun `last serve is returned by use`() {
        val port = SimplePort<String>()
        port.serve(ANOTHER_STRING)
        port.serve(SOME_STRING)
        assertSame(SOME_STRING, port.use())
    }

    @Test
    fun `using a delegated uninitialized port fails`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort<String>()
        port1.delegate(port2)
        assertThrows<java.lang.IllegalStateException> { port1.use() }
    }

    @Test
    fun `using a delegated port obtains the delegated implementation`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort(SOME_STRING)
        port1.delegate(port2)
        assertSame(SOME_STRING, port1.use())
    }

    @Test
    fun `serve replaces delegate`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort(ANOTHER_STRING)
        port1.delegate(port2)
        port1.serve(SOME_STRING)
        assertSame(SOME_STRING, port1.use())
    }

    @Test
    fun `delegate replaces serve`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort(SOME_STRING)

        port1.serve(ANOTHER_STRING)
        port1.delegate(port2)

        assertSame(SOME_STRING, port1.use())
    }

    @Test
    fun `an intermediate delegating port can insert itself`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort<String>()
        val port3 = SimplePort(ANOTHER_STRING)

        port1.delegate(port2)
        port2.delegate(port3)

        port2.serve(SOME_STRING)
        assertSame(SOME_STRING, port1.use())
    }

    @Test
    fun `a port delegated to can itself delegate`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort<String>()
        val port3 = SimplePort(SOME_STRING)

        port1.delegate(port2)
        port2.serve(ANOTHER_STRING)

        port2.delegate(port3)

        assertSame(SOME_STRING, port1.use())
    }

    @Test
    fun `a delegated port can swap its implementation for all upstreams`() {
        val port1 = SimplePort<String>()
        val port2 = SimplePort<String>()
        val port3 = SimplePort(ANOTHER_STRING)

        port1.delegate(port2)
        port2.delegate(port3)

        assertSame(ANOTHER_STRING, port1.use())
        assertFalse(port1.isStale())
        assertFalse(port2.isStale())
        assertFalse(port3.isStale())

        port3.serve(SOME_STRING)
        assertTrue(port1.isStale())
        assertTrue(port2.isStale())
        assertFalse(port3.isStale())

        assertSame(SOME_STRING, port1.use())
        assertFalse(port1.isStale())
        assertFalse(port2.isStale())
        assertFalse(port3.isStale())
    }
}