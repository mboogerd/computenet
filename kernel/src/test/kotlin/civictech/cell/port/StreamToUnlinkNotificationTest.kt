package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.AttentionAggregator
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionSupport
import civictech.cell.link.Link
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
 * The three tests split into two jobs, deliberately:
 *
 * - The first two pin the FIX: each closes/supersedes a bypass-path streamTo
 *   link and asserts the source-side multicast reports it. Both fail against
 *   the unfixed code (`expected [<id>] but was []`).
 * - The third is the acceptance's named case and pins a NEGATIVE FINDING: the
 *   bead's headline consequence — a permanently stranded attention frontier
 *   slot — is **false as stated**, and this test records why, so a later change
 *   that makes it true is caught here rather than in production. See its own
 *   doc comment.
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
     * attached to the link; that is filed as this item's residual and is not
     * reachable on the bypass path at all. Read the assertions below as the
     * measurement the finding was taken from, not as a regression guard.
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
}
