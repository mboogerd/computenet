package civictech.cell.data

import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.StateRequest
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `[24-TAG-04]` (feature computenet-9sm.8, decision 9sm.8-D8, task computenet-9sm.8.5): a
 * `StateRequest(since)` that reaches below the responder's **compaction floor** is answered with
 * FULL state — the same reply `since = null` gets — never a partial delta and never a per-source
 * mix of full and partial. Independently, a `dels[key]` entry ships WHOLE or not at all, never
 * split by dot (`[24-TAG-04]`'s last sentence).
 *
 * The dot-shaped mirror of `SetCellSincePullBelowFloorTest` (task computenet-9sm.7.1) — same
 * fixture shape, same oracle discipline, same reasons for both. See that file's KDoc for the
 * fuller argument; this KDoc states only what differs for the dot-shaped cell.
 *
 * ## The oracle
 *
 * Every below/at-floor case pulls TWICE from independent probes — once `since = null`, once with
 * the `since` under test — and compares the two replies STRUCTURALLY (`TaggedMapDelta` is a data
 * class whose `puts`/`dels` are what the wire encodes). The debug log line (9sm.8-D8, mirroring
 * 9sm.7-D5) is a diagnostic and is deliberately NOT the oracle.
 *
 * **Structural equality between the two replies is not, by itself, proof the fallback ran** — it
 * passes just as well by accident when nothing retained below the `since` would have been dropped
 * by a plain filter (measured on 9sm.7.1 before its RETAINED prefix existed). So every below-floor
 * case additionally asserts the retained `keep*` KEYS BY NAME in the reply under test.
 *
 * ## The fixture, and why it carries a RETAINED prefix
 *
 * The responder's own dot source mints, in order: 20 `keep*` puts at counters 1..20 that no
 * remove ever names (the RETAINED PREFIX BELOW THE FLOOR — load-bearing, not decoration: without
 * it every retained dot sits ABOVE the floor and a since-filtered reply at `since = 40` is
 * identical to the full reply by accident); 40 `e*` puts at counters 21..60, each then removed
 * with a del-dot at counters 61..100 (so each `dels` entry is `{put-dot, del-dot}` and a frontier
 * at 100 covers every one of them — 40 entries x 2 dots = 80 del-dots + 40 covered put-dots = 120
 * dots discarded by `compactBelow`); and 15 `live*` puts at counters 101..115.
 */
class OrMapCellSincePullBelowFloorTest {

    @Suppress("UNCHECKED_CAST")
    private val propagateTaggedMapDelta =
        (Propagate::class.java as Class<Propagate<TaggedMapDelta<String, String>>>)

    private data class Reply(val delta: TaggedMapDelta<String, String>, val ctx: MessageContext)

    /** Retained prefix, reclaimable middle, live tail; compaction is the caller's move. */
    private fun responder(): OrMapCell<String, String> {
        val cell = OrMapCell<String, String>()
        cell.outlet.linking.onLinkedListeners.clear() // isolate the pull path (StatePullTest's idiom)
        repeat(20) { cell.inlet.call.put("keep$it", "v") } // counters 1..20, retained BELOW the floor
        repeat(40) { cell.inlet.call.put("e$it", "v") } // counters 21..60
        repeat(40) { cell.inlet.call.remove("e$it") } // del-dots, counters 61..100
        repeat(15) { cell.inlet.call.put("live$it", "v") } // counters 101..115, never removed
        return cell
    }

    /** The compaction the floor comes from: discards the 40 `e*` entries, floor becomes 100. */
    private val floor = 100L

    /** The `since` under test, far below [floor] and below the retained `keep*` prefix's top. */
    private val belowFloorSince = 40L

    /** A `since` at or above [floor]: the unchanged, since-filtered path. */
    private val aboveFloorSince = 110L

    /** The retained keep keys the below-floor reply must ship BY NAME. */
    private val keepKeys = (0 until 20).map { "keep$it" }.toSet()

    /** One pull from a fresh probe. Each case uses independent probes so no reply is folded twice. */
    private fun pull(responder: OrMapCell<String, String>, since: TagFrontier?): Reply {
        val probe = FanInlet(propagateTaggedMapDelta)
        val got = mutableListOf<Reply>()
        probe.serve(object : Propagate<TaggedMapDelta<String, String>> {
            override fun propagate(value: TaggedMapDelta<String, String>) {
                got += Reply(value, CurrentContext.get()!!)
            }
        })
        val link = (
            responder.outlet.linkTo(probe as LinkFrom<Propagate<TaggedMapDelta<String, String>>>) as LinkResult.Connected
            ).link
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, since))
        got.size shouldBe 1
        return got.single()
    }

    /**
     * A pull that may legitimately draw NO reply at all — `pullServe` ships nothing when both
     * `puts` and `dels` come back empty (`OrMapConvergenceTest`'s "a pull that has nothing beyond
     * the frontier answers nothing at all"), which is exactly what a `since` that already covers
     * every dot in an entry produces once entry-whole hides that entry.
     */
    private fun pullOptional(responder: OrMapCell<String, String>, since: TagFrontier?): Reply? {
        val probe = FanInlet(propagateTaggedMapDelta)
        val got = mutableListOf<Reply>()
        probe.serve(object : Propagate<TaggedMapDelta<String, String>> {
            override fun propagate(value: TaggedMapDelta<String, String>) {
                got += Reply(value, CurrentContext.get()!!)
            }
        })
        val link = (
            responder.outlet.linkTo(probe as LinkFrom<Propagate<TaggedMapDelta<String, String>>>) as LinkResult.Connected
            ).link
        Protocols.sendUpstream(link, Protocols.StateRequest, StateRequest(probe.ref, since))
        (got.size <= 1) shouldBe true
        return got.singleOrNull()
    }

    /** The responder's own dot source, read off a dot of its full-state reply. */
    private fun sourceOf(reply: Reply): UUID =
        (reply.delta.puts.values.flatMap { it.keys } + reply.delta.dels.values.flatten()).first().sourceId

    private fun deliverRemote(cell: OrMapCell<String, String>, delta: TaggedMapDelta<String, String>) {
        cell.deltaInlet.call.propagate(delta)
    }

    /** The full-state reply verbatim: same `puts`/`dels`, same reported frontier. */
    private fun assertSameAsFull(full: Reply, actual: Reply) {
        actual.ctx.baseline.shouldNotBeNull()
        actual.delta shouldBe full.delta
        actual.ctx.baseline shouldBe full.ctx.baseline
    }

    /**
     * Arm A — the floor arrives through `compactBelow` directly: `since = {X->40}` is below the
     * floor of 100, so the reply is the full-state reply — the retained `keep*` dots at counters
     * 1..20 included, which a since-filter would have dropped. Asserts the keep keys BY NAME, not
     * only equality of the two replies.
     */
    @Test
    fun `arm A - a below-floor since after compactBelow is answered with the full-state reply`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))

        // 40 entries x {put-dot, del-dot} = 80 del-dots, plus the 40 put-dots they cover.
        responder.compactBelow(TagFrontier(mapOf(x to floor))) shouldBe 120

        val full = pull(responder, null)
        val below = pull(responder, TagFrontier(mapOf(x to belowFloorSince)))

        assertSameAsFull(full, below)
        below.delta.puts.keys.filter { it.startsWith("keep") }.toSet() shouldBe keepKeys
        keepKeys.forEach { below.delta.membership().contains(it) shouldBe true }
    }

    /**
     * Arm B — the same, reached through the production trigger: the installed stability read
     * compacts inside `snapshot()` (`OrMapCell.compactBelow`'s "THE RECLAIMER'S ONLY PRODUCTION
     * CALLER").
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
        below.delta.puts.keys.filter { it.startsWith("keep") }.toSet() shouldBe keepKeys
    }

    /**
     * At or above the floor the reply is the since-filtered partial, exactly as today — derived
     * independently from the full reply's dots, not copied from the reply under test.
     */
    @Test
    fun `at or above the floor the reply is the since-filtered partial, as today`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        responder.compactBelow(TagFrontier(mapOf(x to floor)))

        val full = pull(responder, null)
        val partial = pull(responder, TagFrontier(mapOf(x to aboveFloorSince)))

        val expectedPuts = full.delta.puts
            .mapValues { (_, dots) -> dots.filterKeys { it.counter > aboveFloorSince } }
            .filterValues { it.isNotEmpty() }
        val expectedDels = full.delta.dels
            .filterValues { dots -> dots.any { it.counter > aboveFloorSince } }
            .mapValues { it.value }
        partial.delta.puts shouldBe expectedPuts
        partial.delta.dels shouldBe expectedDels
        partial.delta shouldNotBe full.delta
        expectedPuts.keys shouldBe setOf("live10", "live11", "live12", "live13", "live14")
    }

    /**
     * Mixed sources: ONE source below its floor is enough to force the whole reply full — there
     * is no per-source mixing of full and partial. Source Y's dots are never reclaimed, so Y has
     * no floor and never triggers the fallback on its own.
     */
    @Test
    fun `mixed sources - one source below floor makes the whole reply full, the other alone does not`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        val y = UUID.randomUUID()
        deliverRemote(responder, TaggedMapDelta(puts = mapOf("y1" to mapOf(Timestamp(y, 10) to "yv"))))
        responder.compactBelow(TagFrontier(mapOf(x to floor)))

        val full = pull(responder, null)

        // X below its floor -> FULL, even though Y is satisfied.
        val below = pull(responder, TagFrontier(mapOf(x to belowFloorSince, y to 10L)))
        assertSameAsFull(full, below)
        below.delta.puts.keys.filter { it.startsWith("keep") }.toSet() shouldBe keepKeys

        // X above the floor and Y unfenced -> partial, as today.
        val partial = pull(responder, TagFrontier(mapOf(x to aboveFloorSince, y to 10L)))
        partial.delta shouldNotBe full.delta
        partial.delta.puts.containsKey("y1") shouldBe false
    }

    /**
     * A source absent from `since` reads as `-1` — `putsSince`/`delsSince`'s own default — so it
     * is below any floor the fence holds, and the whole reply goes full. Needs a second source to
     * be observable at all (an absent-and-unfenced source already receives every dot from the
     * plain filter): X is absent and fenced, Y is named and satisfied, and the fallback is what
     * makes Y's own below-`since` dots ship too, because it replaces the whole `since`, never one
     * source's entry.
     */
    @Test
    fun `a source absent from since but present in the fence counts as below floor`() {
        val responder = responder()
        val x = sourceOf(pull(responder, null))
        val y = UUID.randomUUID()
        deliverRemote(responder, TaggedMapDelta(puts = mapOf("y1" to mapOf(Timestamp(y, 10) to "yv"))))
        responder.compactBelow(TagFrontier(mapOf(x to floor)))

        val full = pull(responder, null)

        // X unnamed -> below floor -> FULL, so Y's dot at exactly its `since` ships as well.
        val below = pull(responder, TagFrontier(mapOf(y to 10L)))
        assertSameAsFull(full, below)
        below.delta.puts.containsKey("y1") shouldBe true
        below.delta.puts.keys.filter { it.startsWith("keep") }.toSet() shouldBe keepKeys

        // The degenerate form is kept for the criterion's literal words, and is deliberately not
        // the oracle: an empty `since` is indistinguishable from full either way.
        assertSameAsFull(full, pull(responder, TagFrontier(emptyMap())))
    }

    /** No compaction -> no floor -> the since-filtered partial, untouched by this feature. */
    @Test
    fun `with no compaction at all a since pull is filtered exactly as before`() {
        val responder = responder()
        val full = pull(responder, null)
        val x = sourceOf(full)

        val partial = pull(responder, TagFrontier(mapOf(x to belowFloorSince)))
        partial.delta shouldNotBe full.delta
        // dots at counters <= 40 are dropped by the filter, as they always were: the whole
        // `keep*` prefix and the first 20 `e*` put-dots — the keep keys are ABSENT here.
        partial.delta.puts.keys shouldBe
            ((20 until 40).map { "e$it" } + (0 until 15).map { "live$it" }).toSet()
        keepKeys.none { partial.delta.puts.containsKey(it) } shouldBe true
    }

    /**
     * The floor survives `snapshot()`/`restore` with no snapshot key of its own: the restored
     * fence rebuilds it (9sm.8-D8, mirroring 9sm.7-D4), so the restored replica still refuses to
     * answer a below-floor `since` incrementally.
     */
    @Test
    fun `the floor survives snapshot-restore and still forces the full-state reply`() {
        val origin = responder()
        val x = sourceOf(pull(origin, null))
        origin.compactBelow(TagFrontier(mapOf(x to floor)))

        val restored = OrMapCell<String, String>()
        restored.outlet.linking.onLinkedListeners.clear()
        restored.restore(origin.snapshot())

        val full = pull(restored, null)
        val below = pull(restored, TagFrontier(mapOf(x to belowFloorSince)))

        assertSameAsFull(full, below)
        below.delta.puts.keys.filter { it.startsWith("keep") }.toSet() shouldBe keepKeys
    }

    /**
     * ENTRY-WHOLE, independent of the floor (no compaction here): a fresh responder mints `put k
     * v1` (dot 1), `put k v2` (retract del-dot 2, put-dot 3 — a re-put over a live key mints two,
     * `[24-TAG-04]` decision 9sm.8-D5), `remove k` (del-dot 4; `dels["k"] == {1,2,3,4}`).
     *
     * `since = {X->3}`: only dot 4 is novel, but the per-dot filter this task replaces would ship
     * `{4}` alone — the datum. The entry-whole filter ships the WHOLE entry because any dot in it
     * is novel. `since = {X->4}`: no dot in `k`'s entry is novel and `puts["k"]`'s own dots (1, 3)
     * are not novel either, so `putsOut`/`delsOut` both come back empty and `pullServe` ships NO
     * reply at all — the same "nothing beyond the frontier" behaviour `OrMapConvergenceTest`
     * already pins, not a defect of this test.
     */
    @Test
    fun `a dels entry ships whole or not at all, never split by dot`() {
        val responder = OrMapCell<String, String>()
        responder.outlet.linking.onLinkedListeners.clear()
        responder.inlet.call.put("k", "v1") // dot 1
        responder.inlet.call.put("k", "v2") // retract del-dot 2, put-dot 3
        responder.inlet.call.remove("k") // del-dot 4

        val full = pull(responder, null)
        val x = sourceOf(full)
        full.delta.dels.getValue("k") shouldBe setOf(Timestamp(x, 1), Timestamp(x, 2), Timestamp(x, 3), Timestamp(x, 4))

        val partialAtThree = pull(responder, TagFrontier(mapOf(x to 3L)))
        partialAtThree.delta.dels.getValue("k") shouldBe setOf(Timestamp(x, 1), Timestamp(x, 2), Timestamp(x, 3), Timestamp(x, 4))

        val partialAtFour = pullOptional(responder, TagFrontier(mapOf(x to 4L)))
        partialAtFour shouldBe null
    }
}
