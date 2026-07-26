package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.GroupByCell

class GroupByCellTest {

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    // elements "a3" → group 'a', value 3
    private fun key(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    private fun sumByKey() = GroupByCell(
        keyFn = ::key,
        aggregator = Aggregators.sumOf(::amount),
    )

    @Test
    fun `groups sum incrementally and die on last retraction`() {
        val cell = sumByKey()
        val out = collect(cell.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(t1), "a4" to setOf(t2), "b5" to setOf(t3))))
        assertEquals(mapOf("a" to 7L, "b" to 5L), out.single().puts)

        cell.inlet.call.propagate(SetDelta(dels = mapOf("a3" to setOf(t1))))
        assertEquals(mapOf("a" to 4L), out[1].puts)

        cell.inlet.call.propagate(SetDelta(dels = mapOf("b5" to setOf(t3))))
        assertEquals(setOf("b"), out[2].removals) // group death, not a zero put

        assertEquals(mapOf("a" to 4L), mapFold(out))
    }

    @Test
    fun `tag churn and value-neutral changes emit nothing`() {
        val cell = sumByKey()
        val out = collect(cell.outlet)

        val t1 = tag(1); val t2 = tag(2)
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(t1))))
        assertEquals(1, out.size)

        // second tag on a live element: membership unchanged → no emission
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(t2))))
        assertEquals(1, out.size)

        // retracting the non-last tag: still live → no emission
        cell.inlet.call.propagate(SetDelta(dels = mapOf("a3" to setOf(t1))))
        assertEquals(1, out.size)

        // add a zero-amount element: sum value unchanged → value-equals gates emission
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a0" to setOf(tag(3)))))
        assertEquals(1, out.size)
    }

    @Test
    fun `count and avg aggregate correctly under retraction`() {
        val count = GroupByCell(keyFn = ::key, aggregator = Aggregators.count<String>())
        val avg = GroupByCell(keyFn = ::key, aggregator = Aggregators.avgOf(::amount))
        val countOut = collect(count.outlet)
        val avgOut = collect(avg.outlet)

        val t1 = tag(1); val t2 = tag(2)
        listOf(count.inlet, avg.inlet).forEach {
            it.call.propagate(SetDelta(adds = mapOf("a2" to setOf(t1), "a4" to setOf(t2))))
        }
        assertEquals(mapOf("a" to 2L), mapFold(countOut))
        assertEquals(mapOf("a" to 3.0), mapFold(avgOut))

        listOf(count.inlet, avg.inlet).forEach {
            it.call.propagate(SetDelta(dels = mapOf("a2" to setOf(t1))))
        }
        assertEquals(mapOf("a" to 1L), mapFold(countOut))
        assertEquals(mapOf("a" to 4.0), mapFold(avgOut))
    }

    @Test
    fun `global folds to a scalar under the constant key`() {
        val total = GroupByCell.global(Aggregators.sumOf(::amount))
        val out = collect(total.outlet)

        total.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(tag(1)), "b5" to setOf(tag(2)))))
        assertEquals(mapOf("global" to 8L), mapFold(out))
    }

    @Test
    fun `serves catch-up to late-linking subscribers`() {
        val cell = sumByKey()
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(tag(1)), "b5" to setOf(tag(2)))))

        val late = MapCollector()
        cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<String, Long>>>)

        assertEquals(mapOf("a" to 3L, "b" to 5L), mapFold(late.arrivals))
    }

    @Test
    fun `snapshot-restore preserves groups and membership`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = GroupByCell(ref, ::key, Aggregators.sumOf(::amount))
        val t1 = tag(1); val t2 = tag(2)
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(t1), "a4" to setOf(t2))))

        // round-trip through real serialization, as migration does
        val restored = GroupByCell(ref, ::key, Aggregators.sumOf(::amount))
        restored.restore(roundTrip(cell.snapshot()))

        val late = MapCollector()
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<String, Long>>>)
        assertEquals(mapOf("a" to 7L), mapFold(late.arrivals))

        // continued retraction against restored membership
        restored.inlet.call.propagate(SetDelta(dels = mapOf("a3" to setOf(t1))))
        assertEquals(mapOf("a" to 4L), mapFold(late.arrivals))
    }

    @Test
    fun `pipeline - grouped sums equal batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val writers = listOf(SetCell<String>(), SetCell<String>())
            val union = UnionSetCell<String>()
            val grouped = sumByKey()

            writers.forEach { it.outlet.linkTo(union.inlet as LinkFrom<Propagate<SetDelta<String>>>) }
            union.outlet.linkTo(grouped.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            val out = collect(grouped.outlet)

            val domain = listOf("a1", "a2", "a5", "b3", "b7", "c4")
            val held = writers.map { mutableSetOf<String>() }
            repeat(80) {
                val w = rnd.nextInt(writers.size)
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || element !in held[w]) {
                    writers[w].inlet.call.add(element); held[w] += element
                } else {
                    writers[w].inlet.call.remove(element); held[w] -= element
                }
            }

            val batch = held.flatten().toSet().groupBy(::key).mapValues { (_, es) -> es.sumOf(::amount) }
            assertEquals(batch, mapFold(out), "groupBy diverged from batch on seed $seed")
        }
    }

    // for extremum tests: elements "a3x" → group 'a', value 3, suffix distinguishes elements
    private fun midVal(e: String) = e[1].toString().toLong()

    @Test
    fun `min survives duplicate-value retraction and reshuffles when the extremum dies`() {
        val cell = GroupByCell(keyFn = ::key, aggregator = Aggregators.minOf(::midVal))
        val out = collect(cell.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        cell.inlet.call.propagate(
            SetDelta(adds = mapOf("a3x" to setOf(t1), "a3y" to setOf(t2), "a7z" to setOf(t3)))
        )
        assertEquals(mapOf("a" to 3L), mapFold(out))

        // one of two duplicate minima retracts: min unchanged, no emission
        cell.inlet.call.propagate(SetDelta(dels = mapOf("a3x" to setOf(t1))))
        assertEquals(1, out.size)

        // the last minimum retracts: min reshuffles without a re-scan
        cell.inlet.call.propagate(SetDelta(dels = mapOf("a3y" to setOf(t2))))
        assertEquals(mapOf("a" to 7L), mapFold(out))
    }

    @Test
    fun `topK keeps the k largest with duplicate multiplicities under retraction`() {
        val cell = GroupByCell(keyFn = ::key, aggregator = Aggregators.topK(2, ::midVal))
        val out = collect(cell.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        cell.inlet.call.propagate(
            SetDelta(adds = mapOf("a5x" to setOf(t1), "a5y" to setOf(t2), "a9z" to setOf(t3)))
        )
        assertEquals(mapOf("a" to listOf(9L, 5L)), mapFold(out))

        // the top value retracts: the evicted duplicate must come back —
        // this is why bounded-memory top-k is unsound and the full support is kept
        cell.inlet.call.propagate(SetDelta(dels = mapOf("a9z" to setOf(t3))))
        assertEquals(mapOf("a" to listOf(5L, 5L)), mapFold(out))
    }

    @Test
    fun `collectToSet mirrors group membership`() {
        val cell = GroupByCell(keyFn = ::key, aggregator = Aggregators.collectToSet<String>())
        val out = collect(cell.outlet)

        val t1 = tag(1); val t2 = tag(2)
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(t1), "a4" to setOf(t2))))
        assertEquals(mapOf("a" to setOf("a3", "a4")), mapFold(out))

        cell.inlet.call.propagate(SetDelta(dels = mapOf("a4" to setOf(t2))))
        assertEquals(mapOf("a" to setOf("a3")), mapFold(out))
    }

    @Test
    fun `pipeline - grouped max equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val writers = listOf(SetCell<String>(), SetCell<String>())
            val union = UnionSetCell<String>()
            val grouped = GroupByCell(keyFn = ::key, aggregator = Aggregators.maxOf(::amount))

            writers.forEach { it.outlet.linkTo(union.inlet as LinkFrom<Propagate<SetDelta<String>>>) }
            union.outlet.linkTo(grouped.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            val out = collect(grouped.outlet)

            val domain = listOf("a1", "a2", "a5", "b3", "b7", "c4")
            val held = writers.map { mutableSetOf<String>() }
            repeat(80) {
                val w = rnd.nextInt(writers.size)
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || element !in held[w]) {
                    writers[w].inlet.call.add(element); held[w] += element
                } else {
                    writers[w].inlet.call.remove(element); held[w] -= element
                }
            }

            val batch = held.flatten().toSet().groupBy(::key).mapValues { (_, es) -> es.maxOf(::amount) }
            assertEquals(batch, mapFold(out), "grouped max diverged from batch on seed $seed")
        }
    }

    @Test
    fun `control - retraction-blind aggregation diverges from batch`() {
        // the failure class the membership-flip fold guards against: an
        // insert-only fold (or a fold of raw deltas without tag dedup) drifts
        // as soon as elements are removed
        var diverged = false
        for (seed in 0L until 20L) {
            val rnd = Random(seed)
            val writer = SetCell<String>()
            val naive = mutableMapOf<String, Long>() // folds adds, ignores retractions
            writer.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    value.adds.keys.forEach { naive.merge(key(it), amount(it), Long::plus) }
                }
            }, PortRef.generate()))

            val domain = listOf("a1", "a2", "b3")
            val held = mutableSetOf<String>()
            repeat(40) {
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 5 || element !in held) {
                    writer.inlet.call.add(element); held += element
                } else {
                    writer.inlet.call.remove(element); held -= element
                }
            }

            val batch = held.groupBy(::key).mapValues { (_, es) -> es.sumOf(::amount) }
            if (naive.filterValues { it != 0L } != batch) diverged = true
        }
        assertTrue(diverged, "control failed to reproduce the divergence")
    }

    class MapCollector(val arrivals: MutableList<MapDelta<String, Long>> = mutableListOf()) {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, Long>>>))

        init {
            inlet.serve(object : Propagate<MapDelta<String, Long>> {
                override fun propagate(value: MapDelta<String, Long>) {
                    arrivals += value
                }
            })
        }
    }

    companion object {
        fun roundTrip(state: Serializable): Serializable {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.ObjectOutputStream(bytes).use { it.writeObject(state) }
            return java.io.ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() as Serializable }
        }
    }
}
