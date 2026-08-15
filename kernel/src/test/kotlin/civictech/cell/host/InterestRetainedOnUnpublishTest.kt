package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.link.Interest
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-6 / [KX-17]: pins that a ref's declared [Interest] survives unpublish,
 * deliberately — this is **not an endorsement** of the retention leak, only
 * a record of current behaviour so a future change to it is a conscious
 * decision rather than an accident.
 *
 * The interest-assignment table (moved onto [InstanceIndex] by this task,
 * [KX-01]) is never cleared by [LocationRegistry.unpublish],
 * [LocationRegistry.mirrorUnpublish], or [LocationRegistry.unpublishRemotes].
 * Clearing it would let a republished ref fall back to [Interest.Total] and
 * silently **widen** a shed range, contradicting PN-6's no-widening rule
 * ("an older epoch cannot widen a shed range back" —
 * `civictech.cell.replication.InstanceSet` KDoc). The resulting unbounded
 * growth of the table is filed as computenet-2971 and is out of scope here.
 */
class InterestRetainedOnUnpublishTest {

    private class PeerSink(private val name: String) : InvocationSink {
        override fun deliver(invocation: HostedPortInvocation) = Unit
        override fun toString() = "peer($name)"
    }

    private fun sink(name: String = UUID.randomUUID().toString()): InvocationSink = PeerSink(name)

    @Test
    fun `interestOf still reports the narrowed interest after unpublish and republish, not Total`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        val narrowed = Interest.Slots(setOf(0), totalSlots = 2)

        registry.publish(ref, sink())
        registry.setInterest(ref, narrowed)
        registry.unpublish(ref)
        registry.publish(ref, sink())

        registry.interestOf(ref) shouldBe narrowed
    }
}
