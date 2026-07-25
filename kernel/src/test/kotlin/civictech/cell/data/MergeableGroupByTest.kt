package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * CP-G1 — the mergeable, [Replicable] group-by. Three concerns:
 *  1. [MapDelta.merge] obeys the merge laws (commutative + associative under 100
 *     generative orders; idempotent where the operator is) that make aggregate
 *     gossip converge.
 *  2. A three-replica mesh of [MergeableGroupByCell] converges to the batch
 *     group-by of the UNION of all inputs while gossiping only O(groups)
 *     aggregates — not the O(input) membership [GroupByCell] would replicate.
 *  3. Both mandated controls diverge: (a) plain [GroupByCell] under
 *     last-writer-wins `MapDelta` replication loses a concurrent partial sum;
 *     (b) a non-commutative merge diverges by delivery order.
 */
class MergeableGroupByTest {

    // "a3" → region 'a', amount 3
    private fun region(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()
    private fun tag(n: Long) = Timestamp(UUID(0, n), n)

    // ------------------------------------------------------------------
    // 1. merge laws on MapDelta.merge
    // ------------------------------------------------------------------

    private fun randomDelta(rnd: Random): MapDelta<String, Long> {
        val keys = listOf("a", "b", "c", "d")
        val puts = keys.filter { rnd.nextBoolean() }.associateWith { rnd.nextInt(50).toLong() }
        val removals = keys.filter { rnd.nextInt(4) == 0 }.toSet() - puts.keys
        return MapDelta(puts, removals)
    }

    @Test
    fun `merge is commutative and associative under 100 generative orders (max)`() {
        val max: (Long, Long) -> Long = ::maxOf
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val deltas = List(2 + rnd.nextInt(5)) { randomDelta(rnd) }
            val reference = deltas.reduce { a, b -> a.merge(b, max) }.normalized()
            // fold the SAME set of deltas in a fresh random permutation; a
            // commutative-associative op must reach the identical result.
            repeat(5) {
                val shuffled = deltas.shuffled(rnd)
                val folded = shuffled.reduce { a, b -> a.merge(b, max) }.normalized()
                assertEquals(reference, folded, "order-dependent fold on seed $seed")
            }
        }
    }

    @Test
    fun `merge is idempotent where the operator is (max)`() {
        val max: (Long, Long) -> Long = ::maxOf
        val rnd = Random(7)
        repeat(100) {
            val d = randomDelta(rnd)
            // folding a delta into itself changes nothing beyond normalization
            assertEquals(d.normalized(), d.merge(d, max).normalized())
        }
    }

    // ------------------------------------------------------------------
    // 2. three-replica mesh converges gossiping only aggregates
    // ------------------------------------------------------------------

    /** A deterministic gossip pump: each cell's outlet enqueues its emissions;
     *  drain delivers each to the OTHER cells' deltaInlet until quiescent. */
    private class Mesh(val cells: List<MergeableGroupByCell<String, String, Long>>) {
        private val queue = ArrayDeque<Pair<Int, MapDelta<String, Long>>>()
        var gossipedEntries = 0; private set

        init {
            cells.forEachIndexed { i, cell ->
                cell.outlet.subscribe(Use.fixed(Propagate<MapDelta<String, Long>> { queue += i to it }, PortRef.generate()))
            }
        }

        fun drain() {
            while (queue.isNotEmpty()) {
                val (from, delta) = queue.removeFirst()
                cells.forEachIndexed { j, cell ->
                    if (j != from) {
                        gossipedEntries += delta.puts.size + delta.removals.size
                        cell.deltaInlet.call.propagate(delta)
                    }
                }
            }
        }
    }

    private fun maxCell() = MergeableGroupByCell<String, String, Long>(
        keyOf = ::region, accumulate = ::amount, merge = ::maxOf,
    )

