package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CounterCellTest {

    @Test
    fun `emits effective deltas and merge is order-insensitive addition`() {
        val cell = CounterCell()
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<CounterDelta>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.increment(3)
        cell.inlet.call.decrement(1)
        cell.inlet.call.increment(0) // ineffective: no emission

        assertEquals(2, invocationBuffer.size)
        val deltas = invocationBuffer.map { it.args[0] as CounterDelta }
        assertEquals(CounterDelta(2), deltas[0].merge(deltas[1]))
        assertEquals(CounterDelta(2), deltas[1].merge(deltas[0]))
    }
}
