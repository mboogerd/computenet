package civictech.demo.exchange

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.data.Aggregators
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.onEach
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.nature.manifestOf
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.link.Interest
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.nature.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.mapSet
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.MergeableGroupByCell
import civictech.cell.partition.ShardCell
import civictech.cell.partition.PartitionedShardSet

/**
 * CP-E2 — the composition **phase exit** ("the Agora for composition").
 *
 * One in-process graph composes ALL five Phase-1/Phase-2 natures over one
 * cross-peer-replicated order stream, deterministically over a
 * [SimulationController] (100 seeds), and asserts the composed board equals a
 * batch recompute on every seed through a **repartition**, a **shard
 * migration**, a **peer partition/recover**, and a **late joiner** — plus the
 * two mandated diverging controls.
 *
 * The five natures, in one graph, per peer:
 *  - **[F] wire**: two symmetric peers' order replicas gossip over a
 *    [Peering.loopback] bridge (the same M5 frame path a socket carries), and
 *    each peer's partitioned shards live on their own hosts reached over bridges.
 *  - **[B] replication**: the logical order OR-set is [Replication.replicate]d
 *    on both peers; writes on either converge (idempotent tag merge). This is
 *    the workaround for the known `GroupByCell`-not-`Replicable` gap — we
 *    replicate the shard's *input membership* (the OR-set), not its `MapDelta`
 *    aggregate, and each peer recomputes the aggregate from convergent input
 *    (GroupByCell's own doc: "peers recompute from their replicated inputs and
 *    converge with no aggregate-level gossip").
 *  - **[C] partitioned**: each peer routes its converged orders into a
 *    [PartitionedShardSet] whose [ShardCell] shards own disjoint region-slot
 *    ranges on different hosts; the scatter-gather union of shard ranges is the
 *    board, with the merge function never exercised (ranges disjoint).
 *  - **[A] glitch-free**: a [GlitchFreeCell] folds the per-region rows and the
 *    global total into one aligned board; the aligned board never shows
 *    total ≠ sum(rows), where a point-consistent sink does (control 1).
 *  - **[D] journal**: durable writer intake — covered by the real two-JVM
 *    `ExchangeScaffoldTest` (kill -9 + journal replay) and the solo journal
 *    test; here the peer partition/heal exercises anti-entropy recovery.
 *
 * Controls that MUST diverge: (1) a point-consistent sink shows a torn board on
 * some seed; (2) an epoch-blind repartition forks a moved region and diverges.
 *
 * Closes the 7 empty pairwise cells in one graph: A–B, A–C, A–F, A–O, C–B,
 * C–F, C–D.
 */
class ExchangeCompositionExitTest {