    @Test
    fun `three-replica mesh converges to batch group-by of the union, gossiping only aggregates`() {
        // many inputs (40) over few groups (4): the contrast between input- and
        // aggregate-sized gossip is only visible when inputs-per-group ≫ 1.
        val groups = listOf("a", "b", "c", "d")
        val inputCount = 120 // ≫ groups, so input- vs aggregate-sized gossip diverges sharply
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val domain = List(inputCount) { i -> groups[i % groups.size] + (rnd.nextInt(1000).toLong() + i) }
            val cells = List(3) { maxCell() }
            val mesh = Mesh(cells)

            // partition the domain: each element lands on exactly one replica's
            // local inlet (disjoint LOCAL inputs; the union is the whole domain).
            val perReplica = List(3) { mutableListOf<String>() }
            val union = mutableSetOf<String>()
            var t = 0L
            domain.forEach { e -> perReplica[rnd.nextInt(3)] += e; union += e }
            // one batched SetDelta per replica: the local fold collapses N inputs
            // into O(groups) aggregate emissions — the whole point of the cell.
            perReplica.forEachIndexed { r, es ->
                cells[r].inlet.call.propagate(SetDelta(adds = es.associateWith { setOf(tag(t++)) }))
            }
            mesh.drain()

            // batch truth: group-by MAX over the union of all inputs
            val expected = union.groupBy { region(it) }.mapValues { (_, es) -> es.maxOf { amount(it) } }
            cells.forEachIndexed { i, c ->
                assertEquals(expected, c.aggregates(), "replica $i diverged on seed $seed")
            }

            // aggregate-sized, NOT input-sized: replicating the input membership
            // (what a plain GroupByCell mesh must do — recompute from replicated
            // inputs) costs at least inputCount×(replicas−1) element-crossings.
            // Gossiping aggregates stays strictly below that, and independent of
            // how many inputs fold into each group.
            val inputReplicationBaseline = union.size * (cells.size - 1)
            assertTrue(
                mesh.gossipedEntries < inputReplicationBaseline,
                "gossip ${mesh.gossipedEntries} not below input baseline $inputReplicationBaseline on seed $seed",
            )
        }
    }

    // ------------------------------------------------------------------
    // 3a. control: plain GroupByCell under last-writer-wins loses a partial
    // ------------------------------------------------------------------

    @Test
    fun `control - plain GroupByCell under last-writer-wins MapDelta loses a concurrent partial sum`() {
        // two peers each hold DISJOINT orders for the SAME region 'a'; batch
        // sum over the union is 10 + 5 = 15.
        val g0 = GroupByCell<String, String, Long, Long>(keyFn = ::region, aggregator = Aggregators.sumOf(::amount))
        val g1 = GroupByCell<String, String, Long, Long>(keyFn = ::region, aggregator = Aggregators.sumOf(::amount))
        val d0 = collectMap(g0); val d1 = collectMap(g1)
        g0.inlet.call.propagate(SetDelta(adds = mapOf("a10" to setOf(tag(1)))))
        g1.inlet.call.propagate(SetDelta(adds = mapOf("a5" to setOf(tag(2)))))
        val p0 = d0.single(); val p1 = d1.single()

        // last-writer-wins replication (the plain MapDelta contract: replace):
        // one peer's partial is silently overwritten.
        val lww = mutableMapOf<String, Long>()
        lww.putAll(p0.puts); lww.putAll(p1.puts)
        assertNotEquals(15L, lww["a"], "LWW should have lost a partial, not summed")

        // the mergeable merge path (sum) recovers the true total.
        val merged = p0.merge(p1, Long::plus)
        assertEquals(15L, merged.puts["a"], "mergeable merge must recover the concurrent partial")
    }

    // ------------------------------------------------------------------
    // 3b. control: a non-commutative merge diverges by delivery order
    // ------------------------------------------------------------------

    @Test
    fun `control - a non-commutative merge diverges by delivery order`() {
        val subtract: (Long, Long) -> Long = { a, b -> a - b } // non-commutative
        var diverged = 0
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            // overlapping keys so the operator is actually exercised
            val deltas = List(3) { MapDelta(mapOf("a" to rnd.nextInt(20).toLong()), emptySet<String>()) }
            val forward = deltas.reduce { a, b -> a.merge(b, subtract) }.puts["a"]
            val reverse = deltas.reversed().reduce { a, b -> a.merge(b, subtract) }.puts["a"]
            if (forward != reverse) diverged++
        }
        assertTrue(diverged > 0, "non-commutative control never diverged — the law is not load-bearing")
    }

    // ------------------------------------------------------------------

    private fun collectMap(cell: GroupByCell<String, String, Long, Long>): MutableList<MapDelta<String, Long>> {
        val out = mutableListOf<MapDelta<String, Long>>()
        cell.outlet.subscribe(Use.fixed(Propagate<MapDelta<String, Long>> { out += it }, PortRef.generate()))
        return out
    }

    /** Drop zero-effect noise so law comparisons are on net content only. */
    private fun MapDelta<String, Long>.normalized() =
        MapDelta(puts.filterKeys { it !in removals }, removals)
}
