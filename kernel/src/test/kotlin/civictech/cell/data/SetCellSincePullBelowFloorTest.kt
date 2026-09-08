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
 * ## The fixture, and why it carries a RETAINED prefix
 *
 * The responder's own source mints, in order: 20 `keep*` adds at counters 1..20 that no remove
 * ever names; 40 `e*` adds at counters 21..60, each then removed with a del-dot at counters
 * 61..100 (so each `dels` entry is `{add-tag, dot}` and a frontier at 100 covers every one of
 * them); and 15 `live*` adds at counters 101..115. Compacting at 100 discards the 40 `e*`
 * entries and the add-tags under them — floor 100 — while the `keep*` add-tags survive
 * untouched, because a live add-tag is never in a `dels` entry (`SetCellCompactBelowTest`'s
 * "a live add-tag below a reclaimed run is still admitted").
 *
 * **The `keep*` prefix is load-bearing, not decoration.** Without it every retained tag sits
 * ABOVE the floor, so a since-filtered reply at `since = 40` and the full-state reply are
 * identical by accident and the test cannot tell the fallback from its absence. Measured: with
 * the fallback branch mutated out, a `keep`-less fixture left arms A and B, the absent-source
 * case and the restore case ALL GREEN. The `keep*` tags are the only thing in the reply that a
 * partial would drop and the fallback must ship (task computenet-9sm.7.1's mutation check).
 */
class SetCellSincePullBelowFloorTest {

    @Suppress("UNCHECKED_CAST")
    private val propagateSetDelta = (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private data class Reply(val delta: SetDelta<String>, val ctx: MessageContext)

    /** Retained prefix, reclaimable middle, live tail; compaction is the caller's move. */
    private fun responder(): SetCell<String> {
        val cell = SetCell<String>()
        cell.outlet.linking.onLinkedListeners.clear() // isolate the pull path (StatePullTest's idiom)
        repeat(20) { cell.inlet.call.add("keep$it") } // counters 1..20, retained BELOW the floor
        repeat(40) { cell.inlet.call.add("e$it") } // counters 21..60
        repeat(40) { cell.inlet.call.remove("e$it") } // del-dots, counters 61..100
        repeat(15) { cell.inlet.call.add("live$it") } // counters 101..115, never removed
        return cell
    }

    /** The compaction the floor comes from: discards the 40 `e*` entries, floor becomes 100. */
    private val floor = 100L

    /** The `since` under test, far below [floor] and below the retained `keep*` prefix's top. */
    private val belowFloorSince = 40L

    /** A `since` at or above [floor]: the unchanged, since-filtered path. */
    private val aboveFloorSince = 110L

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
     * the floor of 100, so the reply is the full-state reply — the retained `keep*` tags at
     * counters 1..20 included, which a since-filter would have dropped — and the requester's
     * fold equals the responder's membership (`[KE3-35]`).
     */
    @Test
    fun `arm A - a below-floor since after compactBelow is answered with the full-state reply`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))

        // 40 entries x {add-tag, del-dot} = 80 del-tags, plus the 40 add-tags they cover.
        responder.compactBelow(TagFrontier(mapOf(x to floor))) shouldBe 120

        val full = pull(responder, null)
        val below = pull(responder, TagFrontier(mapOf(x to belowFloorSince)))

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

        responder.onStability { TagFrontier(mapOf(x to floor)) }
        responder.snapshot()

        val full = pull(responder, null)
        val below = pull(responder, TagFrontier(mapOf(x to belowFloorSince)))

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
        responder.compactBelow(TagFrontier(mapOf(x to floor)))

        val full = pull(responder, null)
        val partial = pull(responder, TagFrontier(mapOf(x to aboveFloorSince)))

        val expectedAdds = full.delta.adds
            .mapValues { (_, tags) -> tags.filter { it.counter > aboveFloorSince }.toSet() }
            .filterValues { it.isNotEmpty() }
        val expectedDels = full.delta.dels
            .filterValues { tags -> tags.any { it.counter > aboveFloorSince } }
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
        responder.compactBelow(TagFrontier(mapOf(x to floor)))

        val full = pull(responder, null)

        // X below its floor ⇒ FULL, even though Y is satisfied.
        assertSameAsFull(full, pull(responder, TagFrontier(mapOf(x to belowFloorSince, y to 10L))))

        // X above the floor and Y unfenced ⇒ partial, as today.
        val partial = pull(responder, TagFrontier(mapOf(x to aboveFloorSince, y to 10L)))
        partial.delta shouldNotBe full.delta
        partial.delta.adds.containsKey("y1") shouldBe false
    }

    /**
     * A source absent from `since` reads as `-1` — `sinceFilter`'s own default — so it is below
     * any floor the fence holds, and the whole reply goes full.
     *
     * **This needs a SECOND source to be observable at all.** An absent source already receives
     * every one of its tags from the plain filter (`-1 < every counter`), so a `since` naming
     * nothing (`TagFrontier(emptyMap())`) yields the full reply whether or not the fallback
     * fires — it cannot tell the two apart, and the mutation check measured it staying green.
     * The discriminating form pins the *consequence* of the decision instead: X is absent and
     * fenced, Y is named and satisfied, and the fallback is what makes Y's own below-`since`
     * tags ship too, because the fallback replaces the whole `since`, never one source's entry.
     */
    @Test
    fun `a source absent from since but present in the fence counts as below floor`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        val y = UUID.randomUUID()
        deliverRemote(responder, SetDelta(adds = mapOf("y1" to setOf(Timestamp(y, 10)))))
        responder.compactBelow(TagFrontier(mapOf(x to floor)))

        val full = pull(responder, null)

        // X unnamed ⇒ below floor ⇒ FULL, so Y's tag at exactly its `since` ships as well.
        val below = pull(responder, TagFrontier(mapOf(y to 10L)))
        assertSameAsFull(full, below)
        below.delta.adds.containsKey("y1") shouldBe true

        // The degenerate form is kept for the criterion's literal words, and is deliberately
        // not the oracle: an empty `since` is indistinguishable from full either way.
        assertSameAsFull(full, pull(responder, TagFrontier(emptyMap())))
    }

    /** No compaction ⇒ no floor ⇒ the since-filtered partial, untouched by this feature. */
    @Test
    fun `with no compaction at all a since pull is filtered exactly as before`() {
        val responder = responder()
        val full = pull(responder, null)
        val x = sourceOf(full)

        val partial = pull(responder, TagFrontier(mapOf(x to belowFloorSince)))
        partial.delta shouldNotBe full.delta
        // adds at counters ≤ 40 are dropped by the filter, as they always were: the whole
        // `keep*` prefix and the first 20 `e*` add-tags.
        partial.delta.adds.keys shouldBe
            ((20 until 40).map { "e$it" } + (0 until 15).map { "live$it" }).toSet()
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
        origin.compactBelow(TagFrontier(mapOf(x to floor)))

        val restored = SetCell<String>()
        restored.outlet.linking.onLinkedListeners.clear()
        restored.restore(origin.snapshot())

        val full = pull(restored, null)
        val below = pull(restored, TagFrontier(mapOf(x to belowFloorSince)))

        assertSameAsFull(full, below)
        tagFold(listOf(below.delta)) shouldBe restored.membership()
    }
}
