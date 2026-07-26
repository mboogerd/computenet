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

/**
 * CP-D3 (spec 20/24 §Partitioned state, 40/42 §Interest-scoped instance sets):
 * a [PartitionedShardSet] whose [ShardCell] shards are interest-scoped hosted
 * instances of one logical id, each on a **different host**, fed over bridges.
 * The router is the disjoint-interest linker; the routing table + routingEpoch
 * cross the wire. Over 100 seeds the scatter-gather board equals a batch
 * group-by over the final input; a mid-run repartition (interest reassignment +
 * a routing-epoch bump) loses and double-counts nothing; the control — an
 * epoch-blind shard — forks a moved group across two shards and diverges.
 */
class PartitionedShardsAcrossHostsTest {

    // "a3" -> group 'a', value 3 (same convention as PartitionedCellTest)
    private fun key(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    /** A router side bridged to [shardCount] shards, each on its own host+registry. */
    private class Mesh(seed: Long, val shardCount: Int, val totalSlots: Int, epochAware: Boolean, keyFn: (String) -> Any?) {
        val controller = SimulationController(seed)
        val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(totalSlots, keyFn, routerRegistry)

        init {
            val shardCells = List(shardCount) { i ->
                val reg = LocationRegistry()
                val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val host = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val interest = Interest.Slots.forShard(i, shardCount, totalSlots)
                val shard = ShardCell<String>(CellRef(logicalId, i.toLong()), keyFn, interest, epochAware)
                host.managementInlet.call.spawn(shard) // published on the shard's own registry
                Peering.loopback(routerSide, Peering.Side(reg, bridgeHost)) // mirror it to the router
                shard to interest
            }
            controller.runToIdle() // let the shard announcements reach the router registry
            shardCells.forEach { (shard, interest) -> router.addShard(shard, interest) }
        }

        fun quiesce() = controller.runToIdle()

        /** Simulate shard [i] migrating away (its router-side deliveries park per-ref, funnel rule). */
        fun migrate(i: Int) = routerRegistry.hold(CellRef(logicalId, i.toLong()))

        /** Shard [i]'s migration completes — parked deliveries replay in order. */
        fun heal(i: Int) = routerRegistry.release(CellRef(logicalId, i.toLong()))
    }

    /** A single-writer OR-set input: mints add-tags, tombstones observed tags on remove — feeds the router. */
    private class Input {
        private val src = UUID.randomUUID()
        private var ctr = 0L
        private val live = mutableMapOf<String, MutableSet<Timestamp>>()

        fun add(e: String): SetDelta<String> {
            val t = Timestamp(src, ++ctr)
            live.getOrPut(e) { mutableSetOf() } += t
            return SetDelta(adds = mapOf(e to setOf(t)))
        }

        /** null when the element is not present (removing an unobserved element is a no-op). */
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

    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    @Test
    fun `board equals batch group-by with shards on different hosts fed over bridges, 100 seeds`() {
        for (seed in 0L until 100L) {
            val mesh = Mesh(seed, shardCount = 3, totalSlots = 12, epochAware = true, keyFn = ::key)
            val input = Input()
            val rnd = Random(seed)

            repeat(60) {
                val e = domain[rnd.nextInt(domain.size)]
                val delta = if (rnd.nextInt(10) < 6 || e !in input.liveSet()) input.add(e) else input.remove(e)
                if (delta != null) mesh.router.route(delta)
                repeat(rnd.nextInt(3)) { mesh.controller.step() }
            }
            mesh.quiesce()

            boardOf(mesh.router.memberships()) shouldBe batch(input.liveSet())
        }
    }

    /** Feed a seed's ops with a mid-run repartition (ownership rotates by one shard). */
    private fun runWithRepartition(seed: Long, epochAware: Boolean): Pair<Map<String, Long>, Map<String, Long>> {
        val shardCount = 3
        val totalSlots = 12
        val mesh = Mesh(seed, shardCount, totalSlots, epochAware, keyFn = ::key)
        val input = Input()
        val rnd = Random(seed)

        repeat(80) { i ->
            if (i == 40) {
                // rotate ownership by one shard — every group's owning shard changes (the flip)
                mesh.router.repartition(List(shardCount) { s ->
                    Interest.Slots.forShard((s + 1) % shardCount, shardCount, totalSlots)
                })
            }
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 6 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }
        mesh.quiesce()
        return boardOf(mesh.router.memberships()) to batch(input.liveSet())
    }

    @Test
    fun `mid-run repartition under the epoch guard loses and double-counts nothing, 100 seeds`() {
        for (seed in 0L until 100L) {
            val (board, batch) = runWithRepartition(seed, epochAware = true)
            board shouldBe batch // routing-epoch-aware: the flip is clean on every seed
        }
    }

    @Test
    fun `control - an epoch-blind shard forks a moved group and diverges on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val (board, batch) = runWithRepartition(seed, epochAware = false)
            if (board != batch) diverged++
        }
        // if this fails the flip never moved a live element — the guard would be untested
        (diverged > 0).shouldBeTrue()
    }

    /** A flip window racing a shard migration, with writes throughout the window. */
    private fun runD4(seed: Long, buffered: Boolean): Pair<Map<String, Long>, Map<String, Long>> {
        val shardCount = 3
        val totalSlots = 12
        val mesh = Mesh(seed, shardCount, totalSlots, epochAware = true, keyFn = ::key)
        val input = Input()
        val rnd = Random(seed)
        val rotated = List(shardCount) { s -> Interest.Slots.forShard((s + 1) % shardCount, shardCount, totalSlots) }

        fun tick() {
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 6 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }

        repeat(30) { tick() } // warm up
        mesh.migrate(1) // shard 1 begins migrating (its router-side traffic parks per-ref)
        mesh.router.beginRepartition(rotated, buffered) // open the flip window while shard 1 is away
        repeat(30) { i -> // writes throughout the window, racing the migration
            tick()
            if (i == 15) mesh.heal(1) // migration completes mid-window
        }
        mesh.router.endRepartition() // close the flip window
        repeat(20) { tick() } // post-flip writes
        mesh.quiesce()
        return boardOf(mesh.router.memberships()) to batch(input.liveSet())
    }

    @Test
    fun `repartition racing a shard migration under a buffered flip loses nothing, 100 seeds`() {
        for (seed in 0L until 100L) {
            val (board, batch) = runD4(seed, buffered = true)
            board shouldBe batch // the buffered flip window + per-ref migration park = zero loss, zero double
        }
    }

    @Test
    fun `control - an unbuffered flip racing a migration drops or double-routes on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val (board, batch) = runD4(seed, buffered = false)
            if (board != batch) diverged++
        }
        (diverged > 0).shouldBeTrue()
    }
}
