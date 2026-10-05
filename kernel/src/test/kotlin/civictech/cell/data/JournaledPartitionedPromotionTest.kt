package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.durability.InMemoryJournal
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.PromotionJudge
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.TypedCellFactory
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.graph
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.partition.PartitionedShardSet
import civictech.cell.partition.PullReply
import civictech.cell.partition.ShardCell
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * uwt8b.3 (feature computenet-uwt8b, decisions D3, D7..D9, D13): the
 * [PartitionedPromotionTest] mesh with **every shard journaled**. Each
 * [ShardCell] is spawned through its own [ApplyContext] (`replicated = true`,
 * `journalId`, its own [InMemoryJournal] carrying frames AND topology), promoted
 * by [ApplyContext.promoteReplica] to a version-marked candidate (which
 * checkpoints the journal and journals a `TopoEvent.Promote` as its COMMIT), and
 * rolled shard by shard. After shard 0's promotion and seven more ticks, shard 0
 * is **killed** (host, bridge host and [ApplyContext] discarded; only its
 * registry and journal survive), rebuilt, `recover`ed, re-peered, and the
 * router follows the recovered cell via [PartitionedShardSet.rebindShard]. The
 * run then repartitions, rolls shard 1, and finishes.
 *
 * **Oracles** (100 seeds): the scatter-gather board equals the batch group-by at
 * every checkpoint pull and at the end; memberships stay pairwise disjoint; the
 * settlement-gated pull consumer never surfaces an undelivered element;
 * shard 0's recovered live cell was built by the CANDIDATE factory (the journal
 * respawned the candidate, not the incumbent) and its replayed topology fold names
 * the candidate factory; shard 1 likewise after its own promotion.
 *
 * **Candidate class.** [ShardCell] is `open` for this test; the candidate is
 * [CandidateShardCell], and the oracle asserts the runtime class of the hosted
 * live cell held by the shard's [ApplyContext] (read reflectively from its cell
 * table) after the crash/recovery and after shard 1's promotion, alongside the
 * recovered fold's spawn factory class.
 *
 * **Handshake counts, honestly read.** With hand-partitioned [ShardCell]s there
 * is no composite membrane, so `[24-PART-01]`'s external-link guarantee (a
 * composite's external links never re-handshake across an internal swap) is NOT
 * exercised here; it is the composite requirement this shape does not prove. What
 * is checked is the hand-partitioned reading: the router-side external paths
 * (the router's ref-keyed route/assign proxies into every shard, and the shard
 * outlet -> inbox reverse pull) are counted by `onLink` hooks on the target
 * inlets (every shard inlet of every instance ever built, plus the inbox inlet);
 * the counts are identical before and after each promotion and after the crash
 * recovery, i.e. the router keeps resolving the reused ref without any link-level
 * handshake. Both paths are proxies / subscriptions rather than [civictech.cell.link.Link]s,
 * so this count is structurally low-information; the load-bearing check that the
 * reused ref keeps resolving is board == batch and control (a).
 *
 * **Controls** (must diverge on some seed, running through the same journaled mesh):
 *  - (a) `FRESH_REF` — shard 0's candidate gets a distinct ref; the router's
 *    ref-keyed route proxy orphans on the retired ref and the board loses the range.
 *  - (b) `NO_REBIND_AUTHORITY` — shard 1 is promoted after the repartition by a
 *    bare `Replication.rebind` carrying the stale pre-repartition state/interest,
 *    so a moved key forks across two shards.
 */
class JournaledPartitionedPromotionTest {

    private fun key(e: String): String = e.first().toString()
    private fun amount(e: String): Long = e.drop(1).toLong()

    private val shardCount = 3
    private val totalSlots = 12
    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    private fun interestForShard(i: Int): Interest = Interest.Slots.forShard(i, shardCount, totalSlots)

    private fun rotatedInterests(): List<Interest> = List(shardCount) { s -> interestForShard((s + 1) % shardCount) }

    private enum class Mode { REUSE_REF, FRESH_REF, NO_REBIND_AUTHORITY }

    /** What a factory built, per ref (refs are unique per run: a fresh logical id each mesh). */
    private class Built(val cell: ShardCell<String>, val version: Int)

    private companion object {
        val built = ConcurrentHashMap<CellRef, Built>()
        val handshakes = ConcurrentHashMap<CellRef, AtomicInteger>()
        fun shardKey(e: String): String = e.first().toString()
    }

