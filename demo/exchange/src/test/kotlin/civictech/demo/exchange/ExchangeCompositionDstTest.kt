package civictech.demo.exchange

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.Interest
import civictech.cell.partition.PartitionedShardSet
import civictech.cell.partition.ShardCell
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.CrashFault
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphBuilder
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.LinkControl
import civictech.testkit.dst.LinkControls
import civictech.testkit.dst.PartitionFault
import civictech.testkit.dst.dstSweep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.WeakHashMap

/**
 * The rig-driven sibling of [ExchangeCompositionExitTest] ([CHA1], epic computenet-umx §8).
 *
 * [ExchangeCompositionExitTest] must keep passing UNTOUCHED on its own 100 seeds — this file
 * does not edit it and does not reuse its private scaffolding. What it drives instead is a
 * smaller graph built from the **same kernel primitives** the exit test composes — two
 * [Peering.loopback]-bridged peers ([F] wire) each holding a [Replication]-replicated order
 * [SetCell] ([B] replication), plus a separately journaled, crash-recovered "local writer"
 * [SetCell] ([D] durability), all routed into per-peer [PartitionedShardSet]s of [ShardCell]s
 * ([C] partitioned) — through the DST rig ([civictech.testkit.dst]) under a seeded [FaultPlan]
 * that includes, at minimum, a partition and a crash ([CHA1-10]..[CHA1-24]).
 *
 * ## Why the crash target is a local writer, not a replicated peer host
 *
 * The first version of this test crashed peer0's *replicated* order host directly (rebuild:
 * re-spawn the [SetCell] at the same [CellRef], re-[Replication.replicate] it). That is what
 * epic §2.1's sketch of [CrashFault] rebuild suggests, and it compiles and runs — but it does
 * not converge: on 9 of 10 measured seeds the two peers' order replicas never re-converge after
 * the crash. `Replication.replicate` re-links a fresh cell against replicas *the registry
 * already knows about*, and a crashed-and-rebuilt cell at the *same* instance id is neither the
 * plain "first replicate" case nor the "late joiner at a fresh id" case the exit test's own late
 * joiner exercises — cross-host gossip re-linking through a crash is not a property this graph
 * (or the exit test) establishes, and forcing it here would test something nobody has shown
 * holds, which is exactly what [CrashFault]'s own KDoc warns against ("a rebuild that derives
 * fresh refs produces a different graph after the crash, and the run that follows is testing
 * something nobody asked about").
 *
 * So the crash target here is a **plain, non-replicated, journaled writer cell**, fed directly
 * into peer0's shard mesh by a local outlet subscription (no [LocationRegistry] or cross-host
 * link involved in its recovery at all) — the same journal-replay mechanism
 * `computenet-umx.3.8`'s `ExclusiveBridgeGraph` relies on, applied to a role the exit test's own
 * KDoc already treats as durability's concern ("[D] journal: durable writer intake ... covered
 * by the real two-JVM `ExchangeScaffoldTest`"). Cross-peer replication (the harder,
 * *not*-yet-established property above) stays intact and unheld-crashed; the partition fault is
 * what exercises it here, exactly as in the exit test's own peer-partition/heal scenario.
 */
object ExchangeCompositionDstGraph {

    const val GRAPH_ID = "exchange-composition-dst"
    const val CHECK_ID = "exchange-composition-dst-check"
    const val PEER_EDGE = "peer0<->peer1"
    const val CRASH_HOST = "peer0-writer-host"
    const val JOURNAL = "peer0-writer-journal"

    private const val SHARD_COUNT = 2
    private const val TOTAL_SLOTS = 8
    private const val WRITE_HORIZON = 40

    private val ordersId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d57e")
    private val writerId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d57f")
    private val orderDomain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")
    private val writerDomain = listOf("a3", "b1", "c6", "d2", "e9", "f4", "g8", "h1")

