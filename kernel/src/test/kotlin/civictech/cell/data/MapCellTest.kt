package civictech.cell.data

import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.port.PortRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MapCellTest {

    @Test
    fun `MapCell propagates puts`() {
        val cell = MapCell<String, Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<MapDelta<String, Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.put("a", 1)

        assertEquals(1, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val delta = invocationBuffer[0].args[0] as MapDelta<String, Int>
        assertEquals(mapOf("a" to 1), delta.puts)
        assertEquals(emptySet<String>(), delta.removals)
    }

    @Test
    fun `MapCell propagates removals`() {
        val cell = MapCell<String, Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<MapDelta<String, Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.remove("a")

        assertEquals(1, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val delta = invocationBuffer[0].args[0] as MapDelta<String, Int>
        assertEquals(emptyMap<String, Int>(), delta.puts)
        assertEquals(setOf("a"), delta.removals)
    }

    @Test
    fun `MapCell handles multiple operations`() {
        val cell = MapCell<String, Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<MapDelta<String, Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.put("a", 1)
        cell.inlet.call.put("b", 2)
        cell.inlet.call.remove("a")

        assertEquals(3, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d1 = invocationBuffer[0].args[0] as MapDelta<String, Int>
        assertEquals(mapOf("a" to 1), d1.puts)

        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as MapDelta<String, Int>
        assertEquals(mapOf("b" to 2), d2.puts)

        @Suppress("UNCHECKED_CAST")
        val d3 = invocationBuffer[2].args[0] as MapDelta<String, Int>
        assertEquals(setOf("a"), d3.removals)
    }
}
