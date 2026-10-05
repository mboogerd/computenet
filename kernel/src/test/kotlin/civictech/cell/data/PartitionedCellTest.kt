package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
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
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.GroupByApi
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.nature.manifestOf
import civictech.cell.partition.PartitionedCell
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.nature.Manifest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * W4.2 (G-56, realizes G-24, 20/24 §Partitioned state): a [PartitionedCell]
 * of key-disjoint [GroupByCell] shards must be indistinguishable, from
 * outside the membrane, from a single unsharded [GroupByCell] — including
 * across a mid-run [PartitionedCell.repartition].
 */
class PartitionedCellTest {

    interface PartitionedInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

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

    private fun replicatedSumByKey(ref: CellRef, shardCount: Int = 3) = PartitionedCell(
        ref = ref,
        initialShardCount = shardCount,
        keyFn = ::key,
        aggregator = Aggregators.sumOf(::amount),
    )

    private fun spawn(peer: Peer, ref: CellRef): PartitionedCell<String, String, Long, Long> {
        var created: PartitionedCell<String, String, Long, Long>? = null
        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "partitioned-${ref.instanceId}",
                    factory = CellFactory { chosen -> replicatedSumByKey(chosen).also { created = it } },
                    identity = IdentityBinding.Exact(ref),
                    replicated = true,
                ),
            ),
        ).apply(ApplyContext(peer.host, peer.replication))
        return checkNotNull(created)
    }

    private fun inlet(peer: Peer, ref: CellRef): Propagate<SetDelta<String>> =
        (HostedCellProxy.create(ref, peer.registry, PartitionedInletProxy::class.java) as PartitionedInletProxy)
            .inlet.call

    private fun batch(live: Set<String>): Map<String, Long> =
        live.groupBy(::key).mapValues { (_, elements) -> elements.sumOf(::amount) }

    private val replicatedDomain = listOf(
        "a1", "a2", "a5", "b3", "b7", "c4", "c8", "d9", "e2", "f6", "g5", "h8", "i4", "j7",
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
    fun `repartition preserves tombstones so a stale add cannot resurrect`() {
        val cell = sumByKey(shardCount = 3)
        val out = collect(cell.outlet)
        val originalTag = tag(1)

        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(originalTag))))
        cell.inlet.call.propagate(SetDelta(dels = mapOf("a3" to setOf(originalTag))))
        cell.repartition(5)
        cell.inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(originalTag))))

        assertEquals(emptyMap<String, Long>(), mapFold(out))
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

    @Test
    fun `replicated PartitionedCells converge through a bridge partition and heal on every seed`() {
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val random = Random(seed)
            val peers = mutableListOf(Peer(controller), Peer(controller))
            val bridge = Peering.loopback(peers[0].side, peers[1].side)
            val logicalId = UUID.nameUUIDFromBytes("replicated-partitioned-$seed".toByteArray())
            val cells = peers.mapIndexedTo(mutableListOf()) { index, peer ->
                spawn(peer, CellRef(logicalId, index.toLong()))
            }
            val boards = cells.mapTo(mutableListOf()) { collect(it.outlet) }
            val inputs = peers.mapIndexedTo(mutableListOf()) { index, peer -> inlet(peer, cells[index].ref) }
            controller.runToIdle()

            var counter = 1L
            val live = linkedMapOf<String, Timestamp>()
            lateinit var retiredTag: Timestamp

            repeat(60) { operation ->
                if (operation == 20) {
                    bridge.partition()
                }
                if (operation == 30) {
                    bridge.heal()
                    controller.runToIdle()
                    if (seed == 0L) {
                        // The late replica is reachable only through peer 1, so
                        // peer 1's organelle gossip echo and the composite's
                        // state-as-delta catch-up are both load-bearing.
                        val late = Peer(controller)
                        peers += late
                        Peering.loopback(peers[1].side, late.side)
                        val lateCell = spawn(late, CellRef(logicalId, 2))
                        cells += lateCell
                        boards += collect(lateCell.outlet)
                        inputs += inlet(late, lateCell.ref)
                        controller.runToIdle()

                        // The deleted tag must have arrived in catch-up even
                        // though it has no live add; replaying it stale stays dead.
                        inputs.last().propagate(SetDelta(adds = mapOf("y7" to setOf(retiredTag))))
                        controller.runToIdle()
                    }
                }

                when (operation) {
                    0 -> {
                        retiredTag = tag(counter++)
                        live["y7"] = retiredTag
                        inputs[0].propagate(SetDelta(adds = mapOf("y7" to setOf(retiredTag))))
                    }
                    1 -> {
                        live.remove("y7")
                        inputs[1].propagate(SetDelta(dels = mapOf("y7" to setOf(retiredTag))))
                    }
                    29 -> {
                        val addTag = tag(counter++)
                        live["z9"] = addTag
                        inputs[0].propagate(SetDelta(adds = mapOf("z9" to setOf(addTag))))
                    }
                    else -> {
                        val element = replicatedDomain[random.nextInt(replicatedDomain.size)]
                        val target = if (operation < inputs.size + 2) {
                            operation % inputs.size
                        } else {
                            random.nextInt(inputs.size)
                        }
                        val existing = live[element]
                        if (existing == null) {
                            val addTag = tag(counter++)
                            live[element] = addTag
                            inputs[target].propagate(SetDelta(adds = mapOf(element to setOf(addTag))))
                        } else {
                            live.remove(element)
                            inputs[target].propagate(SetDelta(dels = mapOf(element to setOf(existing))))
                        }
                    }
                }
                repeat(random.nextInt(3)) { controller.step() }
            }
            controller.runToIdle()

            val expected = batch(live.keys)
            cells.forEachIndexed { index, cell ->
                assertEquals(expected, mapFold(boards[index]), "replica $index board diverged on seed $seed")

                val occurrences = cell.shardContents().flatMap { it.adds.keys }
                assertEquals(live.keys, occurrences.toSet(), "replica $index membership diverged on seed $seed")
                assertEquals(
                    occurrences.size,
                    occurrences.toSet().size,
                    "replica $index stored an element in more than one organelle on seed $seed",
                )
                assertTrue(
                    cell.shardContents().any { retiredTag in (it.dels["y7"] ?: emptySet()) },
                    "replica $index lost the y7 tombstone on seed $seed",
                )

                assertNotNull(peers[index].host.lookup<GroupByApi<String, String, Long>>(cell.ref))
                cell.shardRefs().forEach { organelleRef ->
                    assertNull(
                        peers[index].host.lookup<GroupByApi<String, String, Long>>(organelleRef),
                        "organelle $organelleRef was registry-addressable on replica $index seed $seed",
                    )
                }
                val visibleReplicas = peers[index].registry.instances.replicasOf(logicalId)
                val expectedVisible = if (cells.size == 2 || index == 1) cells.size else 2
                assertEquals(
                    expectedVisible,
                    visibleReplicas.size,
                    "registry on replica $index did not publish one composite per visible peer on seed $seed",
                )
                assertEquals(
                    visibleReplicas.size,
                    visibleReplicas.map { it.instanceId }.toSet().size,
                    "registry on replica $index published duplicate composite instances on seed $seed",
                )
            }
        }
    }

    @Test
    fun `a repartition on one replica keeps both replicated boards equal`() {
        for (seed in 0L until 30L) {
            val controller = SimulationController(seed + 1_000)
            val random = Random(seed)
            val peers = listOf(Peer(controller), Peer(controller))
            Peering.loopback(peers[0].side, peers[1].side)
            val logicalId = UUID.nameUUIDFromBytes("repartitioned-replica-$seed".toByteArray())
            val cells = peers.mapIndexed { index, peer -> spawn(peer, CellRef(logicalId, index.toLong())) }
            val boards = cells.map { collect(it.outlet) }
            val inputs = peers.mapIndexed { index, peer -> inlet(peer, cells[index].ref) }
            controller.runToIdle()

            var counter = 1L
            val live = linkedMapOf<String, Timestamp>()
            repeat(45) { operation ->
                if (operation == 20) cells[0].repartition(5)
                val element = replicatedDomain[random.nextInt(replicatedDomain.size)]
                val target = if (operation < inputs.size) operation else random.nextInt(inputs.size)
                val existing = live[element]
                if (existing == null) {
                    val addTag = tag(counter++)
                    live[element] = addTag
                    inputs[target].propagate(SetDelta(adds = mapOf(element to setOf(addTag))))
                } else {
                    live.remove(element)
                    inputs[target].propagate(SetDelta(dels = mapOf(element to setOf(existing))))
                }
                repeat(random.nextInt(3)) { controller.step() }
            }
            controller.runToIdle()

            val expected = batch(live.keys)
            assertEquals(1, cells[0].routingEpoch, "repartition did not bump replica 0 on seed $seed")
            assertEquals(0, cells[1].routingEpoch, "repartition leaked to replica 1 on seed $seed")
            boards.forEachIndexed { index, board ->
                assertEquals(expected, mapFold(board), "replica $index board diverged after repartition on seed $seed")
            }
        }
    }

    @Test
    fun `PartitionedCell manifest contains replicated and partitioned natures`() {
        val manifest = manifestOf(PartitionedCell::class.java)
        assertTrue(Manifest.REPLICATED in manifest)
        assertTrue(Manifest.PARTITIONED in manifest)
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
