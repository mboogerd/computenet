package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.replication.Interest
import civictech.cell.wire.Peering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * PN-4 (spec 20/24 §Partitioned state, 40/42 §Interest-scoped instance sets):
 * `ShardCell` grew up into a full dataflow cell — `Stateful` (snapshot =
 * `TagState + interest + assignedEpoch`) and `Replicable` (outlet + deltaInlet +
 * `StateRequest`) — so partitioned+durable is buildable end to end.
 *
 * Three shards, each an interest-scoped hosted instance of one logical id on its
 * OWN host with its OWN per-shard WAL. Traffic → repartition (interest
 * reassignment, one shard sheds a range) → more traffic → checkpoint one shard →
 * `kill -9` all three (only the journals survive) → reconstruct + `recoverFrom`
 * each → [PartitionedShardSet.rebuildFrom] recomputes the router table/epoch from
 * the restored shards. Over 100 seeds the scatter-gather board equals a batch
 * group-by over the final input and memberships stay pairwise disjoint (the
 * double-count detector).
 *
 * The recovery correctness turns on the shed surviving the crash. A shard's
 * shed is invisible to its journal (the router narrows the shard's interest by a
 * direct in-process call, not a routed frame), so a non-checkpointed shard
 * reconstructed with its **current** (post-repartition) interest drops its
 * pre-repartition frames for the range it lost; reconstructed with its
 * constructor `initialInterest` instead (PN-4 control a) it re-admits them and
 * the moved range resurrects on two shards at once.
 */
class ShardJournalReplayTest {

