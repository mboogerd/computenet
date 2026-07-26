package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.view.SetView

class KeyedSetCellTest {

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    /** Fold a set-delta stream into a live read model, as a downstream consumer would. */
    private fun view(outlet: Subscribe<Propagate<SetDelta<String>>>): SetView<String> {
        val v = SetView<String>()
        outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                v.apply(value)
            }
        }, PortRef.generate()))
        return v
    }

    @Test
    fun `re-put emits an add then an atomic retract-plus-add in one delta`() {
        val cell = KeyedSetCell<String, String>()
        val out = collect(cell.outlet)

        cell.inlet.call.put("k", "e1")
        cell.inlet.call.put("k", "e2")

        assertEquals(2, out.size)
        // first put: a plain add of e1
        assertEquals(setOf("e1"), out[0].adds.keys)
        assertTrue(out[0].dels.isEmpty())
        // re-put: retract e1 and add e2 in ONE delta — never two live, never zero
        assertEquals(setOf("e2"), out[1].adds.keys)
        assertEquals(setOf("e1"), out[1].dels.keys)
    }

    @Test
    fun `put of the identical element under a key is a no-op`() {
        val cell = KeyedSetCell<String, String>()
        val out = collect(cell.outlet)

        cell.inlet.call.put("k", "e1")
        cell.inlet.call.put("k", "e1")

        assertEquals(1, out.size) // no spurious churn
    }

    @Test
    fun `remove retracts only the current element for the key`() {
        val cell = KeyedSetCell<String, String>()
        val v = view(cell.outlet)

        cell.inlet.call.put("k1", "a")
        cell.inlet.call.put("k2", "b")
        cell.inlet.call.remove("k1")

        assertEquals(setOf("b"), v.current())
    }

    @Test
    fun `remove of a missing key is a no-op`() {
        val cell = KeyedSetCell<String, String>()
        val out = collect(cell.outlet)

        cell.inlet.call.remove("nope")

        assertTrue(out.isEmpty())
    }

    @Test
    fun `multiple keys are independent`() {
        val cell = KeyedSetCell<String, String>()
        val v = view(cell.outlet)

        cell.inlet.call.put("k1", "a")
        cell.inlet.call.put("k2", "b")
        cell.inlet.call.put("k3", "c")
        cell.inlet.call.put("k2", "b2") // re-put only k2

        assertEquals(setOf("a", "b2", "c"), v.current())
    }

    @Test
    fun `tag hygiene - after re-put the retracted element is fully dead and the new one carries a fresh tag`() {
        val cell = KeyedSetCell<String, String>()
        val out = collect(cell.outlet)
        val v = view(cell.outlet)

        cell.inlet.call.put("k", "e1")
        cell.inlet.call.put("k", "e2")

        // downstream membership: exactly one live element
        assertEquals(setOf("e2"), v.current())
        assertFalse("e1" in v)
        assertTrue("e2" in v)
        assertEquals(1, v.size)

        // the retracted element's tag is the very tag the re-put killed…
        val e1Tag = out[0].adds.getValue("e1").single()
        assertEquals(setOf(e1Tag), out[1].dels.getValue("e1"))
        // …and e2's add-tag is fresh, not e1's resurrected identity
        val e2Tag = out[1].adds.getValue("e2").single()
        assertNotEquals(e1Tag, e2Tag)
    }

    @Test
    fun `same element under two keys stays live until both keys drop it`() {
        val cell = KeyedSetCell<String, String>()
        val v = view(cell.outlet)

        cell.inlet.call.put("k1", "shared")
        cell.inlet.call.put("k2", "shared")
        assertEquals(setOf("shared"), v.current())

        // re-put k1 elsewhere: k2 still holds "shared", so it stays live
        cell.inlet.call.put("k1", "other")
        assertEquals(setOf("other", "shared"), v.current())

        // drop the last holder
        cell.inlet.call.remove("k2")
        assertEquals(setOf("other"), v.current())
    }

    @Test
    fun `onLinked delivers current elements as one delta-from-empty to a late subscriber`() {
        val cell = KeyedSetCell<String, String>()
        val live = view(cell.outlet)

        cell.inlet.call.put("k1", "a")
        cell.inlet.call.put("k2", "b")
        cell.inlet.call.put("k1", "a2") // re-put before the late join

        // late subscriber links after the writes: the onLinked handshake fires
        // the current state as one delta-from-empty
        val late = CollectorCell()
        cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        assertEquals(setOf("a2", "b"), tagFold(late.arrivals))
        assertEquals(live.current(), tagFold(late.arrivals))
        assertEquals(1, late.arrivals.size) // exactly one catch-up delta
    }

    @Test
    fun `snapshot-restore round-trips the key-to-element map and the tag counter`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = KeyedSetCell<String, String>(ref)
        cell.inlet.call.put("k1", "a")
        cell.inlet.call.put("k2", "b")
        cell.inlet.call.put("k1", "a2") // burns tag counters 1,2,3

        val restored = KeyedSetCell<String, String>(ref)
        restored.restore(roundTrip(cell.snapshot()))

        // restored membership matches, delivered via the catch-up handshake
        val late = CollectorCell()
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        assertEquals(setOf("a2", "b"), tagFold(late.arrivals))

        // a post-restore put must not re-mint a used tag: if the counter had
        // reset, this add-tag would collide with an already-observed one.
        val out = collect(restored.outlet)
        restored.inlet.call.put("k3", "c")
        val cTag = out.single().adds.getValue("c").single()
        assertTrue(cTag.counter > 3L, "post-restore tag counter must continue past used tags")
        assertEquals(setOf("a2", "b", "c"), tagFold(late.arrivals))
    }

    // --- downstream combination (the spec's core acceptance) --------------------

    // Elements are writer-key-prefixed, e.g. "w3": globally unique per key, so a
    // SetCell (whose remove retracts ALL of an element's tags) is a faithful
    // manual baseline. Grouping/flatMap is on the VALUE part, so distinct keys
    // holding the same value still collide many-to-one downstream.
    private fun valueOf(e: String) = e.drop(1)

    @Test
    fun `downstream GroupBy and FlatMap equal a manual remove-old-then-add sequence`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val writerKeys = listOf("w", "x", "y", "z")
            val values = listOf("1", "2", "5", "3", "7", "4")

            // pipeline under test: KeyedSetCell drives GroupBy (count per value)
            // and a FlatMapSetCell (element → its value group, a many-to-one map).
            val keyed = KeyedSetCell<String, String>()
            val keyedCount = GroupByCell(keyFn = ::valueOf, aggregator = Aggregators.count<String>())
            val keyedFlat = FlatMapSetCell<String, String>(f = { listOf("g:" + valueOf(it)) })
            val keyedFlatView = SetView<String>()
            keyed.outlet.linkTo(keyedCount.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            keyed.outlet.linkTo(keyedFlat.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            keyedFlat.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) { keyedFlatView.apply(value) }
            }, PortRef.generate()))
            val keyedCountOut = collect(keyedCount.outlet)

            // manual baseline: a SetCell with the app-maintained shadow index,
            // issuing remove-old-then-add exactly as the demos do by hand.
            val manual = SetCell<String>()
            val manualCount = GroupByCell(keyFn = ::valueOf, aggregator = Aggregators.count<String>())
            val manualFlat = FlatMapSetCell<String, String>(f = { listOf("g:" + valueOf(it)) })
            val manualFlatView = SetView<String>()
            manual.outlet.linkTo(manualCount.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            manual.outlet.linkTo(manualFlat.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            manualFlat.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) { manualFlatView.apply(value) }
            }, PortRef.generate()))
            val manualCountOut = collect(manualCount.outlet)
            val shadow = mutableMapOf<String, String>() // the index F-3 eliminates

            repeat(80) {
                val k = writerKeys[rnd.nextInt(writerKeys.size)]
                if (rnd.nextInt(10) < 7) {
                    val e = k + values[rnd.nextInt(values.size)] // writer-key-prefixed
                    keyed.inlet.call.put(k, e)
                    // manual remove-old-then-add against the hand-kept index
                    shadow[k]?.let { if (it != e) manual.inlet.call.remove(it) }
                    if (shadow[k] != e) manual.inlet.call.add(e)
                    shadow[k] = e
                } else {
                    keyed.inlet.call.remove(k)
                    shadow.remove(k)?.let { manual.inlet.call.remove(it) }
                }
            }

            assertEquals(
                mapFold(manualCountOut), mapFold(keyedCountOut),
                "keyed GroupBy diverged from manual remove-old-then-add on seed $seed",
            )
            assertEquals(
                manualFlatView.current(), keyedFlatView.current(),
                "keyed FlatMap diverged from manual on seed $seed",
            )
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
