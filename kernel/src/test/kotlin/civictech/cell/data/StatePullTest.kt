package civictech.cell.data

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.data.delta.SetDelta
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PullOnOpen
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * W2.2 (G-37 + G-38, closes G-18): on-demand `StateRequest` pull and the
 * catch-up baseline. A link installed mid-stream issues a StateRequest (spec
 * 20/21 §Pull) and receives a state-as-delta baseline stamped with
 * `MessageContext.baseline` ahead of any later live wave.
 */
class StatePullTest {

    @Suppress("UNCHECKED_CAST")
    private val propagateSetDelta = (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private data class Arrival(val delta: SetDelta<String>, val ctx: MessageContext)

    private fun consumer(
        arrivals: MutableList<Arrival>,
        since: () -> civictech.cell.TagFrontier? = { null },
    ): FanInlet<Propagate<SetDelta<String>>> = FanInlet(propagateSetDelta).also { inlet ->
        inlet.install(PullOnOpen(since))
        inlet.serve(Propagate<SetDelta<String>> { value ->
            arrivals += Arrival(value, CurrentContext.get()!!)
        })
    }

    private fun link(
        outlet: FanOutlet<Propagate<SetDelta<String>>>,
        inlet: FanInlet<Propagate<SetDelta<String>>>,
    ) {
        (outlet.linkTo(inlet as LinkFrom<Propagate<SetDelta<String>>>) is LinkResult.Connected) shouldBe true
    }

    @Test
    fun `a link installed mid-stream pulls a baseline synchronously ahead of live waves`() {
        val arrivals = mutableListOf<Arrival>()
        val inlet = consumer(arrivals)

        val p1 = SetCell<String>()
        val p2 = SetCell<String>()
        p2.outlet.linking.onLinkedListeners.clear()

        link(p1.outlet, inlet)
        p1.inlet.call.add("early")
        arrivals.size shouldBe 1
        arrivals[0].ctx.baseline.shouldBeNull()
        tagFold(listOf(arrivals[0].delta)) shouldBe setOf("early")

        p2.inlet.call.add("q")
        p2.inlet.call.add("r")

        link(p2.outlet, inlet)

        arrivals.size shouldBe 2
        val baseline = arrivals[1]
        baseline.ctx.baseline.shouldNotBeNull()
        tagFold(listOf(baseline.delta)) shouldBe setOf("q", "r")

        p1.inlet.call.add("later")
        arrivals.size shouldBe 3
        arrivals[2].ctx.baseline.shouldBeNull()
        tagFold(listOf(arrivals[2].delta)) shouldBe setOf("later")
    }

    @Test
    fun `since incremental pull returns only the tags beyond the frontier`() {
        val arrivals = mutableListOf<Arrival>()
        val inlet = consumer(arrivals)

        val producer = SetCell<String>()
        producer.outlet.linking.onLinkedListeners.clear()
        producer.inlet.call.add("x")

        link(producer.outlet, inlet)
        arrivals.size shouldBe 1
        val fullFrontier = arrivals[0].ctx.baseline!!

        producer.inlet.call.add("y")

        val incremental = mutableListOf<Arrival>()
        val probe = consumer(incremental, since = { fullFrontier })
        link(producer.outlet, probe)

        incremental.size shouldBe 1
        incremental[0].ctx.baseline.shouldNotBeNull()
        tagFold(listOf(incremental[0].delta)) shouldBe setOf("y")
        incremental[0].delta.adds.containsKey("x").shouldBeFalse()
    }
}
