package civictech.cell.data

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.attention.Progress
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Link
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.StateRequest
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import civictech.cell.data.delta.SetDelta

/**
 * W2.2 (G-37 + G-38, closes G-18): on-demand `StateRequest` pull and the
 * catch-up baseline. A link installed mid-stream issues a StateRequest (spec
 * 20/21 §Pull) and receives a single-wave state-as-delta baseline — stamped
 * with `MessageContext.baseline` (spec 20/22 §Interaction) — ahead of any
 * later live wave, excluded from wave completeness (93 I-24): a glitch-free
 * consumer forwards it as arm state immediately instead of buffering it, and
 * the topology-versioned floor still gates when live multi-edge waves over
 * the post-install topology may complete.
 */
class StatePullTest {

    @Suppress("UNCHECKED_CAST")
    private val propagateSetDelta = (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private data class Arrival(val delta: SetDelta<String>, val ctx: MessageContext)

    private fun link(
        outlet: FanOutlet<Propagate<SetDelta<String>>>,
        gf: GlitchFreeCell<Propagate<SetDelta<String>>>,
    ): Link = (outlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>) as LinkResult.Connected).link

    @Test
    fun `a link installed mid-stream pulls a single-wave baseline ahead of live waves, excluded from completeness`() {
        val gf = GlitchFreeCell(propagateSetDelta)
        val arrivals = mutableListOf<Arrival>()
        gf.outlet.subscribe(
            Use.fixed(
                object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        arrivals += Arrival(value, CurrentContext.get()!!)
                    }
                },
                PortRef.generate(),
            )
        )

        val p1 = SetCell<String>()
        val p2 = SetCell<String>()
        // isolate the new pull path from the pre-existing onLinked push (co-
        // hosted fast path, spec 21) so this test observes StateRequest alone
        p2.outlet.linking.onLinkedListeners.clear()

        link(p1.outlet, gf)
        p1.inlet.call.add("early") // single open edge: flushes as an ordinary live wave
        arrivals.size shouldBe 1
        arrivals[0].ctx.baseline.shouldBeNull()
        tagFold(listOf(arrivals[0].delta)) shouldBe setOf("early")

        // p2 accumulates state entirely before it is ever linked
        p2.inlet.call.add("q")
        p2.inlet.call.add("r")

        val linkP2 = link(p2.outlet, gf) // fresh link mid-stream: EdgeOpen fires a StateRequest

        // the baseline reply is delivered synchronously — ahead of any later
        // live wave — and is tagged as a catch-up baseline, not a wave
        arrivals.size shouldBe 2
        val baseline = arrivals[1]
        baseline.ctx.baseline.shouldNotBeNull()
        tagFold(listOf(baseline.delta)) shouldBe setOf("q", "r")

        // excluded from wave completeness: the baseline reply does not
        // satisfy p2's edge for p1's source — a later p1 wave still needs
        // p2's floor-anchored contribution to settle before it can flush
        p1.inlet.call.add("later")
        arrivals.size shouldBe 2 // still pending

        // an absorb-ack is how an edge that structurally never carries a
        // given source's data settles it (spec 20/22 "Completeness over
        // silent or stuck edges") — glitch-freedom resumes over the
        // post-install topology once every open edge is accounted for
        val p1Source = p1.outlet.waveState().sourceId
        val p1HighWater = p1.outlet.waveState().highWater
        ProtocolSupport.of(gf.inlet).deliver(Protocols.Progress, linkP2, Progress(p1Source, p1HighWater))

        arrivals.size shouldBe 3
        arrivals[2].ctx.baseline.shouldBeNull()
        tagFold(listOf(arrivals[2].delta)) shouldBe setOf("later")
    }

    @Test
    fun `since incremental pull returns only the tags beyond the frontier`() {
        val gf = GlitchFreeCell(propagateSetDelta)
        val arrivals = mutableListOf<Arrival>()
        gf.outlet.subscribe(
            Use.fixed(
                object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        arrivals += Arrival(value, CurrentContext.get()!!)
                    }
                },
                PortRef.generate(),
            )
        )

        val producer = SetCell<String>()
        producer.outlet.linking.onLinkedListeners.clear() // isolate the pull path
        producer.inlet.call.add("x")

        link(producer.outlet, gf)
        arrivals.size shouldBe 1
        val fullFrontier = arrivals[0].ctx.baseline!!

        producer.inlet.call.add("y")

        // a second requester, incremental since the first baseline's frontier
        val probe = FanInlet(propagateSetDelta)
        val incremental = mutableListOf<Arrival>()
        probe.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                incremental += Arrival(value, CurrentContext.get()!!)
            }
        })
        val probeLink = (producer.outlet.linkTo(probe as LinkFrom<Propagate<SetDelta<String>>>) as LinkResult.Connected).link

        Protocols.sendUpstream(probeLink, Protocols.StateRequest, StateRequest(probe.ref, fullFrontier))

        incremental.size shouldBe 1
        incremental[0].ctx.baseline.shouldNotBeNull()
        tagFold(listOf(incremental[0].delta)) shouldBe setOf("y")
        incremental[0].delta.adds.containsKey("x").shouldBeFalse()
    }
}
