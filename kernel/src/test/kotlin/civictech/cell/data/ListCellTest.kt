package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.port.PortRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import civictech.cell.data.delta.ListDelta

class ListCellTest {

    @Test
    fun `ListCell propagates additions`() {
        val cell = ListCell<String>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<ListDelta<String>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add("a")
        cell.inlet.call.add(0, "b")

        assertEquals(2, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d1 = invocationBuffer[0].args[0] as ListDelta<String>
        assertEquals(listOf(IndexedValue(0, "a")), d1.adds)

        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as ListDelta<String>
        assertEquals(listOf(IndexedValue(0, "b")), d2.adds)
    }

    @Test
    fun `ListCell propagates updates`() {
        val cell = ListCell<String>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<ListDelta<String>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add("a")
        cell.inlet.call.set(0, "updated")

        assertEquals(2, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as ListDelta<String>
        assertEquals(listOf(IndexedValue(0, "updated")), d2.updates)
    }

    @Test
    fun `ListCell propagates removals`() {
        val cell = ListCell<String>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<ListDelta<String>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add("a")
        cell.inlet.call.add("b")
        cell.inlet.call.removeAt(0)

        assertEquals(3, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d3 = invocationBuffer[2].args[0] as ListDelta<String>
        assertEquals(listOf(0), d3.removals)
    }
}
