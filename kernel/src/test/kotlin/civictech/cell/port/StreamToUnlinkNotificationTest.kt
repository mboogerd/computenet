package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.AttentionAggregator
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionSupport
import civictech.cell.link.Link
import civictech.cell.link.LinkRole
import civictech.cell.link.LinkSupport
import civictech.cell.link.Linked
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-9wpa — `FanOutlet.streamTo`'s bypass path removes a link record at
 * TWO sites without firing `LinkSupport.onUnlinkListeners`: the link's own
 * teardown, and the supersession eviction for a re-stream over the same
 * [PortRef]. `LinkSupport.remove` is a bare map delete, so a subscriber that
 * keys state by [Link.id] — today only `AttentionSupport.wire`'s band-frontier
 * GC — never learns the id died.
 *
 * The four tests split into three jobs, deliberately:
 *
 * - The first two pin the FIX: each closes/supersedes a bypass-path streamTo
 *   link and asserts the source-side multicast reports it. Both fail against
 *   the unfixed code (`expected [<id>] but was []`).
 * - The third is computenet-9wpa's named case and RECORDS a NEGATIVE FINDING:
 *   the bead's headline consequence — a permanently stranded attention frontier
 *   slot — is **false as stated** on the bypass path. It does not guard it; see
 *   its own doc comment for the measurement.
 * - The fourth is the GUARD (computenet-1632): the same frontier-GC question
 *   asked on the NEGOTIATED streamTo branch, the only branch where an
 *   attention-wired consumer can sit at the far end of the link. It is red
 *   under a named mutation of the retraction it covers; see its doc comment.
 */
class StreamToUnlinkNotificationTest {

    private fun sink() = object : Propagate<String> {
        override fun propagate(value: String) = Unit
    }

    /** Collects every link the source-side infrastructure multicast reports. */
    private fun FanOutlet<Propagate<String>>.recordUnlinks(): MutableList<UUID> =
        mutableListOf<UUID>().also { seen -> linking.onUnlinkListeners += { seen += it.id } }

    @Test
    fun `unlinking a streamTo link reports it on the source-side unlink multicast`() {
        val outlet = FanOutlet.create<Propagate<String>>()
        val seen = outlet.recordUnlinks()

        val link = outlet.streamTo(sink())
        seen shouldBe emptyList()

        link.unlink()

        // the record is gone from the topology ...
        outlet.linking.links.isEmpty() shouldBe true
        // ... and, unlike before computenet-9wpa, every id-keyed subscriber is told
        seen shouldContainExactly listOf(link.id)
    }

    @Test
    fun `re-streaming over the same ref reports the superseded link on the unlink multicast`() {
        val outlet = FanOutlet.create<Propagate<String>>()
        val at = PortRef.generate()
        val seen = outlet.recordUnlinks()

        val first = outlet.streamTo(sink(), at = at)
        seen shouldBe emptyList()

        val second = outlet.streamTo(sink(), at = at)

        // one attachment, one record — computenet-lioe/T21's invariant, unchanged
        outlet.linking.links.map { it.id } shouldContainExactly listOf(second.id)
        // and the id that died in the supersession is retracted, not dropped silently
        seen shouldContainExactly listOf(first.id)
    }

