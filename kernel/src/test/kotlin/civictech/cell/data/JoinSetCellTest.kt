package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.joinSet
import civictech.cell.data.op.crossProduct

class JoinSetCellTest {

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

    private fun key(e: String) = e.first().toString()

    @Test
    fun `pairs enter when both rows are live and exit on either removal`() {
        val join = joinSet<String, String, String>(leftKey = ::key, rightKey = ::key)
        val out = collect(join.outlet)

        val t1 = tag(1); val t2 = tag(2)
        join.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(t1))))
        assertTrue(out.isEmpty())

        join.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(t2))))
        assertEquals(setOf("ax" to "a1"), tagFold(out))

        join.left.call.propagate(SetDelta(dels = mapOf("ax" to setOf(t1))))
        assertEquals(emptySet<Pair<String, String>>(), tagFold(out))
    }

    @Test
    fun `many-to-many keys yield all pairs`() {
        val join = joinSet<String, String, String>(leftKey = ::key, rightKey = ::key)
        val out = collect(join.outlet)

        join.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(tag(1)), "ay" to setOf(tag(2)))))
        join.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(tag(3)), "a2" to setOf(tag(4)))))

        assertEquals(
            setOf("ax" to "a1", "ax" to "a2", "ay" to "a1", "ay" to "a2"),
            tagFold(out),
        )
    }

    @Test
    fun `many-to-one combine keeps the output live until the last pair dies`() {
        // combine collapses every matched pair to just the key
        val join = JoinSetCell<String, String, String, String>(
            leftKey = ::key, rightKey = ::key, combine = { a, _ -> key(a) },
        )
        val out = collect(join.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        join.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(t1), "ay" to setOf(t2))))
        join.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(t3))))
        assertEquals(setOf("a"), tagFold(out)) // two pairs, one output element, two minted tags

        join.left.call.propagate(SetDelta(dels = mapOf("ax" to setOf(t1))))
        assertEquals(setOf("a"), tagFold(out), "output must survive via the (ay, a1) pair")

        join.left.call.propagate(SetDelta(dels = mapOf("ay" to setOf(t2))))
        assertEquals(emptySet<String>(), tagFold(out))
    }

    @Test
    fun `control - deleting the whole output element on one pair's exit diverges`() {
        // the failure class per-pair minting guards against: with a collapsing
        // combine, one pair's exit must not delete the tags the other pairs hold
        val m1 = tag(101); val m2 = tag(102)
        val naive = listOf(
            SetDelta(adds = mapOf("a" to setOf(m1))),      // pair (ax, a1) enters
            SetDelta(adds = mapOf("a" to setOf(m2))),      // pair (ay, a1) enters
            SetDelta(dels = mapOf("a" to setOf(m1, m2))),  // (ax, a1) exits — whole element deleted
        )
        assertEquals(emptySet<String>(), tagFold(naive), "control failed to reproduce the divergence")
        // the real cell's equivalent sequence is the previous test: "a" stays live
    }

    @Test
    fun `re-entering pairs mint fresh tags`() {
        val join = joinSet<String, String, String>(leftKey = ::key, rightKey = ::key)
        val out = collect(join.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        join.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(t1))))
        join.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(t2))))
        val firstTag = out.last().adds.getValue("ax" to "a1").single()

        join.right.call.propagate(SetDelta(dels = mapOf("a1" to setOf(t2))))
        join.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(t3))))
        val secondTag = out.last().adds.getValue("ax" to "a1").single()

        assertTrue(firstTag != secondTag, "re-entry must mint a fresh tag (tag hygiene, 21)")
        assertEquals(setOf("ax" to "a1"), tagFold(out))
    }

    @Test
    fun `cross product pairs everything`() {
        val cross = crossProduct<String, String>()
        val out = collect(cross.outlet)

        cross.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)), "y" to setOf(tag(2)))))
        cross.right.call.propagate(SetDelta(adds = mapOf("1" to setOf(tag(3)))))

        assertEquals(setOf("x" to "1", "y" to "1"), tagFold(out))
    }

    @Test
    fun `serves catch-up to late-linking subscribers folded under combine`() {
        val join = JoinSetCell<String, String, String, String>(
            leftKey = ::key, rightKey = ::key, combine = { a, _ -> key(a) },
        )
        join.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(tag(1)), "ay" to setOf(tag(2)))))
        join.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(tag(3)))))

        val late = CollectorCell()
        join.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        assertEquals(setOf("a"), tagFold(late.arrivals))
        assertEquals(2, late.arrivals.single().adds.getValue("a").size) // one tag per pair
    }

    @Test
    fun `join - incremental result equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val leftWriter = SetCell<String>()
            val rightWriter = SetCell<String>()
            val join = joinSet<String, String, String>(leftKey = ::key, rightKey = ::key)
            leftWriter.outlet.linkTo(join.left as LinkFrom<Propagate<SetDelta<String>>>)
            rightWriter.outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
            val out = collect(join.outlet)

            val leftDomain = listOf("ax", "ay", "bx", "by", "cx")
            val rightDomain = listOf("a1", "a2", "b1", "c1", "c2")
            val heldLeft = mutableSetOf<String>()
            val heldRight = mutableSetOf<String>()
            repeat(80) {
                if (rnd.nextBoolean()) {
                    val element = leftDomain[rnd.nextInt(leftDomain.size)]
                    if (rnd.nextInt(10) < 6 || element !in heldLeft) {
                        leftWriter.inlet.call.add(element); heldLeft += element
                    } else {
                        leftWriter.inlet.call.remove(element); heldLeft -= element
                    }
                } else {
                    val element = rightDomain[rnd.nextInt(rightDomain.size)]
                    if (rnd.nextInt(10) < 6 || element !in heldRight) {
                        rightWriter.inlet.call.add(element); heldRight += element
                    } else {
                        rightWriter.inlet.call.remove(element); heldRight -= element
                    }
                }
            }

            // batch nested loop over final memberships
            val batch = heldLeft.flatMap { a ->
                heldRight.filter { key(it) == key(a) }.map { b -> a to b }
            }.toSet()
            assertEquals(batch, tagFold(out), "join diverged from batch on seed $seed")
        }
    }
}
