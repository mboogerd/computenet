package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.Link
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

class PresenceCountCellTest {

    @Suppress("UNCHECKED_CAST")
    private fun link(source: SetCell<Int>, cell: PresenceCountCell<Int>): Link =
        (source.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<Int>>>) as LinkResult.Connected).link

    private fun collect(outlet: Subscribe<Propagate<MapDelta<Int, Int>>>): MutableList<MapDelta<Int, Int>> {
        val collected = mutableListOf<MapDelta<Int, Int>>()
        outlet.subscribe(Use.fixed(object : Propagate<MapDelta<Int, Int>> {
            override fun propagate(value: MapDelta<Int, Int>) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    @Test
    fun `presence count rises and falls with distinct live sources`() {
        val cell = PresenceCountCell<Int>()
        val out = collect(cell.outlet)
        val s1 = SetCell<Int>()
        val s2 = SetCell<Int>()
        link(s1, cell)
        link(s2, cell)

        s1.inlet.call.add(7) // put(7, 1)
        s2.inlet.call.add(7) // put(7, 2) — a second distinct live source
        s1.inlet.call.remove(7) // put(7, 1)
        s2.inlet.call.remove(7) // removal(7) — count drops to 0

        assertEquals(listOf(mapOf(7 to 1), mapOf(7 to 2), mapOf(7 to 1)), out.dropLast(1).map { it.puts })
        assertEquals(setOf(7), out.last().removals)
        assertEquals(emptyMap<Int, Int>(), mapFold(out))
    }

    @Test
    fun `tag churn that does not move a lane's membership emits nothing`() {
        val cell = PresenceCountCell<Int>()
        val out = collect(cell.outlet)
        val s1 = SetCell<Int>()
        link(s1, cell)

        s1.inlet.call.add(7) // put(7, 1)
        assertEquals(1, out.size)

        // a second add-tag on the same source: lane membership unchanged, count still 1
        s1.inlet.call.add(7)
        assertEquals(1, out.size)

        // retracting one of two live tags: the source still asserts 7, count still 1
        s1.inlet.call.remove(7) // removes both observed tags, actually — SetCell.remove covers all live
        // (SetCell.remove covers every live tag, so 7 leaves; assert the retraction is the only new event)
        assertEquals(2, out.size)
        assertEquals(setOf(7), out.last().removals)
    }

    @Test
    fun `opening an empty source link recomputes nothing`() {
        val cell = PresenceCountCell<Int>()
        val s1 = SetCell<Int>()
        link(s1, cell)
        s1.inlet.call.add(7)

        val out = collect(cell.outlet) // subscribe after the churn: no catch-up on a Use.fixed link
        val s2 = SetCell<Int>() // empty
        link(s2, cell) // EdgeOpen of an empty lane + empty catch-up: no element's count moves

        assertEquals(0, out.size)
    }

    @Test
    fun `closing a link retracts exactly that source's sole contributions`() {
        val cell = PresenceCountCell<Int>()
        val s1 = SetCell<Int>()
        val s2 = SetCell<Int>()
        link(s1, cell)
        val l2 = link(s2, cell)

        s1.inlet.call.add(7)
        s2.inlet.call.add(7) // 7 asserted by both
        s2.inlet.call.add(9) // 9 asserted only by s2

        val out = collect(cell.outlet)
        l2.unlink() // EdgeClose: 7 falls to count 1, 9 falls to 0 (its sole source left)

        assertEquals(mapOf(7 to 1), out.flatMap { it.puts.entries }.associate { it.toPair() })
        assertEquals(setOf(9), out.flatMap { it.removals }.toSet())
    }

    @Test
    fun `late join re-emits current counts as a delta-from-empty`() {
        val cell = PresenceCountCell<Int>()
        val s1 = SetCell<Int>()
        val s2 = SetCell<Int>()
        link(s1, cell)
        link(s2, cell)
        s1.inlet.call.add(7)
        s2.inlet.call.add(7)
        s1.inlet.call.add(9)

        val late = CountCollector()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<Int, Int>>>)

        assertEquals(mapOf(7 to 2, 9 to 1), mapFold(late.arrivals))
    }

    @Test
    fun `snapshot-restore round-trips per-link state`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = PresenceCountCell<Int>(ref)
        val s1 = SetCell<Int>()
        val s2 = SetCell<Int>()
        link(s1, cell)
        link(s2, cell)
        s1.inlet.call.add(7)
        s2.inlet.call.add(7)
        s1.inlet.call.add(9)

        // counts (7 -> 2, 9 -> 1) are rebuilt purely from the restored lanes
        val restored = PresenceCountCell<Int>(ref)
        restored.restore(roundTrip(cell.snapshot()))

        val late = CountCollector()
        @Suppress("UNCHECKED_CAST")
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<Int, Int>>>)
        assertEquals(mapOf(7 to 2, 9 to 1), mapFold(late.arrivals))
    }

    @Test
    fun `pipeline - emitted counts equal batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val cell = PresenceCountCell<Int>()
            val view = MapView<Int, Int>()
            cell.outlet.subscribe(Use.fixed(object : Propagate<MapDelta<Int, Int>> {
                override fun propagate(value: MapDelta<Int, Int>) {
                    view.apply(value)
                }
            }, PortRef.generate()))

            val sources = List(4) { SetCell<Int>() }
            val links = arrayOfNulls<Link>(4)
            val held = List(4) { mutableSetOf<Int>() }
            val domain = listOf(1, 2, 3, 4, 5)

            // start with two live source links; the other two join/leave over the run
            links[0] = link(sources[0], cell)
            links[1] = link(sources[1], cell)

            repeat(140) {
                when (rnd.nextInt(6)) {
                    0, 1, 2 -> { // add to any source (whether currently linked or not)
                        val s = rnd.nextInt(4)
                        val e = domain[rnd.nextInt(domain.size)]
                        sources[s].inlet.call.add(e)
                        held[s] += e
                    }

                    3 -> { // remove from any source
                        val s = rnd.nextInt(4)
                        if (held[s].isNotEmpty()) {
                            val e = held[s].elementAt(rnd.nextInt(held[s].size))
                            sources[s].inlet.call.remove(e)
                            held[s] -= e
                        }
                    }

                    4 -> { // open a link on a currently-unlinked source
                        val idle = (0 until 4).filter { links[it] == null }
                        if (idle.isNotEmpty()) {
                            val s = idle[rnd.nextInt(idle.size)]
                            links[s] = link(sources[s], cell)
                        }
                    }

                    5 -> { // close a link (keep at least one open)
                        val live = (0 until 4).filter { links[it] != null }
                        if (live.size > 1) {
                            val s = live[rnd.nextInt(live.size)]
                            links[s]!!.unlink()
                            links[s] = null
                        }
                    }
                }
            }

            val batch = domain.associateWith { e -> (0 until 4).count { links[it] != null && e in held[it] } }
                .filterValues { it > 0 }
            assertEquals(batch, view.current(), "presence counts diverged from batch on seed $seed")
        }
    }

    class CountCollector(val arrivals: MutableList<MapDelta<Int, Int>> = mutableListOf()) {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<Int, Int>>>))

        init {
            inlet.serve(object : Propagate<MapDelta<Int, Int>> {
                override fun propagate(value: MapDelta<Int, Int>) {
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
