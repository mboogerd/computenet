package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TagState
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.data.view.CountView

/**
 * The consumer-side read models ([SetView] / [MapView] / [CountView]): the
 * canonical fold shared by cells and apps, exercised without any running host.
 * The load-bearing property is that [SetView] materializes the *same*
 * membership as the internal [TagState] a [UnionSetCell] runs — asserted both
 * against [tagFold] (the batch recompute) and against a live UnionSetCell fed
 * the same stream.
 */
class MaterializedViewTest {

    private var tagCounter = 0L
    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    // ---- SetView: OR-set correctness -------------------------------------

    @Test
    fun `add, observed-remove, and re-add reappear exactly once`() {
        val view = SetView<Int>()
        val t1 = freshTag()

        view.apply(SetDelta(adds = mapOf(1 to setOf(t1)))).shouldBeTrue()
        view.current() shouldBe setOf(1)

        // observed-remove of exactly the tags seen
        view.apply(SetDelta(dels = mapOf(1 to setOf(t1)))).shouldBeTrue()
        view.current() shouldBe emptySet()

        // re-add mints a fresh tag; the element reappears — and just once
        val t2 = freshTag()
        view.apply(SetDelta(adds = mapOf(1 to setOf(t2)))).shouldBeTrue()
        view.current() shouldBe setOf(1)
        view.size shouldBe 1
    }

    @Test
    fun `add wins under concurrent tags - a remove observing only one tag leaves the element live`() {
        val view = SetView<Int>()
        val t1 = freshTag()
        val t2 = freshTag() // a concurrent add the remove never observed

        view.apply(SetDelta(adds = mapOf(1 to setOf(t1))))
        view.apply(SetDelta(adds = mapOf(1 to setOf(t2))))

        // remove observing only t1 — t2's add wins
        view.apply(SetDelta(dels = mapOf(1 to setOf(t1)))).shouldBeFalse()
        view.current() shouldBe setOf(1)

        // now the second tag is also covered: element dies
        view.apply(SetDelta(dels = mapOf(1 to setOf(t2)))).shouldBeTrue()
        view.current() shouldBe emptySet()
    }

    @Test
    fun `duplicate delta delivery across a diamond fan-in dedups`() {
        val view = SetView<Int>()
        val t1 = freshTag()
        val delta = SetDelta(adds = mapOf(1 to setOf(t1)))

        view.apply(delta).shouldBeTrue()
        // the same tag arriving again over a second fan-in path: no double count
        view.apply(delta).shouldBeFalse()
        view.current() shouldBe setOf(1)
        view.size shouldBe 1
    }

    @Test
    fun `effective-only - tag churn with no membership change returns false`() {
        val view = SetView<Int>()
        val t1 = freshTag()
        view.apply(SetDelta(adds = mapOf(1 to setOf(t1))))

        // a fresh add-tag for an already-live element: real tag info, no
        // membership change
        val t2 = freshTag()
        view.apply(SetDelta(adds = mapOf(1 to setOf(t2)))).shouldBeFalse()
        view.current() shouldBe setOf(1)
    }

    @Test
    fun `effective-only - a del of an unseen tag returns false`() {
        val view = SetView<Int>()
        view.apply(SetDelta(dels = mapOf(1 to setOf(freshTag())))).shouldBeFalse()
        view.current() shouldBe emptySet()
    }

    // ---- SetView: agreement with the cell's internal TagState ------------

    /** Generates a realistic tagged delta stream, tracking live tags per element. */
    private fun randomSetDeltas(seed: Long, ops: Int): List<SetDelta<Int>> {
        val rnd = Random(seed)
        val domain = (0 until 4).toList()
        val live = mutableMapOf<Int, MutableSet<Timestamp>>()
        val out = mutableListOf<SetDelta<Int>>()
        repeat(ops) {
            val e = domain[rnd.nextInt(domain.size)]
            val here = live.getOrPut(e) { mutableSetOf() }
            val delta = when {
                rnd.nextInt(10) < 6 || here.isEmpty() -> {
                    val t = freshTag()
                    here += t
                    SetDelta(adds = mapOf(e to setOf(t)))
                }
                else -> {
                    val observed = here.toSet()
                    here.clear()
                    SetDelta(dels = mapOf(e to observed))
                }
            }
            out += delta
            // duplicate delivery of the SAME delta (a diamond fan-in): in-order,
            // so per-link FIFO holds (a tag's add still precedes its del) — the
            // invariant TagState's tombstone-free fold relies on.
            if (rnd.nextInt(5) == 0) out += delta
        }
        return out
    }

    @Test
    fun `SetView membership agrees with tagFold and a live UnionSetCell on every seed`() {
        for (seed in 0L until 200L) {
            val deltas = randomSetDeltas(seed, ops = 40)

            val view = SetView<Int>()
            deltas.forEach { view.apply(it) }

            // (a) agrees with the batch tag-algebra recompute
            view.current() shouldBe tagFold(deltas)

            // (b) agrees with a real UnionSetCell fed the identical stream:
            // tagFold over the cell's forwarded effective deltas reconstructs
            // the cell's own internal membership.
            val union = UnionSetCell<Int>()
            val forwarded = mutableListOf<SetDelta<Int>>()
            union.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<Int>> {
                override fun propagate(value: SetDelta<Int>) { forwarded += value }
            }, PortRef.generate()))
            deltas.forEach { union.inlet.call.propagate(it) }

