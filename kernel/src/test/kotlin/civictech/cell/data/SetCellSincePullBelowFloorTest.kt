package civictech.cell.data

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import civictech.cell.proxy.Invocation
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `[KE3-35]`/`[KE3-36]` (BS-15, feature computenet-9sm.7, task computenet-9sm.7.1): a
 * `StateRequest(since)` that reaches below the responder's **compaction floor** is answered
 * with FULL state — the same reply `since = null` gets — never a partial delta and never a
 * per-source mix of full and partial.
 *
 * ## What "the floor" is here
 *
 * `SetCell` holds no per-source floor field, and deliberately so: `compactBelow` discards whole
 * `dels` entries and records exactly what it discarded into the re-admission fence
 * `ReclaimedDots` (`[24-TAG-04]` clause 2). Decision 9sm.7-D4 therefore DERIVES the floor from
 * the fence — `floor[s]` is the highest counter the fence holds for source `s` — which makes it
 * exact (every discarded tag from `s` is ≤ `floor[s]`) and free of any new snapshot key. A
 * source with no run in the fence has no floor and never triggers the fallback; a source absent
 * from `since` reads as `-1`, `sinceFilter`'s own default, so it is below floor iff the fence
 * holds anything for it.
 *
 * ## The oracle
 *
 * Every case pulls TWICE from two independent probes — once `since = null`, once with the
 * `since` under test — and compares the two replies STRUCTURALLY. `SetDelta` is a data class
 * whose `adds`/`dels` maps are what the wire encodes, so equality of the object is the honest
 * form of `[KE3-36]`'s "byte-equal to the `since = null` reply". The debug log line (9sm.7-D5)
 * is a diagnostic and is deliberately NOT the oracle.
 *
 * The fixture mints 80 counters from the responder's own source into reclaimable entries (40
 * adds at counters 1..40, then 40 removes whose del-dots are counters 41..80, so each `dels`
 * entry is `{add-tag, dot}` and a frontier at 80 covers every one of them), plus live elements
 * at counters 81..95 that no remove ever names.
 */
class SetCellSincePullBelowFloorTest {

