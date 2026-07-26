package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.link.Link
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.view.SetView

class QuorumSetCellTest {

    @Suppress("UNCHECKED_CAST")
    private fun link(source: SetCell<Int>, cell: QuorumSetCell<Int>): Link =
        (source.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<Int>>>) as LinkResult.Connected).link

    @Suppress("UNCHECKED_CAST")
    private fun linkTo(source: SetCell<Int>, port: Any) {
        source.outlet.linkTo(port as LinkFrom<Propagate<SetDelta<Int>>>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun chain(from: IntersectSetCell<Int>, port: Any) {
        from.outlet.linkTo(port as LinkFrom<Propagate<SetDelta<Int>>>)
    }

    private fun setView(outlet: Subscribe<Propagate<SetDelta<Int>>>): SetView<Int> {
        val view = SetView<Int>()
        outlet.subscribe(Use.fixed(object : Propagate<SetDelta<Int>> {
            override fun propagate(value: SetDelta<Int>) {
                view.apply(value)
            }
        }, PortRef.generate()))
        return view
    }

    private fun collect(outlet: Subscribe<Propagate<SetDelta<Int>>>): MutableList<SetDelta<Int>> {
        val collected = mutableListOf<SetDelta<Int>>()
        outlet.subscribe(Use.fixed(object : Propagate<SetDelta<Int>> {
            override fun propagate(value: SetDelta<Int>) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    @Test
    fun `intersection quorum equals a chained IntersectSetCell over random schedules`() {
        for (seed in 0L until 50L) {
            val rnd = Random(seed)
            val sources = List(3) { SetCell<Int>() }

            val quorum = QuorumSetCell.intersection<Int>()
            sources.forEach { link(it, quorum) }

            // s0 ∩ s1 then ∩ s2 — the binary chain the primitive generalises
            val i1 = IntersectSetCell<Int>()
            val i2 = IntersectSetCell<Int>()
            linkTo(sources[0], i1.left)
            linkTo(sources[1], i1.right)
            chain(i1, i2.left)
            linkTo(sources[2], i2.right)

            val qView = setView(quorum.outlet)
            val iView = setView(i2.outlet)

            val domain = listOf(1, 2, 3)
            val held = List(3) { mutableSetOf<Int>() }
            repeat(120) {
                val s = rnd.nextInt(3)
                val e = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || e !in held[s]) {
                    sources[s].inlet.call.add(e); held[s] += e
                } else {
                    sources[s].inlet.call.remove(e); held[s] -= e
                }
                assertEquals(iView.current(), qView.current(), "intersection diverged on seed $seed")
            }
        }
    }

    @Test
    fun `threshold-of-one quorum equals UnionSetCell membership over random schedules`() {
        for (seed in 0L until 50L) {
            val rnd = Random(seed)
            val sources = List(3) { SetCell<Int>() }

            val quorum = QuorumSetCell.union<Int>()
            sources.forEach { link(it, quorum) }

            val union = UnionSetCell<Int>()
            sources.forEach { linkTo(it, union.inlet) }

            val qView = setView(quorum.outlet)
            val uView = setView(union.outlet)

            val domain = listOf(1, 2, 3)
            val held = List(3) { mutableSetOf<Int>() }
            repeat(120) {
                val s = rnd.nextInt(3)
                val e = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || e !in held[s]) {
                    sources[s].inlet.call.add(e); held[s] += e
                } else {
                    sources[s].inlet.call.remove(e); held[s] -= e
                }
                assertEquals(uView.current(), qView.current(), "union diverged on seed $seed")
            }
        }
    }

    @Test
    fun `adding a fourth source link tightens a running intersection with no re-spawn`() {
        val quorum = QuorumSetCell.intersection<Int>()
        val view = setView(quorum.outlet)
        val s = List(4) { SetCell<Int>() }
        (0..2).forEach { link(s[it], quorum) }

        s.take(3).forEach { it.inlet.call.add(1) } // 1 shared by all three → in the intersection
        assertEquals(setOf(1), view.current())

        // a fourth (empty) source raises the threshold to 4 — 1 (count 3) drops out
        val l4 = link(s[3], quorum)
        assertEquals(emptySet<Int>(), view.current())

        // the fourth asserts 1 → shared by all four again
        s[3].inlet.call.add(1)
        assertEquals(setOf(1), view.current())

        // dropping the fourth link restores the three-source intersection
        l4.unlink()
        assertEquals(setOf(1), view.current())
    }

    @Test
    fun `closing a link raises a near-miss element as the threshold falls`() {
        val quorum = QuorumSetCell.nearMiss<Int>() // threshold n - 1
        val view = setView(quorum.outlet)
        val s = List(3) { SetCell<Int>() }
        link(s[0], quorum)
        link(s[1], quorum)
        val l2 = link(s[2], quorum)

        s[0].inlet.call.add(5) // 5 held by one of three sources: count 1 < n-1 (=2) → not surfaced
        assertEquals(emptySet<Int>(), view.current())

        // closing an unrelated source makes n = 2, threshold n-1 = 1 → 5 now qualifies
        l2.unlink()
        assertEquals(setOf(5), view.current())
    }

    @Test
    fun `near-miss and majority surface the shared-by-most elements with three sources`() {
        val near = QuorumSetCell.nearMiss<Int>()
        val majority = QuorumSetCell.majority<Int>()
        val nearView = setView(near.outlet)
        val majView = setView(majority.outlet)
        val s = List(3) { SetCell<Int>() }
        s.forEach { link(it, near); link(it, majority) }

        // 7 shared by 2 of 3: meets near-miss (n-1 = 2) and majority (n/2+1 = 2)
        s[0].inlet.call.add(7)
        s[1].inlet.call.add(7)
        assertEquals(setOf(7), nearView.current())
        assertEquals(setOf(7), majView.current())

        // 9 held by only one source: meets neither
        s[0].inlet.call.add(9)
        assertEquals(setOf(7), nearView.current())
        assertEquals(setOf(7), majView.current())
    }

    @Test
    fun `tag churn that does not cross the threshold emits nothing`() {
        val quorum = QuorumSetCell.intersection<Int>()
        val out = collect(quorum.outlet)
        val s0 = SetCell<Int>()
        val s1 = SetCell<Int>()
        link(s0, quorum)
        link(s1, quorum)

        s0.inlet.call.add(7)
        s1.inlet.call.add(7) // 7 now in both → enters the intersection (one add emission)
        assertEquals(1, out.size)

        // a redundant add-tag on an already-in element: count unchanged → no emission
        s0.inlet.call.add(7)
        assertEquals(1, out.size)
    }

    @Test
    fun `output tags track downstream membership exactly - enters once, leaves clean`() {
        val quorum = QuorumSetCell.intersection<Int>()
        val view = setView(quorum.outlet)
        val s0 = SetCell<Int>()
        val s1 = SetCell<Int>()
        link(s0, quorum)
        link(s1, quorum)

        s0.inlet.call.add(7)
        s1.inlet.call.add(7)
        assertEquals(setOf(7), view.current()) // enters once

        s0.inlet.call.remove(7)
        assertEquals(emptySet<Int>(), view.current()) // drops below threshold → leaves cleanly, no lingering tags

        s0.inlet.call.add(7)
        assertEquals(setOf(7), view.current()) // re-enters cleanly
    }

    @Test
    fun `late join re-emits the advertised quorum as a delta-from-empty`() {
        val quorum = QuorumSetCell.intersection<Int>()
        val s0 = SetCell<Int>()
        val s1 = SetCell<Int>()
        link(s0, quorum)
        link(s1, quorum)
        s0.inlet.call.add(7)
        s1.inlet.call.add(7)

        val late = SetCollector()
        @Suppress("UNCHECKED_CAST")
        quorum.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<Int>>>)

        assertEquals(setOf(7), tagFold(late.arrivals))
    }

    @Test
    fun `snapshot-restore round-trips lanes and the advertised quorum`() {
        val ref = CellRef(UUID.randomUUID())
        val quorum = QuorumSetCell<Int>(ref) { n -> n }
        val s0 = SetCell<Int>()
        val s1 = SetCell<Int>()
        link(s0, quorum)
        link(s1, quorum)
        s0.inlet.call.add(7)
        s1.inlet.call.add(7)

        val restored = QuorumSetCell<Int>(ref) { n -> n }
        restored.restore(roundTrip(quorum.snapshot()))

        val late = SetCollector()
        @Suppress("UNCHECKED_CAST")
        restored.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<Int>>>)

        assertEquals(setOf(7), tagFold(late.arrivals))
    }

    @Test
    fun `pipeline - majority quorum equals batch recompute under churn and link open-close`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val quorum = QuorumSetCell.majority<Int>()
            val view = setView(quorum.outlet)

            val sources = List(4) { SetCell<Int>() }
            val links = arrayOfNulls<Link>(4)
            val held = List(4) { mutableSetOf<Int>() }
            val domain = listOf(1, 2, 3, 4, 5)

            links[0] = link(sources[0], quorum)
            links[1] = link(sources[1], quorum)
            links[2] = link(sources[2], quorum)

            repeat(140) {
                when (rnd.nextInt(6)) {
                    0, 1, 2 -> {
                        val s = rnd.nextInt(4)
                        val e = domain[rnd.nextInt(domain.size)]
                        sources[s].inlet.call.add(e); held[s] += e
                    }

                    3 -> {
                        val s = rnd.nextInt(4)
                        if (held[s].isNotEmpty()) {
                            val e = held[s].elementAt(rnd.nextInt(held[s].size))
                            sources[s].inlet.call.remove(e); held[s] -= e
                        }
                    }

                    4 -> {
                        val idle = (0 until 4).filter { links[it] == null }
                        if (idle.isNotEmpty()) {
                            val s = idle[rnd.nextInt(idle.size)]
                            links[s] = link(sources[s], quorum)
                        }
                    }

                    5 -> {
                        val live = (0 until 4).filter { links[it] != null }
                        if (live.size > 1) {
                            val s = live[rnd.nextInt(live.size)]
                            links[s]!!.unlink(); links[s] = null
                        }
                    }
                }
            }

            val liveHeld = (0 until 4).filter { links[it] != null }.map { held[it] }
            val n = liveHeld.size
            val threshold = n / 2 + 1
            val batch = domain.filterTo(mutableSetOf()) { e ->
                val c = liveHeld.count { e in it }
                c >= 1 && c >= threshold
            }
            assertEquals(batch, view.current(), "majority quorum diverged from batch on seed $seed")
        }
    }

    @Test
    fun `k-of-n factory surfaces elements shared by at least k sources`() {
        val quorum = QuorumSetCell.kOfN<Int>(2)
        val view = setView(quorum.outlet)
        val s = List(3) { SetCell<Int>() }
        s.forEach { link(it, quorum) }

        s[0].inlet.call.add(1)
        assertEquals(emptySet<Int>(), view.current()) // 1 source < k
        s[1].inlet.call.add(1)
        assertEquals(setOf(1), view.current()) // 2 sources ≥ k
        s[2].inlet.call.add(1)
        assertEquals(setOf(1), view.current()) // still meets k, no double-entry
    }

    class SetCollector(val arrivals: MutableList<SetDelta<Int>> = mutableListOf()) {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<Int>>>))

        init {
            inlet.serve(object : Propagate<SetDelta<Int>> {
                override fun propagate(value: SetDelta<Int>) {
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
