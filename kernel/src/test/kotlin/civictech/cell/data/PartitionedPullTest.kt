package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.RetainedFrontiers
import civictech.cell.replication.Interest
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * PN-5 (spec 20/24 §Partitioned state, 40/42 §Interest-scoped instance sets):
 * scatter-gather pull. A pull against a partitioned logical id has no single
 * answerer — the router would need O(total state) at one node to serve it, the
 * very thing partitioning exists to avoid. Instead the router fans the pull to
 * every interest-overlapping shard; each answers its own slice with its own
 * frontier. Freshness is per-shard-consistent, cross-shard-arbitrary: a leg is a
 * baseline, never a wave, and a migrating shard's leg simply defers.
 *
 * A late joiner pulls a 3-shard cell (shards behind bridges, one held
 * mid-migration): the assembled union equals a batch group-by, and a second
 * incremental pull returns only the tags a per-shard retained `since` has not
 * seen. Controls: (a) one merged scalar `since` across shards silently loses the
 * migrating shard's non-contiguous tags; (b) answering from the router's own
 * `ledger` is functionally green but materializes O(total) state at one node.
 */
class PartitionedPullTest {

    private fun key(e: String) = e.first().toString()

    /** A router bridged to [shardCount] shards, each an interest-scoped hosted instance on its own host+registry. */
    private class Mesh(seed: Long, val shardCount: Int, val totalSlots: Int) {
        val controller = SimulationController(seed)
        val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(totalSlots, { it.first().toString() }, routerRegistry)

        init {
            val shardCells = List(shardCount) { i ->
                val reg = LocationRegistry()
                val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val host = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val interest = Interest.Slots.forShard(i, shardCount, totalSlots)
                val shard = ShardCell<String>(CellRef(logicalId, i.toLong()), { it.first().toString() }, interest)
                host.managementInlet.call.spawn(shard)
                Peering.loopback(routerSide, Peering.Side(reg, bridgeHost))
                shard to interest
            }
            controller.runToIdle()
            shardCells.forEach { (shard, interest) -> router.addShard(shard, interest) }
        }

        fun quiesce() = controller.runToIdle()

        /** Shard [i] begins migrating — a pull leg to it defers until it settles. */
        fun migrate(i: Int) = routerRegistry.hold(CellRef(logicalId, i.toLong()))

        /** Shard [i]'s migration completes. */
        fun heal(i: Int) = routerRegistry.release(CellRef(logicalId, i.toLong()))
    }

    /** A single-writer OR-set input over one shared source: counters interleave across shards by construction. */
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

    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")
    private val batch2Domain = listOf("m1", "n2", "o3", "p4", "q5", "r6", "s7", "t8")

    private fun union(replies: List<PullReply<String>>): Set<String> =
        replies.flatMapTo(mutableSetOf()) { it.delta.adds.keys }

    private fun tagCount(reply: PullReply<String>): Int = reply.delta.adds.values.sumOf { it.size }

    // ---- the payoff: assembled union == batch, incremental == only unseen ----

    @Test
    fun `late joiner scatter-gather pull assembles the union, incremental returns only unseen, 100 seeds`() {
        for (seed in 0L until 100L) {
            val mesh = Mesh(seed, shardCount = 3, totalSlots = 12)
            val input = Input()
            val rnd = Random(seed)

            // batch 1 — spread across the three shards, then let it settle on every shard
            repeat(50) {
                val e = domain[rnd.nextInt(domain.size)]
                val delta = if (rnd.nextInt(10) < 7 || e !in input.liveSet()) input.add(e) else input.remove(e)
                if (delta != null) mesh.router.route(delta)
                repeat(rnd.nextInt(3)) { mesh.controller.step() }
            }
            mesh.quiesce()

            val retained = RetainedFrontiers()
            val sinceOf: (CellRef) -> TagFrontier? = { retained.sinceFor(it) }
            fun record(replies: List<PullReply<String>>) = replies.forEach { retained.record(it.instance, it.frontier) }

            // late joiner pulls while shard 1 is mid-migration: its leg defers
            mesh.migrate(1)
            val round1 = mesh.router.pull(Interest.Total, sinceOf)
            record(round1)
            // the migrating shard was skipped, so round 1 is at most a subset of the board
            union(round1).all { it in input.liveSet() }.shouldBeTrue()

            // migration completes; the deferred leg is pulled from its own (null) currency ⇒ full
            mesh.heal(1)
            val round2 = mesh.router.pull(Interest.Total, sinceOf)
            record(round2)

            (union(round1) + union(round2)) shouldBe input.liveSet() // assembled state equals the union

            // a second incremental pull with nothing new returns nothing unseen
            val idle = mesh.router.pull(Interest.Total, sinceOf)
            record(idle)
            union(idle) shouldBe emptySet()

            // batch 2 — brand-new elements; the incremental pull returns exactly those
            val newKeys = mutableSetOf<String>()
            repeat(6) {
                val e = batch2Domain[rnd.nextInt(batch2Domain.size)]
                newKeys += e
                mesh.router.route(input.add(e))
                repeat(rnd.nextInt(3)) { mesh.controller.step() }
            }
            mesh.quiesce()

            val incremental = mesh.router.pull(Interest.Total, sinceOf)
            record(incremental)
            union(incremental) shouldBe newKeys // only unseen tags come back — batch-1 elements never repeat
        }
    }

