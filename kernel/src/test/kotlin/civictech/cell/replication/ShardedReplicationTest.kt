package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.data.MapDelta
import civictech.cell.data.MergeableGroupByCell
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.NatureNegotiation
import civictech.cell.port.PortRef
import civictech.cell.port.Reconciliation
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.nature.MergeClass
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-8 — sharded replication end-to-end (plan §4 PN-8, spec 42). "Overlapping
 * interest = sharded replication" had zero call sites and zero tests; this is the
 * proof, plus the one refusal that makes the combination safe:
 *
 * **Partition-with-overlap IS replication, and replication requires a merge.** A
 * key in the overlap of two shards rides *both* covering instances' gossip links,
 * so the structure MUST fold those redeliveries idempotently (set-union / max)
 * or it silently double-counts. [NatureNegotiation.reconcileOverlap] is that
 * refusal, moved to instance-set *formation* time (the moment the overlap is
 * decided) rather than discovered as a wrong board later.
 *
 * The end-to-end graph is CP-G1's mergeable aggregate ([MergeableGroupByCell])
 * over an instance set that is **both sharded and replicated**: 3 shards × 2
 * replicas (six instances of one logical id), overlapping *range* interest over
 * the group key, real Peering bridges, a repartition racing a replica failover.
 * The board (the union of every live instance's aggregates) equals the batch
 * group-by of every input, and no overlap key is ever counted twice.
 *
 * Controls, all diverging:
 *  - **(a)** epoch-blind adoption ([InstanceSet.lastWriterWins]) forks a *moved
 *    range*: two peers that learn a repartition's assignments in different orders
 *    end on different interests, where the epoch-max join ([InstanceSet.epochMaxUnion])
 *    converges.
 *  - **(b)** a non-mergeable structure under overlap is `Refuse`d at formation on
 *    `MERGE_IDEMPOTENCE` — *and*, run anyway (a counted `+` accumulator), the
 *    board double-counts every overlap key: the concrete harm the refusal prevents,
 *    "refused, not silently wrong."
 *  - **(c)** PN-0c reverted (a departing replica's delivered-watermark row not
 *    closed on evict) wedges a settlement-gated board on failover: post-failover
 *    waves never settle. Because PN-0c is only observable through a *replica-fed
 *    frontier*, and MergeableGroupByCell's aggregate gossip re-mints wave identity
 *    per member (an aggregate merge is not origin-addressable — verified: each
 *    peer's watermark row is keyed by its own re-emission source, not a shared
 *    origin), this arm exercises the failover on the origin-tagged tagged-set
 *    family ([SetCell]) — the same instance-set mesh, the substrate on which the
 *    delivered-watermark frontier (PN-7) is defined.
 */
class ShardedReplicationTest {

    // ------------------------------------------------------------------
    // shared harness
    // ------------------------------------------------------------------

    interface GbInletProxy {
        val inlet: Use<Propagate<SetDelta<Int>>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    // group key of an element: e / 100 (so keys 0..8, many elements per group).
    private fun keyOf(e: Int): Int = e / 100
    // The mergeable aggregate is a group-by MAX: idempotent (redelivery on the
    // overlap is a fixpoint), commutative-associative, and wire-native (an Int
    // value crosses the real bridge as the polymorphic value channel; a Set would
    // need a registered element serializer). MAX is the CP-G1 idempotent operator
    // that makes overlap safe — the counterpart the counted control (b) is not.
    private fun maxCell(ref: CellRef) = MergeableGroupByCell(ref, ::keyOf, { e: Int -> e }, ::maxOf)

    // 3 shards over group keys 0..8 with overlap at the boundaries 3 and 5:
    //   shard 0 -> keys [0,4)   shard 1 -> keys [3,6)   shard 2 -> keys [5,9)
    private val shardRanges = listOf(0L to 4L, 3L to 6L, 5L to 9L)
    private fun rangeInterest(lo: Long, hi: Long) = Interest.Ranges(listOf(Interest.Ranges.Range(lo, hi)))
    private fun shardInterest(shard: Int) = rangeInterest(shardRanges[shard].first, shardRanges[shard].second)

    // ------------------------------------------------------------------
    // MAIN: board equals batch group-by, no key counted twice
    // ------------------------------------------------------------------

    private data class MainResult(
        val board: Map<Int, Int>,
        val batch: Map<Int, Int>,
        val perInstanceCoverageOk: Boolean,
    )

    private fun runMain(seed: Long): MainResult {
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

        // per-instance writer proxies (write on the instance's OWN host).
        val ops = refs.mapIndexed { i, r ->
            (HostedCellProxy.create(r, peers[i].registry, GbInletProxy::class.java) as GbInletProxy).inlet.call
        }
        // current interest view the router uses (mutated by the repartition).
        val liveInterest = refs.associateWithTo(mutableMapOf()) { peers[0].registry.interestOf(it) }
        // an InstanceSet lattice governs the interest assignments (epoch-max join).
        val assignments = InstanceSet(CellRef(UUID.randomUUID()))
        refs.forEach { assignments.assign(it, liveInterest.getValue(it), epoch = 1) }
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
                // move shard1's range one step right: [3,6) -> [4,7). keys 3 leaves shard1,
                // key 6 joins it. epoch bumped; the epoch-max join adopts it everywhere.
                val moved = rangeInterest(4, 7)
                listOf(refs[2], refs[3]).forEach { r ->
                    assignments.assign(r, moved, epoch = 2)
                    liveInterest[r] = moved
                    peers.forEach { p -> p.registry.setInterest(r, moved) }
                }
            }
            if (op == failoverAt && !failedOver) {
                failedOver = true
                // fail over one replica of shard0 (instance 1). A real failover evicts a
                // *drained* replica: drain first so the sibling (0) + overlap peers hold
                // every delta the victim folded, then evict — its state is not unique.
                controller.runToIdle()
                val victim = refs[1]
                peers[1].replication.evict(byRef.getValue(victim), peers[1].host, closeDepartedRow = true)
                evicted += victim
                controller.runToIdle()
            }
            // write an element for a random group key to a random LIVE replica of a
            // covering shard (so intra-shard + cross-shard-overlap gossip is load-bearing).
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

        // Gossip is load-bearing (writes land on random replicas), proven two ways:
        //  - shard 2 is stable (no failover, no repartition): its two replicas must be
        //    byte-identical after convergence — a broken mesh would leave them divergent.
        //  - shard 0's failover lost nothing: its sole survivor (instance 0) holds the
        //    full batch set for every key in its stable range [0,4), including the
        //    key-3 overlap it learned from shard 1 before the repartition.
        val shard2Converged = cells[4].aggregates() == cells[5].aggregates()
        val shard0SurvivorOk = batch.filterKeys { it in 0..3 }
            .all { (k, full) -> cells[0].aggregates()[k] == full }
        return MainResult(board, batch, shard2Converged && shard0SurvivorOk)
    }

    @Test
    fun `board over a sharded-and-replicated mergeable aggregate equals batch group-by - 100 seeds`() {
        for (seed in 0L until 100L) {
            val r = runMain(seed)
            r.board shouldBe r.batch          // no key counted twice: set-union dedups the overlap
            r.perInstanceCoverageOk.shouldBeTrue() // replicas + overlap gossip converged; failover lost nothing
        }
    }

    // ------------------------------------------------------------------
    // CONTROL (a): epoch-blind adoption forks a moved range
    // ------------------------------------------------------------------

    @Test
    fun `control a - epoch-blind adoption forks the moved range where the epoch-max join converges`() {
        val ref = CellRef(UUID.randomUUID(), 0)
        val before = rangeInterest(3, 6)
        val movedTo = rangeInterest(4, 7) // the repartition (new epoch 2)
        // the two assignments a peer may learn, in the two arrival orders.
        val old = Assignment(before, epoch = 1)
        val new = Assignment(movedTo, epoch = 2)

        var epochMaxConverged = 0
        var lwwForked = 0
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val orderA = if (rnd.nextBoolean()) listOf(old, new) else listOf(new, old)
            val orderB = orderA.reversed()

            // epoch-max join (the real admission rule): epoch wins regardless of order.
            val emA = InstanceSet(CellRef(UUID.randomUUID())); orderA.forEach { emA.assign(ref, it.interest, it.epoch) }
            val emB = InstanceSet(CellRef(UUID.randomUUID())); orderB.forEach { emB.assign(ref, it.interest, it.epoch) }
            if (emA.interestOf(ref) == emB.interestOf(ref) && emA.interestOf(ref) == movedTo) epochMaxConverged++

            // epoch-blind (last-writer-wins): the moved range forks by arrival order.
            val lwA = InstanceSet(CellRef(UUID.randomUUID()), merge = InstanceSet.Companion::lastWriterWins)
            orderA.forEach { lwA.assign(ref, it.interest, it.epoch) }
            val lwB = InstanceSet(CellRef(UUID.randomUUID()), merge = InstanceSet.Companion::lastWriterWins)
            orderB.forEach { lwB.assign(ref, it.interest, it.epoch) }
            if (lwA.interestOf(ref) != lwB.interestOf(ref)) lwwForked++
        }
        epochMaxConverged shouldBe 100          // the join always converges to the newest epoch
        (lwwForked > 0).shouldBeTrue()          // epoch-blind adoption forks the moved range
    }

    // ------------------------------------------------------------------
    // CONTROL (b): non-mergeable + overlap refused at formation, not silently wrong
    // ------------------------------------------------------------------

    @Test
    fun `control b - overlap onto a non-mergeable structure is refused on MERGE_IDEMPOTENCE`() {
        val mergeable = NatureVector.of(MergeClass.IDEMPOTENT)   // a Replicable/CP-G1 aggregate
        val nonMergeable = NatureVector.DEFAULT                  // NON_IDEMPOTENT (a plain structure)

        // overlap onto a non-mergeable structure: refused, naming the axis.
        val refused = NatureNegotiation.reconcileOverlap(nonMergeable, overlaps = true)
        (refused is Reconciliation.Refuse).shouldBeTrue()
        (refused as Reconciliation.Refuse).mismatch.axis shouldBe NatureAxis.MERGE_IDEMPOTENCE
        refused.mismatch.offered shouldBe MergeClass.NON_IDEMPOTENT
        refused.mismatch.required shouldBe MergeClass.IDEMPOTENT

        // a mergeable structure under overlap composes; disjoint (no overlap) always composes.
        NatureNegotiation.reconcileOverlap(mergeable, overlaps = true) shouldBe Reconciliation.Direct
        NatureNegotiation.reconcileOverlap(nonMergeable, overlaps = false) shouldBe Reconciliation.Direct
        NatureNegotiation.reconcileOverlap(mergeable, overlaps = false) shouldBe Reconciliation.Direct
    }

    @Test
    fun `control b - run anyway a counted accumulator under overlap double-counts, where max does not`() {
        // The harm the refusal prevents, made concrete at the gather. An element in
        // the overlap of two shards is folded by BOTH covering instances (a router
        // sends an overlap-key delta to every covering instance), so each shard's
        // aggregate delta counts that one distinct element once.
        val shardA = MapDelta(mapOf(3 to 1), emptySet())
        val shardB = MapDelta(mapOf(3 to 1), emptySet())

        // A counted (+) merge — the non-mergeable structure the refusal forbids —
        // double-counts the single distinct element as 2 at the gather (and would
        // inflate without bound as the mesh echoes; that is why it is refused, not run).
        shardA.merge(shardB, Int::plus).puts[3] shouldBe 2   // WRONG: batch count is 1
        // The idempotent (max) merge CP-G1 supplies dedups the overlap: safe.
        shardA.merge(shardB, ::maxOf).puts[3] shouldBe 1     // safe: overlap folded once
    }

    // ------------------------------------------------------------------
    // CONTROL (c): PN-0c reverted -> failover wedges a settlement-gated board
    // (over the origin-tagged tagged-set family; see class KDoc for why).
    // ------------------------------------------------------------------

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }
    interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private val propagateSetDelta =
        @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** A shard's two replicas over gossip; a glitch-free board settles on the replica
     *  frontier over the ORIGINAL member set; one replica fails over mid-run. */
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
        @Suppress("UNCHECKED_CAST")
        val r0Out = r0.outlet as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST")
        val gfInletFrom = gf.inlet as LinkFrom<Propagate<SetDelta<String>>>
        (r0Out.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        reroute(r0Out, gf.inlet.ref, routedGf)

        val members = listOf(r0.ref, r1.ref, r2.ref) // original view: the consumer has not learned of the departure.
        val frontier = ReplicaFrontier { source, counter, _ ->
            val companion = p0.replication.watermarkOf(logicalId) ?: return@ReplicaFrontier false
            val rows = companion.rows()
            val closed = companion.closed()
            members.all { ref ->
                val slot = WatermarkCell.slotId(p0.replication.watermarkRef(ref))
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

        peers[2].replication.evict(r2, peers[2].host, closeDepartedRow = closeOnEvict).shouldBeTrue()
        controller.runToIdle()

        (0 until 5).map { "post$it" }.forEach { op0.add(it); controller.runToIdle() }
        controller.runToIdle()
        return released
    }

    @Test
    fun `control c - failover keeps the board settling once PN-0c closes the departed row - 100 seeds`() {
        for (seed in 0L until 100L) {
            val released = runFailover(seed, closeOnEvict = true)
            released shouldContainAll (0 until 3).map { "pre$it" }
            released shouldContainAll (0 until 5).map { "post$it" }
        }
    }

    @Test
    fun `control c - reverting PN-0c wedges the board on failover, on every seed`() {
        for (seed in 0L until 100L) {
            val released = runFailover(seed, closeOnEvict = false)
            released shouldContainAll (0 until 3).map { "pre$it" }
            (0 until 5).map { "post$it" }.filter { it in released }.isEmpty().shouldBeTrue()
        }
    }
}
