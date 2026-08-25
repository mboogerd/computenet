package civictech.cell.link

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.control.AttentionAggregator
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionSupport
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-lioe — re-linking the same producer to the same consumer ref must
 * leave ONE link record, matching the one attachment the outlet can hold.
 *
 * `LinkSupport.active` is keyed by a random `Link.id` while
 * `FanOutlet.consumers` is keyed by [civictech.cell.port.PortRef], so before
 * the fix a relink registered a sibling record for an attachment that had been
 * REPLACED. Every consumer of `linking.links` then counted the corpse:
 * `Protocols.relay` de-duplicates by `link.id` and so relays a protocol
 * message once per record, `FanOutlet.absorbAck` emits one `Progress` per
 * record, and `Attention`/`IntakeControl`/`TopologyWalks`/`CompositeCell`'s
 * stall notices all fan over it. This is the general-path pin; the wire-facing
 * consequence is pinned by `TrustBoundaryTest`'s computenet-hil6 case.
 *
 * The last three tests are the discriminators: the eviction key is the whole
 * `(from, to, role)` triple, so it must NOT collapse a second consumer, a
 * second producer into one fan-in inlet, or an `Observe` tap that coexists
 * with a `Consume` subscription over the same pair.
 */
class LinkSupersessionTest {

    private fun collectingInlet(received: MutableList<String>): FanInlet<Consumer<String>> =
        FanInlet.create<Consumer<String>>().apply {
            serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }

    @Suppress("UNCHECKED_CAST")
    private fun link(outlet: FanOutlet<Consumer<String>>, inlet: FanInlet<Consumer<String>>) =
        outlet.linkTo(inlet as LinkFrom<Consumer<String>>)

    @Test
    fun `re-linking the same producer to the same consumer leaves one record on both sides`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val received = mutableListOf<String>()
        val inlet = collectingInlet(received)

        repeat(3) { link(outlet, inlet) }

        // one record per side, matching the single consumer attachment the
        // outlet's `consumers` map can hold for one ref
        outlet.linking.links.size shouldBe 1
        inlet.linking.links.size shouldBe 1
        outlet.linking.links.single().to shouldBe inlet.ref

        // and the attachment itself is single: one emission, one delivery
        outlet.call.provide("once")
        received shouldBe listOf("once")
    }

    @Test
    fun `the surviving record is the most recent one, and it is the live link`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val inlet = collectingInlet(mutableListOf())

        val first = (link(outlet, inlet) as LinkResult.Connected).link
        val second = (link(outlet, inlet) as LinkResult.Connected).link

        outlet.linking.links.single() shouldBe second
        inlet.linking.links.single() shouldBe second
        outlet.linking.links.single().id shouldBe second.id
        (second.id == first.id) shouldBe false
    }

    @Test
    fun `distinct consumers of one outlet each keep their own record`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val a = collectingInlet(mutableListOf())
        val b = collectingInlet(mutableListOf())

        link(outlet, a)
        link(outlet, b)

        outlet.linking.links.map { it.to } shouldContainExactlyInAnyOrder listOf(a.ref, b.ref)
    }

    @Test
    fun `distinct producers into one fan-in inlet each keep their own record`() {
        // The case an evict-by-`to`-alone rule would destroy: on the TARGET
        // side every link shares `to` (the inlet's own ref) and differs only in
        // `from`, so `to` alone is not the supersession key there.
        val left = FanOutlet.create<Consumer<String>>()
        val right = FanOutlet.create<Consumer<String>>()
        val received = mutableListOf<String>()
        val inlet = collectingInlet(received)

        link(left, inlet)
        link(right, inlet)

        inlet.linking.links.map { it.from } shouldContainExactlyInAnyOrder listOf(left.ref, right.ref)

        left.call.provide("l")
        right.call.provide("r")
        received shouldContainExactlyInAnyOrder listOf("l", "r")
    }

    // ---- computenet-4jpd: per-`Link.id` listener state across a supersession ----

    /** A cell with registered ports, so [AttentionSupport.of] can wire its port faces. */
    private class Stage : Cell {
        override val ref = CellRef(UUID.randomUUID())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.serve(object : Propagate<String> {
                override fun propagate(value: String) = outlet.call.propagate(value)
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun linkStages(from: Stage, to: Stage) =
        from.outlet.linkTo(to.inlet as LinkFrom<Propagate<String>>)

    /**
     * computenet-4jpd — the counting pin the acceptance names: relinking ONE
     * edge N times leaves exactly ONE live contribution in the producer's
     * attention frontier, not N.
     *
     * `AttentionFrontier` keys a slot by `Link.id` and GCs it only from
     * `LinkSupport.onUnlinkListeners`; `evictSuperseded` drops the superseded
     * record. If the eviction stays silent, each relink strands one immortal
     * slot while the replacement adds another. `Sum` makes the slot COUNT
     * observable through the band: one slot at `LOW.level` (0.25) folds to LOW,
     * three fold to 0.75 and quantize to HIGH.
     */
    @Test
    fun `relinking one edge leaves the attention frontier one contribution, not N`() {
        val source = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        sourceAttention.aggregator = AttentionAggregator.Sum
        AttentionSupport.of(sink).attend(0.2f) // sink sits at LOW; it reports 0.25 upstream

        repeat(3) { linkStages(source, sink) }

        source.outlet.linking.links.size shouldBe 1
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    /**
     * computenet-4jpd — the same defect as the bead states its observable: a
     * band that can only ratchet UP across relinks, because the stale slot
     * holds the superseded link's last level for the life of the port while
     * every subsequent report lands on the replacement's slot.
     */
    @Test
    fun `a superseded link's contribution is retracted, so the band can still fall`() {
        val source = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        val sinkAttention = AttentionSupport.of(sink)

        sinkAttention.attend(1f)
        linkStages(source, sink)
        sourceAttention.band shouldBe AttentionBand.HIGH

        linkStages(source, sink) // supersedes: the first link's id is now dead

        sinkAttention.attend(0.2f) // reported over the live link only
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `an Observe tap and a Consume link over the same pair both survive`() {
        // `consumers` and `taps` are separate maps, so both attachments are
        // live at once — which is why `role` is part of the eviction key.
        val outlet = FanOutlet.create<Consumer<String>>()
        val inlet = collectingInlet(mutableListOf())

        link(outlet, inlet)
        outlet.tap(inlet as Use<Consumer<String>>)

        outlet.linking.links.map { it.role } shouldContainExactlyInAnyOrder
            listOf(LinkRole.Consume, LinkRole.Observe)
    }
}