    private fun region(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()
    private fun batch(live: Set<String>): Map<String, Long> =
        live.groupBy { region(it) }.mapValues { (_, es) -> es.sumOf { amount(it) } }

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** Read after quiescence by [check]. Declared once per run, resolved by [CellRef]-stable refs. */
    private class State {
        lateinit var o0: SetCell<String>
        lateinit var o1: SetCell<String>
        lateinit var writer0: SetCell<String>
        lateinit var board0: () -> Map<String, Long>
        lateinit var board1: () -> Map<String, Long>
    }

    private val states = WeakHashMap<DstWorld, State>()

    /** A one-shot per-peer shard mesh: not a crash target, so no rebuild function needed. */
    private class ShardMesh(world: DstWorld, registry: LocationRegistry) {
        private val routerBridgeHost = ManagedHost(scheduler = world.controller.scheduler(), registry = registry)
        private val routerSide = Peering.Side(registry, routerBridgeHost)
        private val logicalId: UUID = UUID.randomUUID()
        val router = PartitionedShardSet<String>(TOTAL_SLOTS, ::region, registry)

        init {
            val shards = List(SHARD_COUNT) { i ->
                val shardRegistry = LocationRegistry()
                val shardBridgeHost = ManagedHost(scheduler = world.controller.scheduler(), registry = shardRegistry)
                val shardHost = ManagedHost(scheduler = world.controller.scheduler(), registry = shardRegistry)
                val interest = Interest.Slots.forShard(i, SHARD_COUNT, TOTAL_SLOTS)
                val shard = ShardCell<String>(CellRef(logicalId, i.toLong()), ::region, interest, epochAware = true)
                shardHost.managementInlet.call.spawn(shard)
                Peering.loopback(routerSide, Peering.Side(shardRegistry, shardBridgeHost))
                shard to interest
            }
            world.controller.runToIdle()
            shards.forEach { (shard, interest) -> router.addShard(shard, interest) }
        }

        fun route(delta: SetDelta<String>) = router.route(delta)
        fun board(): Map<String, Long> {
            val board = mutableMapOf<String, Long>()
            router.memberships().forEach { m ->
                m.groupBy(::region).forEach { (k, es) -> board.merge(k, es.sumOf(::amount), Long::plus) }
            }
            return board
        }
    }

    val spec: GraphSpec = GraphSpec(GRAPH_ID, GraphBuilder { world -> build(world) })

    private fun build(world: DstWorld) {
        val state = State()
        states[world] = state

        val reg0 = LocationRegistry()
        val reg1 = LocationRegistry()

        val bridge0 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg0)
        val bridge1 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg1)
        val side0 = Peering.Side(reg0, bridge0)
        val side1 = Peering.Side(reg1, bridge1)
        val loop = Peering.loopback(side0, side1)

        world.edges.declare(PEER_EDGE)
        LinkControls.declare(world, PEER_EDGE, LinkControl.severing(loop))

        val journal = world.journals.declare(JOURNAL)
        val replication0 = Replication(reg0)
        val replication1 = Replication(reg1)

        val mesh0 = ShardMesh(world, reg0)
        val mesh1 = ShardMesh(world, reg1)
        state.board0 = mesh0::board
        state.board1 = mesh1::board

