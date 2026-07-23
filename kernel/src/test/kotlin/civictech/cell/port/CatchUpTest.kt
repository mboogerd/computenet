package civictech.cell.port

import civictech.cell.data.Propagate
import civictech.cell.data.onEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatchUpTest {

    private fun subscriber(collected: MutableList<String>): FanInlet<Propagate<String>> =
        FanInlet.create<Propagate<String>>().also { inlet -> inlet.onEach { collected += it } }

    @Test
    fun `late subscriber receives the snapshot once, then the live stream`() {
        val outlet = FanOutlet.create<Propagate<String>>()
        outlet.catchUpOnLinked { "snapshot" }

        val collected = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val result = outlet.linkTo(subscriber(collected) as LinkFrom<Propagate<String>>)
        assertTrue(result is LinkResult.Connected)
        assertEquals(listOf("snapshot"), collected)

        outlet.call.propagate("live")
        assertEquals(listOf("snapshot", "live"), collected)
    }

    @Test
    fun `null snapshot sends nothing on link`() {
        val outlet = FanOutlet.create<Propagate<String>>()
        outlet.catchUpOnLinked { null }

        val collected = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val result = outlet.linkTo(subscriber(collected) as LinkFrom<Propagate<String>>)
        assertTrue(result is LinkResult.Connected)
        assertEquals(emptyList<String>(), collected)
    }
}
