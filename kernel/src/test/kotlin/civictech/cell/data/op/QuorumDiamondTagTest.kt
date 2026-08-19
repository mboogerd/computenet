package civictech.cell.data.op

import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.tagFold
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * computenet-s6l2: the [IntersectDiamondTagTest] shape, applied to
 * [QuorumSetCell] — the last operator still borrowing its downstream tags from
 * its inputs (`AdvertisedLedger`).
 *
 * Set algebra is not in dispute: with an intersection threshold `{ n -> n }`,
 * `A ∪ quorum(A, B) = A ∪ (A ∩ B) = A`, so an element live in `A` must stay in
 * the union after it drops below the quorum's threshold.
 *
 * The rig is [QuorumSetCellTest]'s, not a host/`SimulationController` one: a
 * bare `outlet.linkTo(quorum.inlet)` *does* deliver the inlet's
 * `TopologyOrder` `EdgeOpen`, so lanes open and the threshold is met (verified
 * by every case below emitting). The bead's hand-wired probe that "emitted
 * nothing at all" was a probe defect, not a missing host.
 */
class QuorumDiamondTagTest {

    @Suppress("UNCHECKED_CAST")
    private fun feed(source: SetCell<String>, port: Any) {
        source.outlet.linkTo(port as LinkFrom<Propagate<SetDelta<String>>>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun chain(from: QuorumSetCell<String>, port: Any) {
        from.outlet.linkTo(port as LinkFrom<Propagate<SetDelta<String>>>)
    }

    private fun collect(outlet: Subscribe<Propagate<SetDelta<String>>>): MutableList<SetDelta<String>> {
        val collected = mutableListOf<SetDelta<String>>()
        outlet.subscribe(
            Use.fixed(
                object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        collected += value
                    }
                },
                PortRef.generate(),
            ),
        )
        return collected
    }

    @Test
    fun `union of a source with a quorum over that source keeps the source's elements`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val quorum = QuorumSetCell.intersection<String>()
        val union = UnionSetCell<String>()

        // the diamond: A reaches the union twice — directly, and through the quorum
        feed(a, union.inlet)
        feed(a, quorum.inlet)
        feed(b, quorum.inlet)
        chain(quorum, union.inlet)

        val quorumOut = collect(quorum.outlet)
        val out = collect(union.outlet)

        a.inlet.call.add("e")
        b.inlet.call.add("e")               // "e" now meets the 2-of-2 threshold
        assertEquals(setOf("e"), tagFold(quorumOut), "the quorum must actually admit e — else the case proves nothing")
        assertEquals(setOf("e"), tagFold(out))

        b.inlet.call.remove("e")            // "e" drops below threshold, but is still live in A

        assertEquals(
            setOf("e"),
            tagFold(out),
            "A ∪ quorum(A, B) must still hold e: the direct A edge is live, only the quorum leg exited",
        )
    }

    @Test
    fun `control - a union over a distinct source and a quorum is unaffected`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        val quorum = QuorumSetCell.intersection<String>()
        val union = UnionSetCell<String>()

        // same shape, but the union's other leg shares no tag with the quorum
        feed(c, union.inlet)
        feed(a, quorum.inlet)
        feed(b, quorum.inlet)
        chain(quorum, union.inlet)

        val out = collect(union.outlet)

        c.inlet.call.add("e")
        a.inlet.call.add("e")
        b.inlet.call.add("e")
        assertEquals(setOf("e"), tagFold(out))

        b.inlet.call.remove("e")

        assertEquals(setOf("e"), tagFold(out), "C ∪ quorum(A, B) keeps e via C")
    }

    /**
     * 21 §Tag hygiene, independent of any diamond: a quorum's membership flips
     * ON when *another* lane asserts the element, so the flip-ON does not ride
     * a fresh input add-tag on the flipping element, and a re-entry
     * re-advertises a tag a previous exit already deleted.
     */
    @Test
    fun `re-entry after dropping below threshold is still live downstream`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val quorum = QuorumSetCell.intersection<String>()
        val view = UnionSetCell<String>()

        feed(a, quorum.inlet)
        feed(b, quorum.inlet)
        chain(quorum, view.inlet)

        val out = collect(view.outlet)

        a.inlet.call.add("e")
        b.inlet.call.add("e")
        assertEquals(setOf("e"), tagFold(out))

        b.inlet.call.remove("e")            // below threshold
        assertEquals(emptySet<String>(), tagFold(out))

        b.inlet.call.add("e")               // re-enters the quorum

        assertEquals(setOf("e"), tagFold(out), "re-entry must not be swallowed by a stale tombstone")
    }

    /**
     * The lowered-threshold variant: `e` enters the quorum on a lane that is
     * not `A`, so the tag the quorum advertises for it is `B`'s — and yet
     * `A`'s tag is in the advertised union too, because
     * `PresenceLanes.tags(e)` unions *every* live lane's tags for `e`. Exiting
     * therefore still deletes `A`'s tag off the direct edge.
     */
    @Test
    fun `union of a source with a majority quorum over that source keeps the source's elements`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        val quorum = QuorumSetCell.majority<String>()   // 3 lanes -> threshold 2
        val union = UnionSetCell<String>()

        feed(a, union.inlet)
        feed(a, quorum.inlet)
        feed(b, quorum.inlet)
        feed(c, quorum.inlet)
        chain(quorum, union.inlet)

        val out = collect(union.outlet)

        a.inlet.call.add("e")
        b.inlet.call.add("e")               // 2 of 3 — majority met
        assertEquals(setOf("e"), tagFold(out))

        b.inlet.call.remove("e")            // back to 1 of 3, below majority

        assertEquals(
            setOf("e"),
            tagFold(out),
            "A ∪ majority(A, B, C) must still hold e: A alone is not a majority, but the direct A edge is live",
        )
    }
}
