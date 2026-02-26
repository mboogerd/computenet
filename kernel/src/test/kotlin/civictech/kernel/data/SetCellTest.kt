package civictech.kernel.data

import civictech.kernel.germ.ManagedRunner
import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SetCellTest {

    @Test
    fun `SetDelta mergeAddWins works correctly`() {
        val d1 = SetDelta(adds = setOf(1, 2), dels = setOf(3))
        val d2 = SetDelta(adds = setOf(3), dels = setOf(2, 4))
        
        // mergeAddWins: 1, 2 from d1. 3 from d2. 
        // adds = {1, 2, 3}
        // dels = {3} (d1) + {2, 4} (d2) = {2, 3, 4}
        // dels - adds = {4}
        val result = d1.mergeAddWins(d2)
        
        assertEquals(setOf(1, 2, 3), result.adds)
        assertEquals(setOf(4), result.dels)
    }

    @Test
    fun `SetDelta mergeDelWins works correctly`() {
        val d1 = SetDelta(adds = setOf(1, 2), dels = setOf(3))
        val d2 = SetDelta(adds = setOf(3), dels = setOf(2, 4))
        
        // mergeDelWins: 3 from d1. 2, 4 from d2.
        // dels = {2, 3, 4}
        // adds = {1, 2} (d1) + {3} (d2) = {1, 2, 3}
        // adds - dels = {1}
        val result = d1.mergeDelWins(d2)
        
        assertEquals(setOf(1), result.adds)
        assertEquals(setOf(2, 3, 4), result.dels)
    }

    @Test
    fun `SetCell propagates additions`() {
        val runner = ManagedRunner()
        val cell = SetCell<Int>()
        
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))
        
        cell.inlet.call.add(1)
        
        assertEquals(1, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val delta = invocationBuffer[0].args[0] as SetDelta<Int>
        assertEquals(setOf(1), delta.adds)
        assertEquals(emptySet<Int>(), delta.dels)
    }

    @Test
    fun `SetCell propagates deletions`() {
        val runner = ManagedRunner()
        val cell = SetCell<Int>()
        
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))
        
        cell.inlet.call.remove(1)
        
        assertEquals(1, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val delta = invocationBuffer[0].args[0] as SetDelta<Int>
        assertEquals(emptySet<Int>(), delta.adds)
        assertEquals(setOf(1), delta.dels)
    }

    @Test
    fun `SetCell handles multiple operations`() {
        val runner = ManagedRunner()
        val cell = SetCell<Int>()
        
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))
        
        cell.inlet.call.add(1)
        cell.inlet.call.add(2)
        cell.inlet.call.remove(1)
        
        assertEquals(3, invocationBuffer.size)
        
        @Suppress("UNCHECKED_CAST")
        val d1 = invocationBuffer[0].args[0] as SetDelta<Int>
        assertEquals(setOf(1), d1.adds)
        
        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as SetDelta<Int>
        assertEquals(setOf(2), d2.adds)
        
        @Suppress("UNCHECKED_CAST")
        val d3 = invocationBuffer[2].args[0] as SetDelta<Int>
        assertEquals(setOf(1), d3.dels)
    }
}
