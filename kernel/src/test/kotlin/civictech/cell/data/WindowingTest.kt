package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FlatMapSetCell

data class Reading(val sensor: String, val at: Long, val value: Long) : Serializable

class WindowingTest {

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
    fun `tumbling assigner maps event times to window starts`() {
        val w = Windows.tumbling(10)
        assertEquals(0L, w(0))
        assertEquals(0L, w(9))
        assertEquals(10L, w(10))
        assertEquals(-10L, w(-1))
    }

    @Test
    fun `sliding assigner yields every containing window ascending`() {
        val w = Windows.sliding(10, 5)
        assertEquals(listOf(5L, 10L), w(12))
        assertEquals(listOf(-5L, 0L), w(3))
        assertEquals(listOf(0L), Windows.sliding(10, 10)(3)) // slide == size degenerates to tumbling
    }

    @Test
    fun `tumbling window sums update on late elements and retractions`() {
        val window = Windows.tumbling(10)
        val hourly = GroupByCell(
            keyFn = { r: Reading -> "${r.sensor}@${window(r.at)}" },
            aggregator = Aggregators.sumOf { it.value },
        )
        val out = collect(hourly.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        hourly.inlet.call.propagate(
            SetDelta(
                adds = mapOf(
                    Reading("s1", 3, 5) to setOf(t1),
                    Reading("s1", 17, 7) to setOf(t2),
                )
            )
        )
        assertEquals(mapOf("s1@0" to 5L, "s1@10" to 7L), mapFold(out))

        // windows never close: a late element is an ordinary add
        hourly.inlet.call.propagate(SetDelta(adds = mapOf(Reading("s1", 8, 2) to setOf(t3))))
        assertEquals(mapOf("s1@0" to 7L, "s1@10" to 7L), mapFold(out))

        // retraction flows into the window aggregate
        hourly.inlet.call.propagate(SetDelta(dels = mapOf(Reading("s1", 3, 5) to setOf(t1))))
        assertEquals(mapOf("s1@0" to 2L, "s1@10" to 7L), mapFold(out))
    }

    @Test
    fun `sliding windows - flatMap expansion plus groupBy equals batch on every seed`() {
        val window = Windows.sliding(10, 5)
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val readings = SetCell<Reading>()
            val expand = FlatMapSetCell(f = { r: Reading -> window(r.at).map { w -> w to r } })
            val summed = GroupByCell(
                keyFn = { (w, r): Pair<Long, Reading> -> "${r.sensor}@$w" },
                aggregator = Aggregators.sumOf { it.second.value },
            )
            readings.outlet.linkTo(expand.inlet as LinkFrom<Propagate<SetDelta<Reading>>>)
            expand.outlet.linkTo(summed.inlet as LinkFrom<Propagate<SetDelta<Pair<Long, Reading>>>>)
            val out = collect(summed.outlet)

            val domain = listOf(
                Reading("s1", 3, 5), Reading("s1", 8, 2), Reading("s1", 12, 7),
                Reading("s2", 4, 1), Reading("s2", 19, 3),
            )
            val held = mutableSetOf<Reading>()
            repeat(60) {
                val r = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || r !in held) {
                    readings.inlet.call.add(r); held += r
                } else {
                    readings.inlet.call.remove(r); held -= r
                }
            }

            val batch = mutableMapOf<String, Long>()
            held.forEach { r -> window(r.at).forEach { w -> batch.merge("${r.sensor}@$w", r.value, Long::plus) } }
            assertEquals(batch, mapFold(out), "sliding windows diverged from batch on seed $seed")
        }
    }
}