    @Suppress("UNCHECKED_CAST")
    private val propagateSetDelta = (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private data class Reply(val delta: SetDelta<String>, val ctx: MessageContext)

    /** The reclaimable prefix (1..80) plus live tail (81..95); compaction is the caller's move. */
    private fun responder(): SetCell<String> {
        val cell = SetCell<String>()
        cell.outlet.linking.onLinkedListeners.clear() // isolate the pull path (StatePullTest's idiom)
        repeat(40) { cell.inlet.call.add("e$it") } // counters 1..40
        repeat(40) { cell.inlet.call.remove("e$it") } // del-dots, counters 41..80
        repeat(15) { cell.inlet.call.add("live$it") } // counters 81..95, never removed
        return cell
    }

    /** One pull from a fresh probe. Each case uses independent probes so no reply is folded twice. */
    private fun pull(responder: SetCell<String>, since: TagFrontier?): Reply {
        val probe = FanInlet(propagateSetDelta)
        val got = mutableListOf<Reply>()
        probe.serve(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                got += Reply(value, CurrentContext.get()!!)
            }
        })
        val link = (
            responder.outlet.linkTo(probe as LinkFrom<Propagate<SetDelta<String>>>) as LinkResult.Connected
            ).link
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, since))
        got.size shouldBe 1
        return got.single()
    }

    /** The responder's own tag source, read off a tag of its full-state reply. */
    private fun sourceOf(reply: Reply): UUID =
        (reply.delta.adds.values + reply.delta.dels.values).first { it.isNotEmpty() }.first().sourceId

    private fun deliverRemote(cell: SetCell<String>, delta: SetDelta<String>) {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        Invocation.of(propagate, arrayOf(delta), null).invoke(cell.deltaInlet.call)
    }

    /** `[KE3-36]`: same `adds`/`dels`, same reported frontier — the full-state reply verbatim. */
    private fun assertSameAsFull(full: Reply, actual: Reply) {
        actual.ctx.baseline.shouldNotBeNull()
        actual.delta shouldBe full.delta
        actual.ctx.baseline shouldBe full.ctx.baseline
    }

    /**
     * BS-15 arm A — the floor arrives through `compactBelow` directly: `since = {X→40}` is below
     * the floor of 80, so the reply is the full-state reply and the requester's fold equals the
     * responder's membership (`[KE3-35]`).
     */
    @Test
    fun `arm A - a below-floor since after compactBelow is answered with the full-state reply`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))

        responder.compactBelow(TagFrontier(mapOf(x to 80L))) shouldBe 120 // 80 del-tags + the 40 adds they cover

        val full = pull(responder, null)
        val below = pull(responder, TagFrontier(mapOf(x to 40L)))

        assertSameAsFull(full, below)
        tagFold(listOf(below.delta)) shouldBe responder.membership()
    }

    /**
     * BS-15 arm B — the same, reached through the production trigger: the installed stability
     * read compacts inside `snapshot()` (`SetCell`'s "THE RECLAIMER'S ONLY PRODUCTION CALLER").
     */
    @Test
    fun `arm B - a below-floor since after compaction through onStability-snapshot is answered with full state`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))

        responder.onStability { TagFrontier(mapOf(x to 80L)) }
        responder.snapshot()

        val full = pull(responder, null)
        val below = pull(responder, TagFrontier(mapOf(x to 40L)))

        assertSameAsFull(full, below)
        tagFold(listOf(below.delta)) shouldBe responder.membership()
    }

    /**
     * The unchanged path: every source at or above its floor still gets the since-filtered
     * partial, exactly as today. The expectation is derived independently — from the tags of the
     * full reply — rather than copied from the reply under test.
     */
    @Test
    fun `at or above the floor the reply is the since-filtered partial, as today`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        responder.compactBelow(TagFrontier(mapOf(x to 80L)))

        val full = pull(responder, null)
        val partial = pull(responder, TagFrontier(mapOf(x to 90L)))

        val expectedAdds = full.delta.adds
            .mapValues { (_, tags) -> tags.filter { it.counter > 90L }.toSet() }
            .filterValues { it.isNotEmpty() }
        val expectedDels = full.delta.dels
            .filterValues { tags -> tags.any { it.counter > 90L } }
            .mapValues { it.value }
        partial.delta.adds shouldBe expectedAdds
        partial.delta.dels shouldBe expectedDels
        partial.delta shouldNotBe full.delta
        expectedAdds.keys shouldBe setOf("live10", "live11", "live12", "live13", "live14")
    }

    /**
     * Mixed sources (the feature's third example): ONE source below its floor is enough — there
     * is no per-source mixing of full and partial, which would be a silently widened partial.
     * Source Y's tags are never reclaimed, so Y has no floor and never triggers the fallback on
     * its own.
     */
    @Test
    fun `mixed sources - one source below floor makes the whole reply full, the other alone does not`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        val y = UUID.randomUUID()
        deliverRemote(responder, SetDelta(adds = mapOf("y1" to setOf(Timestamp(y, 10)))))
        responder.compactBelow(TagFrontier(mapOf(x to 80L)))

        val full = pull(responder, null)

        // X below its floor ⇒ FULL, even though Y is satisfied.
        assertSameAsFull(full, pull(responder, TagFrontier(mapOf(x to 40L, y to 10L))))

        // X above the floor and Y unfenced ⇒ partial, as today.
        val partial = pull(responder, TagFrontier(mapOf(x to 90L, y to 10L)))
        partial.delta shouldNotBe full.delta
        partial.delta.adds.containsKey("y1") shouldBe false
    }

    /** A source absent from `since` reads as -1, so it is below any floor the fence holds. */
    @Test
    fun `a source absent from since but present in the fence counts as below floor`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        responder.compactBelow(TagFrontier(mapOf(x to 80L)))

        val full = pull(responder, null)
        assertSameAsFull(full, pull(responder, TagFrontier(emptyMap())))
    }

    /** No compaction ⇒ no floor ⇒ the since-filtered partial, untouched by this feature. */
    @Test
    fun `with no compaction at all a since pull is filtered exactly as before`() {
        val responder = responder()
        val full = pull(responder, null)
        val x = sourceOf(full)

        val partial = pull(responder, TagFrontier(mapOf(x to 40L)))
        partial.delta shouldNotBe full.delta
        partial.delta.adds.keys shouldBe (0 until 15).map { "live$it" }.toSet()
    }

    /**
     * The floor survives `snapshot()`/`restore` with no snapshot key of its own: the restored
     * fence rebuilds it (9sm.7-D4), so the restored replica still refuses to answer a below-floor
     * `since` incrementally.
     */
    @Test
    fun `the floor survives snapshot-restore and still forces the full-state reply`() {
        val origin = responder()
        val x = sourceOf(pull(origin, null))
        origin.compactBelow(TagFrontier(mapOf(x to 80L)))

        val restored = SetCell<String>()
        restored.outlet.linking.onLinkedListeners.clear()
        restored.restore(origin.snapshot())

        val full = pull(restored, null)
        val below = pull(restored, TagFrontier(mapOf(x to 40L)))

        assertSameAsFull(full, below)
        tagFold(listOf(below.delta)) shouldBe restored.membership()
    }
}
