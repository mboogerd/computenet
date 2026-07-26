package civictech.cell.port

import civictech.cell.Consumer
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class LinkTest {

    private fun collectingInlet(received: MutableList<String>): FanInlet<Consumer<String>> =
        FanInlet.create<Consumer<String>>().apply {
            serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }

    @Test
    fun `successful handshake returns a Connected link registered on both ports`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val received = mutableListOf<String>()
        val inlet = collectingInlet(received)

        val result = outlet.linkTo(inlet as LinkFrom<Consumer<String>>)

        val link = result.shouldBeInstanceOf<LinkResult.Connected>().link
        link.from shouldBe outlet.ref
        link.to shouldBe inlet.ref
        inlet.linking.links.single() shouldBe link
        outlet.linking.links.single() shouldBe link

        outlet.call.provide("flows")
        received shouldBe listOf("flows")
    }

    @Test
    fun `onLink can reject and nothing is installed`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val received = mutableListOf<String>()
        val inlet = collectingInlet(received)
        inlet.linking.onLink = { LinkResult.Rejected("not today") }

        val result = outlet.linkTo(inlet as LinkFrom<Consumer<String>>)

        result shouldBe LinkResult.Rejected("not today")
        inlet.linking.links.isEmpty().shouldBeTrue()
        outlet.call.provide("dropped")
        received shouldBe emptyList()
    }

    @Test
    fun `policies compose and the first rejection wins`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val inlet = collectingInlet(mutableListOf())
        val evaluated = mutableListOf<String>()
        inlet.linking.policies += LinkPolicy { evaluated += "first"; null }
        inlet.linking.policies += LinkPolicy { evaluated += "second"; LinkResult.Rejected("blocked by second") }
        inlet.linking.policies += LinkPolicy { evaluated += "third"; null }

        val result = outlet.linkTo(inlet as LinkFrom<Consumer<String>>)

        result shouldBe LinkResult.Rejected("blocked by second")
        evaluated shouldBe listOf("first", "second")
    }

    @Test
    fun `policy receives the link request with the identity slot`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val inlet = collectingInlet(mutableListOf())
        var seen: LinkRequest? = null
        inlet.linking.policies += LinkPolicy { request -> seen = request; null }

        outlet.linkTo(inlet as LinkFrom<Consumer<String>>)

        seen shouldBe LinkRequest(outlet.ref, inlet.ref, identity = null)
    }

    @Test
    fun `unlink detaches both sides and fires onUnlink exactly once`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val received = mutableListOf<String>()
        val inlet = collectingInlet(received)
        var unlinked = 0
        inlet.linking.onUnlink = { unlinked++ }

        val link = (outlet.linkTo(inlet as LinkFrom<Consumer<String>>) as LinkResult.Connected).link
        outlet.call.provide("before")

        link.unlink()
        link.unlink() // idempotent
        outlet.call.provide("after")

        received shouldBe listOf("before")
        unlinked shouldBe 1
        inlet.linking.links.isEmpty().shouldBeTrue()
        outlet.linking.links.isEmpty().shouldBeTrue()
    }

    @Test
    fun `fan ports track multiple links`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val a = collectingInlet(mutableListOf())
        val b = collectingInlet(mutableListOf())

        outlet.linkTo(a as LinkFrom<Consumer<String>>)
        outlet.linkTo(b as LinkFrom<Consumer<String>>)

        outlet.linking.links.size shouldBe 2
        outlet.linking.links.map { it.to }.toSet() shouldBe setOf(a.ref, b.ref)
    }

    @Test
    fun `host connect surfaces the link result`() {
        val controller = civictech.cell.host.SimulationController()
        val host = civictech.cell.host.ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val producer = civictech.cell.HostTest.ProducerCell("hi")
        val consumer = civictech.cell.HostTest.CollectingConsumerCell()
        hostApi.spawn(producer)
        hostApi.spawn(consumer)

        val first = hostApi.connect(producer.ref, "outlet", consumer.ref, "inlet")
        first.shouldBeInstanceOf<LinkResult.Connected>()

        // reject via the consumer's onLink and observe the result at the caller
        consumer.inlet.linking.onLink = { LinkResult.Rejected("no more") }
        val second = hostApi.connect(producer.ref, "outlet", consumer.ref, "inlet")
        second shouldBe LinkResult.Rejected("no more")
    }
}
