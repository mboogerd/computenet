package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.proxy.Invocation
import civictech.gen.wire.NatureAxis
import civictech.gen.wire.NatureVector
import civictech.gen.wire.Ownership
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
 * — once admitted — announces itself as an [LinkRole.Observe] link that appears
 * in the topology yet is **never** an expected sibling of the wave-completeness
 * frontier.
 *
 * Two controls diverge from the pinned behavior: (a) the historic bypass
 * (`negotiated = false`) admits despite a deny-all policy; (b) an edge counted as
 * [LinkRole.Consume] rather than Observe wedges the join forever.
 */
class NegotiatedAttachmentTest {

    private val consumerInt =
        @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    private fun denyAll(reason: String): LinkPolicy = LinkPolicy { LinkResult.Rejected(reason) }

    /** A reactive data invocation carrying its wave context (sourcePort + timestamp). */
    private fun wave(sourcePort: PortRef, sourceId: UUID, counter: Long): Invocation =
        Invocation(
            methodName = "provide",
            parameterTypes = listOf("java.lang.Object"),
            args = listOf(counter.toInt()),
            context = MessageContext(Timestamp(sourceId, counter), sourcePort),
        )

    private fun linkFrom(source: PortRef, sink: PortRef, linkRole: LinkRole): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from: PortRef = source
        override val to: PortRef = sink
        override val role: LinkRole = linkRole
        override fun unlink() {}
    }

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
    fun `an Observe edge is absent from the frontier's expected edges - the wave releases`() {
        val delivered = mutableListOf<Invocation>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet = FanInlet.create<Consumer<Int>>()
        frontier.attach(inlet) { delivered += it }

        val producer = PortRef.generate()
        val observer = PortRef.generate()
        // a Consume edge that carries the source's waves, plus an Observe edge
        // (a tap) that never will — both announced via EdgeOpen.
        ProtocolSupport.of(inlet).deliver(
            Protocols.TopologyOrder, linkFrom(producer, inlet.ref, LinkRole.Consume), EdgeOpen,
        )
        ProtocolSupport.of(inlet).deliver(
            Protocols.TopologyOrder, linkFrom(observer, inlet.ref, LinkRole.Observe), EdgeOpen,
        )

        val source = UUID.randomUUID()
        frontier.offer(wave(producer, source, 1L))

        // the Observe edge is not an expected sibling, so the wave releases even
        // though the tap edge never settles the source.
        delivered.size shouldBe 1
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

    @Test
    fun `control (b) - an edge counted as Consume rather than Observe wedges the join`() {
        val delivered = mutableListOf<Invocation>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet = FanInlet.create<Consumer<Int>>()
        frontier.attach(inlet) { delivered += it }

        val producer = PortRef.generate()
        val observer = PortRef.generate()
        ProtocolSupport.of(inlet).deliver(
            Protocols.TopologyOrder, linkFrom(producer, inlet.ref, LinkRole.Consume), EdgeOpen,
        )
        // the SAME topology, but the tap edge mislabelled Consume: now it IS an
        // expected sibling, and it never carries the source — the join wedges.
        ProtocolSupport.of(inlet).deliver(
            Protocols.TopologyOrder, linkFrom(observer, inlet.ref, LinkRole.Consume), EdgeOpen,
        )

        val source = UUID.randomUUID()
        frontier.offer(wave(producer, source, 1L))

        // never releases: the phantom sibling never settles
        delivered.size shouldBe 0
    }
}
