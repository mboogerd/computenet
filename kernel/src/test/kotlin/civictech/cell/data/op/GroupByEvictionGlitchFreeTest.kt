package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.data.Aggregators
import civictech.cell.data.WaterlineCell
import civictech.cell.data.Windows
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.mapFold
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * B16 (`[KE4-42]`, spec 20/22 §Local glitch-freedom `[22-GF-01]`/`[22-GF-02]`):
 * a diamond `S -> WaterlineCell -> GroupByCell.waterline` and
 * `S -> GroupByCell.inlet`, gated by one [WaveFrontier] with a per-inlet
 * [WaveFrontier.arm] on `waterline` and on `inlet` (`waterline` attached
 * first, nt17o-D2 — the eviction arm releases before the wave's fold), never
 * shows an observer of `GroupByCell.outlet` a `MapDelta` sequence that puts a
 * window's key and later removes it within the SAME wave.
 *
 * Fixture: tumbling windows of 10, `keyTime = k + 10`,
 * `Windows.Lateness(LongTime, 0)`, count aggregator, elements `Long` (the
 * event time itself — `[24-WL-01]`'s `timeFn` is the identity). The late-drop
 * guard is the strict `timeFn(e) < floor` (`[24-WL-07]`; AMENDS on this bead,
 * consistent with `GroupByCell.onInlet`).
 *
 * computenet-nt17o.5.
 */
class GroupByEvictionGlitchFreeTest {

    /** Identity event time; a named `Serializable` function value, as `[24-WL-01]` asks. */
    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(e: Long): Long = e
        private fun readResolve(): Any = LongTime
    }

    /** The diamond's single source: one outlet, fanning to both `WC` and `GB.inlet`. */
    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())

        /**
         * One wave, one or several adds: [FanOutlet.originate] mints a fresh
         * `(sourceId, counter)` and fans the SAME `SetDelta` to every link — the
         * batch shape B16 needs, without a `SetCell` (which mints one wave per
         * single-element `add`, per this bead's own `unverified:` note).
         */
        fun add(vararg events: Long) = outlet.originate {
            propagate(SetDelta(adds = events.associate { it to setOf(Timestamp(UUID.randomUUID(), it)) }))
        }
    }

    private fun windowed(): GroupByCell<Long, Long, Long, Long> = GroupByCell(
        keyFn = Windows.tumbling(10),
        aggregator = Aggregators.count(),
        lateness = Windows.Lateness(LongTime, 0),
        keyTime = { k: Long -> k + 10 },
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> link(outlet: FanOutlet<Propagate<T>>, inlet: FanInlet<Propagate<T>>) {
        (outlet.linkTo(inlet as LinkFrom<Propagate<T>>) is LinkResult.Connected).shouldBeTrue()
    }

    /** Every `MapDelta` [cell] emits, with the wave timestamp it rode (as `WaterlineCellTest.record`). */
    private fun recordWaves(
        cell: GroupByCell<Long, Long, Long, Long>,
    ): MutableList<Pair<MapDelta<Long, Long>, Timestamp?>> {
        val seen = mutableListOf<Pair<MapDelta<Long, Long>, Timestamp?>>()
        cell.outlet.subscribe(Use.fixed(object : Propagate<MapDelta<Long, Long>> {
            override fun propagate(value: MapDelta<Long, Long>) {
                seen += value to CurrentContext.get()?.timestamp
            }
        }, PortRef.generate()))
        return seen
    }

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    /** A late-joining subscriber: linking replays the cell's current aggregates (G-22 catch-up). */
    private class LateJoiner {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<Long, Long>>>))
        val arrivals = mutableListOf<MapDelta<Long, Long>>()

        init {
            inlet.serve(object : Propagate<MapDelta<Long, Long>> {
                override fun propagate(value: MapDelta<Long, Long>) {
                    arrivals += value
                }
            })
        }
    }

    /** Link a fresh subscriber to [cell] now and return the fold of what it caught up with. */
    private fun lateJoinFold(cell: GroupByCell<Long, Long, Long, Long>): Map<Long, Long> {
        val joiner = LateJoiner()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(joiner.inlet as LinkFrom<Propagate<MapDelta<Long, Long>>>)
        return mapFold(joiner.arrivals)
    }

    /**
     * `[22-GF-01]`/`[22-GF-02]` as an executable invariant: within one wave, no
     * key may appear in an earlier `MapDelta`'s `puts` and a later one's
     * `removals` — that shape is exactly the torn read the frontier arm order
     * exists to prevent (B16). A removal that precedes its window's own put is
     * fine (that IS the wanted eviction-before-fold order); only
     * put-then-remove, same wave, is torn.
     */
    private fun assertNoTornWave(seen: List<Pair<MapDelta<Long, Long>, Timestamp?>>) {
        val putAt = mutableMapOf<Pair<Timestamp?, Long>, Int>()
        seen.forEachIndexed { idx, (delta, wave) ->
            delta.puts.keys.forEach { k -> putAt.putIfAbsent(wave to k, idx) }
            delta.removals.forEach { k ->
                val putIdx = putAt[wave to k]
                if (putIdx != null && putIdx < idx) {
                    throw AssertionError(
                        "torn wave $wave: key $k put at delta #$putIdx then removed at delta #$idx ($seen)",
                    )
                }
            }
        }
    }

    /**
     * The diamond, wired per nt17o-D2: waterline arm attached first, then inlet —
     * both arms installed directly on the `@CellBase`-generated `GroupByCell`
     * inlets (the `inlet` handler is `onEach`-bound; computenet-mdvgt made a
     * policy on that shape deliverable).
     */
    private class Rig {
        val cell = GroupByCell(
            keyFn = Windows.tumbling(10),
            aggregator = Aggregators.count<Long>(),
            lateness = Windows.Lateness(LongTime, 0),
            keyTime = { k: Long -> k + 10 },
        )
        val wc = WaterlineCell(lateness = Windows.Lateness(LongTime, 0))
        val src = Source()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
    }

    private fun buildRig(): Rig {
        val rig = Rig()
        // nt17o-D2: waterline arm attached FIRST — its release (eviction) precedes
        // the inlet arm's release (the wave's fold) within one wave.
        rig.cell.waterline.install(rig.frontier.arm())
        rig.cell.inlet.install(rig.frontier.arm())
        link(rig.src.outlet, rig.wc.inlet)
        link(rig.src.outlet, rig.cell.inlet)
        link(rig.wc.outlet, rig.cell.waterline)
        return rig
    }

    @Test
    fun `B16 - the frontier arm order prevents a torn wave across eviction and fold`() {
        val rig = buildRig()
        val gb = rig.cell
        val seen = recordWaves(gb)
        val late = collect(gb.late)

        // wave (S,1): adds {3, 15} raise the floor straight to 15 (max - lateness 0).
        // Arm order: the waterline arm releases first and evicts window 0 — but
        // window 0 holds nothing yet (the inlet arm's fold hasn't run), so eviction
        // is a no-op; THEN the inlet arm folds the add: 3 (< 15) is late-dropped,
        // 15 (not < 15) is admitted into window 10.
        rig.src.add(3, 15)

        seen.size shouldBe 1
        seen[0].first shouldBe MapDelta(mapOf(10L to 1L), emptySet())
        late.size shouldBe 1
        late.single().adds.keys shouldBe setOf(3L)
        rig.wc.floor() shouldBe 15L
        gb.floor() shouldBe 15L

        // wave (S,2): adds {12, 25} raise the floor to 25. Window 10 (end 20) has
        // now passed: the waterline arm evicts it FIRST (removals {10}), then the
        // inlet arm folds the add: 12 (< 25) is late-dropped, 25 (not < 25) is
        // admitted into window 20 (puts {20: 1}) — never a put for 10 after its
        // own removal, and never a put for 20 before the eviction that precedes it.
        rig.src.add(12, 25)

        seen.size shouldBe 3
        val wave2 = seen[1].second
        wave2 shouldBe seen[2].second
        seen[1].first shouldBe MapDelta(emptyMap(), setOf(10L))
        seen[2].first shouldBe MapDelta(mapOf(20L to 1L), emptySet())
        late.size shouldBe 2
        late[1].adds.keys shouldBe setOf(12L)
        rig.wc.floor() shouldBe 25L
        gb.floor() shouldBe 25L

        // the invariant this whole test exists to check, over every wave observed
        assertNoTornWave(seen)

        // [24-WL-05]: state == integrated output, at every point along the way
        lateJoinFold(gb) shouldBe mapFold(seen.map { it.first })
    }

    @Test
    fun `control - eager delivery (no frontier) tears the wave`() {
        // same topology, no WaveFrontier at all: S links to GB.inlet FIRST, so the
        // main arm's fold runs on today's (stale) floor before WC's own delivery
        // (linked second) has a chance to raise it and evict behind the fold's back.
        val gb = windowed()
        val wc = WaterlineCell(lateness = Windows.Lateness(LongTime, 0))
        val src = Source()
        link(src.outlet, gb.inlet)
        link(src.outlet, wc.inlet)
        link(wc.outlet, gb.waterline)

        val seen = recordWaves(gb)
        src.add(3, 15)
        src.add(12, 25)

        // [22-GF-01]/[22-GF-02] is exactly what this topology lacks: some key is
        // put in an earlier delta of a wave and removed in a later one of the SAME
        // wave. This is the shape task .5's frontier arm order exists to prevent.
        val violation = shouldThrow<AssertionError> { assertNoTornWave(seen) }
        violation.message!! shouldContain "torn wave"
    }

    @Test
    fun `a non-raising wave completes through Progress and does not stick the frontier`() {
        val rig = buildRig()
        val gb = rig.cell
        val seen = recordWaves(gb)
        val late = collect(gb.late)
        rig.src.add(3, 15) // wave 1: floor -> 15, put {10: 1}, 3 late
        rig.src.add(12, 25) // wave 2: floor -> 25, removal {10}, put {20: 1}, 12 late
        val emittedBeforeWave3 = seen.size
        late.size shouldBe 2

        // wave (S,3): {25, 24} keeps WC's max at 25, so the floor does not raise.
        // 25 (not < 25) is a re-add of the element already live in window 20:
        // GroupByCell's own contract (class KDoc: "membership flips, not tag
        // churn, drive insert/retract") folds it as tag churn — no flip, no
        // MapDelta. 24 (< 25) is late-dropped, so the fold's release is visible
        // on `late` (the direct-wiring stand-in for counting the inlet arm's
        // releases; the arm sits on the generated inlet itself). WC's own
        // delivery is non-raising too, so it absorb-acks (`raiseTo` returns
        // false) instead of emitting a WaterlineDelta — the waterline arm's
        // edge settles by Progress, never by an invocation.
        rig.src.add(25, 24)

        seen.size shouldBe emittedBeforeWave3 // absorbed on both arms, no MapDelta
        // the fold of wave 3 was released NOW, by WC's absorb-ack alone — not later,
        // swept out by wave 4's monotone watermark advance (which would leave the
        // assertions below green even with the absorb-ack removed): with the
        // absorb-ack gone, the frontier still holds wave 3 here and `late` is 2.
        late.size shouldBe 3
        late[2].adds.keys shouldBe setOf(24L)
        rig.wc.floor() shouldBe 25L
        gb.floor() shouldBe 25L

        // the wave completed rather than sticking the frontier: a later, genuinely
        // raising wave proceeds normally, in the same eviction-then-fold order.
        rig.src.add(35) // wave 4: floor -> 35, window 20 (end 30) passes

        seen.size shouldBe emittedBeforeWave3 + 2
        seen[emittedBeforeWave3].first shouldBe MapDelta(emptyMap(), setOf(20L))
        seen[emittedBeforeWave3 + 1].first shouldBe MapDelta(mapOf(30L to 1L), emptySet())
        assertNoTornWave(seen)
    }

    @Test
    fun `a late-linked subscriber's catch-up fold equals the early observer's, after every wave`() {
        val rig = buildRig()
        val gb = rig.cell
        val seen = recordWaves(gb)
        rig.src.add(3, 15)
        rig.src.add(12, 25)
        rig.src.add(25) // non-raising, no-op fold (see the Progress test above)
        rig.src.add(35)

        assertNoTornWave(seen)
        lateJoinFold(gb) shouldBe mapFold(seen.map { it.first })
        lateJoinFold(gb) shouldBe mapOf(30L to 1L)
    }
}
