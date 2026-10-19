package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.handshake
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import civictech.nature.Ownership
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-10 (plan §2 F5). `tap`/`streamTo` historically skip the handshake — no
 * policies, no allowlist, no nature reconcile, no `EdgeOpen`. This test pins the
 * opt-in negotiated path: a negotiated tap runs the same target-side handshake a
 * Consume link runs (so it is refused by a policy and by a nature mismatch), and
 * — once admitted — announces itself as an [LinkRole.Observe] link.
 *
 * The historic bypass (`negotiated = false`) remains as a control: it admits
 * despite a deny-all policy.
 */
class NegotiatedAttachmentTest {

    private val consumerInt =
        @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    private fun denyAll(reason: String): LinkPolicy = LinkPolicy { LinkResult.Rejected(reason) }

    @Test
    fun `a negotiated tap is refused by an allowlist policy`() {
        val outlet = FanOutlet.create<Consumer<Int>>()
        val inlet = FanInlet.create<Consumer<Int>>()
        inlet.linking.policies += denyAll("tap not on the allowlist (spec 43)")

        val result = outlet.tap(inlet, negotiated = true)

        result.shouldBeInstanceOf<LinkResult.Rejected>()
        // refused before install: no link registered on either side, no tap wired
        outlet.linking.links.isEmpty().shouldBeTrue()
        inlet.linking.links.isEmpty().shouldBeTrue()
    }

    @Test
    fun `a negotiated tap is refused by a nature mismatch`() {
        val outlet = FanOutlet.create<Consumer<Int>>()
        val inlet = FanInlet.create<Consumer<Int>>()
        // consumer requires an EXCLUSIVE-ownership producer; the outlet offers
        // the SHARED default — a scoped-axis conflict the handshake must refuse
        // (where today's bypass would drop the mismatch silently at first emit).
        PortNatures.stamp(inlet, NatureVector.of(Ownership.EXCLUSIVE))

        val result = outlet.tap(inlet, negotiated = true)

        val rejected = result.shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.mismatch.shouldNotBeNull()
        rejected.mismatch!!.axis shouldBe NatureAxis.OWNERSHIP
        outlet.linking.links.isEmpty().shouldBeTrue()
        inlet.linking.links.isEmpty().shouldBeTrue()
    }

    @Test
    fun `an admitted negotiated tap appears in linking#links as an Observe link`() {
        val outlet = FanOutlet.create<Consumer<Int>>()
        val inlet = FanInlet.create<Consumer<Int>>()

        val result = outlet.tap(inlet, negotiated = true)

        result.shouldBeInstanceOf<LinkResult.Connected>()
        // registered on both endpoints, and tagged Observe on both
        outlet.linking.links.single().role shouldBe LinkRole.Observe
        inlet.linking.links.single().role shouldBe LinkRole.Observe

        // and it delivers: a tap still receives emissions
        val seen = mutableListOf<Int>()
        inlet.serve(object : Consumer<Int> {
            override fun provide(input: Int) { seen += input }
        })
        outlet.call.provide(7)
        seen shouldBe listOf(7)
    }

    @Test
    fun `control (a) - a non-negotiated tap is admitted despite a deny-all policy`() {
        val outlet = FanOutlet.create<Consumer<Int>>()
        val inlet = FanInlet.create<Consumer<Int>>()
        inlet.linking.policies += denyAll("deny-all")
        val seen = mutableListOf<Int>()
        inlet.serve(object : Consumer<Int> {
            override fun provide(input: Int) { seen += input }
        })

        // today's bypass: no handshake, so the deny-all policy is never consulted
        outlet.tap(inlet, negotiated = false)
        outlet.call.provide(42)

        // diverges from the negotiated case (refused, nothing wired/received)
        seen shouldBe listOf(42)
    }

}
