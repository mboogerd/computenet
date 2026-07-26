package civictech.cell.port

import civictech.cell.data.Propagate
import civictech.gen.wire.NatureAxis
import civictech.gen.wire.NatureVector
import civictech.gen.wire.PullService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * FU-5: `PULL_SERVICE` is a link-flow refusing axis. A consumer that pulls its
 * state on open and depends *solely* on that pull for its baseline
 * (`PullOnOpen(requireServing = true)`) requires a producer that serves pulls
 * (`FanOutlet.pullServe`). The two declaration surfaces are existing runtime
 * calls — `pullServe` registration IS the offer (`BASELINE_SERVING`), the
 * opted-in `PullOnOpen` IS the requirement — so no KSP change is needed and a
 * graph that opts into neither reconciles exactly as today.
 *
 * Why the opt-in and not bare `PullOnOpen`: pull-on-open is *not* by itself a
 * hard dependency on serving. A [civictech.cell.consistency.GlitchFreeCell]
 * installs `PullOnOpen()` on every ALIGN inlet against ordinary non-serving
 * upstreams (MapperCell/SourceCell/another glitch-free outlet) and converges via
 * its frontier — the StateRequest is opportunistic, tolerated-unanswered. That
 * profile (plain non-serving producer + `PullOnOpen`) is byte-for-byte the same
 * as scenario (ii) below, so the two are only distinguishable by the consumer
 * *declaring* its dependence — hence `requireServing`. Default false keeps every
 * existing install DEFAULT on the axis ⇒ byte-identical (glitch-free unaffected).
 *
 * Wired onto a non-serving producer, the consumer's `StateRequest` is answered by
 * no one and its state starves *silently* today; as a link-flow axis this becomes
 * a loud typed [LinkResult.Rejected] at the handshake.
 *
 * NOTE (ticket "Watch"): a bridged edge currently reconciles against DEFAULT for
 * its remote endpoint (Link.kt ~:326-332 — carrying the peer's vector across the
 * wire is a known follow-on), so this refusal is live **in-process only**. Not
 * fixed here.
 */
class PullServiceRefusalTest {

    private fun producer() = FanOutlet.create<Propagate<Int>>()

    // Explicit handler object (not an `onEach` SAM lambda): the reflective pull-
    // reply path can only invoke a handler whose class is accessible.
    private fun collectingInlet(into: MutableList<Int>): FanInlet<Propagate<Int>> =
        FanInlet.create<Propagate<Int>>().also { inlet ->
            inlet.serve(object : Propagate<Int> {
                override fun propagate(value: Int) { into += value }
            })
        }

    @Suppress("UNCHECKED_CAST")
    private fun FanOutlet<Propagate<Int>>.link(inlet: FanInlet<Propagate<Int>>): LinkResult =
        linkTo(inlet as LinkFrom<Propagate<Int>>)

    // (i) pull-on-open onto a pull-serving producer: connects, catch-up arrives.
    @Test
    fun `a pull-on-open inlet linked to a pull-serving producer connects and the baseline arrives`() {
        val producer = producer()
        producer.pullServe { req -> at(req.replyTo).propagate(42) } // registration IS the offer

        val collected = mutableListOf<Int>()
        val inlet = collectingInlet(collected)
        inlet.install(PullOnOpen(requireServing = true)) // opt-in IS the requirement

        val result = producer.link(inlet)

        result.shouldBeInstanceOf<LinkResult.Connected>()
        collected shouldBe listOf(42) // the pulled baseline arrived, byte-identical to today
    }

    // (ii) pull-on-open onto a non-serving producer: refused on PULL_SERVICE.
    @Test
    fun `a pull-on-open inlet linked to a non-serving producer is refused on PULL_SERVICE`() {
        val producer = producer() // no pullServe — offers NONE

        val inlet = collectingInlet(mutableListOf())
        inlet.install(PullOnOpen(requireServing = true)) // requires BASELINE_SERVING

        val rejected = producer.link(inlet).shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.mismatch!!.axis shouldBe NatureAxis.PULL_SERVICE
        rejected.mismatch!!.offered shouldBe PullService.NONE
        rejected.mismatch!!.required shouldBe PullService.BASELINE_SERVING
        rejected.reason.contains("PULL_SERVICE") shouldBe true // human reason still populated
    }

    // Control (a): remove PULL_SERVICE from the refusing set and the (ii) scenario
    // reverts to today's *silent* starvation — the link forms and the StateRequest
    // is answered by no one, so the consumer's state stays empty across the run.
    @Test
    fun `control - PULL_SERVICE out of LINK_FLOW_AXES reverts to today's silent starvation`() {
        // pure: with the axis out of the refusing set the same offer-vs-require pair composes
        NatureNegotiation.reconcile(
            offered = NatureVector.of(PullService.NONE),
            required = NatureVector.of(PullService.BASELINE_SERVING),
            linkFlowAxes = NatureNegotiation.LINK_FLOW_AXES - NatureAxis.PULL_SERVICE,
        ).shouldBeInstanceOf<Reconciliation.Direct>()

        // executable: a non-serving producer + a plain inlet link Direct (both
        // DEFAULT); the StateRequest a puller would emit is answered by no one.
        val producer = producer()
        val collected = mutableListOf<Int>()
        val inlet = collectingInlet(collected)

        val link = (producer.link(inlet) as LinkResult.Connected).link
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(inlet.ref, since = null))

        collected.shouldBeEmpty() // starved — the failure the refusal converts to a loud reject
    }

    // Control (b): a plain inlet (no PullOnOpen) onto a non-serving producer is
    // DEFAULT-vs-DEFAULT — links Direct, no StateRequest, byte-identical to today.
    @Test
    fun `control - a plain inlet onto a non-serving producer links Direct, unchanged`() {
        val producer = producer()
        val collected = mutableListOf<Int>()
        val inlet = collectingInlet(collected) // no PullOnOpen

        producer.link(inlet).shouldBeInstanceOf<LinkResult.Connected>()
        collected.shouldBeEmpty() // no pull issued, no refusal
    }
}
