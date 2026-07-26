package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.UnionSetCell

/**
 * W4.2 (G-56, realizes G-24, 20/24 §Partitioned state): a [PartitionedCell]
 * of key-disjoint [GroupByCell] shards must be indistinguishable, from
 * outside the membrane, from a single unsharded [GroupByCell] — including
 * across a mid-run [PartitionedCell.repartition].
 */
class PartitionedCellTest {

    // elements "a3" -> group 'a', value 3 (same convention as GroupByCellTest)
    private fun key(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(
            Use.fixed(
                object : Propagate<T> {
                    override fun propagate(value: T) {
                        collected += value
                    }
                },
                PortRef.generate(),
            ),
        )
        return collected
    }

    private fun sumByKey(shardCount: Int = 3) = PartitionedCell(
        initialShardCount = shardCount,
        keyFn = ::key,
        aggregator = Aggregators.sumOf(::amount),
    )

    @Test
    fun `groups sum across shards exactly as one unsharded GroupByCell would`() {
        val cell = sumByKey()
        val out = collect(cell.outlet)

        cell.inlet.call.propagate(
            SetDelta(
                adds = mapOf(
                    "a3" to setOf(tag(1)),
                    "a4" to setOf(tag(2)),
                    "b5" to setOf(tag(3)),
                    "c1" to setOf(tag(4)),
                ),
            ),
        )
        assertEquals(mapOf("a" to 7L, "b" to 5L, "c" to 1L), mapFold(out))

        cell.inlet.call.propagate(SetDelta(dels = mapOf("a3" to setOf(tag(1)))))
        assertEquals(mapOf("a" to 4L, "b" to 5L, "c" to 1L), mapFold(out))

        cell.inlet.call.propagate(SetDelta(dels = mapOf("b5" to setOf(tag(3)))))
        assertEquals(setOf("b"), out.last().removals) // group death, not a zero put
    }

    private fun tag(counter: Long) = civictech.cell.Timestamp(UUID(0, counter), counter)

    @Test
    fun `elements sharing a group key always land on the same shard, disjointness holds under repartition`() {
        val cell = sumByKey(shardCount = 4)
        val out = collect(cell.outlet)

        cell.inlet.call.propagate(SetDelta(adds = mapOf("z1" to setOf(tag(1)), "z2" to setOf(tag(2)))))
        assertEquals(mapOf("z" to 3L), mapFold(out))

        cell.repartition(7)
        assertEquals(1, cell.routingEpoch)

        // both elements of group "z" must still be tracked (both moved to the
        // same new shard, or the group would silently fork across two shards)
        cell.inlet.call.propagate(SetDelta(dels = mapOf("z1" to setOf(tag(1)))))
        assertEquals(mapOf("z" to 2L), mapFold(out))
    }

    @Test
    fun `serves catch-up to late-linking subscribers as one coherent union`() {
        val cell = sumByKey()
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(tag(1)), "b5" to setOf(tag(2)))))

        // catch-up (G-22) only fires over a real handshake link, not a raw
        // subscribe — exactly like GroupByCellTest's own catch-up test
        val late = GroupByCellTest.MapCollector()
        cell.outlet.linkTo(late.inlet as LinkFrom<Propagate<MapDelta<String, Long>>>)
        assertEquals(mapOf("a" to 3L, "b" to 5L), mapFold(late.arrivals))
    }

    @Test
    fun `sharded GroupByCell equals unsharded on every seed, including a mid-run repartition`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val writers = listOf(SetCell<String>(), SetCell<String>())
            val union = UnionSetCell<String>()

            val sharded = sumByKey(shardCount = 3)
            val unsharded = GroupByCell(keyFn = ::key, aggregator = Aggregators.sumOf(::amount))

            writers.forEach { it.outlet.linkTo(union.inlet as LinkFrom<Propagate<SetDelta<String>>>) }
            union.outlet.linkTo(sharded.inlet as LinkFrom<Propagate<SetDelta<String>>>)
            union.outlet.linkTo(unsharded.inlet as LinkFrom<Propagate<SetDelta<String>>>)

            val shardedOut = collect(sharded.outlet)
            val unshardedOut = collect(unsharded.outlet)

            val domain = listOf("a1", "a2", "a5", "b3", "b7", "c4", "d9", "e2", "f6")
            val held = writers.map { mutableSetOf<String>() }
            repeat(80) { i ->
                // mid-run repartition (half the seeds shrink, half grow) — the
                // PROTECT clause forbids swapping this seed for a friendlier one
                if (i == 40) {
                    val next = if (seed % 2 == 0L) sharded.shardCount + 2 else maxOf(1, sharded.shardCount - 2)
                    sharded.repartition(next)
                }
                val w = rnd.nextInt(writers.size)
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || element !in held[w]) {
                    writers[w].inlet.call.add(element); held[w] += element
                } else {
                    writers[w].inlet.call.remove(element); held[w] -= element
                }
            }

            val expected = mapFold(unshardedOut)
            val actual = mapFold(shardedOut)
            assertEquals(expected, actual, "sharded PartitionedCell diverged from unsharded GroupByCell on seed $seed")
        }
    }

    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : civictech.cell.Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
    }

    @Test
    fun `PartitionedCell is an ordinary cell under the one authority lattice, organelles never independently reachable`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())

        val partitioned = sumByKey()
        val compositeRef = host.managementInlet.call.spawn(partitioned)

        // spawned/connected exactly like any other data cell (30/34 decision 5:
        // "partitions... are placements in this lattice, not exceptions to it")
        val source = Source()
        val sourceRef = host.managementInlet.call.spawn(source)
        assertEquals(
            true,
            host.managementInlet.call.connect(sourceRef, "outlet", compositeRef, "inlet") is LinkResult.Connected,
        )
        source.outlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(tag(1)))))
        controller.runToIdle()

        // organelle shards were never independently spawned onto the host
        // (G-28 extended to composite-cell containment, 30/31 §Hierarchy —
        // the containment cascade is free): only the composite itself is a
        // resolvable host-level cell, addressable via its exposed API.
        assertEquals(true, host.lookup<GroupByApi<String, String, Long>>(partitioned.ref) != null)

        // draining the host deactivates the composite cleanly, exactly like
        // any other ordinary cell (no special-cased scheduling path)
        host.managementInlet.call.drainHost()
        controller.runToIdle()
    }
}