    private fun countHandshakes(cell: ShardCell<String>) {
        val counter = handshakes.computeIfAbsent(cell.ref) { AtomicInteger() }
        listOf(cell.routeInlet.linking, cell.assignInlet.linking, cell.deltaInlet.linking).forEach { support ->
            support.onLink = { link ->
                counter.incrementAndGet()
                civictech.cell.link.LinkResult.Connected(link)
            }
        }
    }

    /** The version-marked candidate: a distinct runtime class (ShardCell is `open` for this, uwt8b.3). */
    private class CandidateShardCell(ref: CellRef, interest: Interest) :
        ShardCell<String>(ref, { shardKey(it) }, interest)

    /**
     * The live object [ApplyContext] holds for [ref] (its private cell table, read
     * reflectively): the hosted instance itself, independent of factory bookkeeping.
     */
    private fun liveCell(context: ApplyContext, ref: CellRef): Cell {
        val field = ApplyContext::class.java.getDeclaredField("cells").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(context) as Map<CellRef, Cell>).getValue(ref)
    }

    private class IncumbentShardFactory(private val interest: Interest) : TypedCellFactory<ShardCell<String>> {
        override fun create(ref: CellRef): ShardCell<String> =
            ShardCell<String>(ref, { shardKey(it) }, interest).also { built[ref] = Built(it, 1) }
    }

    private class CandidateShardFactory(private val interest: Interest) : TypedCellFactory<ShardCell<String>> {
        override fun create(ref: CellRef): ShardCell<String> =
            CandidateShardCell(ref, interest).also { built[ref] = Built(it, 2) }
    }

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

    private class PullInbox(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        private val collected = mutableListOf<PullReply<String>>()

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    val ctx = CurrentContext.get()
                    val frontier = ctx?.baseline ?: return
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

    /**
     * One shard's crash domain: only [registry] and [journal] survive a [rebuild];
     * the hosts, replication service and [ApplyContext] are discarded.
     */
    private class ShardNode(private val controller: SimulationController, val index: Int) {
        val registry = LocationRegistry()
        val journal = InMemoryJournal()
        lateinit var host: ManagedHost
            private set
        lateinit var side: Peering.Side
            private set
        lateinit var replication: Replication
            private set
        lateinit var context: ApplyContext
            private set
        lateinit var link: Peering.Loopback
        lateinit var replyProxy: Propagate<SetDelta<String>>
        lateinit var cell: ShardCell<String>
        private var hostScheduler: HostScheduler? = null
        private var bridgeScheduler: HostScheduler? = null

        init {
            rebuild()
        }

        fun rebuild() {
            hostScheduler?.shutdown()
            bridgeScheduler?.shutdown()
            val nextHostScheduler = controller.scheduler()
            val nextBridgeScheduler = controller.scheduler()
            lateinit var nextContext: ApplyContext
            val nextReplication = Replication(registry)
            val nextHost = ManagedHost(
                scheduler = nextHostScheduler,
                registry = registry,
                journalFor = { ref -> nextContext.journalFor(ref) },
            )
            nextContext = ApplyContext(
                host = nextHost,
                replication = nextReplication,
                journals = mapOf("j" to journal),
                topology = journal,
            )
            hostScheduler = nextHostScheduler
            bridgeScheduler = nextBridgeScheduler
            host = nextHost
            side = Peering.Side(registry, ManagedHost(scheduler = nextBridgeScheduler, registry = registry))
            replication = nextReplication
            context = nextContext
        }
    }

    private inner class Mesh(seed: Long) {
        val controller = SimulationController(seed)
        private val routerRegistry = LocationRegistry()
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(totalSlots, ::key, routerRegistry)
        val inbox = PullInbox()
        val nodes = List(shardCount) { ShardNode(controller, it) }
        private val inboxHandshakes = AtomicInteger()

        init {
            routerBridgeHost.managementInlet.call.spawn(inbox)
            inbox.inlet.linking.onLink = { link ->
                inboxHandshakes.incrementAndGet()
                civictech.cell.link.LinkResult.Connected(link)
            }
            nodes.forEachIndexed { i, node ->
                val ref = CellRef(logicalId, i.toLong())
                val interest = interestForShard(i)
                node.registry.setInterest(ref, interest)
                graph(node.context) {
                    spawn(
                        "shard",
                        IdentityBinding.Exact(ref),
                        replicated = true,
                        journalId = "j",
                        factory = IncumbentShardFactory(interest),
                    )
                }
                node.cell = built.getValue(ref).cell
                countHandshakes(node.cell)
                node.link = Peering.loopback(routerSide, node.side)
                node.replyProxy =
                    (HostedCellProxy.create(inbox.ref, node.registry, InboxRoute::class.java) as InboxRoute).inlet.call
            }
            controller.runToIdle()
            nodes.forEach { node ->
                router.addShard(node.cell, node.cell.interest)
                node.cell.outlet.subscribe(Use.fixed(node.replyProxy, inbox.inlet.ref))
            }
            controller.runToIdle()
        }

        fun quiesce() = controller.runToIdle()

        fun pullAll(): List<PullReply<String>> {
            router.pull(inbox.inlet.ref, Interest.Total) { null }
            controller.runToIdle()
            return inbox.drain()
        }

        /** Every external-path handshake counter, in a stable order, for before/after comparison. */
        fun handshakeCounts(): List<Int> =
            listOf(inboxHandshakes.get()) + nodes.map { handshakes[it.cell.ref]?.get() ?: 0 }

        private fun judge() = PromotionJudge(
            PromotionPolicy(gates = listOf("glitch-free", "convergence"), window = ObservationWindow(1), judge = "judge"),
        ).also { it.observeCandidateWave() }

        private fun adopt(node: ShardNode, cell: ShardCell<String>) {
            node.cell = cell
            router.rebindShard(cell)
            cell.outlet.subscribe(Use.fixed(node.replyProxy, inbox.inlet.ref)) // re-arm the reverse pull path
        }

        fun promote(i: Int, mode: Mode, staleFor: ((Int) -> Pair<Interest, Serializable>)? = null) {
            val node = nodes[i]
            val incumbent = node.cell
            val ref = incumbent.ref
            when (mode) {
                Mode.REUSE_REF -> {
                    node.context.promoteReplica(ref, CandidateShardFactory(incumbent.interest), judge = judge())
                    val candidate = built.getValue(ref).cell
                    countHandshakes(candidate)
                    adopt(node, candidate)
                }
                Mode.FRESH_REF -> {
                    val candidate = ShardCell<String>(CellRef(logicalId, 100L + i), { key(it) }, incumbent.interest)
                    candidate.restore(incumbent.snapshot())
                    node.context.bind(candidate.ref, node.journal)
                    node.replication.replicate(candidate, node.host)
                    node.host.managementInlet.call.despawn(ref)
                    node.cell = candidate // the router still reads the retired incumbent: the orphan
                }
                Mode.NO_REBIND_AUTHORITY -> {
                    val (staleInterest, staleSnap) = checkNotNull(staleFor)(i)
                    val candidate = ShardCell<String>(ref, { key(it) }, staleInterest)
                    candidate.restore(staleSnap)
                    node.replication.rebind(incumbent, candidate, node.host, carryTagState = false)
                    adopt(node, candidate)
                }
            }
        }

        /** kill -9 shard [i]: only registry + journal survive; rebuild, recover, re-peer, router follows. */
        fun crashAndRecover(i: Int) {
            val node = nodes[i]
            val ref = node.cell.ref
            node.link.partition()
            node.rebuild()
            val recovery = node.context.recover(node.journal)
            controller.runToIdle()
            recovery.awaitApplied(30_000)
            node.link = Peering.loopback(routerSide, node.side)
            controller.runToIdle()
            val recovered = built.getValue(ref).cell
            countHandshakes(recovered)
            adopt(node, recovered)
            controller.runToIdle()
        }
    }

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

    private data class Run(
        val board: Map<String, Long>,
        val batch: Map<String, Long>,
        val disjoint: Boolean,
        val pullUnion: Set<String>,
        val surfacedUndelivered: Boolean,
        val checkpointsEqualBatch: Boolean,
        val shard0RecoveredVersion: Int?,
        val shard0RecoveredClass: Class<*>?,
        val shard1LiveClass: Class<*>?,
        val shard0RecoveredFoldFactory: Class<*>?,
        val shard1Version: Int?,
        val handshakesStable: Boolean,
    )

    private fun runRoll(seed: Long, mode: Mode, crashShard0: Boolean): Run {
        val mesh = Mesh(seed)
        val input = Input()
        val rnd = Random(seed)
        var surfacedUndelivered = false
        var checkpointsEqualBatch = true
        var handshakesStable = true
        var stale: Map<Int, Pair<Interest, Serializable>> = emptyMap()

        fun tick() {
            val e = domain[rnd.nextInt(domain.size)]
            val delta = if (rnd.nextInt(10) < 7 || e !in input.liveSet()) input.add(e) else input.remove(e)
            if (delta != null) mesh.router.route(delta)
            repeat(rnd.nextInt(3)) { mesh.controller.step() }
        }

        fun checkpointPull() {
            mesh.quiesce()
            mesh.pullAll().forEach { leg -> leg.delta.adds.keys.forEach { if (it !in input.liveSet()) surfacedUndelivered = true } }
            if (boardOf(mesh.router.memberships()) != batch(input.liveSet())) checkpointsEqualBatch = false
        }

        fun stableAcross(action: () -> Unit) {
            val before = mesh.handshakeCounts()
            action()
            // the shard cell instance changed (new counter slot appended for it); compare the
            // inbox counter and the per-ref counters, which are keyed by the (reused) ref.
            if (mesh.handshakeCounts() != before) handshakesStable = false
        }

        val promote0Mode = if (mode == Mode.FRESH_REF) Mode.FRESH_REF else Mode.REUSE_REF
        val promote1Mode = if (mode == Mode.NO_REBIND_AUTHORITY) Mode.NO_REBIND_AUTHORITY else Mode.REUSE_REF

        repeat(12) { tick() }

        mesh.quiesce()
        stableAcross { mesh.promote(0, promote0Mode) }
        checkpointPull()
        repeat(7) { tick() }

        var shard0Version: Int? = null
        var shard0Class: Class<*>? = null
        var shard0FoldFactory: Class<*>? = null
        if (crashShard0) {
            mesh.quiesce()
            stableAcross { mesh.crashAndRecover(0) }
            val ref = mesh.nodes[0].cell.ref
            shard0Version = built.getValue(ref).version
            shard0Class = liveCell(mesh.nodes[0].context, ref).javaClass
            shard0FoldFactory = mesh.nodes[0].context.live().spawns.getValue(ref).factory.javaClass
            checkpointPull()
            repeat(4) { tick() }
        }

        mesh.quiesce()
        stale = mesh.nodes.indices.associateWith { i -> mesh.nodes[i].cell.interest to mesh.nodes[i].cell.snapshot() }
        mesh.router.repartition(rotatedInterests())
        mesh.quiesce()
        repeat(7) { tick() }

        mesh.quiesce()
        stableAcross { mesh.promote(1, promote1Mode, staleFor = { i -> stale.getValue(i) }) }
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
            checkpointsEqualBatch = checkpointsEqualBatch,
            shard0RecoveredVersion = shard0Version,
            shard0RecoveredClass = shard0Class,
            shard1LiveClass = if (mode == Mode.REUSE_REF) mesh.nodes[1].cell.ref.let { liveCell(mesh.nodes[1].context, it).javaClass } else null,
            shard0RecoveredFoldFactory = shard0FoldFactory,
            shard1Version = built[mesh.nodes[1].cell.ref]?.version,
            handshakesStable = handshakesStable,
        )
    }

    private fun diverges(run: Run): Boolean =
        run.board != run.batch || !run.disjoint || run.surfacedUndelivered || !run.checkpointsEqualBatch ||
            run.pullUnion.groupBy { key(it) }.keys != run.batch.keys

    @Test
    fun `journaled shard-by-shard promotion survives a shard crash after its promotion - 100 seeds`() {
        for (seed in 0L until 100L) {
            val run = runRoll(seed, Mode.REUSE_REF, crashShard0 = true)
            run.board shouldBe run.batch
            run.checkpointsEqualBatch.shouldBeTrue()
            run.disjoint.shouldBeTrue()
            (!run.surfacedUndelivered).shouldBeTrue()
            run.pullUnion.groupBy { key(it) }.keys shouldBe run.batch.keys
            run.shard0RecoveredClass shouldBe CandidateShardCell::class.java // the hosted cell's runtime class
            run.shard1LiveClass shouldBe CandidateShardCell::class.java
            run.shard0RecoveredVersion shouldBe 2 // the journal respawned the candidate, not the incumbent
            run.shard0RecoveredFoldFactory shouldBe CandidateShardFactory::class.java
            run.shard1Version shouldBe 2
            run.handshakesStable.shouldBeTrue()
        }
    }

    @Test
    fun `control a - a fresh CellRef orphans the shard's routing entry on the journaled mesh - diverges`() {
        var diverged = 0
        for (seed in 0L until 100L) if (diverges(runRoll(seed, Mode.FRESH_REF, crashShard0 = false))) diverged++
        (diverged > 0).shouldBeTrue()
    }

    @Test
    fun `control b - promotion without rebind authority forks a moved key on the journaled mesh - diverges`() {
        var diverged = 0
        for (seed in 0L until 100L) if (diverges(runRoll(seed, Mode.NO_REBIND_AUTHORITY, crashShard0 = false))) diverged++
        (diverged > 0).shouldBeTrue()
    }
}
