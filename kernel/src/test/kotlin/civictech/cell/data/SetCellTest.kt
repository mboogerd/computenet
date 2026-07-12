package civictech.cell.data

import civictech.cell.Timestamp
import civictech.cell.MessageContext
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class SetCellTest {

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    @Test
    fun `SetDelta merge is a commutative tag-set union`() {
        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        val d1 = SetDelta(adds = mapOf("x" to setOf(t1)), dels = mapOf("y" to setOf(t3)))
        val d2 = SetDelta(adds = mapOf("x" to setOf(t2)), dels = emptyMap())

        val merged = d1.merge(d2)
        assertEquals(mapOf("x" to setOf(t1, t2)), merged.adds)
        assertEquals(mapOf("y" to setOf(t3)), merged.dels)
        assertEquals(merged, d2.merge(d1))
    }

    @Test
    fun `SetCell propagates additions with a fresh tag per add`() {
        val cell = SetCell<Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add(1)
        cell.inlet.call.add(1)

        assertEquals(2, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val deltas = invocationBuffer.map { it.args[0] as SetDelta<Int> }
        val tags = deltas.map { it.adds.getValue(1).single() }
        assertEquals(2, tags.toSet().size) // re-adding mints a new tag
    }

    @Test
    fun `SetCell remove emits exactly the observed tags`() {
        val cell = SetCell<Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add(1)
        cell.inlet.call.add(1)
        cell.inlet.call.remove(1)

        assertEquals(3, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val deltas = invocationBuffer.map { it.args[0] as SetDelta<Int> }
        val minted = deltas.take(2).flatMap { it.adds.getValue(1) }.toSet()
        assertEquals(minted, deltas[2].dels.getValue(1))
    }

    @Test
    fun `SetCell remove of an unobserved element is a no-op`() {
        val cell = SetCell<Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.remove(42)
        assertTrue(invocationBuffer.isEmpty())
    }

    @Test
    fun `SetCell remote merge re-originates its wave and preserves tags verbatim`() {
        val cell = SetCell<Int>()
        val emissions = mutableListOf<Invocation>()
        cell.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<Int>>>(emissions), PortRef.generate()))
        val incomingTag = tag(7)
        val incoming = SetDelta(adds = mapOf(1 to setOf(incomingTag)))
        val incomingContext = MessageContext(Timestamp(UUID.randomUUID(), 41), PortRef.generate())
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)

        Invocation.of(propagate, arrayOf(incoming), incomingContext).invoke(cell.deltaInlet.call)

        val emission = emissions.single()
        assertEquals(cell.outlet.ref.id, emission.context!!.timestamp.sourceId)
        assertEquals(cell.outlet.ref, emission.context!!.sourcePort)
        assertEquals(incoming, emission.args.single())
        @Suppress("UNCHECKED_CAST")
        val emitted = emission.args.single() as SetDelta<Int>
        assertSame(incomingTag, emitted.adds.getValue(1).single())
    }
}
