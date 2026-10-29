package civictech.cell.port

import civictech.cell.Owned
import civictech.cell.proxy.DmwlDerived
import civictech.cell.proxy.DmwlHolder
import civictech.cell.proxy.DmwlPush
import civictech.cell.proxy.HandBuiltExclusive
import civictech.cell.proxy.HandBuiltExclusiveDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T05 finding 3: the `Admit` ADMIT tier is the licensed drop point, but a
 * dropped invocation's args were never inspected — an `Owned` was never
 * taken/frozen, a `Leased` never released (pool slot leak), since the SPSC
 * rule (spec 23) means an exclusive-carrying outlet has exactly one
 * consumer, so a drop here was unrecoverable loss.
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

    // computenet-dmwl: the drop discharges only descriptor-marked positions.
    @Test
    fun `an admit drop does not consume a runtime exclusive behind a supertype-declared parameter`() {
        val inlet = FanInlet(DmwlPush::class.java)
        inlet.install(Admit(admits = { false }))

        val item = Owned("item")
        val foreign = Owned("foreign")
        inlet.call.push(item, DmwlHolder(DmwlDerived(foreign)))

        shouldThrow<IllegalStateException> { item.take() }
        foreign.take() shouldBe "foreign"
    }

    @Test
    fun `an admit drop on a hand-built exclusive descriptor with no marked positions discharges every argument`() {
        HandBuiltExclusiveDescriptor.ensureRegistered()
        val inlet = FanInlet(HandBuiltExclusive::class.java)
        inlet.install(Admit(admits = { false }))

        val item = Owned("item")
        inlet.call.accept(item)

        shouldThrow<IllegalStateException> { item.take() }
    }
}