            view.current() shouldBe tagFold(forwarded)
        }
    }

    // ---- SetView: late-join catch-up + snapshot --------------------------

    @Test
    fun `SetView rebuilds from a single delta-from-empty and replay is idempotent`() {
        val source = SetView<Int>()
        val t1 = freshTag(); val t2 = freshTag()
        source.apply(SetDelta(adds = mapOf(1 to setOf(t1), 2 to setOf(t2))))
        source.apply(SetDelta(dels = mapOf(2 to setOf(t2)))) // 2 removed

        // the onLinked / StateRequest reply shape: current live tags as one delta-from-empty
        val catchUp = SetDelta(adds = mapOf(1 to setOf(t1)))

        val joiner = SetView<Int>()
        joiner.apply(catchUp).shouldBeTrue()
        joiner.current() shouldBe source.current()

        // replaying the same catch-up delta again double-counts nothing
        joiner.apply(catchUp).shouldBeFalse()
        joiner.current() shouldBe source.current()
    }

    @Test
    fun `SetView snapshot-restore round-trips`() {
        val view = SetView<Int>()
        view.apply(SetDelta(adds = mapOf(1 to setOf(freshTag()), 2 to setOf(freshTag()))))
        val snap = view.snapshot()

        val restored = SetView<Int>()
        restored.restore(snap)
        restored.current() shouldBe view.current()

        // and it keeps folding correctly after restore
        val t = freshTag()
        restored.apply(SetDelta(adds = mapOf(3 to setOf(t)))).shouldBeTrue()
        restored.current() shouldBe setOf(1, 2, 3)
    }

    // ---- MapView ---------------------------------------------------------

    private fun randomMapDeltas(seed: Long, ops: Int): List<MapDelta<String, Int>> {
        val rnd = Random(seed)
        val keys = listOf("a", "b", "c")
        return (0 until ops).map {
            val k = keys[rnd.nextInt(keys.size)]
            if (rnd.nextInt(10) < 7) MapDelta(mapOf(k to rnd.nextInt(100)), emptySet())
            else MapDelta(emptyMap(), setOf(k))
        }
    }

    @Test
    fun `MapView puts, removals and upsert round-trip against a batch recompute on every seed`() {
        for (seed in 0L until 200L) {
            val deltas = randomMapDeltas(seed, ops = 40)
            val view = MapView<String, Int>()
            deltas.forEach { view.apply(it) }
            view.current() shouldBe mapFold(deltas)
        }
    }

    @Test
    fun `MapView effective-only - restated put and removal of an absent key return false`() {
        val view = MapView<String, Int>()

        view.apply(MapDelta(mapOf("a" to 1), emptySet())).shouldBeTrue()
        // upsert to the same value: no effective change
        view.apply(MapDelta(mapOf("a" to 1), emptySet())).shouldBeFalse()
        // upsert to a new value (last-writer-per-key): changed
        view.apply(MapDelta(mapOf("a" to 2), emptySet())).shouldBeTrue()
        view["a"] shouldBe 2

        // removal of an absent key: no change
        view.apply(MapDelta(emptyMap(), setOf("zzz"))).shouldBeFalse()
        // removal of a present key: changed
        view.apply(MapDelta(emptyMap(), setOf("a"))).shouldBeTrue()
        view.current() shouldBe emptyMap()
    }

    @Test
    fun `MapView rebuilds from a single delta-from-empty and replay is idempotent`() {
        val source = MapView<String, Int>()
        source.apply(MapDelta(mapOf("a" to 1, "b" to 2), emptySet()))
        source.apply(MapDelta(emptyMap(), setOf("b")))

        val catchUp = MapDelta(source.current(), emptySet()) // MapCell onLinked shape
        val joiner = MapView<String, Int>()
        joiner.apply(catchUp).shouldBeTrue()
        joiner.current() shouldBe source.current()
        joiner.apply(catchUp).shouldBeFalse() // idempotent replay
        joiner.current() shouldBe source.current()
    }

    @Test
    fun `MapView snapshot-restore round-trips`() {
        val view = MapView<String, Int>()
        view.apply(MapDelta(mapOf("a" to 1, "b" to 2), emptySet()))
        val restored = MapView<String, Int>()
        restored.restore(view.snapshot())
        restored.current() shouldBe view.current()
    }

    // ---- CountView (the byDay fold) --------------------------------------

    @Test
    fun `CountView folds byDay-style counts with a zero-default accessor`() {
        val counts = CountView<String>()

        // upstream recomputes each day's count and re-puts it (last-writer-per-key)
        counts.apply(MapDelta(mapOf("mon" to 1L), emptySet())).shouldBeTrue()
        counts.apply(MapDelta(mapOf("mon" to 3L, "tue" to 2L), emptySet())).shouldBeTrue()
        counts.count("mon") shouldBe 3L
        counts.count("tue") shouldBe 2L
        counts.count("wed") shouldBe 0L // never counted
        counts.current() shouldBe mapOf("mon" to 3L, "tue" to 2L)

        // a day dropping to zero is modelled as a removal
        counts.apply(MapDelta(emptyMap(), setOf("tue"))).shouldBeTrue()
        counts.count("tue") shouldBe 0L
    }

    @Test
    fun `CountView round-trips a count stream against a batch recompute`() {
        val rnd = Random(7)
        val days = listOf("mon", "tue", "wed")
        val deltas = (0 until 60).map {
            val d = days[rnd.nextInt(days.size)]
            if (rnd.nextInt(10) < 8) MapDelta(mapOf(d to rnd.nextLong(20)), emptySet())
            else MapDelta(emptyMap(), setOf(d))
        }
        val counts = CountView<String>()
        deltas.forEach { counts.apply(it) }
        counts.current() shouldBe mapFold(deltas)
    }

    @Test
    fun `CountView snapshot-restore round-trips`() {
        val counts = CountView<String>()
        counts.apply(MapDelta(mapOf("mon" to 5L), emptySet()))
        val restored = CountView<String>()
        restored.restore(counts.snapshot())
        restored.current() shouldBe counts.current()
        restored.count("mon") shouldBe 5L
    }
}
