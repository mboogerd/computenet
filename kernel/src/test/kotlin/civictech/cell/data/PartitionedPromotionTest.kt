package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Timestamp
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.Promotion
import civictech.cell.evolve.PromotionJudge
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.replication.Interest
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * FU-3 (spec 53 §Replicated promotion, the G-50 partitioned residual): the PN-14
 * rolling promotion — shown for a **replicated** node in
 * [civictech.cell.replication.ReplicatedPromotionTest] — carried **shard by
 * shard** across a **partitioned** node.
 *
 * PN-14 promotes a replica by a reuse-ref rebind ([Promotion.promoteReplica] +
 * [Replication.rebind]): the candidate REUSES the incumbent's [CellRef], so the
 * swap is crash-recovery-equivalent — every mesh/routing identity derives from
 * the ref, and reusing it makes peers (here: the router's ref-keyed route/assign
 * proxies) keep resolving to the candidate with no rewiring. A [ShardCell] IS a
 * [civictech.cell.data.Replicable] ref-addressed instance, so a shard is
 * structurally accepted by that same code — but the partitioned path had no test.
 * This proves it end-to-end and pins the two failure modes the reuse-ref
 * mechanism prevents.
 *
 * A 3-shard partitioned node over **real [Peering.loopback] bridges** (each shard
 * an interest-scoped hosted instance on its own host+registry, reached by the
 * [PartitionedShardSet] router over the registry). shadow → judge → promote shard
 * 0's cell while shards 1–2 serve and writers keep writing; roll to shard 1;
 * interleave one repartition (interest reassignment + a routing-epoch bump).
 *
 * **Invariants** (100 seeds):
 *  - the scatter-gather board ([PartitionedShardSet.memberships]) converges to the
 *    batch group-by of all writes;
 *  - a **settlement-gated consumer** — the PN-5 scatter-gather [PartitionedShardSet.pull]
 *    with per-instance retained frontiers ([RetainedFrontiers]) — never surfaces an
 *    undelivered element (every element a pull leg returns is live in a shard) and
 *    its assembled union equals the board, across both swaps;
 *  - shard memberships stay pairwise disjoint (no key double-counted).
 *
 * **Controls** (must diverge):
 *  - (a) `FRESH_REF` — a shard's candidate is spawned with a **distinct** CellRef
 *    instead of reusing the incumbent shard's ref. The router's routing-table
 *    entry (its ref-keyed route proxy) orphans on the retired ref, so
 *    post-promotion writes to that shard's range dead-letter — lost — and the
 *    board (and the pull's assembled union) drops that range.
 *  - (b) `NO_REBIND_AUTHORITY` — a shard is promoted **racing a repartition
 *    without re-running link-time authority** (rebind): the candidate comes up
 *    under a stale interest still holding a range the repartition moved away, so
 *    the moved key's slice **forks** — held by both the promoted stale owner and
 *    the new owner — memberships are no longer disjoint and the board
 *    double-counts.
 */
class PartitionedPromotionTest {

    // "a3" -> group 'a', value 3 (same convention as the sibling partition tests).
    private fun key(e: String): String = e.first().toString()
    private fun amount(e: String): Long = e.drop(1).toLong()

    private val shardCount = 3
    private val totalSlots = 12
    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    private fun interestForShard(i: Int): Interest = Interest.Slots.forShard(i, shardCount, totalSlots)

    /** Ownership rotated by one shard: every group's owning shard changes (the flip that moves live keys). */
    private fun rotatedInterests(): List<Interest> = List(shardCount) { s -> interestForShard((s + 1) % shardCount) }

    private enum class Mode { REUSE_REF, FRESH_REF, NO_REBIND_AUTHORITY }

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

        fun remove(e: String): SetDelta<String>? {
            val observed = live[e]?.toSet()?.takeIf { it.isNotEmpty() } ?: return null
            live[e]!!.clear()
            return SetDelta(dels = mapOf(e to observed))
        }

        fun liveSet(): Set<String> = live.filterValues { it.isNotEmpty() }.keys.toMutableSet()
    }

    /** The requester side of a scatter-gather pull: correlates each baseline leg to its shard. */
    private class PullInbox(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        private val collected = mutableListOf<PullReply<String>>()

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    val ctx = CurrentContext.get()
                    val frontier = ctx?.baseline ?: return // a live wave (no baseline) is not a pull leg
                    val shardRef = ctx.sourcePort.cell ?: return
                    collected += PullReply(shardRef, value, frontier)
                }
            })
        }

        fun drain(): List<PullReply<String>> = collected.toList().also { collected.clear() }
    }

    private interface InboxRoute {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    /** Everything a single bridged shard owns: its own host/registry/replication, plus the reverse pull proxy. */
    private class ShardNode(
        val cell0: ShardCell<String>,
        val reg: LocationRegistry,
        val host: ManagedHost,
        val replication: Replication,
        val replyProxy: Propagate<SetDelta<String>>,
    ) {
        var cell: ShardCell<String> = cell0
    }

    /** A router side bridged to [shardCount] shards, each an interest-scoped hosted instance on its own host. */
    private inner class Mesh(seed: Long) {
        val controller = SimulationController(seed)
        val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(totalSlots, ::key, routerRegistry)
        val inbox = PullInbox()
        val nodes = mutableListOf<ShardNode>()

        init {
            routerBridgeHost.managementInlet.call.spawn(inbox)
            val built = List(shardCount) { i ->
                val reg = LocationRegistry()
                val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val host = ManagedHost(scheduler = controller.scheduler(), registry = reg)
                val interest = interestForShard(i)
                val ref = CellRef(logicalId, i.toLong())
                reg.setInterest(ref, interest) // known before the mesh forms (disjoint shards form no gossip link)
                val replication = Replication(reg)
                val shard = ShardCell<String>(ref, { key(it) }, interest)
                replication.replicate(shard, host) // spawns + tracks; the promotable (rebind) path
                Peering.loopback(routerSide, Peering.Side(reg, bridgeHost)) // mirror to the router side
                val replyProxy = (HostedCellProxy.create(inbox.ref, reg, InboxRoute::class.java) as InboxRoute).inlet.call
                ShardNode(shard, reg, host, replication, replyProxy)
            }
            controller.runToIdle() // let announcements reach the router registry and the inbox reach the shards
            built.forEach { node ->
                router.addShard(node.cell, node.cell.interest)
                node.cell.outlet.subscribe(Use.fixed(node.replyProxy, inbox.inlet.ref)) // reverse pull path
                nodes += node
            }
            controller.runToIdle()
        }

        fun quiesce() = controller.runToIdle()

        /** Fire a full scatter-gather pull and return the legs once they have crossed the bridges. */
        fun pullAll(): List<PullReply<String>> {
            router.pull(inbox.inlet.ref, Interest.Total) { null }
            controller.runToIdle()
            return inbox.drain()
        }

        /**
         * Shadow → judge → promote shard [i]'s cell. The candidate reuses the
         * incumbent's ref (the mechanism) or, per [mode], forks that identity —
         * the two controls. [staleFor] supplies the pre-repartition (interest,
         * snapshot) used by the no-rebind-authority control.
         */
        fun promote(i: Int, mode: Mode, staleFor: ((Int) -> Pair<Interest, java.io.Serializable>)? = null) {
            val node = nodes[i]
            val incumbent = node.cell
            val ref = incumbent.ref

            // A real judge over a filled observation window: the shadow observed one
            // clean wave, so the verdict is Accept (spec 53 "Judgment is declarative policy").
            val judge = PromotionJudge(
                PromotionPolicy(gates = listOf("glitch-free", "convergence"), window = ObservationWindow(1), judge = "judge"),
            ).also { it.observeCandidateWave() }

            when (mode) {
                Mode.REUSE_REF -> {
                    val candidate = ShardCell<String>(ref, { key(it) }, incumbent.interest)
                    Promotion.promoteReplica(node.host, node.replication, incumbent, candidate, "outlet", judge)
                    router.rebindShard(candidate) // follow the reused ref (the minimal router repoint)
                    node.cell = candidate
                    candidate.outlet.subscribe(Use.fixed(node.replyProxy, inbox.inlet.ref)) // re-arm the reverse pull path
                }
                Mode.FRESH_REF -> {
                    // control (a): a distinct instanceId — the router's route proxy stays bound
                    // to the retired ref, so this shard's post-promotion writes orphan.
                    val candidate = ShardCell<String>(CellRef(logicalId, 100L + i), { key(it) }, incumbent.interest)
                    candidate.restore(incumbent.snapshot())
                    node.replication.replicate(candidate, node.host)
                    node.host.managementInlet.call.despawn(ref)
                    node.cell = candidate // the router still reads the retired incumbent — the orphan
                }
                Mode.NO_REBIND_AUTHORITY -> {
                    // control (b): promote racing a repartition WITHOUT re-running link-time
                    // authority — the candidate comes up under the STALE (pre-repartition)
                    // interest, still holding the range the flip moved away.
                    val (staleInterest, staleSnap) = checkNotNull(staleFor)(i)
                    val candidate = ShardCell<String>(ref, { key(it) }, staleInterest)
                    candidate.restore(staleSnap)
                    // rebind with carryTagState = false: keep the candidate's stale restored state
                    // (do NOT re-restore the incumbent's current, post-shed snapshot).
                    node.replication.rebind(incumbent, candidate, node.host, carryTagState = false)
                    router.rebindShard(candidate)
                    node.cell = candidate
                    candidate.outlet.subscribe(Use.fixed(node.replyProxy, inbox.inlet.ref))
                }
            }
        }
    }

    /** Scatter-gather board: sum per group across shards; a group forked across two shards double-counts. */
    private fun boardOf(memberships: List<Set<String>>): Map<String, Long> {
        val board = mutableMapOf<String, Long>()
        memberships.forEach { m -> m.groupBy { key(it) }.forEach { (k, es) -> board.merge(k, es.sumOf { amount(it) }, Long::plus) } }
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

    /** One end-to-end roll: promote shard 0, interleave a repartition, roll to shard 1 — writers throughout. */
    private data class Run(
        val board: Map<String, Long>,
        val batch: Map<String, Long>,
        val disjoint: Boolean,
        val pullUnion: Set<String>,
        val surfacedUndelivered: Boolean,
    )

    private fun runRoll(seed: Long, mode: Mode): Run {
        val mesh = Mesh(seed)
        val input = Input()
        val rnd = Random(seed)
        var surfacedUndelivered = false

        // pre-repartition (interest, snapshot) per shard — only the no-rebind-authority control reads it.
        var stale: Map<Int, Pair<Interest, java.io.Serializable>> = emptyMap()

        fun tick() {
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 7 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }

        /** A settlement-gated checkpoint read: every element a pull leg returns must be live now. */
        fun checkpointPull() {
            mesh.quiesce()
            mesh.pullAll().forEach { leg -> leg.delta.adds.keys.forEach { if (it !in input.liveSet()) surfacedUndelivered = true } }
        }

        // Control (a) targets the FIRST promotion (shard 0, pre-repartition — the orphan);
        // control (b) targets the SECOND (shard 1, racing the repartition — the fork).
        val promote0Mode = if (mode == Mode.FRESH_REF) Mode.FRESH_REF else Mode.REUSE_REF
        val promote1Mode = if (mode == Mode.NO_REBIND_AUTHORITY) Mode.NO_REBIND_AUTHORITY else Mode.REUSE_REF

        // warm up — all three shards serving.
        repeat(12) { tick() }

        // shadow → judge → promote shard 0 while shards 1–2 serve.
        mesh.quiesce()
        mesh.promote(0, promote0Mode)
        checkpointPull()
        repeat(7) { tick() }

        // interleave ONE repartition (capture the pre-flip state first, for the fork control).
        mesh.quiesce()
        stale = mesh.nodes.indices.associateWith { i -> mesh.nodes[i].cell.interest to mesh.nodes[i].cell.snapshot() }
        mesh.router.repartition(rotatedInterests())
        mesh.quiesce()
        repeat(7) { tick() }

        // roll to shard 1. The fork control promotes it without re-running rebind authority
        // after the flip that moved shard 1's original range away.
        mesh.quiesce()
        mesh.promote(1, promote1Mode, staleFor = { i -> stale.getValue(i) })
        checkpointPull()
        repeat(12) { tick() }

        mesh.quiesce()
        val memberships = mesh.router.memberships()
        val finalPull = mesh.pullAll()
        finalPull.forEach { leg -> leg.delta.adds.keys.forEach { if (it !in input.liveSet()) surfacedUndelivered = true } }
        return Run(
            board = boardOf(memberships),
            batch = batch(input.liveSet()),
            disjoint = pairwiseDisjoint(memberships),
            pullUnion = finalPull.flatMapTo(mutableSetOf()) { it.delta.adds.keys },
            surfacedUndelivered = surfacedUndelivered,
        )
    }

    /** A run diverges from the fix iff the board tore, a key forked, the consumer surfaced an
     *  undelivered element, or the assembled pull union no longer covers the whole board. */
    private fun diverges(run: Run): Boolean =
        run.board != run.batch || !run.disjoint || run.surfacedUndelivered ||
            run.pullUnion.groupBy { key(it) }.keys != run.batch.keys

    @Test
    fun `rolling promotion shard by shard across a partitioned node converges and never tears - 100 seeds`() {
        for (seed in 0L until 100L) {
            val run = runRoll(seed, Mode.REUSE_REF)
            run.board shouldBe run.batch                                    // board converges to the batch group-by
            run.disjoint.shouldBeTrue()                                     // memberships pairwise disjoint (no double-count)
            (!run.surfacedUndelivered).shouldBeTrue()                       // the pull consumer never surfaced an undelivered element
            run.pullUnion.groupBy { key(it) }.keys shouldBe run.batch.keys  // assembled union covers the whole board
        }
    }

    @Test
    fun `control a - a fresh CellRef orphans the shard's routing entry and the board loses its range - diverges`() {
        var diverged = 0
        for (seed in 0L until 100L) if (diverges(runRoll(seed, Mode.FRESH_REF))) diverged++
        (diverged > 0).shouldBeTrue()
    }

    @Test
    fun `control b - promotion racing a repartition without rebind authority forks a moved key - diverges`() {
        var diverged = 0
        for (seed in 0L until 100L) if (diverges(runRoll(seed, Mode.NO_REBIND_AUTHORITY))) diverged++
        (diverged > 0).shouldBeTrue()
    }
}
