package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.GroupByCell

/**
 * KAGG-R-06 probe (epic `computenet-t6b.1`, feature `computenet-qi2tz`):
 * whether `collectToSet()` correctly distinguishes two distinct elements that
 * a caller-side projection would collide, under retraction.
 *
 * **The question BS-05 asks, and why it looks live at first read.** The
 * epic's audit table raises a "non-injective projection" concern for
 * `collectToSet`: if the aggregator kept only a *projected* value per
 * element, two elements mapping to the same projection could be
 * indistinguishable in the accumulator, and retracting one could wrongly
 * evict both (or neither). `Aggregators.collectToSet()` takes no selector —
 * `Collect.insert`/`Collect.retract`
 * (`kernel/src/main/kotlin/civictech/cell/data/Aggregator.kt`, `private class
 * Collect`) close over the element type `E` itself and mutate a
 * `HashSet<E>` keyed by the element's own `equals`/`hashCode`, never by a
 * caller-supplied lens. So the concern cannot be expressed through this
 * aggregator's own API. This probe makes that an observation, not an
 * argument: a caller-side lens (`groupBy`'s own `keyFn`, chosen deliberately
 * to double as the "projection" the concern describes) maps two distinct
 * elements to the same value, and retraction is asserted to discriminate by
 * the element, not the lens.
 */
class AggregatorAuditProbeTest {

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

    /** Two distinct persons; `city` is the caller-side lens the concern is about — both collide on it. */
    data class Person(val id: Long, val city: String) : java.io.Serializable

    @Test
    fun `collectToSet retract of one live element leaves a distinct element with the same caller-side lens standing`() {
        val cell = GroupByCell(
            keyFn = { p: Person -> p.city }, // the "projection" BS-05 worries about, applied deliberately
            aggregator = Aggregators.collectToSet<Person>(),
        )
        val out = collect(cell.outlet)

        val p1 = Person(id = 1L, city = "NYC")
        val p2 = Person(id = 2L, city = "NYC") // distinct element (different id), same lens value as p1
        val t1 = tag(1)
        val t2 = tag(2)

        cell.inlet.call.propagate(SetDelta(adds = mapOf(p1 to setOf(t1), p2 to setOf(t2))))
        assertEquals(setOf(p1, p2), out.single().puts.getValue("NYC"))

        // Retract p1 only. If `collectToSet` tracked the *projected* value
        // rather than the element, this could not distinguish p1 from p2 (both
        // "NYC") and would either evict both or neither. Observed answer:
        assertEquals(1, out.size, "retracting one of two live elements under the same lens must not be a no-op")
        cell.inlet.call.propagate(SetDelta(dels = mapOf(p1 to setOf(t1))))
        assertEquals(setOf(p2), out.last().puts.getValue("NYC"), "p2 must remain live: retraction discriminates by element identity, not by the caller-side lens")

        // Retract p2: the group dies (last member gone) — SQL group-death
        // semantics, `MapDelta` removal rather than an empty-set put.
        cell.inlet.call.propagate(SetDelta(dels = mapOf(p2 to setOf(t2))))
        assertEquals(setOf("NYC"), out.last().removals)
    }
}