    // "a3" -> region 'a', amount 3 (the compact convention shared with the
    // proven kernel PartitionedShardsAcrossHostsTest; the HTTP app's Main.kt
    // uses the richer region/id/amount codec — this test exercises the
    // composition, not the wire codec).
    private fun region(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    private val ordersId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000e2e2")

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** A single symmetric peer: its own registry, a data host, a bridge host, a replicator. */
    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /**
     * One peer's partitioned board: a [PartitionedShardSet] whose [ShardCell]
     * shards live on their own hosts (bridged to the router), fed by this peer's
     * converged order replica. `memberships()` scatter-gathered is the board.
     */
    private class ShardMesh(
        controller: SimulationController,
        private val routerRegistry: LocationRegistry,
        val shardCount: Int,
        val totalSlots: Int,
        epochAware: Boolean,
        keyFn: (String) -> Any?,
    ) {
        private val routerBridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = routerRegistry)
        private val routerSide = Peering.Side(routerRegistry, routerBridgeHost)
        val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(totalSlots, keyFn, routerRegistry)

        init {
            val cells = List(shardCount) { i ->
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
            cells.forEach { (shard, interest) -> router.addShard(shard, interest) }
        }

        fun route(delta: SetDelta<String>) = router.route(delta)
        fun board(): Map<String, Long> = boardOf(router.memberships())
        fun migrate(i: Int) = routerRegistry.hold(CellRef(logicalId, i.toLong()))
        fun heal(i: Int) = routerRegistry.release(CellRef(logicalId, i.toLong()))
    }

    /** Scatter-gather: sum per region across shards. Disjoint ranges ⇒ no key collision. */
    companion object {
        fun boardOf(memberships: List<Set<String>>): Map<String, Long> {
            val board = mutableMapOf<String, Long>()
            memberships.forEach { m ->
                m.groupBy { it.first().toString() }
                    .forEach { (k, es) -> board.merge(k, es.sumOf { it.drop(1).toLong() }, Long::plus) }
            }
            return board
        }
    }

    private fun batch(live: Set<String>): Map<String, Long> =
        live.groupBy { region(it) }.mapValues { (_, es) -> es.sumOf { amount(it) } }

    private val domain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    // ------------------------------------------------------------------
    // v1: replicated orders → per-peer partitioned board == batch, 100 seeds
    // ------------------------------------------------------------------

    @Test
    fun `both peers' partitioned boards equal batch over cross-peer-replicated orders, 100 seeds`() {
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val rnd = Random(seed)
            val p0 = Peer(controller)
            val p1 = Peer(controller)
            Peering.loopback(p0.side, p1.side)

            // logical order OR-set, one replica per peer, gossiping over the bridge
            val o0 = SetCell<String>(CellRef(ordersId, 0)).also { p0.replication.replicate(it, p0.host) }
            val o1 = SetCell<String>(CellRef(ordersId, 1)).also { p1.replication.replicate(it, p1.host) }
            controller.runToIdle()

            // each peer routes ITS converged order replica into ITS own shard mesh
            val m0 = ShardMesh(controller, p0.registry, 3, 12, epochAware = true, keyFn = ::region)
            val m1 = ShardMesh(controller, p1.registry, 3, 12, epochAware = true, keyFn = ::region)
            o0.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { m0.route(it) }, PortRef.generate()))
            o1.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { m1.route(it) }, PortRef.generate()))

            val ops0 = (HostedCellProxy.create(o0.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
            val ops1 = (HostedCellProxy.create(o1.ref, p1.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
            val ops = listOf(ops0, ops1)
            val live = mutableSetOf<String>()

            repeat(60) {
                val e = domain[rnd.nextInt(domain.size)]
                val w = rnd.nextInt(2)
                if (rnd.nextInt(10) < 6 || e !in live) { ops[w].add(e); live += e } else { ops[w].remove(e); live -= e }
                repeat(rnd.nextInt(3)) { controller.step() }
            }
            controller.runToIdle()

            // truth = group-by over the CONVERGED order OR-set (the replicas'
            // own state, maintained independently of the shard routing). Both
            // replicas must converge, and both partitioned boards must equal
            // the batch group-by over that converged membership.
            assertEquals(o0.membership(), o1.membership(), "order replicas diverged on seed $seed")
            val expected = batch(o0.membership())
            assertEquals(expected, m0.board(), "peer0 board diverged on seed $seed")
            assertEquals(expected, m1.board(), "peer1 board diverged on seed $seed")
        }
    }

    // ------------------------------------------------------------------
    // The phase-gate scenario: the composed board equals batch THROUGH a
    // repartition, a shard migration, a peer partition/recover, and a late
    // joiner — driven on one deterministic controller.
    // ------------------------------------------------------------------

    /** Result of one scenario seed: the converged truth and each peer's partitioned board. */
    private data class Scenario(
        val converged: Set<String>,
        val allConverged: Boolean,
        val boards: List<Map<String, Long>>,
    )

    private fun rotate(shardCount: Int, totalSlots: Int): List<Interest> =
        List(shardCount) { s -> Interest.Slots.forShard((s + 1) % shardCount, shardCount, totalSlots) }

    /**
     * One seed of the full composition, exercised through all four scenarios.
     * [epochAware] toggles the shard interest-guard (the routing-epoch control).
     */
    private fun runScenario(seed: Long, epochAware: Boolean): Scenario {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val shardCount = 3
        val totalSlots = 12

        val p0 = Peer(controller)
        val p1 = Peer(controller)
        val loop01 = Peering.loopback(p0.side, p1.side)

        val o0 = SetCell<String>(CellRef(ordersId, 0)).also { p0.replication.replicate(it, p0.host) }
        val o1 = SetCell<String>(CellRef(ordersId, 1)).also { p1.replication.replicate(it, p1.host) }
        controller.runToIdle()

        val m0 = ShardMesh(controller, p0.registry, shardCount, totalSlots, epochAware, ::region)
        val m1 = ShardMesh(controller, p1.registry, shardCount, totalSlots, epochAware, ::region)
        o0.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { m0.route(it) }, PortRef.generate()))
        o1.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { m1.route(it) }, PortRef.generate()))

        val ops0 = (HostedCellProxy.create(o0.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val ops1 = (HostedCellProxy.create(o1.ref, p1.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val ops = mutableListOf(ops0, ops1)
        val live = mutableSetOf<String>()

        // the late joiner, wired in mid-run
        var o2: SetCell<String>? = null
        var m2: ShardMesh? = null

        fun tick() {
            val e = domain[rnd.nextInt(domain.size)]
            val w = rnd.nextInt(ops.size)
            if (rnd.nextInt(10) < 6 || e !in live) { ops[w].add(e); live += e } else { ops[w].remove(e); live -= e }
            repeat(rnd.nextInt(3)) { controller.step() }
        }

        repeat(20) { tick() }
        m0.migrate(1)                              // (2) shard 1 on peer0 begins migrating
        repeat(10) { tick() }
        m0.heal(1)                                 // migration completes; parked deliveries replay
        repeat(5) { tick() }

        // (3) peer partition/recover: the peer↔peer bridge drops (writes park),
        // then heals and re-syncs the full state via the ordinary catch-up path.
        loop01.partition()
        repeat(8) { tick() }
        loop01.heal()
        repeat(4) { tick() }

        // (1) repartition BOTH meshes (rotate ownership by one shard)
        m0.router.repartition(rotate(shardCount, totalSlots))
        m1.router.repartition(rotate(shardCount, totalSlots))
        repeat(10) { tick() }

        // (4) late joiner: peer2 joins the mesh mid-run. Its shards subscribe to
        // its order replica's outlet WHILE EMPTY, then it replicates — so the
        // anti-entropy catch-up of the full order set flows through the outlet
        // into the fresh shards as live deltas (a bare subscribe would miss the
        // pre-existing state; catchUpOnLinked only fires for the replica mesh).
        val late = Peer(controller)
        Peering.loopback(p0.side, late.side)
        Peering.loopback(p1.side, late.side)
        val ol = SetCell<String>(CellRef(ordersId, 2))
        val ml = ShardMesh(controller, late.registry, shardCount, totalSlots, epochAware, ::region)
        ol.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { ml.route(it) }, PortRef.generate()))
        late.replication.replicate(ol, late.host)  // links form → catch-up deltas flow into ml
        controller.runToIdle()
        ops += (HostedCellProxy.create(ol.ref, late.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        o2 = ol; m2 = ml
        repeat(15) { tick() }

        controller.runToIdle()

        val converged = o0.membership()
        val allConverged = o1.membership() == converged && o2!!.membership() == converged
        return Scenario(converged, allConverged, listOf(m0.board(), m1.board(), m2!!.board()))
    }

    @Test
    fun `composed board equals batch through repartition, migration, peer recover, and a late joiner - 100 seeds`() {
        for (seed in 0L until 100L) {
            val s = runScenario(seed, epochAware = true)
            assertTrue(s.allConverged, "order replicas (incl. late joiner) diverged on seed $seed")
            val expected = batch(s.converged)
            s.boards.forEachIndexed { i, b -> assertEquals(expected, b, "board $i diverged on seed $seed") }
        }
    }

    @Test
    fun `control - an epoch-blind repartition forks a region and diverges on some seed`() {
        var diverged = 0
        for (seed in 0L until 60L) {
            val s = runScenario(seed, epochAware = false)
            val expected = batch(s.converged)
            if (s.boards.any { it != expected }) diverged++
        }
        // the routing-epoch guard is what keeps the flip clean; without it a moved
        // region forks across two shards and the scatter-gather double-counts.
        assertTrue(diverged > 0, "epoch-blind control never diverged — the flip moved no live region")
    }

    // ------------------------------------------------------------------
    // [A] the glitch-free aligned board, over replicated inputs. A
    // GlitchFreeCell folds one arm from EACH peer's order replica into one
    // board, gated by the cross-replica settlement frontier (CP-B3): a board
    // row never surfaces an order some replica-set member has not yet
    // delivered. The point-consistent control (frontier off) tears.
    // ------------------------------------------------------------------

    interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private val propagateSetDelta =
        @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    /** The origin add/del tags a released [SetDelta] depends on. */
    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    /** One released board update: the order it names, and whether every replica-set member had delivered it. */
    private data class BoardObs(val order: String, val allMembersDelivered: Boolean)

    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    private fun runGlitchFreeBoard(seed: Long, replicaFrontierOn: Boolean): List<BoardObs> {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val p0 = Peer(controller)
        val p1 = Peer(controller)
        Peering.loopback(p0.side, p1.side)
        val logicalId = ordersId

        val a = SetCell<String>(CellRef(logicalId, 0)).also { p0.replication.replicate(it, p0.host) }
        val b = SetCell<String>(CellRef(logicalId, 1)).also { p1.replication.replicate(it, p1.host) }
        controller.runToIdle()

        // the glitch-free board lives on peer0, folding one arm from each replica
        val board = GlitchFreeCell(propagateSetDelta)
        p0.host.managementInlet.call.spawn(board)
        val routedBoard = p0.host.lookup<SetDeltaInletProxy>(board.ref)!!.inlet.call

        @Suppress("UNCHECKED_CAST") val aOut = PortRegistry.of(a)["outlet"] as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST") val bOut = PortRegistry.of(b)["outlet"] as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST") val boardFrom = board.inlet as LinkFrom<Propagate<SetDelta<String>>>
        assertTrue(aOut.linkTo(boardFrom) is LinkResult.Connected)
        assertTrue(bOut.linkTo(boardFrom) is LinkResult.Connected)
        reroute(aOut, board.inlet.ref, routedBoard)
        reroute(bOut, board.inlet.ref, routedBoard)

        val rows = mutableMapOf<String, Long>()
        val observations = mutableListOf<BoardObs>()
        board.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            val order = delta.adds.keys.firstOrNull() ?: return@Propagate
            delta.adds.keys.forEach { rows.merge(region(it), amount(it), Long::plus) }
            val members = p0.registry.replicasOf(logicalId).map { it.instanceId }
            val byInstance = mapOf(0L to a, 1L to b)
            observations += BoardObs(order, members.all { order in (byInstance[it]?.membership() ?: emptySet()) })
        }, PortRef.generate()))

        val frontier: ReplicaFrontier =
            if (replicaFrontierOn) p0.replication.replicaFrontier(logicalId)
            else ReplicaFrontier { _, _, _ -> true }
        board.useReplicaFrontier(frontier, originTags)
        p0.replication.onWatermarkAdvance(logicalId) { board.recheck() }

        val ops = listOf(
            (HostedCellProxy.create(a.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
            (HostedCellProxy.create(b.ref, p1.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
        )
        val orders = listOf("a1", "b2", "c3", "d4", "e5", "f6", "g7", "h8", "a9", "b1", "c2", "d3")
        for (op in 1..24) {
            ops[rnd.nextInt(2)].add(orders[op % orders.size])
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        return observations
    }

    @Test
    fun `glitch-free board never surfaces an order a replica has not delivered - 100 seeds`() {
        for (seed in 0L until 100L) {
            val obs = runGlitchFreeBoard(seed, replicaFrontierOn = true)
            assertTrue(obs.isNotEmpty(), "no board output on seed $seed")
            obs.forEach { assertTrue(it.allMembersDelivered, "torn board on seed $seed: ${it.order} surfaced early") }
        }
    }

    @Test
    fun `control - a point-consistent board tears (surfaces an undelivered order) on some seed`() {
        var torn = 0
        for (seed in 0L until 100L) {
            val obs = runGlitchFreeBoard(seed, replicaFrontierOn = false)
            if (obs.any { !it.allMembersDelivered }) torn++
        }
        assertTrue(torn > 0, "point-consistent control never tore — tune the interleaving")
    }

    // ==================================================================
    // PN-15 — the evidence join. The per-node combinations, brought into the
    // one graph that IS the composition evidence, so the run's claim is not
    // unit-test-deep. Three arms, one manifest assertion, three diverging
    // controls (torn / stall / wedge — one per failure mode of one arm).
    // ==================================================================

    // ------------------------------------------------------------------
    // [C×B] the sharded-AND-replicated board — Phase 3's payoff (PN-8), now in
    // the evidence graph. A CP-G1 mergeable aggregate ([MergeableGroupByCell])
    // over an instance set that is BOTH sharded and replicated: 3 shards × 2
    // replicas of one logical id, overlapping *range* interest over the group
    // key, over real Peering bridges, a repartition racing a replica failover.
    // The board (the idempotent gather over every live instance's aggregate)
    // equals the batch group-by, and no overlap key is counted twice — the
    // idempotent (MAX) merge is exactly what makes the overlap = replication.
    // ------------------------------------------------------------------

    interface GbInletProxy {
        val inlet: Use<Propagate<SetDelta<Int>>>
    }

    // group key of an element: e / 100 (keys 0..8, many elements per group).
    private fun keyOf(e: Int): Int = e / 100

    // MAX: the CP-G1 idempotent operator that makes overlap safe (redelivery on
    // the overlap is a fixpoint), commutative-associative, and wire-native.
    private fun maxCell(ref: CellRef) = MergeableGroupByCell(ref, ::keyOf, { e: Int -> e }, ::maxOf)

    // 3 shards over group keys 0..8 with overlap at the boundaries 3 and 5:
    //   shard 0 -> keys [0,4)   shard 1 -> keys [3,6)   shard 2 -> keys [5,9)
    private val shardRanges = listOf(0L to 4L, 3L to 6L, 5L to 9L)
    private fun rangeInterest(lo: Long, hi: Long) = Interest.Ranges(listOf(Interest.Ranges.Range(lo, hi)))
    private fun shardInterest(shard: Int) = rangeInterest(shardRanges[shard].first, shardRanges[shard].second)

    private data class ShardedReplicatedResult(
        val board: Map<Int, Int>,
        val batch: Map<Int, Int>,
        val coverageOk: Boolean,
    )

    private fun runShardedReplicatedBoard(seed: Long): ShardedReplicatedResult {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        // 6 peers: peer p hosts instance p. instances 0,1 = shard0 replicas; 2,3 = shard1; 4,5 = shard2.
        val peers = List(6) { Peer(controller) }
        for (i in peers.indices) for (j in i + 1 until peers.size) Peering.loopback(peers[i].side, peers[j].side)
        val logicalId = UUID.randomUUID()
        val refs = List(6) { CellRef(logicalId, it.toLong()) }
        val shardOf = { instanceId: Long -> (instanceId / 2).toInt() }

        // interests known in every registry before the mesh forms, so the gossip
        // linker gates links by overlap (disjoint shards form no link).
        peers.forEach { p -> refs.forEach { r -> p.registry.setInterest(r, shardInterest(shardOf(r.instanceId))) } }
        val cells = refs.mapIndexed { i, r -> maxCell(r).also { peers[i].replication.replicate(it, peers[i].host) } }
        controller.runToIdle()
        val byRef = refs.zip(cells).toMap()

        val ops = refs.mapIndexed { i, r ->
            (HostedCellProxy.create(r, peers[i].registry, GbInletProxy::class.java) as GbInletProxy).inlet.call
        }
        val liveInterest = refs.associateWithTo(mutableMapOf()) { peers[0].registry.interestOf(it) }
        val evicted = mutableSetOf<CellRef>()

        val allElems = mutableListOf<Int>()
        var t = 0L
        var repartitioned = false
        var failedOver = false
        // the repartition and the failover fire at random steps, racing each other.
        val repartitionAt = 8 + rnd.nextInt(8)
        val failoverAt = 8 + rnd.nextInt(8)

        for (op in 0 until 40) {
            if (op == repartitionAt && !repartitioned) {
                repartitioned = true
                // move shard1's range one step right: [3,6) -> [4,7). key 3 leaves shard1,
                // key 6 joins it; the live interest is re-set in every registry.
                val moved = rangeInterest(4, 7)
                listOf(refs[2], refs[3]).forEach { r ->
                    liveInterest[r] = moved
                    peers.forEach { p -> p.registry.setInterest(r, moved) }
                }
            }
            if (op == failoverAt && !failedOver) {
                failedOver = true
                // fail over one replica of shard0 (instance 1): drain first, then evict —
                // its sibling (0) + overlap peers hold every delta it folded, so its
                // state is not unique. The clean departure closes its watermark row.
                controller.runToIdle()
                val victim = refs[1]
                peers[1].replication.evict(byRef.getValue(victim), peers[1].host, closeDepartedRow = true)
                evicted += victim
                controller.runToIdle()
            }
            // write to a random LIVE replica of a covering shard (intra-shard +
            // cross-shard-overlap gossip both load-bearing).
            val key = rnd.nextInt(9)
            val e = key * 100 + op
            val covering = refs.filter { it !in evicted && liveInterest.getValue(it).admits(key) }
            if (covering.isNotEmpty()) {
                val target = covering[rnd.nextInt(covering.size)]
                ops[refs.indexOf(target)].propagate(SetDelta(adds = mapOf(e to setOf(Timestamp(UUID(0, t), t)))))
                allElems += e
                t++
            }
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        // batch truth: group-by MAX over every written element.
        val batch = allElems.groupBy { keyOf(it) }.mapValues { (_, es) -> es.maxOrNull()!! }
        // board: the gather across shards — per-key MAX over every live instance's
        // aggregate. An overlap key folded by two shards contributes the same value
        // from each, so MAX's idempotence means it is never "counted twice".
        val board = mutableMapOf<Int, Int>()
        cells.filter { it.ref !in evicted }.forEach { c ->
            c.aggregates().forEach { (k, v) -> board.merge(k, v) { a, b -> maxOf(a, b) } }
        }
        // gossip is load-bearing: shard2 (stable) replicas converge byte-identical,
        // and shard0's failover survivor holds the full batch set for its stable range.
        val shard2Converged = cells[4].aggregates() == cells[5].aggregates()
        val shard0SurvivorOk = batch.filterKeys { it in 0..3 }
            .all { (k, full) -> cells[0].aggregates()[k] == full }
        return ShardedReplicatedResult(board, batch, shard2Converged && shard0SurvivorOk)
    }

    @Test
    fun `sharded-and-replicated board equals batch group-by through repartition and replica failover - 100 seeds`() {
        for (seed in 0L until 100L) {
            val r = runShardedReplicatedBoard(seed)
            assertEquals(r.batch, r.board, "sharded-replicated board diverged on seed $seed") // no key counted twice
            assertTrue(r.coverageOk, "replicas/overlap gossip or failover lost data on seed $seed")
        }
    }

    // ------------------------------------------------------------------
    // Control (wedge): a covering-quorum member evicted WITHOUT closing its
    // delivered-watermark row wedges a settlement-gated board on failover
    // (PN-7 / the reverted PN-0c). Post-failover waves never settle. Closing
    // the row on evict keeps the board settling. Over the origin-tagged
    // tagged-set family ([SetCell]) — the substrate the delivered-watermark
    // frontier is defined on (a mergeable aggregate's gossip re-mints wave
    // identity per member, so PN-0c is only observable on the tagged set).
    // ------------------------------------------------------------------

    // The delivered-watermark slot for a data ref, re-derived from the kernel's
    // stable naming (Replication.watermarkRef ∘ WatermarkCell.slotId): the
    // deriving fn is kernel-internal, but the naming is stable and slotId is public.
    private fun watermarkSlot(dataRef: CellRef): UUID =
        WatermarkCell.slotId(CellRef(UUID.nameUUIDFromBytes("watermark:${dataRef.id}".toByteArray()), dataRef.instanceId))

    private fun runFailover(seed: Long, closeOnEvict: Boolean): List<String> {
        val controller = SimulationController(seed)
        val peers = List(3) { Peer(controller) }
        for (i in peers.indices) for (j in i + 1 until peers.size) Peering.loopback(peers[i].side, peers[j].side)
        val logicalId = UUID.randomUUID()

        val r0 = SetCell<String>(CellRef(logicalId, 0)).also { peers[0].replication.replicate(it, peers[0].host) }
        val r1 = SetCell<String>(CellRef(logicalId, 1)).also { peers[1].replication.replicate(it, peers[1].host) }
        val r2 = SetCell<String>(CellRef(logicalId, 2)).also { peers[2].replication.replicate(it, peers[2].host) }
        controller.runToIdle()

        val p0 = peers[0]
        val gf = GlitchFreeCell(propagateSetDelta)
        p0.host.managementInlet.call.spawn(gf)
        val routedGf = p0.host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call
        @Suppress("UNCHECKED_CAST") val r0Out = r0.outlet as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST") val gfInletFrom = gf.inlet as LinkFrom<Propagate<SetDelta<String>>>
        assertTrue(r0Out.linkTo(gfInletFrom) is LinkResult.Connected)
        reroute(r0Out, gf.inlet.ref, routedGf)

        // original view: the consumer has not learned of the departure (membership
        // is only eventually consistent), so the settlement read still counts r2 —
        // whether it CONSTRAINS turns entirely on the closed-row marker.
        val members = listOf(r0.ref, r1.ref, r2.ref)
        val frontier = ReplicaFrontier { source, counter, _ ->
            val companion = p0.replication.watermarkOf(logicalId) ?: return@ReplicaFrontier false
            val rows = companion.rows()
            val closed = companion.closed()
            members.all { ref ->
                val slot = watermarkSlot(ref)
                slot in closed || (rows[slot]?.get(source) ?: Long.MIN_VALUE) >= counter
            }
        }
        gf.useReplicaFrontier(frontier, originTags)
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val released = mutableListOf<String>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            delta.adds.keys.forEach { released += it }
        }, PortRef.generate()))

        val op0 = (HostedCellProxy.create(r0.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val op1 = (HostedCellProxy.create(r1.ref, peers[1].registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val op2 = (HostedCellProxy.create(r2.ref, peers[2].registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        listOf(op0, op1, op2).forEachIndexed { i, op -> op.add("pre$i") }
        controller.runToIdle()

        peers[2].replication.evict(r2, peers[2].host, closeDepartedRow = closeOnEvict)
        controller.runToIdle()

        (0 until 5).map { "post$it" }.forEach { op0.add(it); controller.runToIdle() }
        controller.runToIdle()
        return released
    }

    @Test
    fun `failover keeps the settlement-gated board settling once the departed row is closed - 100 seeds`() {
        for (seed in 0L until 100L) {
            val released = runFailover(seed, closeOnEvict = true)
            assertTrue(released.containsAll((0 until 3).map { "pre$it" }), "pre-failover orders missing on seed $seed")
            assertTrue(released.containsAll((0 until 5).map { "post$it" }), "post-failover orders never settled on seed $seed")
        }
    }

    @Test
    fun `control - a covering-quorum member evicted without close wedges the board on failover - 100 seeds`() {
        for (seed in 0L until 100L) {
            val released = runFailover(seed, closeOnEvict = false)
            assertTrue(released.containsAll((0 until 3).map { "pre$it" }), "pre-failover orders missing on seed $seed")
            assertTrue(
                (0 until 5).map { "post$it" }.none { it in released },
                "expected a wedge on seed $seed but post-failover orders surfaced: $released",
            )
        }
    }

    // ------------------------------------------------------------------
    // [A×filter] the filtered arm (A–C), and control (stall). A glitch-free
    // board folds a passing arm (identity mapSet, always emits) and an
    // absorbing arm (a region filter). The final order is filtered on the
    // absorbing arm — so the board can only settle that wave if the filter's
    // CP-A3 absorb-ack crossed. A non-acking filter strands the wave: the board
    // stalls forever. This is the absorb-ack-suppressed → stall control.
    // ------------------------------------------------------------------

    /** A filter that forwards passing elements but NEVER acks a swallowed wave (the CP-A3 control). */
    private class NonAckingFilter(
        private val predicate: (String) -> Boolean,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { d ->
                val passed = SetDelta(d.adds.filterKeys(predicate), d.dels.filterKeys(predicate))
                if (passed.adds.isNotEmpty() || passed.dels.isNotEmpty()) outlet.call.propagate(passed)
                // deliberately no absorb-ack
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun outletOf(cell: Cell): FanOutlet<Propagate<SetDelta<String>>> =
        PortRegistry.of(cell)["outlet"] as FanOutlet<Propagate<SetDelta<String>>>

    /** Membership under the tag algebra: an element is live iff an add-tag is uncovered. */
    private fun tagFold(deltas: List<SetDelta<String>>): Set<String> {
        val all = deltas.fold(SetDelta<String>()) { acc, d -> acc.merge(d) }
        return all.adds.filter { (e, tags) -> (tags - (all.dels[e] ?: emptySet())).isNotEmpty() }.keys
    }

    private fun runFilteredArm(seed: Long, acking: Boolean): Set<String> {
        val controller = SimulationController(seed)
        val hostSrc = ManagedHost(scheduler = controller.scheduler())
        val hostMap = ManagedHost(scheduler = controller.scheduler())
        val hostFilter = ManagedHost(scheduler = controller.scheduler())
        val hostJoin = ManagedHost(scheduler = controller.scheduler())

        val source = SetCell<String>()
        val pass = FlatMapSetCell<String, String>(f = { listOf(it) }) // identity mapSet (always emits)
        val filter: Cell = if (acking) FilterCell<String>(predicate = { region(it) == "a" })
        else NonAckingFilter(predicate = { region(it) == "a" })
        val gf = GlitchFreeCell(propagateSetDelta)
        val observed = mutableListOf<SetDelta<String>>()
        val observer = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

            init {
                inlet.onEach { observed += it }
            }
        }

        hostSrc.managementInlet.call.spawn(source)
        hostMap.managementInlet.call.spawn(pass)
        hostFilter.managementInlet.call.spawn(filter)
        hostJoin.managementInlet.call.spawn(gf)
        hostJoin.managementInlet.call.spawn(observer)
        controller.runToIdle()

        // source fans to both arms through their own hosts (independent scheduling)
        val passInlet = hostMap.lookup<SetDeltaInletProxy>(pass.ref)!!.inlet.call
        val filterInlet = hostFilter.lookup<SetDeltaInletProxy>(filter.ref)!!.inlet.call
        source.outlet.subscribe(Use.fixed(passInlet, PortRef.generate()))
        source.outlet.subscribe(Use.fixed(filterInlet, PortRef.generate()))

        // both arms link into the glitch-free join, delivery routed over hostJoin
        val passOutlet = outletOf(pass)
        val filterOutlet = outletOf(filter)
        passOutlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        filterOutlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        val routedGf = hostJoin.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call
        passOutlet.unsubscribe(gf.inlet.ref)
        passOutlet.subscribe(Use.fixed(routedGf, gf.inlet.ref))
        filterOutlet.unsubscribe(gf.inlet.ref)
        filterOutlet.subscribe(Use.fixed(routedGf, gf.inlet.ref))
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))
        controller.runToIdle()

        val srcApi = hostSrc.lookup<SetInletProxy>(source.ref)!!.inlet.call
        val rnd = Random(seed)
        // interleaved passing writes, then a final non-"a" add absorbed by the filter arm
        listOf("a1", "a2", "a4").forEach { e ->
            srcApi.add(e)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        srcApi.add("z9") // final wave: real on the pass arm, absorbed on the filter arm
        controller.runToIdle()
        return tagFold(observed)
    }

    @Test
    fun `filtered arm settles the glitch-free board once the absorb-ack crosses - 100 seeds`() {
        for (seed in 0L until 100L) {
            val settled = runFilteredArm(seed, acking = true)
            assertTrue("z9" in settled, "the absorbed final wave never settled on seed $seed (settled=$settled)")
            assertEquals(setOf("a1", "a2", "a4", "z9"), settled, "unexpected membership on seed $seed")
        }
    }

    @Test
    fun `control - a suppressed absorb-ack stalls the filtered arm's board`() {
        for (seed in 0L until 20L) {
            val settled = runFilteredArm(seed, acking = false)
            // the filter arm never acks 'z9'; the board holds that wave forever
            assertFalse("z9" in settled, "expected a stall on seed $seed but z9 settled (settled=$settled)")
        }
    }

    // ------------------------------------------------------------------
    // The manifest assertion (PN-12): the evidence graph's composed natures,
    // read off the marker interfaces the cells implement — board {GLITCH_FREE},
    // writers {DURABLE}, union {REPLICATED}, shards {PARTITIONED}. The composed
    // graph's manifest is the union of its cells' manifests: the four
    // Phase-1/2/3 natures, all present in one graph.
    // ------------------------------------------------------------------

    @Test
    fun `the evidence graph's composed manifest carries all four per-node natures`() {
        // each role carries its declared nature (a cell may carry more — e.g. a
        // writer SetCell is DURABLE and REPLICATED; the assertion is presence).
        assertTrue(Manifest.GLITCH_FREE in manifestOf(GlitchFreeCell::class.java), "board is not GLITCH_FREE")
        assertTrue(Manifest.DURABLE in manifestOf(SetCell::class.java), "writers are not DURABLE")
        assertTrue(Manifest.REPLICATED in manifestOf(UnionSetCell::class.java), "union is not REPLICATED")
        assertTrue(Manifest.PARTITIONED in manifestOf(ShardCell::class.java), "shards are not PARTITIONED")

        val composed = manifestOf(GlitchFreeCell::class.java) +   // board
            manifestOf(SetCell::class.java) +                     // writers
            manifestOf(UnionSetCell::class.java) +                // union
            manifestOf(ShardCell::class.java) +                   // shards
            manifestOf(MergeableGroupByCell::class.java)          // sharded-replicated aggregate
        assertEquals(
            setOf(Manifest.GLITCH_FREE, Manifest.DURABLE, Manifest.REPLICATED, Manifest.PARTITIONED),
            composed,
            "composed manifest is not exactly the four per-node natures: $composed",
        )
    }
}
