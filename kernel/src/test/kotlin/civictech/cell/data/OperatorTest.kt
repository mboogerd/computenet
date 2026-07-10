package civictech.cell.data

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
import java.util.*

class OperatorTest {

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

    @Test
    fun `FilterCell passes matching elements with tags intact and absorbs the rest`() {
        val filter = FilterCell<String> { it.startsWith("a") }
        val out = collect(filter.outlet)

        val t1 = tag(1); val t2 = tag(2)
        filter.inlet.call.propagate(SetDelta(adds = mapOf("apple" to setOf(t1), "banana" to setOf(t2))))
        filter.inlet.call.propagate(SetDelta(dels = mapOf("apple" to setOf(t1))))

        assertEquals(2, out.size)
        assertEquals(mapOf("apple" to setOf(t1)), out[0].adds)
        assertEquals(mapOf("apple" to setOf(t1)), out[1].dels)
    }

    @Test
    fun `CountCell emits membership-size changes only`() {
        val count = CountCell<String>()
        val out = collect(count.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        count.inlet.call.propagate(SetDelta(adds = mapOf("x" to setOf(t1))))
        count.inlet.call.propagate(SetDelta(adds = mapOf("x" to setOf(t2), "y" to setOf(t3)))) // x already live
        count.inlet.call.propagate(SetDelta(dels = mapOf("x" to setOf(t1)))) // x still live via t2

        assertEquals(listOf(CounterDelta(1), CounterDelta(1)), out)

        count.inlet.call.propagate(SetDelta(dels = mapOf("x" to setOf(t2))))
        assertEquals(CounterDelta(-1), out.last())
    }

    @Test
    fun `IntersectSetCell tracks entry and exit of both-sided elements`() {
        val intersect = IntersectSetCell<String>()
        val out = collect(intersect.outlet)

        val t1 = tag(1); val t2 = tag(2)
        intersect.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(t1))))
        assertTrue(out.isEmpty()) // only left

        intersect.right.call.propagate(SetDelta(adds = mapOf("x" to setOf(t2))))
        assertEquals(mapOf("x" to setOf(t1, t2)), out.single().adds)

        intersect.left.call.propagate(SetDelta(dels = mapOf("x" to setOf(t1))))
        // exit deletes every advertised tag, so downstream membership dies
        assertEquals(mapOf("x" to setOf(t1, t2)), out[1].dels)
    }

    @Test
    fun `JoinCell joins on both-sided keys and retracts on either removal`() {
        val join = JoinCell<String, Int, String>()
        val out = collect(join.outlet)

        join.left.call.propagate(MapDelta(mapOf("k" to 1), emptySet()))
        assertTrue(out.isEmpty())

        join.right.call.propagate(MapDelta(mapOf("k" to "one"), emptySet()))
        assertEquals(mapOf("k" to (1 to "one")), out.single().puts)

        join.left.call.propagate(MapDelta(mapOf("k" to 2), emptySet())) // refresh
        assertEquals(mapOf("k" to (2 to "one")), out[1].puts)

        join.right.call.propagate(MapDelta(emptyMap(), setOf("k")))
        assertEquals(setOf("k"), out[2].removals)
    }

    @Test
    fun `operators serve catch-up to late-linking subscribers`() {
        val count = CountCell<String>()
        count.inlet.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)), "y" to setOf(tag(2)))))

        val late = mutableListOf<CounterDelta>()
        val lateCollector = CollectorCounter(late)
        count.outlet.linkTo(lateCollector.inlet as LinkFrom<Propagate<CounterDelta>>)

        assertEquals(listOf(CounterDelta(2)), late)
    }

    class CollectorCounter(private val arrivals: MutableList<CounterDelta>) {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {
                    arrivals += value
                }
            })
        }
    }

    @Test
    fun `pipeline - incremental result equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val writers = listOf(SetCell<String>(), SetCell<String>())
            val union = UnionSetCell<String>()
            val filter = FilterCell<String> { it.hashCode() % 2 == 0 }
            val count = CountCell<String>()

            writers.forEach { it.outlet.linkTo(union.inlet as LinkFrom<Propagate<SetDelta<String>>>) }
            union.outlet.linkTo(filter.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            filter.outlet.linkTo(count.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            val filtered = collect(filter.outlet)
            val counts = collect(count.outlet)

            val domain = ('a'..'f').map { it.toString() }
            val held = writers.map { mutableSetOf<String>() }
            repeat(60) {
                val w = rnd.nextInt(writers.size)
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || element !in held[w]) {
                    writers[w].inlet.call.add(element); held[w] += element
                } else {
                    writers[w].inlet.call.remove(element); held[w] -= element
                }
            }

            // batch recompute over the writers' final states
            val batch = held.flatten().toSet().filter { it.hashCode() % 2 == 0 }.toSet()
            assertEquals(batch, tagFold(filtered), "filter diverged from batch on seed $seed")
            assertEquals(batch.size.toLong(), counts.sumOf { it.amount }, "count diverged from batch on seed $seed")
        }
    }
}