    // "a3" -> group 'a', value 3 (same convention as PartitionedShardsAcrossHostsTest)
    private fun key(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    /** A single-writer OR-set input: mints add-tags, tombstones observed tags on remove. */
    private class Input {
        private val src = UUID.randomUUID()
        private var ctr = 0L
        private val live = mutableMapOf<String, MutableSet<Timestamp>>()

        fun add(e: String): SetDelta<String> {
            val t = Timestamp(src, ++ctr)
            live.getOrPut(e) { mutableSetOf() } += t
            return SetDelta(adds = mapOf(e to setOf(t)))
        }

        fun remove(e: String): SetDelta<String>? {
            val observed = live[e]?.toSet()?.takeIf { it.isNotEmpty() } ?: return null
            live[e]!!.clear()
            return SetDelta(dels = mapOf(e to observed))
        }

        fun liveSet(): Set<String> = live.filterValues { it.isNotEmpty() }.keys.toMutableSet()
    }

    /** Scatter-gather board: sum per group across shards; a group forked across two shards double-counts. */
    private fun boardOf(memberships: List<Set<String>>): Map<String, Long> {
        val board = mutableMapOf<String, Long>()
        memberships.forEach { m ->
            m.groupBy { key(it) }.forEach { (k, es) -> board.merge(k, es.sumOf { amount(it) }, Long::plus) }
        }
        return board
    }

    private fun batch(live: Set<String>): Map<String, Long> =
        live.groupBy { key(it) }.mapValues { (_, es) -> es.sumOf { amount(it) } }

    private fun pairwiseDisjoint(memberships: List<Set<String>>): Boolean {
        for (i in memberships.indices) for (j in i + 1 until memberships.size) {
            if (memberships[i].intersect(memberships[j]).isNotEmpty()) return false
        }
        return true
    }

    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    /**
     * A durable mesh: [shardCount] shards, each a hosted [ShardCell] on its own
     * host with its own [InMemoryJournal] ("the disk"), bridged to the router.
     * Routed frames cross the bridge to the shard host and land in its WAL.
     */
    private inner class DurableMesh(seed: Long, val shardCount: Int, val totalSlots: Int) {
        val controller = SimulationController(seed)
        val logicalId: UUID = UUID.randomUUID()
        private val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val router = PartitionedShardSet<String>(totalSlots, ::key, routerRegistry)

        val journals = List(shardCount) { InMemoryJournal() }
        val shardHosts = mutableListOf<ManagedHost>()
        private val shardCells = mutableListOf<ShardCell<String>>()
        var finalInterests: List<Interest> = List(shardCount) { Interest.Slots.forShard(it, shardCount, totalSlots) }
            private set

        init {
            (0 until shardCount).forEach { i ->
                val reg = LocationRegistry()
                val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val host = ManagedHost(scheduler = controller.scheduler(), registry = reg, journal = journals[i])
                shardHosts += host
                val interest = Interest.Slots.forShard(i, shardCount, totalSlots)
                val shard = ShardCell<String>(CellRef(logicalId, i.toLong()), ::key, interest, epochAware = true)
                host.managementInlet.call.spawn(shard)
                shardCells += shard
                Peering.loopback(routerSide, Peering.Side(reg, bridgeHost))
            }
            controller.runToIdle()
            (0 until shardCount).forEach { i -> router.addShard(shardCells[i], finalInterests[i]) }
        }

        fun quiesce() = controller.runToIdle()

        fun repartition(newInterests: List<Interest>) {
            router.repartition(newInterests)
            finalInterests = newInterests
        }

        /** Checkpoint shard [i]'s WAL down to its `Stateful` snapshot (state + interest + epoch). */
        fun checkpoint(i: Int) {
            shardHosts[i].checkpoint(journals[i])
            controller.runToIdle()
        }

        /**
         * `kill -9` all hosts (only the journals survive), then reconstruct each
         * shard on a fresh host over the SAME journal and [ManagedHost.recoverFrom].
         * [useCurrentInterest] true = reconstruct with the shard's post-repartition
         * interest (the fix); false = with its original `initialInterest` (control a).
         */
        fun recover(useCurrentInterest: Boolean): List<ShardCell<String>> {
            val rc = SimulationController(seed = 424242)
            return (0 until shardCount).map { i ->
                val interest =
                    if (useCurrentInterest) finalInterests[i]
                    else Interest.Slots.forShard(i, shardCount, totalSlots)
                val host = ManagedHost(scheduler = rc.scheduler(), registry = LocationRegistry(), journal = journals[i])
                val cell = ShardCell<String>(CellRef(logicalId, i.toLong()), ::key, interest, epochAware = true)
                host.managementInlet.call.spawn(cell)
                rc.runToIdle()
                host.recoverFrom(journals[i]) // pre-spawn THEN replay (spec 24 recovery order)
                rc.runToIdle()
                cell
            }
        }
    }

    /** Feed a seed's ops with a mid-run repartition (ownership rotates by one shard), then checkpoint one shard. */
    private fun runToRecoveryPoint(mesh: DurableMesh, seed: Long) {
        val input = Input()
        val rnd = Random(seed)
        val rotated = List(mesh.shardCount) { s ->
            Interest.Slots.forShard((s + 1) % mesh.shardCount, mesh.shardCount, mesh.totalSlots)
        }
        repeat(70) { i ->
            if (i == 35) mesh.repartition(rotated) // the flip: every group's owning shard changes
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 6 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }
        mesh.quiesce()
        mesh.checkpoint(seed.toInt() % mesh.shardCount) // checkpoint one shard (rotates which, across seeds)
        seedLiveSets[seed] = input.liveSet()
    }

    private val seedLiveSets = mutableMapOf<Long, Set<String>>()

    @Test
    fun `board equals batch after per-shard journal replay and rebuildFrom, 100 seeds`() {
        for (seed in 0L until 100L) {
            val mesh = DurableMesh(seed, shardCount = 3, totalSlots = 12)
            runToRecoveryPoint(mesh, seed)
            val live = seedLiveSets.getValue(seed)

            val recovered = mesh.recover(useCurrentInterest = true)
            val router2 = PartitionedShardSet<String>(mesh.totalSlots, ::key, LocationRegistry())
            router2.rebuildFrom(recovered)

            boardOf(router2.memberships()) shouldBe batch(live)
            pairwiseDisjoint(router2.memberships()).shouldBeTrue()
            router2.routingEpoch shouldBe mesh.router.routingEpoch // epoch recomputed from the restored shards
        }
    }

    @Test
    fun `control a - rebuilding a shard from its constructor initialInterest resurrects a shed range on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val mesh = DurableMesh(seed, shardCount = 3, totalSlots = 12)
            runToRecoveryPoint(mesh, seed)
            val live = seedLiveSets.getValue(seed)

            val recovered = mesh.recover(useCurrentInterest = false) // the pre-PN-4 defect
            val router2 = PartitionedShardSet<String>(mesh.totalSlots, ::key, LocationRegistry())
            router2.rebuildFrom(recovered)

            val board = boardOf(router2.memberships())
            if (board != batch(live) || !pairwiseDisjoint(router2.memberships())) diverged++
        }
        // if this fails, the flip never moved a live element — the shed would be untested
        (diverged > 0).shouldBeTrue()
    }

    /**
     * A pre-PN-4-shaped shard: a write-only, non-`Stateful` sink whose state
     * exists ONLY as replayable routed frames in its WAL — exactly the
     * contributor PN-0b's checkpoint guard must not truncate away. (A real
     * `ShardCell` is now `Stateful`, so its checkpoint captures its state and the
     * guard never fires — this stand-in makes the guard's role executable.)
     */
    private class WriteOnlyShard(override val ref: CellRef) : Cell {
        val received = mutableListOf<RoutedCommand<String>>()
        val routeInlet = registerPort("routeInlet", FanInlet.create<Propagate<RoutedCommand<String>>>())

        init {
            routeInlet.serve(Propagate<RoutedCommand<String>> { cmd -> received += cmd })
        }
    }

    private interface RouteProxy {
        val routeInlet: civictech.cell.port.Use<Propagate<RoutedCommand<String>>>
    }

    private fun route(host: ManagedHost, ref: CellRef): Propagate<RoutedCommand<String>> =
        (civictech.cell.proxy.HostedCellProxy.create(ref, host, RouteProxy::class.java) as RouteProxy).routeInlet.call

    @Test
    fun `control b - PN-0b guard protects a non-Stateful shard's WAL, without which recovery is empty`() {
        val controller = SimulationController(seed = 7)
        val journal = InMemoryJournal() // the only thing that survives the crash
        val ref = CellRef(UUID.randomUUID())

        var host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry(), journal = journal)
        host.managementInlet.call.spawn(WriteOnlyShard(ref))
        controller.runToIdle()