        // Both peers' replicated order hosts are plain: replication convergence (through the
        // partition fault) is what this graph exercises for [F]+[B], and it is not the crash
        // target — see the class KDoc for why.
        val host0 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg0)
        val o0 = SetCell<String>(CellRef(ordersId, 0))
        replication0.replicate(o0, host0)
        o0.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { mesh0.route(it) }, PortRef.generate()))
        state.o0 = o0

        val host1 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg1)
        val o1 = SetCell<String>(CellRef(ordersId, 1))
        replication1.replicate(o1, host1)
        o1.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { mesh1.route(it) }, PortRef.generate()))
        state.o1 = o1

        // The crash target: a local, non-replicated, journaled writer feeding peer0's shard
        // mesh directly by outlet subscription — no LocationRegistry/cross-host link is
        // involved in its recovery, so the rebuild function is deterministic in the sense
        // [CrashFault] requires without depending on an unestablished cross-host re-link
        // property ([CHA1-17]..[CHA1-19]).
        world.hosts.declare(CRASH_HOST) { ctx ->
            val host = ManagedHost(scheduler = ctx.scheduler, registry = reg0, journalFor = { journal })
            val writer0 = SetCell<String>(CellRef(writerId, 0))
            host.managementInlet.call.spawn(writer0)
            writer0.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { mesh0.route(it) }, PortRef.generate()))
            state.writer0 = writer0
            host
        }

        world.controller.runToIdle()

        val ops0 = (HostedCellProxy.create(CellRef(ordersId, 0), reg0, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val ops1 = (HostedCellProxy.create(CellRef(ordersId, 1), reg1, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val opsWriter = (HostedCellProxy.create(CellRef(writerId, 0), reg0, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val orderOps = listOf(ops0, ops1)

        // Workload as a step hook ([CHA1-02]): DstRun drives the whole budget itself, so
        // writes cannot be interleaved with manual controller.step() calls the way the exit
        // test interleaves them. Spreading writes over WRITE_HORIZON steps keeps the run
        // alive through the fault windows below instead of quiescing before they fire.
        val rnd = world.rng("workload")
        val liveOrders = mutableSetOf<String>()
        val liveWriter = mutableSetOf<String>()
        world.steps.onStep { _, step ->
            if (step < WRITE_HORIZON) {
                val e = orderDomain[rnd.nextInt(orderDomain.size)]
                val w = rnd.nextInt(orderOps.size)
                if (rnd.nextInt(10) < 6 || e !in liveOrders) {
                    orderOps[w].add(e)
                    liveOrders += e
                } else {
                    orderOps[w].remove(e)
                    liveOrders -= e
                }

                val we = writerDomain[rnd.nextInt(writerDomain.size)]
                if (rnd.nextInt(10) < 6 || we !in liveWriter) {
                    opsWriter.add(we)
                    liveWriter += we
                } else {
                    opsWriter.remove(we)
                    liveWriter -= we
                }
            }
        }
    }

    /**
     * The composed graph's invariants, after quiescence:
     *  - both peers' order replicas converge ([F]+[B], exercised by the partition fault);
     *  - peer1's board equals the batch over its converged order replica ([C]+[B]);
     *  - peer0's board equals the batch over its converged order replica UNION the crash-
     *    recovered writer's membership ([C]+[D], exercised by the crash fault: every write the
     *    writer accepted before the crash — applied or not — must survive it).
     *
     * This is the same shape [ExchangeCompositionExitTest]'s v1 test asserts, over a graph
     * driven through the adversarial rig instead of a bare controller.
     */
    fun check(): DstCheck = DstCheck { world ->
        val state = states[world] ?: error("no ExchangeCompositionDstGraph state declared for this world")
        val converged = state.o0.membership()
        val other = state.o1.membership()
        if (other != converged) throw ExchangeCompositionDiverged("order replicas", converged, other)

        val expected1 = batch(converged)
        val b1 = state.board1()
        if (b1 != expected1) throw ExchangeCompositionDiverged("peer1 board", expected1, b1)

        val expected0 = batch(converged + state.writer0.membership())
        val b0 = state.board0()
        if (b0 != expected0) throw ExchangeCompositionDiverged("peer0 board", expected0, b0)
    }

    /**
     * The adversary this test names: a partition on the peer-to-peer bridge (exercising
     * replication's convergence-after-heal, [CHA1-12]) and a mid-drain crash of peer0's local
     * writer host, recovering from its journal ([CHA1-17], [CHA1-19]).
     */
    fun plan(seed: Long): FaultPlan = FaultPlan.of(
        seed,
        PartitionFault.park("partition-peers", PEER_EDGE, from = 10, until = 22),
        CrashFault.midDrain("crash-peer0-writer", CRASH_HOST, atStep = 15, journal = JOURNAL),
    )
}

/**
 * Stable message per computenet-umx.3.11's AMENDS: the top-line [message] never embeds a
 * run-varying count or set, so [civictech.testkit.dst.PlanShrinker]'s same-failure predicate
 * (which compares outcome + message) is not defeated by a shrink that reduces how much diverged
 * without changing which invariant failed. The actual sets are in [detail], for a human reading
 * a rendered report, never compared by the shrinker.
 */
class ExchangeCompositionDiverged(
    val what: String,
    val expected: Any,
    val actual: Any,
) : AssertionError("exchange composition diverged: $what ([CHA1-64] rig-driven composition check)") {
    fun detail(): String = "expected=$expected actual=$actual"
}

/**
 * BS-style rig exercise of the composition exit test's graph shape: [CHA1-10]..[CHA1-24] driven
 * over [F] wire + [B] replication + [C] partitioned + [D] durability in one graph, via
 * [civictech.testkit.dst.DstRun] rather than a bare [civictech.cell.host.SimulationController]
 * drive. [ExchangeCompositionExitTest] is untouched and keeps passing on its own 100 seeds —
 * this is a new, smaller variant, not a replacement.
 *
 * ## Seed range: 10, not 100
 *
 * Each seed here builds two peers, two shard meshes, a peer-to-peer bridge and a journaled
 * writer (7 hosts, 2 peer-to-shard bridges each) and drives up to
 * [civictech.cell.host.SimulationController.DEFAULT_BUDGET] steps with a workload step hook —
 * several times heavier per seed than `ExchangeCompositionExitTest`'s single-shard-mesh v1
 * scenario. Ten seeds is enough to make the fault-fired assertion meaningful (every seed must
 * fire both faults) while keeping `:demo:exchange:test`'s wall-clock budget close to what it was
 * before this file existed; the exit test's own four 100-seed loops remain the deep seed
 * coverage for this graph shape.
 */
class ExchangeCompositionDstTest {

    @Test
    fun `composed exchange graph converges under a partition and a crash`() {
        val sweep = dstSweep(
            suite = "exchange-composition-dst",
            seeds = 0L..9L,
            graph = ExchangeCompositionDstGraph.spec,
            checkId = ExchangeCompositionDstGraph.CHECK_ID,
            artifactRoot = artifactRoot,
            planFor = ExchangeCompositionDstGraph::plan,
        )
        sweep.assertAllPassed()

        // A green sweep whose adversary never fired proves nothing (BS-13/BS-14's own lesson,
        // amplified on computenet-umx.3.8's review): assert both faults actually fired, on
        // every seed, not merely that the plan named them.
        val fired = sweep.entries.flatMap { it.report?.appliedFaults.orEmpty() }
            .filter { !it.inert }
            .map { it.id }
            .toSet()
        assertEquals(
            setOf("partition-peers", "crash-peer0-writer"),
            fired,
            "a green composition sweep whose adversary never fired proves nothing: ${sweep.summary()}",
        )

        val perSeedFired = sweep.entries.associate { entry ->
            entry.seed to entry.report?.appliedFaults.orEmpty().filter { !it.inert }.map { it.id }.toSet()
        }
        perSeedFired.forEach { (seed, ids) ->
            assertTrue(
                "partition-peers" in ids && "crash-peer0-writer" in ids,
                "seed $seed did not fire both faults (fired=$ids) — the plan applied to something inert",
            )
        }
    }

    companion object {
        private val artifactRoot = File("build/dst/exchange-composition")

        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(ExchangeCompositionDstGraph.spec)
            CheckRegistry.register(ExchangeCompositionDstGraph.CHECK_ID, ExchangeCompositionDstGraph.check())
            artifactRoot.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ExchangeCompositionDstGraph.GRAPH_ID)
            CheckRegistry.unregister(ExchangeCompositionDstGraph.CHECK_ID)
        }
    }
}

/**
 * The variant [ExchangeCompositionDstGraph]'s KDoc says it deliberately does NOT drive: peer0's
 * **replicated** order host is the crash target, rebuilt at the same [CellRef], while the
 * peer-to-peer bridge is partitioned across the crash window (computenet-h50w).
 *
 * Two peers, one [Peering.loopback] bridge, one [Replication]-replicated order [SetCell] each,
 * and nothing else: the shard meshes and the journaled local writer of the sibling graph are
 * omitted on purpose, because the property under test is exactly one edge — does a replica
 * crashed and rebuilt at the *same instance id* re-establish its outbound gossip link, so that
 * writes it accepts after the rebuild still reach its peer.
 *
 * Before the fix this diverged on 9 of 10 seeds: `Replication.linked` still held the DISCARDED
 * cell object under `(o0.ref, o1.ref)`, so [Replication]'s already-linked branch re-fired M10.1
 * catch-up through the dead cell's outlet and never installed a link from the rebuilt one.
 */
object ExchangeReplicatedCrashDstGraph {

    const val GRAPH_ID = "exchange-replicated-crash-dst"
    const val CHECK_ID = "exchange-replicated-crash-dst-check"
    const val PEER_EDGE = "peer0<->peer1"
    const val CRASH_HOST = "peer0-orders-host"
    const val JOURNAL = "peer0-orders-journal"

    private const val WRITE_HORIZON = 40

    private val ordersId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d580")
    private val orderDomain = listOf("a1", "a2", "b3", "b7", "c4", "d9", "e2", "f6", "g5", "h8", "a5", "c1", "e7")

    private class State {
        lateinit var o0: SetCell<String>
        lateinit var o1: SetCell<String>
    }

    private val states = WeakHashMap<DstWorld, State>()

    val spec: GraphSpec = GraphSpec(GRAPH_ID, GraphBuilder { world -> build(world) })

    private fun build(world: DstWorld) {
        val state = State()
        states[world] = state

        val reg0 = LocationRegistry()
        val reg1 = LocationRegistry()

        val bridge0 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg0)
        val bridge1 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg1)
        val loop = Peering.loopback(Peering.Side(reg0, bridge0), Peering.Side(reg1, bridge1))

        world.edges.declare(PEER_EDGE)
        LinkControls.declare(world, PEER_EDGE, LinkControl.severing(loop))

        val journal = world.journals.declare(JOURNAL)
        val replication0 = Replication(reg0)
        val replication1 = Replication(reg1)

        val host1 = ManagedHost(scheduler = world.controller.scheduler(), registry = reg1)
        val o1 = SetCell<String>(CellRef(ordersId, 1))
        replication1.replicate(o1, host1)
        state.o1 = o1

        // The crash target: peer0's REPLICATED order host. The rebuild re-spawns the same
        // logical replica at the same CellRef and re-replicates it — which is precisely the
        // "crash-and-rebuild at the same instance id" case Replication's M10.1 re-announce
        // KDoc claims to cover.
        world.hosts.declare(CRASH_HOST) { ctx ->
            val host = ManagedHost(scheduler = ctx.scheduler, registry = reg0, journalFor = { journal })
            val o0 = SetCell<String>(CellRef(ordersId, 0))
            replication0.replicate(o0, host)
            state.o0 = o0
            host
        }

        world.controller.runToIdle()

        val ops0 = (
            HostedCellProxy.create(CellRef(ordersId, 0), reg0, ExchangeCompositionDstGraph.SetInletProxy::class.java)
                as ExchangeCompositionDstGraph.SetInletProxy
            ).inlet.call
        val ops1 = (
            HostedCellProxy.create(CellRef(ordersId, 1), reg1, ExchangeCompositionDstGraph.SetInletProxy::class.java)
                as ExchangeCompositionDstGraph.SetInletProxy
            ).inlet.call
        val orderOps = listOf(ops0, ops1)

        val rnd = world.rng("workload")
        val live = mutableSetOf<String>()
        world.steps.onStep { _, step ->
            if (step < WRITE_HORIZON) {
                val e = orderDomain[rnd.nextInt(orderDomain.size)]
                val w = rnd.nextInt(orderOps.size)
                if (rnd.nextInt(10) < 6 || e !in live) {
                    orderOps[w].add(e)
                    live += e
                } else {
                    orderOps[w].remove(e)
                    live -= e
                }
            }
        }
    }

    /** The single invariant: the two order replicas converge after the crash and the heal. */
    fun check(): DstCheck = DstCheck { world ->
        val state = states[world] ?: error("no ExchangeReplicatedCrashDstGraph state declared for this world")
        val a = state.o0.membership()
        val b = state.o1.membership()
        if (a != b) throw ExchangeCompositionDiverged("replicated-crash order replicas", a, b)
    }

    /** Partition across the crash window, and a mid-drain crash of the replicated host. */
    fun plan(seed: Long): FaultPlan = FaultPlan.of(
        seed,
        PartitionFault.park("partition-peers", PEER_EDGE, from = 10, until = 22),
        CrashFault.midDrain("crash-peer0-orders", CRASH_HOST, atStep = 15, journal = JOURNAL),
    )
}