    /** A cell with a registered outlet, so [AttentionSupport.of] can wire its port face. */
    private class Source : Cell {
        override val ref = CellRef(UUID.randomUUID())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())
    }

    /**
     * computenet-9wpa's acceptance case, and the finding it produced: closing a
     * `streamTo` link leaves the producer's attention frontier holding no
     * contribution for the dead link id — **and it held none while the link was
     * live either**.
     *
     * This test PASSES against the unfixed code, measured before the fix, and
     * that is the result rather than a defect in it. The bead was filed on the
     * theory that the silent teardown strands a frontier slot permanently. It
     * cannot, because a bypass-path streamTo link can never acquire one:
     *
     * - `streamTo` builds it as `PortLink(ref, at) { ... }`, the two-argument
     *   constructor, so `fromPort` and `toPort` are both **null**.
     * - `AttentionSupport.wire` guards BOTH ends on port identity — the outlet
     *   face admits an `Attention` report only `if (link.fromPort === port)`,
     *   and the frontier GC retracts only under the same guard. A null
     *   `fromPort` is never `===` a port.
     * - The link is registered on the SOURCE side only (the target is a bare
     *   `Api` object, not a `Linked` port), so the consumer's `emitUpstream`,
     *   which walks its own `linking.links` for `link.toPort === port`, has no
     *   record to send over in the first place.
     *
     * So the multicast the first two tests restore is, today, a latent trap for
     * a future `onUnlinkListeners` subscriber — not a live attention leak.
     *
     * **This test RECORDS that finding; it does not GUARD it, and no assertion
     * here discriminates.** Measured in review (computenet-9wpa, feature
     * review): building the bypass link as
     * `PortLink(ref, at, fromPort = this as? Port)` — giving it exactly the
     * "real endpoint" whose absence the finding rests on — leaves all three
     * tests green. A frontier slot needs an inbound `Attention` *message* as
     * well as the guard passing, and a bypass target is a bare `Api` with no
     * `AttentionSupport` to send one, so the band stays neutral either way.
     * A test that would go red is one with an attention-wired consumer actually
     * attached to the link; that is not reachable on the bypass path at all,
     * and is the next test, on the negotiated branch (computenet-1632). Read the
     * assertions below as the measurement the finding was taken from, not as a
     * regression guard.
     */
    @Test
    fun `the attention frontier holds no contribution for a streamTo link, live or closed`() {
        val source = Source()
        val attention = AttentionSupport.of(source)
        // Sum makes slot COUNT observable through the band: any contribution at
        // all would lift it off the no-slots neutral NORMAL.
        attention.aggregator = AttentionAggregator.Sum

        // (No consumer is wired here on purpose: a bypass target is a bare `Api`,
        // so there is no far end that could report a band up this link. An
        // unattached attention-wired cell would only look like one.)
        val link: Link = source.outlet.streamTo(sink())
        source.outlet.linking.links.map { it.id } shouldContainExactly listOf(link.id)

        // live: no slot was ever created for this link id (the finding)
        attention.band shouldBe AttentionBand.NORMAL

        link.unlink()

        // closed: still none, and now the record is gone too
        attention.band shouldBe AttentionBand.NORMAL
        source.outlet.linking.links.isEmpty() shouldBe true
    }

    /**
     * A local consumer port that IS the streamed-to `Api` object and is also a
     * [Linked] [Port] — the shape `streamTo`'s negotiated branch requires
     * (`target as? Linked`). The kernel's own `Linked` ports (`FanInlet`,
     * `FeedbackInlet`) are `Use<Api>`, not `Api`, so no production port type
     * takes this branch from a plain `streamTo(inlet)`; the test supplies the
     * minimal one. Received values are discarded — only the link matters here.
     */
    private class TapPort : Propagate<String>, Linked, Port {
        override val ref: PortRef = PortRef.generate()
        override val linking = LinkSupport()
        override fun propagate(value: String) = Unit
    }

    /** An attention-wired consumer: [AttentionSupport.of] wires [tap]'s inlet face. */
    private class TapConsumer : Cell {
        override val ref = CellRef(UUID.randomUUID())
        val tap = registerPort("tap", TapPort())
    }

    /**
     * computenet-1632 — the guard the bypass path cannot host. An attention-wired
     * consumer is attached to a `streamTo` link on the NEGOTIATED branch, where
     * `handshake` builds the link with real endpoints (`fromPort` = the outlet,
     * `toPort` = the consumer's port) and registers it on both sides, so the
     * consumer's band genuinely travels up it and keys a frontier slot by the
     * link's id. Closing the link must retract that slot.
     *
     * Observable: the producer's [AttentionBand] under [AttentionAggregator.Sum]
     * with no own level. The producer's only possible contributor is this one
     * link, so the band is a direct read of the frontier: HIGH iff the slot
     * holding the consumer's 1.0 is present, neutral NORMAL iff the frontier is
     * empty (Sum over no slots is `null`). The expected values are literal
     * bands, not recomputed through the aggregator or quantizer.
     *
     * Mutation (measured, computenet-1632, darwin/arm64): deleting the
     * `frontier.onUnlink(link.id)` retraction in `AttentionSupport.wire`'s
     * `onUnlinkListeners` subscriber (Attention.kt, the `if (frontier.onUnlink
     * (link.id)) signal()` line) turns the post-close assertion red —
     * `expected:<NORMAL> but was:<HIGH>`: the dead link id keeps its slot. The
     * other named mutation, dropping `Handshake.kt`'s source-side
     * `sourceLinking?.let { failures.multicast(it.onUnlinkListeners, link) }`
     * teardown multicast, reaches the same assertion by the same route (no
     * source-side notification ⇒ no retraction), but Handshake.kt is outside
     * this item's file claim and was left for the reviewer to run.
     */
    @Test
    fun `a negotiated streamTo link to an attention-wired consumer retracts its frontier slot on close`() {
        val source = Source()
        val attention = AttentionSupport.of(source)
        attention.aggregator = AttentionAggregator.Sum

        val consumer = TapConsumer()
        AttentionSupport.of(consumer).attend(1f)

        // before any link: nothing downstream, neutral
        attention.band shouldBe AttentionBand.NORMAL

        // default `negotiated = true`, and the target is a local Linked port
        val link: Link = source.outlet.streamTo(consumer.tap, at = consumer.tap.ref)

        // preconditions: this really is the negotiated branch, not the bypass
        (link.fromPort === source.outlet) shouldBe true
        (link.toPort === consumer.tap) shouldBe true
        link.role shouldBe LinkRole.Observe
        source.outlet.linking.links.map { it.id } shouldContainExactly listOf(link.id)
        consumer.tap.linking.links.map { it.id } shouldContainExactly listOf(link.id)

        // live: the consumer's inlet face reported its band on link, so the
        // producer's frontier holds the consumer's contribution for `link.id`
        attention.band shouldBe AttentionBand.HIGH

        link.unlink()

        // closed: the record is gone on both sides ...
        source.outlet.linking.links.isEmpty() shouldBe true
        consumer.tap.linking.links.isEmpty() shouldBe true
        // ... and the dead link id's contribution is retracted: back to neutral
        attention.band shouldBe AttentionBand.NORMAL
    }
}
