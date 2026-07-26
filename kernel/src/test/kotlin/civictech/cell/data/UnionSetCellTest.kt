package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.UnionSetCell

class UnionSetCellTest {

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    @Suppress("UNCHECKED_CAST")
    private fun deltas(buffer: List<Invocation>) = buffer.map { it.args[0] as SetDelta<Int> }

    @Test
    fun `forwards only new tag information`() {
        val unionCell = UnionSetCell<Int>()
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        unionCell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        val t1 = tag(1); val t2 = tag(2)
        unionCell.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(t1))))
        // duplicate delivery of t1 (diamond fan-in) plus a genuinely new tag t2
        unionCell.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(t1, t2))))

        val out = deltas(invocationBuffer)
        assertEquals(2, out.size)
        assertEquals(mapOf(1 to setOf(t1)), out[0].adds)
        assertEquals(mapOf(1 to setOf(t2)), out[1].adds) // t1 deduped
    }

    @Test
    fun `element stays live while another source's tag survives`() {
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
        setCell1.inlet.call.remove(1)

        // three effective tag events so far: two adds, one del — element still live
        val afterPartialRemove = deltas(invocationBuffer)
        assertEquals(3, afterPartialRemove.size)
        val liveTags = afterPartialRemove.fold(SetDelta<Int>()) { acc, d -> acc.merge(d) }
            .let { it.adds.getValue(1) - it.dels.getValue(1) }
        assertEquals(1, liveTags.size)

        setCell2.inlet.call.remove(1)

        // now every add-tag is covered by a del: element dead
        val all = deltas(invocationBuffer).fold(SetDelta<Int>()) { acc, d -> acc.merge(d) }
        assertEquals(all.adds.getValue(1), all.dels.getValue(1))
    }

    @Test
    fun `del of an unseen tag is dropped`() {
        val unionCell = UnionSetCell<Int>()
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        unionCell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        unionCell.inlet.call.propagate(SetDelta(dels = mapOf(1 to setOf(tag(9)))))
        assertEquals(0, invocationBuffer.size)
    }
}
