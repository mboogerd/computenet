package civictech.kernel.data

import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnionSetCellTest {

    @Test
    fun `UnionSetCell merges additions from multiple sources`() {
        val unionCell = UnionSetCell<Int>()
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        unionCell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        // Simulate two sources
        val source1 = unionCell.inlet.call
        val source2 = unionCell.inlet.call

        source1.propagate(SetDelta(adds = setOf(1), dels = emptySet()))
        source2.propagate(SetDelta(adds = setOf(1, 2), dels = emptySet()))

        // Total 3 calls to propagate from sources, but only 2 effective deltas should be propagated
        // 1st: add {1} -> union {1} (new)
        // 2nd: add {1, 2} -> {1} is already there, {2} is new.
        
        assertEquals(2, invocationBuffer.size)
        
        @Suppress("UNCHECKED_CAST")
        val d1 = invocationBuffer[0].args[0] as SetDelta<Int>
        assertEquals(setOf(1), d1.adds)
        
        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as SetDelta<Int>
        assertEquals(setOf(2), d2.adds)
    }

    @Test
    fun `UnionSetCell keeps element if one source still has it`() {
        val unionCell = UnionSetCell<Int>()
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        unionCell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        val source1 = unionCell.inlet.call
        val source2 = unionCell.inlet.call

        source1.propagate(SetDelta(adds = setOf(1), dels = emptySet()))
        source2.propagate(SetDelta(adds = setOf(1), dels = emptySet()))
        
        // Remove from source 1
        source1.propagate(SetDelta(adds = emptySet(), dels = setOf(1)))
        
        // union should still have 1 because source 2 has it.
        // invocationBuffer should only have the first 'add 1'
        assertEquals(1, invocationBuffer.size)
        
        // Remove from source 2
        source2.propagate(SetDelta(adds = emptySet(), dels = setOf(1)))
        
        // Now it should be removed from union
        assertEquals(2, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as SetDelta<Int>
        assertEquals(setOf(1), d2.dels)
    }

    @Test
    fun `UnionSetCell works with SetCell`() {
        val setCell1 = SetCell<Int>()
        val setCell2 = SetCell<Int>()
        val unionCell = UnionSetCell<Int>()
        
        setCell1.outlet.linkTo(unionCell.inlet)
        setCell2.outlet.linkTo(unionCell.inlet)
        
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        unionCell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))
        
        setCell1.inlet.call.add(1)
        setCell2.inlet.call.add(1)
        
        assertEquals(1, invocationBuffer.size)
        
        setCell1.inlet.call.remove(1)
        assertEquals(1, invocationBuffer.size)
        
        setCell2.inlet.call.remove(1)
        assertEquals(2, invocationBuffer.size)
        
        @Suppress("UNCHECKED_CAST")
        val lastDelta = invocationBuffer.last().args[0] as SetDelta<Int>
        assertEquals(setOf(1), lastDelta.dels)
    }

    @Test
    fun `UnionSetCell handles simultaneous add and del of same element correctly`() {
        val unionCell = UnionSetCell<Int>()
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        unionCell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        // If an element is in both adds and dels, it's weird but possible.
        // Our current logic processes adds then dels.
        // If count was 0:
        // adds: count becomes 1, effectiveAdds contains element.
        // dels: count becomes 0, effectiveDels contains element.
        // finalAdds = effectiveAdds - effectiveDels = empty
        // finalDels = effectiveDels - effectiveAdds = empty
        // Result: nothing propagated, which is correct as it's a net-zero change.
        
        unionCell.inlet.call.propagate(SetDelta(adds = setOf(1), dels = setOf(1)))
        assertEquals(0, invocationBuffer.size)
        
        // If count was 1:
        // adds: count becomes 2, effectiveAdds is empty (since it was 1).
        // dels: count becomes 1, effectiveDels is empty (since it's not going to 0).
        // Result: nothing propagated, correct.
        unionCell.inlet.call.propagate(SetDelta(adds = setOf(2), dels = emptySet())) // count(2)=1
        unionCell.inlet.call.propagate(SetDelta(adds = setOf(2), dels = setOf(2)))
        assertEquals(1, invocationBuffer.size) // Only for first add of 2
    }
}
