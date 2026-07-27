package civictech.cell.port

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Timestamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T05 finding 3: the `Admit` ADMIT tier is the licensed drop point, but a
 * dropped invocation's args were never inspected — an `Owned` was never
 * taken/frozen, a `Leased` never released (pool slot leak), since the SPSC
 * rule (spec 23) means an exclusive-carrying outlet has exactly one
 * consumer, so a drop here was unrecoverable loss. `mintAck`'s three silent
 * `?: return` exits also went uncounted; the one that matters in practice —
 * no open link matching the invocation's `sourcePort` — means the ack is
 * never minted and a downstream `WaveFrontier` stalls forever.
 */
class AdmitDischargeTest {

    interface OwnedApi {
        fun accept(value: Owned<String>)
    }

    @Test
    fun `a dropped Owned-carrying invocation is discharged, not leaked`() {
        val admit = Admit(admits = { false })
        val inlet = FanInlet(OwnedApi::class.java)
        inlet.install(admit)

        val owned = Owned("payload")
        inlet.call.accept(owned)

        // discharge already took it — a second take() would be a genuine
        // use-after-move if the value had leaked through untouched instead
        shouldThrow<IllegalStateException> { owned.take() }
    }

    @Test
    fun `an admit drop with no matching open link increments unackedDrops instead of silently skipping the ack`() {
        val admit = Admit(admits = { false })
        val inlet = FanInlet(OwnedApi::class.java)
        inlet.install(admit)

        admit.unackedDrops shouldBe 0

        // a wave-context invocation whose sourcePort matches no link this
        // inlet has ever registered — the wire-reconstructed-edge /
        // Use.fixed-producer / replayed-journal-frame case finding 3 names
        val ctx = MessageContext(Timestamp(UUID.randomUUID(), 1L), PortRef.generate())
        CurrentContext.with(ctx) { inlet.call.accept(Owned("orphaned")) }

        admit.unackedDrops shouldBe 1
    }

    @Test
    fun `an admit drop with no wave context at all increments unackedDrops`() {
        val admit = Admit(admits = { false })
        val inlet = FanInlet(OwnedApi::class.java)
        inlet.install(admit)

        // spontaneous call, no CurrentContext — invocation.context is null
        inlet.call.accept(Owned("spontaneous"))

        admit.unackedDrops shouldBe 1
    }
}