/**
 * computenet-h50w: a replicated host crashed mid-drain and rebuilt at the same instance id must
 * re-establish its outbound gossip link, so the two replicas still converge.
 *
 * This is the bug's named pin. It fails on the unfixed kernel — `Replication.linked` retains the
 * discarded cell under the rebuilt replica's `(local, remote)` key, so `maybeLink` re-fires
 * catch-up through the dead outlet instead of installing a link from the rebuilt cell — and the
 * failure is a real divergence of the two replicas, not a weakened assertion.
 */
class ExchangeReplicatedCrashDstTest {

    @Test
    fun `a replicated host crashed mid-drain re-links and converges`() {
        val sweep = dstSweep(
            suite = "exchange-replicated-crash-dst",
            seeds = 0L..9L,
            graph = ExchangeReplicatedCrashDstGraph.spec,
            checkId = ExchangeReplicatedCrashDstGraph.CHECK_ID,
            artifactRoot = artifactRoot,
            planFor = ExchangeReplicatedCrashDstGraph::plan,
        )
        sweep.assertAllPassed()

        val fired = sweep.entries.flatMap { it.report?.appliedFaults.orEmpty() }
            .filter { !it.inert }
            .map { it.id }
            .toSet()
        assertEquals(
            setOf("partition-peers", "crash-peer0-orders"),
            fired,
            "a green sweep whose adversary never fired proves nothing: ${sweep.summary()}",
        )
    }

    companion object {
        private val artifactRoot = File("build/dst/exchange-replicated-crash")

        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(ExchangeReplicatedCrashDstGraph.spec)
            CheckRegistry.register(ExchangeReplicatedCrashDstGraph.CHECK_ID, ExchangeReplicatedCrashDstGraph.check())
            artifactRoot.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ExchangeReplicatedCrashDstGraph.GRAPH_ID)
            CheckRegistry.unregister(ExchangeReplicatedCrashDstGraph.CHECK_ID)
        }
    }
}
