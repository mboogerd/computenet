package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.replication.Interest
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID
import civictech.cell.data.delta.SetDelta
import civictech.cell.partition.ShardCell
import civictech.cell.partition.PartitionedShardSet

/**
 * PN-6, CP-G4's exit folded in (spec 20/24 §Partitioned state, 40/42
 * §Interest-scoped instance sets): the router is on host A; its shards are hosted
 * instances on B and C, fed over **real bridges** ([Peering.loopback]); a
 * repartition races a shard migration while writes flow throughout. The
 * difference from [PartitionedShardsAcrossHostsTest] is the control plane: here
 * the interest reassignment rides a **journaled, ref-addressed [Assignment]
 * hosted invocation** to each shard's `assignInlet` over the same bridge the data
 * takes (`journaledAssign = true`, the PN-6 payoff), not a direct in-process
 * call. Over 100 seeds the buffered flip window plus per-ref migration park loses
 * and double-counts nothing; the epoch-blind control forks.
 */
class BridgedRepartitionTest {

    private fun key(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    /** A router on host A bridged to [shardCount] shards, each an instance of one logical id on its own host. */
    private class Mesh(seed: Long, val shardCount: Int, val totalSlots: Int, epochAware: Boolean, keyFn: (String) -> Any?) {
        val controller = SimulationController(seed)
        val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()

        // journaledAssign = true: the PN-6 control plane crosses the bridge as a
        // journaled, ref-addressed Assignment invocation to each shard's assignInlet.
        val router = PartitionedShardSet<String>(totalSlots, keyFn, routerRegistry, journaledAssign = true)

        init {
            val shardCells = List(shardCount) { i ->
                val reg = LocationRegistry()
                val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val host = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val interest = Interest.Slots.forShard(i, shardCount, totalSlots)
                val shard = ShardCell<String>(CellRef(logicalId, i.toLong()), keyFn, interest, epochAware)
                host.managementInlet.call.spawn(shard)
                Peering.loopback(routerSide, Peering.Side(reg, bridgeHost))
                shard to interest
            }
            controller.runToIdle()
            shardCells.forEach { (shard, interest) -> router.addShard(shard, interest) }
        }

        fun quiesce() = controller.runToIdle()
        fun migrate(i: Int) = routerRegistry.hold(CellRef(logicalId, i.toLong()))
        fun heal(i: Int) = routerRegistry.release(CellRef(logicalId, i.toLong()))
    }

    /** A single-writer OR-set input feeding the router. */
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

    private fun boardOf(memberships: List<Set<String>>): Map<String, Long> {
        val board = mutableMapOf<String, Long>()
        memberships.forEach { m ->
            m.groupBy { key(it) }.forEach { (k, es) -> board.merge(k, es.sumOf { amount(it) }, Long::plus) }
        }
        return board
    }

    private fun batch(live: Set<String>): Map<String, Long> =
        live.groupBy { key(it) }.mapValues { (_, es) -> es.sumOf { amount(it) } }

    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    /** A flip window racing a shard migration, journaled assignment crossing the bridge, writes throughout. */
    private fun runBridged(seed: Long, epochAware: Boolean): Pair<Map<String, Long>, Map<String, Long>> {
        val shardCount = 3
        val totalSlots = 12
        val mesh = Mesh(seed, shardCount, totalSlots, epochAware, ::key)
        val input = Input()
        val rnd = Random(seed)
        val rotated = List(shardCount) { s -> Interest.Slots.forShard((s + 1) % shardCount, shardCount, totalSlots) }

        fun tick() {
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 6 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }

        repeat(30) { tick() }
        mesh.migrate(1)                                  // shard 1 migrates (its router-side traffic parks per-ref)
        mesh.router.beginRepartition(rotated, buffered = true) // journaled assignment crosses the bridge
        repeat(30) { i ->
            tick()
            if (i == 15) mesh.heal(1)                    // migration completes mid-window
        }
        mesh.router.endRepartition()
        repeat(20) { tick() }
        mesh.quiesce()
        return boardOf(mesh.router.memberships()) to batch(input.liveSet())
    }

    @Test
    fun `bridged repartition racing a migration with journaled assignment loses nothing, 100 seeds`() {
        for (seed in 0L until 100L) {
            val (board, batch) = runBridged(seed, epochAware = true)
            board shouldBe batch
        }
    }

    @Test
    fun `control - an epoch-blind shard forks a moved group over the bridge on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val (board, batch) = runBridged(seed, epochAware = false)
            if (board != batch) diverged++
        }
        (diverged > 0).shouldBeTrue()
    }
}
