package civictech.cell.port

import civictech.cell.Propagate
import civictech.cell.onEach
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * PN-9: outlet on-link policies compose via the [LinkSupport.onLinkedListeners]
 * multicast instead of stomping the single [LinkSupport.onLinked] slot; pull-serve
 * is an installable outlet policy; and pull-on-open is a separately installable
 * inlet policy that no longer requires an ALIGN frontier.
 */
class PullPolicyCompositionTest {

    private fun subscriber(collected: MutableList<String>): FanInlet<Propagate<String>> =
        FanInlet.create<Propagate<String>>().also { inlet -> inlet.onEach { collected += it } }

    @Test
    fun `catch-up, pull-serve and a replication re-announce all coexist on one outlet`() {
        val outlet = FanOutlet.create<Propagate<String>>()
        var reannounces = 0
        val served = mutableListOf<StateRequest>()

        outlet.catchUpOnLinked { "snapshot" }                       // ON-LINK: catch-up
        outlet.linking.onLinkedListeners += { reannounces++ }        // ON-LINK: replication re-announce
        outlet.pullServe { req -> served += req }                    // pull-serve (StateRequest handler)

        val collected = mutableListOf<String>()
        val sub = subscriber(collected)
        @Suppress("UNCHECKED_CAST")
        outlet.linkTo(sub as LinkFrom<Propagate<String>>)

        // all three retained — none overwrote another
        collected shouldBe listOf("snapshot")   // catch-up fired
        reannounces shouldBe 1                    // replication hook fired

        val link = outlet.linking.links.first()
        ProtocolSupport.of(outlet).deliver(Protocols.StateRequest, link, StateRequest(sub.ref, since = null))
        served.size shouldBe 1                    // pull-serve fired
    }

    @Test
    fun `control - the single onLinked slot loses catch-up when a second hook is installed`() {
        val outlet = FanOutlet.create<Propagate<String>>()
        val fired = mutableListOf<String>()

        // the pre-PN-9 pattern: both on-link behaviors assign the one slot
        outlet.linking.onLinked = { fired += "catch-up" }
        outlet.linking.onLinked = { fired += "re-announce" }   // stomps catch-up

        val collected = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        outlet.linkTo(subscriber(collected) as LinkFrom<Propagate<String>>)

        fired shouldBe listOf("re-announce")   // catch-up lost — the documented stomp
    }

    @Test
    fun `PullOnOpen without an ALIGN frontier issues a StateRequest on EdgeOpen`() {
        val producer = FanOutlet.create<Propagate<Int>>()
        val captured = mutableListOf<StateRequest>()
        ProtocolSupport.of(producer).handle(Protocols.StateRequest) { _, m -> captured += m as StateRequest }

        val inlet = FanInlet.create<Propagate<Int>>()
        inlet.install(PullOnOpen())
        inlet.serve(object : Propagate<Int> {
            override fun propagate(value: Int) {}
        })

        @Suppress("UNCHECKED_CAST")
        producer.linkTo(inlet as LinkFrom<Propagate<Int>>)

        captured.size shouldBe 1
        captured.first().replyTo shouldBe inlet.ref
    }

    @Test
    fun `control - a bare inlet without PullOnOpen issues no StateRequest`() {
        val producer = FanOutlet.create<Propagate<Int>>()
        val captured = mutableListOf<StateRequest>()
        ProtocolSupport.of(producer).handle(Protocols.StateRequest) { _, m -> captured += m as StateRequest }

        val inlet = FanInlet.create<Propagate<Int>>()
        inlet.serve(object : Propagate<Int> {
            override fun propagate(value: Int) {}
        })

        @Suppress("UNCHECKED_CAST")
        producer.linkTo(inlet as LinkFrom<Propagate<Int>>)

        captured.size shouldBe 0   // today impossible without ALIGN; now opt-in via PullOnOpen
    }
}
