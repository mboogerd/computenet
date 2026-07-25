package civictech.demo.exchange

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.data.Aggregators
import civictech.cell.data.GroupByCell
import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.ShardCell
import civictech.cell.data.PartitionedShardSet
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.replication.Interest
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

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
            else ReplicaFrontier { _, _ -> true }
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
}