        // routed frames reach the WAL; frame replay is this shard's ONLY recovery
        route(host, ref).propagate(RoutedCommand(0L, SetDelta(adds = mapOf("a1" to setOf(Timestamp(UUID.randomUUID(), 1))))))
        route(host, ref).propagate(RoutedCommand(0L, SetDelta(adds = mapOf("b2" to setOf(Timestamp(UUID.randomUUID(), 1))))))
        controller.runToIdle()

        // WITH the guard: checkpoint would reset the WAL down to an empty snapshot
        // (the sink is non-Stateful), destroying both frames — so it refuses.
        shouldThrow<IllegalArgumentException> { host.checkpoint(journal) }

        // guard respected → frames intact → replay rebuilds the shard
        host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry(), journal = journal)
        val recovered = WriteOnlyShard(ref)
        host.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()
        recovered.received.size shouldBe 2

        // CONTROL: guard removed == the checkpoint truncates a no-snapshot journal.
        // Reconstruct the destructive reset it would have written, then recover.
        journal.reset(emptyList())
        val deadController = SimulationController(seed = 8)
        val deadHost = ManagedHost(scheduler = deadController.scheduler(), registry = LocationRegistry(), journal = journal)
        val afterTruncate = WriteOnlyShard(ref)
        deadHost.managementInlet.call.spawn(afterTruncate)
        deadController.runToIdle()
        deadHost.recoverFrom(journal)
        deadController.runToIdle()
        afterTruncate.received shouldBe emptyList() // the shed data is gone — the guard's whole point
    }
}
