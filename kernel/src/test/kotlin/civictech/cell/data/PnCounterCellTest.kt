package civictech.cell.data

import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID
import civictech.cell.data.delta.PnCounterDelta

class PnCounterCellTest {
    @Test
    fun `remote merge re-originates its wave and preserves source totals verbatim`() {
        val cell = PnCounterCell()
        val emissions = mutableListOf<Invocation>()
        cell.outlet.subscribe(Use.fixed(buffering<Propagate<PnCounterDelta>>(emissions), PortRef.generate()))
        val contributionSource = UUID.randomUUID()
        val incoming = PnCounterDelta(incs = mapOf(contributionSource to 7L))
        val incomingContext = MessageContext(Timestamp(UUID.randomUUID(), 41), PortRef.generate())
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)

        Invocation.of(propagate, arrayOf(incoming), incomingContext).invoke(cell.deltaInlet.call)

        val emission = emissions.single()
        // spec 20/22 §Source identity: sourceId is the outlet's emission
        // epoch, minted fresh at construction — never the port identity
        assertEquals(cell.outlet.waveState().sourceId, emission.context!!.timestamp.sourceId)
        assertEquals(cell.outlet.ref, emission.context!!.sourcePort)
        assertEquals(incoming, emission.args.single())
        val emitted = emission.args.single() as PnCounterDelta
        assertSame(contributionSource, emitted.incs.keys.single())
    }
}
