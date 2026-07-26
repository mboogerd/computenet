package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MintedTags

class SemiJoinCellTest {

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
    fun `difference re-enters with a fresh minted tag when the subtrahend removes`() {
        val diff = differenceSet<String>()
        val out = collect(diff.outlet)

        diff.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)))))
        assertEquals(setOf("x"), tagFold(out))
        val firstEntry = out[0].adds.getValue("x").single()

        diff.right.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(2)))))
        assertEquals(emptySet<String>(), tagFold(out)) // x now on both sides

        diff.right.call.propagate(SetDelta(dels = mapOf("x" to setOf(tag(2)))))
        assertEquals(setOf("x"), tagFold(out), "x must re-enter under tombstone folding")
        val secondEntry = out[2].adds.getValue("x").single()
        assertNotEquals(firstEntry, secondEntry, "re-entry must mint a fresh tag (tag hygiene, 21)")
    }

    @Test
    fun `control - advertising input tags instead of minting diverges on subtrahend remove-re-add`() {
        // the failure class MintedTags guards against: re-entry rides the
        // right side's removal, so the only left tags available are exactly
        // the ones previously advertised and deleted — a tombstone-folding
        // consumer never sees the element come back
        val t1 = tag(1)
        val naive = listOf(
            SetDelta(adds = mapOf("x" to setOf(t1))), // left add: advertise left's live tags
            SetDelta(dels = mapOf("x" to setOf(t1))), // right add: exit, delete advertised
            SetDelta(adds = mapOf("x" to setOf(t1))), // right remove: re-enter — same tags again
        )
        assertEquals(emptySet<String>(), tagFold(naive), "control failed to reproduce the divergence")
        // the real cell's equivalent sequence is the previous test: x live at idle
    }

    @Test
    fun `semijoin keeps left rows whose key is present on the right`() {
        val semi = SemiJoinCell<String, String, Char>(
            leftKey = { it.first() },
            rightKey = { it.first() },
        )
        val out = collect(semi.outlet)

        semi.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(tag(1)), "bx" to setOf(tag(2)))))
        assertTrue(out.isEmpty()) // no right rows yet

        semi.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(tag(3)))))
        assertEquals(setOf("ax"), tagFold(out))

        // second right row under the same key: presence unchanged, no emission
        semi.right.call.propagate(SetDelta(adds = mapOf("a2" to setOf(tag(4)))))
        assertEquals(1, out.size)

        semi.right.call.propagate(SetDelta(dels = mapOf("a1" to setOf(tag(3)))))
        assertEquals(setOf("ax"), tagFold(out)) // still matched via a2

        semi.right.call.propagate(SetDelta(dels = mapOf("a2" to setOf(tag(4)))))
        assertEquals(emptySet<String>(), tagFold(out)) // key presence died
    }

    @Test
    fun `difference serves catch-up to late-linking subscribers`() {
        val diff = differenceSet<String>()
        diff.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)), "y" to setOf(tag(2)))))
        diff.right.call.propagate(SetDelta(adds = mapOf("y" to setOf(tag(3)))))

        val late = CollectorCell()
        diff.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        assertEquals(setOf("x"), tagFold(late.arrivals))
    }

    @Test
    fun `difference snapshot-restore preserves advertisements and continues correctly`() {
        val ref = CellRef(UUID.randomUUID())
        val diff = differenceSet<String>(ref)
        diff.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)), "y" to setOf(tag(2)))))
        diff.right.call.propagate(SetDelta(adds = mapOf("y" to setOf(tag(3)))))

        val restored = differenceSet<String>(ref)
        restored.restore(diff.snapshot())

        val late = CollectorCell()
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        assertEquals(setOf("x"), tagFold(late.arrivals))

        // continued operation on rebuilt indexes: right add of x retracts it
        restored.right.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(4)))))
        assertEquals(emptySet<String>(), tagFold(late.arrivals))
    }

    @Test
    fun `difference - incremental result equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = SetCell<String>()
            val b = SetCell<String>()
            val diff = differenceSet<String>()
            a.outlet.linkTo(diff.left as LinkFrom<Propagate<SetDelta<String>>>)
            b.outlet.linkTo(diff.right as LinkFrom<Propagate<SetDelta<String>>>)
            val out = collect(diff.outlet)

            val domain = ('a'..'f').map { it.toString() }
            val heldA = mutableSetOf<String>()
            val heldB = mutableSetOf<String>()
            repeat(80) {
                val element = domain[rnd.nextInt(domain.size)]
                val (cell, held) = if (rnd.nextBoolean()) a to heldA else b to heldB
                if (rnd.nextInt(10) < 6 || element !in held) {
                    cell.inlet.call.add(element); held += element
                } else {
                    cell.inlet.call.remove(element); held -= element
                }
            }

            assertEquals(heldA - heldB, tagFold(out), "difference diverged from batch on seed $seed")
        }
    }

    @Test
    fun `semijoin - incremental result equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val rows = SetCell<String>()
            val keys = SetCell<String>()
            val semi = SemiJoinCell<String, String, String>(
                leftKey = { it.first().toString() },
                rightKey = { it },
            )
            rows.outlet.linkTo(semi.left as LinkFrom<Propagate<SetDelta<String>>>)
            keys.outlet.linkTo(semi.right as LinkFrom<Propagate<SetDelta<String>>>)
            val out = collect(semi.outlet)

            val rowDomain = listOf("ax", "ay", "bx", "by", "cx", "cy")
            val keyDomain = listOf("a", "b", "c")
            val heldRows = mutableSetOf<String>()
            val heldKeys = mutableSetOf<String>()
            repeat(80) {
                if (rnd.nextBoolean()) {
                    val element = rowDomain[rnd.nextInt(rowDomain.size)]
                    if (rnd.nextInt(10) < 6 || element !in heldRows) {
                        rows.inlet.call.add(element); heldRows += element
                    } else {
                        rows.inlet.call.remove(element); heldRows -= element
                    }
                } else {
                    val element = keyDomain[rnd.nextInt(keyDomain.size)]
                    if (rnd.nextInt(10) < 6 || element !in heldKeys) {
                        keys.inlet.call.add(element); heldKeys += element
                    } else {
                        keys.inlet.call.remove(element); heldKeys -= element
                    }
                }
            }

            val batch = heldRows.filter { it.first().toString() in heldKeys }.toSet()
            assertEquals(batch, tagFold(out), "semijoin diverged from batch on seed $seed")
        }
    }
}