    // ---- control (a): one merged scalar `since` silently loses tags ----------

    @Test
    fun `control a - a merged scalar since across shards silently loses the migrating shard's tags on some seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val mesh = Mesh(seed, shardCount = 3, totalSlots = 12)
            val input = Input()
            val rnd = Random(seed)
            repeat(50) {
                val e = domain[rnd.nextInt(domain.size)]
                val delta = if (rnd.nextInt(10) < 7 || e !in input.liveSet()) input.add(e) else input.remove(e)
                if (delta != null) mesh.router.route(delta)
                repeat(rnd.nextInt(3)) { mesh.controller.step() }
            }
            mesh.quiesce()
            if (input.liveSet().isEmpty()) continue

            // round 1: shard 1 migrating ⇒ its leg defers; the two live shards answer.
            // Both disciplines read the same shards (pulls are read-only), so one mesh
            // serves both — the per-instance retained frontier and the one merged scalar.
            val retained = RetainedFrontiers()
            var merged: TagFrontier? = null
            mesh.migrate(1)
            val round1 = mesh.router.pull(Interest.Total) { retained.sinceFor(it) }
            round1.forEach { retained.record(it.instance, it.frontier); merged = mergeFrontier(merged, it.frontier) }
            mesh.heal(1)

            // correct: the deferred shard 1 is pulled from its OWN (null) currency ⇒ full.
            val ok2 = mesh.router.pull(Interest.Total) { retained.sinceFor(it) }
            val got = union(round1) + union(ok2)

            // control: shard 1 pulled with the MERGED since — its non-contiguous counters
            // are mostly <= the merged max, so they read as already-seen and never return.
            val bad2 = mesh.router.pull(Interest.Total) { merged }
            val lost = union(round1) + union(bad2)

            got shouldBe input.liveSet() // per-instance since never loses
            if (lost != input.liveSet()) diverged++ // merged since does, on some seed
        }
        // if this fails, the migrating shard's counters never fell below the merged
        // max on any seed — the merged-since defect would be untested.
        (diverged > 0).shouldBeTrue()
    }

    // ---- control (b): answering from the ledger holds O(total) at one node ----

    @Test
    fun `control b - answering from the ledger is green but materializes O(total) at one node, 100 seeds`() {
        var everFannedBelowTotal = 0
        for (seed in 0L until 100L) {
            val mesh = Mesh(seed, shardCount = 3, totalSlots = 12)
            val input = Input()
            val rnd = Random(seed)
            repeat(50) {
                val e = domain[rnd.nextInt(domain.size)]
                val delta = if (rnd.nextInt(10) < 7 || e !in input.liveSet()) input.add(e) else input.remove(e)
                if (delta != null) mesh.router.route(delta)
                repeat(rnd.nextInt(3)) { mesh.controller.step() }
            }
            mesh.quiesce()
            if (input.liveSet().size < 2) continue

            val fanned = mesh.router.pull(Interest.Total) { null }
            val ledgered = mesh.router.pullFromLedger(Interest.Total, null)

            // both are functionally green: same assembled union of elements
            union(fanned) shouldBe input.liveSet()
            union(ledgered) shouldBe input.liveSet()

            // the whole board's tag count — the state the ledger control holds at one node.
            val boardTags = fanned.sumOf { tagCount(it) }
            // fan-out: no single leg carries the whole board (O(shard-count), not O(total)).
            val fanMax = fanned.maxOf { tagCount(it) }
            // ledger: one reply materializes the entire board at one node (O(total)).
            ledgered.size shouldBe 1
            tagCount(ledgered.single()) shouldBe boardTags

            if (fanMax < boardTags && fanned.size >= 2) everFannedBelowTotal++
        }
        (everFannedBelowTotal > 0).shouldBeTrue()
    }

    private fun mergeFrontier(a: TagFrontier?, b: TagFrontier): TagFrontier {
        if (a == null) return b
        val m = a.perSource.toMutableMap()
        b.perSource.forEach { (s, c) -> m.merge(s, c, ::maxOf) }
        return TagFrontier(m)
    }
}
