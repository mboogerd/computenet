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
import org.junit.jupiter.api.assertThrows
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

    // ---- computenet-dmkp: the retraction must not transiently empty the frontier ----

    /**
     * computenet-dmkp — a relink is not a band event. `evictSuperseded` removes
     * the superseded record and (since computenet-4jpd) retracts its frontier
     * slot; if that retraction runs BEFORE the replacement's
     * `onLinkedListeners` report, a source whose only downstream link is being
     * relinked folds over ZERO slots in between, so `recompute` takes the
     * `null` branch, the band flaps to neutral `NORMAL`, and `onBandChange` +
     * `emitUpstream` fire — then the replacement restores it. Measured on the
     * unfixed code, this list was `[NORMAL, HIGH]`.
     *
     * The pin is the transition LIST, not the final band: the flap is
     * self-correcting inside the same handshake call, so an end-state assertion
     * cannot see it.
     */
    @Test
    fun `relinking the sole downstream link of a source at a non-neutral band produces no band transition`() {
        val source = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        val sinkAttention = AttentionSupport.of(sink)

        sinkAttention.attend(1f)
        linkStages(source, sink)
        sourceAttention.band shouldBe AttentionBand.HIGH

        val transitions = mutableListOf<AttentionBand>()
        sourceAttention.onBandChange { transitions += it }

        linkStages(source, sink) // supersedes the sole downstream link

        transitions shouldBe emptyList()
        sourceAttention.band shouldBe AttentionBand.HIGH
    }

    // ---- computenet-3e35: the swap must not transiently DOUBLE the edge either ----

    /**
     * computenet-3e35 — the other side of computenet-dmkp's window. Deferring
     * the retraction past the replacement's report took the frontier
     * old → old+new → new: never empty, but holding TWO slots for one edge in
     * between. `Max` cannot see that (both slots carry the same level); `Sum`
     * can, because it counts. The sink sits at LOW and reports 0.25, so one
     * slot folds to LOW and two fold to 0.5 = NORMAL. Measured on the code
     * before the slot swap was made atomic, this list was `[NORMAL, LOW]` — a
     * spurious `onBandChange` and `emitUpstream` pair per relink.
     */
    @Test
    fun `relinking the sole downstream link of a Sum-aggregating source produces no band transition`() {
        val source = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        sourceAttention.aggregator = AttentionAggregator.Sum
        AttentionSupport.of(sink).attend(0.2f) // LOW; reports 0.25 upstream

        linkStages(source, sink)
        sourceAttention.band shouldBe AttentionBand.LOW

        val transitions = mutableListOf<AttentionBand>()
        sourceAttention.onBandChange { transitions += it }

        linkStages(source, sink) // supersedes the sole downstream link

        transitions shouldBe emptyList()
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    // ---- computenet-1rvt: a throwing onLinkedListener must not strand the superseded slot ----

    private class ListenerBoom : RuntimeException("onLinkedListener failure (computenet-1rvt)")

    /**
     * computenet-1rvt — the throwing listener runs AFTER `AttentionSupport`'s
     * inlet-face report (the order a listener added after spawn gets). The
     * replacement's slot is therefore established before the throw, and the
     * superseded record's retraction multicast MUST still run: the recording
     * subscriber is the id-keyed witness that it did. Before the guard, the
     * throw skipped the deferred multicast entirely, so no retraction was
     * observed and (before computenet-3e35) the frontier would keep both slots —
     * `Sum` over 0.25 + 0.25 reads NORMAL.
     */
    @Test
    fun `a throwing onLinkedListener after the attention report still retracts the superseded record`() {
        val source = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        sourceAttention.aggregator = AttentionAggregator.Sum
        AttentionSupport.of(sink).attend(0.2f) // LOW; reports 0.25 upstream

        val first = (linkStages(source, sink) as LinkResult.Connected).link
        val retracted = mutableListOf<UUID>()
        source.outlet.linking.onUnlinkListeners += { retracted += it.id }
        sink.inlet.linking.onLinkedListeners += { throw ListenerBoom() }

        assertThrows<ListenerBoom> { linkStages(source, sink) }

        retracted shouldBe listOf(first.id)
        source.outlet.linking.links.size shouldBe 1
        // exactly one 0.25 slot: zero would be neutral NORMAL, two would be 0.5 = NORMAL
        sourceAttention.band shouldBe AttentionBand.LOW
    }

    /**
     * computenet-1rvt — the throwing listener runs BEFORE `AttentionSupport`'s
     * report. Unguarded, the throw skipped both the report and the
     * retraction, so the superseded record's slot outlived its link under a
     * dead id: unlinking the live link afterwards retracted nothing, and the
     * source stayed HIGH on a contribution from an edge that no longer exists
     * (measured before the guard: `expected:<NORMAL> but was:<HIGH>`). The
     * guard isolates each listener, so the report still lands and takes the
     * slot over (no band flap: a bare try/finally that only ran the retraction
     * would empty the frontier and flap to NORMAL), the retraction still runs,
     * and the failure is rethrown afterwards.
     */
    @Test
    fun `a throwing onLinkedListener before the attention report leaves one live slot`() {
        val source = Stage()
        val sink = Stage()
        val sourceAttention = AttentionSupport.of(source)
        sourceAttention.aggregator = AttentionAggregator.Sum
        AttentionSupport.of(sink).attend(1f) // HIGH; reports 1.0 upstream

        val first = (linkStages(source, sink) as LinkResult.Connected).link
        sourceAttention.band shouldBe AttentionBand.HIGH
        val retracted = mutableListOf<UUID>()
        source.outlet.linking.onUnlinkListeners += { retracted += it.id }
        sink.inlet.linking.onLinkedListeners.add(0) { throw ListenerBoom() }

        val transitions = mutableListOf<AttentionBand>()
        sourceAttention.onBandChange { transitions += it }

        assertThrows<ListenerBoom> { linkStages(source, sink) }

        retracted shouldBe listOf(first.id)
        transitions shouldBe emptyList() // the replacement reported: never empty, never doubled

        // the one slot left is the LIVE link's: closing that edge empties the frontier
        source.outlet.linking.links.single().unlink()
        sourceAttention.band shouldBe AttentionBand.NORMAL
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
